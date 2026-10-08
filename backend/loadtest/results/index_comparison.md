# 인덱스 후보 로컬 측정

실측 완료.

**로컬 잠정값 · 운영 재측정 권장.**

## 조건

```json
{
  "UTC": "2026-10-07T15:54:39+00:00",
  "kernel": "6.18.40.1-microsoft-standard-WSL2",
  "cpu_logical": 8,
  "conditions": [
    "baseline",
    "ix_seat_concert_status",
    "ix_ticket_user_created",
    "ix_concert_ticket_period"
  ],
  "repeats": 3,
  "docker": "29.3.1",
  "k6": "k6 v2.0.0 (commit/8c3be52cc1, go1.26.3, linux/amd64)",
  "java": "openjdk version \"25.0.4.1\" 2026-08-18",
  "memory": "MemTotal:       16124276 kB",
  "jar_sha256": "85253f08474144971392809b3fadf68d5b9c8030f18985df82a14f265105ac11"
}
```

- 로컬 Docker MySQL 8.0.36(13306) / Redis 7.2.4(16379), prod 프로필 JVM(18080), pessimistic.
- Hikari 10/10, Tomcat max 200/min-spare 10/accept-count 100: 운영 프로필의 기본값과 동일.
- heap 1GiB/CPU 8은 로컬 고정값(운영 JVM 자원은 별도 확인). Redis TLS 꺼짐, metrics 노출, WARN 로그는 측정 차이.
- 매 회 스키마 재생성(Flyway V1/V2), 구매 100건 예열 뒤 DB/Redis 재초기화. 조회 캐시 없음.
- 공연 20 × 좌석 15,000 / FAN 10,000 + ARTIST 1 / 기존 티켓 38,000. seed=20261007; 공연 1은 빈 예매, 나머지는 각각 SOLD 2,000석. 티켓 시간·사용자 배치 고정, 예매 기간은 실행 시작 UTC 기준으로 모든 회차 동일.
- 각 조건 3회, 회차마다 조건 순서 회전. 베이스라인에는 기존 정합성/FK/V2 멱등성 인덱스가 포함됨.

## API 결과

| 조건 | 회 | API | p95/p99 ms | TPS / 201 TPS | 5xx | 비409 오류/전체(율) | 응답 분포 |
|---|---:|---|---|---|---:|---|---|
| baseline | 1 | seat | 393.83/536.35 | 114.56/0.00 | 0 | 0/5155 (0.0000%) | {"200": 5155} |
| baseline | 1 | available | 1035.67/1428.18 | 54.73/0.00 | 0 | 0/2463 (0.0000%) | {"200": 2463} |
| baseline | 1 | ticket | 17.81/35.57 | 370.62/0.00 | 0 | 0/16678 (0.0000%) | {"200": 16678} |
| baseline | 1 | concert | 9.69/18.24 | 377.64/0.00 | 0 | 0/16994 (0.0000%) | {"200": 16994} |
| baseline | 1 | purchase | 4544.10/4902.40 | 190.41/190.41 | 0 | 0/1000 (0.0000%) | {"201": 1000} |
| baseline | 2 | seat | 237.56/296.09 | 149.16/0.00 | 0 | 0/6712 (0.0000%) | {"200": 6712} |
| baseline | 2 | available | 760.84/872.19 | 69.96/0.00 | 0 | 0/3148 (0.0000%) | {"200": 3148} |
| baseline | 2 | ticket | 8.76/12.70 | 376.22/0.00 | 0 | 0/16930 (0.0000%) | {"200": 16930} |
| baseline | 2 | concert | 7.86/11.43 | 378.71/0.00 | 0 | 0/17042 (0.0000%) | {"200": 17042} |
| baseline | 2 | purchase | 4329.88/4609.96 | 200.32/200.32 | 0 | 0/1000 (0.0000%) | {"201": 1000} |
| baseline | 3 | seat | 240.86/302.99 | 145.58/0.00 | 0 | 0/6551 (0.0000%) | {"200": 6551} |
| baseline | 3 | available | 765.70/888.49 | 70.33/0.00 | 0 | 0/3165 (0.0000%) | {"200": 3165} |
| baseline | 3 | ticket | 8.80/13.03 | 375.42/0.00 | 0 | 0/16894 (0.0000%) | {"200": 16894} |
| baseline | 3 | concert | 7.64/10.54 | 378.64/0.00 | 0 | 0/17039 (0.0000%) | {"200": 17039} |
| baseline | 3 | purchase | 4486.70/4747.73 | 193.58/193.58 | 0 | 0/1000 (0.0000%) | {"201": 1000} |
| ix_seat_concert_status | 1 | seat | 218.14/277.12 | 157.11/0.00 | 0 | 0/7070 (0.0000%) | {"200": 7070} |
| ix_seat_concert_status | 1 | available | 733.41/902.74 | 72.00/0.00 | 0 | 0/3240 (0.0000%) | {"200": 3240} |
| ix_seat_concert_status | 1 | ticket | 8.73/13.18 | 375.67/0.00 | 0 | 0/16905 (0.0000%) | {"200": 16905} |
| ix_seat_concert_status | 1 | concert | 7.75/11.96 | 378.13/0.00 | 0 | 0/17016 (0.0000%) | {"200": 17016} |
| ix_seat_concert_status | 1 | purchase | 4599.21/4929.30 | 187.17/187.17 | 0 | 0/1000 (0.0000%) | {"201": 1000} |
| ix_seat_concert_status | 2 | seat | 224.11/282.56 | 152.62/0.00 | 0 | 0/6868 (0.0000%) | {"200": 6868} |
| ix_seat_concert_status | 2 | available | 733.45/903.39 | 72.51/0.00 | 0 | 0/3263 (0.0000%) | {"200": 3263} |
| ix_seat_concert_status | 2 | ticket | 8.56/12.68 | 375.47/0.00 | 0 | 0/16896 (0.0000%) | {"200": 16896} |
| ix_seat_concert_status | 2 | concert | 7.54/10.63 | 378.71/0.00 | 0 | 0/17042 (0.0000%) | {"200": 17042} |
| ix_seat_concert_status | 2 | purchase | 5108.13/5281.69 | 182.98/182.98 | 0 | 0/1000 (0.0000%) | {"201": 1000} |
| ix_seat_concert_status | 3 | seat | 229.37/294.24 | 151.87/0.00 | 0 | 0/6834 (0.0000%) | {"200": 6834} |
| ix_seat_concert_status | 3 | available | 761.94/857.75 | 71.02/0.00 | 0 | 0/3196 (0.0000%) | {"200": 3196} |
| ix_seat_concert_status | 3 | ticket | 8.73/12.04 | 375.76/0.00 | 0 | 0/16909 (0.0000%) | {"200": 16909} |
| ix_seat_concert_status | 3 | concert | 7.84/11.01 | 377.78/0.00 | 0 | 0/17000 (0.0000%) | {"200": 17000} |
| ix_seat_concert_status | 3 | purchase | 4667.44/4844.16 | 199.44/199.44 | 0 | 0/1000 (0.0000%) | {"201": 1000} |
| ix_ticket_user_created | 1 | seat | 241.93/301.50 | 146.82/0.00 | 0 | 0/6607 (0.0000%) | {"200": 6607} |
| ix_ticket_user_created | 1 | available | 785.59/957.29 | 67.91/0.00 | 0 | 0/3056 (0.0000%) | {"200": 3056} |
| ix_ticket_user_created | 1 | ticket | 9.11/14.62 | 375.78/0.00 | 0 | 0/16910 (0.0000%) | {"200": 16910} |
| ix_ticket_user_created | 1 | concert | 7.83/11.59 | 378.07/0.00 | 0 | 0/17013 (0.0000%) | {"200": 17013} |
| ix_ticket_user_created | 1 | purchase | 4666.75/4851.20 | 202.00/202.00 | 0 | 0/1000 (0.0000%) | {"201": 1000} |
| ix_ticket_user_created | 2 | seat | 236.05/302.41 | 146.62/0.00 | 0 | 0/6598 (0.0000%) | {"200": 6598} |
| ix_ticket_user_created | 2 | available | 755.44/924.26 | 71.07/0.00 | 0 | 0/3198 (0.0000%) | {"200": 3198} |
| ix_ticket_user_created | 2 | ticket | 8.87/12.46 | 376.22/0.00 | 0 | 0/16930 (0.0000%) | {"200": 16930} |
| ix_ticket_user_created | 2 | concert | 7.75/10.92 | 378.82/0.00 | 0 | 0/17047 (0.0000%) | {"200": 17047} |
| ix_ticket_user_created | 2 | purchase | 4713.96/4937.11 | 196.82/196.82 | 0 | 0/1000 (0.0000%) | {"201": 1000} |
| ix_ticket_user_created | 3 | seat | 230.58/296.56 | 150.36/0.00 | 0 | 0/6766 (0.0000%) | {"200": 6766} |
| ix_ticket_user_created | 3 | available | 752.84/867.87 | 71.40/0.00 | 0 | 0/3213 (0.0000%) | {"200": 3213} |
| ix_ticket_user_created | 3 | ticket | 8.54/13.47 | 375.60/0.00 | 0 | 0/16902 (0.0000%) | {"200": 16902} |
| ix_ticket_user_created | 3 | concert | 7.96/11.59 | 378.09/0.00 | 0 | 0/17014 (0.0000%) | {"200": 17014} |
| ix_ticket_user_created | 3 | purchase | 4992.32/5195.74 | 187.11/187.11 | 0 | 0/1000 (0.0000%) | {"201": 1000} |
| ix_concert_ticket_period | 1 | seat | 237.64/296.15 | 148.16/0.00 | 0 | 0/6667 (0.0000%) | {"200": 6667} |
| ix_concert_ticket_period | 1 | available | 769.26/889.11 | 70.84/0.00 | 0 | 0/3188 (0.0000%) | {"200": 3188} |
| ix_concert_ticket_period | 1 | ticket | 8.66/12.66 | 375.76/0.00 | 0 | 0/16909 (0.0000%) | {"200": 16909} |
| ix_concert_ticket_period | 1 | concert | 7.91/11.93 | 377.98/0.00 | 0 | 0/17009 (0.0000%) | {"200": 17009} |
| ix_concert_ticket_period | 1 | purchase | 4849.63/5068.74 | 193.62/193.62 | 0 | 0/1000 (0.0000%) | {"201": 1000} |
| ix_concert_ticket_period | 2 | seat | 249.54/312.31 | 144.36/0.00 | 0 | 0/6496 (0.0000%) | {"200": 6496} |
| ix_concert_ticket_period | 2 | available | 792.75/904.56 | 68.58/0.00 | 0 | 0/3086 (0.0000%) | {"200": 3086} |
| ix_concert_ticket_period | 2 | ticket | 9.00/13.36 | 375.89/0.00 | 0 | 0/16915 (0.0000%) | {"200": 16915} |
| ix_concert_ticket_period | 2 | concert | 7.67/11.26 | 378.33/0.00 | 0 | 0/17025 (0.0000%) | {"200": 17025} |
| ix_concert_ticket_period | 2 | purchase | 4466.27/4784.50 | 194.77/194.77 | 0 | 0/1000 (0.0000%) | {"201": 1000} |
| ix_concert_ticket_period | 3 | seat | 242.94/305.66 | 146.71/0.00 | 0 | 0/6602 (0.0000%) | {"200": 6602} |
| ix_concert_ticket_period | 3 | available | 776.32/910.30 | 69.47/0.00 | 0 | 0/3126 (0.0000%) | {"200": 3126} |
| ix_concert_ticket_period | 3 | ticket | 8.44/12.37 | 375.87/0.00 | 0 | 0/16914 (0.0000%) | {"200": 16914} |
| ix_concert_ticket_period | 3 | concert | 7.39/11.14 | 378.69/0.00 | 0 | 0/17041 (0.0000%) | {"200": 17041} |
| ix_concert_ticket_period | 3 | purchase | 4947.04/5149.92 | 191.07/191.07 | 0 | 0/1000 (0.0000%) | {"201": 1000} |

