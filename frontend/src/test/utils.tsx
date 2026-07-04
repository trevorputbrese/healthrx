import { ReactNode } from 'react';
import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { vi } from 'vitest';
import { ActingAsProvider } from '../state/ActingAsContext';
import type { Lookups } from '../api/types';

export const SAMPLE_LOOKUPS: Lookups = {
  referralStatuses: [
    { value: 'PRIOR_AUTH_SUBMITTED', label: 'Prior auth submitted', nextStatuses: ['PRIOR_AUTH_APPROVED'] },
  ],
  priorities: ['LOW', 'MEDIUM', 'HIGH', 'URGENT'],
  clinics: [{ id: 'c1', name: 'Northside Oncology' }],
  payers: [{ id: 'p1', name: 'Atlas Commercial' }],
  medications: [{ id: 'm1', name: 'Oncora' }],
  owners: [{ id: 'o1', displayName: 'Trevor Putbrese' }],
  diseaseStates: ['Oncology', 'Rheumatology'],
  taskTypes: [{ value: 'MISSING_LAB', label: 'Missing lab' }],
  outreachChannels: [{ value: 'PHONE', label: 'Phone' }],
  outreachOutcomes: [{ value: 'REACHED', label: 'Reached' }],
  interventionTypes: [{ value: 'ADHERENCE_COUNSELING', label: 'Adherence counseling' }],
};

/** Stubs global.fetch, routing by URL substring to canned JSON. */
export function mockFetch(routes: Record<string, unknown>) {
  const fn = vi.fn(async (input: RequestInfo | URL) => {
    const url = typeof input === 'string' ? input : input.toString();
    const key = Object.keys(routes).find((k) => url.includes(k));
    const body = key ? routes[key] : {};
    return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
  });
  vi.stubGlobal('fetch', fn);
  return fn;
}

export function renderWithProviders(ui: ReactNode, initialPath = '/') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[initialPath]}>
        <ActingAsProvider>{ui}</ActingAsProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}
