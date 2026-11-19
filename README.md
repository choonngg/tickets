# 🎟️ 공연 예매 프로젝트

> 예매 오픈 트래픽 제어와 좌석 중복 판매 방지를 위한 공연 예매 서비스

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

대기열에서 순서대로 입장한 사용자가 구역별로 좌석을 골라 공연별 1인 4매까지 구매하는 공연 예매 서비스입니다.
티켓팅을 하며 접한 "이미 선택된 좌석"과 오픈 직후의 대기열이 어떻게 동작하는지 궁금해 시작한 개인 프로젝트입니다.

| 항목 | 내용 |
| --- | --- |
| 개발 기간 | 2026.04 ~ 2026.05, 2026.10 |
| 참여 인원 | 1인 (설계, 백엔드, DB, 프론트엔드, 테스트, 배포 자동화) |
| 기술 스택 | Java 25, Spring Boot 4, MySQL, JPA, Redis, k6, Docker, GitHub Actions, AWS |

### 대표 결과

| 영역 | 해결 | 결과 |
| --- | --- | --- |
| 예매 오픈 트래픽 | Redis 대기열과 1회용 입장 토큰으로 구매 진입 통제 | 2,000명 유입 테스트 5xx 0건, 입장 토큰 없는 새 구매는 403 |
| 같은 좌석 동시 구매 | 낙관적 락, 비관적 락, Redisson 분산 락을 로컬 경합 테스트로 비교 | 1,000명이 같은 좌석을 동시에 구매해도 세 전략 모두 티켓 1장만 발급, 300석 경합 비교 뒤 구조가 단순한 비관적 락 선택 |
| 좌석 조회 | 구역 요약 조회와 선택 구역 좌석 조회로 API 응답 범위를 나눔 | p95 3.74s → 311ms/17ms, DB 집계 조회 2회 → 1회 |

