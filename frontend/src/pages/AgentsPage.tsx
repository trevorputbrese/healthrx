import { useState } from 'react';
import { Link } from 'react-router-dom';
import {
  useAgentPause,
  useAgentRecommendations,
  useAgents,
  useRecommendationDecision,
} from '../api/hooks';
import type { AgentRecommendation, AgentStatus, TraceStep } from '../api/types';
import { useActingAs } from '../state/ActingAsContext';
import { Card, Chip, EmptyState, StateBlock } from '../components/ui';
import { dateTime } from '../format';

const STATUS_TONES: Record<string, string> = {
  PENDING: 'tone-warn',
  APPLYING: 'tone-info',
  APPLIED: 'tone-ok',
  AUTO_APPLIED: 'tone-ok',
  DISMISSED: 'tone-muted',
  SUPERSEDED: 'tone-muted',
};

const STATUS_FILTERS = ['ALL', 'PENDING', 'APPLIED', 'AUTO_APPLIED', 'DISMISSED', 'SUPERSEDED'];

export default function AgentsPage() {
  const [statusFilter, setStatusFilter] = useState('ALL');
  const agents = useAgents();
  const recommendations = useAgentRecommendations({
    status: statusFilter === 'ALL' ? undefined : statusFilter,
  });

  return (
    <div className="page">
      <div className="page-head">
        <h1>Agents</h1>
        <p className="page-sub">
          AI care-team agents: what they saw, what they reasoned, and what they did — every read
          and write is an audited MCP gateway tool call.
        </p>
      </div>

      <StateBlock query={agents}>
        {(data) => (
          <div className="agent-cards">
            {data.agents.map((agent) => (
              <AgentCard key={agent.name} agent={agent} />
            ))}
          </div>
        )}
      </StateBlock>

      <Card
        title="Activity feed"
        action={
          <div className="agent-filter">
            {STATUS_FILTERS.map((f) => (
              <button
                key={f}
                type="button"
                className={statusFilter === f ? 'chip-button is-active' : 'chip-button'}
                onClick={() => setStatusFilter(f)}
              >
                {f === 'ALL' ? 'All' : f.replace('_', ' ')}
              </button>
            ))}
          </div>
        }
      >
        <StateBlock query={recommendations}>
          {(page) =>
            page.items.length === 0 ? (
              <EmptyState message="No agent activity yet. Resume an agent and trigger a scenario." />
            ) : (
              <div className="agent-feed">
                {page.items.map((rec) => (
                  <RecommendationRow key={rec.id} rec={rec} />
                ))}
              </div>
            )
          }
        </StateBlock>
      </Card>
    </div>
  );
}

function AgentCard({ agent }: { agent: AgentStatus }) {
  const pause = useAgentPause();
  return (
    <div className="agent-card">
      <div className="agent-card-head">
        <div>
          <div className="agent-card-name">{agent.displayName}</div>
          <div className="agent-card-sub">
            {agent.reachable ? 'online' : 'unreachable'} ·{' '}
            {agent.lastActivityAt ? `last activity ${dateTime(agent.lastActivityAt)}` : 'no activity yet'}
          </div>
        </div>
        <Chip tone={agent.paused ? 'tone-muted' : 'tone-ok'}>{agent.paused ? 'Paused' : 'Active'}</Chip>
      </div>
      <div className="agent-card-stats">
        <span>{agent.totalRecommendations} recommendations</span>
        <span>{agent.pendingCount} pending</span>
        <span>{agent.appliedCount + agent.autoAppliedCount} applied</span>
      </div>
      <button
        type="button"
        className="btn btn-secondary"
        disabled={pause.isPending}
        onClick={() => pause.mutate({ name: agent.name, paused: !agent.paused })}
      >
        {agent.paused ? 'Resume agent' : 'Pause agent'}
      </button>
    </div>
  );
}

