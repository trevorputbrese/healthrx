import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useLookups, useReferralQueue, type QueueParams } from '../api/hooks';
import { days, money } from '../format';
import { PriorityBadge, RiskBadge, SortableTh, StateBlock, StatusBadge } from '../components/ui';

const PAGE_SIZE = 25;

export default function QueuePage() {
  const navigate = useNavigate();
  const { data: lookups } = useLookups();
  const [filters, setFilters] = useState<QueueParams>({ page: 0, size: PAGE_SIZE, sort: 'receivedAt,desc' });

  const query = useReferralQueue(filters);

  const set = (patch: Partial<QueueParams>) => setFilters((f) => ({ ...f, page: 0, ...patch }));

  // Flash rows whose status changed (or that newly appeared) between polls, so movement in the
  // world — a scenario click, the ambient stream, or an agent advancing a referral — is visible.
  // Tracking resets whenever the query key changes (filters/page), so a re-filtered result set
  // never reads as fresh movement.
  const prevStatuses = useRef<Map<string, string> | null>(null);
  const [changedIds, setChangedIds] = useState<Set<string>>(new Set());
  const items = query.data?.items;
  useEffect(() => {
    if (!items) {
      prevStatuses.current = null;
      setChangedIds((cur) => (cur.size ? new Set<string>() : cur));
      return;
    }
    const next = new Map(items.map((r) => [r.id, r.currentStatus as string]));
    const prev = prevStatuses.current;
    prevStatuses.current = next;
    if (prev === null) return;
    const changed = items.filter((r) => prev.get(r.id) !== r.currentStatus).map((r) => r.id);
    if (changed.length > 0) {
      setChangedIds(new Set(changed));
      const timer = setTimeout(() => setChangedIds(new Set()), 3500);
      return () => clearTimeout(timer);
    }
  }, [items]);

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>Enrollment &amp; Access Queue</h1>
          <p className="page-sub">Track specialty referrals through access milestones.</p>
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
        <select value={filters.diseaseState ?? ''} onChange={(e) => set({ diseaseState: e.target.value || undefined })}>
          <option value="">All disease states</option>
          {lookups?.diseaseStates.map((d) => (
            <option key={d} value={d}>
              {d}
            </option>
          ))}
        </select>
        <select value={filters.clinicId ?? ''} onChange={(e) => set({ clinicId: e.target.value || undefined })}>
          <option value="">All clinics</option>
          {lookups?.clinics.map((c) => (
            <option key={c.id} value={c.id}>
              {c.name}
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
        <label className="filter-check">
          <input
            type="checkbox"
            checked={!!filters.includeCancelled}
            onChange={(e) => set({ includeCancelled: e.target.checked })}
          />
          Include cancelled
        </label>
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
                    <SortableTh label="Owner" field="owner" sort={filters.sort} onSort={(sort) => set({ sort })} />
                    <SortableTh label="Days / PA age" field="receivedAt" sort={filters.sort} onSort={(sort) => set({ sort })} className="num" />
                    <SortableTh label="Copay" field="copayAmount" sort={filters.sort} onSort={(sort) => set({ sort })} className="num" />
                    <th>Refill risk</th>
                    <SortableTh label="Tasks" field="openTaskCount" sort={filters.sort} onSort={(sort) => set({ sort })} className="num" />
                  </tr>
                </thead>
                <tbody>
                  {data.items.map((r) => (
                    <tr
                      key={r.id}
                      className={`row-click ${changedIds.has(r.id) ? 'row-changed' : ''}`}
                      onClick={() => navigate(`/referrals/${r.id}`)}
                    >
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
                      <td>{r.owner.displayName}</td>
                      <td className="num">
                        {r.priorAuthorizationAgeDays != null ? (
                          <span title="Prior authorization age">PA {days(r.priorAuthorizationAgeDays)}</span>
                        ) : (
                          days(r.daysSinceReceived)
                        )}
                      </td>
                      <td className="num">{money(r.copayAmount)}</td>
                      <td>{r.refillRiskLevel ? <RiskBadge risk={r.refillRiskLevel} /> : <span className="cell-sub">—</span>}</td>
                      <td className="num">{r.openTaskCount > 0 ? r.openTaskCount : '—'}</td>
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
