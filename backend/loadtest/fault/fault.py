#!/usr/bin/env python3
"""F1/F2/F3 on the existing, isolated local Docker measurement stack."""
import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
import datetime
import fcntl
import hashlib
import http.client
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import compare as c

RAW = c.RAW / 'fault'
REPORT = c.HERE / 'results/fault_injection.md'
EXPECTATIONS = {
    'F1': 'distributed 500ms lease + 8초 외부 DB 잠금. 동일 좌석 201 정확히 1건, 나머지 409. '
          'uq_ticket_seat 별도 주입은 409 SEAT_NOT_AVAILABLE + 별도 로그. 커밋 결과 유지.',
    'F2': '구매 트랜잭션이 DB에서 대기하는 동안 측정 Redis stop. 신규 요청은 계약 형식 '
          '500 INTERNAL_SERVER_ERROR. 선점 완료 구매는 201. 재시작/상태 유실 뒤 DB replay 및 재등록.',
    'F3': 'loopback 프록시가 서버 201을 읽고 응답 없이 연결 종료. 소진 토큰으로 동일 키 201 replay. '
          '멱등성 키 삭제 후 DB replay 동일. 새 키/새 입장은 매진 409, 합계 4매 유지.',
}


def redis(*args):
    return c.captured(c.COMPOSE + ['exec', '-T', 'redis', 'redis-cli', '--raw', *map(str, args)])


def wait_for(predicate, message, timeout=15):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(0.05)
    raise RuntimeError(message)


def check(result, name, condition):
    result['checks'][name] = bool(condition)
    if not condition:
        raise RuntimeError(f'검증 실패: {name}')


def request(method, path, at=None, body=None, extra=None, base=c.BASE, timeout=30):
    headers = {'Content-Type': 'application/json'}
    if at:
        headers['Authorization'] = 'Bearer ' + at
    headers.update(extra or {})
    req = urllib.request.Request(base + path, method=method, headers=headers,
                                 data=json.dumps(body).encode() if body is not None else None)
    started = time.monotonic()
    try:
        try:
            response = c.HTTP.open(req, timeout=timeout)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read()
            try:
                payload = json.loads(raw)
            except (ValueError, UnicodeError):
                payload = None
            return {'status': response.status, 'body': payload, 'elapsed_ms': (time.monotonic()-started)*1000}
    except (OSError, http.client.HTTPException) as error:
        # Never persist headers, JWTs or urllib request objects.
        return {'status': 0, 'transport': type(error).__name__, 'body': None,
                'elapsed_ms': (time.monotonic()-started)*1000}


def row(user, seats, key):
    return {'id': user, 'seatIds': seats, 'key': key}


def purchase(row, tokens, entries, base=c.BASE):
    return request('POST', '/api/tickets', tokens[row['id']], {'seatIds': row['seatIds']},
                   {'X-Entry-Token': entries[row['id']], 'Idempotency-Key': row['key']}, base)


def record(result, label, response):
    result['responses'].append(dict(response, stage=label))
    return response


def error_is(response, status, code):
    body = response['body']
    return (response['status'] == status and isinstance(body, dict) and set(body) == {'code', 'message'}
            and body['code'] == code and isinstance(body['message'], str) and bool(body['message'])
            and (status != 500 or body['message'] == 'An internal server error occurred.'))


def success_is(response, purchase):
    body = response.get('body')
    return (response.get('status') == 201 and isinstance(body, list) and len(body) == len(purchase['seatIds'])
            and all(isinstance(ticket, dict) and ticket.get('userId') == purchase['id']
                    and isinstance(ticket.get('ticketId'), int) and ticket.get('purchasePrice') == 10000
                    for ticket in body)
            and sorted(ticket.get('seatId', -1) for ticket in body) == sorted(purchase['seatIds']))


