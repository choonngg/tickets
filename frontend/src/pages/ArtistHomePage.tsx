import { CalendarPlus, RefreshCw } from 'lucide-react';
import { FormEvent, useMemo, useState } from 'react';

import { ConcertList } from '../components/ConcertList';
import type { ConcertCreateRequest, ConcertResponse, UserResponse } from '../types';

type ArtistFilter = 'all' | 'mine';

interface ArtistHomePageProps {
  concertForm: ConcertCreateRequest;
  concerts: ConcertResponse[];
  isBusy: boolean;
  me: UserResponse;
  onConcertFormChange: (form: ConcertCreateRequest) => void;
  onCreateConcert: (event: FormEvent) => void;
  onLoadConcerts: () => void;
}

export function ArtistHomePage({
  concertForm,
  concerts,
  isBusy,
  me,
  onConcertFormChange,
  onCreateConcert,
  onLoadConcerts,
}: ArtistHomePageProps) {
  const [filter, setFilter] = useState<ArtistFilter>('all');
  const filteredConcerts = useMemo(
    () => (filter === 'mine' ? concerts.filter((concert) => concert.artistId === me.userId) : concerts),
    [concerts, filter, me.userId],
  );

  return (
    <>
      <section className="page-hero artist-hero">
        <div>
          <p className="eyebrow">ARTIST DASHBOARD</p>
          <h1>아티스트 메인</h1>
          <p>전체 공연을 확인하고, 내가 등록한 공연만 따로 볼 수 있습니다.</p>
        </div>
        <button type="button" onClick={onLoadConcerts} disabled={isBusy}>
          <RefreshCw size={16} aria-hidden="true" />
          공연 새로고침
        </button>
      </section>

      <section className="dashboard-grid">
        <section className="page-panel">
          <div className="section-heading">
            <div>
              <p className="eyebrow">CONCERTS</p>
              <h2>공연 관리</h2>
            </div>
            <div className="segmented-control" aria-label="공연 필터">
              <button className={filter === 'all' ? 'active' : ''} type="button" onClick={() => setFilter('all')}>
                전체 공연
              </button>
              <button className={filter === 'mine' ? 'active' : ''} type="button" onClick={() => setFilter('mine')}>
                내 공연
              </button>
            </div>
          </div>
          <ConcertList
            concerts={filteredConcerts}
            emptyDescription={filter === 'mine' ? '아직 내가 등록한 공연이 없습니다.' : '공연 새로고침을 눌러 목록을 불러오세요.'}
            emptyTitle="표시할 공연이 없습니다."
          />
        </section>

        <aside className="page-panel">
          <div className="section-heading">
            <div>
              <p className="eyebrow">CREATE</p>
              <h2>공연 등록</h2>
            </div>
          </div>
          <form className="manage-form" onSubmit={onCreateConcert}>
            <label>
              공연명
              <input
                value={concertForm.title}
                onChange={(event) => onConcertFormChange({ ...concertForm, title: event.target.value })}
              />
            </label>
            <label>
              장소
              <input
                value={concertForm.venue}
                onChange={(event) => onConcertFormChange({ ...concertForm, venue: event.target.value })}
              />
            </label>
            <label>
              공연일
              <input
                type="datetime-local"
                value={concertForm.concertDate}
                onChange={(event) => onConcertFormChange({ ...concertForm, concertDate: event.target.value })}
              />
            </label>
            <label>
              예매 오픈
              <input
                type="datetime-local"
                value={concertForm.ticketOpenDate}
                onChange={(event) => onConcertFormChange({ ...concertForm, ticketOpenDate: event.target.value })}
              />
            </label>
            <label>
              예매 마감
              <input
                type="datetime-local"
                value={concertForm.ticketCloseDate}
                onChange={(event) => onConcertFormChange({ ...concertForm, ticketCloseDate: event.target.value })}
              />
            </label>
            <label>
              기본 가격
              <input
                min="0"
                type="number"
                value={concertForm.price}
                onChange={(event) => onConcertFormChange({ ...concertForm, price: Number(event.target.value) })}
              />
            </label>
            <button type="submit" disabled={isBusy}>
              <CalendarPlus size={16} aria-hidden="true" />
              공연 등록하기
            </button>
          </form>
        </aside>
      </section>
    </>
  );
}
