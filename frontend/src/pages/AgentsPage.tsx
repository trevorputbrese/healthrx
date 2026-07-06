import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  useAgentPause,
  useAgentRecommendations,
  useAgents,
  useRecommendationDecision,
  useSimStatus,
} from '../api/hooks';
import type { AgentRecommendation, AgentStatus, TraceStep } from '../api/types';
import { useActingAs } from '../state/ActingAsContext';
import { Card, Chip, EmptyState, StateBlock } from '../components/ui';
import { relativeTime } from '../format';

const STATUS_TONES: Record<string, string> = {
  PENDING: 'tone-warn',
  APPLYING: 'tone-info',
  APPLIED: 'tone-ok',
  AUTO_APPLIED: 'tone-ok',
  DISMISSED: 'tone-muted',
  SUPERSEDED: 'tone-muted',
};

/** Plain-English status labels: the raw enum reads as jargon on a projector. */
const STATUS_LABELS: Record<string, string> = {
  PENDING: 'Awaiting approval',
  APPLYING: 'Applying…',
  APPLIED: 'Approved & applied',
  AUTO_APPLIED: 'Acted autonomously',
  DISMISSED: 'Dismissed',
  SUPERSEDED: 'Superseded',
};

const STATUS_FILTERS = ['ALL', 'PENDING', 'APPLIED', 'AUTO_APPLIED', 'DISMISSED', 'SUPERSEDED'];

const FILTER_LABELS: Record<string, string> = {
  ALL: 'All',
  PENDING: 'Awaiting approval',
  APPLIED: 'Approved & applied',
  AUTO_APPLIED: 'Autonomous',
  DISMISSED: 'Dismissed',
  SUPERSEDED: 'Superseded',
};

/** What each agent does, in one glance — shown on its status card. */
const AGENT_MISSIONS: Record<string, string> = {
  'adherence-risk':
    'Watches for missed refills. Investigates the patient’s history, drafts an outreach call ' +
    'script and intervention plan, then waits for a pharmacist to approve before acting.',
  'access-workflow':
    'Watches new, stuck, and prior-auth-submitted referrals. Investigates each case, routes ' +
    'follow-up tasks to owners, and contacts the payer’s portal for PA decisions — fully autonomously.',
  'financial-assistance':
    'Watches referrals the moment their prior auth is approved. If the case needs copay help, ' +
    'it contacts an external patient-assistance foundation for a real decision and records it — ' +
    'no guessing, no coin flips, fully autonomously.',
};

export default function AgentsPage() {
  const [statusFilter, setStatusFilter] = useState('ALL');
  const agents = useAgents();
  const recommendations = useAgentRecommendations({
    status: statusFilter === 'ALL' ? undefined : statusFilter,
  });
  const { data: sim } = useSimStatus();

  // Flash rows that arrive while the page is open (skip the initial load). Reset the tracking
  // when the filter changes — rows that merely became visible under a new filter aren't new.
  const seenIds = useRef<Set<string> | null>(null);
  const [newIds, setNewIds] = useState<Set<string>>(new Set());
  const items = recommendations.data?.items;
  useEffect(() => {
    seenIds.current = null;
    setNewIds((cur) => (cur.size ? new Set<string>() : cur));
  }, [statusFilter]);
  useEffect(() => {
    if (!items) return;
    if (seenIds.current === null) {
      seenIds.current = new Set(items.map((r) => r.id));
      return;
    }
    const fresh = items.filter((r) => !seenIds.current!.has(r.id)).map((r) => r.id);
    if (fresh.length > 0) {
      fresh.forEach((id) => seenIds.current!.add(id));
      setNewIds(new Set(fresh));
      const timer = setTimeout(() => setNewIds(new Set()), 4000);
      return () => clearTimeout(timer);
    }
  }, [items]);

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>Agents</h1>
          <p className="page-sub">
            AI care-team agents that work the referral queue alongside your staff. Every read and
            write they perform in HealthRx is an audited call through the MCP gateway. The activity
            feed below documents each run — the trigger, the investigation, and the action taken.
          </p>
        </div>
      </div>

      <StateBlock query={agents}>
        {(data) => (
          <div className="agent-cards">
            {data.agents.map((agent) => (
              <AgentCard key={agent.name} agent={agent} simNow={sim?.currentInstant} />
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
                {FILTER_LABELS[f]}
              </button>
            ))}
          </div>
        }
      >
        <p className="agent-feed-hint">
          Newest first. Each entry is one autonomous run — expand it to see the agent’s
          step-by-step work. <strong>Awaiting approval</strong> entries need a human decision;{' '}
          <strong>Acted autonomously</strong> entries were completed by the agent on its own.
        </p>
        <StateBlock query={recommendations}>
          {(page) =>
            page.items.length === 0 ? (
              <EmptyState message="No agent activity yet. Resume an agent and trigger a scenario from the simulation bar." />
            ) : (
              <div className="agent-feed">
                {page.items.map((rec) => (
                  <RecommendationRow
                    key={rec.id}
                    rec={rec}
                    simNow={sim?.currentInstant}
                    isNew={newIds.has(rec.id)}
                  />
                ))}
              </div>
            )
          }
        </StateBlock>
      </Card>
    </div>
  );
}