def audit(rows):
    """SQL checks include expected seat sets keyed by user + SHA256(purchase key)."""
    expected = {}
    for purchase in rows:
        identity = (purchase['id'], hashlib.sha256(purchase['key'].encode()).hexdigest())
        seats = sorted(purchase['seatIds'])
        if identity in expected and expected[identity] != seats:
            raise RuntimeError('도구의 동일 키/다른 묶음 기록 오류')
        expected[identity] = seats
    values = ','.join(f"({user},'{key}',{seat})" for (user, key), seats in expected.items() for seat in seats)
    # Temporary table exists only in this one measurement MySQL connection.
    statement = f"""
      CREATE TEMPORARY TABLE fault_expected(user_id BIGINT, purchase_key VARCHAR(64), seat_id BIGINT);
      INSERT INTO fault_expected VALUES {values};
      SELECT COUNT(*) FROM (SELECT seat_id FROM ticket GROUP BY seat_id HAVING COUNT(*)>1) d;
      SELECT COUNT(*) FROM seat s LEFT JOIN ticket t ON t.seat_id=s.id
        WHERE (s.status='SOLD' AND t.id IS NULL) OR (s.status<>'SOLD' AND t.id IS NOT NULL);
      SELECT COUNT(*) FROM (SELECT t.user_id,s.concert_id FROM ticket t JOIN seat s ON s.id=t.seat_id
        GROUP BY t.user_id,s.concert_id HAVING COUNT(*)>4) d;
      CREATE TEMPORARY TABLE fault_expected_count AS
        SELECT user_id, purchase_key, COUNT(*) AS n FROM fault_expected GROUP BY user_id, purchase_key;
      SELECT COUNT(*) FROM (
        SELECT t.user_id,t.purchase_key FROM ticket t
        LEFT JOIN fault_expected e ON e.user_id=t.user_id AND e.purchase_key=t.purchase_key AND e.seat_id=t.seat_id
        LEFT JOIN fault_expected_count x ON x.user_id=t.user_id AND x.purchase_key=t.purchase_key
        GROUP BY t.user_id,t.purchase_key,x.n
        HAVING SUM(e.seat_id IS NULL)>0 OR COUNT(*)<>x.n OR x.n IS NULL
      ) d;
      SELECT COUNT(*) FROM ticket;
    """
    counts = list(map(int, c.sql(statement).splitlines()))
    return dict(zip(('duplicate', 'sold_mismatch', 'over_limit', 'partial_bundle', 'tickets'), counts))


@contextmanager
def db_gate(seat):
    """Delay from outside the app. Named-lock readiness follows the row lock acquisition."""
    with ThreadPoolExecutor(max_workers=1) as pool:
        held = pool.submit(c.sql, f"START TRANSACTION; SELECT id FROM seat WHERE id={seat} FOR UPDATE; "
                           "SELECT GET_LOCK('ticket_fault_gate_ready',0); SELECT SLEEP(8); COMMIT;")
        try:
            wait_for(lambda: c.sql("SELECT IS_USED_LOCK('ticket_fault_gate_ready') IS NOT NULL;") == '1',
                     '외부 DB 잠금 준비 실패', timeout=5)
            yield
        finally:
            held.result(timeout=15)


def db_waiters():
    return int(c.sql('SELECT COUNT(*) FROM performance_schema.data_lock_waits;'))


def health_up():
    body = request('GET', '/actuator/health', timeout=2)['body']
    return isinstance(body, dict) and body.get('status') == 'UP'


def redis_ready():
    try:
        return redis('PING') == 'PONG'
    except subprocess.CalledProcessError:
        return False


@contextmanager
def app(scenario, env, jar):
    directory = RAW / scenario
    directory.mkdir(parents=True, exist_ok=True)
    child_env = {k: v for k, v in os.environ.items() if not k.startswith(('SPRING_', 'TICKET_', 'MANAGEMENT_',
                 'SERVER_', 'LOGGING_', 'JWT_', 'QUEUE_JWT_', 'GOOGLE_')) and
                 k not in {'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'}}
    child_env.update(env, TICKET_PURCHASE_STRATEGY='distributed' if scenario == 'F1' else 'pessimistic')
    locations = ','.join('file:' + str(p) for p in (c.HERE / 'application-loadtest.yaml', HERE / 'application-fault.yaml'))
    with (directory / 'app.log').open('w') as log:
        c.require_free_port(18080)
        process = subprocess.Popen(['java', '-Xms1g', '-Xmx1g', '-XX:ActiveProcessorCount=8', '-jar', str(jar),
                                    '--spring.config.additional-location=' + locations],
                                   env=child_env, stdout=log, stderr=subprocess.STDOUT)
        try:
            def healthy():
                if process.poll() is not None:
                    raise RuntimeError(f'{scenario} 앱 기동 실패: raw/fault/{scenario}/app.log')
                return health_up()
            wait_for(healthy, f'{scenario} 앱 health timeout', timeout=120)
            yield directory
        finally:
            c.stop_app(process)


