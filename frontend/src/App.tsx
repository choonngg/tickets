import { FormEvent, useEffect, useMemo, useRef, useState } from 'react';

import { api, auth, ApiError, SessionExpiredError, getEntryToken, getEntrySeconds, clearEntryToken } from './api';
import { AppHeader } from './components/AppHeader';
import { MessagePanel } from './components/MessagePanel';
import { ArtistHomePage } from './pages/ArtistHomePage';
import { FanHomePage } from './pages/FanHomePage';
import { QueuePage } from './pages/QueuePage';
import { PurchasePage } from './pages/PurchasePage';
import type {
  QueueStatusResponse,
  ConcertCreateRequest,
  ConcertResponse,
  SeatSectionSeatResponse,
  SeatSectionSummaryResponse,
  TicketViewResponse,
  UserResponse,
  UserRole,
} from './types';

type Page = 'loading' | 'unavailable' | 'fan-home' | 'artist-home' | 'purchase' | 'queue';

const initialConcertForm: ConcertCreateRequest = {
  title: '샘플 콘서트',
  venue: 'KSPO DOME',
  concertDate: '2026-06-01T19:00',
  ticketOpenDate: '2026-05-20T12:00',
  ticketCloseDate: '2026-05-31T23:59',
  price: 120000,
};

