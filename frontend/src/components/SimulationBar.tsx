import { useResetDemo, useSimControl, useSimStatus } from '../api/hooks';

const SPEEDS: { value: number; label: string }[] = [
  { value: 1800, label: '30 min/sec' },
  { value: 3600, label: '1 hr/sec' },
  { value: 21600, label: '6 hr/sec' },
  { value: 86400, label: '1 day/sec' },
  { value: 259200, label: '3 days/sec' },
];

const SCENARIO_LABELS: Record<string, string> = {
  'new-referral': 'New referral',
  'advance-referral': 'Advance a referral',
  'submit-prior-auth': 'Submit prior auth',
  'send-at-risk': 'Send at-risk',
  'resolve-risk': 'Resolve risk',
};

/** Demo control bar for the synthetic data generator (proxied through the API). */
export default function SimulationBar() {
  const { data, isError, isLoading } = useSimStatus();
  const { start, stop, setSpeed, scenario } = useSimControl();
  const resetDemo = useResetDemo();

  const onReset = () => {
    if (window.confirm('Reset the demo? This wipes all data and reseeds it from scratch, and pauses the simulation.')) {
      resetDemo.mutate();
    }
  };

  if (isLoading) {
    return null;
  }
  if (isError || !data) {
    return (
      <div className="simbar simbar-offline">
        <span className="sim-dot" /> Simulation generator offline
      </div>
    );
  }

  const simDate = new Date(data.currentInstant).toLocaleDateString('en-US', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  });
  const busy = start.isPending || stop.isPending;

  return (
    <div className="simbar">
      <span className={`sim-dot ${data.enabled ? 'on' : ''}`} />
      <span className="sim-state">{data.enabled ? 'Live' : 'Paused'}</span>
      <span className="sim-clock">Sim date: {simDate}</span>

      <button
        className="btn btn-sm"
        disabled={busy}
        onClick={() => (data.enabled ? stop.mutate() : start.mutate())}
      >
        {data.enabled ? 'Pause' : 'Start'} simulation
      </button>

      <label className="sim-speed">
        Speed
        <select value={String(data.speedSecondsPerSecond)} onChange={(e) => setSpeed.mutate(Number(e.target.value))}>
          {SPEEDS.map((s) => (
            <option key={s.value} value={s.value}>
              {s.label}
            </option>
          ))}
        </select>
      </label>

      <span className="sim-divider" />
      <span className="sim-scenario-label">Scenarios:</span>
      {data.scenarios.map((s) => (
        <button key={s} className="btn btn-sm" disabled={scenario.isPending} onClick={() => scenario.mutate(s)}>
          {SCENARIO_LABELS[s] ?? s}
        </button>
      ))}

      <span className="sim-reset">
        <button className="btn btn-sm btn-reset" disabled={resetDemo.isPending} onClick={onReset}>
          {resetDemo.isPending ? 'Resetting…' : 'Reset demo'}
        </button>
      </span>
    </div>
  );
}
