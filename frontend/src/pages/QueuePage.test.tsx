import { StrictMode } from 'react';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, ApiError } from '../api';
import { QueuePage } from './QueuePage';
import type { QueueStatusResponse } from '../types';

const waiting: QueueStatusResponse = { concertId: 1, status: 'WAITING', rank: 3, behind: 7, nextPollAfterMs: 3000, estimatedWaitSeconds: 120, expiresAt: null };
const home = vi.fn();
const purchase = vi.fn();
function show(initial = waiting) {
  return render(<QueuePage concertId={1} title="콘서트" initial={initial} onHome={home} onPurchase={purchase} />);
}
const advance = (ms: number) => act(async () => { await vi.advanceTimersByTimeAsync(ms); });
const click = (name: string) => act(async () => { fireEvent.click(screen.getByRole('button', { name })); });

describe('QueuePage', () => {
  beforeEach(() => { vi.useFakeTimers(); vi.setSystemTime(new Date('2030-01-01T00:00:00Z')); home.mockReset(); purchase.mockReset(); });
  afterEach(() => { cleanup(); vi.useRealTimers(); vi.restoreAllMocks(); });

  it('shows exact position and uses each server interval without overlapping pending requests', async () => {
    let release!: (value: QueueStatusResponse) => void;
    const status = vi.spyOn(api, 'queueStatus').mockResolvedValueOnce({ ...waiting, nextPollAfterMs: 10000, estimatedWaitSeconds: null })
      .mockImplementationOnce(() => new Promise(resolve => { release = resolve; }))
      .mockResolvedValue(waiting);
    const view = show();
    expect(screen.getByText('내 순번 3번 / 내 뒤 7명 / 약 2분')).toBeInTheDocument();
    await advance(2999); expect(status).not.toHaveBeenCalled();
    await advance(1); expect(status).toHaveBeenCalledTimes(1);
    expect(screen.getByText(/계산 중/)).toBeInTheDocument();
    await advance(9999); expect(status).toHaveBeenCalledTimes(1);
    await advance(1); expect(status).toHaveBeenCalledTimes(2);
    await advance(30000); expect(status).toHaveBeenCalledTimes(2);
    await act(async () => { release(waiting); });
    await advance(2999); expect(status).toHaveBeenCalledTimes(2);
    await advance(1); expect(status).toHaveBeenCalledTimes(3);
    view.unmount(); await advance(30000); expect(status).toHaveBeenCalledTimes(3);
  });

  it('keeps one polling chain under StrictMode and stops when admitted', async () => {
    const status = vi.spyOn(api, 'queueStatus').mockResolvedValue({ ...waiting, status: 'ADMITTED', nextPollAfterMs: 0, expiresAt: '2030-01-01T00:01:00Z' });
    render(<StrictMode><QueuePage concertId={1} title="콘서트" initial={waiting} onHome={home} onPurchase={purchase} /></StrictMode>);
    await advance(3000); expect(status).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('button', { name: '입장하기' })).toBeInTheDocument();
    await advance(30000); expect(status).toHaveBeenCalledTimes(1);
  });

  it('expires admission against the absolute server deadline', async () => {
    show({ ...waiting, status: 'ADMITTED', nextPollAfterMs: 0, expiresAt: '2030-01-01T00:00:02Z' });
    await advance(2000);
    expect(screen.getByText('대기 시간이 만료되었습니다')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '입장하기' })).not.toBeInTheDocument();
    expect(vi.getTimerCount()).toBe(0);
  });

  it('ignores a response received after unmount', async () => {
    let release!: (value: QueueStatusResponse) => void;
    const status = vi.spyOn(api, 'queueStatus').mockImplementation(() => new Promise(resolve => { release = resolve; }));
    const view = show(); await advance(3000); view.unmount();
    await act(async () => { release(waiting); });
    await advance(30000); expect(status).toHaveBeenCalledTimes(1);
  });

  it('enters from ADMITTED and immediately opens purchase without another navigation button', async () => {
    vi.spyOn(api, 'enterQueue').mockResolvedValue({ concertId: 1, status: 'ACTIVE', entryToken: 'entry-secret', expiresAt: '2030-01-01T00:00:05Z', serverTime: '2030-01-01T00:00:00Z' });
    const status = vi.spyOn(api, 'queueStatus');
    show({ ...waiting, status: 'ADMITTED', nextPollAfterMs: 0, expiresAt: '2030-01-01T00:00:10Z' });
    expect(screen.getByText('입장 가능 남은 시간 0분 10초')).toBeInTheDocument();
    await click('입장하기');
    expect(screen.getByText('남은 이용 시간 0분 5초')).toBeInTheDocument();
    await advance(1000); expect(screen.getByText('남은 이용 시간 0분 4초')).toBeInTheDocument();
    expect(purchase).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('button', { name: '예매하러 가기' })).not.toBeInTheDocument();
    await advance(4000); expect(screen.getByText('대기 시간이 만료되었습니다')).toBeInTheDocument();
    expect(status).not.toHaveBeenCalled();
  });

  it.each(['EXPIRED', 'QUEUE_ADMISSION_EXPIRED'])('allows registration again after %s', async code => {
    vi.spyOn(api, 'enterQueue').mockRejectedValue(new ApiError('expired', 409, code));
    const register = vi.spyOn(api, 'registerQueue').mockResolvedValue(waiting);
    show({ ...waiting, status: code === 'EXPIRED' ? 'EXPIRED' : 'ADMITTED', expiresAt: '2030-01-01T00:00:10Z', nextPollAfterMs: 0 });
    if (code !== 'EXPIRED') await click('입장하기');
    expect(screen.getByText('대기 시간이 만료되었습니다')).toBeInTheDocument();
    await click('다시 줄 서기'); expect(register).toHaveBeenCalledTimes(1);
    expect(screen.getByText(/내 순번 3번/)).toBeInTheDocument();
  });

  it('shows re-registration on 403 ENTRY_TOKEN_INVALID without retrying enter', async () => {
    const enter = vi.spyOn(api, 'enterQueue').mockRejectedValue(new ApiError('no admission', 403, 'ENTRY_TOKEN_INVALID'));
    const register = vi.spyOn(api, 'registerQueue').mockResolvedValue(waiting);
    show({ ...waiting, status: 'ADMITTED', expiresAt: '2030-01-01T00:00:10Z', nextPollAfterMs: 0 });
    await click('입장하기');
    expect(screen.getByText('입장 정보가 만료되었습니다. 다시 대기열에 서 주세요')).toBeInTheDocument();
    await advance(10000); expect(enter).toHaveBeenCalledTimes(1);
    await click('다시 줄 서기'); expect(register).toHaveBeenCalledTimes(1);
    expect(screen.getByText(/내 순번 3번/)).toBeInTheDocument();
  });

  it('recovers an admission race by querying status', async () => {
    vi.spyOn(api, 'enterQueue').mockRejectedValue(new ApiError('race', 409, 'QUEUE_NOT_ADMITTED'));
    const status = vi.spyOn(api, 'queueStatus').mockResolvedValue(waiting);
    show({ ...waiting, status: 'ADMITTED', nextPollAfterMs: 0, expiresAt: '2030-01-01T00:00:10Z' });
    await click('입장하기'); expect(status).toHaveBeenCalledTimes(1);
    expect(screen.getByText(/내 순번 3번/)).toBeInTheDocument();
  });

  it.each(['WAITING', 'ADMITTED'] as const)('cancels %s and stops polling', async status => {
    const cancel = vi.spyOn(api, 'cancelQueue').mockResolvedValue(undefined);
    const poll = vi.spyOn(api, 'queueStatus');
    show({ ...waiting, status, expiresAt: status === 'ADMITTED' ? '2030-01-01T00:01:00Z' : null }); await click('대기 취소');
    expect(cancel).toHaveBeenCalledTimes(1); expect(home).toHaveBeenCalledTimes(1);
    await advance(30000); expect(poll).not.toHaveBeenCalled();
  });

  it('returns home with an explanation when sales close', async () => {
    vi.spyOn(api, 'registerQueue').mockRejectedValue(new ApiError('closed', 409, 'CONCERT_NOT_ON_SALE'));
    show({ ...waiting, status: 'EXPIRED' }); await click('다시 줄 서기');
    expect(home).toHaveBeenCalledWith('현재 예매 기간이 아니거나 취소된 공연입니다.');
  });

  it.each([120000, -120000])('counts down against server time when the client clock is off by %i ms', async skew => {
    const serverNow = Date.now();
    vi.setSystemTime(serverNow + skew);
    show({ ...waiting, status: 'ADMITTED', nextPollAfterMs: 0, expiresAt: new Date(serverNow + 60000).toISOString(), serverTime: new Date(serverNow).toISOString() });
    expect(screen.getByText('입장 가능 남은 시간 1분 0초')).toBeInTheDocument();
    await advance(10000);
    expect(screen.getByText('입장 가능 남은 시간 0분 50초')).toBeInTheDocument();
    expect(screen.queryByText('대기 시간이 만료되었습니다')).not.toBeInTheDocument();
  });

  it('shows "1분 이내" for waits under 30 seconds', () => {
    show({ ...waiting, estimatedWaitSeconds: 25 });
    expect(screen.getByText('내 순번 3번 / 내 뒤 7명 / 1분 이내')).toBeInTheDocument();
  });
});
