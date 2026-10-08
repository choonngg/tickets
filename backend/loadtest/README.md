# 로컬 구매 전략 비교

로컬 Docker의 **측정 전용** MySQL 8.0.36·Redis 7.2.4와 호스트 JVM/k6로 비교한다. 운영 측정은 하지 않는다. 운영 코드·루트 compose·개발 DB/Redis를 변경하지 않는다.

## 한 번에 실행

저장소 루트에서:

```bash
bash backend/loadtest/run-strategy-comparison.sh
```

일부만 추가 측정하려면 옵션을 준다(결과 문서는 덮어쓰지 않고 `raw/partial_report_offset*.md`에 쓴다). 예: `--scenarios B --repeats 7 --run-offset 3`(회차 4~10, 회차마다 전략 순서 회전). 옵션: `--scenarios`, `--strategies`, `--repeats`, `--run-offset`.

필수: 로컬 Docker 소켓 접근 및 Compose, Java 25, k6, Python 3 + 이미 설치된 `cryptography`(RSA 서명), `backend/.env`의 기존 JWT·큐 JWT·Google 설정. 의존성을 자동 설치하지 않는다. `.env`는 `bootRun`과 동일하게 첫 `=` 기준으로 읽고 shell로 실행하지 않는다. OS의 동일 이름 secret이 우선한다. PEM 리터럴 `\n`을 지원한다. secret 값은 출력하지 않는다.

13306(MySQL), 16379(Redis), 18080(앱)이 비어 있어야 한다. Docker endpoint는 `unix:///var/run/docker.sock`으로 고정한다. 개발용 3306/6379와 다른 포트이며 `ticket-loadtest` 프로젝트의 `mysql-data`/`redis-data` 볼륨만 사용한다. Compose의 포트와 앱은 loopback에만 바인딩한다. Docker 컨테이너의 자원 제한은 없으므로 재측정 때 호스트의 다른 부하를 같은 수준으로 유지한다.

`bootJar` → 전용 컨테이너 기동·health 확인 → 18회 측정 → Markdown 결과 생성 → 컨테이너·볼륨 제거 순서다. 중간 실패 시에도 자동 정리하고 0이 아닌 종료 코드를 반환한다. 중복 실행은 파일 잠금으로 막는다. 전체 실행은 수십 분 걸릴 수 있다. 실제 실행이 차단되면 결과표에 `미측정`을 기록하며 성능 추천을 보류한다.

## 조건

| 항목 | 설정 |
|---|---|
| A: 동일 좌석 | 서로 다른 사용자 1,000명, 동일 1석, 사용자당 1 요청, 성공 정확히 1건 |
| B: 핫 구역 | 서로 다른 사용자 1,000명, HOT 300석, 균등 무작위 1~4석(요청 내부 중복 없음) |
| 반복 | 각 시나리오 × 3전략 × 3회 = 18회 |
| 고정 seed | 20261007, Python `random.Random`, 모든 회차의 B 요청 목록 동일 |
| 순서 | 회차별 pessimistic→optimistic→distributed 순서를 한 칸씩 회전 |
| 앱 | 매 회 새 JVM, 단일 인스턴스, Xms/Xmx 1GiB, CPU 수 8, Tomcat 최대 200 thread |
| 커넥션 | Hikari min/max 10, 세 전략 동일 |
| 큐 | max-concurrent/admit-per-cycle 2000, worker 100ms, polling 100ms, TTL은 기존 값 |
| 예열 | 다른 공연의 서로 다른 100석을 32개 클라이언트로 구매 후 DB·Redis 다시 초기화 |
| 구매 시작 | 실제 큐로 전원 입장 후 k6 1,000 VU, VU당 1회, 공통 15초 장벽, 재시도 없음 |

시작 장벽은 요청 발송을 한 시점에 모으지만 OS/k6 스케줄링 및 Tomcat/Hikari 제한 때문에 서버 처리까지 물리적으로 동시임을 보장하지 않는다. `dispatch_offset_ms`의 min/max를 함께 기록한다. B의 좌석 선택은 같아도 서버 처리 순서에 따라 성공 수·판매 좌석 수가 달라질 수 있다. 예열 100건은 JVM 정상 기동과 기본 경로 확인용이며 긴 정상 상태 부하를 대신하지 않는다.

