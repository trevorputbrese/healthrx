import { ReactNode } from 'react';
import type { Priority, ReferralStatus, RiskLevel } from '../api/types';
import { priorityTone, riskTone, STATUS_LABELS, statusTone, titleCase } from '../format';

export function StatusBadge({ status }: { status: ReferralStatus }) {
  return <span className={`badge ${statusTone(status)}`}>{STATUS_LABELS[status]}</span>;
}

export function RiskBadge({ risk }: { risk?: RiskLevel }) {
  if (!risk) return <span className="badge tone-muted">No therapy</span>;
  return <span className={`badge ${riskTone(risk)}`}>{titleCase(risk)} risk</span>;
}

export function PriorityBadge({ priority }: { priority: Priority }) {
  return <span className={`badge ${priorityTone(priority)}`}>{titleCase(priority)}</span>;
}

export function Chip({ children, tone = 'tone-muted' }: { children: ReactNode; tone?: string }) {
  return <span className={`badge ${tone}`}>{children}</span>;
}

/** Wraps query render states with consistent loading / error / empty handling. */
export function StateBlock<T>({
  query,
  empty,
  children,
}: {
  query: { isLoading: boolean; isError: boolean; error?: unknown; data?: T };
  empty?: (data: T) => boolean;
  children: (data: T) => ReactNode;
}) {
  if (query.isLoading) return <Loading />;
  if (query.isError) {
    const message = query.error instanceof Error ? query.error.message : 'Something went wrong.';
    return <ErrorBox message={message} />;
  }
  if (query.data === undefined) return <Loading />;
  if (empty && empty(query.data)) return <EmptyState />;
  return <>{children(query.data)}</>;
}

export function Loading({ label = 'Loading…' }: { label?: string }) {
  return (
    <div className="state-block">
      <div className="spinner" aria-hidden />
      <span>{label}</span>
    </div>
  );
}

export function ErrorBox({ message }: { message: string }) {
  return (
    <div className="state-block state-error" role="alert">
      <strong>Couldn’t load this.</strong>
      <span>{message}</span>
    </div>
  );
}

export function EmptyState({ message = 'Nothing to show yet.' }: { message?: string }) {
  return (
    <div className="state-block state-empty">
      <span>{message}</span>
    </div>
  );
}

export function Tile({ label, value, sub }: { label: string; value: ReactNode; sub?: ReactNode }) {
  return (
    <div className="tile">
      <div className="tile-label">{label}</div>
      <div className="tile-value">{value}</div>
      {sub !== undefined && <div className="tile-sub">{sub}</div>}
    </div>
  );
}

/**
 * Clickable table header that toggles "field,asc" / "field,desc" sorting. The arrow shows the
 * active direction; inactive columns show a faint two-way hint.
 */
export function SortableTh({
  label,
  field,
  sort,
  onSort,
  className,
}: {
  label: string;
  field: string;
  sort: string | undefined;
  onSort: (sort: string) => void;
  className?: string;
}) {
  const [curField, curDir] = (sort ?? '').split(',');
  const active = curField === field;
  const next = active && curDir === 'asc' ? 'desc' : 'asc';
  return (
    <th className={`th-sort ${active ? 'is-sorted' : ''} ${className ?? ''}`} aria-sort={active ? (curDir === 'asc' ? 'ascending' : 'descending') : undefined}>
      <button type="button" className="th-sort-btn" onClick={() => onSort(`${field},${next}`)}>
        {label}
        <span className="th-sort-icon" aria-hidden>
          {active ? (curDir === 'asc' ? '▲' : '▼') : '↕'}
        </span>
      </button>
    </th>
  );
}

export function Card({ title, action, children }: { title?: ReactNode; action?: ReactNode; children: ReactNode }) {
  return (
    <section className="card">
      {(title || action) && (
        <header className="card-head">
          {title && <h2 className="card-title">{title}</h2>}
          {action}
        </header>
      )}
      <div className="card-body">{children}</div>
    </section>
  );
}