- TPS: 읽기는 API별 45초 창, 구매는 첫 응답~마지막 응답 + 최소 HTTP 소요 창의 처리량. queue 등록/폴링 TPS는 전체 흐름 중 각 API 응답 창 기준.
- 지연은 오류 포함 모든 응답. 비409 오류는 transport(status=0), 4xx(409 제외), 5xx를 포함. 5xx는 별도 계수.
- Hikari 표본은 1초 목표 주기(지표/Redis 요청만큼 늘어남), 획득 평균은 Timer COUNT/TOTAL_TIME 차이. 순간 최대는 놓칠 수 있음.

## Hikari·검증

| 조건/회 | 활성/대기 최대 | 획득 평균 ms | 구매 정합성 | 유효 |
|---|---|---|---|---|
| baseline/1 | 10.0/187.0 | 287.83119542533336 | {"duplicate": 0, "sold_mismatch": 0, "over_limit": 0, "partial_bundle": 0, "response_db_mismatch": 0, "tickets": 1000, "db_success": 1000} | True |
| baseline/1 조회 | 10.0/28.0 | 44.0570958794139 | 해당 없음 | True |
| ix_seat_concert_status/1 | 10.0/188.0 | 298.2261782713333 | {"duplicate": 0, "sold_mismatch": 0, "over_limit": 0, "partial_bundle": 0, "response_db_mismatch": 0, "tickets": 1000, "db_success": 1000} | True |
| ix_seat_concert_status/1 조회 | 10.0/26.0 | 35.26824449994348 | 해당 없음 | True |
| ix_ticket_user_created/1 | 10.0/185.0 | 276.24929478933336 | {"duplicate": 0, "sold_mismatch": 0, "over_limit": 0, "partial_bundle": 0, "response_db_mismatch": 0, "tickets": 1000, "db_success": 1000} | True |
| ix_ticket_user_created/1 조회 | 10.0/26.0 | 37.48018924028358 | 해당 없음 | True |
| ix_concert_ticket_period/1 | 10.0/188.0 | 286.3756722836667 | {"duplicate": 0, "sold_mismatch": 0, "over_limit": 0, "partial_bundle": 0, "response_db_mismatch": 0, "tickets": 1000, "db_success": 1000} | True |
| ix_concert_ticket_period/1 조회 | 10.0/24.0 | 36.84469971331643 | 해당 없음 | True |
| ix_seat_concert_status/2 | 10.0/187.0 | 297.04080323966656 | {"duplicate": 0, "sold_mismatch": 0, "over_limit": 0, "partial_bundle": 0, "response_db_mismatch": 0, "tickets": 1000, "db_success": 1000} | True |
| ix_seat_concert_status/2 조회 | 10.0/25.0 | 35.81622520787855 | 해당 없음 | True |
| ix_ticket_user_created/2 | 10.0/185.0 | 281.11390759833336 | {"duplicate": 0, "sold_mismatch": 0, "over_limit": 0, "partial_bundle": 0, "response_db_mismatch": 0, "tickets": 1000, "db_success": 1000} | True |
| ix_ticket_user_created/2 조회 | 10.0/25.0 | 36.84327416743198 | 해당 없음 | True |
| ix_concert_ticket_period/2 | 10.0/181.0 | 284.7964627629999 | {"duplicate": 0, "sold_mismatch": 0, "over_limit": 0, "partial_bundle": 0, "response_db_mismatch": 0, "tickets": 1000, "db_success": 1000} | True |
| ix_concert_ticket_period/2 조회 | 10.0/26.0 | 37.686586882611095 | 해당 없음 | True |
| baseline/2 | 10.0/187.0 | 273.4275804596666 | {"duplicate": 0, "sold_mismatch": 0, "over_limit": 0, "partial_bundle": 0, "response_db_mismatch": 0, "tickets": 1000, "db_success": 1000} | True |
| baseline/2 조회 | 10.0/27.0 | 36.76616368965596 | 해당 없음 | True |
| ix_ticket_user_created/3 | 10.0/183.0 | 292.0602827476667 | {"duplicate": 0, "sold_mismatch": 0, "over_limit": 0, "partial_bundle": 0, "response_db_mismatch": 0, "tickets": 1000, "db_success": 1000} | True |
| ix_ticket_user_created/3 조회 | 10.0/27.0 | 36.37299376493906 | 해당 없음 | True |
| ix_concert_ticket_period/3 | 10.0/186.0 | 292.96050761633336 | {"duplicate": 0, "sold_mismatch": 0, "over_limit": 0, "partial_bundle": 0, "response_db_mismatch": 0, "tickets": 1000, "db_success": 1000} | True |
| ix_concert_ticket_period/3 조회 | 10.0/26.0 | 37.115474708948554 | 해당 없음 | True |
| baseline/3 | 10.0/188.0 | 286.073297839 | {"duplicate": 0, "sold_mismatch": 0, "over_limit": 0, "partial_bundle": 0, "response_db_mismatch": 0, "tickets": 1000, "db_success": 1000} | True |
| baseline/3 조회 | 10.0/26.0 | 37.38392046017091 | 해당 없음 | True |
| ix_seat_concert_status/3 | 10.0/183.0 | 274.9109828363333 | {"duplicate": 0, "sold_mismatch": 0, "over_limit": 0, "partial_bundle": 0, "response_db_mismatch": 0, "tickets": 1000, "db_success": 1000} | True |
| ix_seat_concert_status/3 조회 | 10.0/25.0 | 36.21106759268531 | 해당 없음 | True |

