import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useAddNote, useAdvanceStatus, useReferral, useUpdateFinancials } from '../api/hooks';
import { useActingAs } from '../state/ActingAsContext';
import { agentTask, dateOnly, dateTime, days, money, STATUS_LABELS, titleCase } from '../format';
import { Card, PriorityBadge, StateBlock, StatusBadge } from '../components/ui';
import Modal from '../components/Modal';
import type { ReferralDetail, ReferralStatus } from '../api/types';

const MILESTONES: { key: string; label: string }[] = [
  { key: 'benefitsInvestigationStartedAt', label: 'Benefits investigation' },
  { key: 'paSubmittedAt', label: 'Prior auth submitted' },
  { key: 'paDecidedAt', label: 'Prior auth decided' },
  { key: 'readyToFillAt', label: 'Ready to fill' },
  { key: 'deliveryScheduledAt', label: 'Delivery scheduled' },
  { key: 'activeTherapyAt', label: 'Active therapy' },
];

export default function ReferralDetailPage() {
  const { referralId } = useParams();
  const query = useReferral(referralId);
  const [modal, setModal] = useState<'status' | 'financials' | 'note' | null>(null);

  return (
    <div className="page">
      <Link to="/queue" className="back-link">
        ← Back to queue
      </Link>
      <StateBlock query={query}>
        {(r) => (
          <>
            <div className="detail-head">
              <div>
                <div className="detail-eyebrow mono">{r.referralNumber}</div>
                <h1>
                  <Link to={`/patients/${r.patient.id}`}>{r.patient.displayName}</Link>
                </h1>
                <p className="page-sub">
                  {r.patient.diseaseState} · {r.medication.name} ({r.medication.route}) · {r.clinic.name} ·{' '}
                  <Link to={`/patients/${r.patient.id}`}>Open patient record →</Link> ·{' '}
                  <Link to={`/lifecycle?referral=${r.id}`}>View on lifecycle map →</Link>
                </p>
              </div>
              <div className="detail-head-meta">
                <StatusBadge status={r.currentStatus} />
                <PriorityBadge priority={r.priority} />
                {r.pendingAgentRecommendations > 0 && (
                  <Link to="/agents" className="badge tone-info agent-pending-chip">
                    {r.pendingAgentRecommendations} agent recommendation
                    {r.pendingAgentRecommendations === 1 ? '' : 's'} awaiting review
                  </Link>
                )}
              </div>
            </div>

            <div className="action-bar">
              <button
                className="btn btn-primary"
                disabled={r.allowedNextStatuses.length === 0}
                onClick={() => setModal('status')}
              >
                Advance status
              </button>
              <button className="btn" onClick={() => setModal('financials')}>
                Record financials
              </button>
              <button className="btn" onClick={() => setModal('note')}>
                Add note
              </button>
            </div>

            <div className="detail-grid">
              <Card title="Access milestones">
                <ol className="milestones">
                  <li className="done">
                    <span className="m-label">Referral received</span>
                    <span className="m-date">{dateTime(r.receivedAt)}</span>
                  </li>
                  {MILESTONES.map((m) => {
                    const at = r.milestones[m.key];
                    return (
                      <li key={m.key} className={at ? 'done' : ''}>
                        <span className="m-label">{m.label}</span>
                        <span className="m-date">{at ? dateTime(at) : '—'}</span>
                      </li>
                    );
                  })}
                </ol>
              </Card>

              <div className="detail-col">
                <Card title="Metrics & financials">
                  <dl className="kv">
                    <dt>Days since received</dt>
                    <dd>{days(r.metrics.daysSinceReceived)}</dd>
                    <dt>Prior auth age</dt>
                    <dd>{days(r.metrics.priorAuthorizationAgeDays)}</dd>
                    <dt>Time to therapy</dt>
                    <dd>{days(r.metrics.timeToTherapyDays)}</dd>
                    <dt>Copay</dt>
                    <dd>{money(r.financials.copayAmount, true)}</dd>
                    <dt>Financial assistance</dt>
                    <dd>
                      {money(r.financials.financialAssistanceSecuredAmount, true)}
                      {r.financials.financialAssistanceRequired && (
                        <span className="cell-sub"> · required</span>
                      )}
                    </dd>
                    <dt>Owner</dt>
                    <dd>{r.owner.displayName}</dd>
                    <dt>Payer</dt>
                    <dd>
                      {r.payer.name} <span className="cell-sub">({r.payer.payerType})</span>
                    </dd>
                  </dl>
                </Card>

                <Card title={`Open tasks (${r.openTasks.length})`}>
                  {r.openTasks.length === 0 ? (
                    <p className="cell-sub">No open tasks.</p>
                  ) : (
                    <ul className="list">
                      {r.openTasks.map((t) => {
                        const task = agentTask(t.title);
                        return (
                          <li key={t.id}>
                            <span className="cell-strong">{titleCase(t.type)}</span> — {task.title}
                            {task.assignedByAgent && (
                              <span className="badge tone-info task-agent-chip">assigned to you by agent</span>
                            )}
                            {t.dueAt && <span className="cell-sub"> · due {dateOnly(t.dueAt)}</span>}
                          </li>
                        );
                      })}
                    </ul>
                  )}
                </Card>
              </div>

              <Card title="Notes & history">
                {r.recentNotes.length > 0 && (
                  <div className="notes">
                    {r.recentNotes.map((n) => (
                      <div key={n.id} className="note">
                        <div className="note-body">{n.body}</div>
                        <div className="cell-sub">
                          {n.author.displayName} · {dateTime(n.createdAt)}
                        </div>
                      </div>
                    ))}
                  </div>
                )}
                <ul className="history">
                  {[...r.statusHistory].reverse().map((h) => (
                    <li key={h.id}>
                      <span className="dot" />
                      <div>
                        <div className="cell-strong">
                          {h.fromStatus && h.fromStatus !== h.toStatus
                            ? `${STATUS_LABELS[h.fromStatus as ReferralStatus] ?? h.fromStatus} → ${STATUS_LABELS[h.toStatus as ReferralStatus] ?? h.toStatus}`
                            : STATUS_LABELS[h.toStatus as ReferralStatus] ?? h.toStatus}
                        </div>
                        {h.note && <div className="cell-sub">{h.note}</div>}
                        <div className="cell-sub">
                          {h.changedBy?.displayName ?? 'System'} · {dateTime(h.changedAt)}
                        </div>
                      </div>
                    </li>
                  ))}
                </ul>
              </Card>
            </div>

            {modal === 'status' && <AdvanceStatusModal r={r} onClose={() => setModal(null)} />}
            {modal === 'financials' && <FinancialsModal r={r} onClose={() => setModal(null)} />}
            {modal === 'note' && <NoteModal referralId={r.id} onClose={() => setModal(null)} />}
          </>
        )}
      </StateBlock>
    </div>
  );
}

