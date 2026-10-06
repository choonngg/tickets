# 대기열 Redis 저장소 (Phase 4-1)

`QueueRedisRepository`는 기존 `StringRedisTemplate`으로 Lua를 호출한다. HTTP API, 스케줄러, Entry Token 계약은 `QUEUE_API.md`에 있다. 서버 시각은 주입된 UTC `Clock`에서 한 번 읽고 epoch milliseconds로 전달한다. Redis `TIME`은 사용하지 않는다.

| Key | 타입 | Member / 값 / score |
| --- | --- | --- |
| `queue:{performanceId}:waiting` | ZSET | userId / 증가 순번 (`INCR sequence`) |
| `queue:{performanceId}:waiting-expiry` | ZSET | userId / WAITING 만료 epoch ms |
| `queue:{performanceId}:admitted` | ZSET | userId / ADMITTED 절대 만료 epoch ms |
| `queue:{performanceId}:active` | ZSET | userId / ACTIVE 절대 만료 epoch ms |
| `queue:{performanceId}:registered-at` | HASH | userId → 최초 등록 epoch ms |
| `queue:{performanceId}:sequence` | STRING | 마지막 증가 순번 |
| `queue:{performanceId}:admit-history` | LIST | 최근 12회 실제 입장 허가 인원 (최신 항목이 앞) |
| `queue:{performanceId}:admit-rate` | STRING | 최근 12회 실제 입장 허가 인원 합 |
| `queue:active-performances` | SET | 처리할 performanceId |

`{performanceId}`는 치환 표기다. 실제 공연 123의 Key는 `queue:123:waiting`이다. userId는 숫자 변환 없이 문자열로 취급하여 Java long 범위를 보존한다. 순번 score는 Redis double의 정확한 정수 범위(2^53−1) 내에서만 생성한다. 빈 공연에서도 sequence와 입장 통계는 유지한다. 기존 `idempotency:tickets:v2:`와 독립적이다. 전역 Key를 함께 쓰므로 단일 Redis 전용이며 Redis Cluster를 지원하지 않는다.

다섯 가지 실행 스크립트에 공통 검증·함수를 앞붙여 로드한다 (`common.lua` 자체는 실행하지 않는다).

| 작업 | 처리 |
| --- | --- |
| `register` | 유효 ACTIVE/ADMITTED 유지. 최초 등록 후 30초 이하이며 waiting-expiry > now인 중복만 순번·등록 시각 유지하고 생존 갱신. 만료 WAITING(유예 내 포함) 및 유예 이후 재등록은 기존 데이터를 제거하고 맨 뒤로 이동. 만료 ACTIVE/ADMITTED는 배치 전에도 재등록 가능 |
| `status` | WAITING 생존 갱신, rank = ZRANK + 1, behind = ZCARD − rank. 만료/누락은 EXPIRED, 삭제·부활 없음. ADMITTED/ACTIVE 절대 만료 유지 |
| `admit` | 만료 정리 → 빈자리 계산 → 최대 주기당 인원만 ZPOPMIN → 유효 WAITING만 ADMITTED 전환 → 빈 공연 SREM → 최근 12회 입장 통계 갱신 |
| `cancel` | 유효 ACTIVE(active score > now)는 ACTIVE 결과를 반환하고 쓰기 없이 거부(Java에서 409 QUEUE_ALREADY_ACTIVE). 나머지 상태/등록 시각 제거 후 다섯 Key 공백 판정과 SREM을 원자 실행. 미등록/중복 취소는 멱등 |
| `enter` | 만료 정리, 유효 ADMITTED → ACTIVE. 유효 ACTIVE 재요청은 기존 만료 반환. 만료 입장 권한은 EXPIRED, 입장 허가가 없는 사용자는 NOT_ADMITTED. 빈 공연 SREM |

