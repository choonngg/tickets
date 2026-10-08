#!/usr/bin/env python3
"""Offline checks: synthetic results stay in temp files; never write measured reports."""
import copy
import datetime
import json
import os
from pathlib import Path
import subprocess
import tempfile
from unittest.mock import patch

import phase6 as p


def main():
    nodes = p.explain_nodes('-> Sort (actual time=1.0..2.0 rows=3 loops=1)\\n    -> Index lookup on seat using ix (cost=4 rows=5) (actual time=0.01..0.02 rows=5 loops=3)')
    assert nodes[1]['rows_total'] == 15 and nodes[0]['rows_total'] == 3
    assert 'Index lookup' in nodes[1]['path']
    try:
        p.explain_nodes('never executed')
        raise AssertionError('missing actual output accepted')
    except ValueError:
        pass
    assert p.percentile([10, 20, 30], 95) == 29
    pair = (1000, 100)
    row = {'valid': True, 'max_concurrent': 1000, 'admit_per_cycle': 100, 'occupancy_max': 900,
           'apis': {'purchase': {'p95': 1000, 'p99': 2000, '5xx': 0, 'non409_error_rate': 0.00099}}}
    assert p.queue_pass(row)
    assert p.recommend([row, row, row], [pair], 3) == pair
    assert p.recommend([row], [pair], 3) is None
    for key, value in [('p95', 1001), ('p99', 2001), ('5xx', 1), ('non409_error_rate', 0.001)]:
        failed = copy.deepcopy(row)
        failed['apis']['purchase'][key] = value
        assert not p.queue_pass(failed)
        assert p.recommend([row, row, failed], [pair], 3) is None
    high_failed = dict(row, max_concurrent=2000, valid=False)
    assert p.recommend([row]*3 + [high_failed]*3, [pair, (2000, 100)], 3) == pair
    failed = dict(row, occupancy_max=799)
    assert not p.queue_pass(failed)
    with tempfile.TemporaryDirectory() as temporary:
        directory = Path(temporary)
        raw = directory / 'points.json'
        def point(user, status, seconds):
            return json.dumps({'type': 'Point', 'metric': 'api_responses', 'data': {
                'value': 1, 'time': f'2026-01-01T00:00:{seconds:02d}Z',
                'tags': {'api': 'purchase', 'status': str(status), 'user': str(user)}}})
        raw.write_text('\n'.join([point(1, 201, 1), point(2, 409, 2), point(3, 500, 3), point(4, 0, 4)]))
        codes, outcomes, times = p.parse_points(raw)
        summary = {'metrics': {'purchase_duration': {'values': {'min': 100, 'p(95)': 150, 'p(99)': 200}}}}
        stats = p.api_stats(summary, codes, times, 'purchase')['purchase']
        assert stats['5xx'] == 1 and stats['non409_errors'] == 2 and stats['non409_error_rate'] == 0.5
        assert abs(stats['tps'] - 4/3.1) < 1e-8 and outcomes[2] == 409
        raw.write_text(point(1, 201, 1) + '\n' + point(1, 201, 2))
        try:
            p.parse_points(raw)
            raise AssertionError('duplicate accepted')
        except ValueError:
            pass
        with patch.object(p.c, 'sql') as sql, patch.object(p.c, 'redis_reset'):
            anchor = datetime.datetime(2026, 10, 8, tzinfo=datetime.timezone.utc)
            p.seed_dataset(anchor)
            first = sql.call_args.args[0]
            p.seed_dataset(anchor)
            assert first == sql.call_args.args[0]
            assert first.count('INSERT INTO seat(') == 300
            assert first.count('INSERT INTO ticket(') == 38
            assert "(300000,20,'J',50,30,10000,'AVAILABLE')" in first
            assert "(15001,2,'A',1,1,10000,'SOLD')" in first
        # Actual JVM transformation: normal return, caught exception, early return,
        # implicit uncaught exception must all emit exactly one elapsed-time record.
        agent = p.build_agent(directory)
        source = directory / 'com/project/ticket/domain/queue/service/QueueAdmissionWorker.java'
        source.parent.mkdir(parents=True)
        source.write_text('''package com.project.ticket.domain.queue.service;
public class QueueAdmissionWorker {
  static int mode;
  public void runCycle() {
    if (mode == 1) return;
    if (mode == 2) { Object o=null; o.toString(); }
    try { if (mode == 3) throw new RuntimeException(); Thread.sleep(20); }
    catch (Exception e) { return; }
  }
  public static void main(String[] args) {
    for (mode=0; mode<4; mode++) try { new QueueAdmissionWorker().runCycle(); } catch (Exception e) {}
  }
}''')
        p.c.command(['javac', '-d', str(directory), str(source)])
        output = p.c.captured(['java', '-javaagent:' + str(agent), '-cp', str(directory),
                               'com.project.ticket.domain.queue.service.QueueAdmissionWorker'])
        logfile = directory / 'app.log'
        logfile.write_text(output)
        samples = p.worker_samples(logfile, 0, 1e12)
        assert len(samples) == 4, output
        assert samples[0]['duration_ms'] >= 15
        assert p.worker_samples(logfile, 0, 1) == []
        # k6 inspect parses/imports each complete script without network or Docker.
        inputs = directory / 'input.json'
        inputs.write_text(json.dumps([{'id': 1, 'at': 'synthetic', 'entry': 'synthetic', 'arrivalMs': 0}]))
        env = {k: v for k, v in os.environ.items() if not k.startswith('K6_')}
        for script, mode in [('queue/queue.js', 'queue'), ('index/index.js', 'reads'), ('index/index.js', 'purchase')]:
            p.c.captured(['k6', 'inspect', '-e', 'INPUT=' + str(inputs), '-e', 'MODE=' + mode,
                          str(p.HERE / script)], env=env)
        (directory / 'results').mkdir()
        for kind, conditions in [('queue', p.PAIRS), ('index', list(p.CANDIDATES))]:
            with patch.object(p, 'HERE', directory):
                p.report(kind, [], {'synthetic': True}, conditions, 3, 'synthetic blocked')
            name = 'queue_numbers.md' if kind == 'queue' else 'index_comparison.md'
            text = (directory / 'results' / name).read_text()
            assert '실측 미실행' in text
            assert ('추천 보류' if kind == 'queue' else '채택 보류') in text
            assert '미측정' in text and '운영 재측정 권장' in text
        # Completed report paths and candidate isolation checks, with synthetic
        # data confined to temporary report files.
        sample_api = dict(stats, **{'5xx': 0, 'non409_errors': 0, 'non409_error_rate': 0, 'p95': 100, 'p99': 200, 'success_tps': 300})
        sample = dict(row, repeat=1, apis={n: sample_api for n in ['register', 'status', 'enter', 'seat', 'purchase']},
                      hikari_active_max=10, hikari_pending_max=0, hikari_acquire_mean_ms=1,
                      audit={'tickets': 4000}, worker_p95_ms=2, worker_max_ms=3,
                      arrival={'p(95)': 2}, active_residence={'p(95)': 80000})
        with patch.object(p, 'HERE', directory):
            p.report('queue', [sample], {}, [pair], 1)
        assert '추천: max-concurrent=1000' in (directory / 'results/queue_numbers.md').read_text()
        index_rows = []
        for candidate in p.CANDIDATES:
            index_rows.append(dict(sample, candidate=candidate,
                apis={n: sample_api for n in ['seat', 'ticket', 'concert', 'purchase']},
                plans={'sections': [nodes, nodes, nodes]}, sizes='seat\tPRIMARY\t16384',
                reads={'hikari_active_max': 10, 'hikari_pending_max': 0, 'hikari_acquire_mean_ms': 1, 'valid': True}))
        with patch.object(p, 'HERE', directory):
            p.report('index', index_rows, {}, list(p.CANDIDATES), 1)
        assert '구매 201 TPS 300.00' in (directory / 'results/index_comparison.md').read_text()
        with patch.object(p.c, 'sql', return_value='seat\t1\tix_seat_concert_status\t1\tconcert_id'):
            try:
                p.snapshots(directory, 'baseline')
                raise AssertionError('contaminated baseline accepted')
            except RuntimeError:
                pass
        # Even subprocess failure removes the signed input and saves monitor errors.
        with patch.object(p.c, 'metric', return_value={'COUNT': 1, 'TOTAL_TIME': 0, 'VALUE': 0}), \
             patch.object(p.subprocess, 'run', side_effect=RuntimeError('synthetic failure')):
            try:
                p.run_k6(directory / 'failed-run', p.HERE / 'queue/queue.js', [{'at': 'synthetic'}], 'synthetic', 'queue')
                raise AssertionError('failure ignored')
            except RuntimeError:
                pass
        assert not (directory / 'failed-run/input.json').exists()
        for wrapper in ['queue/run-queue-numbers.sh', 'index/run-index-comparison.sh']:
            p.c.command(['bash', '-n', str(p.HERE / wrapper)])
    print('Phase 6 offline checks passed: seed/parser/SLO/recommendation/agent/k6/shell/report')


if __name__ == '__main__':
    main()
