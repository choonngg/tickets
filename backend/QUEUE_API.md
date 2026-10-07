# 대기열 HTTP API (Phase 4-2)

모든 대기열 작업은 기존 로그인 AT를 `Authorization: Bearer <AT>`로 받는다. Entry Token을 로그인 Bearer로 보내면 401 `UNAUTHORIZED`다. 요청 본문은 없다. 등록·입장·취소에서 탈퇴/누락 사용자는 404 `USER_NOT_FOUND`, 없는 공연은 404 `CONCERT_NOT_FOUND`다. 상태 조회는 DB 검사를 하지 않고 Redis Lua 1회 왕복으로 응답한다. 없는 공연·미등록은 200 `EXPIRED`이며, 탈퇴 사용자도 기존 AT가 유효하면 Redis 상태를 조회할 수 있다. 등록만 판매 상태를 검사하며 ON_SALE이 아니면 409 `CONCERT_NOT_ON_SALE`이다.

| 메서드 | 경로 | 성공 |
| --- | --- | --- |
| POST | `/api/queue/{concertId}` | 200, 등록/기존 상태 응답 |
| GET | `/api/queue/{concertId}/status` | 200, 상태 응답 + WAITING 생존 갱신 |
| POST | `/api/queue/{concertId}/enter` | 200, ACTIVE 전환/재요청 + Entry Token |
| DELETE | `/api/queue/{concertId}` | 204, WAITING/ADMITTED/만료 상태 제거(멱등); 유효 ACTIVE는 409 |
| GET | `/api/queue/.well-known/jwks.json` | 200, 큐 공개키 한 개(인증 불필요) |

등록·상태 응답:

```json
{
  "concertId": 123,
  "status": "WAITING",
  "rank": 3,
  "behind": 7,
  "nextPollAfterMs": 3300,
  "estimatedWaitSeconds": 1.5,
  "expiresAt": "2030-01-01T00:00:09Z",
  "serverTime": "2030-01-01T00:00:00Z"
}
```

- serverTime은 등록·상태·입장 200 응답 생성 시 주입된 서버 Clock에서 읽은 ISO-8601 UTC `Z` 시각이다. 클라이언트는 수신 시점의 `expiresAt - serverTime`을 남은 시간으로 잡고, 이후 경과 시간을 차감한다. 클라이언트 절대 시계와 expiresAt을 직접 비교하지 않는다.
- 재등록은 최초 등록 후 30초 이내(경계 포함)이고 WAITING 만료 전인 경우에만 순번·등록 시각을 유지하며 생존 TTL을 갱신한다. 만료 시각 이상이거나 유예를 넘으면 맨 뒤에 새로 등록한다(정리 배치 실행 여부와 무관).
- status: `WAITING`, `ADMITTED`, `ACTIVE`, `EXPIRED`. 미등록/취소 후 조회도 `EXPIRED`다.
- rank는 1-based, behind는 내 뒤의 대기 인원이다. WAITING 외에는 rank/behind/nextPollAfterMs가 0이다.
- WAITING의 nextPollAfterMs는 순번 기준 3/10/20초에 ±20% jitter를 적용한 값이다. 이 간격 뒤에 다시 조회한다. Redis 생존 TTL은 jitter 전 기준 간격의 3배다.
- estimatedWaitSeconds는 WAITING이고 실측 admit-rate > 0인 경우 `rank * 60.0 / admitRate`; 그 외 `null`이다. admit-rate는 최근 12주기 실제 입장 인원 합이며 화면에서 분으로 반올림할 수 있다.
- expiresAt은 상태별 절대 만료 시각이며 ISO-8601 UTC `Z` 문자열이다. EXPIRED는 null이다. ADMITTED/ACTIVE 만료는 status 요청으로 연장되지 않는다.

입장 응답:

```json
{
  "concertId": 123,
  "status": "ACTIVE",
  "entryToken": "<RS256 JWT>",
  "expiresAt": "2030-01-01T00:05:00Z",
  "serverTime": "2030-01-01T00:00:00Z"
}
```

ADMITTED에서 enter를 호출한다. ACTIVE에서 재호출하면 기존 active score로 토큰을 다시 발급하며 만료를 연장하지 않는다. JWT는 설계의 `typ: JWT`, `kid`, `iss: queue-service`, `aud: booking-service`, `sub: userId 문자열`, `pid: concertId 문자열`, `rid: 등록 회차 문자열`, `exp`를 가진다. JWT NumericDate는 초 정밀도이므로 exp는 Redis millisecond score를 초로 절삭한 값(최대 999ms 먼저 만료)이며 expiresAt은 원래 Redis score다. 동일 클레임의 RS256 재발급은 동일 문자열일 수 있다.

