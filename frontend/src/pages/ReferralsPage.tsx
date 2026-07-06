import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useLookups, useReferralQueue, type QueueParams } from '../api/hooks';
import { days, money } from '../format';
import { PriorityBadge, SortableTh, StateBlock, StatusBadge } from '../components/ui';

const PAGE_SIZE = 25;

/**
 * The full referral directory — every referral ever, including active therapy and cancelled.
 * The Queue stays the operational view (in-flight work); this is the record of everything.
 */
export default function ReferralsPage() {
  const navigate = useNavigate();
  const { data: lookups } = useLookups();
  const [filters, setFilters] = useState<QueueParams>({
    page: 0,
    size: PAGE_SIZE,
    sort: 'receivedAt,desc',
    includeCancelled: true,
  });

  const query = useReferralQueue(filters);

  const set = (patch: Partial<QueueParams>) => setFilters((f) => ({ ...f, page: 0, ...patch }));

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>Referrals</h1>
          <p className="page-sub">
            Every referral on record — in flight, on therapy, or closed. For the working view of
            what needs attention today, use the Queue.
          </p>
        </div>
      </div>

      <div className="filters">
        <input
          className="filter-search"
          type="search"
          placeholder="Search patient or referral #"
          value={filters.search ?? ''}
          onChange={(e) => set({ search: e.target.value })}
        />
        <select value={filters.status ?? ''} onChange={(e) => set({ status: e.target.value || undefined })}>
          <option value="">All statuses</option>
          {lookups?.referralStatuses.map((s) => (
            <option key={s.value} value={s.value}>
              {s.label}
            </option>
          ))}
        </select>
        <select
          value={filters.diseaseState ?? ''}
          onChange={(e) => set({ diseaseState: e.target.value || undefined })}
        >
          <option value="">All disease states</option>
          {lookups?.diseaseStates.map((d) => (
            <option key={d} value={d}>
              {d}
            </option>
          ))}
        </select>
        <select value={filters.payerId ?? ''} onChange={(e) => set({ payerId: e.target.value || undefined })}>
          <option value="">All payers</option>
          {lookups?.payers.map((p) => (
            <option key={p.id} value={p.id}>
              {p.name}
            </option>
          ))}
        </select>
      </div>

      <StateBlock query={query} empty={(d) => d.items.length === 0}>
        {(data) => (
          <>
            <div className="table-wrap">
              <table className="data-table">
                <thead>
                  <tr>
                    <SortableTh label="Referral" field="referralNumber" sort={filters.sort} onSort={(sort) => set({ sort })} />
                    <SortableTh label="Patient" field="patient" sort={filters.sort} onSort={(sort) => set({ sort })} />
                    <SortableTh label="Medication" field="medication" sort={filters.sort} onSort={(sort) => set({ sort })} />
                    <SortableTh label="Status" field="currentStatus" sort={filters.sort} onSort={(sort) => set({ sort })} />
                    <SortableTh label="Priority" field="priority" sort={filters.sort} onSort={(sort) => set({ sort })} />
                    <th>Payer</th>
                    <SortableTh label="Received" field="receivedAt" sort={filters.sort} onSort={(sort) => set({ sort })} className="num" />
                    <SortableTh label="Copay" field="copayAmount" sort={filters.sort} onSort={(sort) => set({ sort })} className="num" />
                  </tr>
                </thead>
                <tbody>
                  {data.items.map((r) => (
                    <tr key={r.id} className="row-click" onClick={() => navigate(`/referrals/${r.id}`)}>
                      <td className="mono">{r.referralNumber}</td>
                      <td>
                        <div className="cell-strong">{r.patient.displayName}</div>
                        <div className="cell-sub">{r.patient.diseaseState}</div>
                      </td>
                      <td>{r.medication.name}</td>
                      <td>
                        <StatusBadge status={r.currentStatus} />
                      </td>
                      <td>
                        <PriorityBadge priority={r.priority} />
                      </td>
                      <td>{r.payer.name}</td>
                      <td className="num">{days(r.daysSinceReceived)} ago</td>
                      <td className="num">{money(r.copayAmount)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            <div className="pager">
              <span>
                {data.totalItems} referral{data.totalItems === 1 ? '' : 's'} · page {data.page + 1} of{' '}
                {Math.max(data.totalPages, 1)}
              </span>
              <div className="pager-btns">
                <button disabled={data.page <= 0} onClick={() => setFilters((f) => ({ ...f, page: (f.page ?? 0) - 1 }))}>
                  Previous
                </button>
                <button
                  disabled={data.page + 1 >= data.totalPages}
                  onClick={() => setFilters((f) => ({ ...f, page: (f.page ?? 0) + 1 }))}
                >
                  Next
                </button>
              </div>
            </div>
          </>
        )}
      </StateBlock>
    </div>
  );
}