function RecommendationRow({ rec }: { rec: AgentRecommendation }) {
  const [open, setOpen] = useState(false);
  const { actorId, ready } = useActingAs();
  const approve = useRecommendationDecision('approve');
  const dismiss = useRecommendationDecision('dismiss');
  const busy = approve.isPending || dismiss.isPending || rec.status === 'APPLYING';

  return (
    <div className="agent-rec">
      <button type="button" className="agent-rec-head" onClick={() => setOpen((v) => !v)}>
        <Chip tone={STATUS_TONES[rec.status] ?? 'tone-muted'}>
          {rec.status === 'APPLYING' ? 'APPLYING…' : rec.status.replace('_', ' ')}
        </Chip>
        <span className="agent-rec-agent">{rec.agentDisplayName}</span>
        <span className="agent-rec-summary">{rec.summary}</span>
        <span className="agent-rec-when">{dateTime(rec.createdAt)}</span>
        <span className="agent-rec-caret">{open ? '▾' : '▸'}</span>
      </button>

      {open && (
        <div className="agent-rec-body">
          <div className="agent-rec-meta">
            <span>
              Patient: <Link to={`/patients/${rec.patient.id}`}>{rec.patient.displayName}</Link>
            </span>
            {rec.referralId && (
              <span>
                · <Link to={`/referrals/${rec.referralId}`}>Referral</Link>
              </span>
            )}
            {rec.decidedBy && (
              <span>
                · decided by {rec.decidedBy.displayName}
                {rec.decidedAt ? ` (${dateTime(rec.decidedAt)})` : ''}
              </span>
            )}
          </div>

          <Trace trace={rec.trace} />
          <Proposal recommendation={rec.recommendation} />

          {rec.status === 'PENDING' && (
            <div className="agent-rec-actions">
              <button
                type="button"
                className="btn btn-primary"
                disabled={!ready || busy}
                onClick={() => actorId && approve.mutate({ id: rec.id, decidedById: actorId })}
              >
                {approve.isPending ? 'Applying…' : 'Approve & apply'}
              </button>
              <button
                type="button"
                className="btn btn-secondary"
                disabled={!ready || busy}
                onClick={() => actorId && dismiss.mutate({ id: rec.id, decidedById: actorId })}
              >
                Dismiss
              </button>
              {approve.isError && (
                <span className="agent-rec-error">
                  {(approve.error as Error)?.message ?? 'Approve failed'}
                </span>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

function Trace({ trace }: { trace: TraceStep[] }) {
  if (!trace || trace.length === 0) {
    return null;
  }
  return (
    <div className="agent-trace">
      <div className="agent-trace-title">Thought process</div>
      {trace.map((step, i) => (
        <div key={i} className="agent-trace-step">
          <span className={`agent-trace-kind agent-trace-${step.step}`}>{step.step}</span>
          {step.tool ? (
            <span className="agent-trace-detail">
              <code>{step.tool}</code> {step.input}
              {step.result ? <span className="agent-trace-result"> → {step.result}</span> : null}
            </span>
          ) : (
            <span className="agent-trace-detail">{step.detail}</span>
          )}
        </div>
      ))}
    </div>
  );
}

function Proposal({ recommendation }: { recommendation: Record<string, unknown> }) {
  const outreach = recommendation?.outreach as { channel?: string; script?: string } | undefined;
  const intervention = recommendation?.intervention as { type?: string; rationale?: string } | undefined;
  const refill = recommendation?.refillPlan as { daysSupply?: number; note?: string } | undefined;
  const risk = recommendation?.riskExplanation as string | undefined;
  // Access Workflow Agent shape (autonomous task routing).
  const nextAction = recommendation?.nextAction as string | undefined;
  const task = recommendation?.task as { taskId?: string; title?: string; priority?: string } | undefined;
  if (!outreach && !intervention && !refill && !risk && !nextAction && !task) {
    return null;
  }
  return (
    <div className="agent-proposal">
      <div className="agent-trace-title">Proposed plan</div>
      {risk && <p className="agent-proposal-risk">{risk}</p>}
      {nextAction && (
        <div className="agent-proposal-item">
          <strong>Next action:</strong> {nextAction}
        </div>
      )}
      {task?.title && (
        <div className="agent-proposal-item">
          <strong>Task routed ({task.priority ?? 'MEDIUM'}):</strong> {task.title}
        </div>
      )}
      {outreach?.script && (
        <div className="agent-proposal-item">
          <strong>Outreach ({outreach.channel ?? 'PHONE'}):</strong> {outreach.script}
        </div>
      )}
      {intervention?.type && (
        <div className="agent-proposal-item">
          <strong>Intervention ({intervention.type}):</strong> {intervention.rationale}
        </div>
      )}
      {refill?.daysSupply != null && (
        <div className="agent-proposal-item">
          <strong>Refill:</strong> {refill.daysSupply}-day supply{refill.note ? ` — ${refill.note}` : ''}
        </div>
      )}
    </div>
  );
}