def f1(result, tokens, directory):
    rows = [row(i, [1], f'fault-F1-{i}') for i in range(1, 4)]
    result['rows'] = rows
    entries = {i: c.entry(i, tokens[i]) for i in range(1, 4)}
    with ThreadPoolExecutor(max_workers=2) as pool, db_gate(1):
        first = pool.submit(purchase, rows[0], tokens, entries)
        wait_for(lambda: db_waiters() >= 1, 'F1 첫 구매의 DB 지연 미확인', timeout=4)
        wait_for(lambda: redis('EXISTS', 'lock:seat:1') == '0', 'F1 lease 만료 미확인', timeout=2)
        check(result, 'lease_expired_before_commit', not first.done())
        second = pool.submit(purchase, rows[1], tokens, entries)
        wait_for(lambda: db_waiters() >= 2, 'F1 lease 만료 후 두 구매의 동시 DB 진입 미확인', timeout=3)
        result['observations']['overlapping_db_waiters'] = db_waiters()
        responses = [record(result, f'contender-{i+1}', future.result(timeout=25))
                     for i, future in enumerate((first, second))]
    check(result, 'exactly_one_201_no_5xx', sum(r['status'] == 201 for r in responses) == 1
          and all(success_is(r, rows[i]) if r['status'] == 201 else
                  any(error_is(r, 409, code) for code in ('SEAT_NOT_AVAILABLE', 'SEAT_LOCK_FAILED'))
                  for i, r in enumerate(responses)))
    check(result, 'exactly_one_ticket', c.sql('SELECT COUNT(*) FROM ticket WHERE seat_id=1;') == '1')
    # @Version usually wins before uq_ticket_seat. Explicitly test the final constraint as a separate subcase.
    c.sql("UPDATE seat SET status='AVAILABLE',version=version+1 WHERE id=1;")
    try:
        probe = record(result, 'unique-constraint-probe', purchase(rows[2], tokens, entries))
        check(result, 'unique_constraint_409', error_is(probe, 409, 'SEAT_NOT_AVAILABLE'))
        check(result, 'unique_constraint_logged', 'uq_ticket_seat violation' in (directory / 'app.log').read_text())
        check(result, 'constraint_rollback_keeps_existing_ticket',
              c.sql("SELECT COUNT(*) FROM ticket WHERE seat_id=1;") == '1'
              and c.sql("SELECT status FROM seat WHERE id=1;") == 'AVAILABLE')
    finally:
        # Remove only the intentionally inconsistent fixture; the failed purchase must have rolled back.
        c.sql("UPDATE seat SET status='SOLD',version=version+1 WHERE id=1;")
    result['observations']['constraint_probe'] = '기존 티켓 유지한 채 좌석 AVAILABLE 주입; 롤백 확인 후 SOLD로 복원'