function ActorNote({ note, setNote }: { note: string; setNote: (s: string) => void }) {
  return (
    <label className="field">
      <span>Note (optional)</span>
      <textarea value={note} onChange={(e) => setNote(e.target.value)} rows={3} />
    </label>
  );
}

function AdvanceStatusModal({ r, onClose }: { r: ReferralDetail; onClose: () => void }) {
  const { actorId } = useActingAs();
  const [toStatus, setToStatus] = useState<ReferralStatus>(r.allowedNextStatuses[0]);
  const [note, setNote] = useState('');
  const mutation = useAdvanceStatus(r.id);

  return (
    <Modal title="Advance referral status" onClose={onClose}>
      <label className="field">
        <span>New status</span>
        <select value={toStatus} onChange={(e) => setToStatus(e.target.value as ReferralStatus)}>
          {r.allowedNextStatuses.map((s) => (
            <option key={s} value={s}>
              {STATUS_LABELS[s]}
            </option>
          ))}
        </select>
      </label>
      <ActorNote note={note} setNote={setNote} />
      <MutationFooter
        mutation={mutation}
        disabled={!actorId}
        onSubmit={() =>
          mutation.mutate({ toStatus, changedById: actorId!, note: note || undefined }, { onSuccess: onClose })
        }
      />
    </Modal>
  );
}

function FinancialsModal({ r, onClose }: { r: ReferralDetail; onClose: () => void }) {
  const { actorId } = useActingAs();
  const [copay, setCopay] = useState(String(r.financials.copayAmount ?? ''));
  const [secured, setSecured] = useState(String(r.financials.financialAssistanceSecuredAmount ?? ''));
  const [required, setRequired] = useState(r.financials.financialAssistanceRequired);
  const [note, setNote] = useState('');
  const mutation = useUpdateFinancials(r.id);

  return (
    <Modal title="Record financials" onClose={onClose}>
      <label className="field">
        <span>Copay amount ($)</span>
        <input type="number" min="0" step="0.01" value={copay} onChange={(e) => setCopay(e.target.value)} />
      </label>
      <label className="field">
        <span>Financial assistance secured ($)</span>
        <input type="number" min="0" step="0.01" value={secured} onChange={(e) => setSecured(e.target.value)} />
      </label>
      <label className="field-check">
        <input type="checkbox" checked={required} onChange={(e) => setRequired(e.target.checked)} />
        <span>Financial assistance required</span>
      </label>
      <ActorNote note={note} setNote={setNote} />
      <MutationFooter
        mutation={mutation}
        disabled={!actorId}
        onSubmit={() =>
          mutation.mutate(
            {
              changedById: actorId!,
              copayAmount: copay === '' ? undefined : Number(copay),
              financialAssistanceSecuredAmount: secured === '' ? undefined : Number(secured),
              financialAssistanceRequired: required,
              note: note || undefined,
            },
            { onSuccess: onClose },
          )
        }
      />
    </Modal>
  );
}

function NoteModal({ referralId, onClose }: { referralId: string; onClose: () => void }) {
  const { actorId } = useActingAs();
  const [body, setBody] = useState('');
  const mutation = useAddNote(referralId);
  return (
    <Modal title="Add note" onClose={onClose}>
      <label className="field">
        <span>Note</span>
        <textarea value={body} onChange={(e) => setBody(e.target.value)} rows={4} autoFocus />
      </label>
      <MutationFooter
        mutation={mutation}
        disabled={!actorId || body.trim() === ''}
        onSubmit={() => mutation.mutate({ authorId: actorId!, body: body.trim() }, { onSuccess: onClose })}
      />
    </Modal>
  );
}

export function MutationFooter({
  mutation,
  disabled,
  onSubmit,
}: {
  mutation: { isPending: boolean; isError: boolean; error?: unknown };
  disabled?: boolean;
  onSubmit: () => void;
}) {
  return (
    <div className="modal-foot">
      {mutation.isError && (
        <span className="form-error">
          {mutation.error instanceof Error ? mutation.error.message : 'Could not save.'}
        </span>
      )}
      <button className="btn btn-primary" disabled={disabled || mutation.isPending} onClick={onSubmit}>
        {mutation.isPending ? 'Saving…' : 'Save'}
      </button>
    </div>
  );
}