| 오류 | HTTP | message |
| --- | --- | --- |
| QUEUE_NOT_ADMITTED | 409 | `Queue admission is required.` |
| QUEUE_ADMISSION_EXPIRED | 409 | `Queue admission has expired. Register again.` |
| QUEUE_ALREADY_ACTIVE | 409 | `Queue user is already active. Cancellation is not allowed.` |

오류는 기존 `{ "code": "...", "message": "..." }` 형식이다. WAITING/미등록 enter는 QUEUE_NOT_ADMITTED, 아직 Redis에 남은 만료 ADMITTED/ACTIVE enter는 QUEUE_ADMISSION_EXPIRED다. Worker가 이미 정리한 상태는 미등록과 구분되지 않으므로 QUEUE_NOT_ADMITTED가 될 수 있다. 클라이언트는 두 오류 모두 재등록으로 처리한다. 유효 ADMITTED/ACTIVE의 입장 회차 정보(admission id)가 없으면 enter Lua가 UUID 후보를 HSETNX로 저장하고 전환 결과와 함께 반환한다. 기존 admission id와 ACTIVE 만료는 유지하며, 소진·만료·미등록 상태에는 id를 만들지 않는다. 유효 ACTIVE의 취소는 409 `QUEUE_ALREADY_ACTIVE`로 거부하며 모든 Redis 상태·active score·기발급 Entry Token의 유효성을 유지한다. ACTIVE 자리는 절대 5분 만료 또는 구매 성공 시 소진으로 회수한다. 구매 성공한 입장 회차는 다시 사용할 수 없다. WAITING·ADMITTED 및 만료 상태는 제거하며 미등록 취소는 204(멱등)다. 취소는 기존 탈퇴 사용자·공연 DB 검사를 유지한다(예매 API Entry Token 검증은 아래 Phase 5-1 계약을 따른다).

Worker는 `ticket.queue.worker-delay-ms`(기본 5000) fixedDelay, `worker-initial-delay-ms`(기본 5000)로 시작한다. 활성 공연을 순차 처리하며 오류를 공연별로 로그에 기록하고 다음 공연으로 진행한다. 입장 통계는 기존 admit Lua가 갱신한다. Lua 원자성은 여러 인스턴스에서도 수용량/중복 입장 불변식을 지킨다. 주기 수·입장 속도·12주기 통계는 다중 스케줄러에 의해 달라지므로 설계대로 Worker는 하나만 운영한다. 테스트 YAML은 초기/반복 지연을 2147483647ms로 설정하고 테스트가 runCycle을 직접 호출한다.

배포 환경변수:

- `QUEUE_JWT_PRIVATE_KEY_PEM`: 로그인 키와 별도의 2048-bit RSA PKCS#8 private PEM.
- `QUEUE_JWT_PUBLIC_KEY_PEM`: 대응하는 X.509 public PEM.
- `QUEUE_JWT_KEY_ID`: 기본 `queue-key-1`.

PEM은 실제 개행 또는 리터럴 `\n` 모두 지원한다. 키는 애플리케이션 시작 시 형식·강도·키 쌍 일치를 검증한다. 테스트의 queue 키는 로그인 테스트 키와 별개이며 운영에 사용하지 않는다. 로그인 JWKS(`/.well-known/jwks.json`)와 큐 JWKS는 서로의 키를 포함하지 않는다. 대기열 키용 새 환경변수·의존성은 없다. Phase 5 구매 복구용 DB 변경은 Flyway V2로 적용한다.


## 구매 계약 (Phase 5, 사용자 결정 2026-10-06)

`POST /api/tickets`는 `Authorization: Bearer <AT>`, `X-Entry-Token: <JWT 원문>`, `Idempotency-Key: <고유 키>`와 다음 본문을 받는다. `{seatId}` 단일 필드는 지원하지 않는다.

```json
{ "seatIds": [123, 124] }
```

좌석은 1~4개, 양의 ID, 중복 없이 같은 공연에 속해야 한다. 5개·빈 목록·중복·다른 공연 좌석은 400 `INVALID_REQUEST`, 없는 좌석은 404 `SEAT_NOT_FOUND`다. 전부 성공 또는 전부 실패하며 한 좌석이라도 매진이면 409 `SEAT_NOT_AVAILABLE`이다. 락 획득·버전 경합은 기존 409 `SEAT_LOCK_FAILED`다. 세 전략 모두 오름차순으로 좌석을 처리하며 결제는 합계 금액으로 1회 한다.