## 실제 쿼리 근거

- 좌석 AVAILABLE 조회: `backend/src/main/java/com/project/ticket/domain/seat/repository/SeatRepository.java:17`; 구역 집계(부하 API): `backend/src/main/java/com/project/ticket/domain/seat/repository/SeatRepository.java:26`.
- 사용자 티켓 최신순 + seat/concert EntityGraph: `backend/src/main/java/com/project/ticket/domain/ticket/repository/TicketRepository.java:23`.
- 공연 API는 기간 조건 없는 findAll: `backend/src/main/java/com/project/ticket/domain/concert/service/ConcertService.java:87`. **기간 조회 쿼리 없음**: ix_concert_ticket_period 목표 효과 검증 불가.
- EXPLAIN은 실제 Repository 쿼리와 동등한 SQL(QUERIES 상수), Hibernate 별칭/필드 선택과 차이 있음. 공연은 실제 findAll만 분석하며 가상의 기간 쿼리는 쓰지 않음.

## EXPLAIN ANALYZE·크기

- 각 쿼리 예열 1회 제외 후 3회. 아래는 첫 계획의 모든 접근 노드; 전체 세 번 트리는 raw에 저장.
- actual rows는 노드 출력 행/loop. 스캔 노드 rows×loops를 실제 접근 행으로 읽고, filter/root 출력과 혼동하지 않음.

| 조건/회/쿼리 | 접근 노드 | actual first/last ms | rows/loop × loops = 전체 |
|---|---|---|---|
| baseline/1/available | -> Filter: (s.`status` = 'AVAILABLE') | 1.63/12.1 | 13000.0 × 1 = 13000.0 |
| baseline/1/available | -> Index lookup on s using uq_seat_position (concert_id=2) | 0.174/10.8 | 15000.0 × 1 = 15000.0 |
| baseline/1/sections | -> Group aggregate: count(0) | 2.5/22.4 | 9.0 × 1 = 9.0 |
| baseline/1/sections | -> Filter: (s.`status` = 'AVAILABLE') | 1.48/21.6 | 13000.0 × 1 = 13000.0 |
| baseline/1/sections | -> Index lookup on s using uq_seat_position (concert_id=2) | 0.11/19.5 | 15000.0 × 1 = 15000.0 |
| baseline/1/history | -> Nested loop left join | 0.0619/0.0759 | 4.0 × 1 = 4.0 |
| baseline/1/history | -> Nested loop left join | 0.057/0.0664 | 4.0 × 1 = 4.0 |
| baseline/1/history | -> Sort: t.created_at DESC | 0.0474/0.0479 | 4.0 × 1 = 4.0 |
| baseline/1/history | -> Index lookup on t using ix_ticket_user_fk (user_id=1) | 0.0345/0.0365 | 4.0 × 1 = 4.0 |
| baseline/1/history | -> Single-row index lookup on s using PRIMARY (id=t.seat_id) | 0.00419/0.00421 | 1.0 × 4 = 4.0 |
| baseline/1/history | -> Single-row index lookup on c using PRIMARY (id=s.concert_id) | 0.00197/0.00201 | 1.0 × 4 = 4.0 |
| baseline/1/concerts | -> Table scan on c | 0.0299/0.0341 | 20.0 × 1 = 20.0 |

크기 bytes (baseline/1, InnoDB 통계 page数 × page_size, 근사 할당 공간):

```tsv
auth_session	PRIMARY	16384
auth_session	ix_auth_session_expires_at	16384
auth_session	ix_auth_session_user_fk	16384
concert	PRIMARY	16384
concert	ix_concert_artist_fk	16384
flyway_schema_history	PRIMARY	16384
flyway_schema_history	flyway_schema_history_s_idx	16384
refresh_token	PRIMARY	16384
refresh_token	ix_refresh_token_session_fk	16384
refresh_token	uq_refresh_token_hash	16384
refresh_token	uq_refresh_token_unconsumed_session	16384
seat	PRIMARY	24707072
seat	uq_seat_position	10010624
ticket	PRIMARY	3686400
ticket	ix_ticket_user_fk	1589248
ticket	ix_ticket_user_purchase	2637824
ticket	uq_ticket_seat	1589248
users	PRIMARY	16384
users	uq_users_google_sub	16384
```

