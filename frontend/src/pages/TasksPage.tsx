import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { useSimStatus, useTasks, useTaskStatusChange } from '../api/hooks';
import type { ReferralAdvance, TaskItem } from '../api/types';
import { agentTask, dateOnly, priorityTone, relativeTime, titleCase } from '../format';
import { Card, Chip, EmptyState, StateBlock } from '../components/ui';
import { InterventionModal, OutreachModal } from '../components/PatientActionModals';

const PAGE_SIZE = 25;

const STATUS_FILTERS: { value: string; label: string }[] = [
  { value: 'OPEN_ALL', label: 'Open' },
  { value: 'COMPLETED', label: 'Completed' },
  { value: 'CANCELLED', label: 'Cancelled' },
  { value: '', label: 'All' },
];

const STATUS_TONES: Record<string, string> = {
  OPEN: 'tone-warn',
  IN_PROGRESS: 'tone-info',
  COMPLETED: 'tone-ok',
  CANCELLED: 'tone-muted',
};

/** The signed-in user's work list: newest first, expandable for detail and actions. */
export default function TasksPage() {
  const [statusFilter, setStatusFilter] = useState('OPEN_ALL');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const [advanceNotice, setAdvanceNotice] = useState<ReferralAdvance | null>(null);
  const noticeTimer = useRef<ReturnType<typeof setTimeout>>();

  const query = useTasks({
    status: statusFilter || undefined,
    search: search || undefined,
    page,
    size: PAGE_SIZE,
  });

  // A completed task that advanced its referral gets a page-level confirmation (the row itself
  // leaves the Open filter immediately, so inline feedback would vanish with it).
  const showAdvance = (advance: ReferralAdvance) => {
    setAdvanceNotice(advance);
    clearTimeout(noticeTimer.current);
    noticeTimer.current = setTimeout(() => setAdvanceNotice(null), 8000);
  };
  useEffect(() => () => clearTimeout(noticeTimer.current), []);

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>My Tasks</h1>
          <p className="page-sub">
            Your work list — everything assigned to you, by the care team&apos;s agents or the
            workflow itself. Completing a task does the work it asked for: agent-routed access
            tasks advance their referral to the next stage, where the agents pick it back up.
          </p>
        </div>
      </div>

      {advanceNotice && (
        <div className="task-advance-notice" role="status">
          Referral{' '}
          <Link to={`/referrals/${advanceNotice.referralId}`} className="mono">
            {advanceNotice.referralNumber ?? 'view'}
          </Link>{' '}
          advanced to <strong>{advanceNotice.toStatusLabel}</strong> — the agents take it from
          here. Watch the agent feed for the follow-through.
        </div>
      )}

      <div className="filters">
        <input
          className="filter-search"
          type="search"
          placeholder="Search title, patient, or referral #"
          value={search}
          onChange={(e) => {
            setSearch(e.target.value);
            setPage(0);
          }}
        />
        <div className="agent-filter">
          {STATUS_FILTERS.map((f) => (
            <button
              key={f.value}
              type="button"
              className={statusFilter === f.value ? 'chip-button is-active' : 'chip-button'}
              onClick={() => {
                setStatusFilter(f.value);
                setPage(0);
              }}
            >
              {f.label}
            </button>
          ))}
        </div>
      </div>

      <Card>
        <StateBlock query={query}>
          {(data) =>
            data.items.length === 0 ? (
              <EmptyState message="No tasks here. Agent triage and workflow events create them as the world moves." />
            ) : (
              <>
                <div className="task-feed">
                  {data.items.map((t) => (
                    <TaskRow key={t.id} task={t} onAdvanced={showAdvance} />
                  ))}
                </div>
                <div className="pager">
                  <span>
                    {data.totalItems} task{data.totalItems === 1 ? '' : 's'} · page {data.page + 1} of{' '}
                    {Math.max(data.totalPages, 1)}
                  </span>
                  <div className="pager-btns">
                    <button disabled={data.page <= 0} onClick={() => setPage((p) => p - 1)}>
                      Previous
                    </button>
                    <button disabled={data.page + 1 >= data.totalPages} onClick={() => setPage((p) => p + 1)}>
                      Next
                    </button>
                  </div>
                </div>
              </>
            )
          }
        </StateBlock>
      </Card>
    </div>
  );
}

