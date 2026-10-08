# 로컬 장애 주입 결과

## 환경

```json
{
  "attempt_utc": "2026-10-07T04:46:52.538703+00:00",
  "mysql": "8.0.36",
  "redis": "7.2.4",
  "lease": "500ms (default 10s)",
  "F1_strategy": "distributed",
  "F2_F3_strategy": "pessimistic",
  "db_gate_seconds": 8,
  "jar_sha256": "85253f08474144971392809b3fadf68d5b9c8030f18985df82a14f265105ac11"
}
```

버전·lease·포트는 도구의 지정 설정이다. 실제 기동·장애 주입 여부는 시나리오별 판정에 기록한다.
로컬 Docker ticket-loadtest 프로젝트, MySQL 13306 / Redis 16379 / 앱 18080 / 응답 유실 프록시 18081.
개발 Redis(6379, ticket-redis)·MySQL·8080 앱에는 접근하지 않는다. 운영 실측 없음.

## 시나리오별 주입·기대·관찰·판정

### F1

- 주입 방법·기대: distributed 500ms lease + 8초 외부 DB 잠금. 동일 좌석 201 정확히 1건, 나머지 409. uq_ticket_seat 별도 주입은 409 SEAT_NOT_AVAILABLE + 별도 로그. 커밋 결과 유지.
- 판정: 통과.
- 관찰:
```json
{
  "scenario": "F1",
  "checks": {
    "lease_expired_before_commit": true,
    "exactly_one_201_no_5xx": true,
    "exactly_one_ticket": true,
    "unique_constraint_409": true,
    "unique_constraint_logged": true,
    "constraint_rollback_keeps_existing_ticket": true,
    "all_four_sql_invariants_zero": true
  },
  "responses": [
    {
      "status": 201,
      "body": [
        {
          "ticketId": 1,
          "userId": 1,
          "seatId": 1,
          "purchasePrice": 10000
        }
      ],
      "elapsed_ms": 7839.157812006306,
      "stage": "contender-1"
    },
    {
      "status": 409,
      "body": {
        "code": "SEAT_LOCK_FAILED",
        "message": "Seat lock acquisition failed."
      },
      "elapsed_ms": 6813.635257014539,
      "stage": "contender-2"
    },
    {
      "status": 409,
      "body": {
        "code": "SEAT_NOT_AVAILABLE",
        "message": "Seat is not available."
      },
      "elapsed_ms": 108.299724990502,
      "stage": "unique-constraint-probe"
    }
  ],
  "observations": {
    "overlapping_db_waiters": 3,
    "constraint_probe": "기존 티켓 유지한 채 좌석 AVAILABLE 주입; 롤백 확인 후 SOLD로 복원"
  },
  "valid": true,
  "audit": {
    "duplicate": 0,
    "sold_mismatch": 0,
    "over_limit": 0,
    "partial_bundle": 0,
    "tickets": 1
  },
  "distribution": {
    "201/CREATED": 1,
    "409/SEAT_LOCK_FAILED": 1,
    "409/SEAT_NOT_AVAILABLE": 1
  }
}
```

### F2

