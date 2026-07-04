import { useActingAs } from '../state/ActingAsContext';

/**
 * Shows who is signed in. The demo runs as a single user (the V8 migration collapses the care
 * team to one active member), so this is a label rather than a persona switcher; the context
 * still resolves the actor id from lookups for every write.
 */
export default function ActingAsSelector() {
  const { actor } = useActingAs();
  if (!actor) return null;
  return (
    <span className="acting-as">
      <span className="acting-as-label">Signed in as</span>
      <span className="acting-as-name">{actor.displayName}</span>
    </span>
  );
}
