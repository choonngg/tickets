import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { StrictMode } from 'react';
import { api, auth, clearEntryToken, getEntryToken } from './api';

import App from './App';
import type { UserRole } from './types';

describe('Ticket frontend', () => {
  afterEach(() => {
    auth.clear();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('shows the public concert list and Google login when no cookie session exists', async () => {
    mockLoginFlow('FAN', (url) => url.endsWith('/api/auth/refresh')
      ? Promise.resolve(errorResponse(401, 'REAUTH_REQUIRED', 'Session is invalid.')) : undefined);
    render(<App />);
    expect(screen.queryByRole('heading', { name: '로그인' })).not.toBeInTheDocument();
    expect(screen.getByText('로그인 정보를 확인하고 있습니다.')).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: 'Google로 로그인' })).toBeInTheDocument();
    expect(await screen.findByRole('heading', { name: '현재 공연 목록' })).toBeInTheDocument();
    expect(await screen.findByText('Spring Concert')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '구매한 공연' })).not.toBeInTheDocument();
    expect(screen.queryByText('다시 로그인해 주세요')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('이메일')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('비밀번호')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('역할')).not.toBeInTheDocument();
  });

  it.each(['FAN', 'ARTIST'] as const)('silently restores %s home with one refresh in StrictMode', async (role) => {
    const fetchMock = mockLoginFlow(role);
    render(<StrictMode><App /></StrictMode>);
    expect(await screen.findByRole('heading', { name: role === 'FAN' ? '팬 메인' : '아티스트 메인' })).toBeInTheDocument();
    expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/api/auth/refresh'))).toHaveLength(1);
    const profile = fetchMock.mock.calls.find(([url]) => String(url).endsWith('/api/users/me'))!;
    expect(new Headers(profile[1]?.headers).get('Authorization')).toBe('Bearer access-token');
  });

  it.each([true, false])('handles a startup REFRESH_RETRY with one retry (success: %s)', async (success) => {
    let refreshes = 0;
    mockLoginFlow('FAN', (url) => {
      if (!url.endsWith('/api/auth/refresh')) return;
      refreshes++;
      return Promise.resolve(refreshes === 2 && success
        ? jsonResponse({ accessToken: 'access-token', tokenType: 'Bearer' })
        : errorResponse(401, 'REFRESH_RETRY', 'already consumed'));
    });
    render(<StrictMode><App /></StrictMode>);
    if (success) {
      expect(await screen.findByRole('heading', { name: '팬 메인' })).toBeInTheDocument();
    } else {
      expect(await screen.findByRole('button', { name: 'Google로 로그인' })).toBeInTheDocument();
      expect(screen.getByText('다시 로그인해 주세요')).toBeInTheDocument();
    }
    expect(refreshes).toBe(2);
  });

  it('shows an error with retry, not login, when startup refresh returns 500', async () => {
    let refreshes = 0;
    mockLoginFlow('FAN', (url) => {
      if (!url.endsWith('/api/auth/refresh')) return;
      refreshes++;
      return Promise.resolve(refreshes === 1 ? errorResponse(500, 'INTERNAL_SERVER_ERROR', 'down')
        : jsonResponse({ accessToken: 'access-token', tokenType: 'Bearer' }));
    });
    render(<App />);
    expect(await screen.findByText('서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Google로 로그인' })).not.toBeInTheDocument();
    expect(refreshes).toBe(1);
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    expect(await screen.findByRole('heading', { name: '팬 메인' })).toBeInTheDocument();
    expect(refreshes).toBe(2);
  });

  it('does not go to login when startup refresh fails with a network error', async () => {
    mockLoginFlow('FAN', (url) => url.endsWith('/api/auth/refresh')
      ? Promise.reject(new TypeError('Failed to fetch')) : undefined);
    render(<App />);
    expect(await screen.findByRole('button', { name: '다시 시도' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Google로 로그인' })).not.toBeInTheDocument();
  });

  it('keeps the session and shows an error when in-use refresh fails with 5xx', async () => {
    let refreshes = 0;
    let ticketLookups = 0;
    mockLoginFlow('FAN', (url) => {
      if (url.endsWith('/api/auth/refresh')) {
        refreshes++;
        return refreshes === 1 ? undefined : Promise.resolve(errorResponse(502, 'BAD_GATEWAY', 'bad gateway'));
      }
      if (url.endsWith('/api/tickets/me') && ++ticketLookups > 1) return Promise.resolve(errorResponse(401, 'UNAUTHORIZED', 'expired'));
    });
    render(<App />);
    await screen.findByRole('heading', { name: '팬 메인' });
    fireEvent.click(screen.getByRole('button', { name: '구매한 공연' }));
    expect(await screen.findByText('bad gateway')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '팬 메인' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Google로 로그인' })).not.toBeInTheDocument();
    expect(refreshes).toBe(2);
  });

  it('navigates the whole page to Google login', async () => {
    mockLoginFlow('FAN', (url) => url.endsWith('/api/auth/refresh')
      ? Promise.resolve(errorResponse(401, 'REAUTH_REQUIRED', 'invalid')) : undefined);
    const assign = vi.fn();
    vi.stubGlobal('location', { assign });
    render(<App />);
    fireEvent.click(await screen.findByRole('button', { name: 'Google로 로그인' }));
    expect(assign).toHaveBeenCalledWith('/api/auth/google/login');
  });

  it('keeps the session and displays an error when logout fails', async () => {
    const fetchMock = mockLoginFlow('FAN', (url) => url.endsWith('/api/auth/logout')
      ? Promise.resolve(errorResponse(500, 'INTERNAL_SERVER_ERROR', 'failed')) : undefined);
    render(<App />);
    await screen.findByRole('heading', { name: '팬 메인' });
    fireEvent.click(screen.getByRole('button', { name: '로그아웃' }));
    expect(await screen.findByText('서버 오류가 발생했습니다. 잠시 후 다시 시도하세요.')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '팬 메인' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '구매한 공연' }));
    await screen.findByText('A구역 1행 1번');
    const call = fetchMock.mock.calls.find(([url]) => String(url).endsWith('/api/tickets/me'))!;
    expect(new Headers(call[1]?.headers).get('Authorization')).toBe('Bearer access-token');
  });

  it('returns to login when logout finds the session already invalid', async () => {
    mockLoginFlow('FAN', (url) => url.endsWith('/api/auth/logout')
      ? Promise.resolve(errorResponse(401, 'REAUTH_REQUIRED', 'Session is invalid.')) : undefined);
    render(<App />);
    await screen.findByRole('heading', { name: '팬 메인' });
    fireEvent.click(screen.getByRole('button', { name: '로그아웃' }));
    expect(await screen.findByRole('button', { name: 'Google로 로그인' })).toBeInTheDocument();
    expect(screen.getByText('이미 로그아웃된 세션입니다')).toBeInTheDocument();
  });

  it('clears the session only after successful logout', async () => {
    mockLoginFlow('FAN', (url) => url.endsWith('/api/auth/logout')
      ? Promise.resolve(jsonResponse(undefined, 204)) : undefined);
    render(<App />);
    await screen.findByRole('heading', { name: '팬 메인' });
    fireEvent.click(screen.getByRole('button', { name: '로그아웃' }));
    expect(await screen.findByRole('button', { name: 'Google로 로그인' })).toBeInTheDocument();
    expect(screen.getByText('로그아웃되었습니다.')).toBeInTheDocument();
  });

  it('confirms withdrawal and returns to login on success', async () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    const fetchMock = mockLoginFlow('FAN', (url, options) => url.endsWith('/api/users/me') && options?.method === 'DELETE'
      ? Promise.resolve(jsonResponse(undefined, 204)) : undefined);
    render(<App />);
    await screen.findByRole('heading', { name: '팬 메인' });
    fireEvent.click(screen.getByRole('button', { name: '회원 탈퇴' }));
    expect(fetchMock.mock.calls.some(([, options]) => options?.method === 'DELETE')).toBe(false);
    confirm.mockReturnValue(true);
    fireEvent.click(screen.getByRole('button', { name: '회원 탈퇴' }));
    expect(confirm).toHaveBeenCalled();
    expect(await screen.findByRole('button', { name: 'Google로 로그인' })).toBeInTheDocument();
    expect(screen.getByText('회원 탈퇴가 완료되었습니다.')).toBeInTheDocument();
    const call = fetchMock.mock.calls.find(([, options]) => options?.method === 'DELETE')!;
    expect(new Headers(call[1]?.headers).get('Authorization')).toBe('Bearer access-token');
  });

  it.each(['REAUTH_REQUIRED', 'REFRESH_RETRY', 'retry-server', 'request-again'])('returns to login without a loop after %s', async (failure) => {
    let refreshes = 0;
    let ticketLookups = 0;
    const fetchMock = mockLoginFlow('FAN', (url) => {
      if (url.endsWith('/api/auth/refresh')) {
        refreshes++;
        if (refreshes === 1 || failure === 'request-again') return Promise.resolve(jsonResponse({ accessToken: 'token-' + refreshes, tokenType: 'Bearer' }));
        return Promise.resolve(failure === 'retry-server' && refreshes === 3
          ? errorResponse(500, 'INTERNAL_SERVER_ERROR', 'failed')
          : errorResponse(401, failure === 'REAUTH_REQUIRED' ? failure : 'REFRESH_RETRY', 'invalid'));
      }
      if (url.endsWith('/api/tickets/me') && ++ticketLookups > 1) return Promise.resolve(errorResponse(401, 'UNAUTHORIZED', 'expired'));
    });
    render(<App />);
    await screen.findByRole('heading', { name: '팬 메인' });
    fireEvent.click(screen.getByRole('button', { name: '구매한 공연' }));
    expect(await screen.findByRole('button', { name: 'Google로 로그인' })).toBeInTheDocument();
    expect(screen.getByText('다시 로그인해 주세요')).toBeInTheDocument();
    expect(refreshes).toBe(['REFRESH_RETRY', 'retry-server'].includes(failure) ? 3 : 2);
    expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/api/tickets/me'))).toHaveLength(failure === 'request-again' ? 3 : 2);
  });

  it('loads section summary before loading seats for a selected section', async () => {
    const fetchMock = mockLoginFlow('FAN');

    render(<App />);
    await screen.findByRole('heading', { name: '팬 메인' });

    fireEvent.click(screen.getByRole('button', { name: '공연 새로고침' }));

    await enterPurchase();

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith('/api/concerts/1/seats/availability', expect.any(Object));
    });

    fireEvent.click(screen.getByRole('button', { name: '구역 및 좌석 선택' }));

    expect(screen.getByText(/14951/)).toBeInTheDocument();

    fireEvent.click(await screen.findByRole('button', { name: /A.*석/ }));

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith('/api/concerts/1/seats/availability/sections/A', expect.any(Object));
    });
    expect(await screen.findByText('1-1')).toBeInTheDocument();
    expect(screen.getByText('1-2').closest('button')).toBeDisabled();
  });

  it('shows purchased concerts with concert and seat labels', async () => {
    mockLoginFlow('FAN');

    render(<App />);
    await screen.findByRole('heading', { name: '팬 메인' });

    fireEvent.click(screen.getByRole('button', { name: '구매한 공연' }));

    expect(await screen.findAllByText('Spring Concert')).toHaveLength(2);
    expect(screen.getByText('A구역 1행 1번')).toBeInTheDocument();
    expect(screen.getByText('구매 가격 99,000원')).toBeInTheDocument();
    expect(screen.queryByText(/좌석 #/)).not.toBeInTheDocument();
  });

  it('alerts and closes the seat modal after purchase succeeds', async () => {
    const fetchMock = mockLoginFlow('FAN');
    const alertMock = vi.fn();
    vi.stubGlobal('alert', alertMock);

    render(<App />);
    await screen.findByRole('heading', { name: '팬 메인' });
    fireEvent.click(screen.getByRole('button', { name: '공연 새로고침' }));
    await enterPurchase();
    fireEvent.click(screen.getByRole('button', { name: '구역 및 좌석 선택' }));
    fireEvent.click(await screen.findByRole('button', { name: /A.*석/ }));
    fireEvent.click((await screen.findByText('1-1')).closest('button')!);
    fireEvent.click(screen.getByRole('button', { name: /선택 좌석 예매하기/ }));

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith('/api/tickets', expect.any(Object));
      expect(alertMock).toHaveBeenCalledWith('구매가 완료되었습니다.');
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });
    expect(await screen.findByRole('heading', { name: '팬 메인' })).toBeInTheDocument();
    expect(screen.getAllByText('구매 가격 99,000원')).toHaveLength(2);
  });

  it('sends local concert inputs as UTC instants', async () => {
    const fetchMock = mockLoginFlow('ARTIST', (url, options) => {
      if (url.endsWith('/api/concerts') && options?.method === 'POST') {
        return Promise.resolve(jsonResponse({ concertId: 2, artistId: 7, status: 'BEFORE_OPEN', ...JSON.parse(String(options.body)) }, 201));
      }
    });
    render(<App />);
    await screen.findByRole('heading', { name: '아티스트 메인' });
    fireEvent.click(screen.getByRole('button', { name: '공연 등록하기' }));
    await screen.findByText('공연이 등록되었습니다.');
    const call = fetchMock.mock.calls.find(([url, options]) => String(url).endsWith('/api/concerts') && options?.method === 'POST')!;
    const body = JSON.parse(String(call[1]?.body));
    for (const [field, local] of Object.entries({ concertDate: '2026-06-01T19:00', ticketOpenDate: '2026-05-20T12:00', ticketCloseDate: '2026-05-31T23:59' })) {
      expect(body[field]).toBe(new Date(local).toISOString());
      expect(body[field]).toMatch(/Z$/);
    }
  });

  it('displays UTC concert and sale times in the user local timezone', async () => {
    mockLoginFlow('FAN');
    render(<App />);
    await screen.findByRole('heading', { name: '팬 메인' });
    fireEvent.click(screen.getByRole('button', { name: '공연 새로고침' }));
    await screen.findByRole('button', { name: '구매하기' });
    const format = (value: string) => new Intl.DateTimeFormat('ko-KR', {
      month: 'long', day: 'numeric', hour: '2-digit', minute: '2-digit',
    }).format(new Date(value));
    expect(screen.getByText(format('2026-06-01T10:00:00Z'))).toBeInTheDocument();
    await enterPurchase();
    await screen.findByText('Spring Concert의 구역별 예매 가능 좌석을 불러왔습니다.');
    for (const value of ['2026-06-01T10:00:00Z', '2026-05-20T03:00:00Z', '2026-05-31T14:59:00Z']) {
      expect(screen.getByText(format(value))).toBeInTheDocument();
    }
  });

  it.each([
    [404, 'RESOURCE_NOT_FOUND', '요청한 정보를 찾을 수 없습니다.'],
    [405, 'METHOD_NOT_ALLOWED', '지원하지 않는 요청입니다.'],
    [415, 'UNSUPPORTED_MEDIA_TYPE', '지원하지 않는 데이터 형식입니다.'],
  ])('shows general guidance for HTTP %s', async (status, code, message) => {
    mockLoginFlow('FAN', (url) => url.endsWith('/api/concerts')
      ? Promise.resolve(errorResponse(status, code, 'server message')) : undefined);
    render(<App />);
    await screen.findByRole('heading', { name: '팬 메인' });
    fireEvent.click(screen.getByRole('button', { name: '공연 새로고침' }));
    expect(await screen.findByText(message)).toBeInTheDocument();
  });

  it('keeps section B when an aborted A response arrives late', async () => {
    let resolveA!: (response: ReturnType<typeof jsonResponse>) => void;
    let signalA: AbortSignal | undefined;
    mockLoginFlow('FAN', (url, options) => {
      if (url.endsWith('/sections/A')) {
        signalA = options?.signal as AbortSignal;
        return new Promise((resolve) => { resolveA = resolve; });
      }
      if (url.endsWith('/sections/B')) return Promise.resolve(sectionResponse('B', 3));
    });
    await openSeatModal();
    fireEvent.click(screen.getByRole('button', { name: /A.*석/ }));
    fireEvent.click(screen.getByRole('button', { name: /B.*석/ }));
    expect(await screen.findByText('1-3')).toBeInTheDocument();
    await act(async () => resolveA(sectionResponse('A', 1)));
    expect(signalA?.aborted).toBe(true);
    expect(screen.getByText('1-3')).toBeInTheDocument();
    expect(screen.queryByText('1-1')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /선택 좌석 예매하기/ })).toBeEnabled();
  });

  it.each([
    ['network', undefined, undefined, '구매 응답을 받지 못했습니다. 내 티켓에서 구매 결과를 확인해 주세요.', true],
    ['processing', 409, 'REQUEST_IN_PROGRESS', '예매 요청을 처리 중입니다. 잠시 후 다시 시도하세요.', true],
    ['server', 500, 'INTERNAL_SERVER_ERROR', '서버 오류가 발생했습니다. 잠시 후 다시 시도하세요.', true],
    ['sale', 409, 'CONCERT_NOT_ON_SALE', '현재 예매 기간이 아니거나 취소된 공연입니다.', false],
    ['sold', 409, 'SEAT_NOT_AVAILABLE', '선택한 좌석 중 이미 판매된 좌석이 있습니다', false],
    ['lock', 409, 'SEAT_LOCK_FAILED', '좌석을 확보하지 못했습니다. 잠시 후 다시 시도하세요.', false],
    ['conflict', 422, 'IDEMPOTENCY_CONFLICT', '예매 요청 정보가 이전 요청과 다릅니다. 다시 시도하세요.', false],
  ])('handles %s and reuses only unconfirmed purchase keys', async (_, status, code, message, reuse) => {
    const keys: string[] = [];
    mockLoginFlow('FAN', (url, options) => {
      if (!url.endsWith('/api/tickets')) return;
      keys.push(new Headers(options?.headers).get('Idempotency-Key')!);
      if (keys.length === 1) {
        return status === undefined
          ? Promise.reject(new TypeError('Failed to fetch'))
          : Promise.resolve(errorResponse(status, code!, 'server message'));
      }
      return Promise.resolve(jsonResponse([{ ticketId: 11, seatId: 1, purchasePrice: 99000 }], 201));
    });
    vi.stubGlobal('alert', vi.fn());
    await openSeatModal();
    fireEvent.click(screen.getByRole('button', { name: /A.*석/ }));
    fireEvent.click((await screen.findByText('1-1')).closest('button')!);
    fireEvent.click(screen.getByRole('button', { name: /선택 좌석 예매하기/ }));
    expect(await screen.findByText(message)).toBeInTheDocument();
    if (status === undefined) {
      await enterPurchase();
      fireEvent.click(screen.getByRole('button', { name: /A.*석/ }));
      fireEvent.click((await screen.findByText('1-1')).closest('button')!);
    }
    if (code === 'SEAT_NOT_AVAILABLE') fireEvent.click((await screen.findByText('1-1')).closest('button')!);
    await waitFor(() => expect(screen.getByRole('button', { name: /선택 좌석 예매하기/ })).toBeEnabled());
    fireEvent.click(screen.getByRole('button', { name: /선택 좌석 예매하기/ }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(keys[0] === keys[1]).toBe(reuse);
    // Refresh the seat view to simulate another purchase after a 201 response.
    await enterPurchase();
    fireEvent.click(screen.getByRole('button', { name: '구역 및 좌석 선택' }));
    fireEvent.click(screen.getByRole('button', { name: /A.*석/ }));
    fireEvent.click((await screen.findByText('1-1')).closest('button')!);
    fireEvent.click(screen.getByRole('button', { name: /선택 좌석 예매하기/ }));
    await waitFor(() => expect(keys).toHaveLength(3));
    expect(keys[2]).not.toBe(keys[1]);
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });

  it('creates a new key after switching seats during an unconfirmed attempt', async () => {
    const keys: string[] = [];
    mockLoginFlow('FAN', (url, options) => {
      if (url.endsWith('/sections/B')) return Promise.resolve(sectionResponse('B', 3));
      if (url.endsWith('/api/tickets')) {
        keys.push(new Headers(options?.headers).get('Idempotency-Key')!);
        return Promise.reject(new TypeError('Failed to fetch'));
      }
    });
    await openSeatModal();
    for (const [section, seat] of [['A', '1-1'], ['B', '1-3']]) {
      if (section === 'B') await enterPurchase();
      fireEvent.click(screen.getByRole('button', { name: new RegExp(`${section}.*석`) }));
      fireEvent.click((await screen.findByText(seat)).closest('button')!);
      fireEvent.click(screen.getByRole('button', { name: /선택 좌석 예매하기/ }));
      await screen.findByText('구매 응답을 받지 못했습니다. 내 티켓에서 구매 결과를 확인해 주세요.');
    }
    expect(keys).toHaveLength(2);
    expect(keys[0]).not.toBe(keys[1]);
  });

  it.each([
    ['예매 오픈', '', '예매 일정은 오픈 < 마감 ≤ 공연일 순서로 입력하세요.'],
    ['예매 오픈', '2026-05-31T23:59', '예매 일정은 오픈 < 마감 ≤ 공연일 순서로 입력하세요.'],
    ['예매 마감', '2026-06-02T19:00', '예매 일정은 오픈 < 마감 ≤ 공연일 순서로 입력하세요.'],
    ['공연명', 'a'.repeat(101), '공연명은 100자, 장소는 255자 이하로 입력하세요.'],
    ['장소', 'a'.repeat(256), '공연명은 100자, 장소는 255자 이하로 입력하세요.'],
  ])('blocks invalid concert field %s before submitting', async (label, value, message) => {
    const fetchMock = mockLoginFlow('ARTIST');
    render(<App />);
    await screen.findByRole('heading', { name: '아티스트 메인' });
    fireEvent.change(screen.getByLabelText(label), { target: { value } });
    fireEvent.click(screen.getByRole('button', { name: '공연 등록하기' }));
    expect(await screen.findByText(message)).toBeInTheDocument();
    expect(fetchMock.mock.calls.some(([url, options]) => String(url).endsWith('/api/concerts') && options?.method === 'POST')).toBe(false);
  });

  it('allows closing at concert time and displays server INVALID_REQUEST messages', async () => {
    const fetchMock = mockLoginFlow('ARTIST', (url, options) => {
      if (url.endsWith('/api/concerts') && options?.method === 'POST') {
        return Promise.resolve(errorResponse(400, 'INVALID_REQUEST', 'Request body is invalid.'));
      }
    });
    render(<App />);
    await screen.findByRole('heading', { name: '아티스트 메인' });
    fireEvent.change(screen.getByLabelText('공연명'), { target: { value: 'a'.repeat(100) } });
    fireEvent.change(screen.getByLabelText('장소'), { target: { value: 'a'.repeat(255) } });
    fireEvent.change(screen.getByLabelText('예매 마감'), { target: { value: '2026-06-01T19:00' } });
    fireEvent.click(screen.getByRole('button', { name: '공연 등록하기' }));
    expect(await screen.findByText('Request body is invalid.')).toBeInTheDocument();
    expect(fetchMock.mock.calls.some(([, options]) => options?.method === 'POST' && String(options.body).includes('ticketCloseDate'))).toBe(true);
  });

});