- 주입 방법·기대: 구매 트랜잭션이 DB에서 대기하는 동안 측정 Redis stop. 신규 요청은 계약 형식 500 INTERNAL_SERVER_ERROR. 선점 완료 구매는 201. 재시작/상태 유실 뒤 DB replay 및 재등록.
- 판정: 통과.
- 관찰:
```json
{
  "scenario": "F2",
  "checks": {
    "baseline_201": true,
    "outage_responses_contract_500": true,
    "inflight_commit_remains_201": true,
    "restart_queue_readable": true,
    "restart_replay_1": true,
    "restart_replay_2": true,
    "lost_queue_is_expired": true,
    "lost_active_rejects_old_token": true,
    "state_loss_db_replay_1": true,
    "state_loss_db_replay_2": true,
    "reregister_and_retry_201": true,
    "all_four_sql_invariants_zero": true
  },
  "responses": [
    {
      "status": 201,
      "body": [
        {
          "ticketId": 3,
          "userId": 1,
          "seatId": 1,
          "purchasePrice": 10000
        },
        {
          "ticketId": 4,
          "userId": 1,
          "seatId": 2,
          "purchasePrice": 10000
        }
      ],
      "elapsed_ms": 487.3606619948987,
      "stage": "before-stop"
    },
    {
      "status": 500,
      "body": {
        "code": "INTERNAL_SERVER_ERROR",
        "message": "An internal server error occurred."
      },
      "elapsed_ms": 83.43813000828959,
      "stage": "during-stop-3"
    },
    {
      "status": 500,
      "body": {
        "code": "INTERNAL_SERVER_ERROR",
        "message": "An internal server error occurred."
      },
      "elapsed_ms": 53.81945500266738,
      "stage": "during-stop-4"
    },
    {
      "status": 500,
      "body": {
        "code": "INTERNAL_SERVER_ERROR",
        "message": "An internal server error occurred."
      },
      "elapsed_ms": 45.868500019423664,
      "stage": "during-stop-5"
    },
    {
      "status": 500,
      "body": {
        "code": "INTERNAL_SERVER_ERROR",
        "message": "An internal server error occurred."
      },
      "elapsed_ms": 74.81000499683432,
      "stage": "during-stop-6"
    },
    {
      "status": 500,
      "body": {
        "code": "INTERNAL_SERVER_ERROR",
        "message": "An internal server error occurred."
      },
      "elapsed_ms": 87.32548600528389,
      "stage": "during-stop-7"
    },
    {
      "status": 500,
      "body": {
        "code": "INTERNAL_SERVER_ERROR",
        "message": "An internal server error occurred."
      },
      "elapsed_ms": 82.34910099417903,
      "stage": "during-stop-8"
    },
    {
      "status": 500,
      "body": {
        "code": "INTERNAL_SERVER_ERROR",
        "message": "An internal server error occurred."
      },
      "elapsed_ms": 40.80278499168344,
      "stage": "during-stop-9"
    },
    {
      "status": 500,
      "body": {
        "code": "INTERNAL_SERVER_ERROR",
        "message": "An internal server error occurred."
      },
      "elapsed_ms": 62.83221501507796,
      "stage": "during-stop-10"
    },
    {
      "status": 500,
      "body": {
        "code": "INTERNAL_SERVER_ERROR",
        "message": "An internal server error occurred."
      },
      "elapsed_ms": 62.32932300190441,
      "stage": "during-stop-11"
    },
    {
      "status": 500,
      "body": {
        "code": "INTERNAL_SERVER_ERROR",
        "message": "An internal server error occurred."
      },
      "elapsed_ms": 87.62119902530685,
      "stage": "during-stop-12"
    },
    {
      "status": 500,
      "body": {
        "code": "INTERNAL_SERVER_ERROR",
        "message": "An internal server error occurred."
      },
      "elapsed_ms": 43.58789898105897,
      "stage": "during-stop-13"
    },
    {
      "status": 500,
      "body": {
        "code": "INTERNAL_SERVER_ERROR",
        "message": "An internal server error occurred."
      },
      "elapsed_ms": 83.41645199107006,
      "stage": "during-stop-14"
    },
    {
      "status": 500,
      "body": {
        "code": "INTERNAL_SERVER_ERROR",
        "message": "An internal server error occurred."
      },
      "elapsed_ms": 59.56891999812797,
      "stage": "during-stop-15"
    },
    {
      "status": 500,
      "body": {
        "code": "INTERNAL_SERVER_ERROR",
        "message": "An internal server error occurred."
      },
      "elapsed_ms": 77.537985984236,
      "stage": "during-stop-16"
    },
    {
      "status": 500,
      "body": {
        "code": "INTERNAL_SERVER_ERROR",
        "message": "An internal server error occurred."
      },
      "elapsed_ms": 89.29968799930066,
      "stage": "during-stop-17"
    },
    {
      "status": 500,
      "body": {
        "code": "INTERNAL_SERVER_ERROR",
        "message": "An internal server error occurred."
      },
      "elapsed_ms": 32.60827201302163,
      "stage": "during-stop-18"
    },
    {
      "status": 500,
      "body": {
        "code": "INTERNAL_SERVER_ERROR",
        "message": "An internal server error occurred."
      },
      "elapsed_ms": 91.06659499229863,
      "stage": "during-stop-19"
    },
    {
      "status": 500,
      "body": {
        "code": "INTERNAL_SERVER_ERROR",
        "message": "An internal server error occurred."
      },
      "elapsed_ms": 17.847568989964202,
      "stage": "during-stop-20"
    },
    {
      "status": 500,
      "body": {
        "code": "INTERNAL_SERVER_ERROR",
        "message": "An internal server error occurred."
      },
      "elapsed_ms": 69.22785800998099,
      "stage": "during-stop-21"
    },
    {
      "status": 500,
      "body": {
        "code": "INTERNAL_SERVER_ERROR",
        "message": "An internal server error occurred."
      },
      "elapsed_ms": 59.70429599983618,
      "stage": "during-stop-22"
    },
    {
      "status": 500,
      "body": {
        "code": "INTERNAL_SERVER_ERROR",
        "message": "An internal server error occurred."
      },
      "elapsed_ms": 64.32910400326364,
      "stage": "during-stop-23"
    },
    {
      "status": 500,
      "body": {
        "code": "INTERNAL_SERVER_ERROR",
        "message": "An internal server error occurred."
      },
      "elapsed_ms": 62.39085100241937,
      "stage": "during-stop-24"
    },
    {
      "status": 500,
      "body": {
        "code": "INTERNAL_SERVER_ERROR",
        "message": "An internal server error occurred."
      },
      "elapsed_ms": 61.030179989757016,
      "stage": "during-stop-25"
    },
    {
      "status": 500,
      "body": {
        "code": "INTERNAL_SERVER_ERROR",
        "message": "An internal server error occurred."
      },
      "elapsed_ms": 36.58199499477632,
      "stage": "during-stop-26"
    },
    {
      "status": 500,
      "body": {
        "code": "INTERNAL_SERVER_ERROR",
        "message": "An internal server error occurred."
      },
      "elapsed_ms": 56.91594901145436,
      "stage": "during-stop-27"
    },
    {
      "status": 500,
      "body": {
        "code": "INTERNAL_SERVER_ERROR",
        "message": "An internal server error occurred."
      },
      "elapsed_ms": 63.97577899042517,
      "stage": "during-stop-28"
    },
    {
      "status": 500,
      "body": {
        "code": "INTERNAL_SERVER_ERROR",
        "message": "An internal server error occurred."
      },
      "elapsed_ms": 57.0580160128884,
      "stage": "during-stop-29"
    },
    {
      "status": 500,
      "body": {
        "code": "INTERNAL_SERVER_ERROR",
        "message": "An internal server error occurred."
      },
      "elapsed_ms": 58.94800601527095,
      "stage": "during-stop-30"
    },
    {
      "status": 500,
      "body": {
        "code": "INTERNAL_SERVER_ERROR",
        "message": "An internal server error occurred."
      },
      "elapsed_ms": 34.69795099226758,
      "stage": "during-stop-31"
    },
    {
      "status": 500,
      "body": {
        "code": "INTERNAL_SERVER_ERROR",
        "message": "An internal server error occurred."
      },
      "elapsed_ms": 65.02955700852908,
      "stage": "during-stop-32"
    },
    {
      "status": 500,
      "body": {
        "code": "INTERNAL_SERVER_ERROR",
        "message": "An internal server error occurred."
      },
      "elapsed_ms": 65.59896498220041,
      "stage": "during-stop-33"
    },
    {
      "status": 500,
      "body": {
        "code": "INTERNAL_SERVER_ERROR",
        "message": "An internal server error occurred."
      },
      "elapsed_ms": 61.943634995259345,
      "stage": "during-stop-34"
    },
    {
      "status": 201,
      "body": [
        {
          "ticketId": 5,
          "userId": 2,
          "seatId": 3,
          "purchasePrice": 10000
        },
        {
          "ticketId": 6,
          "userId": 2,
          "seatId": 4,
          "purchasePrice": 10000
        }
      ],
      "elapsed_ms": 7809.005796996644,
      "stage": "claimed-before-stop"
    },
    {
      "status": 200,
      "body": {
        "concertId": 1,
        "status": "ACTIVE",
        "rank": 0,
        "behind": 0,
        "nextPollAfterMs": 0,
        "estimatedWaitSeconds": null,
        "expiresAt": "2026-10-07T04:53:00.127Z",
        "serverTime": "2026-10-07T04:48:10.748703249Z"
      },
      "elapsed_ms": 9.490126016316935,
      "stage": "queue-after-restart"
    },
    {
      "status": 201,
      "body": [
        {
          "ticketId": 3,
          "userId": 1,
          "seatId": 1,
          "purchasePrice": 10000
        },
        {
          "ticketId": 4,
          "userId": 1,
          "seatId": 2,
          "purchasePrice": 10000
        }
      ],
      "elapsed_ms": 11.560754996025935,
      "stage": "restart-replay-1"
    },
    {
      "status": 201,
      "body": [
        {
          "ticketId": 5,
          "userId": 2,
          "seatId": 3,
          "purchasePrice": 10000
        },
        {
          "ticketId": 6,
          "userId": 2,
          "seatId": 4,
          "purchasePrice": 10000
        }
      ],
      "elapsed_ms": 23.732103989459574,
      "stage": "restart-replay-2"
    },
    {
      "status": 200,
      "body": {
        "concertId": 1,
        "status": "EXPIRED",
        "rank": 0,
        "behind": 0,
        "nextPollAfterMs": 0,
        "estimatedWaitSeconds": null,
        "expiresAt": null,
        "serverTime": "2026-10-07T04:48:31.620240364Z"
      },
      "elapsed_ms": 5.561008001677692,
      "stage": "queue-after-state-loss"
    },
    {
      "status": 403,
      "body": {
        "code": "ENTRY_TOKEN_INVALID",
        "message": "Entry token is invalid."
      },
      "elapsed_ms": 33.56418901239522,
      "stage": "lost-active-old-token"
    },
    {
      "status": 201,
      "body": [
        {
          "ticketId": 3,
          "userId": 1,
          "seatId": 1,
          "purchasePrice": 10000
        },
        {
          "ticketId": 4,
          "userId": 1,
          "seatId": 2,
          "purchasePrice": 10000
        }
      ],
      "elapsed_ms": 13.601161015685648,
      "stage": "db-replay-1"
    },
    {
      "status": 201,
      "body": [
        {
          "ticketId": 5,
          "userId": 2,
          "seatId": 3,
          "purchasePrice": 10000
        },
        {
          "ticketId": 6,
          "userId": 2,
          "seatId": 4,
          "purchasePrice": 10000
        }
      ],
      "elapsed_ms": 13.085359998513013,
      "stage": "db-replay-2"
    },
    {
      "status": 201,
      "body": [
        {
          "ticketId": 7,
          "userId": 3,
          "seatId": 5,
          "purchasePrice": 10000
        },
        {
          "ticketId": 8,
          "userId": 3,
          "seatId": 6,
          "purchasePrice": 10000
        },
        {
          "ticketId": 9,
          "userId": 3,
          "seatId": 7,
          "purchasePrice": 10000
        },
        {
          "ticketId": 10,
          "userId": 3,
          "seatId": 8,
          "purchasePrice": 10000
        }
      ],
      "elapsed_ms": 73.32529799896292,
      "stage": "reregister-and-retry"
    }
  ],
  "observations": {
    "queue_state_after_restart": {
      "concertId": 1,
      "status": "ACTIVE",
      "rank": 0,
      "behind": 0,
      "nextPollAfterMs": 0,
      "estimatedWaitSeconds": null,
      "expiresAt": "2026-10-07T04:53:00.127Z",
      "serverTime": "2026-10-07T04:48:10.748703249Z"
    },
    "state_loss": "측정 Redis FLUSHDB 후 EXPIRED, 구 토큰 403, 큐 재등록·동일 키 재시도 201"
  },
  "valid": true,
  "audit": {
    "duplicate": 0,
    "sold_mismatch": 0,
    "over_limit": 0,
    "partial_bundle": 0,
    "tickets": 8
  },
  "distribution": {
    "201/CREATED": 7,
    "500/INTERNAL_SERVER_ERROR": 32,
    "403/ENTRY_TOKEN_INVALID": 1
  }
}
```

