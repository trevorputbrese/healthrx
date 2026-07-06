import { ReactNode } from 'react';
import { NavLink } from 'react-router-dom';
import ActingAsSelector from './ActingAsSelector';
import AgentTicker from './AgentTicker';
import SimulationBar from './SimulationBar';

/**
 * Nav grouped by what the user is doing: working (my queue + my tasks), looking things up
 * (records), understanding the program (insight), and the AI teammates. Groups render with
 * subtle dividers so everything stays one visible row — nothing hides behind menus on stage.
 */
const NAV_GROUPS: { label: string; links: { to: string; label: string; end?: boolean }[] }[] = [
  {
    label: 'Work',
    links: [
      { to: '/queue', label: 'Queue' },
      { to: '/tasks', label: 'My Tasks' },
    ],
  },
  {
    label: 'Records',
    links: [
      { to: '/referrals', label: 'Referrals', end: true },
      { to: '/patients', label: 'Patients' },
    ],
  },
  {
    label: 'Insight',
    links: [
      { to: '/dashboard', label: 'Dashboard' },
      { to: '/lifecycle', label: 'Lifecycle' },
    ],
  },
  {
    label: 'AI',
    links: [{ to: '/agents', label: 'Agents' }],
  },
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
          {NAV_GROUPS.map((group) => (
            <div key={group.label} className="app-nav-group" aria-label={group.label}>
              <span className="app-nav-group-label" aria-hidden>
                {group.label}
              </span>
              <div className="app-nav-group-links">
                {group.links.map((item) => (
                  <NavLink
                    key={item.to}
                    to={item.to}
                    end={item.end}
                    className={({ isActive }) => (isActive ? 'app-nav-link is-active' : 'app-nav-link')}
                  >
                    {item.label}
                  </NavLink>
                ))}
              </div>
            </div>
          ))}
        </nav>
        <div className="app-actor">
          <ActingAsSelector />
        </div>
      </header>
      <SimulationBar />
      <AgentTicker />
      <main className="app-main">{children}</main>
    </div>
  );
}
