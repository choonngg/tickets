#!/usr/bin/env python3
"""도구 로직 확인. 합성값은 results/에 쓰지 않으며 실측을 대체하지 않는다."""
import json
from pathlib import Path
import tempfile
from unittest.mock import patch

import compare
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa


def main():
    a = compare.plan('A', 1000, 1)
    b = compare.plan('B', 1000, 300)
    assert all(r['seatIds'] == [1] for r in a)
    assert b == compare.plan('B', 1000, 300)
    assert len({r['key'] for r in b}) == 1000
    assert all(1 <= len(r['seatIds']) <= 4 and len(set(r['seatIds'])) == len(r['seatIds'])
               and all(1 <= s <= 300 for s in r['seatIds']) for r in b)
    assert {len(r['seatIds']) for r in b} == {1, 2, 3, 4}

    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    pem = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                            serialization.NoEncryption()).decode().replace('\n', '\\n')
    tokens = compare.access_tokens({'JWT_PRIVATE_KEY_PEM': pem, 'JWT_KEY_ID': 'test',
                                    'JWT_ISSUER': 'test-issuer', 'JWT_AUDIENCE': 'test-aud'}, 2)
    for user, token in tokens.items():
        header, payload, signature = token.split('.')
        decode = lambda s: compare.base64.urlsafe_b64decode(s + '=' * (-len(s) % 4))
        key.public_key().verify(decode(signature), f'{header}.{payload}'.encode(), padding.PKCS1v15(), hashes.SHA256())
        claims = json.loads(decode(payload))
        assert claims['sub'] == claims['sid'] == str(user)
        assert claims['typ'] == 'AT' and claims['exp'] - claims['iat'] == 900
        assert json.loads(decode(header))['alg'] == 'RS256'

    with tempfile.TemporaryDirectory() as directory:
        raw = Path(directory) / 'k6.json'
        raw.write_text('\n'.join(json.dumps({'type': 'Point', 'metric': 'purchase_responses',
                       'data': {'value': 1, 'tags': {'status': str(status), 'code': code, 'user': str(user)}}})
                       for user, status, code in [(1, 201, ''), (2, 409, 'SEAT_NOT_AVAILABLE'),
                                                 (3, 409, 'SEAT_LOCK_FAILED'), (4, 500, 'INTERNAL_SERVER_ERROR')]))
        distribution, outcomes = compare.raw_responses(raw)
        assert distribution == {'201/CREATED': 1, '409/SEAT_NOT_AVAILABLE': 1,
                                '409/SEAT_LOCK_FAILED': 1, '500/INTERNAL_SERVER_ERROR': 1}
        assert outcomes == {1: 201, 2: 409, 3: 409, 4: 500}
        with patch.object(compare, 'sql', side_effect=['0', '0', '0', '1\t1']):
            valid = compare.audit(a[:4], outcomes)
        assert all(valid[k] == 0 for k in ('duplicate', 'sold_mismatch', 'over_limit',
                                          'partial_bundle', 'response_db_mismatch'))
        with patch.object(compare, 'sql', side_effect=['1', '2', '3', '1\t1']):
            invalid = compare.audit([{'id': 1, 'seatIds': [1, 2]}], {1: 409})
        assert invalid['partial_bundle'] == invalid['response_db_mismatch'] == 1
        assert invalid['duplicate'] == 1 and invalid['sold_mismatch'] == 2 and invalid['over_limit'] == 3

        report = Path(directory) / 'report.md'
        with patch.object(compare, 'REPORT', report):
            compare.report([], {'selfcheck': True}, 'Docker blocked')
            assert report.read_text().count('| 미측정 |') == 18
            assert '추천은 보류' in report.read_text()
            rows = []
            for scenario in ('A', 'B'):
                for strategy in compare.STRATEGIES:
                    for repeat in (1, 2, 3):
                        rows.append({'scenario': scenario, 'strategy': strategy, 'repeat': repeat,
                                     'valid': True, 'requests': 1000, 'success': 1,
                                     'distribution': {'201/CREATED': 1, '409/SEAT_NOT_AVAILABLE': 999},
                                     'latency': {'med': 1, 'p(95)': 2, 'p(99)': 3},
                                     'dispatch': {'min': 0, 'max': 5}, 'hikari_samples': 10,
                                     'hikari_active_max': 10, 'hikari_pending_max': 4,
                                     'hikari_acquire_mean_ms': 1,
                                     'innodb_delta': {'Innodb_row_lock_time': 10, 'Innodb_row_lock_waits': 2},
                                     'audit': valid})
            compare.report(rows, {'selfcheck': True})
            assert '**pessimistic**' in report.read_text()
            assert '18회 실측 및 정합성 검사 완료' in report.read_text()

        # Execute the actual k6 purchase code with an HTTP stub: no network or DB.
        input_path = Path(directory) / 'input.json'
        summary_path = Path(directory) / 'summary.json'
        points_path = Path(directory) / 'points.json'
        config_path = Path(directory) / 'config.json'
        driver = Path(directory) / 'check.js'
        config_path.write_text('{}')
        input_path.write_text(json.dumps([{'id': 1, 'at': 'synthetic', 'entry': 'synthetic',
                                           'seatIds': [1], 'key': 'selfcheck'}]))
        driver.write_text("import http from 'k6/http';\n"
                          'import purchase, {options as original, handleSummary as summary} from ' +
                          json.dumps(str(compare.HERE / 'purchase.js')) + ';\n'
                          'export const options = original;\n'
                          'http.post = () => ({status:201, json:()=>[{seatId:1,userId:1}], timings:{duration:1.25}});\n'
                          'export default function () { purchase({startAt:Date.now()}); }\n'
                          'export const handleSummary = summary;\n')
        compare.captured(['k6', 'run', '--config', str(config_path), '--no-usage-report',
                          '-e', 'INPUT=' + str(input_path), '-e', 'SUMMARY=' + str(summary_path),
                          '--out', 'json=' + str(points_path), str(driver)])
        actual = json.loads(summary_path.read_text())
        assert actual['metrics']['purchase_duration']['values']['p(99)'] == 1.25
        assert actual['metrics']['invalid_success_body']['values']['count'] == 0
        assert compare.raw_responses(points_path) == ({'201/CREATED': 1}, {1: 201})
    print('도구 자체 검증 통과: 고정 시드·RS256 AT·응답 파싱·부분 구매/불일치 검출·보고·실제 k6 코드(HTTP 대체)')


if __name__ == '__main__':
    main()
