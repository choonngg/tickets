import { FormEvent, useEffect, useMemo, useRef, useState } from 'react';

import { api, auth, ApiError, SessionExpiredError } from './api';
import { AppHeader } from './components/AppHeader';
import { MessagePanel } from './components/MessagePanel';
import { ArtistHomePage } from './pages/ArtistHomePage';
import { FanHomePage } from './pages/FanHomePage';
import { LoginPage } from './pages/LoginPage';
import { PurchasePage } from './pages/PurchasePage';
import type {
  ConcertCreateRequest,
  ConcertResponse,
  SeatSectionSeatResponse,
  SeatSectionSummaryResponse,
  TicketViewResponse,
  UserResponse,
  UserRole,
} from './types';

type Page = 'loading' | 'unavailable' | 'login' | 'fan-home' | 'artist-home' | 'purchase';

const initialConcertForm: ConcertCreateRequest = {
  title: '샘플 콘서트',
  venue: 'KSPO DOME',
  concertDate: '2026-06-01T19:00',
  ticketOpenDate: '2026-05-20T12:00',
  ticketCloseDate: '2026-05-31T23:59',
  price: 120000,
};

function App() {
  const [page, setPage] = useState<Page>('loading');
  const [concertForm, setConcertForm] = useState(initialConcertForm);
  const [me, setMe] = useState<UserResponse | null>(null);
  const [concerts, setConcerts] = useState<ConcertResponse[]>([]);
  const [selectedConcertId, setSelectedConcertId] = useState<number | null>(null);
  const [seatSections, setSeatSections] = useState<SeatSectionSummaryResponse[]>([]);
  const [selectedSection, setSelectedSection] = useState<string | null>(null);
  const [totalAvailableSeats, setTotalAvailableSeats] = useState(0);
  const [seats, setSeats] = useState<SeatSectionSeatResponse[]>([]);
  const [selectedSeatId, setSelectedSeatId] = useState<number | null>(null);
  const [tickets, setTickets] = useState<TicketViewResponse[]>([]);
  const [message, setMessage] = useState('');
  const [isBusy, setIsBusy] = useState(false);

  const sectionRequest = useRef<AbortController | null>(null);
  const purchaseAttempt = useRef<{ userId: number; seatId: number; key: string } | null>(null);

  useEffect(() => () => sectionRequest.current?.abort(), []);

  const selectedConcert = useMemo(
    () => concerts.find((concert) => concert.concertId === selectedConcertId) ?? null,
    [concerts, selectedConcertId],
  );
  const selectedSeat = useMemo(
    () => seats.find((seat) => seat.seatId === selectedSeatId) ?? null,
    [seats, selectedSeatId],
  );

  const bootstrapId = useRef(0);

  async function bootstrap() {
    const id = ++bootstrapId.current;
    const active = () => bootstrapId.current === id;
    let refreshed = false;
    try {
      await auth.refresh();
      refreshed = true;
      if (!active()) return;
      const profile = await api.me();
      if (!active()) return;
      setMe(profile);
      setPage(pageForRole(profile.role));
      setMessage('내 정보를 불러왔습니다.');
    } catch (error) {
      if (!active()) return;
      // Network errors, 5xx and unexpected responses are not a session expiry.
      if (!(error instanceof ApiError && error.code === 'REAUTH_REQUIRED')) {
        setPage('unavailable');
        setMessage('');
        return;
      }
      auth.clear();
      const noSession = !refreshed && !(error instanceof SessionExpiredError);
      resetSession(noSession ? '' : errorMessage(error));
    }
  }

  function retryBootstrap() {
    setPage('loading');
    void bootstrap();
  }

  useEffect(() => {
    const unsubscribe = auth.onExpired(() => resetSession('다시 로그인해 주세요'));
    void bootstrap();
    return () => { bootstrapId.current++; unsubscribe(); };
  }, []);

  function resetSession(message: string) {
    cancelSectionRequest();
    purchaseAttempt.current = null;
    setMe(null);
    setPage('login');
    setTickets([]);
    setConcerts([]);
    setSelectedConcertId(null);
    setMessage(message);
  }

  async function run<T>(successMessage: string, action: () => Promise<T>) {
    setIsBusy(true);

    try {
      const result = await action();
      setMessage(successMessage);
      return result;
    } catch (error) {
      setMessage(errorMessage(error));
      return null;
    } finally {
      setIsBusy(false);
    }
  }

  async function loadConcerts(showMessage = true) {
    const result = await run(
      showMessage ? '공연 목록을 새로 불러왔습니다.' : '공연 목록을 불러왔습니다.',
      api.findConcerts,
    );

    if (result) {
      setConcerts(result);
    }
  }

  function cancelSectionRequest() {
    sectionRequest.current?.abort();
    sectionRequest.current = null;
    setIsBusy(false);
  }

  function selectSeat(seatId: number | null) {
    if (seatId !== selectedSeatId) purchaseAttempt.current = null;
    setSelectedSeatId(seatId);
  }

  async function openPurchase(concert: ConcertResponse) {
    cancelSectionRequest();
    purchaseAttempt.current = null;
    setSelectedConcertId(concert.concertId);
    setSelectedSeatId(null);
    setSelectedSection(null);
    setSeats([]);
    setSeatSections([]);
    setTotalAvailableSeats(0);
    setPage('purchase');

    const result = await run(`${concert.title}의 구역별 예매 가능 좌석을 불러왔습니다.`, () =>
      api.findSeatAvailabilitySummary(concert.concertId),
    );

    if (result) {
      setSeatSections(result.sections);
      setTotalAvailableSeats(result.totalAvailable);
    }
  }

  async function selectSection(section: string) {
    if (!selectedConcert) return;

    cancelSectionRequest();
    const controller = new AbortController();
    sectionRequest.current = controller;
    setSelectedSection(section);
    selectSeat(null);
    setSeats([]);
    setIsBusy(true);

    try {
      const result = await api.findSeatSectionAvailability(selectedConcert.concertId, section, controller.signal);
      if (sectionRequest.current !== controller || controller.signal.aborted) return;
      if (result.section === section) {
        setSeats(result.seats);
        setMessage(`${section}구역 좌석을 불러왔습니다.`);
      }
    } catch (error) {
      if (sectionRequest.current === controller && !controller.signal.aborted) {
        setMessage(error instanceof ApiError ? apiErrorMessages[error.code ?? ''] ?? error.message : error instanceof Error ? error.message : String(error));
      }
    } finally {
      if (sectionRequest.current === controller) setIsBusy(false);
    }
  }

  async function handleLogout() {
    const result = await run('로그아웃되었습니다.', async () => ({ already: await api.logout() }));
    if (result) resetSession(result.already ? '이미 로그아웃된 세션입니다' : '로그아웃되었습니다.');
  }

  async function handleDeleteMe() {
    if (!window.confirm('정말 회원 탈퇴하시겠습니까?')) return;
    const success = await run('회원 탈퇴가 완료되었습니다.', async () => {
      await api.deleteMe();
      return true;
    });
    if (success) resetSession('회원 탈퇴가 완료되었습니다.');
  }

  async function handlePurchase() {
    if (!me) {
      setMessage('예매하려면 먼저 로그인하세요.');
      return false;
    }

    if (!selectedSeat || !selectedSection || !selectedConcert) {
      setMessage('예매할 좌석을 먼저 선택하세요.');
      return false;
    }

    const purchasedSeat = selectedSeat;
    const purchasedSection = selectedSection;
    const purchasedConcert = selectedConcert;
    if (purchaseAttempt.current?.userId !== me.userId || purchaseAttempt.current.seatId !== purchasedSeat.seatId) {
      purchaseAttempt.current = { userId: me.userId, seatId: purchasedSeat.seatId, key: createIdempotencyKey() };
    }
    const attempt = purchaseAttempt.current;
    const result = await run('티켓 예매가 완료되었습니다.', async () => {
      try {
        const response = await api.purchaseTicket(purchasedSeat.seatId, attempt.key);
        if (purchaseAttempt.current === attempt) purchaseAttempt.current = null;
        return response;
      } catch (error) {
        if (error instanceof ApiError && error.status >= 400 && error.status < 500 && error.code !== 'REQUEST_IN_PROGRESS') {
          if (purchaseAttempt.current === attempt) purchaseAttempt.current = null;
        }
        throw error;
      }
    });

    if (result) {
      setTickets((current) => [
        {
          ticketId: result.ticketId,
          concertTitle: purchasedConcert.title,
          section: purchasedSection,
          row: purchasedSeat.row,
          col: purchasedSeat.col,
          purchasePrice: result.purchasePrice,
        },
        ...current,
      ]);
      setSeats((current) =>
        current.map((seat) => (seat.seatId === purchasedSeat.seatId ? { ...seat, status: 'SOLD' } : seat)),
      );
      setSeatSections((current) =>
        current.map((section) =>
          section.section === purchasedSection
            ? { ...section, availableCount: Math.max(section.availableCount - 1, 0) }
            : section,
        ),
      );
      setTotalAvailableSeats((current) => Math.max(current - 1, 0));
      setSelectedSeatId(null);
      return true;
    }

    return false;
  }

  async function handleFindMyTickets() {
    if (!me) {
      setMessage('내 티켓을 보려면 먼저 로그인하세요.');
      return;
    }

    const result = await run('내 티켓을 불러왔습니다.', () => api.findMyTickets());

    if (result) {
      setTickets(result);
    }
  }

  async function handleCreateConcert(event: FormEvent) {
    event.preventDefault();

    if (!me) {
      setMessage('공연을 등록하려면 먼저 로그인하세요.');
      return;
    }

    if (concertForm.title.length > 100 || concertForm.venue.length > 255) {
      setMessage('공연명은 100자, 장소는 255자 이하로 입력하세요.');
      return;
    }
    const open = new Date(concertForm.ticketOpenDate).getTime();
    const close = new Date(concertForm.ticketCloseDate).getTime();
    const concert = new Date(concertForm.concertDate).getTime();
    if (!(open < close && close <= concert)) {
      setMessage('예매 일정은 오픈 < 마감 ≤ 공연일 순서로 입력하세요.');
      return;
    }

    const result = await run('공연이 등록되었습니다.', () =>
      api.createConcert({
        ...concertForm,
        concertDate: new Date(concert).toISOString(),
        ticketOpenDate: new Date(open).toISOString(),
        ticketCloseDate: new Date(close).toISOString(),
        price: Number(concertForm.price),
      }),
    );

    if (result) {
      setConcerts((current) => [result, ...current.filter((concert) => concert.concertId !== result.concertId)]);
    }
  }

  return (
    <main className="app-shell">
      {page === 'loading' && <p role="status">로그인 정보를 확인하고 있습니다.</p>}

      {page === 'unavailable' && (
        <section role="alert">
          <p>서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요</p>
          <button type="button" onClick={retryBootstrap}>다시 시도</button>
        </section>
      )}

      {me && page !== 'login' && page !== 'loading' && page !== 'unavailable' && (
        <AppHeader
          currentPage={page}
          me={me}
          onGoArtist={() => setPage('artist-home')}
          onGoFan={() => setPage('fan-home')}
          onLogout={handleLogout}
          onDeleteMe={handleDeleteMe}
          isBusy={isBusy}
        />
      )}

      {page === 'login' && <LoginPage />}

      {page === 'fan-home' && (
        <FanHomePage
          concerts={concerts}
          isBusy={isBusy}
          tickets={tickets}
          onLoadConcerts={() => loadConcerts(true)}
          onLoadTickets={handleFindMyTickets}
          onOpenPurchase={openPurchase}
        />
      )}

      {page === 'artist-home' && me && (
        <ArtistHomePage
          concertForm={concertForm}
          concerts={concerts}
          isBusy={isBusy}
          me={me}
          onConcertFormChange={setConcertForm}
          onCreateConcert={handleCreateConcert}
          onLoadConcerts={() => loadConcerts(true)}
        />
      )}

      {page === 'purchase' && selectedConcert && (
        <PurchasePage
          concert={selectedConcert}
          isBusy={isBusy}
          onBack={() => {
            cancelSectionRequest();
            setPage('fan-home');
          }}
          onPurchase={handlePurchase}
          onSelectSeat={selectSeat}
          onSelectSection={selectSection}
          seats={seats}
          seatSections={seatSections}
          selectedSeat={selectedSeat}
          selectedSeatId={selectedSeatId}
          selectedSection={selectedSection}
          totalAvailableSeats={totalAvailableSeats}
        />
      )}

      <MessagePanel isBusy={isBusy} message={message} />
    </main>
  );
}

