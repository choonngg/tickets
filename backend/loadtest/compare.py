#!/usr/bin/env python3
"""Local Docker only. Production code/configuration is never changed."""
import argparse
import base64
import concurrent.futures
import datetime
import fcntl
import hashlib
import json
import os
from pathlib import Path
import platform
import random
import shutil
import socket
import subprocess
import sys
import threading
import time
import urllib.request

HERE = Path(__file__).resolve().parent
BACKEND = HERE.parent
RAW = HERE / 'raw'
REPORT = HERE / 'results/strategy_comparison.md'
DOCKER = ['docker', '--host', 'unix:///var/run/docker.sock']
COMPOSE = DOCKER + ['compose', '-p', 'ticket-loadtest', '-f', str(HERE / 'compose.yaml')]
STRATEGIES = ['pessimistic', 'optimistic', 'distributed']
HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}))
BASE = 'http://127.0.0.1:18080'
SEED = 20261007


def command(args, **kwargs):
    return subprocess.run(args, check=True, text=True, **kwargs)


def captured(args, **kwargs):
    return command(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, **kwargs).stdout.strip()


def sql(statement):
    # exec inside the dedicated service, never a development host/port.
    return captured(COMPOSE + ['exec', '-T', '-e', 'MYSQL_PWD=loadtest-only',
                               'mysql', 'mysql', '-uroot', '-N', '-B', 'ticket_loadtest'], input=statement)


def redis_reset():
    captured(COMPOSE + ['exec', '-T', 'redis', 'redis-cli', 'FLUSHDB'])


def local_env():
    # Parse like backend/build.gradle bootRun: first '=', no shell evaluation.
    allowed = {'JWT_PRIVATE_KEY_PEM', 'JWT_PUBLIC_KEY_PEM', 'JWT_KEY_ID', 'JWT_ISSUER',
               'JWT_AUDIENCE', 'QUEUE_JWT_PRIVATE_KEY_PEM', 'QUEUE_JWT_PUBLIC_KEY_PEM',
               'QUEUE_JWT_KEY_ID', 'GOOGLE_CLIENT_ID', 'GOOGLE_CLIENT_SECRET', 'GOOGLE_REDIRECT_URI'}
    values = {}
    for line in (BACKEND / '.env').read_text().splitlines():
        line = line.strip()
        if line.startswith('#') or '=' not in line:
            continue
        key, value = line.split('=', 1)
        if key.strip() in allowed:
            values[key.strip()] = value
    for key in allowed:
        if key in os.environ:
            values[key] = os.environ[key]
    for key in allowed - {'QUEUE_JWT_KEY_ID'}:
        if not values.get(key):
            raise RuntimeError(f'로컬 secret 누락: {key} (backend/.env)')
    return values


def b64(data):
    return base64.urlsafe_b64encode(data).rstrip(b'=').decode()


def access_tokens(env, count):
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import padding
    key = serialization.load_pem_private_key(env['JWT_PRIVATE_KEY_PEM'].replace('\\n', '\n').encode(), None)
    now = int(time.time())
    header = b64(json.dumps({'alg': 'RS256', 'typ': 'JWT', 'kid': env['JWT_KEY_ID']}).encode())
    tokens = {}
    for user in range(1, count + 1):
        payload = b64(json.dumps({'iss': env['JWT_ISSUER'], 'aud': [env['JWT_AUDIENCE']],
                                  'sub': str(user), 'sid': str(user), 'typ': 'AT', 'role': 'FAN',
                                  'iat': now, 'exp': now + 900}).encode())
        message = f'{header}.{payload}'.encode()
        tokens[user] = message.decode() + '.' + b64(key.sign(message, padding.PKCS1v15(), hashes.SHA256()))
    return tokens


def api(method, path, at=None, body=None, extra=None):
    headers = {'Content-Type': 'application/json'}
    if at:
        headers['Authorization'] = 'Bearer ' + at
    headers.update(extra or {})
    request = urllib.request.Request(BASE + path, method=method, headers=headers,
                                     data=json.dumps(body).encode() if body is not None else None)
    with HTTP.open(request, timeout=10) as response:
        return json.load(response)


