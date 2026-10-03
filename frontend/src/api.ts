import type {
  ConcertCreateRequest,
  ConcertResponse,
  LoginRequest,
  LoginResponse,
  SeatAvailabilitySummaryResponse,
  SeatSectionAvailabilityResponse,
  SeatResponse,
  SignupRequest,
  SignupResponse,
  TicketPurchaseResponse,
  TicketViewResponse,
  UserResponse,
} from './types';

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '';

interface RequestOptions extends RequestInit {
  accessToken?: string;
  idempotencyKey?: string;
}

export class ApiError extends Error {
  constructor(message: string, public readonly status: number, public readonly code?: string) {
    super(message);
    this.name = 'ApiError';
  }
}

async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const headers = new Headers(options.headers);
  headers.set('Accept', 'application/json');

  if (options.body && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }

  if (options.accessToken) {
    headers.set('Authorization', `Bearer ${options.accessToken}`);
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

export const api = {
  signup: (body: SignupRequest) =>
    request<SignupResponse>('/api/auth/signup', {
      method: 'POST',
      body: JSON.stringify(body),
    }),
  login: (body: LoginRequest) =>
    request<LoginResponse>('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify(body),
    }),
  me: (accessToken: string) => request<UserResponse>('/api/users/me', { accessToken }),
  logout: (refreshToken: string) =>
    request<void>('/api/auth/logout', {
      method: 'POST',
      body: JSON.stringify({ refreshToken }),
    }),
  findConcerts: () => request<ConcertResponse[]>('/api/concerts'),
  createConcert: (accessToken: string, body: ConcertCreateRequest) =>
    request<ConcertResponse>('/api/concerts', {
      method: 'POST',
      accessToken,
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
  purchaseTicket: (accessToken: string, seatId: number, idempotencyKey: string) =>
    request<TicketPurchaseResponse>('/api/tickets', {
      method: 'POST',
      accessToken,
      idempotencyKey,
      body: JSON.stringify({ seatId }),
    }),
  findMyTickets: (accessToken: string) =>
    request<TicketViewResponse[]>('/api/tickets/me', { accessToken }),
};
