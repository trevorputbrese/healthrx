import { dateOnly } from '../format';
import type { ReferralStatus } from '../api/types';

type Tone = 'completed' | 'current' | 'skipped' | 'upcoming';

interface NodeModel {
  label: string;
  tone: Tone;
  /** Extra semantic accent (a PA decision shows green/red). */
  accent?: 'ok' | 'danger';
  date?: string;
}

interface Props {
  /** The referral's current status, or undefined for the generic (unselected) lifecycle. */
  current?: ReferralStatus;
  /** Map of status -> ISO date the referral entered it (from its history). */
  reached: Partial<Record<ReferralStatus, string>>;
  labelFor: (status: ReferralStatus) => string;
}

// Position of each status along the happy path. The PA decision (approved/denied) shares rank 3.
const RANK: Partial<Record<ReferralStatus, number>> = {
  ELIGIBILITY_IDENTIFIED: 0,
  BENEFITS_INVESTIGATION: 1,
  PRIOR_AUTH_SUBMITTED: 2,
  PRIOR_AUTH_APPROVED: 3,
  PRIOR_AUTH_DENIED: 3,
  FINANCIAL_ASSISTANCE_REVIEW: 4,
  READY_TO_FILL: 5,
  DELIVERY_SCHEDULED: 6,
  ACTIVE_THERAPY: 7,
};
const DECISION_RANK = 3;

/**
 * The referral access lifecycle as a left-to-right pipeline. When a referral is selected, each
 * stage is shaded completed / current / skipped / upcoming by its position relative to the
 * referral's current status, and stamped with the date it was reached. The prior-authorization
 * decision collapses the approve/deny fork; denial/resubmit and cancellation are annotated below.
 */
export default function ReferralFlowMap({ current, reached, labelFor }: Props) {
  const has = (s: ReferralStatus) => reached[s] !== undefined;
  const currentRank = current && current !== 'CANCELLED' ? RANK[current] : undefined;
  const cancelled = current === 'CANCELLED';

  const toneFor = (s: ReferralStatus): Tone => {
    if (current === s) return 'current';
    if (cancelled) return has(s) ? 'completed' : 'upcoming';
    const rs = RANK[s];
    if (currentRank === undefined || rs === undefined) return has(s) ? 'completed' : 'upcoming';
    if (rs < currentRank) return has(s) ? 'completed' : 'skipped';
    return 'upcoming';
  };

  const node = (s: ReferralStatus): NodeModel => ({ label: labelFor(s), tone: toneFor(s), date: reached[s] });

  // The PA decision node collapses approved/denied, and reflects pending vs decided vs skipped.
  const decisionNode = (): NodeModel => {
    if (current === 'PRIOR_AUTH_APPROVED') {
      return { label: 'PA approved', tone: 'current', accent: 'ok', date: reached.PRIOR_AUTH_APPROVED };
    }
    if (current === 'PRIOR_AUTH_DENIED') {
      return { label: 'PA denied', tone: 'current', accent: 'danger', date: reached.PRIOR_AUTH_DENIED };
    }
    const decided = (tone: Tone): NodeModel | null => {
      if (has('PRIOR_AUTH_APPROVED')) {
        return { label: 'PA approved', tone, accent: 'ok', date: reached.PRIOR_AUTH_APPROVED };
      }
      if (has('PRIOR_AUTH_DENIED')) {
        return { label: 'PA denied', tone, accent: 'danger', date: reached.PRIOR_AUTH_DENIED };
      }
      return null;
    };
    if (cancelled) {
      return decided('completed') ?? { label: 'PA decision', tone: 'upcoming' };
    }
    if (currentRank !== undefined && currentRank > DECISION_RANK) {
      // The decision is behind the current stage: show the outcome, or "skipped" if PA wasn't needed.
      return decided('completed') ?? { label: 'PA decision', tone: 'skipped' };
    }
    // Not yet decided.
    return { label: current === 'PRIOR_AUTH_SUBMITTED' ? 'PA decision (pending)' : 'PA decision', tone: 'upcoming' };
  };

  const spine: NodeModel[] = [
    node('ELIGIBILITY_IDENTIFIED'),
    node('BENEFITS_INVESTIGATION'),
    node('PRIOR_AUTH_SUBMITTED'),
    decisionNode(),
    node('FINANCIAL_ASSISTANCE_REVIEW'),
    node('READY_TO_FILL'),
    node('DELIVERY_SCHEDULED'),
    node('ACTIVE_THERAPY'),
  ];
  if (cancelled) {
    spine.push({ label: 'Cancelled', tone: 'current', accent: 'danger', date: reached.CANCELLED });
  }

  return (
    <div className="flow-map">
      <div
        className="flow-track"
        role="list"
        aria-label="Referral access lifecycle"
        tabIndex={0}
      >
        {spine.map((n, i) => (
          <div className="flow-step" key={n.label + i} role="listitem">
            <div
              className={`flow-node tone-${n.tone}${n.accent ? ' accent-' + n.accent : ''}`}
              aria-current={n.tone === 'current' ? 'step' : undefined}
            >
              <span className="flow-node-dot" aria-hidden />
              <span className="flow-node-label">{n.label}</span>
              {n.date && <span className="flow-node-date">{dateOnly(n.date)}</span>}
            </div>
            {i < spine.length - 1 && <span className="flow-arrow" aria-hidden>›</span>}
          </div>
        ))}
      </div>

      <div className="flow-legend">
        <span className="legend-item"><span className="legend-dot tone-completed" /> Completed</span>
        <span className="legend-item"><span className="legend-dot tone-current" /> Current stage</span>
        <span className="legend-item"><span className="legend-dot tone-skipped" /> Skipped</span>
        <span className="legend-item"><span className="legend-dot tone-upcoming" /> Upcoming</span>
        <span className="legend-caption">PA outcome is tinted green (approved) or red (denied).</span>
      </div>

      <ul className="flow-notes">
        <li>
          <strong>Prior authorization</strong> can be <em>approved</em> (continue) or <em>denied</em>
          {' '}→ resubmitted, looping back to “PA submitted”.
        </li>
        <li>
          Benefits investigation and an approved PA may shortcut straight to{' '}
          <strong>Ready to fill</strong> when no financial assistance is needed (shown as “Skipped”).
        </li>
        <li className={cancelled ? 'is-active-note' : ''}>
          <strong>Cancelled</strong> is reachable from any active stage (patient declines, switches
          pharmacy, therapy changes){cancelled ? ' — this referral is cancelled.' : ''}.
        </li>
      </ul>
    </div>
  );
}
