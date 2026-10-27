// Personal recommendations under load, for customers who already have an order history (the personalised
// path: taste profile from MongoDB, stored vectors from Redis, one KNN query). No Gemini calls are involved.
//   docker run --rm -v "$PWD/load-tests:/scripts" -v "$PWD/load-tests/results:/results" \
//     -e BASE_URL=http://host.docker.internal:8080 -e RATE=100 grafana/k6:1.3.0 run /scripts/recommendations.js
import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';
import { API, CENTRE, auth, createCustomers, menuItems, restaurants, summaryWriter } from './lib.js';

const RATE = Number(__ENV.RATE || 50);
const CUSTOMERS = Number(__ENV.CUSTOMERS || 50);
const ORDERS_EACH = 3;

export const options = {
  setupTimeout: '5m',
  scenarios: {
    recommendations: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: __ENV.DURATION || '30s',
      preAllocatedVUs: 50,
      maxVUs: 500,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{name:recommendations}': ['p(95)<300'],
    'checks{check:personalised}': ['rate>0.99'],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  const open = restaurants(true).filter((r) => r.distanceKm < 3);
  const dishes = open.flatMap((r) => menuItems(r.id).map((item) => ({ id: item.id, lat: r.latitude, lng: r.longitude })));
  const tokens = createCustomers(CUSTOMERS, 'recs');
  // Give every customer a few orders, so recommendations take the personalised path
  tokens.forEach((token, i) => {
    for (let n = 0; n < ORDERS_EACH; n++) {
      const dish = dishes[(i * ORDERS_EACH + n) % dishes.length];
      http.post(`${API}/cart/items?replace=true`, JSON.stringify({ menuItemId: dish.id, quantity: 1 }), auth(token));
      const params = auth(token);
      params.headers['Idempotency-Key'] = `setup-${i}-${n}-${Date.now()}`;
      http.post(`${API}/orders`, JSON.stringify({ deliveryAddress: { line: 'Load test', latitude: dish.lat, longitude: dish.lng } }), params);
    }
  });
  return { tokens };
}

export default function ({ tokens }) {
  const token = tokens[exec.scenario.iterationInTest % tokens.length];
  const res = http.get(`${API}/recommendations?latitude=${CENTRE.lat}&longitude=${CENTRE.lng}&radiusKm=20`,
    { ...auth(token), tags: { name: 'recommendations' } });
  check(res, {
    'status 200': (r) => r.status === 200,
    personalised: (r) => r.status === 200 && r.json('strategy') === 'PERSONALIZED',
  });
}

export const handleSummary = summaryWriter('recommendations');
