import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useLookups, usePatients } from '../api/hooks';
import { dateOnly } from '../format';
import { RiskBadge, SortableTh, StateBlock } from '../components/ui';

const PAGE_SIZE = 25;

/** Patient directory: every patient with a workload and refill-risk rollup. */
export default function PatientsPage() {
  const navigate = useNavigate();
  const { data: lookups } = useLookups();
  const [search, setSearch] = useState('');
  const [diseaseState, setDiseaseState] = useState('');
  const [sort, setSort] = useState('name,asc');
  const [page, setPage] = useState(0);

  const onSort = (next: string) => {
    setSort(next);
    setPage(0);
  };

  const query = usePatients({
    search: search || undefined,
    diseaseState: diseaseState || undefined,
    sort,
    page,
    size: PAGE_SIZE,
  });

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>Patients</h1>
          <p className="page-sub">
            Everyone under HealthRx care. Open a patient to see therapies, adherence, and their
            full journey timeline.
          </p>
        </div>
      </div>

      <div className="filters">
        <input
          className="filter-search"
          type="search"
          placeholder="Search name or MRN"
          value={search}
          onChange={(e) => {
            setSearch(e.target.value);
            setPage(0);
          }}
        />
        <select
          value={diseaseState}
          onChange={(e) => {
            setDiseaseState(e.target.value);
            setPage(0);
          }}
        >
          <option value="">All disease states</option>
          {lookups?.diseaseStates.map((d) => (
            <option key={d} value={d}>
              {d}
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
                    <SortableTh label="MRN" field="mrn" sort={sort} onSort={onSort} />
                    <SortableTh label="Patient" field="name" sort={sort} onSort={onSort} />
                    <SortableTh label="Clinic" field="clinic" sort={sort} onSort={onSort} />
                    <SortableTh label="Payer" field="payer" sort={sort} onSort={onSort} />
                    <SortableTh label="Owner" field="owner" sort={sort} onSort={onSort} />
                    <SortableTh label="Therapies" field="therapyCount" sort={sort} onSort={onSort} className="num" />
                    <th>Refill risk</th>
                    <SortableTh label="Active referrals" field="activeReferralCount" sort={sort} onSort={onSort} className="num" />
                    <SortableTh label="Open tasks" field="openTaskCount" sort={sort} onSort={onSort} className="num" />
                  </tr>
                </thead>
                <tbody>
                  {data.items.map((p) => (
                    <tr key={p.id} className="row-click" onClick={() => navigate(`/patients/${p.id}`)}>
                      <td className="mono">{p.demoMrn}</td>
                      <td>
                        <div className="cell-strong">{p.displayName}</div>
                        <div className="cell-sub">
                          {p.diseaseState} · DOB {dateOnly(p.dateOfBirth)}
                        </div>
                      </td>
                      <td>{p.clinic.name}</td>
                      <td>{p.payer.name}</td>
                      <td>{p.primaryOwner.displayName}</td>
                      <td className="num">{p.therapyCount > 0 ? p.therapyCount : '—'}</td>
                      <td>
                        {p.highestRefillRiskLevel ? (
                          <RiskBadge risk={p.highestRefillRiskLevel} />
                        ) : (
                          <span className="cell-sub">—</span>
                        )}
                      </td>
                      <td className="num">{p.activeReferralCount > 0 ? p.activeReferralCount : '—'}</td>
                      <td className="num">{p.openTaskCount > 0 ? p.openTaskCount : '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            <div className="pager">
              <span>
                {data.totalItems} patient{data.totalItems === 1 ? '' : 's'} · page {data.page + 1} of{' '}
                {Math.max(data.totalPages, 1)}
              </span>
              <div className="pager-btns">
                <button disabled={data.page <= 0} onClick={() => setPage((p) => p - 1)}>
                  Previous
                </button>
                <button disabled={data.page + 1 >= data.totalPages} onClick={() => setPage((p) => p + 1)}>
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
