// Checkout under load: customers add a dish to their cart and place an order, at a fixed rate of orders.
// Every 10th order is immediately sent again with the same Idempotency-Key, like a double tap, and must
// return the same order (200) rather than create a second one.
//   docker run --rm -v "$PWD/load-tests:/scripts" -v "$PWD/load-tests/results:/results" \
//     -e BASE_URL=http://host.docker.internal:8080 -e RATE=50 grafana/k6:1.3.0 run /scripts/checkout.js
import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { API, auth, createCustomers, menuItems, restaurants, summaryWriter } from './lib.js';

const RATE = Number(__ENV.RATE || 20);
const CUSTOMERS = Number(__ENV.CUSTOMERS || 200);

const ordersCreated = new Counter('orders_created');
const ordersReplayed = new Counter('orders_replayed');
const duplicateOrders = new Counter('duplicate_orders');

export const options = {
  setupTimeout: '5m',
  scenarios: {
    checkout: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: __ENV.DURATION || '30s',
      preAllocatedVUs: Math.min(50, CUSTOMERS),
      // One customer per VU, so a customer never has two checkouts in flight
      maxVUs: CUSTOMERS,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{name:place-order}': ['p(95)<300'],
    duplicate_orders: ['count==0'],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  // Only restaurants open now accept orders; customers order from where they are
  const open = restaurants(true).filter((r) => r.distanceKm < 3);
  if (open.length === 0) {
    throw new Error('No restaurant near the centre is open right now');
  }
  const dishes = open.flatMap((r) => menuItems(r.id).map((item) => ({ id: item.id, lat: r.latitude, lng: r.longitude })));
  return { tokens: createCustomers(CUSTOMERS, 'checkout'), dishes };
}

export default function ({ tokens, dishes }) {
  const token = tokens[(exec.vu.idInTest - 1) % tokens.length];
  const dish = dishes[Math.floor(Math.random() * dishes.length)];

  const cart = http.post(`${API}/cart/items?replace=true`, JSON.stringify({ menuItemId: dish.id, quantity: 1 }),
    { ...auth(token), tags: { name: 'add-to-cart' } });
  check(cart, { 'cart 200': (r) => r.status === 200 });

  const key = `${exec.vu.idInTest}-${exec.scenario.iterationInTest}-${Date.now()}`;
  const body = JSON.stringify({ deliveryAddress: { line: 'Load test', latitude: dish.lat, longitude: dish.lng } });
  const params = auth(token);
  params.headers['Idempotency-Key'] = key;
  const order = http.post(`${API}/orders`, body, { ...params, tags: { name: 'place-order' } });
  if (check(order, { 'order 201': (r) => r.status === 201 })) {
    ordersCreated.add(1);
  }

  if (exec.scenario.iterationInTest % 10 === 0) {
    const replay = http.post(`${API}/orders`, body, { ...params, tags: { name: 'replay-order' } });
    const sameOrder = replay.status === 200 && replay.json('id') === order.json('id');
    check(replay, { 'replay returns the same order': () => sameOrder });
    if (sameOrder) {
      ordersReplayed.add(1);
    } else if (replay.status === 201) {
      duplicateOrders.add(1);
    }
  }
}

export const handleSummary = summaryWriter('checkout');
