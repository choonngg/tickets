import { useEffect, useRef, useState } from 'react';
import { api, ApiError, clearEntryToken, floorToSecondMs } from '../api';
import type { QueueStatusResponse } from '../types';

interface QueuePageProps {
  concertId: number;
  title: string;
  initial: QueueStatusResponse;
  onHome: (message?: string) => void;
  onPurchase: () => void;
}

function serverOffset(serverTime?: string) {
  const parsed = serverTime ? Date.parse(serverTime) : NaN;
  return Number.isNaN(parsed) ? 0 : parsed - Date.now();
}

function waitText(seconds: number | null) {
  if (seconds === null) return '계산 중';
  return seconds >= 0 && seconds < 30 ? '1분 이내' : `약 ${Math.round(seconds / 60)}분`;
}

export function QueuePage({ concertId, title, initial, onHome, onPurchase }: QueuePageProps) {
  const [queue, setQueue] = useState(initial);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const [entryLost, setEntryLost] = useState(false);
  const [now, setNow] = useState(Date.now());
  const offset = useRef(serverOffset(initial.serverTime));
  const request = useRef<AbortController | null>(null);
  const stopped = useRef(false);
  const seconds = queue.expiresAt ? Math.max(0, Math.ceil((floorToSecondMs(queue.expiresAt) - (now + offset.current)) / 1000)) : 0;
  const expired = queue.status === 'EXPIRED' || ((queue.status === 'ADMITTED' || queue.status === 'ACTIVE') && seconds === 0);

  useEffect(() => {
    stopped.current = false;
    return () => { stopped.current = true; request.current?.abort(); };
  }, []);

  useEffect(() => {
    if (expired || (queue.status !== 'ADMITTED' && queue.status !== 'ACTIVE')) return;
    setNow(Date.now());
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [queue, expired]);

  useEffect(() => {
    if (expired) clearEntryToken(concertId);
  }, [expired, concertId]);

  useEffect(() => {
    if (queue.status !== 'WAITING' || busy || stopped.current || queue.nextPollAfterMs <= 0) return;
    const timer = setTimeout(() => { void perform('status'); }, queue.nextPollAfterMs);
    return () => clearTimeout(timer);
  }, [queue, busy]);

  function accept(next: QueueStatusResponse) {
    offset.current = serverOffset(next.serverTime);
    setQueue(next);
  }

  async function perform(action: 'status' | 'enter' | 'register' | 'cancel') {
    if (request.current || stopped.current) return;
    const controller = new AbortController();
    request.current = controller;
    setBusy(true);
    setMessage('');
    if (action === 'register') setEntryLost(false);
    const live = () => !stopped.current && !controller.signal.aborted;
    try {
      if (action === 'cancel') {
        await api.cancelQueue(concertId, controller.signal);
        if (live()) { stopped.current = true; onHome(); }
        return;
      }
      if (action === 'enter') {
        const entry = await api.enterQueue(concertId, controller.signal);
        if (live()) { accept({ ...queue, ...entry, nextPollAfterMs: 0 }); onPurchase(); }
      } else {
        const result = await (action === 'register' ? api.registerQueue : api.queueStatus)(concertId, controller.signal);
        if (live()) accept(result);
      }
      if (live()) setNow(Date.now());
    } catch (error) {
      if (!live()) return;
      if (action === 'enter' && error instanceof ApiError && error.status === 403 && error.code === 'ENTRY_TOKEN_INVALID') {
        // No admission info on the server: do not retry enter; offer re-registration like EXPIRED.
        setEntryLost(true);
        setQueue({ ...queue, status: 'EXPIRED', expiresAt: null });
        return;
      }
      if (error instanceof ApiError && error.status === 409) {
        if (error.code === 'QUEUE_ADMISSION_EXPIRED') {
          setQueue({ ...queue, status: 'EXPIRED', expiresAt: null });
          return;
        }
        if (error.code === 'CONCERT_NOT_ON_SALE') {
          stopped.current = true;
          onHome('현재 예매 기간이 아니거나 취소된 공연입니다.');
          return;
        }
        if (error.code === 'QUEUE_NOT_ADMITTED') {
          try {
            const result = await api.queueStatus(concertId, controller.signal);
            if (live()) { accept(result); setNow(Date.now()); }
          } catch (statusError) {
            if (live()) setMessage(statusError instanceof Error ? statusError.message : String(statusError));
          }
          return;
        }
      }
      setMessage(error instanceof Error ? error.message : String(error));
    } finally {
      request.current = null;
      if (live()) setBusy(false);
    }
  }

  const remaining = `${Math.floor(seconds / 60)}분 ${seconds % 60}초`;
  return (
    <section className="page-panel" aria-label="대기열">
      <h1>{title} 대기열</h1>
      {expired ? <>
        <p role="status">{entryLost ? '입장 정보가 만료되었습니다. 다시 대기열에 서 주세요' : '대기 시간이 만료되었습니다'}</p>
        <button type="button" disabled={busy} onClick={() => void perform('register')}>다시 줄 서기</button>
      </> : <>
        {queue.status === 'WAITING' && <p role="status">내 순번 {queue.rank}번 / 내 뒤 {queue.behind}명 / {waitText(queue.estimatedWaitSeconds)}</p>}
        {queue.status === 'ADMITTED' && <>
          <p role="status">입장 가능 남은 시간 {remaining}</p>
          <button type="button" disabled={busy} onClick={() => void perform('enter')}>입장하기</button>
        </>}
        {queue.status === 'ACTIVE' && <>
          <p role="status">남은 이용 시간 {remaining}</p>
          <button type="button" disabled={busy} onClick={() => void perform('enter')}>입장하기</button>
        </>}
        {(queue.status === 'WAITING' || queue.status === 'ADMITTED') && <button className="secondary-button" type="button" disabled={busy} onClick={() => void perform('cancel')}>대기 취소</button>}
      </>}
      {message && <div role="alert"><p>{message}</p><button type="button" disabled={busy} onClick={() => void perform('status')}>상태 다시 확인</button></div>}
    </section>
  );
}
