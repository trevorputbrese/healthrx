import { afterEach, describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import DashboardPage from './DashboardPage';
import { mockFetch, renderWithProviders, SAMPLE_LOOKUPS } from '../test/utils';
import type { DashboardSummary, DashboardTrends } from '../api/types';

const summary: DashboardSummary = {
  window: { from: '2026-05-30', to: '2026-06-29' },
  tiles: {
    activePatientsOnTherapy: 42,
    medianTimeToTherapyDays: 19,
    medianPriorAuthorizationTurnaroundDays: 5,
    refillRiskCount: 18,
    highRefillRiskCount: 7,
    averageAdherencePdcPercent: 93,
    financialAssistanceSecuredAmount: 104411,
    financialAssistanceSecuredCount: 14,
    overdueTaskCount: 17,
  },
  statusCounts: [{ status: 'ACTIVE_THERAPY', count: 42 }],
  openTasksByOwner: [{ owner: { id: 'o1', displayName: 'Trevor Putbrese' }, count: 7 }],
};
const trends: DashboardTrends = {
  bucket: 'month',
  series: [
    { from: '2026-06-01', to: '2026-07-01', referralsReceived: 21, activatedTherapies: 14, averageAdherencePdcPercent: 93, refillRiskCount: 18 },
  ],
};

afterEach(() => vi.unstubAllGlobals());

describe('DashboardPage', () => {
  it('renders tiles and breakdowns from the API', async () => {
    mockFetch({
      '/api/lookups': SAMPLE_LOOKUPS,
      '/api/dashboard/summary': summary,
      '/api/dashboard/trends': trends,
    });

    renderWithProviders(<DashboardPage />);

    expect(await screen.findByText('Active patients on therapy')).toBeInTheDocument();
    expect(screen.getByText('19.0 d')).toBeInTheDocument();
    expect(screen.getByText('$104,411')).toBeInTheDocument();
    // "Trevor Putbrese" / "42" recur across the acting-as selector and breakdowns; assert presence.
    expect(screen.getAllByText('Trevor Putbrese').length).toBeGreaterThan(0);
  });
});
