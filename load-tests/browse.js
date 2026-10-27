// Anonymous browsing at a fixed request rate: the read-heavy traffic a food delivery app mostly sees.
//   docker run --rm -v "$PWD/load-tests:/scripts" -v "$PWD/load-tests/results:/results" \
//     -e BASE_URL=http://host.docker.internal:8080 -e RATE=500 grafana/k6:1.3.0 run /scripts/browse.js
import http from 'k6/http';
import { check } from 'k6';
import { API, nearbyPoint, restaurants, summaryWriter } from './lib.js';

const RATE = Number(__ENV.RATE || 200);

export const options = {
  scenarios: {
    browse: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: __ENV.DURATION || '30s',
      preAllocatedVUs: 100,
      maxVUs: 1000,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<200'],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  return { restaurantIds: restaurants(false).map((r) => r.id) };
}

export default function ({ restaurantIds }) {
  const pick = Math.random();
  const restaurantId = restaurantIds[Math.floor(Math.random() * restaurantIds.length)];
  let res;
  if (pick < 0.5) {
    // Restaurant search from a random spot within ~3 km: many different geohash cells, so a realistic hit rate
    const { lat, lng } = nearbyPoint(3);
    res = http.get(`${API}/restaurants?latitude=${lat}&longitude=${lng}`, { tags: { name: 'nearby' } });
  } else if (pick < 0.85) {
    res = http.get(`${API}/restaurants/${restaurantId}/menu`, { tags: { name: 'menu' } });
  } else {
    res = http.get(`${API}/restaurants/${restaurantId}/reviews/summary`, { tags: { name: 'review-summary' } });
  }
  check(res, { 'status 200': (r) => r.status === 200 });
}

export const handleSummary = summaryWriter('browse');