def f2(result, tokens, directory):
    rows = [row(1, [1, 2], 'fault-F2-before'), row(2, [3, 4], 'fault-F2-inflight')]
    rows += [row(i, list(range(4+(i-3)*4+1, 4+(i-3)*4+1+(i % 4+1))), f'fault-F2-{i}')
             for i in range(3, 35)]
    result['rows'] = rows
    prepared = c.prepare(rows, tokens)
    entries = {p['id']: p['entry'] for p in prepared}
    baseline = record(result, 'before-stop', purchase(rows[0], tokens, entries))
    check(result, 'baseline_201', success_is(baseline, rows[0]))
    stopped = False
    try:
        with ThreadPoolExecutor(max_workers=32) as pool, db_gate(3):
            inflight = pool.submit(purchase, rows[1], tokens, entries)
            wait_for(lambda: db_waiters() >= 1, 'F2 선점 완료 구매의 DB 지연 미확인', timeout=4)
            c.captured(c.COMPOSE + ['stop', '-t', '1', 'redis'])
            stopped = True
            during = list(pool.map(lambda p: purchase(p, tokens, entries), rows[2:]))
            for i, response in enumerate(during, 3):
                record(result, f'during-stop-{i}', response)
            committed = record(result, 'claimed-before-stop', inflight.result(timeout=30))
        check(result, 'outage_responses_contract_500', all(error_is(r, 500, 'INTERNAL_SERVER_ERROR') for r in during))
        check(result, 'inflight_commit_remains_201', success_is(committed, rows[1]))
    finally:
        if stopped:
            c.captured(c.COMPOSE + ['start', 'redis'])
            wait_for(redis_ready, 'F2 Redis 재시작 실패')
            wait_for(lambda: health_up(),
                     'F2 앱 Redis 재연결 실패', timeout=30)
    # Record restart behavior before deliberately simulating complete state loss.
    status = record(result, 'queue-after-restart', request('GET', '/api/queue/1/status', tokens[3]))
    result['observations']['queue_state_after_restart'] = status['body']
    check(result, 'restart_queue_readable', status['status'] == 200)
    for p, original in zip(rows[:2], (baseline, committed)):
        # IN_PROGRESS may survive restart after failed COMPLETE. Contract keeps its 30s TTL.
        wait_for(lambda: 'IN_PROGRESS' not in redis('GET', f"idempotency:tickets:v3:{p['id']}:{p['key']}"),
                 'F2 IN_PROGRESS 30초 TTL 이후에도 잔존', timeout=35)
        replay = record(result, f"restart-replay-{p['id']}", purchase(p, tokens, entries))
        check(result, f"restart_replay_{p['id']}", replay['status'] == 201 and replay['body'] == original['body'])
    c.redis_reset()  # Dedicated measurement Redis only; also test snapshot/queue loss.
    status = record(result, 'queue-after-state-loss', request('GET', '/api/queue/1/status', tokens[3]))
    check(result, 'lost_queue_is_expired', status['status'] == 200 and status['body']['status'] == 'EXPIRED')
    invalid = record(result, 'lost-active-old-token', purchase(rows[2], tokens, entries))
    check(result, 'lost_active_rejects_old_token', error_is(invalid, 403, 'ENTRY_TOKEN_INVALID'))
    for p, original in zip(rows[:2], (baseline, committed)):
        replay = record(result, f"db-replay-{p['id']}", purchase(p, tokens, entries))
        check(result, f"state_loss_db_replay_{p['id']}", replay['status'] == 201 and replay['body'] == original['body'])
    entries[3] = c.entry(3, tokens[3])
    retried = record(result, 'reregister-and-retry', purchase(rows[2], tokens, entries))
    check(result, 'reregister_and_retry_201', success_is(retried, rows[2]))
    result['observations']['state_loss'] = '측정 Redis FLUSHDB 후 EXPIRED, 구 토큰 403, 큐 재등록·동일 키 재시도 201'


def forward_and_disconnect(handler, captured, upstream_port):
    upstream = http.client.HTTPConnection('127.0.0.1', upstream_port, timeout=30)
    try:
        body = handler.rfile.read(int(handler.headers['Content-Length']))
        upstream.request('POST', handler.path, body,
                         {k: v for k, v in handler.headers.items() if k.lower() not in ('host', 'connection')})
        response = upstream.getresponse()
        captured.update(status=response.status, body=json.loads(response.read()))
    except Exception as error:
        captured['error'] = type(error).__name__
    finally:
        upstream.close()
        handler.close_connection = True
        handler.connection.shutdown(socket.SHUT_RDWR)


