import { ReactElement, useMemo, useState } from 'react';
import {
  Bar,
  BarChart,
  CartesianGrid,
  Legend,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import { useDashboardSummary, useDashboardTrends, useLookups, type DashboardParams } from '../api/hooks';
import { days, money, monthYear, percent, STATUS_LABELS } from '../format';
import { Card, StateBlock, Tile } from '../components/ui';
import type { ReferralStatus, TrendBucket } from '../api/types';

export default function DashboardPage() {
  const { data: lookups } = useLookups();
  const [filters, setFilters] = useState<DashboardParams>({});
  const [bucket, setBucket] = useState('month');

  const summary = useDashboardSummary(filters);
  const trends = useDashboardTrends(filters, bucket);

  const set = (patch: Partial<DashboardParams>) => setFilters((f) => ({ ...f, ...patch }));

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>Outcomes Dashboard</h1>
          <p className="page-sub">Operational view across access, adherence, and outcomes.</p>
        </div>
      </div>

      <div className="filters">
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
        <select value={filters.medicationId ?? ''} onChange={(e) => set({ medicationId: e.target.value || undefined })}>
          <option value="">All medications</option>
          {lookups?.medications.map((m) => (
            <option key={m.id} value={m.id}>
              {m.name}
            </option>
          ))}
        </select>
        <select value={filters.ownerId ?? ''} onChange={(e) => set({ ownerId: e.target.value || undefined })}>
          <option value="">All owners</option>
          {lookups?.owners.map((o) => (
            <option key={o.id} value={o.id}>
              {o.displayName}
            </option>
          ))}
        </select>
      </div>

      <StateBlock query={summary}>
        {(s) => (
          <>
            <p className="page-sub window-note">
              Window {s.window.from} → {s.window.to}
            </p>
            <div className="tiles">
              <Tile label="Active patients on therapy" value={s.tiles.activePatientsOnTherapy} />
              <Tile label="Median time to therapy" value={days(s.tiles.medianTimeToTherapyDays)} />
              <Tile label="Median PA turnaround" value={days(s.tiles.medianPriorAuthorizationTurnaroundDays)} />
              <Tile
                label="Refill risk"
                value={s.tiles.refillRiskCount}
                sub={`${s.tiles.highRefillRiskCount} high`}
              />
              <Tile label="Avg adherence (PDC)" value={percent(s.tiles.averageAdherencePdcPercent)} />
              <Tile
                label="Financial assistance secured"
                value={money(s.tiles.financialAssistanceSecuredAmount)}
                sub={`${s.tiles.financialAssistanceSecuredCount} patients`}
              />
              <Tile label="Overdue tasks" value={s.tiles.overdueTaskCount} />
            </div>

            <div className="dash-grid">
              <Card title="Referrals by status">
                <StatusBars
                  counts={s.statusCounts}
                  max={Math.max(1, ...s.statusCounts.map((c) => c.count))}
                />
              </Card>
              <Card title="Open tasks by owner">
                {s.openTasksByOwner.length === 0 ? (
                  <p className="cell-sub">No open tasks.</p>
                ) : (
                  <ul className="owner-tasks">
                    {s.openTasksByOwner.map((o) => (
                      <li key={o.owner.id}>
                        <span>{o.owner.displayName}</span>
                        <span className="badge tone-info">{o.count}</span>
                      </li>
                    ))}
                  </ul>
                )}
              </Card>
            </div>
          </>
        )}
      </StateBlock>

      <Card
        title="Trends"
        action={
          <div className="chip-filters">
            {['month', 'week'].map((b) => (
              <button key={b} className={`chip-toggle ${bucket === b ? 'on' : ''}`} onClick={() => setBucket(b)}>
                {b === 'month' ? 'Monthly' : 'Weekly'}
              </button>
            ))}
          </div>
        }
      >
        <StateBlock query={trends} empty={(d) => d.series.length === 0}>
          {(t) => <Trends series={t.series} />}
        </StateBlock>
      </Card>
    </div>
  );
}

