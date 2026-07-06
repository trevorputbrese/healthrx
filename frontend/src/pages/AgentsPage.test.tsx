import { afterEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import AgentsPage from './AgentsPage';
import { mockFetch, renderWithProviders, SAMPLE_LOOKUPS } from '../test/utils';
import type { AgentRecommendation, AgentsResponse, PageResponse } from '../api/types';

const agents: AgentsResponse = {
  agents: [
    {
      name: 'adherence-risk',
      displayName: 'Adherence Risk Agent',
      paused: true,
      reachable: true,
      lastActivityAt: '2026-06-29T00:00:00Z',
      totalRecommendations: 3,
      pendingCount: 1,
      appliedCount: 2,
      autoAppliedCount: 0,
    },
  ],
};

const pending: AgentRecommendation = {
  id: 'rec1',
  agentName: 'adherence-risk',
  agentDisplayName: 'Adherence Risk Agent',
  patient: { id: 'pt1', displayName: 'Marlowe Okafor' },
  referralId: 'r1',
  therapyId: 't1',
  status: 'PENDING',
  summary: 'Reach Marlowe about the missed Neuraxol refill',
  recommendation: {
    riskExplanation: 'Refill overdue and two unanswered calls in the last 14 days.',
    outreach: { channel: 'PHONE', script: 'Hi Marlowe, this is your care team…' },
    intervention: { type: 'ADHERENCE_COUNSELING', rationale: 'Reinforce daily routine.' },
    refillPlan: { daysSupply: 30, note: 'Matches prior fills.' },
  },
  trace: [
    { step: 'trigger', detail: 'RefillMissed for patient pt1' },
    { step: 'query', tool: 'executeQuery', input: 'select …', result: '[…]' },
    {
      step: 'reasoning',
      tool: 'llm.chat_completion',
      detail: 'model gpt-5.4 · 1893 tokens in / 214 out · 2.1s',
      input: 'Trigger: RefillMissed…',
      result: '{"riskExplanation": "…"}',
    },
    { step: 'proposal', detail: 'plan drafted' },
  ],
  createdAt: '2026-06-29T00:00:00Z',
};

const autoApplied: AgentRecommendation = {
  id: 'rec2',
  agentName: 'access-workflow',
  agentDisplayName: 'Access Workflow Agent',
  patient: { id: 'pt2', displayName: 'Jordan Ellis' },
  referralId: 'r2',
  taskId: 'task1',
  status: 'AUTO_APPLIED',
  summary: 'PA pending 6 days for Oncora; payer follow-up routed',
  recommendation: {
    caseSummary: 'Jordan Ellis, Oncora (Oncology), PA pending 6 days with Atlas Commercial.',
    nextAction: 'Call the payer about the pending prior authorization.',
    task: { taskId: 'task1', title: 'Call Atlas about pending PA', priority: 'HIGH' },
  },
  trace: [{ step: 'trigger', detail: 'stuck referral: PA_PENDING' }],
  createdAt: '2026-06-29T00:00:00Z',
};

afterEach(() => vi.unstubAllGlobals());

describe('AgentsPage', () => {
  it('renders agent cards and the recommendation feed', async () => {
    const page: PageResponse<AgentRecommendation> = {
      items: [pending],
      page: 0,
      size: 25,
      totalItems: 1,
      totalPages: 1,
    };
    mockFetch({
      '/api/lookups': SAMPLE_LOOKUPS,
      '/api/agents/recommendations': page,
      '/api/agents': agents,
    });

    renderWithProviders(<AgentsPage />);

    expect((await screen.findAllByText('Adherence Risk Agent')).length).toBeGreaterThan(0);
    expect(screen.getByText('Resume agent')).toBeInTheDocument();
    expect(screen.getByText('Reach Marlowe about the missed Neuraxol refill')).toBeInTheDocument();
  });

  it('renders an AUTO_APPLIED access-agent action with its routed task', async () => {
    const page: PageResponse<AgentRecommendation> = {
      items: [autoApplied],
      page: 0,
      size: 25,
      totalItems: 1,
      totalPages: 1,
    };
    mockFetch({
      '/api/lookups': SAMPLE_LOOKUPS,
      '/api/agents/recommendations': page,
      '/api/agents': agents,
    });

    renderWithProviders(<AgentsPage />);

    fireEvent.click(await screen.findByText(/payer follow-up routed/));
    expect(await screen.findByText(/Call the payer about the pending prior authorization/)).toBeInTheDocument();
    expect(screen.getByText(/Call Atlas about pending PA/)).toBeInTheDocument();
    // Autonomous actions have no approve button.
    expect(screen.queryByText('Approve & apply')).not.toBeInTheDocument();
  });

  it('expands a pending recommendation to show the trace and approve action', async () => {
    const page: PageResponse<AgentRecommendation> = {
      items: [pending],
      page: 0,
      size: 25,
      totalItems: 1,
      totalPages: 1,
    };
    mockFetch({
      '/api/lookups': SAMPLE_LOOKUPS,
      '/api/agents/recommendations': page,
      '/api/agents': agents,
    });

    renderWithProviders(<AgentsPage />);

    fireEvent.click(await screen.findByText('Reach Marlowe about the missed Neuraxol refill'));

    expect(await screen.findByText('What the agent did, step by step')).toBeInTheDocument();
    // Tool calls are narrated in plain English, with the raw call collapsed behind a details element.
    expect(screen.getByText(/Queried the HealthRx database/)).toBeInTheDocument();
    expect(screen.getByText('executeQuery')).toBeInTheDocument();
    // The LLM call is audited into the trace with model, token usage, and latency.
    expect(screen.getByText('LLM call')).toBeInTheDocument();
    expect(screen.getByText(/model gpt-5.4 · 1893 tokens in \/ 214 out/)).toBeInTheDocument();
    expect(screen.getByText('Approve & apply')).toBeInTheDocument();
    expect(screen.getByText(/Refill overdue and two unanswered calls/)).toBeInTheDocument();
  });
});
