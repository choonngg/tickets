import { CalendarDays, MapPin, Music2 } from 'lucide-react';

import type { ConcertResponse } from '../types';

interface ConcertListProps {

  isBusy?: boolean;
  purchaseCounts?: Record<number, number>;
  concerts: ConcertResponse[];
  emptyDescription: string;
  emptyTitle: string;
  selectedConcertId?: number | null;
  onSelect?: (concert: ConcertResponse) => void;
}

export function ConcertList({
  isBusy,
  purchaseCounts,
  concerts,
  emptyDescription,
  emptyTitle,
  selectedConcertId,
  onSelect,
}: ConcertListProps) {
  if (concerts.length === 0) {
    return (
      <div className="empty-state">
        <Music2 size={32} aria-hidden="true" />
        <strong>{emptyTitle}</strong>
        <p>{emptyDescription}</p>
      </div>
    );
  }

  return (
    <div className="concert-grid">
      {concerts.map((concert) => (
        <article className={concert.concertId === selectedConcertId ? 'concert-card selected' : 'concert-card'} key={concert.concertId}>
          <div className="poster-block">
            <Music2 size={30} aria-hidden="true" />
          </div>
          <div className="concert-info">
            <span className="status-pill">{concert.status}</span>
            <h3>{concert.title}</h3>
            <p>
              <MapPin size={14} aria-hidden="true" />
              {concert.venue}
            </p>
            <p>
              <CalendarDays size={14} aria-hidden="true" />
              {formatDate(concert.concertDate)}
            </p>
            {onSelect && <>
              {concert.status === 'BEFORE_OPEN' && <p>예매 오픈 {formatDate(concert.ticketOpenDate)}</p>}
              <button type="button" disabled={isBusy || concert.status !== 'ON_SALE' || (purchaseCounts?.[concert.concertId] ?? 0) >= 4} onClick={() => onSelect(concert)}>
                {(purchaseCounts?.[concert.concertId] ?? 0) >= 4 ? '구매 한도(4매) 도달' : { BEFORE_OPEN: '오픈 예정', ON_SALE: '구매하기', CLOSED: '판매 마감', CANCELLED: '취소된 공연' }[concert.status]}
              </button>
            </>}

          </div>
        </article>
      ))}
    </div>
  );
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat('ko-KR', {
    month: 'long',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value));
}
