import { expect, test, type Page, type Request } from '@playwright/test';
import type { ConcertResponse, QueueStatusResponse, TicketViewResponse } from '../src/types';

const accessToken = 'e2e-login-access-token';
const entryToken = 'e2e-queue-entry-token';
const concerts: ConcertResponse[] = [
  { concertId: 1, artistId: 10, title: '오픈 전 공연', venue: '서울 공연장', concertDate: '2030-06-01T10:00:00Z', ticketOpenDate: '2030-05-20T03:00:00Z', ticketCloseDate: '2030-05-31T14:59:00Z', status: 'BEFORE_OPEN' },
  { concertId: 2, artistId: 10, title: '예매 중 공연', venue: '서울 공연장', concertDate: '2030-06-01T10:00:00Z', ticketOpenDate: '2030-05-20T03:00:00Z', ticketCloseDate: '2030-05-31T14:59:00Z', status: 'ON_SALE' },
  { concertId: 3, artistId: 10, title: '마감 공연', venue: '서울 공연장', concertDate: '2030-06-01T10:00:00Z', ticketOpenDate: '2030-05-20T03:00:00Z', ticketCloseDate: '2030-05-31T14:59:00Z', status: 'CLOSED' },
];
const ticket: TicketViewResponse = {
  ticketId: 101, concertId: 2, concertTitle: '예매 중 공연', section: 'A', row: 1, col: 1, purchasePrice: 120000,
};

function queueStatus(status: 'WAITING' | 'ADMITTED'): QueueStatusResponse {
  return {
    concertId: 2, status, rank: status === 'WAITING' ? 3 : 0,
    behind: status === 'WAITING' ? 7 : 0, nextPollAfterMs: status === 'WAITING' ? 100 : 0,
    estimatedWaitSeconds: status === 'WAITING' ? 1.5 : null,
    serverTime: '2030-05-20T03:00:00Z', expiresAt: '2030-05-20T03:05:00Z',
  };
}

async function mockBackend(page: Page, options: {
  loggedIn?: boolean;
  purchase?: 'success' | 'expired' | 'lost' | 'limit';
} = {}) {
  const state = {
    loggedIn: options.loggedIn ?? true, admit: false, purchased: false,
    requests: [] as Request[],
  };
  await page.route('**/api/**', async route => {
    const request = route.request();
    state.requests.push(request);
    const path = new URL(request.url()).pathname;
    const endpoint = `${request.method()} ${path}`;
    switch (endpoint) {
      case 'GET /api/auth/google/login':
        expect(request.isNavigationRequest()).toBe(true);
        state.loggedIn = true;
        // Replace the external Google round trip with the callback's 302 /.
        await route.fulfill({ status: 302, headers: { location: '/' } });
        return;
      case 'POST /api/auth/refresh':
        await route.fulfill(state.loggedIn
          ? { json: { accessToken, tokenType: 'Bearer' } }
          : { status: 401, json: { code: 'REAUTH_REQUIRED', message: 'Session is invalid. Sign in again.' } });
        return;
      case 'GET /api/users/me':
        expect(request.headers().authorization).toBe(`Bearer ${accessToken}`);
        await route.fulfill({ json: { userId: 7, name: '테스트 팬', role: 'FAN' } });
        return;
      case 'GET /api/concerts':
        await route.fulfill({ json: concerts });
        return;
      case 'POST /api/queue/2':
        state.admit = false;
        expect(request.headers().authorization).toBe(`Bearer ${accessToken}`);
        await route.fulfill({ json: queueStatus('WAITING') });
        return;
      case 'GET /api/queue/2/status':
        expect(request.headers().authorization).toBe(`Bearer ${accessToken}`);
        await route.fulfill({ json: queueStatus(state.admit ? 'ADMITTED' : 'WAITING') });
        return;
      case 'POST /api/queue/2/enter':
        expect(state.admit).toBe(true);
        expect(request.headers().authorization).toBe(`Bearer ${accessToken}`);
        await route.fulfill({ json: {
          concertId: 2, status: 'ACTIVE', entryToken,
          serverTime: '2030-05-20T03:00:00Z', expiresAt: '2030-05-20T03:05:00Z',
        } });
        return;
      case 'GET /api/concerts/2/seats/availability':
        await route.fulfill({ json: { totalAvailable: 2, sections: [{ section: 'A', availableCount: 2 }] } });
        return;
      case 'GET /api/concerts/2/seats/availability/sections/A':
        await route.fulfill({ json: { section: 'A', availableCount: 2, seats: [
          { seatId: 21, row: 1, col: 1, price: 120000, status: 'AVAILABLE' },
          { seatId: 22, row: 1, col: 2, price: 120000, status: 'AVAILABLE' },
        ] } });
        return;
      case 'POST /api/tickets':
        expect(request.headers().authorization).toBe(`Bearer ${accessToken}`);
        expect(request.headers()['x-entry-token']).toBe(entryToken);
        expect(request.headers()['idempotency-key']).toMatch(/\S+/);
        expect(request.postDataJSON()).toEqual({ seatIds: [21, 22] });
        if (options.purchase === 'expired') {
          state.admit = false;
          await route.fulfill({ status: 403, json: { code: 'ENTRY_TOKEN_EXPIRED', message: 'Entry token has expired.' } });
        } else if (options.purchase === 'limit') {
          state.purchased = true;
          await route.fulfill({ status: 409, json: { code: 'PURCHASE_LIMIT_EXCEEDED', message: 'limit' } });
        } else {
          state.purchased = true;
          if (options.purchase === 'lost') await route.abort('failed');
          else await route.fulfill({ status: 201, json: [{ ticketId: 101, userId: 7, seatId: 21, purchasePrice: 120000 }, { ticketId: 102, userId: 7, seatId: 22, purchasePrice: 120000 }] });
        }
        return;
      case 'GET /api/tickets/me':
        expect(request.headers().authorization).toBe(`Bearer ${accessToken}`);
        expect(request.headers()['x-entry-token']).toBeUndefined();
        await route.fulfill({ json: state.purchased ? (options.purchase === 'limit' ? Array.from({ length: 4 }, (_, i) => ({ ...ticket, ticketId: 101 + i, col: i + 1 })) : [ticket, { ...ticket, ticketId: 102, col: 2 }]) : [] });
        return;
      default:
        throw new Error(`Unexpected API request: ${endpoint}`);
    }
  });
  return state;
}

