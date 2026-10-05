# 인증 API (Phase 3-3)

nginx와 SPA는 같은 origin을 사용한다. Google code는 서버에서 교환하고 UserInfo의 `sub`·`email_verified`를 검증한다. Google 토큰은 저장하지 않는다. 새 사용자는 FAN이며 이메일로 다른 Google 계정을 연결하지 않는다.

| 경로 | 인증 | 성공 응답 |
| --- | --- | --- |
| `GET /api/auth/google/login` | 없음 | `302 Location: https://accounts.google.com/o/oauth2/v2/auth?...`, state Cookie 발급 |
| `GET /api/auth/google/callback?code=...&state=...` | state Cookie + query state/code | **302 Location: /**, RT Cookie 발급, state Cookie 삭제, 본문 없음 |
| `POST /api/auth/refresh` | **RT Cookie만**, AT 불필요 | `200 {"accessToken":"...","tokenType":"Bearer"}`, 새 RT Cookie |
| `POST /api/auth/logout` | **RT Cookie만**, AT 불필요 | `204`, 현재 Session revoke + RT Cookie 삭제 |
| `DELETE /api/users/me` | Bearer AT | `204`, `deleted_at` 설정 + 사용자 전체 Session revoke + RT Cookie 삭제 |

이메일/비밀번호 `/api/auth/signup`, `/api/auth/login`과 구 refresh `/api/auth/token/refresh`는 제거했다. refresh·logout의 501 stub은 없다. callback 본문·URL·fragment에 AT를 넣지 않는다. refresh·logout은 Authorization 헤더에 만료되거나 잘못된 AT가 있어도 RT Cookie로만 인증한다. AT는 RS256, 15분 수명이며 `sid`는 발급 Session ID다. 로그아웃·탈퇴 이후에도 기존 AT의 무상태 서명 검증은 만료 전까지 가능하므로 구매 경로는 `users.deleted_at IS NULL`도 확인한다(탈퇴 사용자는 `404 USER_NOT_FOUND`). 공연 생성(`POST /api/concerts`)도 같다.

| Cookie | HttpOnly | Secure | SameSite | Path | Max-Age | Domain |
| --- | --- | --- | --- | --- | --- | --- |
| `oauth_state` | true | 기본 true | Lax | `/api/auth/google` | 600초 | 생략(host-only) |
| `refresh_token`(로그인·refresh) | true | 기본 true | Lax | `/api/auth` | Session `expires_at - 현재`(초, 0 미만은 0). 로그인 직후 1209600초(14일), refresh 시 남은 수명 | 생략(host-only) |
| 삭제 Cookie | true | 기본 true | Lax | 원래 Cookie와 동일 | 0 | 생략(host-only) |

callback 성공·실패 모두 state Cookie를 삭제한다. `REAUTH_REQUIRED`, 성공 logout·탈퇴는 RT Cookie를 삭제한다. `REFRESH_RETRY`는 Cookie를 변경하지 않는다. 원본 RT는 32바이트 SecureRandom의 Base64URL이며 Cookie에만 전달하고 DB에는 SHA-256 BINARY(32)만 저장한다. 로그인·callback·refresh·logout 응답은 `Cache-Control: no-store`다.

오류는 JSON `{"code":"...","message":"..."}`다.

| 코드 | HTTP | 의미·클라이언트 처리 |
| --- | --- | --- |
| `INVALID_REQUEST` | 400 | callback state 불일치·누락, code 누락. 메시지 `Request body is invalid.` |
| `UNAUTHORIZED` | 401 | 미검증 이메일, 잘못된 Google 프로필/토큰 교환 실패, 보호 API의 AT 오류. 메시지 `Authentication is required.` |
| `REFRESH_RETRY` | 401 | **유효 Session**에서 소비된 RT를 소비 후 15초 이내(경계 포함)에 재제출. Session 유지. 최신 Cookie로 **1회만 재시도**. 메시지 `Refresh token was already consumed. Retry once.` |
| `REAUTH_REQUIRED` | 401 | refresh의 RT 누락·잘못된 형식·존재하지 않는 RT·Session 만료/revoke 또는 15초 초과 재사용. RT Cookie 삭제, **재로그인**. logout에서 RT가 누락되거나 존재하지 않는 경우도 이 코드. 메시지 `Session is invalid. Sign in again.` |
| `INTERNAL_SERVER_ERROR` | 500 | 저장/트랜잭션 실패 등 내부 오류. logout 실패를 204로 바꾸지 않는다. 메시지 `An internal server error occurred.` |

logout은 현재 Session의 소비된 RT도 허용하며 이미 revoke된 Session에도 204로 Cookie를 삭제한다. 탈퇴 후 같은 Google 계정으로 로그인하면 기존 사용자 ID/role을 복구하고 새 Session을 만들며, 과거 revoke된 Session은 복구하지 않는다.

프론트 연결:

- callback의 `302 /` 이후 앱 로드 시 `POST /api/auth/refresh`로 AT를 받는다. AT를 메모리에 보관하고 보호 API에 Bearer로 전달한다.
- Cookie API에는 `credentials: 'include'`를 사용한다. RT를 localStorage에 저장하지 않는다.
- 여러 401이 발생하면 Single-Flight로 refresh를 한 번 공유한다. refresh에서 `REFRESH_RETRY`면 최신 Cookie로 1회 재시도하고, 재시도도 실패하면 재로그인하고, 반복 재시도 루프를 만들지 않는다. `REAUTH_REQUIRED`면 AT를 비우고 Google 재로그인으로 이동한다.
- `POST /api/auth/logout` 또는 `DELETE /api/users/me` 성공 시 메모리 AT를 비운다. 오류를 로그아웃 성공으로 표시하지 않는다.

Cookie Max-Age는 Session 만료 시각과 일치시킨다(`expires_at - now`). refresh는 Session 수명을 연장하지 않는다. Session은 로그인부터 절대 14일이며 사용자당 Session 개수 상한은 없다(05 3(f)). 만료 후 7일 보존하고 매일 UTC 03:00에 Session과 RT 이력(FK CASCADE)을 정리한다. refresh는 잠금 없는 RT 스칼라 조회 → Session 행 잠금 → RT 행 잠금 → 상태 확인 → 소비 UPDATE/flush → 새 RT INSERT를 한 트랜잭션으로 처리한다. Reuse의 revoke는 오류 결과를 정상 반환해 커밋한 뒤 컨트롤러가 401을 반환한다. 자세한 락 순서는 `plan/plan_output/05_설계_정정_및_API_계약.md` 3(d)에 기록했다.

신규 환경변수: `JWT_PRIVATE_KEY_PEM`, `JWT_PUBLIC_KEY_PEM`, `JWT_KEY_ID`, `JWT_ISSUER`, `JWT_AUDIENCE`, `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `GOOGLE_REDIRECT_URI`, `AUTH_COOKIE_SECURE`. `AUTH_COOKIE_SECURE`는 HTTPS 운영에서 기본 true, HTTP 로컬 개발에만 false를 사용할 수 있다. nginx의 `/api/` 프록시는 Set-Cookie를 보존해야 한다. 스키마는 마이그레이션 V1을 재작성했다.

V1의 `refresh_token.unconsumed_session_id`는 **VIRTUAL로 정정 적용(05 3(b))**했다. STORED 생성식의 기반 컬럼 FK와 ON DELETE CASCADE 조합이 MySQL 1215로 거부되어 변경한 것이다. VIRTUAL의 UNIQUE 보조 인덱스와 FK CASCADE는 유지한다.
