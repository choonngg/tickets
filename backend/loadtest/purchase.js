import http from 'k6/http';
import { sleep } from 'k6';
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';
import { Counter, Trend } from 'k6/metrics';

const users = new SharedArray('prepared users', () => JSON.parse(open(__ENV.INPUT)));
const responses = new Counter('purchase_responses');
const duration = new Trend('purchase_duration', true);
const dispatch = new Trend('dispatch_offset_ms');
const invalid = new Counter('invalid_success_body');
export const options = {
  scenarios: { purchase: { executor: 'per-vu-iterations', vus: users.length,
    iterations: 1, maxDuration: '180s' } },
  summaryTrendStats: ['min', 'max', 'med', 'p(95)', 'p(99)'],
  thresholds: { invalid_success_body: ['count==0'] },
};

export function setup() { return { startAt: Date.now() + 15000 }; }
export default function (data) {
  const user = users[exec.vu.idInTest - 1];
  sleep(Math.max(0, data.startAt - Date.now()) / 1000);
  dispatch.add(Date.now() - data.startAt);
  const res = http.post('http://127.0.0.1:18080/api/tickets',
    JSON.stringify({ seatIds: user.seatIds }), { headers: {
      Authorization: `Bearer ${user.at}`, 'X-Entry-Token': user.entry,
      'Idempotency-Key': user.key, 'Content-Type': 'application/json',
    }, timeout: '120s', responseCallback: http.expectedStatuses(201, 409) });
  let code = '';
  let body;
  try { body = res.json(); code = body.code || ''; } catch (_) { code = 'NON_JSON'; }
  if (res.status === 201) {
    const actual = Array.isArray(body) ? body.map(t => t.seatId).sort((a, b) => a - b) : [];
    const expected = [...user.seatIds].sort((a, b) => a - b);
    invalid.add(JSON.stringify(actual) === JSON.stringify(expected) &&
      body.every(t => t.userId === user.id) ? 0 : 1);
  }
  responses.add(1, { status: String(res.status), code, user: String(user.id) });
  duration.add(res.timings.duration);
}
export function handleSummary(data) {
  return { [__ENV.SUMMARY]: JSON.stringify(data, null, 2) };
}