const apiErrorMessages: Record<string, string> = {
  REAUTH_REQUIRED: '다시 로그인해 주세요',
  RESOURCE_NOT_FOUND: '요청한 정보를 찾을 수 없습니다.',
  METHOD_NOT_ALLOWED: '지원하지 않는 요청입니다.',
  UNSUPPORTED_MEDIA_TYPE: '지원하지 않는 데이터 형식입니다.',
  CONCERT_NOT_ON_SALE: '현재 예매 기간이 아니거나 취소된 공연입니다.',
  REQUEST_IN_PROGRESS: '예매 요청을 처리 중입니다. 잠시 후 다시 시도하세요.',
  IDEMPOTENCY_CONFLICT: '예매 요청 정보가 이전 요청과 다릅니다. 다시 시도하세요.',
  SEAT_NOT_AVAILABLE: '이미 판매되었거나 예매할 수 없는 좌석입니다. 다른 좌석을 선택하세요.',
  SEAT_LOCK_FAILED: '좌석을 확보하지 못했습니다. 잠시 후 다시 시도하세요.',
  INTERNAL_SERVER_ERROR: '서버 오류가 발생했습니다. 잠시 후 다시 시도하세요.',
};

function pageForRole(role: UserRole): Page {
  return role === 'ARTIST' ? 'artist-home' : 'fan-home';
}

function errorMessage(error: unknown) {
  const message = error instanceof Error ? error.message : String(error);
  return error instanceof ApiError ? apiErrorMessages[error.code ?? ''] ?? message : message;
}

function createIdempotencyKey() {
  if (typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }

  return Array.from(crypto.getRandomValues(new Uint8Array(16)), (byte) => byte.toString(16).padStart(2, '0')).join('');
}

export default App;
