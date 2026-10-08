#!/usr/bin/env python3
"""Phase 6-3/4, shared local lifecycle; imports the existing measurement helpers."""
import argparse
import datetime
import fcntl
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import re
import shutil
import signal
import statistics
import subprocess
import sys
import threading
import time

import compare as c

HERE = c.HERE
RAW = c.RAW / 'phase6'
PAIRS = [(250, 50), (500, 50), (500, 100), (1000, 100), (1000, 200), (2000, 100), (2000, 200)]
CANDIDATES = {
    'baseline': None,
    'ix_seat_concert_status': ('seat', 'concert_id,status'),
    'ix_ticket_user_created': ('ticket', 'user_id,created_at'),
    'ix_concert_ticket_period': ('concert', 'ticket_open_at,ticket_close_at'),
}
# SQL equivalents of the actual JPQL/derived queries, including EntityGraph joins.
QUERIES = {
    'available': "SELECT s.* FROM seat s WHERE s.concert_id=2 AND s.status='AVAILABLE'",
    'sections': "SELECT s.section_name,COUNT(*) FROM seat s WHERE s.concert_id=2 AND s.status='AVAILABLE' GROUP BY s.section_name ORDER BY s.section_name",
    'history': 'SELECT t.*,s.*,c.* FROM ticket t LEFT JOIN seat s ON s.id=t.seat_id LEFT JOIN concert c ON c.id=s.concert_id WHERE t.user_id=1 ORDER BY t.created_at DESC',
    'concerts': 'SELECT c.* FROM concert c',
}


def percentile(values, percent):
    if not values:
        return None
    ordered = sorted(values)
    position = (len(ordered) - 1) * percent / 100
    low = math.floor(position)
    return ordered[low] + (ordered[math.ceil(position)] - ordered[low]) * (position - low)


def explain_nodes(output):
    """Keep each node, including loops; never call root output rows scanned rows."""
    nodes = []
    for line in output.replace('\\n', '\n').splitlines():
        match = re.search(r'actual time=([\d.eE+-]+)\.\.([\d.eE+-]+) rows=([\d.eE+-]+) loops=(\d+)', line)
        if match:
            first, last, rows, loops = map(float, match.groups())
            nodes.append({'path': line.split('(cost=')[0].split('(actual time=')[0].strip(),
                          'first_ms': first, 'last_ms': last, 'rows_per_loop': rows,
                          'loops': int(loops), 'rows_total': rows * loops})
    if not nodes:
        raise ValueError('EXPLAIN ANALYZE actual rows/time missing')
    return nodes


def parse_points(path):
    distribution, outcomes, times = {}, {}, {}
    with path.open() as source:
        for line in source:
            point = json.loads(line)
            if point.get('type') != 'Point' or point.get('metric') != 'api_responses':
                continue
            data = point['data']
            tags = data['tags']
            name, status = tags['api'], int(tags['status'])
            distribution.setdefault(name, {})
            key = str(status)
            distribution[name][key] = distribution[name].get(key, 0) + int(data['value'])
            times.setdefault(name, []).append(datetime.datetime.fromisoformat(data['time'].replace('Z', '+00:00')).timestamp())
            if name == 'purchase':
                user = int(tags['user'])
                if user in outcomes:
                    raise ValueError('duplicate purchase user')
                outcomes[user] = status
    return distribution, outcomes, times


def api_stats(summary, distribution, times, mode):
    result = {}
    for name, counts in distribution.items():
        total = sum(counts.values())
        server = sum(v for k, v in counts.items() if 500 <= int(k) <= 599)
        other = sum(v for k, v in counts.items() if not 200 <= int(k) < 300 and int(k) != 409)
        # Reads each have a fixed 45-second scheduled window. Purchases use their
        # observed response span including the first request's duration, not k6 wall time.
        latency = summary['metrics'][name + '_duration']['values']
        span = 45 if mode == 'reads' else max(0.001, max(times[name]) - min(times[name]) + latency['min'] / 1000)
        result[name] = {'p95': latency['p(95)'], 'p99': latency['p(99)'], 'requests': total,
                        '5xx': server, 'non409_errors': other, 'non409_error_rate': other / total,
                        'tps': total / span, 'success_tps': counts.get('201', 0) / span,
                        'window_seconds': span, 'codes': counts}
    return result


def queue_pass(row):
    purchase = row['apis'].get('purchase', {})
    return (row['valid'] and row['occupancy_max'] >= 0.8 * row['max_concurrent']
            and purchase.get('p95', math.inf) <= 1000 and purchase.get('p99', math.inf) <= 2000
            and all(a['5xx'] == 0 and a['non409_error_rate'] < 0.001 for a in row['apis'].values()))


