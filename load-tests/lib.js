// Shared helpers for the Forkcast k6 load tests.
import http from 'k6/http';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.1.0/index.js';

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
export const API = `${BASE_URL}/api/v1`;

// Gwalior city centre, where the seeded restaurants are
export const CENTRE = { lat: 26.2124, lng: 78.1772 };

export const JSON_HEADERS = { 'Content-Type': 'application/json' };

/** A random point within roughly `km` of the centre, so requests spread over many geohash cache cells. */
export function nearbyPoint(km) {
  const dLat = ((Math.random() * 2 - 1) * km) / 111;
  const dLng = ((Math.random() * 2 - 1) * km) / (111 * Math.cos((CENTRE.lat * Math.PI) / 180));
  return { lat: (CENTRE.lat + dLat).toFixed(5), lng: (CENTRE.lng + dLng).toFixed(5) };
}

export function auth(token) {
  return { headers: { ...JSON_HEADERS, Authorization: `Bearer ${token}` } };
}

/** Registers and logs in `count` fresh customers; returns their tokens. Passwords are random per run. */
export function createCustomers(count, prefix) {
  const run = Date.now().toString(36);
  const tokens = [];
  for (let i = 0; i < count; i++) {
    const email = `${prefix}-${run}-${i}@loadtest.example`;
    const password = `pw-${run}-${Math.random().toString(36).slice(2)}`;
    const registered = http.post(`${API}/auth/register`, JSON.stringify({ name: `Load Tester ${i}`, email, password }),
      { headers: JSON_HEADERS });
    if (registered.status !== 201 && registered.status !== 200) {
      throw new Error(`register failed: ${registered.status} ${registered.body}`);
    }
    const login = http.post(`${API}/auth/login`, JSON.stringify({ email, password }), { headers: JSON_HEADERS });
    tokens.push(login.json('accessToken'));
  }
  return tokens;
}

/** Restaurants near the centre, optionally only those open right now. */
export function restaurants(openNow) {
  const res = http.get(`${API}/restaurants?latitude=${CENTRE.lat}&longitude=${CENTRE.lng}&radiusKm=20${openNow ? '&openNow=true' : ''}`);
  return res.json('restaurants');
}

export function menuItems(restaurantId) {
  const menu = http.get(`${API}/restaurants/${restaurantId}/menu`).json();
  return menu.categories.flatMap((category) => category.items).filter((item) => item.available);
}

/** Writes a JSON summary to /results (mounted from load-tests/results) plus the usual text summary. */
export function summaryWriter(name) {
  return (data) => {
    const tag = `${name}-${__ENV.RATE || 'default'}rps`;
    return {
      [`/results/${tag}.json`]: JSON.stringify(data, null, 2),
      stdout: textSummary(data, { indent: ' ', enableColors: false }),
    };
  };
}
