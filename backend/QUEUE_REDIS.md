# 대기열 Redis 저장소 (Phase 4-1)

`QueueRedisRepository`는 기존 `StringRedisTemplate`으로 Lua를 호출한다. HTTP API, 스케줄러, Entry Token 계약은 `QUEUE_API.md`에 있다. 서버 시각은 주입된 UTC `Clock`에서 한 번 읽고 epoch milliseconds로 전달한다. Redis `TIME`은 사용하지 않는다.

| Key | 타입 | Member / 값 / score |
| --- | --- | --- |
| `queue:{performanceId}:waiting` | ZSET | userId / 증가 순번 (`INCR sequence`) |
| `queue:{performanceId}:waiting-expiry` | ZSET | userId / WAITING 만료 epoch ms |
| `queue:{performanceId}:admitted` | ZSET | userId / ADMITTED 절대 만료 epoch ms |
| `queue:{performanceId}:active` | ZSET | userId / ACTIVE 절대 만료 epoch ms |
| `queue:{performanceId}:registered-at` | HASH | userId → 최초 등록 epoch ms; `userId:admission` → 등록 UUID 문자열(rid) |
| `queue:{performanceId}:sequence` | STRING | 마지막 증가 순번 |
| `queue:{performanceId}:admit-history` | LIST | 최근 12회 실제 입장 허가 인원 (최신 항목이 앞) |
| `queue:{performanceId}:admit-rate` | STRING | 최근 12회 실제 입장 허가 인원 합 |
| `queue:active-performances` | SET | 처리할 performanceId |

`{performanceId}`는 치환 표기다. 실제 공연 123의 Key는 `queue:123:waiting`이다. userId는 숫자 변환 없이 문자열로 취급하여 Java long 범위를 보존한다. 순번 score는 Redis double의 정확한 정수 범위(2^53−1) 내에서만 생성한다. 빈 공연에서도 sequence와 입장 통계는 유지한다. 기존 `idempotency:tickets:v3:`와 독립적이다. 전역 Key를 함께 쓰므로 단일 Redis 전용이며 Redis Cluster를 지원하지 않는다.

다섯 가지 실행 스크립트에 공통 검증·함수를 앞붙여 로드한다 (`common.lua` 자체는 실행하지 않는다).

| 작업 | 처리 |
| --- | --- |
| `register` | 유효 ACTIVE/ADMITTED 유지. 최초 등록 후 30초 이하이며 waiting-expiry > now인 중복만 순번·등록 시각 유지하고 생존 갱신. 만료 WAITING(유예 내 포함) 및 유예 이후 재등록은 기존 데이터를 제거하고 맨 뒤로 이동. 만료 ACTIVE/ADMITTED는 배치 전에도 재등록 가능 |
| `status` | WAITING 생존 갱신, rank = ZRANK + 1, behind = ZCARD − rank. 만료/누락은 EXPIRED, 삭제·부활 없음. ADMITTED/ACTIVE 절대 만료 유지 |
| `admit` | 만료 정리 → 빈자리 계산 → 최대 주기당 인원만 ZPOPMIN → 유효 WAITING만 ADMITTED 전환 → 빈 공연 SREM → 최근 12회 입장 통계 갱신 |
| `cancel` | 유효 ACTIVE(active score > now)는 ACTIVE 결과를 반환하고 쓰기 없이 거부(Java에서 409 QUEUE_ALREADY_ACTIVE). 나머지 상태/등록 시각 제거 후 다섯 Key 공백 판정과 SREM을 원자 실행. 미등록/중복 취소는 멱등 |
| `enter` | 만료 정리, 유효 ADMITTED → ACTIVE. 유효 ACTIVE 재요청은 기존 만료 반환. 두 성공 경로 모두 admission 필드가 없으면 ARGV 15의 UUID 후보를 HSETNX로 저장하고 실제 id를 같은 Lua 결과에 반환(기존 id 불변). 만료 입장 권한은 EXPIRED, 입장 허가가 없는 사용자는 NOT_ADMITTED. 빈 공연 SREM |

