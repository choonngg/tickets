import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import App from './App';
import type { UserRole } from './types';

describe('Ticket frontend', () => {
  afterEach(() => {
    localStorage.clear();
    vi.unstubAllGlobals();
  });

  it('starts on the login page when there is no saved session', async () => {
    mockFetch();

    render(<App />);

    expect(await screen.findByRole('heading', { name: '로그인' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '로그인' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '회원가입' })).toBeInTheDocument();
    expect(screen.queryByLabelText('이름')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('역할')).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '회원가입' }));

    expect(await screen.findByRole('dialog')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '회원가입' })).toBeInTheDocument();
    expect(screen.getByLabelText('이름')).toBeInTheDocument();
    expect(screen.getByLabelText('역할')).toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'FAN' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'ARTIST' })).toBeInTheDocument();
    expect(screen.queryByRole('option', { name: 'ADMIN' })).not.toBeInTheDocument();
  });

  it('routes FAN users to the fan home after login', async () => {
    mockLoginFlow('FAN');

    render(<App />);
    fireEvent.click(screen.getByRole('button', { name: '로그인' }));

    expect(await screen.findByRole('heading', { name: '팬 메인' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '공연 새로고침' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '구매한 공연' })).toBeInTheDocument();
  });

  it('routes ARTIST users to the artist home after login', async () => {
    mockLoginFlow('ARTIST');

    render(<App />);
    fireEvent.change(screen.getByLabelText('이메일'), { target: { value: 'artist@example.com' } });
    fireEvent.click(screen.getByRole('button', { name: '로그인' }));

    expect(await screen.findByRole('heading', { name: '아티스트 메인' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '전체 공연' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '내 공연' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '공연 등록' })).toBeInTheDocument();
  });

  it('routes ADMIN users to the pending page after login', async () => {
    mockLoginFlow('ADMIN');

    render(<App />);
    fireEvent.click(screen.getByRole('button', { name: '로그인' }));

    expect(await screen.findByRole('heading', { name: '관리자 화면 준비 중' })).toBeInTheDocument();
  });

  it('loads section summary before loading seats for a selected section', async () => {
    const fetchMock = mockLoginFlow('FAN');

    render(<App />);
    fireEvent.click(screen.getByRole('button', { name: '로그인' }));
    await screen.findByRole('heading', { name: '팬 메인' });

    fireEvent.click(screen.getByRole('button', { name: '공연 새로고침' }));

    fireEvent.click(await screen.findByRole('button', { name: '구매하기' }));

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
    fireEvent.click(screen.getByRole('button', { name: '로그인' }));
    await screen.findByRole('heading', { name: '팬 메인' });

    fireEvent.click(screen.getByRole('button', { name: '구매한 공연' }));

    expect(await screen.findByText('Spring Concert')).toBeInTheDocument();
    expect(screen.getByText('A구역 1행 1번')).toBeInTheDocument();
    expect(screen.queryByText(/좌석 #/)).not.toBeInTheDocument();
  });

  it('alerts and closes the seat modal after purchase succeeds', async () => {
    const fetchMock = mockLoginFlow('FAN');
    const alertMock = vi.fn();
    vi.stubGlobal('alert', alertMock);

    render(<App />);
    fireEvent.click(screen.getByRole('button', { name: '로그인' }));
    await screen.findByRole('heading', { name: '팬 메인' });
    fireEvent.click(screen.getByRole('button', { name: '공연 새로고침' }));
    fireEvent.click(await screen.findByRole('button', { name: '구매하기' }));
    fireEvent.click(screen.getByRole('button', { name: '구역 및 좌석 선택' }));
    fireEvent.click(await screen.findByRole('button', { name: /A.*석/ }));
    fireEvent.click((await screen.findByText('1-1')).closest('button')!);
    fireEvent.click(screen.getByRole('button', { name: /선택 좌석 예매하기/ }));

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith('/api/tickets', expect.any(Object));
      expect(alertMock).toHaveBeenCalledWith('구매가 완료되었습니다.');
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });
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
    ['network', undefined, undefined, 'Failed to fetch', true],
    ['processing', 409, 'REQUEST_IN_PROGRESS', '예매 요청을 처리 중입니다. 잠시 후 다시 시도하세요.', true],
    ['server', 500, 'INTERNAL_SERVER_ERROR', '서버 오류가 발생했습니다. 잠시 후 다시 시도하세요.', true],
    ['sale', 409, 'CONCERT_NOT_ON_SALE', '현재 예매 기간이 아니거나 취소된 공연입니다.', false],
    ['sold', 409, 'SEAT_NOT_AVAILABLE', '이미 판매되었거나 예매할 수 없는 좌석입니다. 다른 좌석을 선택하세요.', false],
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
      return Promise.resolve(jsonResponse({ ticketId: 11, status: 'COMPLETED' }, 201));
    });
    vi.stubGlobal('alert', vi.fn());
    await openSeatModal();
    fireEvent.click(screen.getByRole('button', { name: /A.*석/ }));
    fireEvent.click((await screen.findByText('1-1')).closest('button')!);
    fireEvent.click(screen.getByRole('button', { name: /선택 좌석 예매하기/ }));
    expect(await screen.findByText(message)).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('button', { name: /선택 좌석 예매하기/ })).toBeEnabled());
    fireEvent.click(screen.getByRole('button', { name: /선택 좌석 예매하기/ }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(keys[0] === keys[1]).toBe(reuse);
    // Refresh the seat view to simulate another purchase after a 201 response.
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
      fireEvent.click(screen.getByRole('button', { name: new RegExp(`${section}.*석`) }));
      fireEvent.click((await screen.findByText(seat)).closest('button')!);
      fireEvent.click(screen.getByRole('button', { name: /선택 좌석 예매하기/ }));
      await screen.findByText('Failed to fetch');
      await waitFor(() => expect(screen.getByRole('button', { name: /선택 좌석 예매하기/ })).toBeEnabled());
    }
    expect(keys).toHaveLength(2);
    expect(keys[0]).not.toBe(keys[1]);
  });

  it.each([
    ['예매 오픈', '2026-05-31T23:59', '예매 일정은 오픈 < 마감 ≤ 공연일 순서로 입력하세요.'],
    ['예매 마감', '2026-06-02T19:00', '예매 일정은 오픈 < 마감 ≤ 공연일 순서로 입력하세요.'],
    ['공연명', 'a'.repeat(101), '공연명은 100자, 장소는 255자 이하로 입력하세요.'],
    ['장소', 'a'.repeat(256), '공연명은 100자, 장소는 255자 이하로 입력하세요.'],
  ])('blocks invalid concert field %s before submitting', async (label, value, message) => {
    const fetchMock = mockLoginFlow('ARTIST');
    render(<App />);
    fireEvent.click(screen.getByRole('button', { name: '로그인' }));
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
    fireEvent.click(screen.getByRole('button', { name: '로그인' }));
    await screen.findByRole('heading', { name: '아티스트 메인' });
    fireEvent.change(screen.getByLabelText('공연명'), { target: { value: 'a'.repeat(100) } });
    fireEvent.change(screen.getByLabelText('장소'), { target: { value: 'a'.repeat(255) } });
    fireEvent.change(screen.getByLabelText('예매 마감'), { target: { value: '2026-06-01T19:00' } });
    fireEvent.click(screen.getByRole('button', { name: '공연 등록하기' }));
    expect(await screen.findByText('Request body is invalid.')).toBeInTheDocument();
    expect(fetchMock.mock.calls.some(([, options]) => options?.method === 'POST' && String(options.body).includes('ticketCloseDate'))).toBe(true);
  });

});