| ix_seat_concert_status/1/available | -> Index lookup on s using ix_seat_concert_status (concert_id=2, status='AVAILABLE'), with index condition: (s.`status` = 'AVAILABLE') | 0.144/9.73 | 13000.0 × 1 = 13000.0 |
| ix_seat_concert_status/1/sections | -> Sort: s.section_name | 12.4/12.4 | 9.0 × 1 = 9.0 |
| ix_seat_concert_status/1/sections | -> Table scan on <temporary> | 12.4/12.4 | 9.0 × 1 = 9.0 |
| ix_seat_concert_status/1/sections | -> Aggregate using temporary table | 12.4/12.4 | 9.0 × 1 = 9.0 |
| ix_seat_concert_status/1/sections | -> Index lookup on s using ix_seat_concert_status (concert_id=2, status='AVAILABLE'), with index condition: (s.`status` = 'AVAILABLE') | 0.145/9.49 | 13000.0 × 1 = 13000.0 |
| ix_seat_concert_status/1/history | -> Nested loop left join | 0.0665/0.079 | 4.0 × 1 = 4.0 |
| ix_seat_concert_status/1/history | -> Nested loop left join | 0.0623/0.0709 | 4.0 × 1 = 4.0 |
| ix_seat_concert_status/1/history | -> Sort: t.created_at DESC | 0.0533/0.0536 | 4.0 × 1 = 4.0 |
| ix_seat_concert_status/1/history | -> Index lookup on t using ix_ticket_user_fk (user_id=1) | 0.0403/0.0419 | 4.0 × 1 = 4.0 |
| ix_seat_concert_status/1/history | -> Single-row index lookup on s using PRIMARY (id=t.seat_id) | 0.00393/0.00394 | 1.0 × 4 = 4.0 |
| ix_seat_concert_status/1/history | -> Single-row index lookup on c using PRIMARY (id=s.concert_id) | 0.00166/0.00168 | 1.0 × 4 = 4.0 |
| ix_seat_concert_status/1/concerts | -> Table scan on c | 0.0315/0.0358 | 20.0 × 1 = 20.0 |

크기 bytes (ix_seat_concert_status/1, InnoDB 통계 page数 × page_size, 근사 할당 공간):

```tsv
auth_session	PRIMARY	1589248
auth_session	ix_auth_session_expires_at	245760
auth_session	ix_auth_session_user_fk	245760
concert	PRIMARY	16384
concert	ix_concert_artist_fk	16384
flyway_schema_history	PRIMARY	16384
flyway_schema_history	flyway_schema_history_s_idx	16384
refresh_token	PRIMARY	16384
refresh_token	ix_refresh_token_session_fk	16384
refresh_token	uq_refresh_token_hash	16384
refresh_token	uq_refresh_token_unconsumed_session	16384
seat	PRIMARY	24707072
seat	ix_seat_concert_status	7880704
seat	uq_seat_position	10010624
ticket	PRIMARY	3686400
ticket	ix_ticket_user_fk	1589248
ticket	ix_ticket_user_purchase	2637824
ticket	uq_ticket_seat	1589248
users	PRIMARY	16384
users	uq_users_google_sub	16384
```

| ix_ticket_user_created/1/available | -> Filter: (s.`status` = 'AVAILABLE') | 1.61/12.0 | 13000.0 × 1 = 13000.0 |
| ix_ticket_user_created/1/available | -> Index lookup on s using uq_seat_position (concert_id=2) | 0.169/10.7 | 15000.0 × 1 = 15000.0 |
| ix_ticket_user_created/1/sections | -> Group aggregate: count(0) | 2.05/12.0 | 9.0 × 1 = 9.0 |
| ix_ticket_user_created/1/sections | -> Filter: (s.`status` = 'AVAILABLE') | 1.21/11.5 | 13000.0 × 1 = 13000.0 |
| ix_ticket_user_created/1/sections | -> Index lookup on s using uq_seat_position (concert_id=2) | 0.109/10.2 | 15000.0 × 1 = 15000.0 |
| ix_ticket_user_created/1/history | -> Nested loop left join | 0.0665/0.0871 | 4.0 × 1 = 4.0 |
| ix_ticket_user_created/1/history | -> Nested loop left join | 0.0606/0.0755 | 4.0 × 1 = 4.0 |
| ix_ticket_user_created/1/history | -> Index lookup on t using ix_ticket_user_created (user_id=1) (reverse) | 0.0487/0.0522 | 4.0 × 1 = 4.0 |
| ix_ticket_user_created/1/history | -> Single-row index lookup on s using PRIMARY (id=t.seat_id) | 0.00529/0.00531 | 1.0 × 4 = 4.0 |
| ix_ticket_user_created/1/history | -> Single-row index lookup on c using PRIMARY (id=s.concert_id) | 0.00253/0.00256 | 1.0 × 4 = 4.0 |
| ix_ticket_user_created/1/concerts | -> Table scan on c | 0.0335/0.0374 | 20.0 × 1 = 20.0 |

크기 bytes (ix_ticket_user_created/1, InnoDB 통계 page数 × page_size, 근사 할당 공간):

```tsv
auth_session	PRIMARY	16384
auth_session	ix_auth_session_expires_at	16384
auth_session	ix_auth_session_user_fk	16384
concert	PRIMARY	16384
concert	ix_concert_artist_fk	16384
flyway_schema_history	PRIMARY	16384
flyway_schema_history	flyway_schema_history_s_idx	16384
refresh_token	PRIMARY	16384
refresh_token	ix_refresh_token_session_fk	16384
refresh_token	uq_refresh_token_hash	16384
refresh_token	uq_refresh_token_unconsumed_session	16384
seat	PRIMARY	24707072
seat	uq_seat_position	10010624
ticket	PRIMARY	3686400
ticket	ix_ticket_user_created	1589248
ticket	ix_ticket_user_fk	1589248
ticket	ix_ticket_user_purchase	2637824
ticket	uq_ticket_seat	1589248
users	PRIMARY	16384
users	uq_users_google_sub	16384
```