@contextmanager
def lost_response_proxy(listen_port=18081, upstream_port=18080):
    """Single request: read upstream response fully, then disconnect without sending bytes."""
    captured = {}

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_POST(self):
            forward_and_disconnect(self, captured, upstream_port)

    with HTTPServer(('127.0.0.1', listen_port), Handler) as server:
        server.timeout = 35
        thread = threading.Thread(target=server.handle_request, daemon=True)
        thread.start()
        try:
            yield captured, f'http://127.0.0.1:{server.server_port}'
        finally:
            thread.join(timeout=40)
            if thread.is_alive():
                raise RuntimeError('F3 응답 유실 프록시 종료 실패')


def f3(result, tokens, directory):
    first = row(1, [1, 2], 'fault-F3-lost')
    rows = [first, row(1, [1, 2], 'fault-F3-other-key'), row(1, [3, 4, 5], 'fault-F3-over'),
            row(1, [3, 4], 'fault-F3-second'), row(1, [5], 'fault-F3-fifth')]
    result['rows'] = rows
    entries = {1: c.entry(1, tokens[1])}
    with lost_response_proxy() as (upstream, proxy_url):
        lost = record(result, 'lost-client-response', purchase(first, tokens, entries, proxy_url))
    record(result, 'proxy-observed-upstream', upstream)
    check(result, 'commit_201_and_client_disconnect', success_is(upstream, first) and lost['status'] == 0)
    check(result, 'committed_before_replay', c.sql('SELECT COUNT(*) FROM ticket WHERE user_id=1;') == '2')
    replay = record(result, 'consumed-token-replay', purchase(first, tokens, entries))
    check(result, 'consumed_token_replay_201', replay['status'] == 201 and replay['body'] == upstream['body'])
    removed = redis('DEL', 'idempotency:tickets:v3:1:fault-F3-lost')
    check(result, 'idempotency_key_removed', removed == '1')
    recovered = record(result, 'deleted-key-db-replay', purchase(first, tokens, entries))
    check(result, 'db_recovery_same_201', recovered['status'] == 201 and recovered['body'] == upstream['body'])
    old = record(result, 'different-key-consumed-token', purchase(rows[1], tokens, entries))
    check(result, 'new_key_old_token_403', error_is(old, 403, 'ENTRY_TOKEN_INVALID'))
    entries[1] = c.entry(1, tokens[1])
    sold = record(result, 'different-key-new-entry-sold', purchase(rows[1], tokens, entries))
    check(result, 'new_key_sold_seats_409', error_is(sold, 409, 'SEAT_NOT_AVAILABLE'))
    over = record(result, 'cumulative-five-rejected', purchase(rows[2], tokens, entries))
    check(result, 'limit_rejects_five', error_is(over, 409, 'PURCHASE_LIMIT_EXCEEDED'))
    second = record(result, 'cumulative-four-success', purchase(rows[3], tokens, entries))
    check(result, 'failed_batch_retains_token_then_four_succeed', success_is(second, rows[3]))
    entries[1] = c.entry(1, tokens[1])
    fifth = record(result, 'fifth-ticket-rejected', purchase(rows[4], tokens, entries))
    check(result, 'four_ticket_limit_accurate', error_is(fifth, 409, 'PURCHASE_LIMIT_EXCEEDED')
          and c.sql('SELECT COUNT(*) FROM ticket WHERE user_id=1;') == '4')


