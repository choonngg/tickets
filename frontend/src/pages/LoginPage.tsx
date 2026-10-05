import { LogIn } from 'lucide-react';

export function LoginPage() {
  return (
    <section className="login-page">
      <div className="login-hero">
        <p className="eyebrow">LIVE TICKET BOOKING</p>
        <h1>Google 계정으로 시작하는 티켓 예매</h1>
        <p>팬은 공연을 고르고 좌석을 구매하고, 아티스트는 공연을 등록하고 관리합니다.</p>
      </div>
      <div className="auth-card">
        <div className="section-heading form-heading">
          <div>
            <p className="eyebrow">ACCOUNT</p>
            <h2>로그인</h2>
          </div>
        </div>
        <button type="button" onClick={() => window.location.assign('/api/auth/google/login')}>
          <LogIn size={16} aria-hidden="true" />
          Google로 로그인
        </button>
      </div>
    </section>
  );
}
