import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { useLookups, useReferral, useReferralQueue } from '../api/hooks';
import { dateOnly, STATUS_LABELS } from '../format';
import { Card, StateBlock, StatusBadge } from '../components/ui';
import ReferralFlowMap from '../components/ReferralFlowMap';
import type { ReferralStatus } from '../api/types';

const DESCRIPTIONS: Record<ReferralStatus, string> = {
  ELIGIBILITY_IDENTIFIED: 'Referral received; the patient is confirmed appropriate/eligible for the program (often EMR-driven identification).',
  BENEFITS_INVESTIGATION: 'The team verifies insurance coverage and what the payer will require — a prior authorization? what copay?',
  PRIOR_AUTH_SUBMITTED: 'A prior authorization has been sent to the payer and is pending a decision.',
  PRIOR_AUTH_APPROVED: 'The payer approved coverage.',
  PRIOR_AUTH_DENIED: 'The payer denied it; the team can revise and resubmit.',
  FINANCIAL_ASSISTANCE_REVIEW: 'Securing copay assistance or foundation grants so the patient can afford the therapy.',
  READY_TO_FILL: 'All access barriers are cleared — the prescription can be dispensed.',
  DELIVERY_SCHEDULED: 'The fill is scheduled or shipped to the patient.',
  ACTIVE_THERAPY: 'The goal state: the patient is on therapy. Ongoing care continues in the Patient Therapy Workbench.',
  CANCELLED: 'Referral closed without reaching therapy (patient declined, switched pharmacy, or therapy changed).',
};

const STAGE_ORDER: ReferralStatus[] = [
  'ELIGIBILITY_IDENTIFIED', 'BENEFITS_INVESTIGATION', 'PRIOR_AUTH_SUBMITTED', 'PRIOR_AUTH_APPROVED',
  'PRIOR_AUTH_DENIED', 'FINANCIAL_ASSISTANCE_REVIEW', 'READY_TO_FILL', 'DELIVERY_SCHEDULED',
  'ACTIVE_THERAPY', 'CANCELLED',
];

export default function LifecyclePage() {
  const { data: lookups } = useLookups();
  const [search, setSearch] = useState('');
  // Deep-linkable: /lifecycle?referral=<id> preselects that referral (linked from the
  // referral detail page). Manual picker changes after arrival still work as before.
  const [searchParams] = useSearchParams();
  const urlReferralId = searchParams.get('referral') ?? undefined;
  const [selectedId, setSelectedId] = useState<string | undefined>(urlReferralId);

  useEffect(() => {
    if (urlReferralId) {
      setSelectedId(urlReferralId);
    }
  }, [urlReferralId]);

  const list = useReferralQueue({ search, size: 50, includeCancelled: true, sort: 'receivedAt,desc' });
  const detail = useReferral(selectedId);
  const didAutoSelect = useRef(false);

  // Auto-select the first result exactly once on load so the map isn't empty. After that the
  // user can clear the picker to see the generic (unselected) lifecycle view.
  useEffect(() => {
    if (!didAutoSelect.current && !selectedId && list.data && list.data.items.length > 0) {
      didAutoSelect.current = true;
      setSelectedId(list.data.items[0].id);
    }
  }, [list.data, selectedId]);

  const items = list.data?.items ?? [];
  const selectedInList = items.some((r) => r.id === selectedId);

  const labelFor = (s: ReferralStatus) =>
    lookups?.referralStatuses.find((o) => o.value === s)?.label ?? STATUS_LABELS[s] ?? s;

  const reached = useMemo(() => {
    const map: Partial<Record<ReferralStatus, string>> = {};
    const d = detail.data;
    if (!d) return map;
    for (const h of d.statusHistory) {
      // Skip pure financial annotations (from === to); they aren't stage transitions.
      if (h.fromStatus && h.fromStatus === h.toStatus) continue;
      map[h.toStatus as ReferralStatus] = h.changedAt;
    }
    if (map[d.currentStatus] === undefined) map[d.currentStatus] = d.receivedAt;
    return map;
  }, [detail.data]);

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>Referral Lifecycle</h1>
        </div>
      </div>

      <Card title="Referral queue explained">
        <p className="prose">
          The queue tracks <strong>referrals</strong>, not patients directly. A referral is one
          access case — <em>this patient, on this medication, under this payer, from this clinic,
          owned by one care team member.</em> A patient can have several referrals over time, but
          each referral is a single “get this patient onto this therapy” journey worked by the
          pharmacy access team. Prescribers send referrals in; they are the source, not users of
          the app. See the <Link to="/queue">queue</Link> to work referrals and the{' '}
          <Link to="/dashboard">dashboard</Link> for program outcomes.
        </p>
      </Card>

      <Card
        title="Where is a referral in the flow?"
        action={
          <div className="referral-picker">
            <input
              type="search"
              placeholder="Search referral # or patient…"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              aria-label="Search referrals"
            />
            <select
              value={selectedId ?? ''}
              onChange={(e) => setSelectedId(e.target.value || undefined)}
              aria-label="Select a referral"
            >
              <option value="">Select a referral…</option>
              {selectedId && !selectedInList && detail.data && (
                <option value={selectedId}>
                  {detail.data.referralNumber} — {detail.data.patient.displayName} (selected)
                </option>
              )}
              {items.map((r) => (
                <option key={r.id} value={r.id}>
                  {r.referralNumber} — {r.patient.displayName} ({labelFor(r.currentStatus)})
                </option>
              ))}
            </select>
          </div>
        }
      >
        {selectedId === undefined ? (
          <ReferralFlowMap reached={{}} labelFor={labelFor} />
        ) : (
          <StateBlock query={detail}>
            {(d) => (
              <>
                <div className="flow-selected">
                  <span className="mono">{d.referralNumber}</span>
                  <Link to={`/referrals/${d.id}`}>{d.patient.displayName}</Link>
                  <span className="cell-sub">{d.patient.diseaseState} · {d.medication.name}</span>
                  <StatusBadge status={d.currentStatus} />
                  <span className="cell-sub">received {dateOnly(d.receivedAt)}</span>
                </div>
                <ReferralFlowMap current={d.currentStatus} reached={reached} labelFor={labelFor} />
              </>
            )}
          </StateBlock>
        )}
      </Card>

      <Card title="The 10 queue statuses">
        <table className="data-table status-ref">
          <thead>
            <tr>
              <th>Status</th>
              <th>What it means</th>
            </tr>
          </thead>
          <tbody>
            {STAGE_ORDER.map((s) => (
              <tr key={s}>
                <td>
                  <StatusBadge status={s} />
                </td>
                <td>{DESCRIPTIONS[s]}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>
    </div>
  );
}