function TaskRow({ task, onAdvanced }: { task: TaskItem; onAdvanced: (a: ReferralAdvance) => void }) {
  const [open, setOpen] = useState(false);
  const [modal, setModal] = useState<'outreach' | 'intervention' | null>(null);
  const { data: sim } = useSimStatus();
  const change = useTaskStatusChange();
  const t = agentTask(task.title);
  const active = task.status === 'OPEN' || task.status === 'IN_PROGRESS';
  const advance = task.advancesReferralTo;

  const complete = () =>
    change.mutate(
      { id: task.id, toStatus: 'COMPLETED' },
      { onSuccess: (res) => res.referralAdvance && onAdvanced(res.referralAdvance) },
    );

  return (
    <div className={`agent-rec task-row ${task.status === 'COMPLETED' ? 'is-done' : ''}`}>
      <button type="button" className="agent-rec-head" onClick={() => setOpen((v) => !v)}>
        <Chip tone={STATUS_TONES[task.status] ?? 'tone-muted'}>{titleCase(task.status)}</Chip>
        <Chip tone={priorityTone(task.priority)}>{titleCase(task.priority)}</Chip>
        <span className="agent-rec-agent">{titleCase(task.type)}</span>
        <span className="agent-rec-summary">
          {t.title}
          {t.assignedByAgent && <span className="badge tone-info task-agent-chip">assigned by agent</span>}
        </span>
        <span className="agent-rec-when">
          {task.dueAt ? `due ${dateOnly(task.dueAt)}` : relativeTime(task.createdAt, sim?.currentInstant)}
        </span>
        <span className="agent-rec-caret">{open ? '▾' : '▸'}</span>
      </button>

      {open && (
        <div className="agent-rec-body">
          <div className="agent-rec-meta">
            <span>
              Patient: <Link to={`/patients/${task.patient.id}`}>{task.patient.displayName}</Link>
            </span>
            {task.referralId && (
              <span>
                · Referral:{' '}
                <Link to={`/referrals/${task.referralId}`} className="mono">
                  {task.referralNumber ?? 'open'}
                </Link>
              </span>
            )}
            <span>· created {dateOnly(task.createdAt)}</span>
            {task.completedAt && <span>· completed {dateOnly(task.completedAt)}</span>}
          </div>

          {task.description && <p className="task-description">{task.description}</p>}

          {active && advance && (
            <p className="task-advance-hint">
              Completing this task advances{' '}
              <span className="mono">{advance.referralNumber ?? 'the referral'}</span> to{' '}
              <strong>{advance.toStatusLabel}</strong> — the agents take it from there.
            </p>
          )}

          <div className="agent-rec-actions">
            {active && (
              <>
                <button
                  type="button"
                  className="btn btn-primary"
                  disabled={change.isPending}
                  onClick={complete}
                >
                  {advance ? 'Complete & advance' : 'Mark complete'}
                </button>
                {task.status === 'OPEN' && (
                  <button
                    type="button"
                    className="btn"
                    disabled={change.isPending}
                    onClick={() => change.mutate({ id: task.id, toStatus: 'IN_PROGRESS' })}
                  >
                    Start
                  </button>
                )}
                <button type="button" className="btn" onClick={() => setModal('outreach')}>
                  Log outreach
                </button>
                <button type="button" className="btn" onClick={() => setModal('intervention')}>
                  Log intervention
                </button>
                <button
                  type="button"
                  className="btn btn-secondary"
                  disabled={change.isPending}
                  onClick={() => change.mutate({ id: task.id, toStatus: 'CANCELLED' })}
                >
                  Cancel task
                </button>
              </>
            )}
            {!active && (
              <button
                type="button"
                className="btn"
                disabled={change.isPending}
                onClick={() => change.mutate({ id: task.id, toStatus: 'OPEN' })}
              >
                Reopen
              </button>
            )}
            {change.isError && (
              <span className="agent-rec-error">
                {(change.error as Error)?.message ?? 'Could not update the task'}
              </span>
            )}
          </div>
        </div>
      )}

      {modal === 'outreach' && (
        <OutreachModal
          patientId={task.patient.id}
          referralId={task.referralId}
          onClose={() => setModal(null)}
        />
      )}
      {modal === 'intervention' && (
        <InterventionModal
          patientId={task.patient.id}
          referralId={task.referralId}
          onClose={() => setModal(null)}
        />
      )}
    </div>
  );
}