def recommend(rows, pairs, repeats):
    # All requested trials must exist and every repeat of a pair must pass.
    # pairs: the focus pairs that must have all `repeats` trials (boundary run may be a subset).
    if any(sum((r['max_concurrent'], r['admit_per_cycle']) == pair for r in rows) != repeats for pair in pairs):
        return None
    eligible = []
    for maximum, admit in pairs:
        trials = [r for r in rows if (r['max_concurrent'], r['admit_per_cycle']) == (maximum, admit)]
        if len(trials) == repeats and all(queue_pass(r) for r in trials):
            eligible.append((maximum, admit))
    return max(eligible, default=None)  # tie: largest passing admission batch


def seed_dataset(anchor):
    statements = ['DELETE FROM ticket; DELETE FROM seat; DELETE FROM concert; DELETE FROM refresh_token; DELETE FROM auth_session; DELETE FROM users;']
    def insert(table, columns, rows):
        batch = []
        for row in rows:
            batch.append(row)
            if len(batch) == 1000:
                statements.append(f'INSERT INTO {table}({columns}) VALUES ' + ','.join(batch) + ';')
                batch = []
        if batch:
            statements.append(f'INSERT INTO {table}({columns}) VALUES ' + ','.join(batch) + ';')
    insert('users', 'id,google_sub,email,name,role',
           (f"({u},'phase6-{u}','p{u}@example.invalid','phase6','{'ARTIST' if u == 10001 else 'FAN'}')" for u in range(1, 10002)))
    insert('auth_session', 'id,user_id,expires_at', (f'({u},{u},UTC_TIMESTAMP()+INTERVAL 1 DAY)' for u in range(1, 10001)))
    opening = (anchor - datetime.timedelta(days=1)).strftime('%Y-%m-%d %H:%M:%S')
    closing = (anchor + datetime.timedelta(days=1)).strftime('%Y-%m-%d %H:%M:%S')
    concert = (anchor + datetime.timedelta(days=2)).strftime('%Y-%m-%d %H:%M:%S')
    insert('concert', 'id,artist_id,title,venue,concert_at,ticket_open_at,ticket_close_at',
           (f"({i},10001,'phase6-{i}','local','{concert}','{opening}','{closing}')" for i in range(1, 21)))
    def seats():
        for performance in range(1, 21):
            for position in range(15000):
                seat = (performance - 1) * 15000 + position + 1
                section = chr(65 + position // 1500)
                status = 'SOLD' if performance > 1 and position < 2000 else 'AVAILABLE'
                yield f"({seat},{performance},'{section}',{position % 1500 // 30 + 1},{position % 30 + 1},10000,'{status}')"
    insert('seat', 'id,concert_id,section_name,row_no,seat_no,price,status', seats())
    def tickets():
        for i in range(38000):
            performance, position = i // 2000 + 2, i % 2000
            seat = (performance - 1) * 15000 + position + 1
            # fixed seed permutation spreads histories, 2k distinct users per concert
            user = ((i * 7919 + c.SEED) % 10000) + 1
            created = (datetime.datetime(2026, 1, 1) + datetime.timedelta(seconds=i)).strftime('%Y-%m-%d %H:%M:%S')
            yield f"({i+1},{user},{seat},10000,'{created}','seed-{i+1}','seed-{i+1}')"
    insert('ticket', 'id,user_id,seat_id,purchase_price,created_at,purchase_key,admission_id', tickets())
    c.sql('\n'.join(statements))
    c.redis_reset()


def build_agent(directory):
    target = directory / 'agent'
    target.mkdir(exist_ok=True)
    c.command(['javac', '-d', str(target), str(HERE / 'agent/WorkerTimer.java')])
    manifest = target / 'manifest.mf'
    manifest.write_text('Premain-Class: WorkerTimer\n\n')
    jar = directory / 'worker-timer.jar'
    c.command(['jar', 'cfm', str(jar), str(manifest), '-C', str(target), '.'])
    return jar


def worker_samples(log, start, end):
    return [{'time_ms': int(t), 'duration_ms': int(ns) / 1e6}
            for t, ns in re.findall(r'LOADTEST_WORKER (\d+) (\d+)', log.read_text())
            if start * 1000 <= int(t) <= end * 1000]


def run_k6(directory, script, rows, token, mode, occupancy=False):
    directory.mkdir(exist_ok=True)
    input_path = directory / 'input.json'
    for name in ('input.json', 'summary.json', 'k6.json', 'hikari.json'):
        (directory / name).unlink(missing_ok=True)
    (directory / 'k6-config.json').write_text('{}')
    samples, errors = [], []
    done = threading.Event()
    before = c.metric('hikaricp.connections.acquire', token)
    def monitor():
        while not done.is_set():
            try:
                sample = {'time': time.time(), 'active': c.metric('hikaricp.connections.active', token)['VALUE'],
                          'pending': c.metric('hikaricp.connections.pending', token)['VALUE']}
                if occupancy:
                    count = c.captured(c.COMPOSE + ['exec', '-T', 'redis', 'redis-cli', '--raw', 'EVAL',
                        "return redis.call('ZCARD',KEYS[1])+redis.call('ZCARD',KEYS[2])",
                        '2', 'queue:1:active', 'queue:1:admitted'])
                    sample['occupancy'] = int(count)
                samples.append(sample)
            except Exception as error:
                errors.append(type(error).__name__)
            done.wait(1)
    thread = threading.Thread(target=monitor, daemon=True)
    start = time.time()
    try:
        input_path.write_text(json.dumps(rows))
        thread.start()
        with (directory / 'k6.log').open('w') as log:
            result = subprocess.run(['k6', 'run', '--config', str(directory / 'k6-config.json'), '--no-usage-report',
                                     '-e', 'INPUT=' + str(input_path), '-e', 'SUMMARY=' + str(directory / 'summary.json'),
                                     '-e', 'MODE=' + mode, '--out', 'json=' + str(directory / 'k6.json'), str(script)],
                                    env={k: v for k, v in os.environ.items() if not k.startswith('K6_')},
                                    stdout=log, stderr=subprocess.STDOUT, timeout=920 if occupancy else 900 if mode == 'reads' else 180)
    finally:
        done.set()
        if thread.ident is not None:
            thread.join(timeout=25)
        input_path.unlink(missing_ok=True)
        (directory / 'hikari.json').write_text(json.dumps({'samples': samples, 'errors': errors}, indent=2))
    end = time.time()
    after = c.metric('hikaricp.connections.acquire', token)
    acquisitions = after['COUNT'] - before['COUNT']
    summary = json.loads((directory / 'summary.json').read_text())
    distribution, outcomes, times = parse_points(directory / 'k6.json')
    apis = api_stats(summary, distribution, times, mode)
    return {'apis': apis, 'outcomes': outcomes, 'start': start, 'end': end,
            'hikari_pending_max': max((s['pending'] for s in samples), default=None),
            'hikari_active_max': max((s['active'] for s in samples), default=None),
            'hikari_acquire_mean_ms': (after['TOTAL_TIME'] - before['TOTAL_TIME']) * 1000 / acquisitions if acquisitions else None,
            'occupancy_max': max((s.get('occupancy', 0) for s in samples), default=0),
            'valid': result.returncode == 0 and bool(samples) and not errors,
            'k6_exit': result.returncode}


def snapshots(directory, expected):
    actual_candidates = set()
    for table in ('seat', 'ticket', 'concert'):
        output = c.sql(f'SHOW INDEX FROM {table};')
        (directory / f'show-index-{table}.tsv').write_text(output)
        actual_candidates.update(line.split('\t')[2] for line in output.splitlines()
                                 if line.split('\t')[2] in CANDIDATES)
    if actual_candidates != (set() if expected == 'baseline' else {expected}):
        raise RuntimeError('SHOW INDEX: actual candidate set differs from requested single candidate')
    c.sql('ANALYZE TABLE seat,ticket,concert;')
    plans = {}
    for name, query in QUERIES.items():
        # Discard one warm plan, then keep three complete trees.
        c.sql('EXPLAIN ANALYZE ' + query + ';')
        plans[name] = []
        for repeat in range(1, 4):
            output = c.sql('EXPLAIN ANALYZE ' + query + ';')
            (directory / f'explain-{name}-{repeat}.txt').write_text(output)
            plans[name].append(explain_nodes(output))
    sizes = c.sql("SELECT table_name,index_name,stat_value*@@innodb_page_size FROM mysql.innodb_index_stats WHERE database_name='ticket_loadtest' AND stat_name='size' ORDER BY table_name,index_name;")
    (directory / 'index-sizes.tsv').write_text(sizes)
    return plans, sizes


def measure(kind, condition, repeat, env, jar, agent, anchor):
    label = f'{kind}-{condition[0]}-{condition[1]}-{repeat}' if kind == 'queue' else f'index-{condition}-{repeat}'
    directory = RAW / label
    directory.mkdir(exist_ok=True)
    # Fresh schema per trial retains all Flyway/FK indices and avoids implicit FK
    # index replacement by a candidate contaminating the following baseline.
    c.sql('DROP DATABASE ticket_loadtest; CREATE DATABASE ticket_loadtest;')
    c.redis_reset()
    maximum, admit = condition if kind == 'queue' else (2000, 2000)
    flags = [f'--ticket.queue.max-concurrent={maximum}', f'--ticket.queue.admit-per-cycle={admit}']
    if kind == 'index':
        flags += ['--ticket.queue.worker-delay-ms=100', '--ticket.queue.worker-initial-delay-ms=100',
                  '--ticket.queue.fast-poll-ms=100', '--ticket.queue.medium-poll-ms=100', '--ticket.queue.slow-poll-ms=100']
    child_env = {k: v for k, v in os.environ.items() if not k.startswith(('SPRING_', 'TICKET_', 'MANAGEMENT_',
                 'SERVER_', 'LOGGING_', 'JWT_', 'QUEUE_JWT_', 'GOOGLE_', 'AUTH_')) and
                 k not in {'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'}}
    child_env.update(env, TICKET_PURCHASE_STRATEGY='pessimistic')
    process = None
    with (directory / 'app.log').open('w') as log:
        try:
            c.require_free_port(18080)
            process = subprocess.Popen(['java', '-javaagent:' + str(agent), '-Xms1g', '-Xmx1g',
                                        '-XX:ActiveProcessorCount=8', '-jar', str(jar), '--spring.profiles.active=prod',
                                        '--spring.config.additional-location=file:' + str(HERE / 'application-phase6.yaml'), *flags],
                                       env=child_env, stdout=log, stderr=subprocess.STDOUT)
            deadline = time.monotonic() + 120
            while True:
                if process.poll() is not None or time.monotonic() > deadline:
                    raise RuntimeError(f'{label}: app startup failed (app.log)')
                try:
                    if c.api('GET', '/actuator/health')['status'] == 'UP':
                        break
                except (OSError, ValueError):
                    pass
                time.sleep(0.5)
            c.seed(100, 100)
            c.warmup(c.access_tokens(env, 100))
            seed_dataset(anchor)
            if kind == 'index' and CANDIDATES[condition]:
                table, columns = CANDIDATES[condition]
                c.sql(f'CREATE INDEX {condition} ON {table}({columns});')
            tokens = c.access_tokens(env, 2000 if kind == 'queue' else 1000)
            if kind == 'queue':
                users = [{'id': u, 'at': tokens[u], 'arrivalMs': 0 if u <= 1000 else 10000 if u <= 1500 else 30000} for u in tokens]
                result = run_k6(directory / 'flow', HERE / 'queue/queue.js', users, tokens[1], 'queue', True)
                worker = worker_samples(directory / 'app.log', result['start'], result['end'])
                result.update(max_concurrent=maximum, admit_per_cycle=admit, worker=worker,
                              worker_p95_ms=percentile([s['duration_ms'] for s in worker], 95),
                              worker_max_ms=max((s['duration_ms'] for s in worker), default=None),
                              arrival= json.loads((directory / 'flow/summary.json').read_text())['metrics'].get('arrival_offset_ms', {}).get('values'),
                              active_residence=json.loads((directory / 'flow/summary.json').read_text())['metrics'].get('active_residence_ms', {}).get('values'))
                result['valid'] &= bool(worker) and set(result['apis']) == {'register', 'status', 'enter', 'seat', 'purchase'}
            else:
                plans, sizes = snapshots(directory, condition)
                for _ in range(10):
                    for path in ('/api/concerts/2/seats/availability', '/api/concerts/2/seats/available', '/api/tickets/me', '/api/concerts'):
                        c.api('GET', path, tokens[1])
                reads = run_k6(directory / 'reads', HERE / 'index/index.js', [{'id': u, 'at': tokens[u]} for u in tokens], tokens[1], 'reads')
                rows = [{'id': u, 'seatIds': [u], 'key': f'index-{u}'} for u in tokens]
                prepared = c.prepare(rows, tokens)  # real queue, excluded from purchase window
                purchase = run_k6(directory / 'purchase', HERE / 'index/index.js', prepared, tokens[1], 'purchase')
                result = purchase
                result.update(candidate=condition, plans=plans, sizes=sizes, reads=reads)
                result['apis'] = {**reads['apis'], **purchase['apis']}
                result['valid'] &= reads['valid'] and set(result['apis']) == {'seat', 'available', 'ticket', 'concert', 'purchase'}
            rows = [{'id': u, 'seatIds': [u]} for u in tokens]
            audit = c.audit(rows, result['outcomes'])
            result['audit'] = audit
            result['valid'] &= len(result['outcomes']) == len(tokens) and all(audit[k] == 0 for k in
                ('duplicate', 'sold_mismatch', 'over_limit', 'partial_bundle', 'response_db_mismatch'))
            result['valid'] &= all(s in (201, 409) for s in result['outcomes'].values())
            result['valid'] &= audit['db_success'] == len(tokens)  # unique free seats: avoid fast-409 SLO bias
            # Non-409 errors fail index comparisons; queue SLO failures remain
            # recorded trials rather than stopping the remaining candidates.
            if kind == 'index':
                result['valid'] &= all(a['non409_errors'] == 0 for a in result['apis'].values())
            result['repeat'] = repeat
            result.pop('outcomes')
            (directory / 'result.json').write_text(json.dumps(result, indent=2))
            return result
        finally:
            c.stop_app(process)


def source_refs():
    definitions = [('domain/seat/repository/SeatRepository.java', 'findByConcertIdAndStatus'),
                   ('domain/seat/repository/SeatRepository.java', 'countAvailableSeatsBySection'),
                   ('domain/ticket/repository/TicketRepository.java', 'findByUserIdOrderByCreatedAtDesc'),
                   ('domain/concert/service/ConcertService.java', 'concertRepository.findAll()')]
    refs = []
    for relative, needle in definitions:
        path = c.BACKEND / 'src/main/java/com/project/ticket' / relative
        line = next(i for i, text in enumerate(path.read_text().splitlines(), 1) if needle in text)
        refs.append(f'backend/src/main/java/com/project/ticket/{relative}:{line}')
    return refs


def report(kind, rows, environment, conditions, repeats, failure=None, focus=None):
    path = HERE / 'results' / ('queue_numbers.md' if kind == 'queue' else 'index_comparison.md')
    focus = focus or conditions
    count = lambda cond: sum(((r['max_concurrent'], r['admit_per_cycle']) == cond if kind == 'queue' else r['candidate'] == cond) for r in rows)
    complete = all(count(cond) == repeats for cond in focus) and not failure
    lines = [f'# {"대기열 수치" if kind == "queue" else "인덱스 후보"} 로컬 측정', '',
             '실측 완료.' if complete else ('실측 미실행.' if not rows else '실측 부분 완료. 전체 비교 미완료.'), '',
             '**로컬 잠정값 · 운영 재측정 권장.**', '', '## 조건', '',
             '```json', json.dumps(environment, ensure_ascii=False, indent=2), '```', '',
             '- 로컬 Docker MySQL 8.0.36(13306) / Redis 7.2.4(16379), prod 프로필 JVM(18080), pessimistic.',
             '- Hikari 10/10, Tomcat max 200/min-spare 10/accept-count 100: 운영 프로필의 기본값과 동일.',
             '- heap 1GiB/CPU 8은 로컬 고정값(운영 JVM 자원은 별도 확인). Redis TLS 꺼짐, metrics 노출, WARN 로그는 측정 차이.',
             '- 매 회 스키마 재생성(Flyway V1/V2), 구매 100건 예열 뒤 DB/Redis 재초기화. 조회 캐시 없음.',
             f'- 공연 20 × 좌석 15,000 / FAN 10,000 + ARTIST 1 / 기존 티켓 38,000. seed={c.SEED}; '
             '공연 1은 빈 예매, 나머지는 각각 SOLD 2,000석. 티켓 시간·사용자 배치 고정, 예매 기간은 실행 시작 UTC 기준으로 모든 회차 동일.',
             f'- 각 조건 {repeats}회, 회차마다 조건 순서 회전. 베이스라인에는 기존 정합성/FK/V2 멱등성 인덱스가 포함됨.', '',
             '## API 결과', '',
             '| 조건 | 회 | API | p95/p99 ms | TPS / 201 TPS | 5xx | 비409 오류/전체(율) | 응답 분포 |',
             '|---|---:|---|---|---|---:|---|---|']
    for condition in conditions:
        label = f'{condition[0]}/{condition[1]}' if kind == 'queue' else condition
        trials = [r for r in rows if ((r['max_concurrent'], r['admit_per_cycle']) == condition if kind == 'queue' else r['candidate'] == condition)]
        if not trials:
            lines.append(f'| {label} | — | — | 미측정 | 미측정 | 미확인 | 미확인 | — |')
        for r in trials:
            for name, a in r['apis'].items():
                lines.append(f"| {label} | {r['repeat']} | {name} | {a['p95']:.2f}/{a['p99']:.2f} | {a['tps']:.2f}/{a['success_tps']:.2f} | {a['5xx']} | {a['non409_errors']}/{a['requests']} ({a['non409_error_rate']:.4%}) | {json.dumps(a['codes'])} |")
    lines += ['', '- TPS: 읽기는 API별 45초 창, 구매는 첫 응답~마지막 응답 + 최소 HTTP 소요 창의 처리량. queue 등록/폴링 TPS는 전체 흐름 중 각 API 응답 창 기준.',
              '- 지연은 오류 포함 모든 응답. 비409 오류는 transport(status=0), 4xx(409 제외), 5xx를 포함. 5xx는 별도 계수.',
              '- Hikari 표본은 1초 목표 주기(지표/Redis 요청만큼 늘어남), 획득 평균은 Timer COUNT/TOTAL_TIME 차이. 순간 최대는 놓칠 수 있음.', '',
              '## Hikari·검증', '', '| 조건/회 | 활성/대기 최대 | 획득 평균 ms | 구매 정합성 | 유효 |', '|---|---|---|---|---|']
    for r in rows:
        label = f"{r['max_concurrent']}/{r['admit_per_cycle']}" if kind == 'queue' else r['candidate']
        lines.append(f"| {label}/{r['repeat']} | {r['hikari_active_max']}/{r['hikari_pending_max']} | {r['hikari_acquire_mean_ms']} | {json.dumps(r['audit'])} | {r['valid']} |")
        if kind == 'index':
            rd = r['reads']
            lines.append(f"| {label}/{r['repeat']} 조회 | {rd['hikari_active_max']}/{rd['hikari_pending_max']} | {rd['hikari_acquire_mean_ms']} | 해당 없음 | {rd['valid']} |")
    if kind == 'queue':
        lines += ['', '## 판정·관찰·추천', '',
                  '- **잠정 기준(사용자 확정 대상): 구매 p95 ≤ 1s, p99 ≤ 2s; 모든 API 5xx=0, 비409 오류율 <0.1%.**',
                  '- 등록 → 서버 nextPollAfterMs 준수 status → 실제 Worker 입장 → enter → 좌석 요약/구역 3회 → 생각 시간 → 구매 1회.',
                  '- 총 2,000명, 오픈 직후 1,000명/10초 500명/30초 500명 도착. ACTIVE 생각 시간 75~90초, TTL 5분·Worker fixedDelay 5초 유지.',
                  '- 고유 빈 좌석이므로 전원 201·완료/미발송·HTTP↔DB 검사도 유효성 조건. capacity(ADMITTED+ACTIVE) 표본이 max의 80% 미만이면 해당 max 검증 불충분으로 추천 제외.',
                  '- Worker는 외부 Java agent로 runCycle 전체를 nanoTime 계측(예외 포함), 앱 내부 작업/Redis 왕복 포함. 로그 출력 자체의 오버헤드는 제외.', '',
                  '| max/admit/회 | 실제 예약 점유 최대 | Worker p95/max ms | SLO·부하 조건 충족 |', '|---|---:|---|---|']
        for r in rows:
            lines.append(f"| {r['max_concurrent']}/{r['admit_per_cycle']}/{r['repeat']} | {r['occupancy_max']} | {r['worker_p95_ms']}/{r['worker_max_ms']} | {queue_pass(r)} |")
        for r in rows:
            lines.append(f"- {r['max_concurrent']}/{r['admit_per_cycle']}/{r['repeat']}: 도착 오차 ms={json.dumps(r['arrival'])}, ACTIVE 체류 ms={json.dumps(r['active_residence'])}.")
        if focus != conditions:
            lines.append(f'- 측정 구성: 전체 {len(conditions)}조합 탐색 1회 + 경계 조합 {focus} 반복(총 {repeats}회). 추천은 반복 {repeats}회가 모두 기준을 통과한 경계 조합만 대상.')
        best = recommend(rows, focus, repeats) if complete else None
        lines += ['', f'- 추천: max-concurrent={best[0]}, admit-per-cycle={best[1]}. 모든 반복 통과 중 최대 max, 동률이면 최대 admit.' if best else '- 추천 보류: 전체 실측 및 기준·충분한 점유를 통과한 조합 근거가 없음.',
                  '- max는 실제 이용자 수가 아닌 상한이며 구매 성공 시 ACTIVE가 제거됨. admit과 생각 시간 때문에 큰 max가 포화되지 않을 수 있음. 운영 도착률·체류시간으로 재측정 필요.']
    else:
        refs = source_refs()
        lines += ['', '## 실제 쿼리 근거', '',
                  f'- 좌석 AVAILABLE 조회: `{refs[0]}`; 구역 집계(부하 API): `{refs[1]}`.',
                  f'- 사용자 티켓 최신순 + seat/concert EntityGraph: `{refs[2]}`.',
                  f'- 공연 API는 기간 조건 없는 findAll: `{refs[3]}`. **기간 조회 쿼리 없음**: ix_concert_ticket_period 목표 효과 검증 불가.',
                  '- EXPLAIN은 실제 Repository 쿼리와 동등한 SQL(QUERIES 상수), Hibernate 별칭/필드 선택과 차이 있음. 공연은 실제 findAll만 분석하며 가상의 기간 쿼리는 쓰지 않음.', '',
                  '## EXPLAIN ANALYZE·크기', '',
                  '- 각 쿼리 예열 1회 제외 후 3회. 아래는 첫 계획의 모든 접근 노드; 전체 세 번 트리는 raw에 저장.',
                  '- actual rows는 노드 출력 행/loop. 스캔 노드 rows×loops를 실제 접근 행으로 읽고, filter/root 출력과 혼동하지 않음.', '',
                  '| 조건/회/쿼리 | 접근 노드 | actual first/last ms | rows/loop × loops = 전체 |', '|---|---|---|---|']
        for r in rows:
            for query, plans in r['plans'].items():
                for n in plans[0]:
                    lines.append(f"| {r['candidate']}/{r['repeat']}/{query} | {n['path'].replace('|', '/')} | {n['first_ms']}/{n['last_ms']} | {n['rows_per_loop']} × {n['loops']} = {n['rows_total']} |")
            lines += ['', f"크기 bytes ({r['candidate']}/{r['repeat']}, InnoDB 통계 page数 × page_size, 근사 할당 공간):", '', '```tsv', r['sizes'], '```', '']
        lines += ['', '## 쓰기 비용·관찰·추천', '',
                  '- 조회: 각 API 40 VU/45초, 순차 창, 생각 시간 100ms. 공연 2 좌석 구역 집계·AVAILABLE 전체 / 사용자 티켓 / 전체 공연 목록.',
                  '- 쓰기: 1,000명 실제 큐 입장 준비 후 동일 창에 고유 공연1 좌석 1매 구매. 201 TPS는 INSERT ticket + UPDATE seat 트랜잭션 비용을 함께 반영(독립 DML 비용 아님).',
                  '- index 실험의 큐 준비만 max/admit=2000/2000, Worker·poll=100ms; 구매 측정 창 제외. 큐 수치 실험과 혼동 금지.',
                  '- 후보는 매번 새 V1/V2 베이스라인에 정확히 하나만 DDL 적용, SHOW INDEX 원문으로 FK 자동 인덱스 포함 실제 구성을 확인. V3 없음.', '']
        if complete and all(r['valid'] for r in rows):
            grouped = {name: [r for r in rows if r['candidate'] == name] for name in conditions}
            base = grouped['baseline']
            for name, api in [('ix_seat_concert_status', 'seat'), ('ix_ticket_user_created', 'ticket'), ('ix_concert_ticket_period', 'concert')]:
                p95 = statistics.median(r['apis'][api]['p95'] for r in grouped[name])
                bp95 = statistics.median(r['apis'][api]['p95'] for r in base)
                tps = statistics.median(r['apis']['purchase']['success_tps'] for r in grouped[name])
                btps = statistics.median(r['apis']['purchase']['success_tps'] for r in base)
                lines.append(f'- {name}: 조회 p95 중앙값 {p95:.2f}ms (baseline {bp95:.2f}, 변화 {100*(p95/bp95-1):+.2f}%), 구매 201 TPS {tps:.2f} (baseline {btps:.2f}, 변화 {100*(tps/btps-1):+.2f}%).')
                suggested = name != 'ix_concert_ticket_period' and p95 < bp95 * 0.9 and tps >= btps * 0.95
                lines.append('- 잠정 채택 검토 추천(조회 p95 10% 이상 감소·쓰기 저하 5% 이내); 접근 계획과 크기도 사용자 검토 필요.' if suggested else '- 채택 보류: 목표 조회가 없거나 잠정 개선/쓰기 기준 불충족.')
        else:
            lines += ['- 실측 근거 부족으로 세 후보 채택 보류. 기간 후보는 목표 쿼리가 추가될 때 다시 검토.']
    if failure:
        lines += ['', '## 차단 원인', '', failure, '', 'raw/phase6/attempt.log 확인. 미측정값을 0/통과로 해석하지 않는다.']
    lines += ['', '## 재현·정리', '', f'`bash backend/loadtest/{kind}/run-{"queue-numbers" if kind == "queue" else "index-comparison"}.sh`', '',
              '실패/종료 시 측정 JVM·컨테이너·볼륨 정리. 개발 3306/6379/8080 접근 없음. 원시 출력은 gitignore.', '']
    path.write_text('\n'.join(lines))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('kind', choices=['queue', 'index'])
    parser.add_argument('--repeats', type=int, default=3)
    parser.add_argument('--cleanup', action='store_true')
    parser.add_argument('--pairs', help='queue only: boundary pairs like 1000x100,1000x200 (default: all)')
    parser.add_argument('--repeat-start', type=int, default=1, help='first repeat number; earlier raw results are reloaded')
    args = parser.parse_args()
    if args.repeats < 1:
        parser.error('--repeats >= 1')
    RAW.mkdir(parents=True, exist_ok=True)
    conditions = PAIRS if args.kind == 'queue' else list(CANDIDATES)
    focus = conditions
    if args.pairs:
        if args.kind != 'queue':
            parser.error('--pairs is queue only')
        focus = [tuple(map(int, item.split('x'))) for item in args.pairs.split(',')]
        if any(pair not in PAIRS for pair in focus):
            parser.error('--pairs must be among ' + ','.join(f'{m}x{a}' for m, a in PAIRS))
    rows, owned = [], False
    for earlier in range(1, args.repeat_start):
        for pair in conditions:
            saved = RAW / f'queue-{pair[0]}-{pair[1]}-{earlier}' / 'result.json'
            if args.kind == 'queue' and saved.exists():
                rows.append(json.loads(saved.read_text()))
    anchor = datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0)
    environment = {'UTC': anchor.isoformat(), 'kernel': platform.release(), 'cpu_logical': os.cpu_count(),
                   'conditions': conditions, 'repeats': args.repeats}
    attempt = RAW / 'attempt.log'
    def interrupted(signum, frame):
        raise RuntimeError(f'interrupted signal {signum}')
    with (c.RAW / 'run.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        if args.cleanup:
            c.command(c.COMPOSE + ['down', '--volumes', '--remove-orphans'])
            return
        signal.signal(signal.SIGTERM, interrupted)
        signal.signal(signal.SIGINT, interrupted)
        failure = None
        try:
            for tool in ('docker', 'java', 'javac', 'jar', 'k6'):
                if not shutil.which(tool):
                    raise RuntimeError(f'필수 도구 없음: {tool}')
            with attempt.open('w') as log:
                # Fail before resource creation. No remote context/socket fallback.
                c.command(c.DOCKER + ['info', '--format', '{{.ServerVersion}}'], stdout=log, stderr=subprocess.STDOUT)
                environment['docker'] = c.captured(c.DOCKER + ['version', '--format', '{{.Server.Version}}'])
                environment['k6'] = c.captured(['k6', 'version'])
                environment['java'] = c.command(['java', '-version'], stdout=subprocess.PIPE, stderr=subprocess.PIPE).stderr.splitlines()[0]
                environment['memory'] = next(s for s in Path('/proc/meminfo').read_text().splitlines() if s.startswith('MemTotal:'))
                for port in (13306, 16379, 18080):
                    c.require_free_port(port)
                env = c.local_env()
                c.command(['./gradlew', '--no-daemon', 'bootJar'], cwd=c.BACKEND, stdout=log, stderr=subprocess.STDOUT)
                jars = [p for p in (c.BACKEND / 'build/libs').glob('*.jar') if not p.name.endswith('-plain.jar')]
                if len(jars) != 1:
                    raise RuntimeError('실행 jar는 build/libs에 하나여야 함')
                environment['jar_sha256'] = hashlib.sha256(jars[0].read_bytes()).hexdigest()
                agent = build_agent(RAW)
                owned = True
                c.command(c.COMPOSE + ['up', '-d', '--wait', '--wait-timeout', '240'], stdout=log, stderr=subprocess.STDOUT)
            for repeat in range(args.repeat_start, args.repeats + 1):
                offset = (repeat - 1) % len(focus)
                for condition in focus[offset:] + focus[:offset]:
                    print(f'측정: {args.kind}/{condition}/{repeat}', flush=True)
                    rows.append(measure(args.kind, condition, repeat, env, jars[0], agent, anchor))
                    report(args.kind, rows, environment, conditions, args.repeats, focus=focus)
            if args.kind == 'index' and not all(r['valid'] for r in rows):
                raise RuntimeError('일부 측정 유효성 실패: 결과표/raw 확인; 추천은 실패 회차 제외 불가')
        except Exception as error:
            failure = f'{type(error).__name__}: {error}'
            if isinstance(error, subprocess.CalledProcessError):
                failure = f'명령 실패(exit={error.returncode}): ' + ' '.join(error.cmd)
                if attempt.exists() and 'permission denied' in attempt.read_text().lower():
                    failure += '\n\n로컬 Docker 소켓 unix:///var/run/docker.sock: permission denied.'
            print(failure, file=sys.stderr)
        finally:
            if owned:
                with attempt.open('a') as log:
                    cleanup = subprocess.run(c.COMPOSE + ['down', '--volumes', '--remove-orphans'], stdout=log, stderr=subprocess.STDOUT)
                if cleanup.returncode:
                    failure = (failure or '') + '\n자동 정리 실패: --cleanup 필요.'
            report(args.kind, rows, environment, conditions, args.repeats, failure, focus=focus)
        if failure:
            raise SystemExit(1)


if __name__ == '__main__':
    main()