def seed(count, seats):
    statements = ['DELETE FROM ticket; DELETE FROM seat; DELETE FROM concert;',
                  'DELETE FROM refresh_token; DELETE FROM auth_session; DELETE FROM users;']
    users = [f"({i},'loadtest-{i}','lt{i}@example.invalid','loadtest','FAN')" for i in range(1, count + 1)]
    users.append(f"({count+1},'loadtest-artist','artist@example.invalid','artist','ARTIST')")
    statements.append('INSERT INTO users(id,google_sub,email,name,role) VALUES ' + ','.join(users) + ';')
    statements.append('INSERT INTO auth_session(id,user_id,expires_at) VALUES ' +
                      ','.join(f'({i},{i},UTC_TIMESTAMP()+INTERVAL 1 DAY)' for i in range(1, count + 1)) + ';')
    statements.append('INSERT INTO concert(id,artist_id,title,venue,concert_at,ticket_open_at,ticket_close_at) VALUES ' +
                      ','.join(f"({i},{count+1},'loadtest','local',UTC_TIMESTAMP()+INTERVAL 2 DAY,"
                               'UTC_TIMESTAMP()-INTERVAL 1 DAY,UTC_TIMESTAMP()+INTERVAL 1 DAY)' for i in (1, 2)) + ';')
    rows = [f"({i},1,'HOT',1,{i},10000)" for i in range(1, seats + 1)]
    rows += [f"({10000+i},2,'WARMUP',1,{i},10000)" for i in range(1, 101)]
    statements.append('INSERT INTO seat(id,concert_id,section_name,row_no,seat_no,price) VALUES ' + ','.join(rows) + ';')
    sql('\n'.join(statements))
    redis_reset()


def entry(user, token, concert=1):
    path = f'/api/queue/{concert}'
    api('POST', path, token)
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        status = api('GET', path + '/status', token)
        if status['status'] == 'ADMITTED':
            return api('POST', path + '/enter', token)['entryToken']
        if status['status'] != 'WAITING':
            raise RuntimeError(f'queue user={user}: {status["status"]}')
        time.sleep(max(0.001, status['nextPollAfterMs'] / 1000))
    raise RuntimeError(f'queue admission timeout user={user}')


def plan(scenario, count, seats):
    rng = random.Random(SEED)
    return [{'id': i, 'seatIds': [1] if scenario == 'A' else rng.sample(range(1, seats + 1), rng.randint(1, 4)),
             'key': f'loadtest-{scenario}-{i}'} for i in range(1, count + 1)]


def prepare(rows, tokens):
    def one(row):
        return dict(row, at=tokens[row['id']], entry=entry(row['id'], tokens[row['id']]))
    with concurrent.futures.ThreadPoolExecutor(max_workers=32) as pool:
        return list(pool.map(one, rows))


def warmup(tokens):
    def one(user):
        token = entry(user, tokens[user], 2)
        response = api('POST', '/api/tickets', tokens[user], {'seatIds': [10000 + user]},
                       {'X-Entry-Token': token, 'Idempotency-Key': f'warmup-{user}'})
        if len(response) != 1:
            raise RuntimeError('warmup purchase failed')
    with concurrent.futures.ThreadPoolExecutor(max_workers=32) as pool:
        list(pool.map(one, range(1, 101)))


def metric(name, token):
    data = api('GET', '/actuator/metrics/' + name, token)
    return {m['statistic']: m['value'] for m in data['measurements']}


def lock_status():
    data = sql("SHOW GLOBAL STATUS WHERE Variable_name IN ('Innodb_row_lock_time','Innodb_row_lock_waits');")
    return {key: int(value) for key, value in (line.split('\t') for line in data.splitlines())}


