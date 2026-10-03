import { FormEvent, useEffect, useMemo, useRef, useState } from 'react';

import { api, ApiError } from './api';
import { AppHeader } from './components/AppHeader';
import { MessagePanel } from './components/MessagePanel';
import { AdminPendingPage } from './pages/AdminPendingPage';
import { ArtistHomePage } from './pages/ArtistHomePage';
import { FanHomePage } from './pages/FanHomePage';
import { LoginPage } from './pages/LoginPage';
import { PurchasePage } from './pages/PurchasePage';
import type {
  ConcertCreateRequest,
  ConcertResponse,
  LoginResponse,
  SeatSectionSeatResponse,
  SeatSectionSummaryResponse,
  SignupRequest,
  TicketViewResponse,
  UserResponse,
  UserRole,
} from './types';

type Page = 'login' | 'fan-home' | 'artist-home' | 'purchase' | 'admin-pending';

const initialAuthForm: SignupRequest = {
  email: 'fan@example.com',
  password: 'password1234',
  name: '팬 사용자',
  role: 'FAN',
};

const initialConcertForm: ConcertCreateRequest = {
  title: '샘플 콘서트',
  venue: 'KSPO DOME',
  concertDate: '2026-06-01T19:00',
  ticketOpenDate: '2026-05-20T12:00',
  ticketCloseDate: '2026-05-31T23:59',
  price: 120000,
};

