import { afterEach, describe, expect, it, vi } from 'vitest';

import { api, auth } from './api';

describe('API errors', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('preserves status, code and the original server message', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({
      code: 'INVALID_REQUEST', message: 'Request body is invalid.',
    }), { status: 400 })));
    await expect(api.findConcerts()).rejects.toMatchObject({
      name: 'ApiError', status: 400, code: 'INVALID_REQUEST', message: 'Request body is invalid.',
    });
  });

  it('preserves HTTP status when the server error body is not JSON', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('Bad gateway', { status: 502, statusText: 'Bad Gateway' })));
    await expect(api.findConcerts()).rejects.toMatchObject({ status: 502, message: '502 Bad Gateway' });
  });
});

describe('memory authentication', () => {
  afterEach(() => {
    auth.clear();
    vi.unstubAllGlobals();
  });

  it('shares refresh for simultaneous 401s and reuses the new AT for a late 401', async () => {
    let releaseLate!: () => void;
    const late = new Promise<void>((resolve) => { releaseLate = resolve; });
    let refreshes = 0;
    const fetchMock = vi.fn(async (input: RequestInfo | URL, options?: RequestInit) => {
      if (String(input).endsWith('/api/auth/refresh')) {
        refreshes++;
        return json({ accessToken: refreshes === 1 ? 'old' : 'new', tokenType: 'Bearer' });
      }
      if (new Headers(options?.headers).get('Authorization') === 'Bearer old') {
        if (String(input).endsWith('/api/users/me')) await late;
        return json({ code: 'UNAUTHORIZED' }, 401);
      }
      return json([]);
    });
    vi.stubGlobal('fetch', fetchMock);
    await auth.refresh();
    const first = api.findMyTickets();
    const second = api.findMyTickets();
    const third = api.me();
    await Promise.all([first, second]);
    releaseLate();
    await third;
    expect(refreshes).toBe(2); // Bootstrap + one shared recovery.
    expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/api/tickets/me'))).toHaveLength(4);
  });

  it('retries a purchase once with the same body and Idempotency-Key', async () => {
    let purchases = 0;
    let refreshes = 0;
    const fetchMock = vi.fn(async (input: RequestInfo | URL, _options?: RequestInit) => {
      if (String(input).endsWith('/api/auth/refresh')) {
        refreshes++;
        return json({ accessToken: 'token-' + refreshes, tokenType: 'Bearer' });
      }
      purchases++;
      return purchases === 1 ? json({ code: 'UNAUTHORIZED' }, 401) : json({ ticketId: 11 }, 201);
    });
    vi.stubGlobal('fetch', fetchMock);
    await auth.refresh();
    await expect(api.purchaseTicket(1, 'purchase-key')).resolves.toEqual({ ticketId: 11 });
    const calls = fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/api/tickets'));
    expect(calls).toHaveLength(2);
    for (const [, options] of calls) {
      expect(new Headers(options?.headers).get('Idempotency-Key')).toBe('purchase-key');
      expect(options?.body).toBe(JSON.stringify({ seatId: 1 }));
    }
    expect(calls.map(([, options]) => new Headers(options?.headers).get('Authorization'))).toEqual(['Bearer token-1', 'Bearer token-2']);
  });

  it('retries REFRESH_RETRY once using cookies without an Authorization header', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json({ code: 'REFRESH_RETRY' }, 401))
      .mockResolvedValueOnce(json({ accessToken: 'new', tokenType: 'Bearer' }));
    vi.stubGlobal('fetch', fetchMock);
    await auth.refresh();
    expect(fetchMock).toHaveBeenCalledTimes(2);
    for (const [url, options] of fetchMock.mock.calls) {
      expect(url).toBe('/api/auth/refresh');
      expect(options).toMatchObject({ method: 'POST', credentials: 'include' });
      expect(options.body).toBeUndefined();
      expect(new Headers(options.headers).has('Authorization')).toBe(false);
    }
  });

  it('keeps the AT and surfaces the original error when refresh fails with 5xx or network error', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json({ accessToken: 'old', tokenType: 'Bearer' }))
      .mockResolvedValueOnce(json({ code: 'UNAUTHORIZED' }, 401))
      .mockResolvedValueOnce(json({ code: 'BAD_GATEWAY' }, 502))
      .mockResolvedValueOnce(json({ code: 'UNAUTHORIZED' }, 401))
      .mockRejectedValueOnce(new TypeError('Failed to fetch'))
      .mockResolvedValueOnce(json([]));
    vi.stubGlobal('fetch', fetchMock);
    await auth.refresh();
    await expect(api.findMyTickets()).rejects.toMatchObject({ status: 502 });
    await expect(api.findMyTickets()).rejects.toBeInstanceOf(TypeError);
    await expect(api.findMyTickets()).resolves.toEqual([]);
  });

  it('does not restore a session when logout happens while refresh is in flight', async () => {
    let release!: () => void;
    const gate = new Promise<void>((resolve) => { release = resolve; });
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (String(input).endsWith('/api/auth/refresh')) {
        await gate;
        return json({ accessToken: 'late', tokenType: 'Bearer' });
      }
      return json([]);
    });
    vi.stubGlobal('fetch', fetchMock);
    const pending = auth.refresh();
    const assertion = expect(pending).rejects.toMatchObject({ code: 'REAUTH_REQUIRED' });
    auth.clear();
    release();
    await assertion;
    await expect(api.findMyTickets()).rejects.toMatchObject({ code: 'REAUTH_REQUIRED' });
  });

  it('sends logout with cookies only and clears AT after 204', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json({ accessToken: 'old', tokenType: 'Bearer' }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    vi.stubGlobal('fetch', fetchMock);
    await auth.refresh();
    await api.logout();
    const [, options] = fetchMock.mock.calls[1];
    expect(options).toMatchObject({ method: 'POST', credentials: 'include' });
    expect(options.body).toBeUndefined();
    expect(new Headers(options.headers).has('Authorization')).toBe(false);
    await expect(api.me()).rejects.toMatchObject({ code: 'REAUTH_REQUIRED' });
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });
});

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status });
}
