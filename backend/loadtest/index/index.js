import http from 'k6/http';
import { sleep } from 'k6';
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';
import { Counter, Trend } from 'k6/metrics';
const users = new SharedArray('users', () => JSON.parse(open(__ENV.INPUT)));
const names = ['seat', 'available', 'ticket', 'concert', 'purchase'];
const durations = Object.fromEntries(names.map(n => [n, new Trend(`${n}_duration`, true)]));
const responses = new Counter('api_responses');
const invalid = new Counter('invalid_body');
export const options = {
  scenarios: __ENV.MODE === 'purchase' ? {
    purchase: { executor: 'per-vu-iterations', vus: users.length, iterations: 1, maxDuration: '150s' },
  } : Object.fromEntries(['seat', 'available', 'ticket', 'concert'].map((n, i) => [n, {
    executor: 'constant-vus', vus: 40, duration: '45s', startTime: `${i * 50}s`, gracefulStop: '5s', exec: n,
  }])),
  summaryTrendStats: ['min', 'max', 'med', 'p(95)', 'p(99)'],
  thresholds: { invalid_body: ['count==0'] },
};
function request(name, path) {
  const user = users[(exec.vu.idInTest - 1) % users.length];
  const res = http.get(`http://127.0.0.1:18080${path}`, {
    headers: { Authorization: `Bearer ${user.at}` }, timeout: '10s', tags: { name },
  });
  durations[name].add(res.timings.duration);
  responses.add(1, { api: name, status: String(res.status) });
  sleep(0.1);
}
export function seat() { request('seat', '/api/concerts/2/seats/availability'); }
export function available() { request('available', '/api/concerts/2/seats/available'); }
export function ticket() { request('ticket', '/api/tickets/me'); }
export function concert() { request('concert', '/api/concerts'); }
export function setup() { return { start: Date.now() + 5000 }; }
export default function (data) {
  const user = users[exec.vu.idInTest - 1];
  sleep(Math.max(0, data.start - Date.now()) / 1000);
  const res = http.post('http://127.0.0.1:18080/api/tickets', JSON.stringify({ seatIds: [user.id] }), {
    headers: { Authorization: `Bearer ${user.at}`, 'X-Entry-Token': user.entry,
      'Idempotency-Key': `index-${user.id}`, 'Content-Type': 'application/json' },
    timeout: '120s', tags: { name: 'purchase' }, responseCallback: http.expectedStatuses(201, 409),
  });
  durations.purchase.add(res.timings.duration);
  responses.add(1, { api: 'purchase', status: String(res.status), user: String(user.id) });
  if (res.status === 201) {
    let body;
    try { body = res.json(); } catch (_) { invalid.add(1); return; }
    invalid.add(Array.isArray(body) && body.length === 1 && body[0].seatId === user.id && body[0].userId === user.id ? 0 : 1);
  }
}
export function handleSummary(data) { return { [__ENV.SUMMARY]: JSON.stringify(data, null, 2) }; }