불변식은 `ADMITTED + ACTIVE ≤ maxConcurrent`, 한 사용자/공연당 하나의 상태다. 유효 ACTIVE 취소는 거부한다. 구매 성공 시 입장 회차를 소진하고 ACTIVE를 제거하므로 기발급 토큰은 더 이상 구매할 수 없으며 Worker는 빈자리를 안전하게 재사용한다. 실패 시 ACTIVE를 유지한다. 만료 WAITING은 status·register 모두 부활시키지 않으며, register에서 기존 데이터를 제거하고 새 sequence·등록 시각으로 맨 뒤에 삽입한다. 정리 예산은 WAITING, ADMITTED, ACTIVE의 **합계**이며 한 호출당 최대 설정값(최대 200명)이다. 주기당 `admit`을 한 번 호출한다. 남은 만료 데이터는 후속 호출로 이어서 정리한다. 배치에서 제거되지 않은 만료 WAITING이 ZPOPMIN에 잡히면 허가하지 않고 waiting-expiry/registered-at을 다음 정리까지 유지한다. 그만큼의 입장 인원은 같은 주기에 보충하지 않는다.

등록의 SADD와 정리의 다섯 Key 공백 확인 + SREM은 각 Lua 내부에서 원자적으로 실행한다. waiting, waiting-expiry, admitted, active, registered-at 중 하나라도 남아 있으면 활성 목록에서 제거하지 않는다. 통계/sequence는 이 공백 판정에 포함하지 않는다.

폴링 구간은 1-based rank ≤ 100: 3초, ≤ 1000: 10초, 나머지: 20초다. Lua는 기준 주기 × 3을 만료 시각에 더한다. Java 반환 시에만 ±20% jitter를 적용하며 TTL에는 영향을 주지 않는다. Status의 `basePollAfterMs`는 검증·관찰용 기준값, `nextPollAfterMs`는 jitter 적용값이다. WAITING 외 rank/behind/poll은 0이고, 유효 만료가 없는 결과의 expiresAt은 null이다. admitRate는 실측 합계로 그대로 반환한다.

모든 작업의 KEYS 순서는 위 표 순서(9개)다. ARGV 15개는 다음 순서다:

1. performanceId
2. userId (`admit`만 빈 문자열)
3. now epoch ms
4. maxConcurrent
5. admitPerCycle
6. admittedTtlMs
7. activeTtlMs
8. cleanupBatchSize
9. reregisterGraceMs
10. fastRankThreshold
11. mediumRankThreshold
12. fastPollMs
13. mediumPollMs
14. slowPollMs
15. registrationId (서버 UUID 후보, 신규 WAITING 등록 또는 유효 enter의 누락 admission 필드에만 저장)

`ticket.queue.*`로 모든 정책값을 외부화한다. 기본값은 main/test/integration-test YAML에 명시한다. Redis 정책용 별도 환경변수나 의존성은 추가하지 않는다. Entry Token 키 환경변수는 `QUEUE_API.md`에 명시한다.

Redis Lua는 오류 발생 시 기존 쓰기를 롤백하지 않는다. 쓰기 전에 인자 개수·범위·ID·Key 이름과 타입을 확인하고 sequence/통계 숫자도 검증한다. 접근하는 사용자 및 정리·입장 후보의 상태 중복/누락과 등록 시각을 먼저 검증한다. 정리·입장 후보 검증도 첫 쓰기 전에 끝내며 전체 대기열은 순회하지 않는다. 외부에서 손상된 데이터를 발견하면 `QUEUE ...` 오류로 중단하고, 해당 호출은 데이터를 바꾸지 않는다. 자동 복구 정책은 추가하지 않는다.

검증은 Redis 7.2.4 Testcontainers 전용 통합 테스트(별도 MySQL 불필요)와 Clock/설정 바인딩 단위 테스트로 수행한다. `test` 프로필도 API Bean 의존성을 위해 저장소 Bean을 만들지만 Worker 초기/반복 지연을 충분히 길게 설정해 Redis 호출을 막는다. API 테스트는 저장소를 Mockito로 대체하고 실제 Redis 불변식은 통합 테스트에서 검증한다.

Phase 4 리뷰 후속 검증: 재등록의 만료 전/만료 후 유예 내/유예 후 경계, ACTIVE 취소 무변경·수용량 유지, admit↔register 직접 경합(100회)을 `QueueRedisRepositoryIntegrationTest`로 검증한다. HTTP 409·기발급 토큰 유효성은 `QueueApiIntegrationTest`, status의 DB 호출 0은 `QueueServiceTest`로 검증한다. enter의 정리·SREM은 유지하며 설계 이탈 근거와 영향은 `plan/plan_output/05_설계_정정_및_API_계약.md` §3(h)에 기록한다.


## 구매 입장 선점·소진 (Phase 5)

`EntryAdmissionStore`의 운영 구현은 `RedisEntryAdmissionStore`이며 `purchase.lua`를 실행한다. 테스트 프로필의 인메모리 구현은 test 소스셋에만 있다.