전략은 `TICKET_PURCHASE_STRATEGY`로 바꾼다. `application-loadtest.yaml`은 `spring.config.additional-location`으로만 읽으며 jar에 포함되지 않는다. 상속된 Spring/JVM override는 제거하여 측정 DB/Redis 연결이 `.env`나 OS 설정으로 바뀌지 않게 한다.

## 인증·구매 정책

`users`·`auth_session`을 시드한 뒤 로컬 RSA 키로 RS256 AT를 서명한다. `JwtAuthenticationFilter.authenticate` → `JwtProvider.parse`는 **세션 행을 조회하지 않는다**. `sid` 양수·서명·iss/aud/kid/typ·role·시간 클레임을 확인하는 무상태 경로다. 따라서 시드는 실제 세션 모양을 유지하지만 필터의 세션 조회 성능을 측정하는 것은 아니다. 구매의 사용자 존재/탈퇴 검사는 그대로 실행한다.

Entry Token은 Redis 직접 삽입/서명으로 만들지 않는다. 사용자별 `POST /api/queue/1` → `GET /api/queue/1/status`(WAITING 동안 서버 poll 간격 준수) → ADMITTED 후 `POST /api/queue/1/enter`로 얻는다. 구매에는 AT, `X-Entry-Token`, 사용자별 고유 `Idempotency-Key`와 실제 `seatIds`를 보낸다. Entry 1회 소진·1인 공연당 4매·묶음 전부 성공/실패 정책과 MockPaymentClient를 유지한다.

## 측정·실패 판정

- 성공률: 전체 예정 1,000 요청 대비 201 비율. 코드 전체 분포, 409 `SEAT_NOT_AVAILABLE`/`SEAT_LOCK_FAILED`, 5xx 및 기타 응답을 표시한다. 미발송 요청, 예상하지 않은 409/4xx/5xx/transport 오류는 실패다.
- p50/p95/p99: 구매 HTTP 응답 전체의 k6 Trend(ms). 큐 준비·장벽·예열 제외. 201 body의 좌석 집합·사용자도 확인한다.
- Hikari active/pending: 보호된 `/actuator/metrics`를 시드 AT로 200ms 간격 목표로 표본 수집. 실제 간격은 API 처리시간만큼 늘어나며 순간 피크를 놓칠 수 있다. 지표 수집 오류는 실패다.
- Hikari 획득 대기: `hikaricp.connections.acquire` Timer의 COUNT/TOTAL_TIME 전후 차이로 평균(ms)을 계산한다. 계측 API 자체의 인증은 무상태이며 DB를 쓰지 않는다.
- InnoDB: `Innodb_row_lock_time`(ms), `Innodb_row_lock_waits` 전후 차이. 측정 전용 MySQL global 지표라 개발 DB 트래픽이 섞이지 않는다.
- 충돌 수: 두 종류 좌석 409의 합이다. 내부 낙관 버전 충돌만의 수치와 같지 않다.
- SQL: `ticket GROUP BY seat_id HAVING COUNT(*)>1`; SOLD인데 티켓 없음/AVAILABLE인데 티켓 있음; 사용자·공연별 티켓 4매 초과. 시드 요청 목록과 DB 티켓 좌석 집합을 비교해 부분 묶음 및 HTTP↔DB 불일치도 검사한다. 모든 위반은 0이어야 하고 A는 티켓/201 정확히 1건이어야 한다.

최종 표는 `results/strategy_comparison.md`. 각 회차의 `raw/<시나리오>-<전략>-<회차>/`에는 k6 JSON·요약·로그, 앱 로그, Hikari 표본, SQL 검사와 지표 차이 `result.json`을 남긴다. 대용량 raw는 `.gitignore`로 제외한다. AT/Entry를 담은 입력 파일은 k6 종료 시 제거한다. 강제 종료로 남은 경우에도 raw는 공유하지 않는다. 새 실행은 같은 이름의 raw를 덮어쓰므로 비교 전 필요한 원시 자료를 별도 보관한다.