describe('Queue navigation', () => {
  afterEach(() => { auth.clear(); vi.useRealTimers(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });

  it('registers, enters and opens the existing purchase screen without persisting entry tokens', async () => {
    const storage = [vi.spyOn(Storage.prototype, 'setItem')];
    const fetchMock = mockLoginFlow('FAN', (url) => {
      if (url.endsWith('/api/queue/1/enter')) return Promise.resolve(jsonResponse({ concertId: 1, status: 'ACTIVE', entryToken: 'entry-secret', expiresAt: '2099-01-01T00:05:00Z', serverTime: '2099-01-01T00:00:00Z' }));
      if (url.endsWith('/api/queue/1')) return Promise.resolve(jsonResponse({ concertId: 1, status: 'ADMITTED', rank: 0, behind: 0, nextPollAfterMs: 0, estimatedWaitSeconds: null, expiresAt: '2099-01-01T00:01:00Z', serverTime: '2099-01-01T00:00:00Z' }));
    });
    render(<App />);
    await screen.findByRole('heading', { name: '팬 메인' });
    fireEvent.click(screen.getByRole('button', { name: '공연 새로고침' }));
    fireEvent.click(await screen.findByRole('button', { name: '구매하기' }));
    fireEvent.click(await screen.findByRole('button', { name: '입장하기' }));
    expect(await screen.findByRole('button', { name: '구역 및 좌석 선택' })).toBeInTheDocument();
    expect(getEntryToken(1)).toBe('entry-secret');
    expect(storage[0]).not.toHaveBeenCalled();
    const registration = fetchMock.mock.calls.find(([url]) => String(url).endsWith('/api/queue/1'))!;
    expect(registration[1]?.method).toBe('POST');
    expect(new Headers(registration[1]?.headers).get('Authorization')).toBe('Bearer access-token');
  });

  it.each(['BEFORE_OPEN', 'CLOSED', 'CANCELLED'])('disables queue registration for %s concerts', async status => {
    mockLoginFlow('FAN', url => url.endsWith('/api/concerts') ? Promise.resolve(jsonResponse([{ concertId: 1, title: 'Closed Concert', venue: 'Park', concertDate: '2030-01-01T00:00:00Z', ticketOpenDate: '2030-01-01T00:00:00Z', status }])) : undefined);
    render(<App />); await screen.findByRole('heading', { name: '팬 메인' });
    fireEvent.click(screen.getByRole('button', { name: '공연 새로고침' }));
    expect(await screen.findByRole('button', { name: { BEFORE_OPEN: '오픈 예정', CLOSED: '판매 마감', CANCELLED: '취소된 공연' }[status] })).toBeDisabled();
    expect(screen.queryByRole('button', { name: '구매하기' })).not.toBeInTheDocument();
  });

  it.each(['home', 'logout'])('stops polling on %s navigation', async destination => {
    const fetchMock = mockLoginFlow('FAN', url => url.endsWith('/api/queue/1') ? Promise.resolve(jsonResponse({ concertId: 1, status: 'WAITING', rank: 1, behind: 2, nextPollAfterMs: 3000, estimatedWaitSeconds: null, expiresAt: null })) : undefined);
    render(<App />); await screen.findByRole('heading', { name: '팬 메인' });
    fireEvent.click(screen.getByRole('button', { name: '공연 새로고침' }));
    await screen.findByRole('button', { name: '구매하기' });
    vi.useFakeTimers();
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '구매하기' })); });
    expect(screen.getByText(/내 순번 1번/)).toBeInTheDocument();
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: destination === 'home' ? '팬 메인' : '로그아웃' })); });
    await act(async () => { await vi.advanceTimersByTimeAsync(30000); });
    expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/status'))).toHaveLength(0);
    if (destination === 'logout') expect(screen.getByRole('button', { name: 'Google로 로그인' })).toBeInTheDocument();
  });
});