측정값은 단일 로컬 환경의 결과이며, 조건과 해석 범위는 각 절과 [측정과 기술 결정](#-측정과-기술-결정)에 적었습니다.

**목차** : [프로젝트 소개](#-프로젝트-소개) / [기술 스택과 구조](#-기술-스택과-구조) / [문제 해결](#-문제-해결) / [핵심 구현](#-핵심-구현) / [측정과 기술 결정](#-측정과-기술-결정) / [저장소 구조와 실행](#-저장소-구조와-실행)

<br>

## 📌 프로젝트 소개

사용자는 공연을 조회하고 Google 계정으로 로그인한 뒤, 공연별 대기열을 거쳐 좌석을 구매합니다.
아티스트는 공연을 등록할 수 있고, 공연을 만들면 구역, 행, 열 기준으로 15,000석이 자동 생성됩니다. 좌석은 1,000건 단위 JDBC batch로 저장하고, 중간 배치가 실패하면 공연과 좌석을 모두 롤백합니다.

```text
공연 조회 → Google 로그인 → 대기열 등록 → 입장 허가 → 입장
          → 구역 요약 조회 → 구역 선택, 좌석 선택 → 1~4매 구매 → 내 티켓 확인
```

| 구분 | 규칙 |
| --- | --- |
| 조회 | 비회원도 공연 목록, 공연 상세, 좌석 현황을 조회할 수 있음 |
| 회원 역할 | 신규 회원은 `FAN`. 아티스트 승격 UI와 API가 없어 `ARTIST`는 DB에서 지정 |
| 구매 단위 | 한 번에 같은 공연 좌석 1~4매, 공연당 1인 누적 한도 4매 |
| 구매 성공 | 입장 토큰을 소진하고 자리를 반환. 다시 사려면 대기열에 다시 등록 |
| 구매 실패 | 좌석 경쟁으로 실패하면 남은 구매 가능 시간 안에서 다른 좌석 선택 가능 |
| 결제 | **Mock 결제**. 실제 PG, 취소와 환불, 좌석 임시 선점은 범위에서 제외 |

<br>

## 🧱 기술 스택과 구조

| 영역 | 기술 |
| --- | --- |
| Backend | Java 25, Spring Boot 4.0.5, Spring Security, Spring Data JPA |
| Data | MySQL 8.0, Redis 7.2 |
| Frontend | React 19, TypeScript |
| 검증 | Testcontainers, k6 |
| 배포 | Docker, GitHub Actions, GHCR, AWS |

<img width="2745" height="1934" alt="Image" src="https://github.com/user-attachments/assets/69619cb4-a036-4595-9fca-ee1f03000fd5" />

- 백엔드는 Spring Boot 애플리케이션 한 개로 동작합니다.
- **MySQL**에는 확정된 구매 결과를, **Redis**에는 대기열과 요청 처리 상태를 저장합니다.
- 로그인 토큰과 입장 토큰은 **서로 다른 RSA 키쌍**으로 서명합니다.
- 테스트는 단위, API 테스트 222개와 실제 MySQL, Redis를 띄우는 통합 테스트 75개(동시 구매, 멱등성, 응답 복구, Refresh Token Rotation 등)입니다.
- GitHub Actions와 GHCR로 Nginx와 Spring Boot Docker 이미지를 빌드해 올리고, EC2에서 Docker Compose로 배포합니다. 2026-05 버전은 AWS(ALB, EC2, RDS MySQL, ElastiCache Valkey)에 배포했고, 대기열과 구매, 인증을 바꾼 현재 버전은 다시 배포하지 않았습니다. 배포 워크플로는 수동 실행으로 두었습니다.

<br>

## 🧩 문제 해결

### 1. 대기열로 구매 진입 통제

> 예매 오픈 직후 로그인한 사용자라면 누구나 구매 API를 바로 호출할 수 있어 몰릴 요청을 제어할 수단이 없었고 Redis 대기열과 1회용 입장 토큰으로 구매 진입을 통제해 2,000명 유입 테스트를 5xx 없이 통과하고 입장 토큰 없는 새 구매는 403으로 차단

**문제 원인**

- 로그인만 하면 구매 API를 바로 호출할 수 있어 예매 오픈 직후 요청이 들어온 순서와 상관없이 구매 트랜잭션에 들어감
- 한 번에 들어올 수 있는 인원에도 제한이 없어 오픈 직후 몰린 요청이 모두 동시에 구매로 향함
- 대기 화면만 두면 API를 직접 호출해 우회할 수 있어 대기열을 통과했는지 서버가 검증해야 함

**해결 과정**

- 공연별 대기열을 Redis Sorted Set으로 만들고 등록 순번(INCR)을 점수로 써서 먼저 온 순서를 고정했으며 대기, 입장 허가(60초), 구매 가능(5분) 세 상태로 나눠 시간이 지나면 정리
- 입장 Worker가 5초마다 Lua 스크립트로 만료 정리, 빈자리 계산, 입장 허가를 한 번에 처리해 동시 수용 1,000명 안에서 주기당 최대 100명만 입장
- 입장할 때 입장 토큰을 발급하고 새 구매는 토큰을 검증한 요청만 진행
- 실제 이용 흐름을 직접 써 보다가 다시 구매할 때 이전 구매 가능 시간이 그대로 이어지는 문제를 발견해 구매에 성공하면 입장 토큰을 소진하고 다시 사려면 대기열에 다시 서도록 바꿈

```text
대기 ── Worker의 입장 허가 ──▶ 입장 허가 ── 사용자의 입장 요청 ──▶ 구매 가능
순번별 폴링                    60초 안에 입장                     최대 5분
                                                                   │
                                          구매 성공: 입장 토큰 소진, 자리 반환
                                          구매 실패: 남은 시간 안에서 다시 선택
```

| 항목 | 설정과 규칙 |
| --- | --- |
| 공연별 수용량 | 입장 허가 + 구매 가능 ≤ 1,000 (전체 대기자 수의 상한은 아님) |
| 입장 Worker | 앞 실행이 끝나고 5초 뒤 다시 실행, 빈자리 안에서 주기당 최대 100명 |
| 대기 상태 폴링 | 순번 1\~100: 3초, 101\~1,000: 10초, 1,001 이상: 20초, 응답에 ±20% jitter |
| 입장 토큰 | 사용자, 공연, 입장 회차, 만료를 검증하고 Redis에서 구매 권한을 선점 |
| 구매 가능 상태의 대기 취소 | 거부 (권한이 남아 있는데 자리를 다시 배정하는 문제 방지) |

프론트엔드는 서버가 알려 준 폴링 간격을 따르고 요청을 겹쳐 보내지 않으며, 남은 입장 시간은 응답의 서버 시각으로 보정합니다. Access Token 문제는 401, 입장 토큰 문제는 403으로 나눠 입장 권한 만료를 로그인 갱신으로 처리하지 않게 했습니다.

**결과**

- 2,000명 시간차 유입 (1,000명→500명→500명 순으로 대기열에 등록, 서로 다른 빈 좌석 1매씩)
  - 전원 구매 성공, 5xx 0건
- 주기당 입장 인원 비교 (동시 수용 1,000명, 각 1회)
  - 입장 API p95 100명 19.18ms, 200명 883.72ms
  - 좌석 조회 p95 100명 30.44ms, 200명 109.79ms
  - 200명에서 입장과 좌석 조회가 함께 느려져 처음 정한 100명 유지
- 우회 차단
  - 로그인 토큰만 가진 새 구매, 이미 쓴 입장 토큰으로 시도한 새 구매 모두 403
- 남은 과제: 오픈 직후 대기열 등록 API의 p95가 1.3~2.0초로, 등록 시 DB 확인에서 커넥션 대기가 쌓입니다.

> [!IMPORTANT]
> 현재 Worker는 **단일 백엔드 인스턴스**를 전제로 합니다. 여러 인스턴스에서 Worker가 돌면 주기당 입장 속도가 달라지므로, 다중 인스턴스 운영은 따로 검증해야 합니다.

📄 [대기열 수치 측정 결과](backend/loadtest/results/queue_numbers.md)

---

### 2. 락 전략 비교와 기본 전략 선택

> 같은 좌석 동시 구매를 제어할 락 전략을 정하기 위해 로컬 경합 테스트로 낙관적 락, 비관적 락, Redisson 분산 락을 비교해 1,000명이 같은 좌석을 동시에 구매해도 세 전략 모두 티켓이 1장만 발급되는 것을 확인한 뒤, 1,000명이 300석을 1~4매씩 동시에 구매하는 경합에서 분산 락의 응답 시간 이점이 크지 않아 구조가 단순한 비관적 락을 기본 전략으로 선택

**문제 원인**

- 같은 좌석에 구매가 동시에 몰리면 티켓의 좌석 UNIQUE 제약이 마지막에 중복을 막더라도, 그 전에 좌석 상태를 바꾸는 단계에서 경합을 제어할 방법이 필요
- 제어 전략에는 버전 검사인 낙관적 락, DB 행 잠금인 비관적 락, Redis 잠금인 분산 락이 있고 세 전략은 기다리는 방식과 추가로 필요한 구성 요소가 서로 다름
- 단일 인스턴스로 운영하는 상황에서 세 전략 중 무엇을 기본으로 둘지 정할 근거가 없었음

**해결 과정**

- 세 전략을 설정으로 바꿀 수 있게 만들고 사용자 행 잠금과 좌석 ID 오름차순 처리는 공통 구매 흐름에 둔 채 좌석을 제어하는 방식만 다르게 둠
- 분산 락은 다른 사람이 구매 중인 좌석을 기다리게 하기보다 바로 알려 다른 좌석을 고르게 하려고 잠금을 잡지 못하면 바로 실패하도록 둠
- 낙관적 락은 같은 좌석에 요청이 몰리면 충돌로 실패하는 요청이 많고 다시 시도해도 앞선 요청이 이미 좌석을 팔아 소용이 없을 것으로 예상해 비관적 락을 비교 기준으로 둠
- 측정 전에, 정합성을 확인한 뒤 응답 시간을 비교해 분산 락의 이점이 크지 않으면 Redis 잠금이 필요 없는 비관적 락을 기본으로 두기로 정함
- 같은 좌석 1석에 1,000명이 동시에 구매하는 경우와 300석 구역에 1,000명이 1~4매씩 동시에 구매하는 경우로 나눠 측정했고 고정 시드로 사용자마다 한 번만 시도하게 하고 재시도는 넣지 않음

**결과**

| 항목 | 낙관적 락 | 비관적 락 (기본값) | 분산 락 |
| --- | ---: | ---: | ---: |
| 같은 좌석 경합 (1석에 1,000명, 전략별 3회) | 티켓 1장만 발급 | 티켓 1장만 발급 | 티켓 1장만 발급 |
| 300석 경합 구매 p95 (실패 응답 포함, 전략별 10회 평균) | 6.39s | 6.93s | 5.81s |
| 구매에 성공한 사용자 (300석 경합, 전략별 10회 평균) | 159.5명 | 162.5명 | 153.5명 |
| 정합성 (두 경합 시나리오의 좌석 중복과 부분 구매) | 0건 | 0건 | 0건 |

- 분산 락이 비관적 락보다 구매 p95가 16.1% 낮음
- 분산 락은 잠금을 못 잡으면 바로 실패하고 재시도가 없어, 빨리 끝난 실패 응답이 p95를 낮추고 구매에 성공한 사용자 수를 줄였을 수 있음
- 낙관적 락은 예상과 달리 비관적 락과 응답 시간, 구매에 성공한 사용자 수 모두 뚜렷한 차이가 없었음
- 결정: 낙관적 락은 비관적 락과 뚜렷한 차이가 없었고 분산 락은 응답 시간 이점이 크지 않아 구조가 단순한 비관적 락을 기본 전략으로 선택
- 세 전략은 `TICKET_PURCHASE_STRATEGY`로 바꿀 수 있습니다. 단일 인스턴스에서 잰 결과라, 여러 인스턴스에서 분산 락이 주는 이점은 확인하지 않았습니다.

📄 [락 전략 비교 결과](backend/loadtest/results/strategy_comparison.md)

---

### 3. 좌석 조회 응답 범위 나누기

> 공연 좌석 조회에서 불필요한 전체 좌석 payload가 반복 전송되어 지연이 발생했고 구역별 잔여 좌석 요약과 선택 구역 좌석 조회로 API 응답 범위를 나눠 p95 3.74s → 311ms/17ms로 개선하고 요약은 구역별 집계를 합산해 DB 집계 조회를 2회 → 1회로 축소

**문제 원인**

- 공연을 고른 직후 아직 구역을 고르지 않은 단계에도 예매 가능 좌석 전체 목록을 응답해, 측정한 15,000석 공연에서는 응답마다 예매 가능 좌석 14,951석이 담김
- 이 단계에서 필요한 정보는 전체 잔여 좌석 수와 구역별 잔여 좌석 수뿐인데, 큰 JSON 응답이 반복 전송되며 전송량과 파싱 비용이 커짐
- 100명이 30초 동안 반복 조회한 결과 오류는 0건이었지만 p95 3.74s, 누적 수신 데이터 1.4GB로 전체 목록 조회의 지연과 전송 부담을 확인

**해결 과정**

- 공연을 고른 직후에는 전체 잔여 좌석 수와 구역별 잔여 좌석 수만 주는 구역 요약 API, 구역을 고른 뒤에는 그 구역 좌석만 주는 API로 나눔
- 기존 전체 목록 조회와 두 API를 같은 부하 조건(100명, 30초 반복 조회)에서 각각 1회 비교
- 구역 요약 API가 공연을 고를 때마다 불리는 핵심 조회가 됐는데, 전체 잔여 좌석 수와 구역별 잔여 좌석 수를 따로 집계해 DB 집계 조회가 2회 발생
  - 먼저 복합 인덱스를 검토했으나 구역 요약 조회가 새 인덱스를 쓰지 않았고 응답 시간 개선도 일관되지 않아 전체 잔여 좌석 수를 구역별 합으로 계산해 DB 집계 조회를 1회로 줄이고 합산 결과가 기존 응답과 같은지 테스트로 확인

| API | 응답 |
| --- | --- |
| `GET /api/concerts/{concertId}/seats/available` | 예매 가능 좌석 전체 목록 (비교 기준인 기존 조회) |
| `GET /api/concerts/{concertId}/seats/availability` | 전체 잔여 좌석 수와 구역별 잔여 좌석 수 |
| `GET /api/concerts/{concertId}/seats/availability/sections/{section}` | 선택 구역 좌석 목록 |

**결과**

- 좌석 조회 (100명, 30초 반복 조회, 사용자 대기 1초 ± 0.5초, 각 1회)
  - 구역 요약 조회: p95 3.74s → 311.05ms, 수신 데이터 1.4GB → 1.8MB
  - 선택 구역 좌석 조회: p95 3.74s → 17.09ms, 수신 데이터 1.4GB → 103.6MB
- 중복 집계 제거 (같은 조건, 전후 각 3회 평균)
  - DB 집계 조회 2회 → 1회
  - 구역 요약 조회 p95 270.23ms → 113.15ms
- 안정성
  - 모든 측정에서 서버 오류와 예상 외 응답 0건

좌석 조회는 아래 공통 측정 환경과 다른 로컬 환경에서 측정했습니다.

<br>

## 💡 핵심 구현

### 1. 좌석 1~4매를 한 트랜잭션으로 구매

> 한 번에 고른 같은 공연 좌석 1~4매를 한 트랜잭션에서 구매해 그중 일부만 구매되는 경우를 막고, 좌석 ID 순서 잠금과 사용자 행 잠금으로 겹치는 구매 간 데드락과 1인 4매 한도 초과를 방지하는 구매 트랜잭션 흐름 구현

- `POST /api/tickets` 한 번에 같은 공연의 좌석 1~4매를 한 트랜잭션으로 처리
- 한 좌석이라도 이미 팔렸거나 Mock 결제가 실패하면 좌석 상태 변경과 티켓 생성을 모두 롤백

```http
POST /api/tickets
Authorization: Bearer <AT>
X-Entry-Token: <입장 토큰>
Idempotency-Key: <UUID>

{ "seatIds": [123, 124] }
```

기본 전략(비관적 락)의 DB 트랜잭션 순서입니다. JPA `IDENTITY` 키 생성에서는 티켓 INSERT가 좌석 UPDATE보다 먼저 실행될 수 있어, 좌석 UPDATE를 **명시적으로 flush한 뒤** 결제와 티켓 생성을 진행합니다. 그래서 경쟁에서 진 요청은 결제 전에 실패합니다.

```text
사용자 행 잠금 → 좌석 ID 오름차순 조회와 잠금 → 누적 4매, 판매 기간 확인
              → 모든 좌석 SOLD 변경 후 flush → 합산 Mock 결제 → 티켓 생성 → 커밋
```

| 보호 장치 | 지키는 규칙 |
| --- | --- |
| `users` 행 잠금 | 같은 사용자의 동시 요청이 누적 4매 한도를 넘지 않도록 직렬화 |
| 좌석 ID 오름차순 접근 | 겹치는 좌석 묶음도 같은 잠금 순서로 처리해 데드락 방지 |
| 낙관적 락, 비관적 락, 분산 락 | 서로 다른 사용자의 좌석 경쟁 제어, 설정으로 전략 교체 |
| 좌석 `@Version` | 버전이 바뀐 좌석의 갱신 거부 |
| `uq_ticket_seat` | 좌석당 티켓 1장이라는 DB 무결성 보장 |
| `purchase_key`, `admission_id` 기록 | 같은 구매의 응답 복구, 이미 쓴 입장 회차의 새 구매 거부 |

**결과**

- 겹치는 좌석 묶음을 동시에 구매해도 세 전략 모두 데드락 없이 한쪽만 성공 (실제 MySQL 통합 테스트)
- 모든 측정에서 좌석 중복, 누적 4매 초과, 부분 구매 0건

---

### 2. 같은 구매 요청은 한 번만 처리하고 응답을 잃어도 결과 복구

> Redis 멱등성 키와 DB 구매 기록 기반 응답 복구를 적용해 중복 클릭과 네트워크 재시도에도 같은 구매 요청을 한 번만 처리하고 구매가 끝난 뒤 응답을 받지 못한 재요청에는 같은 결과를 반환

**막아야 하는 상황**

- 키를 조회한 뒤 구매하고 응답을 저장하는 순서라면, 조회와 저장 사이에 들어온 같은 키의 요청이 함께 처리될 수 있음
- 같은 키로 다른 좌석을 요청했을 때 이전 응답을 돌려주면 안 됨
- 구매가 DB에 커밋된 뒤 Redis 저장이 실패하면, 성공한 구매가 500 응답으로 바뀔 수 있음

**구현**

1. Redis Lua로 `IN_PROGRESS`를 원자적으로 선점합니다. 완료와 해제는 선점한 요청의 owner가 같을 때만 수행합니다.
2. 좌석 ID 목록을 정렬해 SHA-256 해시로 비교합니다.
   - 같은 키, 다른 좌석 조합 → `422 IDEMPOTENCY_CONFLICT`
   - 처리 중인 요청 → `409 REQUEST_IN_PROGRESS`
3. **구매 트랜잭션의 커밋과 그 뒤의 Redis 처리를 나눕니다.** 커밋 뒤 응답 저장, 입장 권한 정리, 분산 락 해제에서 오류가 나도 확정된 구매를 실패로 바꾸지 않습니다.
4. Redis 기록이 없으면 같은 사용자, 같은 구매 키의 티켓 묶음을 DB에서 조회해 기존 성공 응답을 다시 만듭니다.
5. 끝난 구매의 응답 재전송과 DB 복구는 입장 토큰 검증보다 먼저 처리합니다. 구매에 성공하면 토큰을 이미 소진하므로, 재요청에 새 입장 권한을 요구하면 응답 복구가 막히기 때문입니다. **새 구매**는 반드시 입장 토큰 검증을 거칩니다.
6. 프론트엔드도 네트워크 오류나 5xx 뒤 같은 좌석으로 다시 구매할 때 같은 멱등성 키를 씁니다.

```text
요청 선점 → DB 구매 트랜잭션 → 커밋 → 입장 토큰 소진, 응답 저장
                                       └ 이 단계가 실패해도 구매 성공 유지

재요청 때 Redis 기록이 없으면 → 같은 구매 묶음을 DB에서 조회해 응답 복구
```

**결과**

- 커밋 직후 응답 유실을 주입했을 때, 같은 키로 다시 보내 같은 201 응답을 받음
- Redis 멱등성 기록을 지우거나 Redis를 재시작한 뒤에도 DB 구매 묶음으로 기존 성공 결과를 복구
- 자세한 결과는 [장애 주입](#장애-주입-커밋된-구매-결과가-실패로-바뀌는지-확인)에 있습니다.

> [!NOTE]
> 응답 저장에 실패해 `IN_PROGRESS`가 남아 있으면 최대 30초 동안 409가 반환될 수 있습니다.

---

### 3. 로그인 토큰 보관과 Refresh Token 재사용 탐지

> Google OAuth 2.0 로그인에서 Access Token은 메모리에만, Refresh Token은 스크립트로 읽을 수 없는 HttpOnly Cookie에 두고, 갱신할 때마다 새로 발급하는 Refresh Token Rotation을 구현해 이미 쓴 토큰의 재사용이 감지되면 탈취로 보고 세션을 끊는 인증 흐름 제공

**목표**

- Access Token(AT)이 만료돼도 다시 로그인하지 않고 계속 쓸 수 있게 함
- 스크립트로 Refresh Token(RT)을 읽을 수 없게 하고, 서버에도 RT 원문을 남기지 않음
- 로그인마다 세션을 따로 두어 다른 기기의 로그인이 서로의 RT를 덮어쓰지 않게 하고, 이미 쓴 RT가 다시 오면 그 세션을 끊음

| 영역 | 구현 |
| --- | --- |
| 로그인 | Google OAuth 2.0, RS256 JWT와 JWKS |
| 토큰 보관 | AT와 입장 토큰은 메모리, 난수 RT는 HttpOnly Cookie. DB에는 RT 원문 대신 SHA-256 해시 저장 |
| 세션 | 로그인마다 Session을 만들고 RT를 Session 단위로 관리. refresh와 로그아웃은 RT Cookie로 인증 |
| RT Rotation | Session, RT 순서로 잠그고 기존 RT를 소비한 뒤 새 RT 생성 |
| RT 재사용 탐지 | 소비 후 15초 안의 재제출은 재시도로 보고 유예, 그 뒤의 재제출은 Session을 끊음. 끊은 것을 커밋한 뒤 401 응답 |
| DB 제약 | 생성 컬럼과 UNIQUE로 세션당 미사용 RT 최대 1개 보장 |

**결과**

- 동시 refresh 10건에서 성공 1건, 나머지 9건은 재시도 응답이었고 미사용 RT 1개 유지, UNIQUE 위반 0건 (실제 MySQL 통합 테스트)
- 로그아웃이나 탈퇴 뒤의 refresh는 거부

> [!NOTE]
> 이미 발급된 AT를 바로 폐기하는 블랙리스트는 없으며, AT 수명은 15분입니다.

<br>

## 📊 측정과 기술 결정

> [!NOTE]
> **공통 측정 환경** : WSL2 8코어, 16GB, 단일 JVM(heap 1GiB), Hikari 10, Tomcat 최대 200 threads, Docker MySQL 8.0.36, Redis 7.2.4.
> 대기열, 락 전략, 인덱스, 장애 주입 측정에 해당합니다. k6와 앱, DB를 같은 머신에서 실행했으며 운영 환경 부하 측정은 하지 않았습니다.

대기열, 락 전략, 좌석 조회 측정은 [문제 해결](#-문제-해결)의 각 절에 있습니다.

### 인덱스: 효과가 작으면 추가하지 않음

좌석 상태 집계, 내 티켓 조회, 공연 판매 기간 조회에 쓸 수 있는 인덱스 후보 3개를 비교했습니다. 20개 공연, 30만 좌석, 기존 티켓 38,000건에서 인덱스가 없는 상태와 후보 3개를 각각 3회 비교하고, `EXPLAIN ANALYZE`, API 지연, 구매 처리량을 함께 봤습니다.

| 후보 | 관찰 | 결정 |
| --- | --- | --- |
| 좌석 `(concert_id, status)` | 구역 집계 p95 약 7%, AVAILABLE 조회 약 4.2% 감소, 구매 TPS 약 3.3% 감소 | 추가하지 않음 (개선 폭이 작고 쓰기 처리량이 줄어듦) |
| 티켓 `(user_id, created_at)` | 내 티켓 조회 개선 효과 없음 | 추가하지 않음 |
| 공연 판매 기간 인덱스 | 현재 API에 해당 조건의 조회 쿼리가 없음 | 추가하지 않음 |

세 후보 모두 추가하지 않았고, 이를 위한 Flyway V3도 만들지 않았습니다.

📄 [인덱스 비교 결과](backend/loadtest/results/index_comparison.md)

---

### 장애 주입: 커밋된 구매 결과가 실패로 바뀌는지 확인

[핵심 구현](#-핵심-구현) 1, 2번의 결과를 장애 상황에서 확인했습니다.

| 주입한 장애 | 확인한 결과 |
| --- | --- |
| 분산 락 lease 만료 | 경쟁 요청 중 성공 1건, 티켓 1장 유지. DB 제약 위반은 409 응답 |
| Redis 중단과 재시작 | 중단 중 새 구매는 500, 재시작 뒤 같은 키로 기존 성공 결과 복구 |
| 커밋 직후 응답 유실 | 같은 키로 다시 보내 같은 201 응답, Redis 멱등성 기록을 지운 뒤에도 DB 구매 묶음으로 복구 |

측정마다 좌석 중복, SOLD와 티켓 상태 불일치, 누적 4매 초과, 부분 구매를 검사했으며 **확인된 위반은 0건**입니다.

📄 [장애 주입 결과](backend/loadtest/results/fault_injection.md) / [측정 도구와 재현 방법](backend/loadtest/README.md)

<br>

## 📁 저장소 구조와 실행

```text
backend/
├─ src/main/java/.../domain/   auth, concert, seat, ticket, queue, user, payment
├─ src/main/java/.../infra/    멱등성, 분산 락(Redis, Redisson), 대기열, Google, Mock 결제
├─ src/main/resources/         설정, Flyway, 대기열 Lua
├─ src/test/                   단위, API 테스트
├─ src/integrationTest/        실제 MySQL, Redis 통합 테스트
└─ loadtest/                   k6, 장애 주입 도구, 측정 결과
frontend/                      React 화면, Vitest, Playwright
.github/                       CI, 배포 워크플로, 배포 스크립트
nginx/                         SPA, API 프록시 설정
Dockerfile.*                   backend, nginx 이미지
```

### 실행 방법

MySQL, Redis, Google OAuth 클라이언트, RSA 키쌍 2개(로그인용, 입장 토큰용)가 필요합니다. 환경변수는 [.env.example](.env.example)을 참고하세요.

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
# backend 단위, API 테스트
cd backend && ./gradlew test

# backend 통합 테스트 (Testcontainers 사용, Docker 필요)
cd backend && ./gradlew integrationTest

# frontend 테스트
cd frontend && npm test
```

</details>

<details>
<summary>API 요약</summary>

| 영역 | Method, Path | 인증 | 설명 |
| --- | --- | --- | --- |
| 인증 | `GET /api/auth/google/login`, `GET /api/auth/google/callback` | 없음 | Google 로그인 시작과 콜백, RT Cookie 발급 |
| 인증 | `POST /api/auth/refresh`, `POST /api/auth/logout` | RT Cookie | AT 재발급과 RT Rotation, 세션 종료 |
| 인증 | `GET /.well-known/jwks.json` | 없음 | 로그인 토큰 공개키 |
| 회원 | `GET /api/users/me`, `DELETE /api/users/me` | AT | 내 정보, 회원 탈퇴 |
| 공연 | `GET /api/concerts`, `GET /api/concerts/{concertId}` | 없음 | 공연 목록과 상세 |
| 공연 | `POST /api/concerts` | AT(ARTIST) | 공연 등록, 좌석 15,000석 생성 |
| 좌석 | `GET /api/concerts/{concertId}/seats/availability` | 없음 | 전체 잔여 좌석 수와 구역별 잔여 좌석 수 |
| 좌석 | `GET /api/concerts/{concertId}/seats/availability/sections/{section}` | 없음 | 선택 구역 좌석 목록 |
| 좌석 | `GET /api/concerts/{concertId}/seats`, `GET /api/concerts/{concertId}/seats/available` | 없음 | 전체 좌석 목록, 예매 가능 좌석 전체 목록 |
| 대기열 | `POST /api/queue/{concertId}`, `GET /api/queue/{concertId}/status` | AT | 대기열 등록, 순번과 폴링 간격 조회 |
| 대기열 | `POST /api/queue/{concertId}/enter`, `DELETE /api/queue/{concertId}` | AT | 입장(입장 토큰 발급), 대기 취소 |
| 대기열 | `GET /api/queue/.well-known/jwks.json` | 없음 | 입장 토큰 공개키 |
| 티켓 | `POST /api/tickets` | AT + `X-Entry-Token` + `Idempotency-Key` | 좌석 1~4매 구매 |
| 티켓 | `GET /api/tickets/me` | AT | 내 티켓 조회 |

</details>

### 관련 문서

- [백엔드 도메인 코드](backend/src/main/java/com/project/ticket/domain)
- [프론트 API 클라이언트](frontend/src/api.ts)
- [운영 Compose](docker-compose.prod.yml)
- [측정 결과 모음](backend/loadtest/results)
