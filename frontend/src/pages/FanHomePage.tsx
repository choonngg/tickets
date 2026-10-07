import { ReceiptText, RefreshCw } from 'lucide-react';

import { ConcertList } from '../components/ConcertList';
import type { ConcertResponse, TicketViewResponse } from '../types';

interface FanHomePageProps {
  concerts: ConcertResponse[];
  purchaseCounts: Record<number, number>;
  isBusy: boolean;
  tickets: TicketViewResponse[];
  onLoadConcerts: () => void;
  onLoadTickets: () => void;
  onJoinQueue: (concert: ConcertResponse) => void;
  authenticated: boolean;
}

export function FanHomePage({
  concerts,
  purchaseCounts,
  isBusy,
  tickets,
  onLoadConcerts,
  onLoadTickets,
  authenticated,
  onJoinQueue,
}: FanHomePageProps) {
  return (
    <>
      <section className="page-hero fan-hero">
        <div>
          <p className="eyebrow">FAN DASHBOARD</p>
          <h1>팬 메인</h1>
          <p>현재 예매 가능한 공연을 둘러보고, 구매한 티켓을 확인하세요.</p>
        </div>
        <div className="hero-actions">
          <button type="button" onClick={onLoadConcerts} disabled={isBusy}>
            <RefreshCw size={16} aria-hidden="true" />
            공연 새로고침
          </button>
          {authenticated && <button className="secondary-button" type="button" onClick={onLoadTickets} disabled={isBusy}>
            <ReceiptText size={16} aria-hidden="true" />
            구매한 공연
          </button>}
        </div>
      </section>

      <section className="dashboard-grid">
        <section className="page-panel">
          <div className="section-heading">
            <div>
              <p className="eyebrow">CONCERTS</p>
              <h2>현재 공연 목록</h2>
            </div>
            <span>{concerts.length}개 공연</span>
          </div>
          <ConcertList
            purchaseCounts={purchaseCounts}
            isBusy={isBusy}
            concerts={concerts}
            emptyDescription="공연 새로고침을 눌러 예매 가능한 공연을 불러오세요."
            emptyTitle="아직 조회된 공연이 없습니다."
            onSelect={onJoinQueue}
          />
        </section>

        {authenticated && <aside className="page-panel">
          <div className="section-heading">
            <div>
              <p className="eyebrow">MY TICKETS</p>
              <h2>구매한 공연</h2>
            </div>
            <span>{tickets.length}건</span>
          </div>
          <div className="ticket-list">
            {tickets.length === 0 ? (
              <div className="empty-state small">
                <ReceiptText size={30} aria-hidden="true" />
                <strong>표시할 티켓이 없습니다.</strong>
                <p>공연을 구매하면 이곳에서 확인할 수 있습니다.</p>
              </div>
            ) : (
              tickets.map((ticket) => (
                <article className="ticket-card" key={ticket.ticketId}>
                  <span>Ticket #{ticket.ticketId}</span>
                  <strong>{ticket.concertTitle}</strong>
                  <p>{`${ticket.section}구역 ${ticket.row}행 ${ticket.col}번`}</p>
                  <p>구매 가격 {ticket.purchasePrice.toLocaleString()}원</p>
                </article>
              ))
            )}
          </div>
        </aside>}
      </section>
    </>
  );
}
