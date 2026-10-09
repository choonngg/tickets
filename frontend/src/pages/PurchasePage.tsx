import { ArrowLeft, CheckCircle2, Clock, Ticket } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';

import { SeatMap } from '../components/SeatMap';
import { SeatSectionPicker } from '../components/SeatSectionPicker';
import type {
  ConcertResponse,
  SeatSectionSeatResponse,
  SeatSectionSummaryResponse,
} from '../types';

interface PurchasePageProps {
  concert: ConcertResponse;
  isBusy: boolean;
  isPurchasing: boolean;
  remainingSeconds: number;
  seats: SeatSectionSeatResponse[];
  seatSections: SeatSectionSummaryResponse[];
  selectedSeats: (SeatSectionSeatResponse & { section: string })[];
  remainingAllowance: number;
  selectedSection: string | null;
  totalAvailableSeats: number;
  onBack: () => void;
  onPurchase: () => Promise<boolean>;
  onSelectSeat: (seatId: number) => void;
  onSelectSection: (section: string) => void;
}

export function PurchasePage({
  concert,
  isBusy,
  isPurchasing,
  remainingSeconds,
  seats,
  seatSections,
  selectedSeats,
  remainingAllowance,
  selectedSection,
  totalAvailableSeats,
  onBack,
  onPurchase,
  onSelectSeat,
  onSelectSection,
}: PurchasePageProps) {
  const [isSeatModalOpen, setIsSeatModalOpen] = useState(true);
  const [limitWarning, setLimitWarning] = useState(false);
  const warningTimer = useRef<number | undefined>(undefined);
  const limit = Math.min(4, remainingAllowance);

  useEffect(() => () => window.clearTimeout(warningTimer.current), []);
  useEffect(() => {
    if (!isSeatModalOpen) {
      window.clearTimeout(warningTimer.current);
      setLimitWarning(false);
    }
  }, [isSeatModalOpen]);

  function handleSelectSeat(seatId: number) {
    if (!selectedSeats.some(seat => seat.seatId === seatId) && selectedSeats.length >= limit) {
      window.clearTimeout(warningTimer.current);
      setLimitWarning(true);
      warningTimer.current = window.setTimeout(() => setLimitWarning(false), 3000);
      return;
    }
    onSelectSeat(seatId);
  }

  const seatLabels = selectedSeats.map(seat => `${seat.section}구역 ${seat.row}행 ${seat.col}열`).join(', ') || '미선택';
  const totalPrice = selectedSeats.reduce((total, seat) => total + seat.price, 0);
  const clock = `${Math.floor(remainingSeconds / 60)}:${String(remainingSeconds % 60).padStart(2, '0')}`;
  const remaining = `남은 이용 시간 ${Math.floor(remainingSeconds / 60)}분 ${remainingSeconds % 60}초`;

  async function handlePurchaseClick() {
    const completed = await onPurchase();

    if (completed) {
      window.alert('구매가 완료되었습니다.');
      setIsSeatModalOpen(false);
    }
  }

  return (
    <>
      <section className="page-hero purchase-hero">
        <div>
          <p className="eyebrow">PURCHASE</p>
          <h1>공연 구매 페이지</h1>
          {!isSeatModalOpen && <p role="status">{remaining}</p>}
          <p>{concert.title}의 구역을 선택하고 좌석을 예매하세요.</p>
          <p>이 공연은 {remainingAllowance}매 더 구매할 수 있습니다</p>
        </div>
        <button className="secondary-button" type="button" onClick={onBack}>
          <ArrowLeft size={16} aria-hidden="true" />
          팬 메인으로
        </button>
      </section>

      <section className="purchase-layout">
        <section className="page-panel">
          <div className="section-heading">
            <div>
              <p className="eyebrow">CONCERT</p>
              <h2>{concert.title}</h2>
            </div>
            <span>{concert.status}</span>
          </div>
          <div className="selected-concert">
            <strong>{concert.venue}</strong>
            <span>{formatDate(concert.concertDate)}</span>
          </div>
          <div className="purchase-summary">
            <div>
              <span>예매 오픈</span>
              <strong>{formatDate(concert.ticketOpenDate)}</strong>
            </div>
            <div>
              <span>예매 마감</span>
              <strong>{formatDate(concert.ticketCloseDate)}</strong>
            </div>
          </div>
          <button className="wide-button" type="button" onClick={() => setIsSeatModalOpen(true)}>
            구역 및 좌석 선택
          </button>
        </section>

        <aside className="page-panel purchase-preview">
          <div className="section-heading">
            <div>
              <p className="eyebrow">SELECTED</p>
              <h2>선택 정보</h2>
            </div>
          </div>
          <div className="purchase-summary">
            <div>
              <span>선택 좌석</span>
              <strong>
                {seatLabels}
              </strong>
            </div>
            <div>
              <span>합계 금액</span>
              <strong>{selectedSeats.length ? `${totalPrice.toLocaleString()}원` : '-'}</strong>
            </div>
          </div>
          <p className="empty-copy">좌석을 더 넓게 보려면 좌석 선택 모달을 여세요.</p>
        </aside>
      </section>

      {isSeatModalOpen && (
        <div className="modal-backdrop seat-modal-backdrop">
          <section aria-modal="true" className="seat-selection-modal" role="dialog">
            <div className="section-heading seat-modal-heading">
              <div>
                <p className="eyebrow">SEAT MAP</p>
                <h2>구역 및 좌석 선택</h2>
              </div>
              <div className="seat-modal-info">
                <div className="seat-modal-badges">
                  <span className={`seat-badge${remainingSeconds < 60 ? ' urgent' : ''}`} role="status">
                    <Clock aria-hidden="true" size={14} />
                    남은 시간 {clock}
                  </span>
                  <span className="seat-badge seat-limit-notice" role="status">
                    <Ticket aria-hidden="true" size={14} />
                    선택 {selectedSeats.length}/{limit}석 · {remainingAllowance}매 더 구매 가능
                  </span>
                </div>
                <p className="seat-limit-warning" role={limitWarning ? 'alert' : undefined}>
                  {limitWarning ? `최대 ${limit}석까지 선택할 수 있습니다.` : ''}
                </p>
              </div>
            </div>
            <div className="seat-modal-layout">
              <aside className="seat-modal-sidebar">
                <strong>{concert.title}</strong>
                <span>{concert.venue}</span>
                <span className="seat-modal-available">잔여 {totalAvailableSeats}석</span>
                <SeatSectionPicker
                  disabled={isPurchasing}
                  sections={seatSections}
                  selectedSection={selectedSection}
                  onSelectSection={onSelectSection}
                />
              </aside>
              <div className="seat-modal-map">
                <SeatMap
                  seats={seats}
                  selectedSeatIds={selectedSeats.map(seat => seat.seatId)}
                  selectedSection={selectedSection}
                  onSelectSeat={handleSelectSeat}
                />
              </div>
            </div>
            <div className="seat-modal-footer">
              <div className="purchase-summary">
                <div>
                  <span>선택 좌석</span>
                  <strong>
                    {seatLabels}
                  </strong>
                </div>
                <div>
                  <span>합계 금액</span>
                  <strong>{selectedSeats.length ? `${totalPrice.toLocaleString()}원` : '-'}</strong>
                </div>
              </div>
              <div className="modal-actions">
                <button className="secondary-button" type="button" onClick={() => setIsSeatModalOpen(false)}>
                  닫기
                </button>
                <button type="button" onClick={handlePurchaseClick} disabled={isBusy}>
                  <CheckCircle2 size={17} aria-hidden="true" />
                  선택 좌석 예매하기
                </button>
              </div>
            </div>
          </section>
        </div>
      )}
    </>
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
