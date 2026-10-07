import type { SeatSectionSeatResponse } from '../types';

interface SeatMapProps {
  seats: SeatSectionSeatResponse[];
  selectedSeatIds: number[];
  selectedSection: string | null;
  onSelectSeat: (seatId: number) => void;
}

export function SeatMap({ seats, selectedSeatIds, selectedSection, onSelectSeat }: SeatMapProps) {
  if (!selectedSection) {
    return <p className="empty-copy">좌석을 보려면 구역을 선택하세요.</p>;
  }

  if (seats.length === 0) {
    return <p className="empty-copy">표시할 예매 가능 좌석이 없습니다.</p>;
  }

  return (
    <div className="seat-map" aria-label="예매 가능 좌석">
      {seats.map((seat) => {
        const isAvailable = seat.status === 'AVAILABLE';

        return (
          <button
            className={[
              'seat-button',
              selectedSeatIds.includes(seat.seatId) ? 'selected' : '',
              isAvailable ? 'available' : 'unavailable',
            ]
              .filter(Boolean)
              .join(' ')}
            aria-pressed={selectedSeatIds.includes(seat.seatId)}
            disabled={!isAvailable}
            key={seat.seatId}
            type="button"
            onClick={() => onSelectSeat(seat.seatId)}
          >
            {selectedSection}
            <small>
              {seat.row}-{seat.col}
            </small>
          </button>
        );
      })}
    </div>
  );
}