### F3

- 주입 방법·기대: loopback 프록시가 서버 201을 읽고 응답 없이 연결 종료. 소진 토큰으로 동일 키 201 replay. 멱등성 키 삭제 후 DB replay 동일. 새 키/새 입장은 매진 409, 합계 4매 유지.
- 판정: 통과.
- 관찰:
```json
{
  "scenario": "F3",
  "checks": {
    "commit_201_and_client_disconnect": true,
    "committed_before_replay": true,
    "consumed_token_replay_201": true,
    "idempotency_key_removed": true,
    "db_recovery_same_201": true,
    "new_key_old_token_403": true,
    "new_key_sold_seats_409": true,
    "limit_rejects_five": true,
    "failed_batch_retains_token_then_four_succeed": true,
    "four_ticket_limit_accurate": true,
    "all_four_sql_invariants_zero": true
  },
  "responses": [
    {
      "status": 0,
      "transport": "RemoteDisconnected",
      "body": null,
      "elapsed_ms": 471.6352279938292,
      "stage": "lost-client-response"
    },
    {
      "status": 201,
      "body": [
        {
          "ticketId": 11,
          "userId": 1,
          "seatId": 1,
          "purchasePrice": 10000
        },
        {
          "ticketId": 12,
          "userId": 1,
          "seatId": 2,
          "purchasePrice": 10000
        }
      ],
      "stage": "proxy-observed-upstream"
    },
    {
      "status": 201,
      "body": [
        {
          "ticketId": 11,
          "userId": 1,
          "seatId": 1,
          "purchasePrice": 10000
        },
        {
          "ticketId": 12,
          "userId": 1,
          "seatId": 2,
          "purchasePrice": 10000
        }
      ],
      "elapsed_ms": 12.186664011096582,
      "stage": "consumed-token-replay"
    },
    {
      "status": 201,
      "body": [
        {
          "ticketId": 11,
          "userId": 1,
          "seatId": 1,
          "purchasePrice": 10000
        },
        {
          "ticketId": 12,
          "userId": 1,
          "seatId": 2,
          "purchasePrice": 10000
        }
      ],
      "elapsed_ms": 26.322398014599457,
      "stage": "deleted-key-db-replay"
    },
    {
      "status": 403,
      "body": {
        "code": "ENTRY_TOKEN_INVALID",
        "message": "Entry token is invalid."
      },
      "elapsed_ms": 35.43492199969478,
      "stage": "different-key-consumed-token"
    },
    {
      "status": 409,
      "body": {
        "code": "SEAT_NOT_AVAILABLE",
        "message": "Seat is not available."
      },
      "elapsed_ms": 44.37827298534103,
      "stage": "different-key-new-entry-sold"
    },
    {
      "status": 409,
      "body": {
        "code": "PURCHASE_LIMIT_EXCEEDED",
        "message": "Purchase limit of four tickets per concert exceeded."
      },
      "elapsed_ms": 47.46245002024807,
      "stage": "cumulative-five-rejected"
    },
    {
      "status": 201,
      "body": [
        {
          "ticketId": 13,
          "userId": 1,
          "seatId": 3,
          "purchasePrice": 10000
        },
        {
          "ticketId": 14,
          "userId": 1,
          "seatId": 4,
          "purchasePrice": 10000
        }
      ],
      "elapsed_ms": 57.98358598258346,
      "stage": "cumulative-four-success"
    },
    {
      "status": 409,
      "body": {
        "code": "PURCHASE_LIMIT_EXCEEDED",
        "message": "Purchase limit of four tickets per concert exceeded."
      },
      "elapsed_ms": 30.684088997077197,
      "stage": "fifth-ticket-rejected"
    }
  ],
  "observations": {},
  "valid": true,
  "audit": {
    "duplicate": 0,
    "sold_mismatch": 0,
    "over_limit": 0,
    "partial_bundle": 0,
    "tickets": 4
  },
  "distribution": {
    "0/TRANSPORT": 1,
    "201/CREATED": 4,
    "403/ENTRY_TOKEN_INVALID": 1,
    "409/SEAT_NOT_AVAILABLE": 1,
    "409/PURCHASE_LIMIT_EXCEEDED": 2
  }
}
```