describe('Phase 5 purchase flow', () => {
  afterEach(() => { auth.clear(); sessionStorage.clear(); vi.useRealTimers(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });

  it('stores only the concert id for guest purchase and resumes its queue after Google login', async () => {
    const assign = vi.fn();
    vi.stubGlobal('location', { assign });
    const storage = vi.spyOn(Storage.prototype, 'setItem');
    const guest = mockLoginFlow('FAN', url => url.endsWith('/refresh') ? Promise.resolve(errorResponse(401, 'REAUTH_REQUIRED', 'no session')) : undefined);
    const view = render(<App />);
    fireEvent.click(await screen.findByRole('button', { name: '구매하기' }));
    expect(assign).toHaveBeenCalledWith('/api/auth/google/login');
    expect(storage.mock.calls).toEqual([['pendingConcertId', '1']]);
    expect(localStorage.length).toBe(0);
    expect(guest.mock.calls.some(([url]) => String(url).includes('/queue/'))).toBe(false);
    view.unmount();
    const signedIn = mockLoginFlow('FAN');
    render(<StrictMode><App /></StrictMode>);
    expect(await screen.findByRole('button', { name: '입장하기' })).toBeInTheDocument();
    expect(sessionStorage.getItem('pendingConcertId')).toBeNull();
    expect(signedIn.mock.calls.filter(([url]) => String(url).endsWith('/api/queue/1'))).toHaveLength(1);
    expect(screen.queryByRole('button', { name: '대기열 참여' })).not.toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('shows all four public sale actions and the local opening time', async () => {
    const opening = '2030-01-01T00:00:00Z';
    const fetchMock = mockLoginFlow('FAN', url => {
      if (url.endsWith('/refresh')) return Promise.resolve(errorResponse(401, 'REAUTH_REQUIRED', 'no session'));
      if (url.endsWith('/api/concerts')) return Promise.resolve(jsonResponse(['BEFORE_OPEN', 'ON_SALE', 'CLOSED', 'CANCELLED'].map((status, i) => ({ concertId: i + 1, title: status, venue: 'Park', concertDate: opening, ticketOpenDate: opening, ticketCloseDate: opening, status }))));
    });
    render(<App />);
    expect(await screen.findByRole('button', { name: '구매하기' })).toBeEnabled();
    for (const label of ['오픈 예정', '판매 마감', '취소된 공연']) expect(screen.getByRole('button', { name: label })).toBeDisabled();
    const local = new Intl.DateTimeFormat('ko-KR', { month: 'long', day: 'numeric', hour: '2-digit', minute: '2-digit' }).format(new Date(opening));
    expect(screen.getByText(`예매 오픈 ${local}`)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '대기열 참여' })).not.toBeInTheDocument();
    const request = fetchMock.mock.calls.find(([url]) => String(url).endsWith('/api/concerts'))![1]!;
    expect(new Headers(request.headers).has('Authorization')).toBe(false);
  });

  it.each(['REQUIRED', 'INVALID', 'EXPIRED', 'MISMATCH'])('handles ENTRY_TOKEN_%s and retains the same purchase key after reentry', async code => {
    const requests: RequestInit[] = [];
    const fetchMock = mockLoginFlow('FAN', (url, options) => {
      if (!url.endsWith('/api/tickets')) return;
      requests.push(options!);
      return Promise.resolve(requests.length === 1 ? errorResponse(403, 'ENTRY_TOKEN_' + code, 'token error') : jsonResponse([{ ticketId: 11, seatId: 1, purchasePrice: 99000 }], 201));
    });
    vi.stubGlobal('alert', vi.fn());
    await openSeatModal();
    fireEvent.click(screen.getByRole('button', { name: /A.*석/ }));
    fireEvent.click((await screen.findByText('1-1')).closest('button')!);
    fireEvent.click(screen.getByRole('button', { name: /선택 좌석 예매하기/ }));
    expect(await screen.findByText(code === 'EXPIRED' ? '이용 시간이 끝났습니다. 다시 대기열에 서 주세요' : '대기열을 통해 입장해 주세요')).toBeInTheDocument();
    expect(getEntryToken(1)).toBeNull();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: code === 'EXPIRED' ? '다시 줄 서기' : '대기열로' }));
    fireEvent.click(await screen.findByRole('button', { name: '입장하기' }));
    fireEvent.click(await screen.findByRole('button', { name: /A.*석/ }));
    fireEvent.click((await screen.findByText('1-1')).closest('button')!);
    fireEvent.click(screen.getByRole('button', { name: /선택 좌석 예매하기/ }));
    await screen.findByRole('heading', { name: '팬 메인' });
    expect(requests).toHaveLength(2);
    expect(new Headers(requests[0].headers).get('Idempotency-Key')).toBe(new Headers(requests[1].headers).get('Idempotency-Key'));
    for (const request of requests) {
      expect(new Headers(request.headers).get('X-Entry-Token')).toBe('entry-secret');
      expect(new Headers(request.headers).get('Authorization')).toBe('Bearer access-token');
    }
    expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/refresh'))).toHaveLength(1);
  });

  it.each([120000, -120000])('expires ACTIVE at server time despite %i ms clock skew', async skew => {
    let elapsed = 0;
    vi.spyOn(performance, 'now').mockImplementation(() => elapsed);
    vi.spyOn(Date, 'now').mockReturnValue(Date.parse('2030-01-01T00:00:00Z') + skew);
    const fetchMock = mockLoginFlow('FAN', url => url.endsWith('/enter') ? Promise.resolve(jsonResponse({ concertId: 1, status: 'ACTIVE', entryToken: 'entry-secret', serverTime: '2030-01-01T00:00:00Z', expiresAt: '2030-01-01T00:00:02Z' })) : undefined);
    render(<App />);
    await screen.findByRole('button', { name: '구매하기' });
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '구매하기' })); });
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '입장하기' })); });
    expect(screen.getByText('남은 시간 0:02')).toBeInTheDocument();
    expect(getEntryToken(1)).toBe('entry-secret');
    elapsed = 1000;
    await act(async () => { await vi.advanceTimersByTimeAsync(1000); });
    expect(screen.getByText('남은 시간 0:01')).toBeInTheDocument();
    elapsed = 2000;
    await act(async () => { await vi.advanceTimersByTimeAsync(1000); });
    expect(screen.getByText('이용 시간이 끝났습니다. 다시 대기열에 서 주세요')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '다시 줄 서기' })).toBeInTheDocument();
    expect(getEntryToken(1)).toBeNull();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(fetchMock.mock.calls.some(([url]) => String(url).endsWith('/api/tickets'))).toBe(false);
  });

  it('redirects an entry response without a valid memory token back to the queue', async () => {
    const fetchMock = mockLoginFlow('FAN');
    vi.spyOn(api, 'enterQueue').mockResolvedValue({ concertId: 1, status: 'ACTIVE', entryToken: 'unretained', expiresAt: '2099-01-01T00:05:00Z', serverTime: '2099-01-01T00:00:00Z' });
    render(<App />);
    fireEvent.click(await screen.findByRole('button', { name: '구매하기' }));
    fireEvent.click(await screen.findByRole('button', { name: '입장하기' }));
    await waitFor(() => expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/api/queue/1'))).toHaveLength(2));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(fetchMock.mock.calls.some(([url]) => String(url).includes('/seats/'))).toBe(false);
  });

  it('recovers a lost purchase response through AT-only ticket lookup', async () => {
    const fetchMock = mockLoginFlow('FAN', url => {
      if (url.endsWith('/api/tickets')) { clearEntryToken(1); return Promise.reject(new TypeError('Failed to fetch')); }
    });
    await openSeatModal();
    fireEvent.click(screen.getByRole('button', { name: /A.*석/ }));
    fireEvent.click((await screen.findByText('1-1')).closest('button')!);
    fireEvent.click(screen.getByRole('button', { name: /선택 좌석 예매하기/ }));
    await screen.findByRole('heading', { name: '팬 메인' });
    expect(await screen.findByText('A구역 1행 1번')).toBeInTheDocument();
    expect(screen.getByText('구매 응답을 받지 못했습니다. 내 티켓에서 구매 결과를 확인해 주세요.')).toBeInTheDocument();
    const request = fetchMock.mock.calls.find(([url]) => String(url).endsWith('/api/tickets/me'))![1]!;
    expect(new Headers(request.headers).get('Authorization')).toBe('Bearer access-token');
    expect(new Headers(request.headers).has('X-Entry-Token')).toBe(false);
  });

  it('retains the token when a lost response lookup has no purchased tickets', async () => {
    const fetchMock = mockLoginFlow('FAN', url => {
      if (url.endsWith('/api/tickets/me')) return Promise.resolve(jsonResponse([]));
      if (url.endsWith('/api/tickets')) return Promise.reject(new TypeError('Failed to fetch'));
    });
    await openSeatModal();
    fireEvent.click(screen.getByRole('button', { name: /A.*석/ }));
    fireEvent.click((await screen.findByText('1-1')).closest('button')!);
    fireEvent.click(screen.getByRole('button', { name: /선택 좌석 예매하기/ }));
    await screen.findByText('구매 응답을 받지 못했습니다. 내 티켓에서 구매 결과를 확인해 주세요.');
    expect(getEntryToken(1)).toBe('entry-secret');
    expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/api/tickets'))).toHaveLength(1);
    await enterPurchase();
    fireEvent.click(screen.getByRole('button', { name: '구역 및 좌석 선택' }));
    fireEvent.click(await screen.findByRole('button', { name: /A.*석/ }));
    fireEvent.click((await screen.findByText('1-1')).closest('button')!);
    fireEvent.click(screen.getByRole('button', { name: /선택 좌석 예매하기/ }));
    await waitFor(() => expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/api/tickets'))).toHaveLength(2));
    const keys = fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/api/tickets')).map(([, o]) => new Headers(o?.headers).get('Idempotency-Key'));
    expect(keys[0]).toBe(keys[1]);
  });

  it('keeps selections across sections and normalizes retry combinations', async () => {
    const requests: RequestInit[] = [];
    mockLoginFlow('FAN', (url, options) => {
      if (url.endsWith('/sections/B')) return Promise.resolve(sectionResponse('B', 3));
      if (url.endsWith('/api/tickets')) {
        requests.push(options!);
        return Promise.resolve(errorResponse(409, 'REQUEST_IN_PROGRESS', 'processing'));
      }
    });
    await openSeatModal();
    fireEvent.click(screen.getByRole('button', { name: /B.*석/ }));
    fireEvent.click((await screen.findByText('1-3')).closest('button')!);
    fireEvent.click(screen.getByRole('button', { name: /A.*석/ }));
    fireEvent.click((await screen.findByText('1-1')).closest('button')!);
    expect(screen.getAllByText('B구역 1행 3열, A구역 1행 1열')).toHaveLength(2);
    expect(screen.getAllByText('240,000원')).toHaveLength(2);
    fireEvent.click(screen.getByRole('button', { name: /선택 좌석 예매하기/ }));
    await screen.findByText('예매 요청을 처리 중입니다. 잠시 후 다시 시도하세요.');
    fireEvent.click(screen.getByText('1-1').closest('button')!);
    fireEvent.click(screen.getByText('1-1').closest('button')!);
    fireEvent.click(screen.getByRole('button', { name: /선택 좌석 예매하기/ }));
    await waitFor(() => expect(requests).toHaveLength(2));
    expect(requests.map(request => JSON.parse(String(request.body)))).toEqual([{ seatIds: [1, 3] }, { seatIds: [1, 3] }]);
    expect(new Headers(requests[0].headers).get('Idempotency-Key')).toBe(new Headers(requests[1].headers).get('Idempotency-Key'));
    await waitFor(() => expect(screen.getByRole('button', { name: /선택 좌석 예매하기/ })).toBeEnabled());
    fireEvent.click(screen.getByText('1-1').closest('button')!);
    fireEvent.click(screen.getByRole('button', { name: /선택 좌석 예매하기/ }));
    await waitFor(() => expect(requests).toHaveLength(3));
    expect(JSON.parse(String(requests[2].body))).toEqual({ seatIds: [3] });
    expect(new Headers(requests[2].headers).get('Idempotency-Key')).not.toBe(new Headers(requests[1].headers).get('Idempotency-Key'));
  });

  it('adds every purchased ticket, consumes the entry token and registers WAITING on repurchase', async () => {
    let registrations = 0;
    const fetchMock = mockLoginFlow('FAN', (url, options) => {
      if (url.endsWith('/api/tickets/me')) return Promise.resolve(jsonResponse([]));
      if (url.endsWith('/sections/B')) return Promise.resolve(sectionResponse('B', 3));
      if (url.endsWith('/api/tickets')) return Promise.resolve(jsonResponse([{ ticketId: 11, seatId: 1, purchasePrice: 120000 }, { ticketId: 12, seatId: 3, purchasePrice: 120000 }], 201));
      if (url.endsWith('/api/queue/1') && options?.method === 'POST' && ++registrations === 2) return Promise.resolve(jsonResponse({ concertId: 1, status: 'WAITING', rank: 5, behind: 0, nextPollAfterMs: 3000, estimatedWaitSeconds: null, expiresAt: null }));
    });
    vi.stubGlobal('alert', vi.fn());
    await openSeatModal();
    fireEvent.click(screen.getByRole('button', { name: /A.*석/ }));
    fireEvent.click((await screen.findByText('1-1')).closest('button')!);
    fireEvent.click(screen.getByRole('button', { name: /B.*석/ }));
    fireEvent.click((await screen.findByText('1-3')).closest('button')!);
    fireEvent.click(screen.getByRole('button', { name: /선택 좌석 예매하기/ }));
    await screen.findByText('Ticket #12');
    expect(screen.getByText('Ticket #11')).toBeInTheDocument();
    expect(getEntryToken(1)).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '구매하기' }));
    expect(await screen.findByText(/내 순번 5번/)).toBeInTheDocument();
    expect(fetchMock.mock.calls.filter(([url, options]) => String(url).endsWith('/api/queue/1') && options?.method === 'POST')).toHaveLength(2);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('recalculates allowance on PURCHASE_LIMIT_EXCEEDED while retaining the entry token', async () => {
    let exceeded = false;
    mockLoginFlow('FAN', url => {
      if (url.endsWith('/api/tickets')) { exceeded = true; return Promise.resolve(errorResponse(409, 'PURCHASE_LIMIT_EXCEEDED', 'limit')); }
      if (url.endsWith('/api/tickets/me')) return Promise.resolve(jsonResponse(Array.from({ length: exceeded ? 4 : 0 }, (_, i) => ({ ticketId: i, concertId: 1, concertTitle: 'Owned', section: 'B', row: 1, col: i, purchasePrice: 1 }))));
    });
    await openSeatModal();
    fireEvent.click(screen.getByRole('button', { name: /A.*석/ }));
    fireEvent.click((await screen.findByText('1-1')).closest('button')!);
    fireEvent.click(screen.getByRole('button', { name: /선택 좌석 예매하기/ }));
    expect(await screen.findByText('공연당 최대 4매까지 구매할 수 있습니다')).toBeInTheDocument();
    expect(screen.getByText('이 공연은 0매 더 구매할 수 있습니다')).toBeInTheDocument();
    expect(getEntryToken(1)).toBe('entry-secret');
    expect(document.querySelectorAll('.seat-button.selected')).toHaveLength(0);
    fireEvent.click(screen.getByRole('button', { name: '팬 메인' }));
    expect(screen.getByRole('button', { name: '구매 한도(4매) 도달' })).toBeDisabled();
  });

  it('reloads sold seats and keeps the token for reselection', async () => {
    let sold = false;
    const fetchMock = mockLoginFlow('FAN', url => {
      if (url.endsWith('/api/tickets')) { sold = true; return Promise.resolve(errorResponse(409, 'SEAT_NOT_AVAILABLE', 'sold')); }
      if (sold && url.endsWith('/sections/A')) return Promise.resolve(jsonResponse({ section: 'A', seats: [{ seatId: 1, row: 1, col: 1, price: 120000, status: 'SOLD' }] }));
    });
    await openSeatModal();
    fireEvent.click(screen.getByRole('button', { name: /A.*석/ }));
    fireEvent.click((await screen.findByText('1-1')).closest('button')!);
    fireEvent.click(screen.getByRole('button', { name: /선택 좌석 예매하기/ }));
    await screen.findByText('선택한 좌석 중 이미 판매된 좌석이 있습니다');
    expect(screen.getByText('1-1').closest('button')).toBeDisabled();
    expect(getEntryToken(1)).toBe('entry-secret');
    expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/seats/availability'))).toHaveLength(2);
  });

  it.each([0, 2, 3])('limits selection to remaining allowance with %i owned tickets', async owned => {
    mockLoginFlow('FAN', url => {
      if (url.endsWith('/api/tickets/me')) return Promise.resolve(jsonResponse(Array.from({ length: owned }, (_, i) => ({ ticketId: i, concertId: 1, concertTitle: 'Owned', section: 'B', row: 1, col: i, purchasePrice: 1 }))));
      if (url.endsWith('/sections/A')) return Promise.resolve(jsonResponse({ section: 'A', seats: Array.from({ length: 5 }, (_, i) => ({ seatId: i + 1, row: 1, col: i + 1, price: 100, status: 'AVAILABLE' })) }));
    });
    await openSeatModal();
    expect(screen.getByText(`이 공연은 ${4 - owned}매 더 구매할 수 있습니다`)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /A.*석/ }));
    await screen.findByText('1-5');
    for (let i = 1; i <= 5; i++) fireEvent.click(screen.getByText(`1-${i}`).closest('button')!);
    expect(document.querySelectorAll('.seat-button.selected')).toHaveLength(4 - owned);
    expect(screen.getByRole('alert')).toHaveTextContent(`최대 ${4 - owned}석까지 선택할 수 있습니다.`);
    expect(screen.getByText(`선택 ${4 - owned}/${4 - owned}석 · ${4 - owned}매 더 구매 가능`)).toBeInTheDocument();
    vi.useFakeTimers();
    fireEvent.click(screen.getByText('1-5').closest('button')!);
    act(() => { vi.advanceTimersByTime(3100); });
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    vi.useRealTimers();
    fireEvent.click(screen.getByText('1-1').closest('button')!);
    expect(document.querySelectorAll('.seat-button.selected')).toHaveLength(3 - owned);
  });

  it('disables purchasing at four owned tickets without registering a queue', async () => {
    const fetchMock = mockLoginFlow('FAN', url => url.endsWith('/api/tickets/me') ? Promise.resolve(jsonResponse(Array.from({ length: 4 }, (_, i) => ({ ticketId: i, concertId: 1, concertTitle: 'Owned', section: 'A', row: 1, col: i, purchasePrice: 1 })))) : undefined);
    render(<App />);
    expect(await screen.findByRole('button', { name: '구매 한도(4매) 도달' })).toBeDisabled();
    expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/api/tickets/me'))).toHaveLength(1);
    expect(fetchMock.mock.calls.some(([url]) => String(url).includes('/queue/'))).toBe(false);
  });

  it('keeps artist concert listings without the inactive confirmation action', async () => {
    mockLoginFlow('ARTIST');
    render(<App />);
    await screen.findByRole('heading', { name: '아티스트 메인' });
    expect(await screen.findByText('Spring Concert')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '확인' })).not.toBeInTheDocument();
  });
});

