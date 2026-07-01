import type { ReferralStatus, RiskLevel } from './api/types';

const MONEY = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 });
const MONEY_CENTS = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' });

export function money(n: number | undefined | null, cents = false): string {
  if (n === undefined || n === null) return '—';
  return (cents ? MONEY_CENTS : MONEY).format(n);
}

export function dateOnly(iso: string | undefined | null): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return d.toLocaleDateString('en-US', { year: 'numeric', month: 'short', day: 'numeric' });
}

export function dateTime(iso: string | undefined | null): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return d.toLocaleString('en-US', { month: 'short', day: 'numeric', year: 'numeric', hour: 'numeric', minute: '2-digit' });
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

export function titleCase(s: string): string {
  return s
    .toLowerCase()
    .split('_')
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join(' ');
}