## 정합성 SQL

- 좌석당 ticket 중복 / SOLD↔ticket 불일치 / 사용자·공연별 4매 초과 / purchase_key별 부분 묶음: 각각 0이어야 통과.
- 부분 묶음은 SQL 임시 테이블에 요청 사용자·구매키 SHA-256·좌석을 넣고 DB의 각 구매 묶음과 정확히 대조한다.
- 동일 키 replay는 중복 요청으로 세지 않는다. 응답 유실 요청도 서버 성공 및 커밋을 별도로 확인한다.

## 발견 결함과 조치

- 2026-10-07 로컬 Docker 실측 완료. 앱 결함 없음(정합성 위반·커밋된 구매의 5xx·replay 불일치 0건). 회귀 테스트 6건을 별도로 추가했다.
- 하드코딩된 lease만 ticket.seat-lock-lease로 외부화했다(기본 10초, waitTime 0 유지). 측정용 서버 우회 경로 없음.
- 기존 unlock 예외 격리, 커밋 후 consume/COMPLETE 예외 격리, purchase_key DB 복구 경로를 그대로 사용한다.

## 해석·남은 일

- @Version이 uq_ticket_seat보다 먼저 충돌을 감지할 수 있다. F1은 lease 경합과 UNIQUE 최종 방어를 별도 검사한다.
- F1 UNIQUE 하위 시나리오는 티켓이 있는 좌석을 AVAILABLE로 만드는 고의 불일치이며, 롤백 확인 후 SOLD로 복원해 최종 SQL을 검사한다.
- COMPLETE 장애 후 IN_PROGRESS가 남으면 30초 동안 409 REQUEST_IN_PROGRESS가 가능하다. F2는 TTL 소멸 뒤 replay를 검사한다.
- F2 stop/start 뒤 실제 큐 상태를 먼저 기록한다. 별도 FLUSHDB로 전체 상태 유실도 주입해 EXPIRED/재등록/DB 복구를 검사한다.
- 새 키 + 소진 토큰은 403 ENTRY_TOKEN_INVALID, 새 키 + 새 입장은 매진 409 또는 한도 409다.
- F1 경합 패자는 409 SEAT_LOCK_FAILED(낙관적 락 충돌 매핑)로 응답한다. 계약상 409이며 SEAT_NOT_AVAILABLE과 코드가 다르다.
- Redis 중단 중 신규 구매는 500 INTERNAL_SERVER_ERROR(05 계약 형식)다. 05에 503 코드가 없어 현재 계약에는 부합한다. 503 도입은 계약 변경 사항이다.

## 실행

`bash backend/loadtest/fault/run-fault-injection.sh`

실행 방법·필수 도구·정리·Docker 없는 검증은 `backend/loadtest/README.md` 참조.
