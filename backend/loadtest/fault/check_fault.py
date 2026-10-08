#!/usr/bin/env python3
"""Synthetic checks only, no Docker/development endpoints, no measurement report writes."""
import io
import json
from pathlib import Path
import sqlite3
import tempfile
from types import SimpleNamespace
from unittest.mock import Mock, patch

import fault as f


def main():
    rows = [f.row(1, [1, 2], 'a'), f.row(1, [3, 4], 'b'), f.row(2, [5, 6], 'failed')]
    key_a = f.hashlib.sha256(b'a').hexdigest()
    key_b = f.hashlib.sha256(b'b').hexdigest()
    db = sqlite3.connect(':memory:')
    db.executescript('CREATE TABLE seat(id INT,concert_id INT,status TEXT);'
                     'CREATE TABLE ticket(id INTEGER PRIMARY KEY,user_id INT,purchase_key TEXT,seat_id INT);'
                     "INSERT INTO seat VALUES(1,1,'SOLD'),(2,1,'SOLD'),(3,1,'SOLD'),(4,1,'SOLD'),"
                     "(5,1,'AVAILABLE'),(6,1,'AVAILABLE');")
    db.executemany('INSERT INTO ticket(user_id,purchase_key,seat_id) VALUES(?,?,?)',
                   [(1, key_a, 1), (1, key_a, 2), (1, key_b, 3), (1, key_b, 4)])

    def sql(statement):
        db.execute('DROP TABLE IF EXISTS fault_expected')
        db.execute('DROP TABLE IF EXISTS fault_expected_count')
        counts = []
        for part in statement.split(';'):
            if part.strip():
                cursor = db.execute(part)
                if cursor.description:
                    counts += [str(row[0]) for row in cursor.fetchall()]
        return '\n'.join(counts)

    with patch.object(f.c, 'sql', side_effect=sql):
        # SQL itself runs locally: repeat requests collapse, failed batches add no expected tickets.
        assert f.audit(rows + [rows[0]]) == {'duplicate': 0, 'sold_mismatch': 0, 'over_limit': 0,
                                           'partial_bundle': 0, 'tickets': 4}
        db.execute('DELETE FROM ticket WHERE seat_id=4')
        assert f.audit(rows)['partial_bundle'] == 1
        assert f.audit(rows)['sold_mismatch'] == 1
        db.execute('INSERT INTO ticket(user_id,purchase_key,seat_id) VALUES(?,?,4)', (1, key_b))
        db.execute('INSERT INTO ticket(user_id,purchase_key,seat_id) VALUES(?,?,1)', (1, key_a))
        invalid = f.audit(rows)
        assert invalid['duplicate'] == 1 and invalid['over_limit'] == 1 and invalid['partial_bundle'] == 1
    db.close()

    error = {'status': 500, 'body': {'code': 'INTERNAL_SERVER_ERROR', 'message': 'An internal server error occurred.'}}
    assert f.error_is(error, 500, 'INTERNAL_SERVER_ERROR')
    assert not f.error_is(dict(error, body={'code': 'INTERNAL_SERVER_ERROR', 'message': 'secret exception'}),
                          500, 'INTERNAL_SERVER_ERROR')
    assert not f.error_is(dict(error, body=dict(error['body'], trace='secret')), 500, 'INTERNAL_SERVER_ERROR')
    body = [{'ticketId': seat, 'userId': 1, 'seatId': seat, 'purchasePrice': 10000} for seat in (1, 2)]
    assert f.success_is({'status': 201, 'body': body}, rows[0])
    assert not f.success_is({'status': 201, 'body': body[:1]}, rows[0])

    # Socket-free check of the same forwarding function used by the real HTTP handler.
    data = b'{"seatIds":[1,2]}'
    handler = SimpleNamespace(rfile=io.BytesIO(data), wfile=Mock(), connection=Mock(), path='/api/tickets',
                              headers={'Content-Length': str(len(data)), 'Host': 'proxy',
                                       'Authorization': 'Bearer synthetic', 'Idempotency-Key': 'synthetic'})
    upstream = Mock()
    upstream.getresponse.return_value.status = 201
    upstream.getresponse.return_value.read.return_value = json.dumps(body).encode()
    captured = {}
    with patch.object(f.http.client, 'HTTPConnection', return_value=upstream):
        f.forward_and_disconnect(handler, captured, 18080)
    assert captured == {'status': 201, 'body': body} and handler.close_connection
    handler.connection.shutdown.assert_called_once_with(f.socket.SHUT_RDWR)
    handler.wfile.write.assert_not_called()
    upstream.close.assert_called_once()
    upstream.request.assert_called_once_with('POST', '/api/tickets', data,
        {'Content-Length': str(len(data)), 'Authorization': 'Bearer synthetic', 'Idempotency-Key': 'synthetic'})

    with tempfile.TemporaryDirectory() as directory, patch.object(f, 'REPORT', Path(directory) / 'report.md'):
        f.report([], {'synthetic': True}, 'Docker blocked')
        report = f.REPORT.read_text()
        assert report.count('- 판정: 미실행.') == 3 and '건수 미확인' in report
        failed = {'scenario': 'F1', 'valid': False, 'checks': {'injected': False}, 'responses': []}
        f.report([failed], {'synthetic': True})
        assert '- 판정: 실패/미완료.' in f.REPORT.read_text()
    print('통과: SQL 판정(SQLite), 오류 계약, 프록시 전달·연결 종료(HTTP 더블), 미실측 보고서. 실측값 아님.')


if __name__ == '__main__':
    main()
