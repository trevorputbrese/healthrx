// Force a west-of-UTC timezone BEFORE importing format.ts, so a bare `YYYY-MM-DD` parsed as UTC
// midnight would visibly render as the previous day — the bug these helpers guard against.
// (Declared locally rather than pulling @types/node into this browser app's production build.)
declare const process: { env: Record<string, string | undefined> };
process.env.TZ = 'America/New_York';

import { describe, expect, it } from 'vitest';
import { dateOnly, monthYear } from './format';

describe('date formatting is timezone-safe for bare dates', () => {
  it('renders a LocalDate on its own calendar day, not the day before', () => {
    expect(dateOnly('2026-06-01')).toBe('Jun 1, 2026');
    expect(dateOnly('2026-07-01')).toBe('Jul 1, 2026');
    expect(dateOnly('2026-01-27')).toBe('Jan 27, 2026');
  });

  it('renders a trend-bucket month on its own month, not the month before', () => {
    expect(monthYear('2026-06-01')).toBe('Jun 26');
    expect(monthYear('2026-01-01')).toBe('Jan 26');
  });

  it('still parses full timestamps as real instants', () => {
    // 2026-06-29T00:00:00Z is 2026-06-28 20:00 in America/New_York — the intended local rendering.
    expect(dateOnly('2026-06-29T00:00:00Z')).toBe('Jun 28, 2026');
  });

  it('handles null/undefined', () => {
    expect(dateOnly(null)).toBe('—');
    expect(monthYear(undefined)).toBe('—');
  });
});