def audit(rows, outcomes):
    duplicates = int(sql('SELECT COUNT(*) FROM (SELECT seat_id FROM ticket GROUP BY seat_id HAVING COUNT(*)>1) d;'))
    mismatch = int(sql("SELECT COUNT(*) FROM seat s LEFT JOIN ticket t ON t.seat_id=s.id WHERE s.concert_id=1 "
                       "AND ((s.status='SOLD' AND t.id IS NULL) OR (s.status<>'SOLD' AND t.id IS NOT NULL));"))
    limit = int(sql('SELECT COUNT(*) FROM (SELECT t.user_id,s.concert_id FROM ticket t JOIN seat s ON s.id=t.seat_id '
                    'GROUP BY t.user_id,s.concert_id HAVING COUNT(*)>4) x;'))
    tickets = sql('SELECT user_id,seat_id FROM ticket t JOIN seat s ON s.id=t.seat_id WHERE s.concert_id=1;')
    sold = {}
    for line in tickets.splitlines():
        user, seat = map(int, line.split('\t'))
        sold.setdefault(user, []).append(seat)
    partial = sum(bool(sold.get(row['id'])) and sorted(sold[row['id']]) != sorted(row['seatIds']) for row in rows)
    response_mismatch = sum((outcomes.get(row['id']) == 201) != bool(sold.get(row['id'])) for row in rows)
    return {'duplicate': duplicates, 'sold_mismatch': mismatch, 'over_limit': limit,
            'partial_bundle': partial, 'response_db_mismatch': response_mismatch,
            'tickets': sum(map(len, sold.values())), 'db_success': len(sold)}


def raw_responses(path):
    distribution, outcomes = {}, {}
    with path.open() as stream:
        for line in stream:
            point = json.loads(line)
            if point.get('type') != 'Point' or point.get('metric') != 'purchase_responses':
                continue
            tags = point['data']['tags']
            key = tags['status'] + '/' + (tags['code'] or 'CREATED')
            distribution[key] = distribution.get(key, 0) + int(point['data']['value'])
            user = int(tags['user'])
            if user in outcomes:
                raise RuntimeError(f'duplicate request user={user}')
            outcomes[user] = int(tags['status'])
    return distribution, outcomes


def stop_app(process):
    if process is None or process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=20)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait()


def require_free_port(port):
    with socket.socket() as probe:
        # TIME_WAIT 잔여 소켓은 허용하고 LISTEN 중인 포트만 사용 불가로 본다.
        probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            probe.bind(('127.0.0.1', port))
        except OSError as error:
            raise RuntimeError(f'로컬 포트 {port} 사용 불가: {error.strerror}') from error


