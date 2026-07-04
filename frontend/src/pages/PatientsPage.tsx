import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useLookups, usePatients } from '../api/hooks';
import { dateOnly } from '../format';
import { RiskBadge, StateBlock } from '../components/ui';

const PAGE_SIZE = 25;

/** Patient directory: every patient with a workload and refill-risk rollup. */
export default function PatientsPage() {
  const navigate = useNavigate();
  const { data: lookups } = useLookups();
  const [search, setSearch] = useState('');
  const [diseaseState, setDiseaseState] = useState('');
  const [page, setPage] = useState(0);

  const query = usePatients({
    search: search || undefined,
    diseaseState: diseaseState || undefined,
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
                    <th>MRN</th>
                    <th>Patient</th>
                    <th>Clinic</th>
                    <th>Payer</th>
                    <th>Owner</th>
                    <th className="num">Therapies</th>
                    <th>Refill risk</th>
                    <th className="num">Active referrals</th>
                    <th className="num">Open tasks</th>
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