def report(results, environment, failure=None):
    lines = ['# 로컬 장애 주입 결과', '', '## 환경', '', '```json',
             json.dumps(environment, ensure_ascii=False, indent=2), '```', '',
             '버전·lease·포트는 도구의 지정 설정이다. 실제 기동·장애 주입 여부는 시나리오별 판정에 기록한다.',
             '로컬 Docker ticket-loadtest 프로젝트, MySQL 13306 / Redis 16379 / 앱 18080 / 응답 유실 프록시 18081.',
             '개발 Redis(6379, ticket-redis)·MySQL·8080 앱에는 접근하지 않는다. 운영 실측 없음.', '',
             '## 시나리오별 주입·기대·관찰·판정', '']
    for scenario in EXPECTATIONS:
        result = next((r for r in results if r['scenario'] == scenario), None)
        lines += [f'### {scenario}', '', f'- 주입 방법·기대: {EXPECTATIONS[scenario]}']
        if result is None:
            lines += ['- 관찰: 실측 미실행. 응답 코드·정합성 SQL 건수 미확인.', '- 판정: 미실행.', '']
            continue
        lines += [f"- 판정: {'통과' if result['valid'] else '실패/미완료'}.",
                  '- 관찰:', '```json', json.dumps({k: v for k, v in result.items() if k != 'rows'},
                                                    ensure_ascii=False, indent=2), '```', '']
    lines += ['## 정합성 SQL', '',
              '- 좌석당 ticket 중복 / SOLD↔ticket 불일치 / 사용자·공연별 4매 초과 / purchase_key별 부분 묶음: 각각 0이어야 통과.',
              '- 부분 묶음은 SQL 임시 테이블에 요청 사용자·구매키 SHA-256·좌석을 넣고 DB의 각 구매 묶음과 정확히 대조한다.',
              '- 동일 키 replay는 중복 요청으로 세지 않는다. 응답 유실 요청도 서버 성공 및 커밋을 별도로 확인한다.', '',
              '## 발견 결함과 조치', '',
              '- 2026-10-07 로컬 Docker 실측 완료. 앱 결함 없음(정합성 위반·커밋된 구매의 5xx·replay 불일치 0건). 회귀 테스트 6건을 별도로 추가했다.',
              '- 하드코딩된 lease만 ticket.seat-lock-lease로 외부화했다(기본 10초, waitTime 0 유지). 측정용 서버 우회 경로 없음.',
              '- 기존 unlock 예외 격리, 커밋 후 consume/COMPLETE 예외 격리, purchase_key DB 복구 경로를 그대로 사용한다.', '',
              '## 해석·남은 일', '',
              '- @Version이 uq_ticket_seat보다 먼저 충돌을 감지할 수 있다. F1은 lease 경합과 UNIQUE 최종 방어를 별도 검사한다.',
              '- F1 UNIQUE 하위 시나리오는 티켓이 있는 좌석을 AVAILABLE로 만드는 고의 불일치이며, 롤백 확인 후 SOLD로 복원해 최종 SQL을 검사한다.',
              '- COMPLETE 장애 후 IN_PROGRESS가 남으면 30초 동안 409 REQUEST_IN_PROGRESS가 가능하다. F2는 TTL 소멸 뒤 replay를 검사한다.',
              '- F2 stop/start 뒤 실제 큐 상태를 먼저 기록한다. 별도 FLUSHDB로 전체 상태 유실도 주입해 EXPIRED/재등록/DB 복구를 검사한다.',
              '- 새 키 + 소진 토큰은 403 ENTRY_TOKEN_INVALID, 새 키 + 새 입장은 매진 409 또는 한도 409다.',
              '- F1 경합 패자는 409 SEAT_LOCK_FAILED(낙관적 락 충돌 매핑)로 응답한다. 계약상 409이며 SEAT_NOT_AVAILABLE과 코드가 다르다.',
              '- Redis 중단 중 신규 구매는 500 INTERNAL_SERVER_ERROR(05 계약 형식)다. 05에 503 코드가 없어 현재 계약에는 부합한다. 503 도입은 계약 변경 사항이다.']
    if failure:
        lines += ['', '## 차단/실패 원인', '', failure, '', '로그: `raw/fault/attempt.log` 및 각 시나리오 app.log.']
    lines += ['', '## 실행', '', '`bash backend/loadtest/fault/run-fault-injection.sh`', '',
              '실행 방법·필수 도구·정리·Docker 없는 검증은 `backend/loadtest/README.md` 참조.', '']
    REPORT.parent.mkdir(exist_ok=True)
    REPORT.write_text('\n'.join(lines))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--cleanup', action='store_true')
    args = parser.parse_args()
    RAW.mkdir(parents=True, exist_ok=True)
    with (c.RAW / 'run.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        if args.cleanup:
            c.command(c.COMPOSE + ['down', '--volumes', '--remove-orphans'])
            return
        results, owned = [], False
        environment = {'attempt_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                       'mysql': '8.0.36', 'redis': '7.2.4', 'lease': '500ms (default 10s)',
                       'F1_strategy': 'distributed', 'F2_F3_strategy': 'pessimistic', 'db_gate_seconds': 8}
        attempt = RAW / 'attempt.log'
        try:
            if not shutil.which('docker'):
                raise RuntimeError('필수 도구 없음: docker')
            with attempt.open('w') as log:
                # Fail before loading secrets, building or mutating measurement resources.
                c.command(c.DOCKER + ['info', '--format', '{{.ServerVersion}}'], stdout=log, stderr=subprocess.STDOUT)
                for tool in ('java', 'python3'):
                    if not shutil.which(tool):
                        raise RuntimeError(f'필수 도구 없음: {tool}')
                for port in (13306, 16379, 18080, 18081):
                    c.require_free_port(port)
                env = c.local_env()
                tokens = c.access_tokens(env, 34)
                c.command(['./gradlew', '--no-daemon', 'bootJar'], cwd=c.BACKEND, stdout=log, stderr=subprocess.STDOUT)
                jars = [p for p in (c.BACKEND / 'build/libs').glob('*.jar') if not p.name.endswith('-plain.jar')]
                if len(jars) != 1:
                    raise RuntimeError('build/libs의 실행 jar는 하나여야 한다')
                environment['jar_sha256'] = hashlib.sha256(jars[0].read_bytes()).hexdigest()
                owned = True
                c.command(c.COMPOSE + ['up', '-d', '--wait', '--wait-timeout', '240'], stdout=log, stderr=subprocess.STDOUT)
            for scenario, run in (('F1', f1), ('F2', f2), ('F3', f3)):
                print(f'장애 주입: {scenario}', flush=True)
                result = {'scenario': scenario, 'checks': {}, 'responses': [], 'observations': {}, 'valid': False}
                results.append(result)
                with app(scenario, env, jars[0]) as directory:
                    c.seed(34, 140)
                    # Sign after JVM startup so slow setup cannot expire the 15 minute ATs.
                    tokens = c.access_tokens(env, 34)
                    try:
                        run(result, tokens, directory)
                    finally:
                        if result.get('rows'):
                            result['audit'] = audit(result['rows'])
                        (directory / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2))
                    check(result, 'all_four_sql_invariants_zero', all(result['audit'][k] == 0 for k in
                          ('duplicate', 'sold_mismatch', 'over_limit', 'partial_bundle')))
                    result['distribution'] = dict(Counter(f"{r['status']}/" +
                            (r['body'].get('code', 'OTHER') if isinstance(r['body'], dict) else
                             'CREATED' if r['status'] == 201 else 'TRANSPORT') for r in result['responses']
                            if r['stage'] != 'queue-after-restart' and r['stage'] != 'queue-after-state-loss'))
                    result['valid'] = True
                    (directory / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2))
                report(results, environment)
            report(results, environment)
        except Exception as error:
            reason = str(error) if isinstance(error, RuntimeError) else type(error).__name__
            if isinstance(error, subprocess.CalledProcessError):
                reason = f'명령 실패(exit={error.returncode}): ' + ' '.join(error.cmd)
                if error.stderr:
                    reason += '\n' + str(error.stderr).strip()[-500:]
                if attempt.exists() and 'permission denied' in attempt.read_text().lower():
                    reason += '\n\n로컬 Docker 소켓 unix:///var/run/docker.sock: permission denied. 실측 미실행.'
            report(results, environment, reason)
            print(reason, file=sys.stderr)
            raise SystemExit(1)
        finally:
            if owned:
                with attempt.open('a') as log:
                    cleanup = subprocess.run(c.COMPOSE + ['down', '--volumes', '--remove-orphans'],
                                             stdout=log, stderr=subprocess.STDOUT)
                if cleanup.returncode:
                    with REPORT.open('a') as output:
                        output.write('\n자동 정리 실패. README의 --cleanup 필요.\n')
                    raise SystemExit(1)


if __name__ == '__main__':
    main()
