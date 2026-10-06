import type {
  QueueStatusResponse,
  QueueEntryResponse,
  ConcertCreateRequest,
  ConcertResponse,
  AccessTokenResponse,
  SeatAvailabilitySummaryResponse,
  SeatSectionAvailabilityResponse,
  SeatResponse,
  TicketPurchaseResponse,
  TicketViewResponse,
  UserResponse,
} from './types';

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '';

interface RequestOptions extends RequestInit {
  idempotencyKey?: string;
}

export class ApiError extends Error {
  constructor(message: string, public readonly status: number, public readonly code?: string) {
    super(message);
    this.name = 'ApiError';
  }
}

// Raised by the client itself (not the server) when the session must be re-established.
export class SessionExpiredError extends ApiError {
  constructor(message: string) {
    super(message, 401, 'REAUTH_REQUIRED');
    this.name = 'SessionExpiredError';
  }
}

async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const headers = new Headers(options.headers);
  headers.set('Accept', 'application/json');

  if (options.body && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }

  if (options.idempotencyKey) {
    headers.set('Idempotency-Key', options.idempotencyKey);
  }

  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...options,
    headers,
  });

  if (!response.ok) {
    throw await readApiError(response);
  }

  if (response.status === 204) {
    return undefined as T;
  }

  return response.json() as Promise<T>;
}

async function readApiError(response: Response): Promise<ApiError> {
  const fallback = `${response.status} ${response.statusText}`;

  try {
    const body = await response.json();
    return new ApiError(body.message ?? body.error ?? fallback, response.status, body.code);
  } catch {
    return new ApiError(fallback, response.status);
  }
}

const entryTokens = new Map<number, QueueEntryResponse>();

export function getEntryToken(concertId: number): string | null {
  const entry = entryTokens.get(concertId);
  if (!entry || Date.parse(entry.expiresAt) <= Date.now()) {
    entryTokens.delete(concertId);
    return null;
  }
  return entry.entryToken;
}

export function clearEntryToken(concertId: number) {
  entryTokens.delete(concertId);
}

let accessToken: string | null = null;
let refreshPromise: Promise<void> | null = null;
let sessionVersion = 0;
const expiredListeners = new Set<() => void>();
const signInMessage = '다시 로그인해 주세요';

function clearAccessToken() {
  accessToken = null;
  entryTokens.clear();
  sessionVersion++;
}

function reauthError() {
  return new SessionExpiredError(signInMessage);
}

function expireSession() {
  clearAccessToken();
  expiredListeners.forEach((listener) => listener());
  return reauthError();
}

function refreshAccessToken(): Promise<void> {
  if (refreshPromise) return refreshPromise;
  const version = sessionVersion;
  refreshPromise = (async () => {
    let result: AccessTokenResponse;
    try {
      result = await request<AccessTokenResponse>('/api/auth/refresh', {
        method: 'POST', credentials: 'include',
      });
    } catch (error) {
      if (error instanceof ApiError && error.status === 401 && error.code === 'REFRESH_RETRY') {
        try {
          result = await request<AccessTokenResponse>('/api/auth/refresh', {
            method: 'POST', credentials: 'include',
          });
        } catch {
          clearAccessToken();
          throw reauthError();
        }
      } else {
        if (error instanceof ApiError && error.code === 'REAUTH_REQUIRED') clearAccessToken();
        throw error;
      }
    }
    // A late refresh must not restore a session after logout or withdrawal.
    if (version !== sessionVersion) throw reauthError();
    accessToken = result.accessToken;
  })().finally(() => { refreshPromise = null; });
  return refreshPromise;
}

export const auth = {
  refresh: refreshAccessToken,
  clear: clearAccessToken,
  onExpired: (listener: () => void) => {
    expiredListeners.add(listener);
    return () => { expiredListeners.delete(listener); };
  },
};