function mockLoginFlow(role: UserRole, override?: (url: string, options?: RequestInit) => Promise<ReturnType<typeof jsonResponse>> | undefined) {
  const fetchMock = vi.fn(async (input: RequestInfo | URL, options?: RequestInit) => {
    const url = String(input);
    const custom = override?.(url, options);
    if (custom) return custom;

    if (url.endsWith('/api/auth/login')) {
      return jsonResponse({
        accessToken: 'access-token',
        refreshToken: 'refresh-token',
        tokenType: 'Bearer',
      });
    }

    if (url.endsWith('/api/users/me')) {
      return jsonResponse({
        userId: role === 'ARTIST' ? 7 : 1,
        name: role === 'ARTIST' ? '아티스트 사용자' : role === 'ADMIN' ? '관리자' : '팬 사용자',
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
          concertDate: '2026-06-01T19:00:00',
          ticketOpenDate: '2026-05-20T12:00:00',
          ticketCloseDate: '2026-05-31T23:59:00',
          status: 'ON_SALE',
        },
      ]);
    }

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
          concertTitle: 'Spring Concert',
          section: 'A',
          row: 1,
          col: 1,
          status: 'COMPLETED',
        },
      ]);
    }

    if (url.endsWith('/api/tickets')) {
      return jsonResponse({
        ticketId: 11,
        userId: 1,
        seatId: 1,
        paymentId: 11,
        status: 'COMPLETED',
      });
    }

    return jsonResponse([]);
  });

  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

function mockFetch() {
  const fetchMock = vi.fn(async () => jsonResponse([]));
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
  fireEvent.click(screen.getByRole('button', { name: '로그인' }));
  await screen.findByRole('heading', { name: '팬 메인' });
  fireEvent.click(screen.getByRole('button', { name: '공연 새로고침' }));
  fireEvent.click(await screen.findByRole('button', { name: '구매하기' }));
  fireEvent.click(screen.getByRole('button', { name: '구역 및 좌석 선택' }));
  await screen.findByRole('button', { name: /A.*석/ });
}
