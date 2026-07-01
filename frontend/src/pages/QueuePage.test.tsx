import { afterEach, describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import QueuePage from './QueuePage';
import { mockFetch, renderWithProviders, SAMPLE_LOOKUPS } from '../test/utils';
import type { PageResponse, ReferralSummary } from '../api/types';

const sampleRow: ReferralSummary = {
  id: 'r1',
  referralNumber: 'RX-10042',
  patient: { id: 'pt1', displayName: 'Jordan Ellis', diseaseState: 'Oncology' },
  clinic: { id: 'c1', name: 'Northside Oncology' },
  medication: { id: 'm1', name: 'Oncora' },
  payer: { id: 'p1', name: 'Atlas Commercial' },
  owner: { id: 'o1', displayName: 'Maya Patel' },
  currentStatus: 'PRIOR_AUTH_SUBMITTED',
  priority: 'HIGH',
  receivedAt: '2026-06-20T13:00:00Z',
  daysSinceReceived: 9.1,
  priorAuthorizationAgeDays: 4.5,
  copayAmount: 1200,
  financialAssistanceSecuredAmount: 0,
  openTaskCount: 2,
  refillRiskLevel: undefined,
};

afterEach(() => vi.unstubAllGlobals());

describe('QueuePage', () => {
  it('renders referral rows from the API', async () => {
    const page: PageResponse<ReferralSummary> = { items: [sampleRow], page: 0, size: 25, totalItems: 1, totalPages: 1 };
    mockFetch({ '/api/lookups': SAMPLE_LOOKUPS, '/api/referrals': page });

    renderWithProviders(<QueuePage />);

    expect(await screen.findByText('RX-10042')).toBeInTheDocument();
    expect(screen.getByText('Jordan Ellis')).toBeInTheDocument();
    // "Prior auth submitted" appears both as a filter option and the row's status badge.
    expect(screen.getAllByText('Prior auth submitted').length).toBeGreaterThan(0);
  });

  it('shows an empty state when there are no referrals', async () => {
    const empty: PageResponse<ReferralSummary> = { items: [], page: 0, size: 25, totalItems: 0, totalPages: 0 };
    mockFetch({ '/api/lookups': SAMPLE_LOOKUPS, '/api/referrals': empty });

    renderWithProviders(<QueuePage />);

    expect(await screen.findByText(/Nothing to show yet/i)).toBeInTheDocument();
  });
});
