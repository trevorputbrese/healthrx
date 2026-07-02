import { ReactNode } from 'react';
import { NavLink } from 'react-router-dom';
import ActingAsSelector from './ActingAsSelector';
import SimulationBar from './SimulationBar';

const NAV = [
  { to: '/queue', label: 'Queue' },
  { to: '/dashboard', label: 'Dashboard' },
  { to: '/lifecycle', label: 'Referral Lifecycle' },
  { to: '/agents', label: 'Agents' },
];

export default function AppLayout({ children }: { children: ReactNode }) {
  return (
    <div className="app-shell">
      <header className="app-header">
        <div className="app-brand">
          <span className="app-brand-mark">Rx</span>
          <span className="app-brand-name">HealthRx</span>
          <span className="app-brand-sub">Specialty Pharmacy Care Operations</span>
        </div>
        <nav className="app-nav">
          {NAV.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              className={({ isActive }) => (isActive ? 'app-nav-link is-active' : 'app-nav-link')}
            >
              {item.label}
            </NavLink>
          ))}
        </nav>
        <div className="app-actor">
          <ActingAsSelector />
        </div>
      </header>
      <SimulationBar />
      <main className="app-main">{children}</main>
    </div>
  );
}
