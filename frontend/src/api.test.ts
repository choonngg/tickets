import { afterEach, describe, expect, it, vi } from 'vitest';

import { api } from './api';

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