| ix_concert_ticket_period/1/available | -> Filter: (s.`status` = 'AVAILABLE') | 1.4/11.3 | 13000.0 × 1 = 13000.0 |
| ix_concert_ticket_period/1/available | -> Index lookup on s using uq_seat_position (concert_id=2) | 0.143/10.0 | 15000.0 × 1 = 15000.0 |
| ix_concert_ticket_period/1/sections | -> Group aggregate: count(0) | 2.15/10.6 | 9.0 × 1 = 9.0 |
| ix_concert_ticket_period/1/sections | -> Filter: (s.`status` = 'AVAILABLE') | 1.25/10.0 | 13000.0 × 1 = 13000.0 |
| ix_concert_ticket_period/1/sections | -> Index lookup on s using uq_seat_position (concert_id=2) | 0.109/8.82 | 15000.0 × 1 = 15000.0 |
| ix_concert_ticket_period/1/history | -> Nested loop left join | 0.102/0.12 | 4.0 × 1 = 4.0 |
| ix_concert_ticket_period/1/history | -> Nested loop left join | 0.0958/0.108 | 4.0 × 1 = 4.0 |
| ix_concert_ticket_period/1/history | -> Sort: t.created_at DESC | 0.0803/0.0806 | 4.0 × 1 = 4.0 |
| ix_concert_ticket_period/1/history | -> Index lookup on t using ix_ticket_user_fk (user_id=1) | 0.0609/0.0631 | 4.0 × 1 = 4.0 |
| ix_concert_ticket_period/1/history | -> Single-row index lookup on s using PRIMARY (id=t.seat_id) | 0.00618/0.0062 | 1.0 × 4 = 4.0 |
| ix_concert_ticket_period/1/history | -> Single-row index lookup on c using PRIMARY (id=s.concert_id) | 0.00249/0.00251 | 1.0 × 4 = 4.0 |
| ix_concert_ticket_period/1/concerts | -> Table scan on c | 0.0661/0.0705 | 20.0 × 1 = 20.0 |

크기 bytes (ix_concert_ticket_period/1, InnoDB 통계 page数 × page_size, 근사 할당 공간):

```tsv
auth_session	PRIMARY	16384
auth_session	ix_auth_session_expires_at	16384
auth_session	ix_auth_session_user_fk	16384
concert	PRIMARY	16384
concert	ix_concert_artist_fk	16384
concert	ix_concert_ticket_period	16384
flyway_schema_history	PRIMARY	16384
flyway_schema_history	flyway_schema_history_s_idx	16384
refresh_token	PRIMARY	16384
refresh_token	ix_refresh_token_session_fk	16384
refresh_token	uq_refresh_token_hash	16384
refresh_token	uq_refresh_token_unconsumed_session	16384
seat	PRIMARY	24707072
seat	uq_seat_position	10010624
ticket	PRIMARY	3686400
ticket	ix_ticket_user_fk	1589248
ticket	ix_ticket_user_purchase	2637824
ticket	uq_ticket_seat	1589248
users	PRIMARY	16384
users	uq_users_google_sub	16384
```

| ix_seat_concert_status/2/available | -> Index lookup on s using ix_seat_concert_status (concert_id=2, status='AVAILABLE'), with index condition: (s.`status` = 'AVAILABLE') | 0.204/11.0 | 13000.0 × 1 = 13000.0 |
| ix_seat_concert_status/2/sections | -> Sort: s.section_name | 11.2/11.2 | 9.0 × 1 = 9.0 |
| ix_seat_concert_status/2/sections | -> Table scan on <temporary> | 11.2/11.2 | 9.0 × 1 = 9.0 |
| ix_seat_concert_status/2/sections | -> Aggregate using temporary table | 11.2/11.2 | 9.0 × 1 = 9.0 |
| ix_seat_concert_status/2/sections | -> Index lookup on s using ix_seat_concert_status (concert_id=2, status='AVAILABLE'), with index condition: (s.`status` = 'AVAILABLE') | 0.11/8.96 | 13000.0 × 1 = 13000.0 |
| ix_seat_concert_status/2/history | -> Nested loop left join | 0.0894/0.103 | 4.0 × 1 = 4.0 |
| ix_seat_concert_status/2/history | -> Nested loop left join | 0.0841/0.0934 | 4.0 × 1 = 4.0 |
| ix_seat_concert_status/2/history | -> Sort: t.created_at DESC | 0.0742/0.0745 | 4.0 × 1 = 4.0 |
| ix_seat_concert_status/2/history | -> Index lookup on t using ix_ticket_user_fk (user_id=1) | 0.0471/0.0489 | 4.0 × 1 = 4.0 |
| ix_seat_concert_status/2/history | -> Single-row index lookup on s using PRIMARY (id=t.seat_id) | 0.00423/0.00425 | 1.0 × 4 = 4.0 |
| ix_seat_concert_status/2/history | -> Single-row index lookup on c using PRIMARY (id=s.concert_id) | 0.00211/0.00214 | 1.0 × 4 = 4.0 |
| ix_seat_concert_status/2/concerts | -> Table scan on c | 0.0359/0.0403 | 20.0 × 1 = 20.0 |

크기 bytes (ix_seat_concert_status/2, InnoDB 통계 page数 × page_size, 근사 할당 공간):

```tsv
auth_session	PRIMARY	16384
auth_session	ix_auth_session_expires_at	16384
auth_session	ix_auth_session_user_fk	16384
concert	PRIMARY	16384
concert	ix_concert_artist_fk	16384
flyway_schema_history	PRIMARY	16384
flyway_schema_history	flyway_schema_history_s_idx	16384
refresh_token	PRIMARY	16384
refresh_token	ix_refresh_token_session_fk	16384
refresh_token	uq_refresh_token_hash	16384
refresh_token	uq_refresh_token_unconsumed_session	16384
seat	PRIMARY	24707072
seat	ix_seat_concert_status	7880704
seat	uq_seat_position	10010624
ticket	PRIMARY	3686400
ticket	ix_ticket_user_fk	1589248
ticket	ix_ticket_user_purchase	2637824
ticket	uq_ticket_seat	1589248
users	PRIMARY	1589248
users	uq_users_google_sub	360448
```

| ix_ticket_user_created/2/available | -> Filter: (s.`status` = 'AVAILABLE') | 1.43/12.2 | 13000.0 × 1 = 13000.0 |
| ix_ticket_user_created/2/available | -> Index lookup on s using uq_seat_position (concert_id=2) | 0.205/10.9 | 15000.0 × 1 = 15000.0 |
| ix_ticket_user_created/2/sections | -> Group aggregate: count(0) | 2.19/10.3 | 9.0 × 1 = 9.0 |
| ix_ticket_user_created/2/sections | -> Filter: (s.`status` = 'AVAILABLE') | 1.21/9.75 | 13000.0 × 1 = 13000.0 |
| ix_ticket_user_created/2/sections | -> Index lookup on s using uq_seat_position (concert_id=2) | 0.107/8.57 | 15000.0 × 1 = 15000.0 |
| ix_ticket_user_created/2/history | -> Nested loop left join | 0.0479/0.0621 | 4.0 × 1 = 4.0 |
| ix_ticket_user_created/2/history | -> Nested loop left join | 0.0437/0.0536 | 4.0 × 1 = 4.0 |
| ix_ticket_user_created/2/history | -> Index lookup on t using ix_ticket_user_created (user_id=1) (reverse) | 0.034/0.0362 | 4.0 × 1 = 4.0 |
| ix_ticket_user_created/2/history | -> Single-row index lookup on s using PRIMARY (id=t.seat_id) | 0.00375/0.00377 | 1.0 × 4 = 4.0 |
| ix_ticket_user_created/2/history | -> Single-row index lookup on c using PRIMARY (id=s.concert_id) | 0.00179/0.00182 | 1.0 × 4 = 4.0 |
| ix_ticket_user_created/2/concerts | -> Table scan on c | 0.0439/0.0482 | 20.0 × 1 = 20.0 |