function App() {
  const [page, setPage] = useState<Page>('login');
  const [authForm, setAuthForm] = useState(initialAuthForm);
  const [concertForm, setConcertForm] = useState(initialConcertForm);
  const [session, setSession] = useState<LoginResponse | null>(() => readSession());
  const [me, setMe] = useState<UserResponse | null>(null);
  const [concerts, setConcerts] = useState<ConcertResponse[]>([]);
  const [selectedConcertId, setSelectedConcertId] = useState<number | null>(null);
  const [seatSections, setSeatSections] = useState<SeatSectionSummaryResponse[]>([]);
  const [selectedSection, setSelectedSection] = useState<string | null>(null);
  const [totalAvailableSeats, setTotalAvailableSeats] = useState(0);
  const [seats, setSeats] = useState<SeatSectionSeatResponse[]>([]);
  const [selectedSeatId, setSelectedSeatId] = useState<number | null>(null);
  const [tickets, setTickets] = useState<TicketViewResponse[]>([]);
  const [message, setMessage] = useState('로그인하면 역할에 맞는 화면으로 이동합니다.');
  const [isBusy, setIsBusy] = useState(false);

  const sectionRequest = useRef<AbortController | null>(null);
  const purchaseAttempt = useRef<{ userId: number; seatId: number; key: string } | null>(null);

  useEffect(() => () => sectionRequest.current?.abort(), []);

  const accessToken = session?.accessToken ?? '';
  const refreshToken = session?.refreshToken ?? '';
  const selectedConcert = useMemo(
    () => concerts.find((concert) => concert.concertId === selectedConcertId) ?? null,
    [concerts, selectedConcertId],
  );
  const selectedSeat = useMemo(
    () => seats.find((seat) => seat.seatId === selectedSeatId) ?? null,
    [seats, selectedSeatId],
  );

  useEffect(() => {
    if (!session) {
      localStorage.removeItem('ticket-session');
      setMe(null);
      setPage('login');
      return;
    }

    localStorage.setItem('ticket-session', JSON.stringify(session));
  }, [session]);

  useEffect(() => {
    if (!session || me) {
      return;
    }

    void loadProfile(session.accessToken);
  }, [me, session]);

  async function run<T>(successMessage: string, action: () => Promise<T>) {
    setIsBusy(true);

    try {
      const result = await action();
      setMessage(successMessage);
      return result;
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : String(error);
      setMessage(error instanceof ApiError ? purchaseErrorMessages[error.code ?? ''] ?? errorMessage : errorMessage);
      return null;
    } finally {
      setIsBusy(false);
    }
  }

  async function loadProfile(token: string) {
    const profile = await run('내 정보를 불러왔습니다.', () => api.me(token));

    if (profile) {
      setMe(profile);
      setPage(pageForRole(profile.role));
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
        setMessage(error instanceof Error ? error.message : String(error));
      }
    } finally {
      if (sectionRequest.current === controller) setIsBusy(false);
    }
  }

  async function handleSignup(event: FormEvent) {
    event.preventDefault();
    const signupForm: SignupRequest = {
      ...authForm,
      role: authForm.role === 'ADMIN' ? 'FAN' : authForm.role,
    };

    await run('회원가입이 완료되었습니다. 이제 로그인할 수 있습니다.', () => api.signup(signupForm));
  }

  async function handleLogin(event: FormEvent) {
    event.preventDefault();

    const loginResult = await run('로그인되었습니다.', () =>
      api.login({ email: authForm.email, password: authForm.password }),
    );

    if (loginResult) {
      setSession(loginResult);
      await loadProfile(loginResult.accessToken);
    }
  }

  async function handleLogout() {
    if (refreshToken) {
      await run('로그아웃되었습니다.', () => api.logout(refreshToken));
    }

    cancelSectionRequest();
    purchaseAttempt.current = null;
    setSession(null);
    setTickets([]);
    setConcerts([]);
    setSelectedConcertId(null);
    setMessage('로그아웃되었습니다.');
  }

  async function handlePurchase() {
    if (!accessToken) {
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
    if (!me) return false;
    if (purchaseAttempt.current?.userId !== me.userId || purchaseAttempt.current.seatId !== purchasedSeat.seatId) {
      purchaseAttempt.current = { userId: me.userId, seatId: purchasedSeat.seatId, key: createIdempotencyKey() };
    }
    const attempt = purchaseAttempt.current;
    const result = await run('티켓 예매가 완료되었습니다.', async () => {
      try {
        const response = await api.purchaseTicket(accessToken, purchasedSeat.seatId, attempt.key);
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
          status: result.status,
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
    if (!accessToken) {
      setMessage('내 티켓을 보려면 먼저 로그인하세요.');
      return;
    }

    const result = await run('내 티켓을 불러왔습니다.', () => api.findMyTickets(accessToken));

    if (result) {
      setTickets(result);
    }
  }

  async function handleCreateConcert(event: FormEvent) {
    event.preventDefault();

    if (!accessToken) {
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
      api.createConcert(accessToken, {
        ...concertForm,
        price: Number(concertForm.price),
      }),
    );

    if (result) {
      setConcerts((current) => [result, ...current.filter((concert) => concert.concertId !== result.concertId)]);
    }
  }

  return (
    <main className="app-shell">
      {page !== 'login' && (
        <AppHeader
          currentPage={page}
          me={me}
          onGoArtist={() => setPage('artist-home')}
          onGoFan={() => setPage('fan-home')}
          onLogout={handleLogout}
        />
      )}

      {page === 'login' && (
        <LoginPage
          authForm={authForm}
          isBusy={isBusy}
          onChange={setAuthForm}
          onLogin={handleLogin}
          onSignup={handleSignup}
        />
      )}

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

      {page === 'admin-pending' && <AdminPendingPage />}

      <MessagePanel isBusy={isBusy} message={message} />
    </main>
  );
}

const purchaseErrorMessages: Record<string, string> = {
  CONCERT_NOT_ON_SALE: '현재 예매 기간이 아니거나 취소된 공연입니다.',
  REQUEST_IN_PROGRESS: '예매 요청을 처리 중입니다. 잠시 후 다시 시도하세요.',
  IDEMPOTENCY_CONFLICT: '예매 요청 정보가 이전 요청과 다릅니다. 다시 시도하세요.',
  SEAT_NOT_AVAILABLE: '이미 판매되었거나 예매할 수 없는 좌석입니다. 다른 좌석을 선택하세요.',
  SEAT_LOCK_FAILED: '좌석을 확보하지 못했습니다. 잠시 후 다시 시도하세요.',
  INTERNAL_SERVER_ERROR: '서버 오류가 발생했습니다. 잠시 후 다시 시도하세요.',
};

function pageForRole(role: UserRole): Page {
  if (role === 'FAN') {
    return 'fan-home';
  }

  if (role === 'ARTIST') {
    return 'artist-home';
  }

  return 'admin-pending';
}

function readSession(): LoginResponse | null {
  const raw = localStorage.getItem('ticket-session');

  if (!raw) {
    return null;
  }

  try {
    return JSON.parse(raw) as LoginResponse;
  } catch {
    return null;
  }
}

function createIdempotencyKey() {
  if (typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }

  return Array.from(crypto.getRandomValues(new Uint8Array(16)), (byte) => byte.toString(16).padStart(2, '0')).join('');
}

export default App;