function AgentCard({ agent, simNow }: { agent: AgentStatus; simNow?: string }) {
  const pause = useAgentPause();
  return (
    <div className={`agent-card agent-${agent.name}`}>
      <div className="agent-card-head">
        <div>
          <div className="agent-card-name">{agent.displayName}</div>
          <div className="agent-card-sub">
            {agent.reachable ? 'online' : 'unreachable'} ·{' '}
            {agent.lastActivityAt ? `last activity ${relativeTime(agent.lastActivityAt, simNow)}` : 'no activity yet'}
          </div>
        </div>
        <Chip tone={agent.paused ? 'tone-muted' : 'tone-ok'}>{agent.paused ? 'Paused' : 'Active'}</Chip>
      </div>
      <p className="agent-mission">{AGENT_MISSIONS[agent.name] ?? ''}</p>
      <div className="agent-card-stats">
        <span>{agent.totalRecommendations} runs</span>
        <span>{agent.pendingCount} awaiting approval</span>
        <span>{agent.appliedCount + agent.autoAppliedCount} actions applied</span>
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

function RecommendationRow({
  rec,
  simNow,
  isNew,
}: {
  rec: AgentRecommendation;
  simNow?: string;
  isNew: boolean;
}) {
  const [open, setOpen] = useState(false);
  const { actorId, ready } = useActingAs();
  const approve = useRecommendationDecision('approve');
  const dismiss = useRecommendationDecision('dismiss');
  const busy = approve.isPending || dismiss.isPending || rec.status === 'APPLYING';

  return (
    <div className={`agent-rec agent-${rec.agentName} ${isNew ? 'is-new' : ''}`}>
      <button type="button" className="agent-rec-head" onClick={() => setOpen((v) => !v)}>
        <Chip tone={STATUS_TONES[rec.status] ?? 'tone-muted'}>
          {STATUS_LABELS[rec.status] ?? rec.status}
        </Chip>
        <span className="agent-rec-agent">{rec.agentDisplayName}</span>
        <span className="agent-rec-summary">{rec.summary}</span>
        <span className="agent-rec-when">{relativeTime(rec.createdAt, simNow)}</span>
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
                · <Link to={`/referrals/${rec.referralId}`}>Open referral</Link>
              </span>
            )}
            {rec.decidedBy && (
              <span>
                · decided by {rec.decidedBy.displayName}
                {rec.decidedAt ? ` (${relativeTime(rec.decidedAt, simNow)})` : ''}
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

/**
 * Friendly narration for a trace step, keyed by step kind and tool name. `protocol` names the
 * mechanism used — an audited MCP tool call through the gateway, a plain REST API call to an
 * external partner, an event off the message bus, or an LLM inference — so viewers can tell at
 * a glance HOW the agent did each thing.
 */
function describeStep(step: TraceStep): { icon: string; label: string; friendly?: string; protocol?: string } {
  if (step.tool) {
    switch (step.tool) {
      case 'executeQuery':
        return { icon: '🔍', label: 'Investigated', protocol: 'MCP tool call', friendly: 'Queried the HealthRx database (read-only SQL via the MCP gateway)' };
      case 'get_medication_guidance':
        return { icon: '📚', label: 'Consulted', protocol: 'MCP tool call', friendly: 'Looked up drug guidance in the knowledge base' };
      case 'get_condition_guidance':
        return { icon: '📚', label: 'Consulted', protocol: 'MCP tool call', friendly: 'Looked up condition guidance in the knowledge base' };
      case 'clearpath_portal.prior_auth_decision':
        return { icon: '🏢', label: 'External call', protocol: 'REST API call', friendly: 'Contacted ClearPath Benefits — the payer’s portal, outside HealthRx' };
      case 'bridgefund_portal.financial_assistance_decision':
        return { icon: '🏢', label: 'External call', protocol: 'REST API call', friendly: 'Contacted BridgeFund Patient Assistance — an independent foundation, outside HealthRx' };
      case 'llm.chat_completion':
        return { icon: '🧠', label: 'Reasoned', protocol: 'LLM call', friendly: 'Model reasoned over the findings' };
      default:
        return { icon: '🔧', label: 'Tool call', protocol: 'MCP tool call', friendly: `Called ${step.tool} via the MCP gateway` };
    }
  }
  switch (step.step) {
    case 'trigger':
      return { icon: '⚡', label: 'Trigger', protocol: 'Event (RabbitMQ)' };
    case 'reasoning':
      return { icon: '🧠', label: 'Reasoned', protocol: 'LLM call' };
    case 'proposal':
      return { icon: '📝', label: 'Drafted plan' };
    case 'action':
      return { icon: '✅', label: 'Action', protocol: 'MCP tool call' };
    default:
      return { icon: '·', label: step.step };
  }
}

function Trace({ trace }: { trace: TraceStep[] }) {
  if (!trace || trace.length === 0) {
    return null;
  }
  return (
    <div className="agent-trace">
      <div className="agent-trace-title">What the agent did, step by step</div>
      {trace.map((step, i) => {
        const d = describeStep(step);
        return (
          <div key={i} className="agent-trace-step">
            <span className="agent-trace-icon" aria-hidden>
              {d.icon}
            </span>
            <span className={`agent-trace-kind agent-trace-${step.step}`}>{d.label}</span>
            {step.tool ? (
              <span className="agent-trace-detail">
                {d.friendly}
                {step.detail && <span className="agent-trace-llm-meta"> · {step.detail}</span>}
                {step.result ? <span className="agent-trace-result"> → {shorten(step.result)}</span> : null}
                <details className="agent-trace-raw">
                  <summary>raw call</summary>
                  <code>{step.tool}</code> {step.input}
                  {step.result ? <div className="agent-trace-result">→ {step.result}</div> : null}
                </details>
              </span>
            ) : (
              <span className="agent-trace-detail">{step.detail}</span>
            )}
            {d.protocol && <span className="trace-proto">{d.protocol}</span>}
          </div>
        );
      })}
    </div>
  );
}

function shorten(value: string, max = 160): string {
  return value.length <= max ? value : value.slice(0, max) + '…';
}

function Proposal({ recommendation }: { recommendation: Record<string, unknown> }) {
  const outreach = recommendation?.outreach as { channel?: string; script?: string } | undefined;
  const intervention = recommendation?.intervention as { type?: string; rationale?: string } | undefined;
  const refill = recommendation?.refillPlan as { daysSupply?: number; note?: string } | undefined;
  const risk = recommendation?.riskExplanation as string | undefined;
  // Access Workflow Agent shapes (autonomous task routing + payer decisions).
  const nextAction = recommendation?.nextAction as string | undefined;
  const task = recommendation?.task as { taskId?: string; title?: string; priority?: string } | undefined;
  const payerDecision = recommendation?.payerDecision as
    | {
        portal?: string;
        payer?: string;
        decision?: string;
        authorizationNumber?: string;
        denialReason?: string;
        reviewer?: string;
        turnaroundSeconds?: number;
      }
    | undefined;
  const financialAssistanceDecision = recommendation?.financialAssistanceDecision as
    | {
        program?: string;
        decision?: string;
        securedAmount?: number;
        denialReason?: string;
        reviewer?: string;
        turnaroundSeconds?: number;
      }
    | undefined;
  if (
    !outreach && !intervention && !refill && !risk && !nextAction && !task &&
    !payerDecision && !financialAssistanceDecision
  ) {
    return null;
  }
  return (
    <div className="agent-proposal">
      <div className="agent-trace-title">Outcome</div>
      {risk && <p className="agent-proposal-risk">{risk}</p>}
      {payerDecision?.decision && (
        <div className="agent-proposal-item agent-payer-decision">
          <strong>Payer decision ({payerDecision.portal ?? 'payer portal'}):</strong>{' '}
          <Chip tone={payerDecision.decision === 'APPROVED' ? 'tone-ok' : 'tone-danger'}>
            {payerDecision.decision}
          </Chip>{' '}
          {payerDecision.authorizationNumber && <>auth {payerDecision.authorizationNumber} · </>}
          {payerDecision.denialReason && <>{payerDecision.denialReason} · </>}
          {payerDecision.reviewer && <>reviewed by {payerDecision.reviewer}</>}
          {payerDecision.turnaroundSeconds != null && <> · {payerDecision.turnaroundSeconds}s turnaround</>}
        </div>
      )}
      {financialAssistanceDecision?.decision && (
        <div className="agent-proposal-item agent-payer-decision">
          <strong>Financial assistance ({financialAssistanceDecision.program ?? 'assistance program'}):</strong>{' '}
          {financialAssistanceDecision.decision === 'NOT_REQUIRED' ? (
            <Chip tone="tone-muted">NOT REQUIRED</Chip>
          ) : (
            <>
              <Chip tone={financialAssistanceDecision.decision === 'APPROVED' ? 'tone-ok' : 'tone-danger'}>
                {financialAssistanceDecision.decision}
              </Chip>{' '}
              {financialAssistanceDecision.securedAmount != null && (
                <>${financialAssistanceDecision.securedAmount} secured · </>
              )}
              {financialAssistanceDecision.denialReason && <>{financialAssistanceDecision.denialReason} · </>}
              {financialAssistanceDecision.reviewer && <>reviewed by {financialAssistanceDecision.reviewer}</>}
              {financialAssistanceDecision.turnaroundSeconds != null && (
                <> · {financialAssistanceDecision.turnaroundSeconds}s turnaround</>
              )}
            </>
          )}
        </div>
      )}
      {nextAction && (
        <div className="agent-proposal-item">
          <strong>Next action:</strong> {nextAction}
        </div>
      )}
      {task?.title && (
        <div className="agent-proposal-item">
          <strong>To-do assigned to the care team ({task.priority ?? 'MEDIUM'}):</strong> {task.title}
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
