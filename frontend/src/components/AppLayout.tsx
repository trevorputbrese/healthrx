import { ReactNode } from 'react';
import { NavLink } from 'react-router-dom';
import ActingAsSelector from './ActingAsSelector';
import AgentTicker from './AgentTicker';
import SimulationBar from './SimulationBar';

/**
 * Nav grouped by what the user is doing: working (my queue + my tasks), looking things up
 * (records), understanding the program (insight), and the AI teammates. Groups are visually
 * separated only by hairline dividers (group names stay as aria-labels) — one calm row, nothing
 * hides behind menus on stage. The simulation controls dock as a fixed strip at the bottom of
 * the viewport, console-style, so the header stays product, not demo rig.
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
    links: [
      { to: '/agents', label: 'Agents' },
      { to: '/assistant', label: 'Assistant' },
    ],
  },
];

export default function AppLayout({ children }: { children: ReactNode }) {
  return (
    <div className="app-shell">
      <header className="app-header">
        <div className="app-brand">
          <span className="app-brand-mark">Rx</span>
          <span className="app-brand-text">
            <span className="app-brand-name">HealthRx</span>
            <span className="app-brand-sub">Specialty Pharmacy Care Operations</span>
          </span>
        </div>
        <nav className="app-nav">
          {NAV_GROUPS.map((group) => (
            <div key={group.label} className="app-nav-group" aria-label={group.label}>
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
          ))}
        </nav>
        <div className="app-actor">
          <ActingAsSelector />
        </div>
      </header>
      <AgentTicker />
      <main className="app-main">{children}</main>
      <SimulationBar />
    </div>
  );
}