추천 규칙: 전체 18회 정합성 통과가 전제다. 단일 인스턴스에서는 단순한 pessimistic을 기준으로 삼고, distributed가 B의 3회 p95 중앙값을 20% 이상 줄이면서 성공 요청 합계가 낮지 않을 때 distributed를 추천한다. 이 로컬 결과로 다중 인스턴스 확장성을 확정하지 않는다. 낙관적 전략의 집중 경합 적합성은 409 분포·지연·DB 대기를 함께 비교한다. 운영 기본값은 이 도구가 바꾸지 않는다.

## 정리·도구 검증

정리만 다시 실행:

```bash
bash backend/loadtest/run-strategy-comparison.sh --cleanup
```

내부 명령은 다음과 같다. **프로젝트/파일을 바꾸지 않는다.**

```bash
docker --host unix:///var/run/docker.sock compose -p ticket-loadtest -f backend/loadtest/compose.yaml down --volumes --remove-orphans
```

이는 `ticket-loadtest` 프로젝트 리소스만 지우며 개발 compose의 컨테이너·볼륨·Redis 6379에 접근하지 않는다. 원시 출력은 남는다. 실행 중 강제 종료 후 18080 JVM이 남았다면 해당 실행의 JVM PID를 확인해 종료한다.

Docker 없이 도구의 시드·JWT·원시 지표 파싱·정합성 판정·보고서 생성을 검사:

```bash
python3 backend/loadtest/check_tool.py
```

기존 회귀 확인:

```bash
cd backend && ./gradlew --no-daemon test
```

## Phase 6-2 장애 주입

저장소 루트에서 한 명령으로 F1→F2→F3를 실행한다:

```bash
bash backend/loadtest/fault/run-fault-injection.sh
```

필수 도구와 로컬 secret은 위 비교 도구와 같으며, 장애 도구는 Python 표준 HTTP 클라이언트를 사용하므로 **k6는 필요 없다**. `cryptography`는 기존 AT 서명용이다. 13306·16379·18080·18081이 비어 있어야 한다. `ticket-loadtest` 전용 Compose·볼륨과 비교 도구의 파일 잠금을 공유하므로 두 도구를 동시에 실행하지 않는다. 실행 시 전용 DB·Redis를 초기화하고, 종료 시 전용 컨테이너·볼륨을 제거한다. 개발 DB·6379·ticket-redis·8080에는 접근하지 않는다.

| 시나리오 | 주입 방법 | 통과 조건 |
|---|---|---|
| F1 distributed | 외부 `application-fault.yaml`로 lease 500ms(wait 0), 측정 MySQL 세션에서 좌석 행 잠금 8초. 첫 구매의 DB 대기·Redis 락 소멸 후 두 번째 구매를 발송하고 두 DB 대기를 확인 | 201 정확히 1건, 나머지는 409 SEAT_NOT_AVAILABLE/SEAT_LOCK_FAILED, 커밋된 결과가 5xx로 바뀌지 않음 |
| F1 DB 최종 방어 | 같은 좌석의 기존 티켓을 유지하면서 상태만 AVAILABLE로 바꿔 UNIQUE 위반을 강제 | 409 SEAT_NOT_AVAILABLE, `uq_ticket_seat violation` 로그, 티켓 수 불변·실패 트랜잭션 롤백. 고의 불일치는 SOLD로 복원 후 최종 SQL 검사 |
| F2 pessimistic | 2매 구매 1건 성공 후 다른 2매 구매를 DB에서 지연, 측정 Redis `stop -t 1`, 신규 32명(각 1~4매) 부하, 선점 완료 구매가 끝난 뒤 `start` | 중단 중 신규 요청은 500 INTERNAL_SERVER_ERROR와 고정 message, 선점 완료 구매는 201, 재시작 뒤 같은 키 replay. 실제 재시작 큐 상태 기록 |
| F2 전체 상태 유실 | 재시작 상태 관찰 뒤 측정 Redis FLUSHDB | 큐 상태 EXPIRED, 신규 구매의 구 Entry Token은 403, 재등록 가능·재시도 201, 이미 성공한 구매는 구 토큰·같은 키로 DB에서 동일 응답 복구 |
| F3 pessimistic | loopback 18081 프록시가 실제 서버 201 전체를 읽고 클라이언트에 응답 없이 연결 종료 | 클라이언트 transport 오류 + 실제 DB 커밋 확인, 소진 토큰으로 동일 키 201 replay. Redis 멱등성 키 DEL 뒤에도 동일한 ticketId·응답 배열 |
| F3 새 키·한도 | 소진 토큰/새 키, 새 입장/매진 좌석, 보유 2매에서 추가 3매·2매·1매 순서 | 구 토큰 403, 새 입장 매진 409, 5매 거부 후 같은 입장으로 4매 성공, 5번째 409 PURCHASE_LIMIT_EXCEEDED |

