export type UserRole = 'FAN' | 'ARTIST';

export interface AccessTokenResponse {
  accessToken: string;
  tokenType: string;
}

export interface UserResponse {
  userId: number;
  name: string;
  role: UserRole;
}

export interface ConcertCreateRequest {
  title: string;
  venue: string;
  concertDate: string;
  ticketOpenDate: string;
  ticketCloseDate: string;
  price: number;
}

export interface ConcertResponse {
  concertId: number;
  artistId: number;
  title: string;
  venue: string;
  concertDate: string;
  ticketOpenDate: string;
  ticketCloseDate: string;
  status: 'BEFORE_OPEN' | 'ON_SALE' | 'CLOSED' | 'CANCELLED';
}

export interface SeatResponse {
  seatId: number;
  concertId: number;
  section: string;
  row: number;
  col: number;
  price: number;
  status: 'AVAILABLE' | 'SOLD';
}

export interface SeatSectionSummaryResponse {
  section: string;
  availableCount: number;
}

export interface SeatAvailabilitySummaryResponse {
  totalAvailable: number;
  sections: SeatSectionSummaryResponse[];
}

export interface SeatSectionSeatResponse {
  seatId: number;
  row: number;
  col: number;
  price: number;
  status: 'AVAILABLE' | 'SOLD';
}

export interface SeatSectionAvailabilityResponse {
  section: string;
  availableCount: number;
  seats: SeatSectionSeatResponse[];
}

export interface TicketPurchaseResponse {
  ticketId: number;
  userId: number;
  seatId: number;
  purchasePrice: number;
}

export interface TicketViewResponse {
  concertId: number;
  ticketId: number;
  concertTitle: string;
  section: string;
  row: number;
  col: number;
  purchasePrice: number;
}

export interface QueueStatusResponse {
  concertId: number;
  status: 'WAITING' | 'ADMITTED' | 'ACTIVE' | 'EXPIRED';
  rank: number;
  behind: number;
  nextPollAfterMs: number;
  estimatedWaitSeconds: number | null;
  expiresAt: string | null;
  serverTime?: string;
}

export interface QueueEntryResponse {
  concertId: number;
  status: 'ACTIVE';
  entryToken: string;
  expiresAt: string;
  serverTime: string;
}
