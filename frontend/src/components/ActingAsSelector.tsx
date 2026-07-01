import { useActingAs } from '../state/ActingAsContext';

/** Header "Acting as" selector — the Phase 1 (no-auth) actor model. */
export default function ActingAsSelector() {
  const { actors, actorId, setActorId } = useActingAs();
  if (actors.length === 0) return null;
  return (
    <label className="acting-as">
      <span className="acting-as-label">Acting as</span>
      <select value={actorId ?? ''} onChange={(e) => setActorId(e.target.value)}>
        {actors.map((a) => (
          <option key={a.id} value={a.id}>
            {a.displayName}
          </option>
        ))}
      </select>
    </label>
  );
}