function StatusBars({
  counts,
  max,
}: {
  counts: { status: ReferralStatus; count: number }[];
  max: number;
}) {
  if (counts.length === 0) return <p className="cell-sub">No referrals match these filters.</p>;
  return (
    <div className="status-bars">
      {counts.map((c) => (
        <div key={c.status} className="status-bar-row">
          <span className="status-bar-label">{STATUS_LABELS[c.status] ?? c.status}</span>
          <div className="status-bar-track">
            <div className="status-bar-fill" style={{ width: `${(c.count / max) * 100}%` }} />
          </div>
          <span className="status-bar-count">{c.count}</span>
        </div>
      ))}
    </div>
  );
}

function Trends({ series }: { series: TrendBucket[] }) {
  const data = useMemo(
    () =>
      series.map((b) => ({
        label: monthYear(b.from),
        Received: b.referralsReceived,
        Activated: b.activatedTherapies,
        'Time to therapy (d)': b.medianTimeToTherapyDays ?? null,
        'PA turnaround (d)': b.medianPriorAuthorizationTurnaroundDays ?? null,
        'Adherence PDC (%)': b.averageAdherencePdcPercent ?? null,
        'Refill risk': b.refillRiskCount,
      })),
    [series],
  );

  return (
    <div className="charts">
      <ChartFrame title="Referrals received vs therapies activated">
        <BarChart data={data} margin={{ top: 8, right: 8, left: -16, bottom: 0 }}>
          <CartesianGrid strokeDasharray="3 3" stroke="#eef2f6" />
          <XAxis dataKey="label" tick={{ fontSize: 12 }} />
          <YAxis tick={{ fontSize: 12 }} allowDecimals={false} />
          <Tooltip />
          <Legend />
          <Bar dataKey="Received" fill="#0f6fb5" radius={[3, 3, 0, 0]} />
          <Bar dataKey="Activated" fill="#137a63" radius={[3, 3, 0, 0]} />
        </BarChart>
      </ChartFrame>
      <ChartFrame title="Median access timing (days)">
        <LineChart data={data} margin={{ top: 8, right: 8, left: -16, bottom: 0 }}>
          <CartesianGrid strokeDasharray="3 3" stroke="#eef2f6" />
          <XAxis dataKey="label" tick={{ fontSize: 12 }} />
          <YAxis tick={{ fontSize: 12 }} />
          <Tooltip />
          <Legend />
          <Line type="monotone" dataKey="Time to therapy (d)" stroke="#0f6fb5" connectNulls dot={false} />
          <Line type="monotone" dataKey="PA turnaround (d)" stroke="#b5760a" connectNulls dot={false} />
        </LineChart>
      </ChartFrame>
      <ChartFrame title="Average adherence (PDC %)">
        <LineChart data={data} margin={{ top: 8, right: 8, left: -16, bottom: 0 }}>
          <CartesianGrid strokeDasharray="3 3" stroke="#eef2f6" />
          <XAxis dataKey="label" tick={{ fontSize: 12 }} />
          <YAxis domain={[0, 100]} tick={{ fontSize: 12 }} />
          <Tooltip />
          <Line type="monotone" dataKey="Adherence PDC (%)" stroke="#137a63" connectNulls dot={false} />
        </LineChart>
      </ChartFrame>
      <ChartFrame title="Refill risk (medium + high)">
        <BarChart data={data} margin={{ top: 8, right: 8, left: -16, bottom: 0 }}>
          <CartesianGrid strokeDasharray="3 3" stroke="#eef2f6" />
          <XAxis dataKey="label" tick={{ fontSize: 12 }} />
          <YAxis tick={{ fontSize: 12 }} allowDecimals={false} />
          <Tooltip />
          <Bar dataKey="Refill risk" fill="#b5760a" radius={[3, 3, 0, 0]} />
        </BarChart>
      </ChartFrame>
    </div>
  );
}

function ChartFrame({ title, children }: { title: string; children: ReactElement }) {
  return (
    <div className="chart">
      <div className="chart-title">{title}</div>
      <ResponsiveContainer width="100%" height={220}>
        {children}
      </ResponsiveContainer>
    </div>
  );
}