크기 bytes (ix_ticket_user_created/2, InnoDB 통계 page数 × page_size, 근사 할당 공간):

```tsv
auth_session	PRIMARY	16384
auth_session	ix_auth_session_expires_at	16384
auth_session	ix_auth_session_user_fk	16384
concert	PRIMARY	16384
concert	ix_concert_artist_fk	16384
flyway_schema_history	PRIMARY	16384
flyway_schema_history	flyway_schema_history_s_idx	16384
refresh_token	PRIMARY	16384
refresh_token	ix_refresh_token_session_fk	16384
refresh_token	uq_refresh_token_hash	16384
refresh_token	uq_refresh_token_unconsumed_session	16384
seat	PRIMARY	24707072
seat	uq_seat_position	10010624
ticket	PRIMARY	3686400
ticket	ix_ticket_user_created	1589248
ticket	ix_ticket_user_fk	1589248
ticket	ix_ticket_user_purchase	2637824
ticket	uq_ticket_seat	1589248
users	PRIMARY	16384
users	uq_users_google_sub	16384
```

| ix_concert_ticket_period/2/available | -> Filter: (s.`status` = 'AVAILABLE') | 1.61/12.2 | 13000.0 × 1 = 13000.0 |
| ix_concert_ticket_period/2/available | -> Index lookup on s using uq_seat_position (concert_id=2) | 0.183/10.9 | 15000.0 × 1 = 15000.0 |
| ix_concert_ticket_period/2/sections | -> Group aggregate: count(0) | 2.13/11.1 | 9.0 × 1 = 9.0 |
| ix_concert_ticket_period/2/sections | -> Filter: (s.`status` = 'AVAILABLE') | 1.14/10.5 | 13000.0 × 1 = 13000.0 |
| ix_concert_ticket_period/2/sections | -> Index lookup on s using uq_seat_position (concert_id=2) | 0.0927/9.2 | 15000.0 × 1 = 15000.0 |
| ix_concert_ticket_period/2/history | -> Nested loop left join | 0.0606/0.0728 | 4.0 × 1 = 4.0 |
| ix_concert_ticket_period/2/history | -> Nested loop left join | 0.0564/0.0646 | 4.0 × 1 = 4.0 |
| ix_concert_ticket_period/2/history | -> Sort: t.created_at DESC | 0.0474/0.0478 | 4.0 × 1 = 4.0 |
| ix_concert_ticket_period/2/history | -> Index lookup on t using ix_ticket_user_fk (user_id=1) | 0.0344/0.0362 | 4.0 × 1 = 4.0 |
| ix_concert_ticket_period/2/history | -> Single-row index lookup on s using PRIMARY (id=t.seat_id) | 0.00376/0.00379 | 1.0 × 4 = 4.0 |
| ix_concert_ticket_period/2/history | -> Single-row index lookup on c using PRIMARY (id=s.concert_id) | 0.00172/0.00175 | 1.0 × 4 = 4.0 |
| ix_concert_ticket_period/2/concerts | -> Table scan on c | 0.0372/0.0413 | 20.0 × 1 = 20.0 |

크기 bytes (ix_concert_ticket_period/2, InnoDB 통계 page数 × page_size, 근사 할당 공간):

```tsv
auth_session	PRIMARY	16384
auth_session	ix_auth_session_expires_at	16384
auth_session	ix_auth_session_user_fk	16384
concert	PRIMARY	16384
concert	ix_concert_artist_fk	16384
concert	ix_concert_ticket_period	16384
flyway_schema_history	PRIMARY	16384
flyway_schema_history	flyway_schema_history_s_idx	16384
refresh_token	PRIMARY	16384
refresh_token	ix_refresh_token_session_fk	16384
refresh_token	uq_refresh_token_hash	16384
refresh_token	uq_refresh_token_unconsumed_session	16384
seat	PRIMARY	24707072
seat	uq_seat_position	10010624
ticket	PRIMARY	3686400
ticket	ix_ticket_user_fk	1589248
ticket	ix_ticket_user_purchase	2637824
ticket	uq_ticket_seat	1589248
users	PRIMARY	16384
users	uq_users_google_sub	16384
```

| baseline/2/available | -> Filter: (s.`status` = 'AVAILABLE') | 1.52/13.0 | 13000.0 × 1 = 13000.0 |
| baseline/2/available | -> Index lookup on s using uq_seat_position (concert_id=2) | 0.173/11.6 | 15000.0 × 1 = 15000.0 |
| baseline/2/sections | -> Group aggregate: count(0) | 2.09/10.1 | 9.0 × 1 = 9.0 |
| baseline/2/sections | -> Filter: (s.`status` = 'AVAILABLE') | 1.19/9.58 | 13000.0 × 1 = 13000.0 |
| baseline/2/sections | -> Index lookup on s using uq_seat_position (concert_id=2) | 0.109/8.43 | 15000.0 × 1 = 15000.0 |
| baseline/2/history | -> Nested loop left join | 0.0646/0.0779 | 4.0 × 1 = 4.0 |
| baseline/2/history | -> Nested loop left join | 0.06/0.0686 | 4.0 × 1 = 4.0 |
| baseline/2/history | -> Sort: t.created_at DESC | 0.0497/0.0501 | 4.0 × 1 = 4.0 |
| baseline/2/history | -> Index lookup on t using ix_ticket_user_fk (user_id=1) | 0.037/0.0392 | 4.0 × 1 = 4.0 |
| baseline/2/history | -> Single-row index lookup on s using PRIMARY (id=t.seat_id) | 0.00412/0.00415 | 1.0 × 4 = 4.0 |
| baseline/2/history | -> Single-row index lookup on c using PRIMARY (id=s.concert_id) | 0.00192/0.00195 | 1.0 × 4 = 4.0 |
| baseline/2/concerts | -> Table scan on c | 0.0301/0.0339 | 20.0 × 1 = 20.0 |

크기 bytes (baseline/2, InnoDB 통계 page数 × page_size, 근사 할당 공간):

```tsv
auth_session	PRIMARY	16384
auth_session	ix_auth_session_expires_at	16384
auth_session	ix_auth_session_user_fk	16384
concert	PRIMARY	16384
concert	ix_concert_artist_fk	16384
flyway_schema_history	PRIMARY	16384
flyway_schema_history	flyway_schema_history_s_idx	16384
refresh_token	PRIMARY	16384
refresh_token	ix_refresh_token_session_fk	16384
refresh_token	uq_refresh_token_hash	16384
refresh_token	uq_refresh_token_unconsumed_session	16384
seat	PRIMARY	24707072
seat	uq_seat_position	10010624
ticket	PRIMARY	3686400
ticket	ix_ticket_user_fk	1589248
ticket	ix_ticket_user_purchase	2637824
ticket	uq_ticket_seat	1589248
users	PRIMARY	16384
users	uq_users_google_sub	16384
```