function mockLoginFlow(role: UserRole, override?: (url: string, options?: RequestInit) => Promise<ReturnType<typeof jsonResponse>> | undefined) {
  const fetchMock = vi.fn(async (input: RequestInfo | URL, options?: RequestInit) => {
    const url = String(input);
    const custom = override?.(url, options);
    if (custom) return custom;

    if (url.endsWith('/api/auth/refresh')) {
      return jsonResponse({
        accessToken: 'access-token',
        tokenType: 'Bearer',
      });
    }

    if (url.endsWith('/api/users/me')) {
      return jsonResponse({
        userId: role === 'ARTIST' ? 7 : 1,
        name: role === 'ARTIST' ? '아티스트 사용자' : '팬 사용자',
        role,
      });
    }

    if (url.endsWith('/api/concerts')) {
      return jsonResponse([
        {
          concertId: 1,
          artistId: 7,
          title: 'Spring Concert',
          venue: 'Olympic Park',
          concertDate: '2026-06-01T10:00:00Z',
          ticketOpenDate: '2026-05-20T03:00:00Z',
          ticketCloseDate: '2026-05-31T14:59:00Z',
          status: 'ON_SALE',
        },
      ]);
    }

    if (url.endsWith('/api/queue/1/enter')) return jsonResponse({ concertId: 1, status: 'ACTIVE', entryToken: 'entry-secret', expiresAt: '2099-01-01T00:05:00Z', serverTime: '2099-01-01T00:00:00Z' });
    if (url.endsWith('/api/queue/1')) return jsonResponse({ concertId: 1, status: 'ADMITTED', rank: 0, behind: 0, nextPollAfterMs: 0, expiresAt: '2099-01-01T00:01:00Z', serverTime: '2099-01-01T00:00:00Z' });

    if (url.endsWith('/api/concerts/1/seats/availability')) {
      return jsonResponse({
        totalAvailable: 14951,
        sections: [
          { section: 'A', availableCount: 1500 },
          { section: 'B', availableCount: 1498 },
        ],
      });
    }

    if (url.endsWith('/api/concerts/1/seats/availability/sections/A')) {
      return jsonResponse({
        section: 'A',
        availableCount: 1,
        seats: [
          { seatId: 1, row: 1, col: 1, price: 120000, status: 'AVAILABLE' },
          { seatId: 2, row: 1, col: 2, price: 120000, status: 'SOLD' },
        ],
      });
    }

    if (url.endsWith('/api/tickets/me')) {
      return jsonResponse([
        {
          ticketId: 10,
          concertId: 1,
          concertTitle: 'Spring Concert',
          section: 'A',
          row: 1,
          col: 1,
          purchasePrice: 99000,
        },
      ]);
    }

    if (url.endsWith('/api/tickets')) {
      return jsonResponse([{
        ticketId: 11,
        userId: 1,
        seatId: 1,
        purchasePrice: 99000,
      }]);
    }

    return jsonResponse([]);
  });

  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

function jsonResponse(body: unknown, status = 200) {
  return {
    ok: status < 400,
    status,
    json: async () => body,
  };
}


function errorResponse(status: number, code: string, message: string) {
  return jsonResponse({ code, message }, status);
}

function sectionResponse(section: string, col: number) {
  return jsonResponse({ section, availableCount: 1, seats: [{ seatId: col, row: 1, col, price: 120000, status: 'AVAILABLE' }] });
}

async function openSeatModal() {
  render(<App />);
  await screen.findByRole('heading', { name: '팬 메인' });
  fireEvent.click(screen.getByRole('button', { name: '공연 새로고침' }));
  await enterPurchase();
  fireEvent.click(screen.getByRole('button', { name: '구역 및 좌석 선택' }));
  await screen.findByRole('button', { name: /A.*석/ });
}

async function enterPurchase() {
  fireEvent.click(await screen.findByRole('button', { name: '구매하기' }));
  fireEvent.click(await screen.findByRole('button', { name: '입장하기' }));
  await screen.findByRole('button', { name: '구역 및 좌석 선택' });
}
