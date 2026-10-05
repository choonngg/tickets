import { LogOut, Music2, Ticket } from 'lucide-react';

import type { UserResponse } from '../types';

interface AppHeaderProps {
  currentPage: string;
  me: UserResponse | null;
  onGoArtist: () => void;
  onGoFan: () => void;
  onLogout: () => void;
  onDeleteMe: () => void;
  isBusy: boolean;
}

export function AppHeader({ currentPage, me, onGoArtist, onGoFan, onLogout, onDeleteMe, isBusy }: AppHeaderProps) {
  return (
    <header className="site-header">
      <div className="brand" aria-label="Book Tickets">
        <Ticket size={22} aria-hidden="true" />
        Book Tickets
      </div>
      <nav className="main-nav" aria-label="주요 메뉴">
        {me?.role === 'FAN' && (
          <button className={currentPage === 'fan-home' ? 'active' : ''} type="button" onClick={onGoFan}>
            팬 메인
          </button>
        )}
        {me?.role === 'ARTIST' && (
          <button className={currentPage === 'artist-home' ? 'active' : ''} type="button" onClick={onGoArtist}>
            아티스트 메인
          </button>
        )}
      </nav>
      <div className="header-actions">
        <span className="login-state active">
          <Music2 size={14} aria-hidden="true" />
          {me ? `${me.name} · ${me.role}` : '게스트'}
        </span>
        <button className="text-button" type="button" onClick={onLogout} disabled={isBusy}>
          <LogOut size={16} aria-hidden="true" />
          로그아웃
        </button>
        <button className="text-button" type="button" onClick={onDeleteMe} disabled={isBusy}>
          회원 탈퇴
        </button>
      </div>
    </header>
  );
}