def measure(strategy, repeat, scenario, env, jar):
    label = f'{scenario}-{strategy}-{repeat}'
    directory = RAW / label
    directory.mkdir(exist_ok=True)
    for name in ('input.json', 'k6.json', 'summary.json', 'hikari.json', 'result.json'):
        (directory / name).unlink(missing_ok=True)
    count, seats = 1000, 1 if scenario == 'A' else 300
    # Remove inherited Spring/JVM overrides so all strategies get the exact same configuration.
    child_env = {k: v for k, v in os.environ.items() if not k.startswith(('SPRING_', 'TICKET_', 'MANAGEMENT_',
                 'SERVER_', 'LOGGING_', 'JWT_', 'QUEUE_JWT_', 'GOOGLE_')) and
                 k not in {'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'}}
    child_env.update(env, TICKET_PURCHASE_STRATEGY=strategy)
    process = None
    with (directory / 'app.log').open('w') as log:
        try:
            require_free_port(18080)
            redis_reset()
            process = subprocess.Popen(['java', '-Xms1g', '-Xmx1g', '-XX:ActiveProcessorCount=8', '-jar', str(jar),
                                        '--spring.config.additional-location=file:' + str(HERE / 'application-loadtest.yaml')],
                                       env=child_env, stdout=log, stderr=subprocess.STDOUT)
            deadline = time.monotonic() + 120
            while True:
                if process.poll() is not None or time.monotonic() > deadline:
                    raise RuntimeError(f'{label}: app startup failed; raw/{label}/app.log 확인')
                try:
                    if api('GET', '/actuator/health').get('status') == 'UP':
                        break
                except (OSError, ValueError):
                    pass
                time.sleep(0.5)
            seed(count, seats)
            tokens = access_tokens(env, count)
            warmup(tokens)
            seed(count, seats)  # warmup tickets/queue are excluded from measurements and audits.
            rows = plan(scenario, count, seats)
            prepared = prepare(rows, tokens)
            input_path = directory / 'input.json'
            input_path.write_text(json.dumps(prepared))
            config_path = directory / 'k6-config.json'
            config_path.write_text('{}')  # ignore the user's global k6/cloud configuration
            before = lock_status()
            acquire_before = metric('hikaricp.connections.acquire', tokens[1])
            samples, errors = [], []
            done = threading.Event()

            def monitor():
                while not done.is_set():
                    try:
                        samples.append({'time': time.time(),
                                        'active': metric('hikaricp.connections.active', tokens[1])['VALUE'],
                                        'pending': metric('hikaricp.connections.pending', tokens[1])['VALUE']})
                    except Exception as error:
                        errors.append(type(error).__name__)
                    done.wait(0.2)

            thread = threading.Thread(target=monitor, daemon=True)
            thread.start()
            try:
                with (directory / 'k6.log').open('w') as output:
                    k6_env = {k: v for k, v in os.environ.items() if not k.startswith('K6_')}
                    result = subprocess.run(['k6', 'run', '--config', str(config_path), '--no-usage-report',
                                             '-e', 'INPUT=' + str(input_path),
                                             '-e', 'SUMMARY=' + str(directory / 'summary.json'),
                                             '--out', 'json=' + str(directory / 'k6.json'),
                                             str(HERE / 'purchase.js')], env=k6_env, stdout=output,
                                             stderr=subprocess.STDOUT, timeout=240)
            finally:
                done.set()
                thread.join(timeout=25)
                input_path.unlink(missing_ok=True)  # remove signed tokens even when k6 fails.
            after = lock_status()
            acquire_after = metric('hikaricp.connections.acquire', tokens[1])
            (directory / 'hikari.json').write_text(json.dumps({'samples': samples, 'errors': errors}))
            summary = json.loads((directory / 'summary.json').read_text())
            distribution, outcomes = raw_responses(directory / 'k6.json')
            invariants = audit(rows, outcomes)
            checks = ['duplicate', 'sold_mismatch', 'over_limit', 'partial_bundle', 'response_db_mismatch']
            valid = (result.returncode == 0 and len(outcomes) == count and not errors and bool(samples) and
                     all(invariants[k] == 0 for k in checks) and
                     (scenario != 'A' or (invariants['tickets'] == 1 and invariants['db_success'] == 1)) and
                     all(status in (201, 409) for status in outcomes.values()) and
                     all(k in ('201/CREATED', '409/SEAT_NOT_AVAILABLE', '409/SEAT_LOCK_FAILED') for k in distribution))
            acquired = acquire_after['COUNT'] - acquire_before['COUNT']
            row = {'scenario': scenario, 'strategy': strategy, 'repeat': repeat, 'requests': len(outcomes),
                   'success': distribution.get('201/CREATED', 0), 'distribution': distribution,
                   'latency': summary['metrics']['purchase_duration']['values'],
                   'dispatch': summary['metrics']['dispatch_offset_ms']['values'],
                   'hikari_active_max': max((s['active'] for s in samples), default=None),
                   'hikari_pending_max': max((s['pending'] for s in samples), default=None),
                   'hikari_acquire_mean_ms': 1000 * (acquire_after['TOTAL_TIME'] - acquire_before['TOTAL_TIME']) / acquired
                   if acquired else None, 'hikari_samples': len(samples),
                   'innodb_delta': {k: after[k] - before[k] for k in before}, 'audit': invariants,
                   'valid': valid, 'k6_exit': result.returncode}
            (directory / 'result.json').write_text(json.dumps(row, indent=2))
            return row
        finally:
            stop_app(process)