서버에 지연·장애 우회 코드를 넣지 않는다. 외부 MySQL 행 잠금과 HTTP 프록시만 사용한다. 각 시나리오마다 새 JVM과 DB/Redis 시드를 사용한다. 500ms lease는 **이 측정 전용 설정**이며 `ticket.seat-lock-lease` 기본값 10초는 유지된다. 설정값은 1ms 이상이어야 한다. 다른 동작·API 계약·기본 pessimistic 전략은 유지한다.

F1의 좌석 UPDATE에는 기존 `@Version` 검사가 있으므로 lease 경합에서 UNIQUE 위반보다 버전 충돌이 먼저 날 수 있다. 따라서 UNIQUE 방어를 별도 하위 시나리오로 강제하고 경합과 구분해 기록한다. 정상 커밋 후 lease가 이미 끝났으면 unlock을 생략한다. 확인 후 만료/Redis 장애로 unlock 자체가 실패하는 경합은 `RedissonSeatLockManagerTest`가 결과 보존을 검사한다.

F2의 stop/start는 Redis 데이터 보존을 보장하지 않는다. 재시작 후 큐 상태를 관찰한 다음, 별도 FLUSHDB로 전체 유실을 확실히 재현한다. Redis 장애 후 `IN_PROGRESS`가 남았다면 계약에 따라 최대 30초 동안 `409 REQUEST_IN_PROGRESS`가 가능하고, TTL 소멸 뒤 재전송해야 DB 복구로 진입한다. 도구는 해당 TTL을 기다린다. Redis가 살아난 뒤 연결·health도 확인한다.

각 시나리오의 종료 시 SQL은 좌석 중복, SOLD↔ticket 불일치, 사용자·공연별 4매 초과, 구매키별 부분 묶음을 검사한다. 부분 묶음은 임시 SQL 테이블의 예정 좌석 집합과 DB 구매키를 대조하며, 실패한 요청의 티켓이 0개인 경우와 같은 키 replay를 구분한다. 네 가지 모두 0이어야 통과한다. 결과는 `results/fault_injection.md`, 시나리오별 응답·검사 JSON 및 앱 로그는 `raw/fault/F*/`다. AT·Entry Token을 결과/로그에 출력하거나 파일로 저장하지 않는다.

Docker 접근이 막히면 리소스를 건드리기 전에 종료 코드 1로 중단하고 결과 문서에 **실측 미실행**, 응답 코드·정합성 건수 **미확인**을 쓴다. 값이 비어 있는 항목을 0건/통과로 해석하지 않는다. 주입 확인 실패(예: DB 대기 미확인)도 통과로 기록하지 않는다.

Docker 없는 도구 검증(SQL 판정은 SQLite, 프록시 전달·연결 종료는 소켓 없이 HTTP 더블로 검사; 결과 문서에 합성값을 쓰지 않음):

```bash
python3 backend/loadtest/fault/check_fault.py
cd backend && ./gradlew --no-daemon test integrationTest
```

두 번째 명령의 `test`는 Docker 없이 실행 가능하지만 `integrationTest`는 Testcontainers Docker 접근이 필요하다. 전용 환경 정리만 다시 하려면:

```bash
bash backend/loadtest/fault/run-fault-injection.sh --cleanup
```