function App() {
  const [queueInitial, setQueueInitial] = useState<QueueStatusResponse | null>(null);
  const queueRegistration = useRef<AbortController | null>(null);
  const [entryError, setEntryError] = useState<'expired' | 'required' | null>(null);
  const [entrySeconds, setEntrySeconds] = useState(0);
  const [page, setPage] = useState<Page>('loading');
  const [concertForm, setConcertForm] = useState(initialConcertForm);
  const [me, setMe] = useState<UserResponse | null>(null);
  const [concerts, setConcerts] = useState<ConcertResponse[]>([]);
  const [selectedConcertId, setSelectedConcertId] = useState<number | null>(null);
  const [seatSections, setSeatSections] = useState<SeatSectionSummaryResponse[]>([]);
  const [selectedSection, setSelectedSection] = useState<string | null>(null);
  const [totalAvailableSeats, setTotalAvailableSeats] = useState(0);
  const [seats, setSeats] = useState<SeatSectionSeatResponse[]>([]);
  const [selectedSeats, setSelectedSeats] = useState<(SeatSectionSeatResponse & { section: string })[]>([]);
  const [tickets, setTickets] = useState<TicketViewResponse[]>([]);
  const [message, setMessage] = useState('');
  const [isBusy, setIsBusy] = useState(false);

  const sectionRequest = useRef<AbortController | null>(null);
  const purchaseAttempt = useRef<{ userId: number; combination: string; key: string } | null>(null);

  useEffect(() => () => { sectionRequest.current?.abort(); queueRegistration.current?.abort(); }, []);

  const selectedConcert = useMemo(
    () => concerts.find((concert) => concert.concertId === selectedConcertId) ?? null,
    [concerts, selectedConcertId],
  );
  const purchaseCounts = tickets.reduce<Record<number, number>>((counts, ticket) => {
    counts[ticket.concertId] = (counts[ticket.concertId] ?? 0) + 1;
    return counts;
  }, {});
  const remainingAllowance = Math.max(0, 4 - (purchaseCounts[selectedConcertId ?? -1] ?? 0));

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
      let list: ConcertResponse[];
      try {
        list = await api.findConcerts();
      }
      catch (error) {
        if (!active()) return;
        setMe(profile);
        setPage(pageForRole(profile.role));
        setMessage(errorMessage(error));
        return;
      }
      if (!active()) return;
      const owned = await api.findMyTickets();
      if (!active()) return;
      setTickets(owned);
      setConcerts(list);
      setMe(profile);
      setPage(pageForRole(profile.role));
      setMessage('내 정보를 불러왔습니다.');
      const pending = sessionStorage.getItem('pendingConcertId');
      sessionStorage.removeItem('pendingConcertId');
      const concert = list.find(item => String(item.concertId) === pending);
      if (concert?.status === 'ON_SALE' && owned.filter(ticket => ticket.concertId === concert.concertId).length < 4) await joinQueue(concert, true);
    } catch (error) {
      if (!active()) return;
      // Network errors, 5xx and unexpected responses are not a session expiry.
      if (!(error instanceof ApiError && error.code === 'REAUTH_REQUIRED')) {
        setPage('unavailable');
        setMessage('');
        return;
      }
      auth.clear();
      sessionStorage.removeItem('pendingConcertId');
      const noSession = !refreshed && !(error instanceof SessionExpiredError);
      resetSession(noSession ? '' : errorMessage(error));
      try {
        const list = await api.findConcerts();
        if (active()) setConcerts(list);
      } catch (listError) { if (active()) setMessage(errorMessage(listError)); }
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
    cancelQueueRegistration();
    setQueueInitial(null);
    cancelSectionRequest();
    purchaseAttempt.current = null;
    setMe(null);
    setPage('fan-home');
    setTickets([]);
    setSelectedConcertId(null);
    setMessage(message);
  }

  async function run<T>(successMessage: string, action: () => Promise<T>) {
    setIsBusy(true);

    try {
      const result = await action();
      if (result !== null) setMessage(successMessage);
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
      async () => {
        const list = await api.findConcerts();
        if (me) setTickets(await api.findMyTickets());
        return list;
      },
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

  function selectSeat(seatId: number) {
    if (selectedSeats.some(seat => seat.seatId === seatId)) {
      setSelectedSeats(selectedSeats.filter(seat => seat.seatId !== seatId));
      return;
    }
    if (selectedSeats.length >= Math.min(4, remainingAllowance)) {
      setMessage(`최대 ${Math.min(4, remainingAllowance)}석까지 선택할 수 있습니다.`);
      return;
    }
    const seat = seats.find(seat => seat.seatId === seatId);
    if (seat?.status === 'AVAILABLE' && selectedSection) setSelectedSeats([...selectedSeats, { ...seat, section: selectedSection }]);
  }

  function cancelQueueRegistration() {
    queueRegistration.current?.abort();
    queueRegistration.current = null;
  }

  function goHome(message?: string) {
    const registering = queueRegistration.current !== null;
    cancelQueueRegistration();
    if (registering) setIsBusy(false);
    setPage('fan-home');
    if (message) setMessage(message);
  }

  async function joinQueue(concert: ConcertResponse, restored = false) {
    if (!me && !restored) {
      sessionStorage.setItem('pendingConcertId', String(concert.concertId));
      window.location.assign('/api/auth/google/login');
      return;
    }
    if (queueRegistration.current || concert.status !== 'ON_SALE' || (purchaseCounts[concert.concertId] ?? 0) >= 4) return;
    const controller = new AbortController();
    queueRegistration.current = controller;
    setIsBusy(true);
    try {
      const result = await api.registerQueue(concert.concertId, controller.signal);
      if (controller.signal.aborted) return;
      setSelectedConcertId(concert.concertId);
      setQueueInitial(result);
      setMessage('');
      setPage('queue');
    } catch (error) {
      if (!controller.signal.aborted) setMessage(errorMessage(error));
    } finally {
      if (queueRegistration.current === controller) {
        queueRegistration.current = null;
        setIsBusy(false);
      }
    }
  }

  async function openPurchase(concert: ConcertResponse) {
    cancelQueueRegistration();
    cancelSectionRequest();
    if (!getEntryToken(concert.concertId)) {
      await joinQueue(concert);
      return;
    }
    setEntryError(null);
    setEntrySeconds(getEntrySeconds(concert.concertId));
    setSelectedConcertId(concert.concertId);
    setSelectedSeats([]);
    setSelectedSection(null);
    setSeats([]);
    setSeatSections([]);
    setTotalAvailableSeats(0);
    setPage('purchase');

    const result = await run(`${concert.title}의 구역별 예매 가능 좌석을 불러왔습니다.`,
      async () => {
        setTickets(await api.findMyTickets());
        return api.findSeatAvailabilitySummary(concert.concertId);
      },
    );

    if (result) {
      setSeatSections(result.sections);
      setTotalAvailableSeats(result.totalAvailable);
    }
  }

  useEffect(() => {
    if (page !== 'purchase' || selectedConcertId === null || entryError) return;
    function tick() {
      const seconds = getEntrySeconds(selectedConcertId!);
      setEntrySeconds(seconds);
      if (seconds === 0) setEntryError('expired');
      else if (!getEntryToken(selectedConcertId!)) {
        setMessage('대기열을 통해 입장해 주세요');
        if (selectedConcert) void joinQueue(selectedConcert);
      }
    }
    tick();
    const timer = setInterval(tick, 1000);
    return () => clearInterval(timer);
  }, [page, selectedConcertId, entryError]);

  async function selectSection(section: string) {
    if (!selectedConcert) return;

    cancelSectionRequest();
    const controller = new AbortController();
    sectionRequest.current = controller;
    setSelectedSection(section);
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

    if (!selectedSeats.length || !selectedConcert) {
      setMessage('예매할 좌석을 먼저 선택하세요.');
      return false;
    }

    if (selectedSeats.length > remainingAllowance) {
      setMessage('공연당 최대 4매까지 구매할 수 있습니다');
      return false;
    }
    const entryToken = getEntryToken(selectedConcert.concertId);
    if (!entryToken) {
      setEntryError('expired');
      return false;
    }
    const purchasedSeats = selectedSeats;
    const seatIds = purchasedSeats.map(seat => seat.seatId).sort((a, b) => a - b);
    const combination = seatIds.join(',');
    const purchasedConcert = selectedConcert;
    if (purchaseAttempt.current?.userId !== me.userId || purchaseAttempt.current.combination !== combination) {
      purchaseAttempt.current = { userId: me.userId, combination, key: createIdempotencyKey() };
    }
    const attempt = purchaseAttempt.current;
    const result = await run('티켓 예매가 완료되었습니다.', async () => {
      try {
        const response = await api.purchaseTicket(seatIds, attempt.key, entryToken);
        if (purchaseAttempt.current === attempt) purchaseAttempt.current = null;
        return response;
      } catch (error) {
        if (error instanceof ApiError && error.status === 403 && error.code?.startsWith('ENTRY_TOKEN_')) {
          clearEntryToken(purchasedConcert.concertId);
          setEntryError(error.code === 'ENTRY_TOKEN_EXPIRED' ? 'expired' : 'required');
          return null;
        }
        if (!(error instanceof ApiError)) {
          const recovered = await api.findMyTickets();
          setTickets(recovered);
          if (purchasedSeats.every(seat => recovered.some(ticket =>
            ticket.concertId === purchasedConcert.concertId && ticket.section === seat.section && ticket.row === seat.row && ticket.col === seat.col
          ))) clearEntryToken(purchasedConcert.concertId);
          goHome('구매 응답을 받지 못했습니다. 내 티켓에서 구매 결과를 확인해 주세요.');
          return null;
        }
        if (error instanceof ApiError && error.code === 'PURCHASE_LIMIT_EXCEEDED') {
          setTickets(await api.findMyTickets());
          setSelectedSeats([]);
        }
        if (error instanceof ApiError && error.code === 'SEAT_NOT_AVAILABLE') {
          const summary = await api.findSeatAvailabilitySummary(purchasedConcert.concertId);
          setSeatSections(summary.sections);
          setTotalAvailableSeats(summary.totalAvailable);
          if (selectedSection) {
            const section = await api.findSeatSectionAvailability(purchasedConcert.concertId, selectedSection);
            setSeats(section.seats);
          }
          setSelectedSeats([]);
        }
        if (error instanceof ApiError && error.status >= 400 && error.status < 500 && error.code !== 'REQUEST_IN_PROGRESS') {
          if (purchaseAttempt.current === attempt) purchaseAttempt.current = null;
        }
        throw error;
      }
    });

    if (result) {
      clearEntryToken(purchasedConcert.concertId);
      setTickets(current => [
        ...result.map(ticket => {
          const seat = purchasedSeats.find(seat => seat.seatId === ticket.seatId)!;
          return { ticketId: ticket.ticketId, concertId: purchasedConcert.concertId, concertTitle: purchasedConcert.title,
            section: seat.section, row: seat.row, col: seat.col, purchasePrice: ticket.purchasePrice };
        }),
        ...current,
      ]);
      setSelectedSeats([]);
      goHome('티켓 예매가 완료되었습니다.');
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

      {me && page !== 'loading' && page !== 'unavailable' && (
        <AppHeader
          currentPage={page}
          me={me}
          onGoArtist={() => setPage('artist-home')}
          onGoFan={() => goHome()}
          onLogout={handleLogout}
          onDeleteMe={handleDeleteMe}
          isBusy={isBusy}
        />
      )}

      {!me && page === 'fan-home' && <header className="site-header">
        <button type="button" onClick={() => window.location.assign('/api/auth/google/login')}>Google로 로그인</button>
      </header>}

      {page === 'fan-home' && (
        <FanHomePage
          concerts={concerts}
          isBusy={isBusy}
          tickets={tickets}
          purchaseCounts={purchaseCounts}
          onLoadConcerts={() => loadConcerts(true)}
          onLoadTickets={handleFindMyTickets}
          authenticated={!!me}
          onJoinQueue={joinQueue}
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

      {page === 'queue' && selectedConcert && queueInitial && (
        <QueuePage
          key={selectedConcert.concertId}
          concertId={selectedConcert.concertId}
          title={selectedConcert.title}
          initial={queueInitial}
          onHome={goHome}
          onPurchase={() => void openPurchase(selectedConcert)}
        />
      )}

      {page === 'purchase' && selectedConcert && (
        entryError ? <section className="page-panel" role="alert">
          <p>{entryError === 'expired' ? '이용 시간이 끝났습니다. 다시 대기열에 서 주세요' : '대기열을 통해 입장해 주세요'}</p>
          <button type="button" disabled={isBusy} onClick={() => void joinQueue(selectedConcert)}>{entryError === 'expired' ? '다시 줄 서기' : '대기열로'}</button>
        </section> : <>
        <PurchasePage
          remainingSeconds={entrySeconds}
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
          selectedSeats={selectedSeats}
          remainingAllowance={remainingAllowance}
          selectedSection={selectedSection}
          totalAvailableSeats={totalAvailableSeats}
        />
        </>
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
  QUEUE_ALREADY_ACTIVE: '이미 입장한 상태에서는 대기를 취소할 수 없습니다.',
  REQUEST_IN_PROGRESS: '예매 요청을 처리 중입니다. 잠시 후 다시 시도하세요.',
  IDEMPOTENCY_CONFLICT: '예매 요청 정보가 이전 요청과 다릅니다. 다시 시도하세요.',
  PURCHASE_LIMIT_EXCEEDED: '공연당 최대 4매까지 구매할 수 있습니다',
  SEAT_NOT_AVAILABLE: '선택한 좌석 중 이미 판매된 좌석이 있습니다',
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