def report(rows, environment, failure=None):
    complete = len(rows) == 18 and all(row['valid'] for row in rows) and not failure
    lines = ['# 구매 전략 로컬 비교', '', '## 상태', '',
             '18회 실측 및 정합성 검사 완료.' if complete else '측정 미완료. 아래 미측정 항목은 성능·정합성 근거로 사용할 수 없다.', '',
             '## 환경·동일 조건', '', '```json', json.dumps(environment, ensure_ascii=False, indent=2), '```', '',
             '- MySQL 8.0.36 / Redis 7.2.4, 별도 Docker 프로젝트·볼륨·127.0.0.1:13306/16379.',
             '- Java 단일 인스턴스, heap 1GiB, ActiveProcessorCount=8, Tomcat 200 threads, Hikari 10 connections.',
             f'- A: 1,000명 → 동일 1석. B: 1,000명 → 300석 HOT 구역에서 고정 seed={SEED}, 균등 무작위 1~4매.',
             '- 각 조건 3회. 매 회 앱 재시작·DB/Redis 초기화·100건 별도 공연 예열 후 측정 데이터 재초기화.',
             '- 큐 API로 전원 입장 후 1 VU=1 사용자=1 구매. 15초 시작 장벽, 재시도 없음. AT/Entry/멱등성 정책 유지.',
             '- 반복별 전략 순서를 회전한다. 같은 시드라도 실제 서버 처리 순서·B의 성공 묶음은 달라질 수 있다.', '',
             '## 실측 결과', '',
             '| 시나리오 | 전략 | 회차 | 요청/201(%) | 409 매진/락 | 5xx/기타 | p50/p95/p99 ms | Hikari 활성/대기 최대 | 획득 평균 ms | InnoDB 대기 ms/회 | 충돌 | 정합성 5항 | 유효 |',
             '|---|---|---:|---|---|---|---|---|---:|---|---:|---|---|']
    indexed = {(r['scenario'], r['strategy'], r['repeat']): r for r in rows}
    for scenario in ('A', 'B'):
        for strategy in STRATEGIES:
            for repeat in (1, 2, 3):
                r = indexed.get((scenario, strategy, repeat))
                if not r:
                    lines.append(f'| {scenario} | {strategy} | {repeat} | 미측정 | — | — | — | — | — | — | — | 미확인 | 미실행 |')
                    continue
                d = r['distribution']
                seat, lock = d.get('409/SEAT_NOT_AVAILABLE', 0), d.get('409/SEAT_LOCK_FAILED', 0)
                server = sum(v for k, v in d.items() if k.startswith('5'))
                other = r['requests'] - r['success'] - seat - lock - server
                latency = '/'.join(f"{r['latency'][k]:.2f}" for k in ('med', 'p(95)', 'p(99)'))
                acquisition = r['hikari_acquire_mean_ms']
                acquisition_text = f'{acquisition:.3f}' if acquisition is not None else '미측정'
                audit_values = '/'.join(str(r['audit'][k]) for k in
                                        ('duplicate', 'sold_mismatch', 'over_limit', 'partial_bundle', 'response_db_mismatch'))
                lines.append(f"| {scenario} | {strategy} | {repeat} | {r['requests']}/{r['success']}"
                             f" ({100*r['success']/1000:.2f}%) | {seat}/{lock} | {server}/{other} | {latency} | "
                             f"{r['hikari_active_max']}/{r['hikari_pending_max']} | "
                             f"{acquisition_text} | {r['innodb_delta']['Innodb_row_lock_time']}/"
                             f"{r['innodb_delta']['Innodb_row_lock_waits']} | {seat+lock} | {audit_values} | "
                             f"{'통과' if r['valid'] else '실패'} |")
    lines += ['', '- 정합성 5항: ticket 좌석 중복 / SOLD↔ticket 불일치 / 공연당 4매 초과 / 부분 묶음 / HTTP 성공↔DB 불일치.',
              '- 충돌은 HTTP 409 SEAT_NOT_AVAILABLE + SEAT_LOCK_FAILED의 합. 내부 낙관 버전 충돌만 따로 식별하는 지표는 아니다.',
              '- p50/p95/p99는 구매 HTTP 전체 응답(201·409·오류)을 포함하며 큐 등록·입장·장벽 시간은 제외한다.',
              '- Hikari 최대는 200ms 간격 목표 표본의 최대값이며 순간 피크를 놓칠 수 있다. 커넥션 획득 평균은 Timer COUNT/TOTAL_TIME 전후 차이다.',
              '- InnoDB 값은 구매 직전/직후 global status 차이. 단위는 ms/대기 횟수. 큐 준비·예열 제외.', '',
              '## 관찰·4-4 예상과 비교·기본 전략 추천', '']
    if complete:
        import statistics
        b = {s: [r for r in rows if r['scenario'] == 'B' and r['strategy'] == s] for s in STRATEGIES}
        p95 = {s: statistics.median(r['latency']['p(95)'] for r in b[s]) for s in STRATEGIES}
        successes = {s: sum(r['success'] for r in b[s]) for s in STRATEGIES}
        recommended = 'pessimistic'
        if p95['distributed'] < 0.8 * p95['pessimistic'] and successes['distributed'] >= successes['pessimistic']:
            recommended = 'distributed'
        lines += [f'- B의 3회 p95 중앙값(ms): {json.dumps(p95)}. 성공 요청 합계: {json.dumps(successes)}.',
                  '- A의 모든 회차는 정확히 1건 성공했고, 두 시나리오의 모든 정합성 위반은 0건이다.',
                  f'- 단일 인스턴스 기본 전략 추천: **{recommended}**. 사전 규칙은 단순한 pessimistic을 기준으로, '
                  'distributed가 B p95를 20% 이상 줄이고 성공 수가 낮지 않을 때 distributed를 추천한다.',
                  '- 4-4의 낙관 락 집중 경합 우려는 409 락 실패·지연을 비관 전략과 비교해야 한다. HTTP 충돌은 매진도 포함하므로 버전 충돌과 동일시하지 않는다.',
                  '- 분산락이 DB 진입 전 경합을 줄이는지는 Hikari 대기와 InnoDB 차이로 판단한다. 이 단일 인스턴스 측정으로 다중 인스턴스 확장 이점은 확정할 수 없다.']
    else:
        lines += ['- 실측이 완료되지 않아 4-4의 예상(집중 경합의 낙관 락 불리, 단일 인스턴스 비관 락 단순, 확장 시 분산락 유리)을 검증하지 못했다.',
                  '- 기본 전략 추천은 보류한다. 운영 기본값 변경 없음. 재실행 후 성공 수·p95·DB 대기·정합성을 함께 비교해야 한다.']
    if rows:
        lines += ['', '### 응답 코드 전체 분포·동시 시작 오차', '']
        for r in rows:
            lines.append(f"- {r['scenario']}/{r['strategy']}/{r['repeat']}: {json.dumps(r['distribution'])}; "
                         f"발송 오차 min/max={r['dispatch']['min']:.2f}/{r['dispatch']['max']:.2f}ms, "
                         f"Hikari 표본={r['hikari_samples']}, 판매 좌석={r['audit']['tickets']}.")
    if failure:
        lines += ['', '## 차단 원인', '', failure, '', '실행 로그: `raw/attempt.log`. 권한 변경·원격 Docker·운영 측정 우회는 하지 않았다.']
    lines += ['', '## 재현·정리', '', '`bash backend/loadtest/run-strategy-comparison.sh`', '',
              '기본 종료 시 측정 컨테이너·볼륨을 제거한다. 실패 시에도 정리한다. 수동 정리는 README 참조.', '']
    REPORT.parent.mkdir(exist_ok=True)
    REPORT.write_text('\n'.join(lines))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--cleanup', action='store_true', help='측정 전용 프로젝트·볼륨 제거만 수행')
    parser.add_argument('--scenarios', default='A,B', help='쉼표 구분 A,B 중 선택 (기본 A,B)')
    parser.add_argument('--strategies', default=','.join(STRATEGIES), help='쉼표 구분 전략 목록')
    parser.add_argument('--repeats', type=int, default=3, help='전략·시나리오별 반복 횟수 (기본 3)')
    parser.add_argument('--run-offset', type=int, default=0, help='회차 번호 시작 오프셋. 기존 raw 결과를 덮어쓰지 않음')
    parser.add_argument('--order', choices=('round-robin',), default='round-robin', help='회차마다 전략 순서를 회전')
    args = parser.parse_args()
    global REPORT
    custom = (args.scenarios, args.strategies, args.repeats, args.run_offset) != ('A,B', ','.join(STRATEGIES), 3, 0)
    if custom:  # 부분 실행은 기존 결과 문서를 덮어쓰지 않고 raw에 별도 보고서를 쓴다.
        REPORT = RAW / f'partial_report_offset{args.run_offset}.md'
    scenarios, strategies = args.scenarios.split(','), args.strategies.split(',')
    if not set(scenarios) <= {'A', 'B'} or not set(strategies) <= set(STRATEGIES):
        parser.error('--scenarios는 A,B / --strategies는 pessimistic,optimistic,distributed 중에서 선택')
    RAW.mkdir(exist_ok=True)
    with (RAW / 'run.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        if args.cleanup:
            command(COMPOSE + ['down', '--volumes', '--remove-orphans'])
            return
        rows, owned = [], False
        environment = {'time': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                       'kernel': platform.release(), 'cpu_logical': os.cpu_count(),
                       'seed': SEED, 'declared_host': 'WSL2 8코어·16GB (사용자 제공)'}
        attempt = RAW / 'attempt.log'
        try:
            for tool in ('docker', 'java', 'k6', 'python3'):
                if not shutil.which(tool):
                    raise RuntimeError(f'필수 도구 없음: {tool}')
            with attempt.open('w') as log:
                environment['k6'] = captured(['k6', 'version'])
                java = command(['java', '-version'], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                environment['java'] = java.stderr.splitlines()[0]
                environment['docker_cli'] = captured(DOCKER + ['--version'])
                environment['memory'] = next(line.strip() for line in Path('/proc/meminfo').read_text().splitlines()
                                             if line.startswith('MemTotal:'))
                # Fail before touching any resources if local Docker is unavailable.
                command(DOCKER + ['info', '--format', '{{.ServerVersion}}'], stdout=log, stderr=subprocess.STDOUT)
                environment['docker'] = captured(DOCKER + ['version', '--format', '{{.Server.Version}}'])
                for port in (13306, 16379, 18080):
                    require_free_port(port)
                env = local_env()
                from cryptography.hazmat.primitives import serialization  # installed prerequisite
                command(['./gradlew', '--no-daemon', 'bootJar'], cwd=BACKEND, stdout=log, stderr=subprocess.STDOUT)
                jars = list((BACKEND / 'build/libs').glob('*.jar'))
                jars = [p for p in jars if not p.name.endswith('-plain.jar')]
                if len(jars) != 1:
                    raise RuntimeError('build/libs에서 실행 jar를 하나만 유지해야 한다')
                environment['jar_sha256'] = hashlib.sha256(jars[0].read_bytes()).hexdigest()
                owned = True
                command(COMPOSE + ['up', '-d', '--wait', '--wait-timeout', '240'], stdout=log, stderr=subprocess.STDOUT)
            for repeat in range(args.run_offset + 1, args.run_offset + args.repeats + 1):
                shift = (repeat - 1) % len(strategies)
                order = strategies[shift:] + strategies[:shift]
                for scenario in scenarios:
                    for strategy in order:
                        print(f'측정: {scenario}/{strategy}/{repeat}', flush=True)
                        row = measure(strategy, repeat, scenario, env, jars[0])
                        rows.append(row)
                        report(rows, environment)
                        if not row['valid']:
                            raise RuntimeError(f'{scenario}/{strategy}/{repeat}: 측정/정합성 검증 실패; raw result.json 확인')
            report(rows, environment)
        except Exception as error:
            # Do not print captured output or environment: they may contain secrets.
            reason = str(error)
            if isinstance(error, subprocess.CalledProcessError):
                reason = f'명령 실패(exit={error.returncode}): ' + ' '.join(error.cmd)
                if attempt.exists() and 'permission denied' in attempt.read_text().lower():
                    reason += '\n\n로컬 Docker API 소켓 unix:///var/run/docker.sock: permission denied.'
            report(rows, environment, reason)
            print(reason, file=sys.stderr)
            raise SystemExit(1)
        finally:
            if owned:
                with attempt.open('a') as log:
                    cleanup = subprocess.run(COMPOSE + ['down', '--volumes', '--remove-orphans'],
                                             text=True, stdout=log, stderr=subprocess.STDOUT)
                if cleanup.returncode:
                    print('측정 리소스 자동 정리 실패: --cleanup으로 재시도', file=sys.stderr)
                    with REPORT.open('a') as output:
                        output.write('\n자동 정리 실패. `--cleanup` 재실행 필요.\n')
                    raise SystemExit(1)


if __name__ == '__main__':
    main()