강제 종료 후 남은 JVM은 `raw/fault/F*/app.log` 및 해당 실행의 PID를 확인해 정리한다. 자동 정리는 개발용 리소스를 제거하지 않는다. Docker가 허용된 로컬 환경에서 실측 명령을 재실행한 뒤 생성 보고서의 F1/F2/F3 판정과 네 SQL 건수를 확인한다.

## Phase 6-3 대기열 수치 / 6-4 인덱스 후보

저장소 루트에서 각각 한 명령으로 모든 조건·반복, 보고서 작성, 정리를 수행한다:

```bash
bash backend/loadtest/queue/run-queue-numbers.sh
bash backend/loadtest/index/run-index-comparison.sh
```

기존 전략 비교와 같은 로컬 Docker Compose·파일 잠금을 재사용하므로 도구는 동시에 실행하지 않는다. 필수 도구는 위 목록에 **Java 25 JDK의 javac·jar**를 더한다. 추가 패키지 설치 없이 기존 cryptography와 Python 표준 라이브러리, Java 표준 Class-File API를 쓴다. 원격 Docker나 운영에서 실행하지 않는다. `--repeats 1`은 빠른 탐색(해당 결과 문서를 덮어씀), 기본은 3회다. 큐는 `--pairs 2000x200,2000x100 --repeat-start 2 --repeats 3`처럼 경계 조합만 추가 반복할 수 있다(앞선 1회차 raw를 읽어 문서에 합치며, 추천은 지정 조합만 대상). 인덱스 reads의 k6 제한은 NTFS에서 JSON 출력 flush 지연 때문에 900초다. 큐 전체는 로컬 CPU와 응답에 따라 수 시간, 인덱스 전체는 약 1시간이 걸릴 수 있다. 2,000 k6 VU와 JVM 1GiB, DB/Redis를 함께 수용할 메모리가 필요하다. 호스트 자원이 부족하면 서버 한계와 부하 생성기 한계를 구분해 재측정한다.

매 회 측정 전용 DB 스키마를 재생성하고 V1/V2로 시작한다. 구매 100건 예열 후 고정 데이터를 다시 심고 Redis를 초기화한다. 초기 데이터는 공연 20개 × 좌석 15,000, FAN 10,000 + ARTIST 1, 티켓 38,000건이다. 공연 1은 구매용 AVAILABLE, 공연 2~20은 각각 2,000 SOLD + 13,000 AVAILABLE. 좌석은 A~J 구역, 구역당 50×30이다. 사용자 배치는 seed=20261007의 고정 순열, 티켓 시간은 2026-01-01부터 고정 증가다. 공연의 예매 기간은 실행 시작 UTC 기준으로 모든 조건·회차에 동일하다. 데이터 생성/DDL/큐 입장 준비는 API 측정 창에서 제외한다.

`application-phase6.yaml`은 jar 밖에서 prod 프로필에 추가한다. Hikari max/min-idle=10/10, Tomcat max/min-spare/accept-count=200/10/100은 현재 운영 프로필의 기본값을 유지한다. 이전 전략 비교의 min-spare=20/accept-count=2000은 사용하지 않는다. 운영과 다른 값은 로컬 DB/Redis 주소·인증, Redis TLS 해제, metrics 노출, WARN 로그, 로컬 heap 1GiB/CPU 8, 사용자 결정에 따른 pessimistic이다. 운영 JVM 자원은 배포 환경에서 확인한다. 큐 실험은 Worker 5초, ACTIVE TTL 5분, ADMITTED TTL 1분, 기본 폴링 간격을 유지한다. 운영 소스/프로필/기본 설정을 바꾸지 않는다.

### 큐 시나리오와 판정

기본 조합(max/admit): 250/50, 500/50, 500/100, 1000/100, 1000/200, 2000/100, 2000/200. 회차마다 실행 순서를 회전한다. 매 회 2,000명 중 1,000명은 오픈 즉시, 500명은 10초 뒤, 나머지 500명은 30초 뒤에 도착한다. k6의 예정/실제 도착 오차는 raw 요약의 `arrival_offset_ms`다.