불변식은 `ADMITTED + ACTIVE ≤ maxConcurrent`, 한 사용자/공연당 하나의 상태다. 유효 ACTIVE 취소를 거부하여 기발급 무상태 Entry Token이 exp까지 유효한 동안 Worker가 해당 자리를 재사용하지 않는다. ACTIVE 자리 회수는 절대 만료 한 가지 기준이다. 만료 WAITING은 status·register 모두 부활시키지 않으며, register에서 기존 데이터를 제거하고 새 sequence·등록 시각으로 맨 뒤에 삽입한다. 정리 예산은 WAITING, ADMITTED, ACTIVE의 **합계**이며 한 호출당 최대 설정값(최대 200명)이다. 주기당 `admit`을 한 번 호출한다. 남은 만료 데이터는 후속 호출로 이어서 정리한다. 배치에서 제거되지 않은 만료 WAITING이 ZPOPMIN에 잡히면 허가하지 않고 waiting-expiry/registered-at을 다음 정리까지 유지한다. 그만큼의 입장 인원은 같은 주기에 보충하지 않는다.

등록의 SADD와 정리의 다섯 Key 공백 확인 + SREM은 각 Lua 내부에서 원자적으로 실행한다. waiting, waiting-expiry, admitted, active, registered-at 중 하나라도 남아 있으면 활성 목록에서 제거하지 않는다. 통계/sequence는 이 공백 판정에 포함하지 않는다.

폴링 구간은 1-based rank ≤ 100: 3초, ≤ 1000: 10초, 나머지: 20초다. Lua는 기준 주기 × 3을 만료 시각에 더한다. Java 반환 시에만 ±20% jitter를 적용하며 TTL에는 영향을 주지 않는다. Status의 `basePollAfterMs`는 검증·관찰용 기준값, `nextPollAfterMs`는 jitter 적용값이다. WAITING 외 rank/behind/poll은 0이고, 유효 만료가 없는 결과의 expiresAt은 null이다. admitRate는 실측 합계로 그대로 반환한다.

모든 작업의 KEYS 순서는 위 표 순서(9개)다. ARGV 14개는 다음 순서다:

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

`ticket.queue.*`로 모든 정책값을 외부화한다. 기본값은 main/test/integration-test YAML에 명시한다. Redis 정책용 별도 환경변수나 의존성은 추가하지 않는다. Entry Token 키 환경변수는 `QUEUE_API.md`에 명시한다.

Redis Lua는 오류 발생 시 기존 쓰기를 롤백하지 않는다. 쓰기 전에 인자 개수·범위·ID·Key 이름과 타입을 확인하고 sequence/통계 숫자도 검증한다. 접근하는 사용자 및 정리·입장 후보의 상태 중복/누락과 등록 시각을 먼저 검증한다. 정리·입장 후보 검증도 첫 쓰기 전에 끝내며 전체 대기열은 순회하지 않는다. 외부에서 손상된 데이터를 발견하면 `QUEUE ...` 오류로 중단하고, 해당 호출은 데이터를 바꾸지 않는다. 자동 복구 정책은 추가하지 않는다.

검증은 Redis 7.2.4 Testcontainers 전용 통합 테스트(별도 MySQL 불필요)와 Clock/설정 바인딩 단위 테스트로 수행한다. `test` 프로필도 API Bean 의존성을 위해 저장소 Bean을 만들지만 Worker 초기/반복 지연을 충분히 길게 설정해 Redis 호출을 막는다. API 테스트는 저장소를 Mockito로 대체하고 실제 Redis 불변식은 통합 테스트에서 검증한다.

Phase 4 리뷰 후속 검증: 재등록의 만료 전/만료 후 유예 내/유예 후 경계, ACTIVE 취소 무변경·수용량 유지, admit↔register 직접 경합(100회)을 `QueueRedisRepositoryIntegrationTest`로 검증한다. HTTP 409·기발급 토큰 유효성은 `QueueApiIntegrationTest`, status의 DB 호출 0은 `QueueServiceTest`로 검증한다. enter의 정리·SREM은 유지하며 설계 이탈 근거와 영향은 `plan/plan_output/05_설계_정정_및_API_계약.md` §3(h)에 기록한다.