function requestsTo(state: Awaited<ReturnType<typeof mockBackend>>, method: string, path: string) {
  return state.requests.filter(request => request.method() === method && new URL(request.url()).pathname === path);
}

async function selectSeat(page: Page, state: Awaited<ReturnType<typeof mockBackend>>) {
  await expect(page.getByRole('region', { name: '대기열', exact: true })).toBeVisible();
  await expect(page.getByRole('status').filter({ hasText: '내 순번 3번' })).toBeVisible();
  // Confirm a WAITING status poll before allowing admission; no fixed sleeps.
  await expect.poll(() => requestsTo(state, 'GET', '/api/queue/2/status').length).toBeGreaterThan(0);
  state.admit = true;
  await page.getByRole('button', { name: '입장하기', exact: true }).click();
  await expect(page.getByRole('heading', { name: '공연 구매 페이지' })).toBeVisible();
  const dialog = page.getByRole('dialog');
  await dialog.getByRole('button', { name: 'A 2석', exact: true }).click();
  await dialog.getByRole('button', { name: 'A 1-1', exact: true }).click();
  await dialog.getByRole('button', { name: 'A 1-2', exact: true }).click();
  await expect(dialog.getByText('A구역 1행 1열, A구역 1행 2열', { exact: true })).toBeVisible();
  await expect(dialog.getByText(/선택 2\/\d석 · \d매 더 구매 가능/)).toBeVisible();
  await expect(dialog.getByText('240,000원', { exact: true })).toBeVisible();
}

async function expectTicket(page: Page) {
  const tickets = page.getByRole('complementary').filter({ has: page.getByRole('heading', { name: '구매한 공연' }) });
  await expect(tickets.getByText('Ticket #101', { exact: true })).toBeVisible();
  await expect(tickets.getByText('예매 중 공연', { exact: true })).toHaveCount(2);
  await expect(tickets.getByText('Ticket #102', { exact: true })).toBeVisible();
  await expect(tickets.getByText('A구역 1행 1번', { exact: true })).toBeVisible();
  await expect(tickets.getByText('구매 가격 120,000원', { exact: true })).toHaveCount(2);
}

test('비회원 조회 → Google 로그인 복귀 → 대기열 입장 → 좌석 구매 → 내 티켓', async ({ page }) => {
  const state = await mockBackend(page, { loggedIn: false });
  await page.goto('/');
  await expect(page.getByRole('heading', { level: 3 })).toHaveCount(3);
  await expect(page.getByRole('button', { name: 'Google로 로그인' })).toBeVisible();
  await expect(page.getByRole('heading', { name: '구매한 공연' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: '오픈 예정' })).toBeDisabled();
  await expect(page.getByRole('button', { name: '판매 마감' })).toBeDisabled();
  await page.getByRole('button', { name: '구매하기', exact: true }).click();
  await selectSeat(page, state);
  expect(requestsTo(state, 'GET', '/api/auth/google/login')).toHaveLength(1);
  expect(requestsTo(state, 'POST', '/api/auth/refresh').length).toBeGreaterThanOrEqual(2);
  expect(requestsTo(state, 'POST', '/api/queue/2')).toHaveLength(1);
  expect(requestsTo(state, 'POST', '/api/queue/2/enter')).toHaveLength(1);
  page.once('dialog', dialog => dialog.accept());
  await page.getByRole('button', { name: '선택 좌석 예매하기' }).click();
  await expectTicket(page);
  expect(requestsTo(state, 'POST', '/api/tickets')).toHaveLength(1);
});