각 사용자가 실제 등록 → status(서버 nextPollAfterMs 준수) → Worker ADMITTED → enter → 좌석 요약/구역 조회 3회(각각 생각 시간 25~30초) → 구매 1회를 수행한다. 모든 후보에 동일한 2,000명을 사용한다. 가장 작은 max=250에서도 정상 처리라면 15분 AT 유효기간 안에 소진할 수 있도록 status 대기는 730초, 전체 k6 창은 880초로 제한한다. 체류는 약 75~90초이며 `active_residence_ms`로 남긴다. 실제 Entry Token을 API로 발급받으며 고유 빈 좌석을 1매씩 구매한다. 토큰을 Redis에 직접 심거나 TTL/입장 검증을 우회하지 않는다. max는 상한이고 ACTIVE는 구매 성공 시 제거되므로 max 후보가 충분히 사용됐는지도 확인한다.

**잠정 판정(사용자 확정 대상)**: 구매 p95 ≤ 1초·p99 ≤ 2초, 모든 API 5xx=0·비409 오류율 <0.1%. transport·4xx(409 제외)·5xx가 비409 오류에 포함된다. 모든 반복의 판정을 만족한 조합 중 가장 큰 max를 추천하고, 동률이면 가장 큰 admit을 추천한다. 고유 AVAILABLE 좌석 시나리오이므로 전원 201, 누락 없는 구매와 HTTP↔DB 정합성도 전제다. 빠른 409만으로 SLO를 통과하는 것을 방지한다. ADMITTED+ACTIVE 예약 점유 표본이 max의 80% 미만이면 그 max는 부하 근거가 부족하므로 추천에서 제외한다. 80%와 생각 시간, 도착량은 이 로컬 실험의 충분한 부하 확인 기준이며 운영 서비스 기준으로 확정된 값은 아니다.

구매·좌석·status와 등록·enter의 p95/p99/응답 분포, 5xx·비409 오류율, Hikari 활성/대기 최대·커넥션 획득 평균을 자동 보고한다. Hikari/예약 점유는 1초 목표 간격의 표본이며 피크를 놓칠 수 있다. `agent/WorkerTimer.java`는 측정 JVM의 runCycle 전체(등록 공연 목록 읽기·Lua·Redis 왕복·예외 경로)를 Java 표준 Class-File API로 계측한다. 정확한 nanoTime 차이를 앱 로그에 쓰고, 측정 창의 Worker p95/max와 각 주기 원시값을 남긴다. agent jar는 raw에만 생성하며 애플리케이션 jar와 소스는 수정하지 않는다. 로그 출력 자체의 작은 오버헤드는 시간값에서 제외되지만 다음 Worker 시작에는 영향을 줄 수 있다.

결과: `results/queue_numbers.md`. 원시 출력: `raw/phase6/queue-<max>-<admit>-<회>/`, API 응답 JSON, k6 요약, Hikari·예약 점유 표본, Worker 앱 로그, 판정과 SQL audit의 result.json. 실측을 못 하면 수치는 미측정, 추천은 보류다.

### 인덱스 비교와 해석

매 회 V1/V2 베이스라인 또는 후보 **하나만** 적용한 상태로 비교한다. 후보는 ix_seat_concert_status(concert_id,status), ix_ticket_user_created(user_id,created_at), ix_concert_ticket_period(ticket_open_at,ticket_close_at)다. 후보 DDL은 전용 DB에만 적용한다. Flyway V3나 운영 인덱스를 만들지 않는다. baseline은 기존 UNIQUE/FK 지원 인덱스와 V2 멱등성 인덱스를 포함한다. 후보가 FK 지원 인덱스를 대체하더라도 다음 회차는 새 스키마로 복원한다.

실제 Repository 쿼리의 file:line은 자동 보고한다. 좌석은 AVAILABLE 전체 조회와 AVAILABLE 구역 집계, 티켓은 사용자별 created_at DESC + seat/concert EntityGraph를 동등 SQL로 EXPLAIN ANALYZE한다. 대표 공연=2, 대표 사용자=1이며 Hibernate의 필드 선택/별칭과는 차이가 있다. **현재 공연 목록은 기간 WHERE 없는 findAll이고 예매 기간 조건 쿼리가 없다.** 따라서 세 번째 후보는 기존 전체 공연 목록·구매 비용만 관찰하고 목표 조회 효과는 검증 불가로 기록한다. 가상의 기간 API/쿼리를 추가하지 않는다.

