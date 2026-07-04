import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useLogIntervention, useLogOutreach, useLookups, usePatient, usePatientTimeline } from '../api/hooks';
import { useActingAs } from '../state/ActingAsContext';
import { dateOnly, dateTime, percent, titleCase } from '../format';
import { Card, RiskBadge, StateBlock } from '../components/ui';
import Modal from '../components/Modal';
import { MutationFooter } from './ReferralDetailPage';

const TIMELINE_TYPES = ['REFERRAL', 'STATUS_CHANGE', 'FINANCIAL', 'FILL', 'TASK', 'OUTREACH', 'INTERVENTION', 'NOTE'];

export default function PatientWorkbenchPage() {
  const { patientId } = useParams();
  const query = usePatient(patientId);
  const [modal, setModal] = useState<'outreach' | 'intervention' | null>(null);
  const [activeTypes, setActiveTypes] = useState<string[]>([]);
  const timeline = usePatientTimeline(patientId, activeTypes);

  const toggleType = (t: string) =>
    setActiveTypes((cur) => (cur.includes(t) ? cur.filter((x) => x !== t) : [...cur, t]));

  return (
    <div className="page">
      <Link to="/patients" className="back-link">
        ← Back to patients
      </Link>
      <StateBlock query={query}>
        {(p) => (
          <>
            <div className="detail-head">
              <div>
                <div className="detail-eyebrow mono">{p.demoMrn}</div>
                <h1>{p.displayName}</h1>
                <p className="page-sub">
                  {p.diseaseState} · DOB {dateOnly(p.dateOfBirth)} · {p.clinic.name} · {p.payer.name} · Owner{' '}
                  {p.primaryOwner.displayName}
                </p>
              </div>
              <div className="action-bar">
                <button className="btn btn-primary" onClick={() => setModal('outreach')}>
                  Log outreach
                </button>
                <button className="btn" onClick={() => setModal('intervention')}>
                  Log intervention
                </button>
              </div>
            </div>

            <div className="workbench-grid">
              <div className="workbench-col">
                <Card title="Therapies">
                  {p.therapies.length === 0 ? (
                    <p className="cell-sub">No therapies on record.</p>
                  ) : (
                    p.therapies.map((t) => (
                      <div key={t.id} className="therapy">
                        <div className="therapy-head">
                          <span className="cell-strong">{t.medication.name}</span>
                          <RiskBadge risk={t.refillRiskLevel} />
                        </div>
                        <div className="therapy-meta">
                          <span>Status: {titleCase(t.status)}</span>
                          <span>Adherence (PDC): {t.adherencePdcPercent != null ? percent(t.adherencePdcPercent) : 'New therapy'}</span>
                          <span>Refill due: {dateOnly(t.currentRefillDueDate)}</span>
                        </div>
                        {t.refillRiskReasons.length > 0 && (
                          <ul className="risk-reasons">
                            {t.refillRiskReasons.map((reason) => (
                              <li key={reason}>{reason}</li>
                            ))}
                          </ul>
                        )}
                      </div>
                    ))
                  )}
                </Card>

                <Card title={`Open tasks (${p.openTasks.length})`}>
                  {p.openTasks.length === 0 ? (
                    <p className="cell-sub">No open tasks.</p>
                  ) : (
                    <ul className="list">
                      {p.openTasks.map((t) => (
                        <li key={t.id}>
                          <span className="cell-strong">{titleCase(t.type)}</span> — {t.title}
                          {t.dueAt && <span className="cell-sub"> · due {dateOnly(t.dueAt)}</span>}
                        </li>
                      ))}
                    </ul>
                  )}
                </Card>
              </div>

              <Card
                title="Journey timeline"
                action={
                  <div className="chip-filters">
                    {TIMELINE_TYPES.map((t) => (
                      <button
                        key={t}
                        className={`chip-toggle ${activeTypes.includes(t) ? 'on' : ''}`}
                        onClick={() => toggleType(t)}
                      >
                        {titleCase(t)}
                      </button>
                    ))}
                  </div>
                }
              >
                <StateBlock query={timeline} empty={(d) => d.items.length === 0}>
                  {(d) => (
                    <ul className="timeline">
                      {d.items.map((item) => (
                        <li key={item.id} className={`tl-${item.type.toLowerCase()}`}>
                          <span className="tl-dot" />
                          <div className="tl-body">
                            <div className="tl-row">
                              <span className="cell-strong">{item.title}</span>
                              <span className="cell-sub">{dateTime(item.occurredAt)}</span>
                            </div>
                            {item.body && <div className="tl-detail">{item.body}</div>}
                            {item.actor && <div className="cell-sub">{item.actor.displayName}</div>}
                          </div>
                        </li>
                      ))}
                    </ul>
                  )}
                </StateBlock>
              </Card>
            </div>

            {modal === 'outreach' && (
              <OutreachModal patientId={p.id} onClose={() => setModal(null)} />
            )}
            {modal === 'intervention' && (
              <InterventionModal patientId={p.id} onClose={() => setModal(null)} />
            )}
          </>
        )}
      </StateBlock>
    </div>
  );
}