test('로그인해도 판매 상태와 대기열을 건너뛰어 구매할 수 없다', async ({ page }) => {
  const state = await mockBackend(page);
  await page.goto('/');
  await expect(page.getByText('테스트 팬 · FAN')).toBeVisible();
  await expect(page.getByRole('button', { name: '오픈 예정' })).toBeDisabled();
  await expect(page.getByRole('button', { name: '판매 마감' })).toBeDisabled();
  await expect(page.getByRole('button', { name: '구매하기', exact: true })).toBeEnabled();
  await expect(page.getByRole('heading', { name: '공연 구매 페이지' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: '구역 및 좌석 선택' })).toHaveCount(0);
  await page.getByRole('button', { name: '구매하기', exact: true }).click();
  await expect(page.getByRole('status').filter({ hasText: '내 순번 3번' })).toBeVisible();
  await expect.poll(() => requestsTo(state, 'GET', '/api/queue/2/status').length).toBeGreaterThan(0);
  await expect(page.getByRole('button', { name: '입장하기' })).toHaveCount(0);
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(page.getByRole('button', { name: '선택 좌석 예매하기' })).toHaveCount(0);
  expect(requestsTo(state, 'POST', '/api/queue/2')).toHaveLength(1);
  expect(requestsTo(state, 'POST', '/api/queue/2/enter')).toHaveLength(0);
  expect(state.requests.filter(request => /\/seats|\/api\/tickets$/.test(new URL(request.url()).pathname))).toHaveLength(0);
});

test('ENTRY_TOKEN_EXPIRED → 만료 안내 → 다시 대기열 등록', async ({ page }) => {
  const state = await mockBackend(page, { purchase: 'expired' });
  await page.goto('/');
  await page.getByRole('button', { name: '구매하기', exact: true }).click();
  await selectSeat(page, state);
  await page.getByRole('button', { name: '선택 좌석 예매하기' }).click();
  await expect(page.getByRole('alert')).toContainText('이용 시간이 끝났습니다. 다시 대기열에 서 주세요');
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await page.getByRole('button', { name: '다시 줄 서기' }).click();
  await expect(page.getByRole('status').filter({ hasText: '내 순번 3번' })).toBeVisible();
  expect(requestsTo(state, 'POST', '/api/queue/2')).toHaveLength(2);
  expect(requestsTo(state, 'POST', '/api/tickets')).toHaveLength(1);
  // Entry expiry must not trigger access-token refresh.
  expect(requestsTo(state, 'POST', '/api/auth/refresh')).toHaveLength(1);
});

test('구매 응답 네트워크 유실 → 내 티켓 재조회로 구매 결과 복구', async ({ page }) => {
  const state = await mockBackend(page, { purchase: 'lost' });
  await page.goto('/');
  await page.getByRole('button', { name: '구매하기', exact: true }).click();
  await selectSeat(page, state);
  await page.getByRole('button', { name: '선택 좌석 예매하기' }).click();
  await expect(page.getByText('구매 응답을 받지 못했습니다. 내 티켓에서 구매 결과를 확인해 주세요.', { exact: true })).toBeVisible();
  await expectTicket(page);
  expect(requestsTo(state, 'POST', '/api/tickets')).toHaveLength(1);
  expect(requestsTo(state, 'GET', '/api/tickets/me')).toHaveLength(3);
  const purchaseIndex = state.requests.findIndex(request => new URL(request.url()).pathname === '/api/tickets');
  const recoveryIndex = state.requests.findIndex((request, index) => index > purchaseIndex && new URL(request.url()).pathname === '/api/tickets/me');
  expect(recoveryIndex).toBeGreaterThan(purchaseIndex);
});

test('구매 후 재구매는 WAITING으로 대기열 재등록', async ({ page }) => {
  const state = await mockBackend(page);
  await page.goto('/');
  await page.getByRole('button', { name: '구매하기', exact: true }).click();
  await selectSeat(page, state);
  page.once('dialog', dialog => dialog.accept());
  await page.getByRole('button', { name: '선택 좌석 예매하기' }).click();
  await expectTicket(page);
  await page.getByRole('button', { name: '구매하기', exact: true }).click();
  await expect(page.getByRole('status').filter({ hasText: '내 순번 3번' })).toBeVisible();
  expect(requestsTo(state, 'POST', '/api/queue/2')).toHaveLength(2);
  expect(requestsTo(state, 'POST', '/api/queue/2/enter')).toHaveLength(1);
  await expect(page.getByRole('dialog')).toHaveCount(0);
});

test('PURCHASE_LIMIT_EXCEEDED 안내와 보유 한도 재조회', async ({ page }) => {
  const state = await mockBackend(page, { purchase: 'limit' });
  await page.goto('/');
  await page.getByRole('button', { name: '구매하기', exact: true }).click();
  await selectSeat(page, state);
  await page.getByRole('button', { name: '선택 좌석 예매하기' }).click();
  await expect(page.getByText('공연당 최대 4매까지 구매할 수 있습니다', { exact: true })).toBeVisible();
  await expect(page.getByText('이 공연은 0매 더 구매할 수 있습니다', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '닫기', exact: true }).click();
  await page.getByRole('button', { name: '팬 메인으로', exact: true }).click();
  await expect(page.getByRole('button', { name: '구매 한도(4매) 도달', exact: true })).toBeDisabled();
  expect(requestsTo(state, 'POST', '/api/queue/2')).toHaveLength(1);
});