201 응답은 좌석 ID 오름차순 티켓 배열이다:

```json
[
  { "ticketId": 10, "userId": 1, "seatId": 123, "purchasePrice": 10000 },
  { "ticketId": 11, "userId": 1, "seatId": 124, "purchasePrice": 20000 }
]
```

공연당 사용자 누적 보유 수 + 요청 수가 4를 넘으면 409 `PURCHASE_LIMIT_EXCEEDED`, 메시지는 `Purchase limit of four tickets per concert exceeded.`다. 판정과 구매는 같은 트랜잭션에서 users 행 잠금으로 직렬화된다. 한 번에 4개 선택 가능하더라도 기존 보유 수만큼 선택 상한을 줄여야 한다.

신규 구매는 큐 공개키 RS256 서명·exp·iss/aud/kid/typ·sub/pid/rid를 검증하고 Redis에서 ACTIVE 회차와 만료를 대조하여 원자 선점한다. leeway는 0이며 `now >= exp`는 만료다. 서로 다른 키라도 같은 입장 회차가 진행 중이면 409 `REQUEST_IN_PROGRESS`다.

| 오류 | HTTP | 권장 화면 처리 |
| --- | --- | --- |
| ENTRY_TOKEN_REQUIRED | 403 | 헤더 누락·빈 값. 대기열을 통해 입장 |
| ENTRY_TOKEN_INVALID | 403 | 서명·클레임 오류, 소진·삭제된 입장, 이전 입장 회차. 대기열을 통해 입장 |
| ENTRY_TOKEN_EXPIRED | 403 | 이용 시간 종료 후 다시 대기열 |
| ENTRY_TOKEN_MISMATCH | 403 | 사용자·공연 불일치. 대기열을 통해 입장 |
| PURCHASE_LIMIT_EXCEEDED | 409 | 보유 수 확인 후 남은 수량 이하로 재선택 |
| REQUEST_IN_PROGRESS | 409 | 같은 키 또는 같은 입장 회차가 처리 중. 완료를 기다려 재시도 |
| IDEMPOTENCY_CONFLICT | 422 | 같은 키·다른 좌석 조합. 새 선택에는 새 키 사용 |

구매 실패 시 선점을 해제하고 ACTIVE·토큰 만료를 유지한다. 남은 시간 안에 좌석을 바꾸어 재시도할 수 있다. 구매 성공 시 토큰의 입장 회차를 소진하고 ACTIVE를 즉시 제거한다. 다음 Worker 주기에 다음 대기자가 입장한다. 추가 구매는 `POST /api/queue/{concertId}`로 다시 등록하여 맨 뒤 WAITING에서 시작한다. ACTIVE 등록/enter 재요청의 기존 입장 유지 규칙은 소진 전까지만 적용된다.

멱등성 본문 해시는 좌석 목록을 정렬한 뒤 생성하므로 순서만 바뀌어도 같은 요청이다. 처리 순서는 본문·키 검사 → 멱등성 선점/기존 결과 확인 → 같은 사용자·같은 키의 DB 복구 → 신규 구매만 Entry Token 검증·입장 선점 → 구매 트랜잭션 → 성공 소진/실패 해제 → 성공 응답 저장이다. 토큰 오류 시 멱등성 선점도 해제하여 같은 키로 유효 토큰 재요청이 가능하다.

성공 응답 유실 시 **같은 키·같은 좌석 목록으로 POST 재전송**하면 토큰이 소진되거나 만료되었어도 기존 201 배열을 반환한다. Redis 응답 저장 실패 후에는 IN_PROGRESS TTL(30초)이 끝나면 DB에서 해당 키의 전체 티켓 묶음을 복구한다. 다른 조합은 422이며 새 키로 이미 산 좌석을 요청하면 새 구매 검증을 거쳐 실패한다. 다른 구매들의 티켓을 합쳐 복구하지 않는다.

`GET /api/tickets/me`는 AT만 요구한다. 각 항목에 `concertId`가 추가된다. 해당 공연의 항목 개수를 세어 `max(0, 4 - 개수)`로 남은 한도를 계산할 수 있어 별도 조회 API는 추가하지 않는다. 이 값은 화면 안내용이며 최종 한도는 구매 트랜잭션에서 판정한다.