| ix_ticket_user_created/3/available | -> Filter: (s.`status` = 'AVAILABLE') | 1.67/13.7 | 13000.0 × 1 = 13000.0 |
| ix_ticket_user_created/3/available | -> Index lookup on s using uq_seat_position (concert_id=2) | 0.174/12.3 | 15000.0 × 1 = 15000.0 |
| ix_ticket_user_created/3/sections | -> Group aggregate: count(0) | 2.0/10.7 | 9.0 × 1 = 9.0 |
| ix_ticket_user_created/3/sections | -> Filter: (s.`status` = 'AVAILABLE') | 1.22/10.1 | 13000.0 × 1 = 13000.0 |
| ix_ticket_user_created/3/sections | -> Index lookup on s using uq_seat_position (concert_id=2) | 0.0988/8.81 | 15000.0 × 1 = 15000.0 |
| ix_ticket_user_created/3/history | -> Nested loop left join | 0.0457/0.0582 | 4.0 × 1 = 4.0 |
| ix_ticket_user_created/3/history | -> Nested loop left join | 0.0421/0.051 | 4.0 × 1 = 4.0 |
| ix_ticket_user_created/3/history | -> Index lookup on t using ix_ticket_user_created (user_id=1) (reverse) | 0.0319/0.034 | 4.0 × 1 = 4.0 |
| ix_ticket_user_created/3/history | -> Single-row index lookup on s using PRIMARY (id=t.seat_id) | 0.00375/0.00376 | 1.0 × 4 = 4.0 |
| ix_ticket_user_created/3/history | -> Single-row index lookup on c using PRIMARY (id=s.concert_id) | 0.00149/0.00152 | 1.0 × 4 = 4.0 |
| ix_ticket_user_created/3/concerts | -> Table scan on c | 0.0314/0.0354 | 20.0 × 1 = 20.0 |

크기 bytes (ix_ticket_user_created/3, InnoDB 통계 page数 × page_size, 근사 할당 공간):

```tsv
auth_session	PRIMARY	16384
auth_session	ix_auth_session_expires_at	16384
auth_session	ix_auth_session_user_fk	16384
concert	PRIMARY	16384
concert	ix_concert_artist_fk	16384
flyway_schema_history	PRIMARY	16384
flyway_schema_history	flyway_schema_history_s_idx	16384
refresh_token	PRIMARY	16384
refresh_token	ix_refresh_token_session_fk	16384
refresh_token	uq_refresh_token_hash	16384
refresh_token	uq_refresh_token_unconsumed_session	16384
seat	PRIMARY	24707072
seat	uq_seat_position	10010624
ticket	PRIMARY	3686400
ticket	ix_ticket_user_created	1589248
ticket	ix_ticket_user_fk	1589248
ticket	ix_ticket_user_purchase	2637824
ticket	uq_ticket_seat	1589248
users	PRIMARY	16384
users	uq_users_google_sub	16384
```

| ix_concert_ticket_period/3/available | -> Filter: (s.`status` = 'AVAILABLE') | 1.32/10.9 | 13000.0 × 1 = 13000.0 |
| ix_concert_ticket_period/3/available | -> Index lookup on s using uq_seat_position (concert_id=2) | 0.161/9.7 | 15000.0 × 1 = 15000.0 |
| ix_concert_ticket_period/3/sections | -> Group aggregate: count(0) | 2.1/11.8 | 9.0 × 1 = 9.0 |
| ix_concert_ticket_period/3/sections | -> Filter: (s.`status` = 'AVAILABLE') | 1.2/11.2 | 13000.0 × 1 = 13000.0 |
| ix_concert_ticket_period/3/sections | -> Index lookup on s using uq_seat_position (concert_id=2) | 0.109/9.82 | 15000.0 × 1 = 15000.0 |
| ix_concert_ticket_period/3/history | -> Nested loop left join | 0.097/0.108 | 4.0 × 1 = 4.0 |
| ix_concert_ticket_period/3/history | -> Nested loop left join | 0.0929/0.0999 | 4.0 × 1 = 4.0 |
| ix_concert_ticket_period/3/history | -> Sort: t.created_at DESC | 0.0849/0.0852 | 4.0 × 1 = 4.0 |
| ix_concert_ticket_period/3/history | -> Index lookup on t using ix_ticket_user_fk (user_id=1) | 0.0724/0.0739 | 4.0 × 1 = 4.0 |
| ix_concert_ticket_period/3/history | -> Single-row index lookup on s using PRIMARY (id=t.seat_id) | 0.00332/0.00333 | 1.0 × 4 = 4.0 |
| ix_concert_ticket_period/3/history | -> Single-row index lookup on c using PRIMARY (id=s.concert_id) | 0.00171/0.00174 | 1.0 × 4 = 4.0 |
| ix_concert_ticket_period/3/concerts | -> Table scan on c | 0.0298/0.0334 | 20.0 × 1 = 20.0 |

크기 bytes (ix_concert_ticket_period/3, InnoDB 통계 page数 × page_size, 근사 할당 공간):

```tsv
auth_session	PRIMARY	16384
auth_session	ix_auth_session_expires_at	16384
auth_session	ix_auth_session_user_fk	16384
concert	PRIMARY	16384
concert	ix_concert_artist_fk	16384
concert	ix_concert_ticket_period	16384
flyway_schema_history	PRIMARY	16384
flyway_schema_history	flyway_schema_history_s_idx	16384
refresh_token	PRIMARY	16384
refresh_token	ix_refresh_token_session_fk	16384
refresh_token	uq_refresh_token_hash	16384
refresh_token	uq_refresh_token_unconsumed_session	16384
seat	PRIMARY	24707072
seat	uq_seat_position	10010624
ticket	PRIMARY	3686400
ticket	ix_ticket_user_fk	1589248
ticket	ix_ticket_user_purchase	2637824
ticket	uq_ticket_seat	1589248
users	PRIMARY	16384
users	uq_users_google_sub	16384
```

| baseline/3/available | -> Filter: (s.`status` = 'AVAILABLE') | 1.51/11.0 | 13000.0 × 1 = 13000.0 |
| baseline/3/available | -> Index lookup on s using uq_seat_position (concert_id=2) | 0.158/9.7 | 15000.0 × 1 = 15000.0 |
| baseline/3/sections | -> Group aggregate: count(0) | 2.15/9.99 | 9.0 × 1 = 9.0 |
| baseline/3/sections | -> Filter: (s.`status` = 'AVAILABLE') | 1.24/9.45 | 13000.0 × 1 = 13000.0 |
| baseline/3/sections | -> Index lookup on s using uq_seat_position (concert_id=2) | 0.113/8.31 | 15000.0 × 1 = 15000.0 |
| baseline/3/history | -> Nested loop left join | 0.079/0.0927 | 4.0 × 1 = 4.0 |
| baseline/3/history | -> Nested loop left join | 0.0736/0.0821 | 4.0 × 1 = 4.0 |
| baseline/3/history | -> Sort: t.created_at DESC | 0.0629/0.0633 | 4.0 × 1 = 4.0 |
| baseline/3/history | -> Index lookup on t using ix_ticket_user_fk (user_id=1) | 0.0453/0.0479 | 4.0 × 1 = 4.0 |
| baseline/3/history | -> Single-row index lookup on s using PRIMARY (id=t.seat_id) | 0.00422/0.00424 | 1.0 × 4 = 4.0 |
| baseline/3/history | -> Single-row index lookup on c using PRIMARY (id=s.concert_id) | 0.00229/0.00231 | 1.0 × 4 = 4.0 |
| baseline/3/concerts | -> Table scan on c | 0.0323/0.0367 | 20.0 × 1 = 20.0 |

