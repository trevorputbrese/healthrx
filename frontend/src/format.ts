import type { ReferralStatus, RiskLevel } from './api/types';

const MONEY = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 });
const MONEY_CENTS = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' });

export function money(n: number | undefined | null, cents = false): string {
  if (n === undefined || n === null) return '—';
  return (cents ? MONEY_CENTS : MONEY).format(n);
}

/**
 * Parse a backend date string for display. A bare `YYYY-MM-DD` (a Java LocalDate — patient DOB,
 * refill/start dates, trend-bucket months) must be read as a LOCAL calendar date: `new Date('2026-06-01')`
 * parses as UTC midnight, which `toLocaleDateString` then renders as the PREVIOUS day (or month)
 * in any timezone west of UTC. Building the Date from its parts avoids the shift. Full timestamps
 * (with a `T`) are real instants and parse as-is.
 */
function parseDisplayDate(iso: string): Date {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso);
  return m ? new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3])) : new Date(iso);
}

export function dateOnly(iso: string | undefined | null): string {
  if (!iso) return '—';
  return parseDisplayDate(iso).toLocaleDateString('en-US', { year: 'numeric', month: 'short', day: 'numeric' });
}

/** Month + 2-digit year for a date-only string (e.g. a trend bucket's start), timezone-safe. */
export function monthYear(iso: string | undefined | null): string {
  if (!iso) return '—';
  return parseDisplayDate(iso).toLocaleDateString('en-US', { month: 'short', year: '2-digit' });
}

export function dateTime(iso: string | undefined | null): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return d.toLocaleString('en-US', { month: 'short', day: 'numeric', year: 'numeric', hour: 'numeric', minute: '2-digit' });
}

/**
 * Compact "how long ago" for live feeds; falls back to the full date after a day. Pass the
 * SIMULATED now (from /api/simulation/status) — timestamps in this app live on the sim clock,
 * so wall-clock deltas would be meaningless.
 */
export function relativeTime(iso: string | undefined | null, nowIso?: string): string {
  if (!iso) return '—';
  const now = nowIso ? new Date(nowIso).getTime() : Date.now();
  const seconds = Math.round((now - new Date(iso).getTime()) / 1000);
  if (seconds < 60) return 'just now';
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const d = Math.floor(hours / 24);
  if (d <= 14) return `${d}d ago`;
  return dateTime(iso);
}

export function days(n: number | undefined | null): string {
  if (n === undefined || n === null) return '—';
  return `${n.toFixed(1)} d`;
}

export function percent(n: number | undefined | null): string {
  if (n === undefined || n === null) return '—';
  return `${n}%`;
}

export const STATUS_LABELS: Record<ReferralStatus, string> = {
  ELIGIBILITY_IDENTIFIED: 'Eligibility identified',
  BENEFITS_INVESTIGATION: 'Benefits investigation',
  PRIOR_AUTH_SUBMITTED: 'Prior auth submitted',
  PRIOR_AUTH_APPROVED: 'Prior auth approved',
  PRIOR_AUTH_DENIED: 'Prior auth denied',
  FINANCIAL_ASSISTANCE_REVIEW: 'Financial assistance',
  READY_TO_FILL: 'Ready to fill',
  DELIVERY_SCHEDULED: 'Delivery scheduled',
  ACTIVE_THERAPY: 'Active therapy',
  CANCELLED: 'Cancelled',
};

/** Semantic tone for a status chip. */
export function statusTone(status: ReferralStatus): string {
  switch (status) {
    case 'PRIOR_AUTH_DENIED':
    case 'CANCELLED':
      return 'tone-danger';
    case 'PRIOR_AUTH_APPROVED':
    case 'READY_TO_FILL':
    case 'ACTIVE_THERAPY':
      return 'tone-ok';
    case 'PRIOR_AUTH_SUBMITTED':
    case 'FINANCIAL_ASSISTANCE_REVIEW':
    case 'DELIVERY_SCHEDULED':
      return 'tone-warn';
    default:
      return 'tone-info';
  }
}

export function riskTone(risk: RiskLevel | undefined): string {
  switch (risk) {
    case 'HIGH':
      return 'tone-danger';
    case 'MEDIUM':
      return 'tone-warn';
    case 'LOW':
      return 'tone-ok';
    default:
      return 'tone-muted';
  }
}

export function priorityTone(priority: string): string {
  switch (priority) {
    case 'URGENT':
      return 'tone-danger';
    case 'HIGH':
      return 'tone-warn';
    case 'MEDIUM':
      return 'tone-info';
    default:
      return 'tone-muted';
  }
}

/**
 * Agent-routed tasks carry an "[Agent] " title prefix on the wire. Presentation strips it and
 * flags provenance separately, so "Submit PA for X" clearly reads as the USER's to-do that the
 * agent assigned — not something the agent already did.
 */
export function agentTask(title: string): { assignedByAgent: boolean; title: string } {
  if (title.startsWith('[Agent] ')) {
    return { assignedByAgent: true, title: title.slice('[Agent] '.length) };
  }
  return { assignedByAgent: false, title };
}

export function titleCase(s: string): string {
  return s
    .toLowerCase()
    .split('_')
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join(' ');
}