async function authenticatedRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const token = accessToken;
  const version = sessionVersion;
  if (!token) throw reauthError();
  const send = () => {
    const headers = new Headers(options.headers);
    headers.set('Authorization', `Bearer ${accessToken}`);
    return request<T>(path, { ...options, headers });
  };
  try {
    return await send();
  } catch (error) {
    if (!(error instanceof ApiError && error.status === 401 && error.code === 'UNAUTHORIZED')) throw error;
    if (version !== sessionVersion) throw reauthError();
    try {
      // A concurrent request may already have refreshed this rejected token.
      if (token === accessToken) await refreshAccessToken();
    } catch (refreshError) {
      // Only a definitive re-auth signal ends the session; network/5xx failures keep it.
      if (refreshError instanceof ApiError && refreshError.code === 'REAUTH_REQUIRED') throw expireSession();
      throw refreshError;
    }
    if (version !== sessionVersion) throw reauthError();
    try {
      return await send();
    } catch (retryError) {
      if (retryError instanceof ApiError && retryError.status === 401 && retryError.code === 'UNAUTHORIZED') {
        throw expireSession();
      }
      throw retryError;
    }
  }
}

export const api = {
  registerQueue: (concertId: number, signal?: AbortSignal) =>
    authenticatedRequest<QueueStatusResponse>(`/api/queue/${concertId}`, { method: 'POST', signal }),
  queueStatus: (concertId: number, signal?: AbortSignal) =>
    authenticatedRequest<QueueStatusResponse>(`/api/queue/${concertId}/status`, { signal }),
  enterQueue: async (concertId: number, signal?: AbortSignal) => {
    const version = sessionVersion;
    const entry = await authenticatedRequest<QueueEntryResponse>(`/api/queue/${concertId}/enter`, { method: 'POST', signal });
    if (version === sessionVersion && !signal?.aborted) entryTokens.set(concertId, entry);
    return entry;
  },
  cancelQueue: async (concertId: number, signal?: AbortSignal) => {
    await authenticatedRequest<void>(`/api/queue/${concertId}`, { method: 'DELETE', signal });
    clearEntryToken(concertId);
  },
  me: () => authenticatedRequest<UserResponse>('/api/users/me'),
  // Resolves true when the server session was already gone (401 REAUTH_REQUIRED).
  logout: async () => {
    try {
      await request<void>('/api/auth/logout', { method: 'POST', credentials: 'include' });
    } catch (error) {
      if (!(error instanceof ApiError && error.status === 401 && error.code === 'REAUTH_REQUIRED')) throw error;
      clearAccessToken();
      return true;
    }
    clearAccessToken();
    return false;
  },
  deleteMe: async () => {
    await authenticatedRequest<void>('/api/users/me', { method: 'DELETE' });
    clearAccessToken();
  },
  findConcerts: () => request<ConcertResponse[]>('/api/concerts'),
  createConcert: (body: ConcertCreateRequest) =>
    authenticatedRequest<ConcertResponse>('/api/concerts', {
      method: 'POST',
      body: JSON.stringify(body),
    }),
  findSeats: (concertId: number) => request<SeatResponse[]>(`/api/concerts/${concertId}/seats`),
  findAvailableSeats: (concertId: number) =>
    request<SeatResponse[]>(`/api/concerts/${concertId}/seats/available`),
  findSeatAvailabilitySummary: (concertId: number) =>
    request<SeatAvailabilitySummaryResponse>(`/api/concerts/${concertId}/seats/availability`),
  findSeatSectionAvailability: (concertId: number, section: string, signal?: AbortSignal) =>
    request<SeatSectionAvailabilityResponse>(
      `/api/concerts/${concertId}/seats/availability/sections/${encodeURIComponent(section)}`,
      { signal },
    ),
  purchaseTicket: (seatId: number, idempotencyKey: string) =>
    authenticatedRequest<TicketPurchaseResponse>('/api/tickets', {
      method: 'POST',
      idempotencyKey,
      body: JSON.stringify({ seatId }),
    }),
  findMyTickets: () => authenticatedRequest<TicketViewResponse[]>('/api/tickets/me'),
};