크기 bytes (baseline/3, InnoDB 통계 page数 × page_size, 근사 할당 공간):

```tsv
auth_session	PRIMARY	16384
auth_session	ix_auth_session_expires_at	16384
auth_session	ix_auth_session_user_fk	16384
concert	PRIMARY	16384
concert	ix_concert_artist_fk	16384
flyway_schema_history	PRIMARY	16384
flyway_schema_history	flyway_schema_history_s_idx	16384
refresh_token	PRIMARY	16384
refresh_token	ix_refresh_token_session_fk	16384
refresh_token	uq_refresh_token_hash	16384
refresh_token	uq_refresh_token_unconsumed_session	16384
seat	PRIMARY	24707072
seat	uq_seat_position	10010624
ticket	PRIMARY	3686400
ticket	ix_ticket_user_fk	1589248
ticket	ix_ticket_user_purchase	2637824
ticket	uq_ticket_seat	1589248
users	PRIMARY	16384
users	uq_users_google_sub	16384
```

| ix_seat_concert_status/3/available | -> Index lookup on s using ix_seat_concert_status (concert_id=2, status='AVAILABLE'), with index condition: (s.`status` = 'AVAILABLE') | 0.136/9.8 | 13000.0 × 1 = 13000.0 |
| ix_seat_concert_status/3/sections | -> Sort: s.section_name | 10.9/10.9 | 9.0 × 1 = 9.0 |
| ix_seat_concert_status/3/sections | -> Table scan on <temporary> | 10.8/10.8 | 9.0 × 1 = 9.0 |
| ix_seat_concert_status/3/sections | -> Aggregate using temporary table | 10.8/10.8 | 9.0 × 1 = 9.0 |
| ix_seat_concert_status/3/sections | -> Index lookup on s using ix_seat_concert_status (concert_id=2, status='AVAILABLE'), with index condition: (s.`status` = 'AVAILABLE') | 0.11/8.69 | 13000.0 × 1 = 13000.0 |
| ix_seat_concert_status/3/history | -> Nested loop left join | 0.0561/0.0698 | 4.0 × 1 = 4.0 |
| ix_seat_concert_status/3/history | -> Nested loop left join | 0.0514/0.0597 | 4.0 × 1 = 4.0 |
| ix_seat_concert_status/3/history | -> Sort: t.created_at DESC | 0.0435/0.0438 | 4.0 × 1 = 4.0 |
| ix_seat_concert_status/3/history | -> Index lookup on t using ix_ticket_user_fk (user_id=1) | 0.0327/0.0343 | 4.0 × 1 = 4.0 |
| ix_seat_concert_status/3/history | -> Single-row index lookup on s using PRIMARY (id=t.seat_id) | 0.00357/0.00359 | 1.0 × 4 = 4.0 |
| ix_seat_concert_status/3/history | -> Single-row index lookup on c using PRIMARY (id=s.concert_id) | 0.00221/0.00223 | 1.0 × 4 = 4.0 |
| ix_seat_concert_status/3/concerts | -> Table scan on c | 0.0301/0.0338 | 20.0 × 1 = 20.0 |

크기 bytes (ix_seat_concert_status/3, InnoDB 통계 page数 × page_size, 근사 할당 공간):

```tsv
auth_session	PRIMARY	16384
auth_session	ix_auth_session_expires_at	16384
auth_session	ix_auth_session_user_fk	16384
concert	PRIMARY	16384
concert	ix_concert_artist_fk	16384
flyway_schema_history	PRIMARY	16384
flyway_schema_history	flyway_schema_history_s_idx	16384
refresh_token	PRIMARY	16384
refresh_token	ix_refresh_token_session_fk	16384
refresh_token	uq_refresh_token_hash	16384
refresh_token	uq_refresh_token_unconsumed_session	16384
seat	PRIMARY	24707072
seat	ix_seat_concert_status	7880704
seat	uq_seat_position	10010624
ticket	PRIMARY	3686400
ticket	ix_ticket_user_fk	1589248
ticket	ix_ticket_user_purchase	2637824
ticket	uq_ticket_seat	1589248
users	PRIMARY	16384
users	uq_users_google_sub	16384
```


## 쓰기 비용·관찰·추천

- 조회: 각 API 40 VU/45초, 순차 창, 생각 시간 100ms. 공연 2 좌석 구역 집계·AVAILABLE 전체 / 사용자 티켓 / 전체 공연 목록.
- 쓰기: 1,000명 실제 큐 입장 준비 후 동일 창에 고유 공연1 좌석 1매 구매. 201 TPS는 INSERT ticket + UPDATE seat 트랜잭션 비용을 함께 반영(독립 DML 비용 아님).
- index 실험의 큐 준비만 max/admit=2000/2000, Worker·poll=100ms; 구매 측정 창 제외. 큐 수치 실험과 혼동 금지.
- 후보는 매번 새 V1/V2 베이스라인에 정확히 하나만 DDL 적용, SHOW INDEX 원문으로 FK 자동 인덱스 포함 실제 구성을 확인. V3 없음.

- ix_seat_concert_status: 조회 p95 중앙값 224.11ms (baseline 240.86, 변화 -6.95%), 구매 201 TPS 187.17 (baseline 193.58, 변화 -3.31%).
- 채택 보류: 목표 조회가 없거나 잠정 개선/쓰기 기준 불충족.
- ix_ticket_user_created: 조회 p95 중앙값 8.87ms (baseline 8.80, 변화 +0.79%), 구매 201 TPS 196.82 (baseline 193.58, 변화 +1.68%).
- 채택 보류: 목표 조회가 없거나 잠정 개선/쓰기 기준 불충족.
- ix_concert_ticket_period: 조회 p95 중앙값 7.67ms (baseline 7.86, 변화 -2.46%), 구매 201 TPS 193.62 (baseline 193.58, 변화 +0.02%).
- 채택 보류: 목표 조회가 없거나 잠정 개선/쓰기 기준 불충족.

## 재현·정리

`bash backend/loadtest/index/run-index-comparison.sh`

실패/종료 시 측정 JVM·컨테이너·볼륨 정리. 개발 3306/6379/8080 접근 없음. 원시 출력은 gitignore.

## 결정 (2026-10-08)

- 사용자가 후보 3종을 모두 채택하지 않는다(Flyway V3 없음). 위 "채택 보류"를 그대로 확정한다.
- ix_seat_concert_status: 좌석 p95 -7%, 스캔 15,000→13,000행에 그친다. 좌석 대부분이 AVAILABLE이라 선택도가 낮고, 구매 TPS -3.3%, 크기 7.9MB의 비용이 있다.
- ix_ticket_user_created: 효과 없음(+0.8%). ix_concert_ticket_period: 대상 쿼리가 코드에 없다(ConcertService:87 findAll).
- 가장 느린 읽기는 좌석 가용 조회(p95 약 0.7s)다. 설계 5_DB대로 인덱스 비교 이후의 별도 실험(캐시) 대상이다.
