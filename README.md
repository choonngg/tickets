# 🎟️ 공연 예매 프로젝트

> 예매 오픈에 몰리는 요청을 대기열로 제어하고, 동시 구매와 응답 유실 상황에서도 구매 결과의 정합성을 지키는 공연 예매 서비스

<p>
  <img src="https://img.shields.io/badge/Java-25-007396?logo=openjdk&logoColor=white" alt="Java 25">
  <img src="https://img.shields.io/badge/Spring_Boot-4.0.5-6DB33F?logo=springboot&logoColor=white" alt="Spring Boot 4.0.5">
  <img src="https://img.shields.io/badge/MySQL-8.0-4479A1?logo=mysql&logoColor=white" alt="MySQL 8.0">
  <img src="https://img.shields.io/badge/Redis-7.2-DC382D?logo=redis&logoColor=white" alt="Redis 7.2">
  <img src="https://img.shields.io/badge/React-19-61DAFB?logo=react&logoColor=black" alt="React 19">
  <img src="https://img.shields.io/badge/TypeScript-5.9-3178C6?logo=typescript&logoColor=white" alt="TypeScript 5.9">
  <img src="https://img.shields.io/badge/Docker-Compose-2496ED?logo=docker&logoColor=white" alt="Docker Compose">
  <img src="https://img.shields.io/badge/GitHub_Actions-CI%2FCD-2088FF?logo=githubactions&logoColor=white" alt="GitHub Actions">
  <img src="https://img.shields.io/badge/k6-Load_Test-7D64FF?logo=k6&logoColor=white" alt="k6">
</p>

티켓팅을 하며 접한 “이미 선택된 좌석”과 오픈 직후의 대기열이 어떻게 동작하는지 궁금해 시작한 개인 프로젝트입니다.
1차 구현 이후 설계와 코드를 다시 비교하고, **예매 정합성 → 인증 → 대기열 → 부하·장애 검증** 순서로 재구축했습니다.

| 항목 | 내용 |
| --- | --- |
| 개발 기간 | 1차 구현 2026.04~05 · 리팩토링 및 추가 구현 2026.10 |
| 참여 인원 | 1인 (설계 · 백엔드 · DB · 프론트엔드 · 테스트 · 배포 자동화) |
| 중점 | 중복 요청 제어, 다중 좌석 원자 구매, 대기열 진입 통제, 실패 후 재시도 |

### 대표 검증 결과

- **같은 좌석에 1,000명 경합** — 세 락 전략 각각 3회 측정, 모든 회차에서 성공 1건·티켓 1장
- **전략별 10회 비교 후 기본값 결정** — 지연·판매 결과·구현 복잡도를 함께 고려해 비관적 락 선택
- **장애 3종 주입** — 분산 락 lease 만료, Redis 중단, 커밋 직후 응답 유실에서 정합성과 재시도 결과 확인

