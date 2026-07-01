import { afterEach, describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import LifecyclePage from './LifecyclePage';
import { mockFetch, renderWithProviders, SAMPLE_LOOKUPS } from '../test/utils';
import type { PageResponse, ReferralDetail, ReferralSummary } from '../api/types';

const listRow: ReferralSummary = {
  id: 'r1',
  referralNumber: 'RX-10001',
  patient: { id: 'pt1', displayName: 'Jordan Ellis', diseaseState: 'Oncology' },
  clinic: { id: 'c1', name: 'Northside Oncology' },
  medication: { id: 'm1', name: 'Oncora' },
  payer: { id: 'p1', name: 'Atlas Commercial' },
  owner: { id: 'o1', displayName: 'Maya Patel' },
  currentStatus: 'FINANCIAL_ASSISTANCE_REVIEW',
  priority: 'HIGH',
  receivedAt: '2026-06-01T13:00:00Z',
  copayAmount: 2850,
  financialAssistanceSecuredAmount: 0,
  openTaskCount: 0,
};

const detail: ReferralDetail = {
  id: 'r1',
  referralNumber: 'RX-10001',
  patient: { id: 'pt1', displayName: 'Jordan Ellis', dateOfBirth: '1978-04-12', diseaseState: 'Oncology' },
  clinic: { id: 'c1', name: 'Northside Oncology' },
  medication: { id: 'm1', name: 'Oncora', route: 'Oral' },
  payer: { id: 'p1', name: 'Atlas Commercial', payerType: 'Commercial' },
  owner: { id: 'o1', displayName: 'Maya Patel' },
  currentStatus: 'FINANCIAL_ASSISTANCE_REVIEW',
  allowedNextStatuses: ['READY_TO_FILL', 'CANCELLED'],
  priority: 'HIGH',
  receivedAt: '2026-06-01T13:00:00Z',
  milestones: {
    benefitsInvestigationStartedAt: '2026-06-03T13:00:00Z',
    paSubmittedAt: '2026-06-05T13:00:00Z',
    paDecidedAt: '2026-06-08T13:00:00Z',
  },
  financials: { copayAmount: 2850, financialAssistanceRequired: true, financialAssistanceSecuredAmount: 0 },
  metrics: { daysSinceReceived: 28 },
  openTasks: [],
  recentNotes: [],
  statusHistory: [
    { id: 'h1', toStatus: 'ELIGIBILITY_IDENTIFIED', changedAt: '2026-06-01T13:00:00Z' },
    { id: 'h2', fromStatus: 'ELIGIBILITY_IDENTIFIED', toStatus: 'BENEFITS_INVESTIGATION', changedAt: '2026-06-03T13:00:00Z' },
    { id: 'h3', fromStatus: 'BENEFITS_INVESTIGATION', toStatus: 'PRIOR_AUTH_SUBMITTED', changedAt: '2026-06-05T13:00:00Z' },
    { id: 'h4', fromStatus: 'PRIOR_AUTH_SUBMITTED', toStatus: 'PRIOR_AUTH_APPROVED', changedAt: '2026-06-08T13:00:00Z' },
    { id: 'h5', fromStatus: 'PRIOR_AUTH_APPROVED', toStatus: 'FINANCIAL_ASSISTANCE_REVIEW', changedAt: '2026-06-10T13:00:00Z' },
  ],
};

afterEach(() => vi.unstubAllGlobals());

describe('LifecyclePage', () => {
  it('renders the status reference and highlights a selected referral on the flow map', async () => {
    const page: PageResponse<ReferralSummary> = { items: [listRow], page: 0, size: 8, totalItems: 1, totalPages: 1 };
    mockFetch({
      '/api/lookups': SAMPLE_LOOKUPS,
      '/api/referrals/': detail, // detail (path has trailing slash + id)
      'referrals?': page, // list/search (query string)
    });

    renderWithProviders(<LifecyclePage />);

    // Page + status reference render.
    expect(await screen.findByRole('heading', { name: 'Referral Lifecycle' })).toBeInTheDocument();
    expect(screen.getByText(/Securing copay assistance/i)).toBeInTheDocument();

    // The referral auto-selects and the map reflects its journey.
    expect(await screen.findByText('RX-10001')).toBeInTheDocument();
    expect(await screen.findByText('PA approved')).toBeInTheDocument();
    expect(screen.getAllByText('Financial assistance').length).toBeGreaterThan(0);
  });
});