ANALYZE TABLE 후 쿼리별 예열 계획 1회 제외, EXPLAIN ANALYZE 3회를 보관한다. 보고서에는 첫 트리의 모든 접근 노드, actual first/last 시간, rows/loop·loops·곱을 적는다. 스캔 노드의 실제 접근 행과 filter/root의 결과 행을 구분한다. SHOW INDEX 원문을 모든 테이블에 남겨 InnoDB FK 자동 인덱스까지 확인한다. `mysql.innodb_index_stats`의 size 페이지 × @@innodb_page_size로 각 인덱스의 할당 공간 근사 bytes를 기록한다(행 데이터 bytes나 OS 파일 크기와 같지 않음).

API는 40 VU·45초·100ms 생각 시간을 각각 순차 창으로 수행한다: 공연2 좌석 구역 집계, AVAILABLE 좌석 전체, 사용자 티켓 내역, 전체 공연 목록. p95/p99/TPS·응답 분포를 기록한다. 이어 1,000명 실제 큐 입장 후 고유 공연1 좌석 구매를 한 번씩 동시에 시도해 INSERT ticket + UPDATE seat 트랜잭션의 201 TPS와 지연·Hikari 대기를 비교한다. 구매 201 TPS는 첫 응답~마지막 응답 + 최소 HTTP 시간의 완료 창 기준이며, 개별 INSERT/UPDATE만의 독립 벤치마크는 아니다. API 측정의 예열/think/인증/JSON 비용도 포함된다. 큐 준비에만 2000/2000·Worker/poll 100ms를 쓰며 구매 측정 창에서 제외한다.

완료 후 후보별 조회 p95와 구매 201 TPS의 3회 중앙값·baseline 대비 변화율을 자동 계산한다. 조회 p95 10% 이상 개선, 구매 TPS 저하 5% 이내인 후보를 잠정 채택 검토로 표시한다. 접근 계획·p99·인덱스 크기도 함께 사용자 검토해야 하며 자동 채택하지 않는다. 기간 쿼리가 없는 후보는 항상 보류한다. 기준과 결과는 **로컬 잠정값이며 운영 재측정 권장**이다. 조회 캐시는 별도 실험으로 남긴다.

결과: `results/index_comparison.md`. 원시 출력: `raw/phase6/index-<후보>-<회>/`, SHOW INDEX·인덱스 크기 TSV, EXPLAIN 전체 트리 3회, 읽기/쓰기 k6 출력·요약·Hikari 표본·SQL audit·result.json.

### 도구 검증과 정리

Docker 없이 문법·고정 데이터·EXPLAIN/원시 응답 파서·경계 판정·불완전 실험 추천 차단·Worker 정상/예외 계측을 확인한다. 합성값은 결과 문서에 쓰지 않는다:

```bash
python3 backend/loadtest/check_phase6.py
cd backend && ./gradlew --no-daemon test
```

일반 종료·오류·SIGINT/SIGTERM에는 측정 JVM을 종료하고 측정 프로젝트 컨테이너·볼륨을 제거한다. 서명 토큰 입력은 k6 성공/실패 후 제거하며 raw 전체는 gitignore 대상이다. 강제 SIGKILL/호스트 중단은 자동 정리가 불가능하므로 남은 해당 JVM PID를 확인하고 아래 명령을 실행한다. 개발 앱/컨테이너/Redis에는 접근하지 않는다:

```bash
bash backend/loadtest/queue/run-queue-numbers.sh --cleanup
# 또는 동일 측정 프로젝트 정리
bash backend/loadtest/index/run-index-comparison.sh --cleanup
```

Docker 소켓 권한으로 막히면 리소스 생성 전 종료 코드 1로 중단하고 결과 문서에 **실측 미실행**과 원인을 자동 기록한다. 정합성/오류율/지연을 0이나 통과로 만들지 않는다. 도구 검증 통과는 실측 통과를 뜻하지 않는다.
