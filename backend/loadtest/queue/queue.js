import http from 'k6/http';
import { sleep } from 'k6';
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';
import { Counter, Trend } from 'k6/metrics';

const users = new SharedArray('users', () => JSON.parse(open(__ENV.INPUT)));
const durations = Object.fromEntries(['register', 'status', 'enter', 'seat', 'purchase'].map(n => [n, new Trend(`${n}_duration`, true)]));
const responses = new Counter('api_responses');
const completed = new Counter('completed_users');
const invalid = new Counter('invalid_body');
const arrivals = new Trend('arrival_offset_ms');
const residence = new Trend('active_residence_ms');
export const options = {
  scenarios: { opening: { executor: 'per-vu-iterations', vus: users.length, iterations: 1, maxDuration: '880s' } },
  summaryTrendStats: ['min', 'max', 'med', 'p(95)', 'p(99)'],
  thresholds: { invalid_body: ['count==0'] },
};
export function setup() { return { start: Date.now() + 5000 }; }
function request(name, method, path, user, body, extra = {}) {
  const res = http.request(method, `http://127.0.0.1:18080${path}`, body === null ? null : JSON.stringify(body), {
    headers: { Authorization: `Bearer ${user.at}`, 'Content-Type': 'application/json', ...extra },
    timeout: '10s', tags: { name }, responseCallback: http.expectedStatuses(200, 201, 409),
  });
  durations[name].add(res.timings.duration);
  responses.add(1, { api: name, status: String(res.status), user: String(user.id) });
  return res;
}
export default function (data) {
  const user = users[exec.vu.idInTest - 1];
  sleep(Math.max(0, data.start + user.arrivalMs - Date.now()) / 1000);
  arrivals.add(Date.now() - data.start - user.arrivalMs);
  const path = '/api/queue/1';
  if (request('register', 'POST', path, user, null).status !== 200) return;
  let admitted = false;
  const deadline = Date.now() + 730000;
  while (Date.now() < deadline) {
    const res = request('status', 'GET', path + '/status', user, null);
    if (res.status !== 200) return;
    let status;
    try { status = res.json(); } catch (_) { invalid.add(1); return; }
    if (status.status === 'ADMITTED') { admitted = true; break; }
    if (status.status !== 'WAITING' || !(status.nextPollAfterMs > 0)) { invalid.add(1); return; }
    sleep(status.nextPollAfterMs / 1000);
  }
  if (!admitted) return;
  const entered = request('enter', 'POST', path + '/enter', user, null);
  if (entered.status !== 200) return;
  let token;
  try { token = entered.json().entryToken; } catch (_) { invalid.add(1); return; }
  if (!token) { invalid.add(1); return; }
  const activeStart = Date.now();
  for (let i = 0; i < 3; i++) {
    const endpoint = i === 1 ? '/api/concerts/1/seats/availability/sections/A' : '/api/concerts/1/seats/availability';
    if (request('seat', 'GET', endpoint, user, null).status !== 200) return;
    sleep(25 + user.id % 6); // 75–90s ACTIVE, to exercise the 2,000-capacity candidate.
  }
  const res = request('purchase', 'POST', '/api/tickets', user, { seatIds: [user.id] }, {
    'X-Entry-Token': token, 'Idempotency-Key': `queue-${user.id}`,
  });
  residence.add(Date.now() - activeStart);
  if (res.status === 201) {
    let body;
    try { body = res.json(); } catch (_) { invalid.add(1); return; }
    if (!Array.isArray(body) || body.length !== 1 || body[0].seatId !== user.id || body[0].userId !== user.id) { invalid.add(1); return; }
  }
  if (res.status === 201 || res.status === 409) completed.add(1);
}
export function handleSummary(data) { return { [__ENV.SUMMARY]: JSON.stringify(data, null, 2) }; }