function OutreachModal({ patientId, onClose }: { patientId: string; onClose: () => void }) {
  const { actorId } = useActingAs();
  const { data: lookups } = useLookups();
  const [channel, setChannel] = useState('PHONE');
  const [outcome, setOutcome] = useState('REACHED');
  const [notes, setNotes] = useState('');
  const mutation = useLogOutreach(patientId);
  return (
    <Modal title="Log outreach" onClose={onClose}>
      <label className="field">
        <span>Channel</span>
        <select value={channel} onChange={(e) => setChannel(e.target.value)}>
          {lookups?.outreachChannels.map((o) => (
            <option key={o.value} value={o.value}>
              {o.label}
            </option>
          ))}
        </select>
      </label>
      <label className="field">
        <span>Outcome</span>
        <select value={outcome} onChange={(e) => setOutcome(e.target.value)}>
          {lookups?.outreachOutcomes.map((o) => (
            <option key={o.value} value={o.value}>
              {o.label}
            </option>
          ))}
        </select>
      </label>
      <label className="field">
        <span>Notes (optional)</span>
        <textarea value={notes} onChange={(e) => setNotes(e.target.value)} rows={3} />
      </label>
      <MutationFooter
        mutation={mutation}
        disabled={!actorId}
        onSubmit={() =>
          mutation.mutate(
            { ownerId: actorId!, channel, outcome, notes: notes || undefined },
            { onSuccess: onClose },
          )
        }
      />
    </Modal>
  );
}

function InterventionModal({ patientId, onClose }: { patientId: string; onClose: () => void }) {
  const { actorId } = useActingAs();
  const { data: lookups } = useLookups();
  const [interventionType, setInterventionType] = useState('ADHERENCE_COUNSELING');
  const [summary, setSummary] = useState('');
  const mutation = useLogIntervention(patientId);
  return (
    <Modal title="Log clinical intervention" onClose={onClose}>
      <label className="field">
        <span>Type</span>
        <select value={interventionType} onChange={(e) => setInterventionType(e.target.value)}>
          {lookups?.interventionTypes.map((o) => (
            <option key={o.value} value={o.value}>
              {o.label}
            </option>
          ))}
        </select>
      </label>
      <label className="field">
        <span>Summary</span>
        <textarea value={summary} onChange={(e) => setSummary(e.target.value)} rows={4} autoFocus />
      </label>
      <MutationFooter
        mutation={mutation}
        disabled={!actorId || summary.trim() === ''}
        onSubmit={() =>
          mutation.mutate(
            { ownerId: actorId!, interventionType, summary: summary.trim() },
            { onSuccess: onClose },
          )
        }
      />
    </Modal>
  );
}
