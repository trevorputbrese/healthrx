import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import ReferralFlowMap from './ReferralFlowMap';
import { STATUS_LABELS } from '../format';
import type { ReferralStatus } from '../api/types';

const labelFor = (s: ReferralStatus) => STATUS_LABELS[s] ?? s;
const D = '2026-06-05T13:00:00Z';

describe('ReferralFlowMap', () => {
  it('shows a pending PA decision after a denial+resubmit (not a completed "denied")', () => {
    render(
      <ReferralFlowMap
        current="PRIOR_AUTH_SUBMITTED"
        reached={{
          ELIGIBILITY_IDENTIFIED: D,
          BENEFITS_INVESTIGATION: D,
          PRIOR_AUTH_SUBMITTED: D,
          PRIOR_AUTH_DENIED: D, // a prior cycle was denied; now resubmitted
        }}
        labelFor={labelFor}
      />,
    );
    expect(screen.getByText('PA decision (pending)')).toBeInTheDocument();
    expect(screen.queryByText('PA denied')).not.toBeInTheDocument();
  });

  it('marks the decision as approved (current) when the referral is PA approved', () => {
    render(
      <ReferralFlowMap
        current="PRIOR_AUTH_APPROVED"
        reached={{ ELIGIBILITY_IDENTIFIED: D, BENEFITS_INVESTIGATION: D, PRIOR_AUTH_SUBMITTED: D, PRIOR_AUTH_APPROVED: D }}
        labelFor={labelFor}
      />,
    );
    const approved = screen.getByText('PA approved').closest('.flow-node');
    expect(approved).toHaveAttribute('aria-current', 'step');
  });

  it('renders a cancelled terminal marker for a cancelled referral', () => {
    render(
      <ReferralFlowMap
        current="CANCELLED"
        reached={{ ELIGIBILITY_IDENTIFIED: D, BENEFITS_INVESTIGATION: D, CANCELLED: D }}
        labelFor={labelFor}
      />,
    );
    // "Cancelled" also appears in the footnote; scope to the flow-node terminal chip.
    const chip = screen.getAllByText('Cancelled').map((el) => el.closest('.flow-node')).find(Boolean);
    expect(chip).toBeTruthy();
    expect(chip).toHaveAttribute('aria-current', 'step');
    expect(chip?.className).toContain('accent-danger');
  });
});