측정값은 단일 로컬 환경의 결과이며, 조건과 해석 범위는 [측정과 기술 결정](#-측정과-기술-결정)에 정리했습니다.

**목차** · [프로젝트 소개](#-프로젝트-소개) · [기술 스택과 구조](#-기술-스택과-구조) · [핵심 구현](#-핵심-구현) · [측정과 기술 결정](#-측정과-기술-결정) · [저장소 구조와 실행](#-저장소-구조와-실행)

<br>

## 📌 프로젝트 소개

사용자는 공연을 조회하고 Google 계정으로 로그인한 뒤, 공연별 대기열을 거쳐 좌석을 구매합니다.
아티스트는 공연을 등록할 수 있으며, 공연 생성 시 구역·행·열 기준으로 15,000석이 자동 생성됩니다.

```text
공연 조회 → Google 로그인 → 대기열 등록 → 입장 허가 → 입장
          → 구역·좌석 선택 → 1~4매 구매 → 내 티켓 확인
```

| 구분 | 규칙 |
| --- | --- |
| 조회 | 비회원도 공연 목록과 상세를 조회할 수 있음 |
| 회원 역할 | 신규 회원은 `FAN`. 아티스트 승격 UI·API가 없어 `ARTIST`는 DB에서 지정 |
| 구매 단위 | 한 번에 같은 공연 좌석 1~4매, 공연당 1인 누적 한도 4매 |
| 구매 성공 | 입장 권한을 소진하고 자리 반환. 추가 구매는 대기열 맨 뒤에 재등록 |
| 구매 실패 | 좌석 경쟁으로 실패하면 남은 입장 시간 안에서 다른 좌석 선택 가능 |
| 결제 | **Mock 결제**. 실제 PG, 취소·환불, 좌석 임시 선점은 범위에서 제외 |

### 1차 구현 대비 변경점

1차 구현에서는 구역별 좌석 조회와 세 가지 락 전략을 구현했습니다.
이후 테스트가 통과하는 코드도 설계와 다르거나 실제 Redis·MySQL에서 검증되지 않은 부분이 있다는 것을 확인해, 기능 추가와 결함 수정을 함께 진행했습니다.

| 영역 | 1차 구현 | 현재 구현 |
| --- | --- | --- |
| 좌석 조회·생성 | 구역 요약·선택 구역 조회, 중복 집계 제거, 좌석 생성은 JPA 건별 INSERT | 조회 구조 유지, 좌석 생성은 JDBC batch 적용 |
| 구매 단위 | 좌석 1매, 1인 구매 한도 없음 | 같은 공연 1~4매 원자 구매, 공연당 누적 4매 |
| 멱등성 | Redis 조회 → 구매 → 응답 저장 | 원자적 요청 선점·본문 비교·DB 구매 결과 복구 |
| 인증 | 이메일 로그인, 토큰을 localStorage에 보관 | Google OAuth, RS256, 메모리 Access Token(AT)·HttpOnly Refresh Token(RT), Rotation·재사용 탐지 |
| 구매 진입 | 로그인 후 구매 API 호출 | 대기열 입장 권한 검증·1회용 소진 |
| 시간·스키마 | LocalDateTime, Hibernate 스키마 자동 변경 | UTC Instant·주입된 Clock, Flyway·스키마 검증 |
| 검증 | H2·인메모리 테스트 중심 | 실제 MySQL·Redis 통합 테스트, 부하 측정, 장애 주입 |

<br>

## 🧱 기술 스택과 구조

| 영역 | 기술 | 사용 목적 |
| --- | --- | --- |
| Backend | Java 25, Spring Boot 4, Spring Security, Spring Data JPA | API, 인증·권한, 구매 트랜잭션 |
| Database | MySQL 8, Flyway | 좌석·티켓·세션 저장, 무결성 제약, 스키마 이력 |
| Redis | Redis 7, Lua, Redisson | 대기열 상태 전이, 멱등성 선점, 분산 락 비교 |
| 인증 | Google OAuth, RS256 JWT, JWKS | 로그인, 공개키 기반 토큰 검증, 입장 권한 분리 |
| Frontend | React 19, TypeScript, Vite | 대기열·좌석 선택·구매 화면 |
| 검증 | JUnit, Testcontainers, k6, Vitest, Playwright | 경계값·동시성·부하·장애·화면 흐름 검증 |
| 배포 | Docker Compose, Nginx, GitHub Actions, GHCR | 이미지 빌드, 스모크 검사, 롤백 자동화 |

<img width="1488" height="554" alt="Image" src="https://github.com/user-attachments/assets/d468d11e-4886-4678-a116-fbc49d5bcfc3" />

- 백엔드는 **하나의 Spring Boot 애플리케이션**입니다.
- **MySQL**에는 확정된 구매 결과를, **Redis**에는 대기열과 요청 처리 상태를 저장합니다.
- 로그인 토큰과 Entry Token은 **서로 다른 RSA 키쌍**으로 서명합니다.

<br>

## 💡 핵심 구현

### 1. 대기열을 통과한 사용자의 구매만 허용

**S · 상황**

1차 구현에서는 로그인만 하면 바로 구매 API를 호출할 수 있어, 예매 오픈 직후 몰리는 요청을 제어할 수단이 없었습니다.

**T · 목표**

공연별로 동시에 구매 화면에 머무는 인원을 제한하고, 대기열을 거쳐 입장 허가를 받은 사용자만 정해진 시간 안에 구매하게 한다.

**A · 행동**

대기열의 순번과 만료 시각을 Redis Sorted Set에 저장하고, 등록·입장·만료 정리를 Lua로 원자 처리합니다. 상태 조회는 사용자·공연을 매번 DB에서 확인하지 않고 Redis 한 번의 왕복으로 처리합니다.

```text
WAITING ── Worker의 입장 허가 ──▶ ADMITTED ── 사용자의 입장 요청 ──▶ ACTIVE
  순번별 폴링                       60초 안에 입장                   최대 5분
                                                                     │
                                              구매 성공: 권한 소진·자리 반환
                                              구매 실패: 남은 시간 내 재선택
```

| 항목 | 현재 설정·규칙 |
| --- | --- |
| 공연별 수용량 | `ADMITTED + ACTIVE ≤ 1,000` (전체 대기자 수의 상한은 아님) |
| 입장 Worker | 실행 완료 후 5초 간격, 빈자리 범위에서 주기당 최대 100명 |
| WAITING 폴링 | 순번 1\~100: 3초 / 101\~1,000: 10초 / 1,001 이상: 20초, 응답에 ±20% jitter 적용 |
| Entry Token | 사용자·공연·입장 회차·만료를 검증, Redis에서 구매 권한 선점 |
| 구매 성공 | Entry Token 1회용 소진, ACTIVE 자리 반환 |
| 유효 ACTIVE의 대기 취소 | 거부 (권한이 남아 있는데 자리를 다시 배정하는 문제 방지) |

프론트엔드는 서버가 알려준 폴링 간격을 따르고 요청을 중첩하지 않으며, 남은 입장 시간은 응답의 서버 시각으로 보정합니다. Access Token 문제는 401, Entry Token 문제는 403으로 구분해 입장 권한 만료를 로그인 갱신으로 처리하지 않도록 했습니다.

**R · 결과**

- 2,000명이 시간차로 도착하는 시나리오에서 **2,000/2,000 구매 성공**, 구매 API p95 44.58ms, 입장 API p95 19.18ms를 기록했습니다.
- 등록 API p95 1.3~2.0초는 남은 병목입니다.
- 설정값 결정 과정은 [대기열 측정](#대기열-구매-지연과-입장-속도를-함께-확인)에 정리했습니다.

> [!IMPORTANT]
> 현재 Worker는 **단일 백엔드 인스턴스**를 전제로 합니다. 여러 인스턴스에서 Worker가 실행되면 주기당 입장 속도가 달라지므로, 다중 인스턴스 운영은 별도 검증이 필요합니다.

---

### 2. 여러 좌석 구매의 원자성과 누적 구매 한도 보장

**S · 상황**

- 1차 구현은 좌석 1매 구매만 지원했고, 1인 구매 한도가 없었습니다.
- JPA `IDENTITY` 키 생성에서는 티켓 INSERT가 좌석 UPDATE보다 먼저 실행될 수 있었습니다. 이 순서에서는 낙관적 충돌보다 티켓 유니크 위반이 먼저 발생해, 경쟁에서 진 요청도 결제까지 진행했습니다.

**T · 목표**

같은 공연 좌석 1~4매를 **전부 성공하거나 전부 실패**하도록 처리하고, 동시 요청에서도 공연당 누적 4매 한도를 지킨다.

**A · 행동**

`POST /api/tickets` 한 번에 같은 공연의 좌석 1~4매를 한 트랜잭션으로 처리합니다. 한 좌석이라도 판매됐거나 Mock 결제가 실패하면 좌석 상태 변경과 티켓 생성을 모두 롤백합니다.

```http
POST /api/tickets
Authorization: Bearer <AT>
X-Entry-Token: <Entry Token>
Idempotency-Key: <UUID>

{ "seatIds": [123, 124] }
```

기본 전략(비관적 락)의 DB 트랜잭션은 다음 순서로 진행됩니다. 좌석 UPDATE를 **명시적으로 flush한 뒤** 결제·티켓 생성을 수행하도록 바꿨습니다.

```text
사용자 행 잠금 → 좌석 ID 오름차순 조회·잠금 → 누적 4매·판매 기간 확인
              → 모든 좌석 SOLD 변경·flush → 합산 Mock 결제 → 티켓 생성 → 커밋
```

| 보호 장치 | 지키는 규칙 |
| --- | --- |
| `users` 행 잠금 | 같은 사용자의 동시 요청이 누적 4매 한도를 넘지 않도록 직렬화 |
| 좌석 ID 오름차순 접근 | 겹치는 좌석 묶음도 같은 잠금 순서로 처리 |
| 비관적·낙관적·분산 락 전략 | 서로 다른 사용자의 좌석 경쟁 제어, 설정으로 전략 교체 |
| 좌석 `@Version` | 버전이 바뀐 좌석의 갱신 거부 |
| `uq_ticket_seat` | 좌석당 티켓 1장이라는 DB 무결성 보장 (기존 제약 유지) |
| `purchase_key`·`admission_id` 기록 | 같은 구매 응답 복구, 이미 사용한 입장 회차의 신규 구매 거부 |

**R · 결과**

- 경쟁에서 진 요청은 결제 전에 실패합니다.
- **1,000명 → 같은 1석** 시나리오에서 세 전략 모두, 모든 회차에서 성공 1건·티켓 1장이었습니다.
- 모든 측정에서 좌석 중복, 누적 4매 초과, 부분 구매 위반은 0건이었습니다.

---

### 3. 같은 구매 요청은 한 번만 처리하고, 응답을 잃어도 결과를 복구

**S · 상황**

기존 멱등성 처리는 Redis에서 키를 조회 → 구매 → 응답 저장 순서였습니다.

- 조회와 저장 사이에 들어온 같은 키의 요청이 함께 처리될 수 있었습니다.
- 같은 키로 다른 좌석을 요청해도 이전 응답이 반환됐습니다.
- 구매가 DB에 커밋된 뒤 Redis 저장이 실패하면, 성공한 구매가 500 응답으로 바뀌었습니다.

**T · 목표**

같은 키의 요청은 정확히 한 번만 처리하고, 이미 커밋된 구매는 어떤 후속 오류에도 성공 응답으로 돌려준다.

**A · 행동**

1. Redis Lua로 `IN_PROGRESS`를 원자적으로 선점합니다. 완료·해제는 선점한 요청의 owner가 일치할 때만 수행합니다.
2. 좌석 ID 목록을 정렬해 SHA-256 해시로 비교합니다.
   - 같은 키·다른 좌석 조합 → `422 IDEMPOTENCY_CONFLICT`
   - 처리 중인 요청 → `409 REQUEST_IN_PROGRESS`
3. **구매 트랜잭션의 커밋과 후속 Redis 처리를 분리**합니다. 커밋 후 응답 저장·입장 권한 정리·분산 락 해제 오류가 확정된 구매를 실패로 바꾸지 않습니다.
4. Redis 기록이 없어지면 같은 사용자·구매 키의 티켓 묶음을 DB에서 조회해 기존 성공 응답을 재구성합니다.
5. 성공 응답 재전송과 DB 복구는 Entry Token 재검증보다 먼저 처리합니다. 구매 성공 시 토큰을 이미 소진하므로, 재시도에 새 입장 권한을 요구하면 응답 복구가 막히기 때문입니다. **새로운 구매**는 반드시 입장 권한 검증을 거칩니다.
6. 프론트엔드도 네트워크 오류·5xx 이후 같은 구매 시도에는 같은 멱등성 키를 유지합니다.

```text
요청 선점 → DB 구매 트랜잭션 → 커밋 → 입장 권한 소진 · 응답 저장
                                       └ 이 단계가 실패해도 구매 성공 유지

재시도 시 Redis 기록이 없으면 → 같은 구매 묶음을 DB에서 조회해 응답 복구
```

**R · 결과**

- 커밋 직후 응답 유실을 주입했을 때, 같은 키 재전송으로 동일한 201 응답을 받았습니다.
- Redis 멱등성 기록을 삭제하거나 Redis를 재시작한 뒤에도 DB 구매 묶음으로 기존 성공 결과를 복구했습니다.
- 자세한 결과는 [장애 주입](#장애-주입-커밋된-구매-결과가-실패로-바뀌는지-확인)에 있습니다.

> [!NOTE]
> 응답 저장 실패 후 `IN_PROGRESS`가 남아 있으면 최대 30초 동안 409가 반환될 수 있습니다.

---

### 4. 로그인 토큰 보관과 Refresh Token 재사용 방지

**S · 상황**

- refresh 경로가 Access Token 인증을 요구해, AT(30분)가 만료되면 refresh에 도달하지 못하고 다시 로그인해야 했습니다. 프론트엔드에는 refresh 호출 코드도 없었습니다.
- 로그아웃 요청에 인증 헤더가 없어 서버 처리가 실패했지만 UI는 성공으로 표시해, 서버에 RT가 남았습니다.
- AT·RT를 모두 localStorage에 보관해 XSS 시 RT 원문까지 탈취될 수 있었습니다. 서버도 RT 원문을 저장했고, 회전과 재사용 탐지가 없었습니다.
- RT 저장 키가 사용자당 하나라 다른 기기에서 로그인하면 기존 기기의 RT가 덮어써졌습니다.

**T · 목표**

토큰이 탈취돼도 피해를 세션 단위로 막고, AT가 만료돼도 다시 로그인하지 않고 계속 쓸 수 있게 한다.

**A · 행동**

| 영역 | 변경 |
| --- | --- |
| 로그인 | 이메일 로그인 → Google OAuth, RS256 JWT·JWKS |
| 토큰 보관 | AT·Entry Token은 메모리, 난수 RT는 HttpOnly Cookie. DB에는 RT 원문 대신 SHA-256 해시 저장 |
| 세션 | 로그인마다 Session을 만들고 RT를 Session 단위로 관리. refresh·로그아웃은 RT Cookie로 인증 |
| RT Rotation | Session → RT 순서로 잠금, 기존 RT 소비 후 새 RT 생성 |
| RT 재사용 탐지 | 소비 후 15초 이내 재제출은 재시도로 유예, 이후는 Session revoke. revoke를 커밋한 뒤 401 응답 |
| DB 제약 | 생성 컬럼과 UNIQUE로 세션당 미사용 RT 최대 1개 보장 |

**R · 결과**

- 동시 refresh 10건에서 성공 1건, 나머지 9건은 재시도 응답이었고 미사용 RT 1개 유지·UNIQUE 위반 0건이었습니다. (실제 MySQL 통합 테스트)
- 로그아웃·탈퇴 이후의 refresh는 거부됩니다.

> [!NOTE]
> 이미 발급된 AT를 즉시 폐기하는 블랙리스트는 없으며, AT 수명은 15분입니다.

<br>

## 📊 측정과 기술 결정

> [!NOTE]
> **공통 측정 환경** — WSL2 8코어·16GB, 단일 JVM·heap 1GiB, Hikari 10, Tomcat 최대 200 threads, Docker MySQL 8.0.36·Redis 7.2.4.
> k6·앱·DB를 같은 머신에서 실행했으며 운영 환경 부하 측정은 수행하지 않았습니다.

### 락 전략: 첫 측정 결과를 반복 측정으로 재확인

**S · 상황**

비관적·낙관적·분산 락을 설정으로 교체할 수 있게 구현했습니다. 처음 전략별 3회 측정에서는 분산 락의 지연이 가장 낮았지만, 회차별 변동이 있어 3회만으로 결론을 내기 어려웠습니다.

**T · 목표**

기본 전략을 근거 있게 정한다. 분산 락은 **p95를 20% 이상 줄일 때만** 채택한다는 기준을 사전에 정했습니다.

**A · 행동**

- 핫 구역 시나리오를 **전략별 10회**로 늘리고, 실행 순서는 회차마다 바꿨습니다.
- 조건: 서로 다른 사용자 1,000명, HOT 구역 300석, 고정 시드로 1~4매 요청, 사용자당 첫 시도 1회, 재시도 없음.
- p95는 성공·실패를 포함한 구매 HTTP 응답이며 대기열 준비 시간은 제외했습니다.
- 별도로 **1,000명 → 같은 1석** 시나리오를 세 전략 각각 3회 실행했습니다. (전략 측정 총 39회)

**R · 결과**

| 전략 | 판매 좌석 수 (회차 평균) | 구매 p95 (회차 평균) |
| --- | ---: | ---: |
| **비관적 락 (기본값)** | 262.4석 | 6.93s |
| 낙관적 락 | 260.5석 | 6.39s |
| 분산 락 | 245.4석 | 5.81s |

분산 락은 p95 20% 감소 기준을 충족하지 못했습니다. 단일 인스턴스에서 Redis 락·lease 관리를 추가하지 않아도 되는 단순성을 함께 고려해 **비관적 락을 기본값**으로 선택했습니다. 세 전략은 `TICKET_PURCHASE_STRATEGY`로 교체할 수 있습니다.

- 비관적·낙관적 락의 판매 좌석 차이는 통계적으로 구분되지 않았습니다.
- 분산 락의 낮은 판매 수는 락 획득 실패 뒤 재시도하지 않는 이번 조건의 영향일 수 있어, 실제 최종 판매량으로 일반화하지 않았습니다.
- 같은 1석 시나리오는 모든 회차에서 성공 1건·티켓 1장이었습니다.

📄 [락 전략 비교 결과](backend/loadtest/results/strategy_comparison.md)

---

### 대기열: 구매 지연과 입장 속도를 함께 확인

**S · 상황**

공연별 수용량과 주기당 입장 인원은 구매 API 부하와 입장 대기 시간을 함께 좌우합니다.

**T · 목표**

목표 규모를 처리하면서 구매·입장·조회 지연을 낮게 유지하는 설정값을 고른다.

**A · 행동**

총 2,000명이 시간차로 도착해 다음 흐름을 수행하는 시나리오로 측정했습니다.

```text
등록 → 상태 조회 → 입장 → 좌석 조회 → 생각 시간 75~90초 → 고유 좌석 1매 구매
```

수용량·주기당 입장 인원 7조합을 탐색하고, 경계 2조합을 추가 반복해 총 11회 실행했습니다.

**R · 결과**

| 유지한 설정 | 구매 API p95 | 입장 API p95 | 구역 좌석 조회 p95 | 구매 결과 |
| --- | ---: | ---: | ---: | ---: |
| 수용량 1,000 / 주기당 100명 | 44.58ms | 19.18ms | 30.44ms | 2,000/2,000 성공 |

주기당 200명 입장에서는 입장 p95가 0.8~1.2초로 증가해 **1,000/100을 유지**했습니다. 이 설정 자체는 1회 측정이므로 운영 환경에서 반복 측정이 필요합니다.

- 위 값은 대기 시간을 포함한 전체 예매 소요 시간이 아닙니다.
- 락 경쟁 실험과 인원·도착 패턴·좌석 경쟁 조건이 달라, 두 결과를 전후 성능 개선율로 비교하지 않았습니다.
- 등록 API p95 1.3~2.0초는 남은 병목입니다.

📄 [대기열 수치 측정 결과](backend/loadtest/results/queue_numbers.md)

---

### 인덱스: 효과가 작으면 추가하지 않는 선택

**S · 상황**

좌석 상태 집계, 내 티켓 조회, 공연 판매 기간 조회에 쓸 수 있는 인덱스 후보 3종이 있었습니다.

**T · 목표**

인덱스는 사전에 정한 기준(조회 10% 이상 개선)을 넘을 때만 추가하고, 저장·쓰기 비용도 함께 따진다.

**A · 행동**

20개 공연·30만 좌석·기존 티켓 38,000건에서 베이스라인과 후보 3종을 각각 3회 비교했습니다. `EXPLAIN ANALYZE`, API 지연, 구매 처리량을 함께 확인했습니다.

**R · 결과**

| 후보 | 관찰 | 결정 |
| --- | --- | --- |
| 좌석 `(concert_id, status)` | 구역 집계 p95 약 7%·AVAILABLE 조회 약 4.2% 감소, 구매 TPS 약 3.3% 감소 | 보류 (기준 10% 미달) |
| 티켓 `(user_id, created_at)` | 내 티켓 조회 개선 효과 없음 | 보류 |
| 공연 판매 기간 인덱스 | 현재 API에 해당 조건의 조회 쿼리가 없음 | 보류 |

세 후보 모두 채택하지 않았고, 이를 위한 Flyway V3도 추가하지 않았습니다.

📄 [인덱스 비교 결과](backend/loadtest/results/index_comparison.md)

---

### 장애 주입: 커밋된 구매 결과가 실패로 바뀌는지 확인

핵심 구현 2·3의 결과를 장애 상황에서 확인한 검증입니다.

| 주입한 장애 | 확인한 결과 |
| --- | --- |
| 분산 락 lease 만료 | 경쟁 요청 중 성공 1건·티켓 1장 유지, DB 제약 위반은 409 응답 |
| Redis 중단·재시작 | 중단 중 신규 구매는 500, 재시작 후 같은 키로 기존 성공 결과 복구 |
| 커밋 직후 응답 유실 | 같은 키 재전송으로 동일 201 응답, Redis 멱등성 기록 삭제 후에도 DB 구매 묶음으로 복구 |

측정마다 좌석 중복, SOLD·티켓 상태 불일치, 누적 4매 초과, 부분 구매를 검사했으며 **확인된 위반은 0건**입니다.

📄 [장애 주입 결과](backend/loadtest/results/fault_injection.md) · [측정 도구와 재현 방법](backend/loadtest/README.md)

<br>

## 📁 저장소 구조와 실행

```text
backend/
├─ src/main/java/.../domain/   auth · concert · seat · ticket · queue · user · payment
├─ src/main/java/.../infra/    멱등성·분산 락(Redis·Redisson) · 대기열 · Google · Mock 결제
├─ src/main/resources/         설정 · Flyway · 대기열 Lua
├─ src/test/                   단위·API 테스트
├─ src/integrationTest/        실제 MySQL·Redis 통합 테스트
└─ loadtest/                   k6·장애 주입 도구·측정 결과
frontend/                      React 화면 · Vitest · Playwright
.github/                       CI·배포 워크플로 · 배포 스크립트
nginx/                         SPA·API 프록시 설정
Dockerfile.*                   backend·nginx 이미지
```

### 실행 방법

MySQL·Redis, Google OAuth 클라이언트, RSA 키쌍 2개(로그인용·Entry Token용)가 필요합니다. 환경변수는 [.env.example](.env.example)을 참고하세요.

```bash
# 로컬 Redis
docker compose -f docker-compose.local.yml up -d

# backend
cd backend && ./gradlew bootRun

# frontend
cd frontend && npm install && npm run dev
```

> [!TIP]
> `./gradlew bootRun`은 `.env` 파일을 자동으로 읽지 않으므로 환경변수를 셸에 설정한 뒤 실행합니다. 로컬 http 환경에서는 `AUTH_COOKIE_SECURE=false`로 두어야 로그인 쿠키가 저장됩니다.

<details>
<summary>테스트 실행</summary>

```bash
# backend 단위·API 테스트
cd backend && ./gradlew test

# backend 통합 테스트 (Testcontainers 사용, Docker 필요)
cd backend && ./gradlew integrationTest

# frontend 테스트
cd frontend && npm test
```

</details>

<details>
<summary>API 요약</summary>

| 영역 | Method · Path | 인증 | 설명 |
| --- | --- | --- | --- |
| 인증 | `GET /api/auth/google/login` · `GET /api/auth/google/callback` | 없음 | Google 로그인 시작·콜백, RT Cookie 발급 |
| 인증 | `POST /api/auth/refresh` · `POST /api/auth/logout` | RT Cookie | AT 재발급·RT Rotation, 세션 종료 |
| 인증 | `GET /.well-known/jwks.json` | 없음 | 로그인 토큰 공개키 |
| 회원 | `GET /api/users/me` · `DELETE /api/users/me` | AT | 내 정보, 회원 탈퇴 |
| 공연 | `GET /api/concerts` · `GET /api/concerts/{concertId}` | 없음 | 공연 목록·상세 |
| 공연 | `POST /api/concerts` | AT(ARTIST) | 공연 등록, 좌석 15,000석 생성 |
| 좌석 | `GET /api/concerts/{concertId}/seats/availability` | 없음 | 구역별 잔여 수 요약 |
| 좌석 | `GET /api/concerts/{concertId}/seats/availability/sections/{section}` | 없음 | 선택 구역 좌석 목록 |
| 대기열 | `POST /api/queue/{concertId}` · `GET /api/queue/{concertId}/status` | AT | 대기열 등록, 순번·폴링 간격 조회 |
| 대기열 | `POST /api/queue/{concertId}/enter` · `DELETE /api/queue/{concertId}` | AT | 입장(Entry Token 발급), 대기 취소 |
| 대기열 | `GET /api/queue/.well-known/jwks.json` | 없음 | Entry Token 공개키 |
| 티켓 | `POST /api/tickets` | AT + `X-Entry-Token` + `Idempotency-Key` | 좌석 1~4매 구매 |
| 티켓 | `GET /api/tickets/me` | AT | 내 티켓 조회 |

</details>

### 관련 문서

- [백엔드 도메인 코드](backend/src/main/java/com/project/ticket/domain)
- [프론트 API 클라이언트](frontend/src/api.ts)
- [운영 Compose](docker-compose.prod.yml)
- [측정 결과 모음](backend/loadtest/results)