| Key | 타입 | 값·수명 |
| --- | --- | --- |
| `queue:{concertId}:purchase:{userId}` | STRING | `rid:owner(UUID)`; 선점 시 `PX = JWT exp - now`로 만료 |
| `queue:{concertId}:registered-at`의 `userId:admission` 필드 | HASH field | 등록 UUID 문자열. ACTIVE enter 재요청에도 불변; 재등록 시 새 UUID |

등록 Lua가 timestamp와 admission 필드를 함께 만든다. 정책 변경 이전 등록분처럼 admission 필드가 누락된 유효 ADMITTED/ACTIVE는 enter Lua에서 HSETNX로 보완한다. enter 반환값은 기존 여섯 항목(state, rank, behind, expiry epoch ms, basePollAfterMs, admitRate)에 일곱 번째 admission id를 붙이며, 실패 상태는 빈 문자열이다. Java는 이 결과로 토큰을 발급하고 별도 HGET을 하지 않는다. 기존 removeMember는 두 필드를 함께 제거한다. HASH 필드 수는 등록 사용자 수의 두 배지만 정리 상한은 사용자 기준 200명으로 유지한다. 입장 JWT의 서명된 `rid`로 이전 토큰과 새 입장을 구분하므로 같은 초에 재입장해 exp가 같아도 이전 토큰은 무효다.

purchase.lua KEYS 3개: `active`, `registered-at`, `purchase:{userId}`. ARGV 6개: operation(`claim`/`consume`/`release`), userId, rid, owner, JWT exp epoch ms, Clock now epoch ms. 기존 큐 스크립트는 9 KEYS를 유지하고 ARGV 15번째에 새 등록 UUID를 추가한다. register의 ACTIVE/ADMITTED 유지·WAITING 유예 재등록은 이 새 UUID를 쓰지 않고 기존 회차를 유지한다. enter도 기존 admission 필드가 있으면 후보 UUID를 버리고 기존 회차를 반환한다. Key 타입·operation·claim 시각을 첫 쓰기 전에 검증한다.

- claim: ACTIVE score > now, score를 초로 절삭한 값 == JWT exp, HASH admission == rid, exp > now를 모두 확인한다. 불일치 0(403 ENTRY_TOKEN_INVALID), 기존 선점 2(409 REQUEST_IN_PROGRESS), 성공 1. 확인과 SET/PX가 한 Lua에 있어 같은 사용자·공연의 입장은 하나만 진행한다.
- release: `rid:owner` 일치 시 선점 Key만 삭제한다. 실패한 오래된 요청이 새 owner의 선점을 해제하지 못한다. ACTIVE와 만료는 유지한다.
- consume: **구매 커밋 또는 같은 키의 DB 복구 후에만** 호출한다. 현재 admission이 구매에 기록한 rid와 같을 때 ACTIVE·등록 timestamp·admission·선점 Key를 함께 제거한다. claim TTL이 구매 중 만료되어도 커밋된 회차를 제거할 수 있다. 새로운 입장 회차는 제거하지 않는다. 성공 구매의 DB admission 기록도 토큰 재사용을 막는다.

소진은 활성 공연 SET을 제거하지 않는다. WAITING이 남으면 다음 Worker가 즉시 그 빈자리를 사용하고, 모두 비었으면 기존 admit 정리가 SREM한다. 소진은 ADMITTED+ACTIVE 합을 감소시키고 신규 claim은 유효 ACTIVE만 허용하므로 R4-1의 무상태 토큰 조기 회수 상한 초과 문제가 발생하지 않는다.

Redis 장애로 커밋 후 consume/응답 저장이 실패하면 성공 응답은 유지한다. DB에 사용자·키 SHA-256·입장 회차가 기록되어 재결제·토큰 재사용을 방지하고, 같은 키 재시도에서 consume을 재시도한다. 그때까지 슬롯은 기존 만료·Worker 정리로 회수된다. 실패 release 장애의 선점 기록은 JWT exp에 맞춘 TTL로 사라진다. Lua와 DB의 분산 트랜잭션/백그라운드 재시도 인프라는 추가하지 않는다.

멱등성은 목록 응답과 새 해시를 위해 `idempotency:tickets:v3:{userId}:{key}`를 쓴다. IN_PROGRESS 30초, COMPLETED 24시간은 유지한다. Flyway V2의 `ticket.purchase_key`(키 SHA-256), `ticket.admission_id`와 사용자·구매키 인덱스로 전체 묶음을 복구한다. 기존 티켓은 NULL이며 공연별 보유 수에는 포함한다. 롤아웃 이전 토큰은 rid가 없어 신규 구매에서 INVALID이며 다시 입장해야 한다.
