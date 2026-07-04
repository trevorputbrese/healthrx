import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { useAgentRecommendations, useSimStatus } from '../api/hooks';
import { relativeTime } from '../format';

/**
 * One-line live strip under the simulation bar: the most recent agent action, visible from
 * every page so agent work is never out of sight. Flashes briefly when a new action lands.
 */
export default function AgentTicker() {
  const recommendations = useAgentRecommendations({});
  const { data: sim } = useSimStatus();
  const latest = recommendations.data?.items?.[0];

  const lastSeenId = useRef<string | null>(null);
  const [isNew, setIsNew] = useState(false);
  const latestId = latest?.id;

  useEffect(() => {
    if (!latestId) return;
    const prev = lastSeenId.current;
    lastSeenId.current = latestId;
    if (prev !== null && prev !== latestId) {
      setIsNew(true);
      const timer = setTimeout(() => setIsNew(false), 4000);
      return () => clearTimeout(timer);
    }
  }, [latestId]);

  if (!latest) {
    return null;
  }

  const live = latest.status === 'AUTO_APPLIED' || latest.status === 'APPLIED';
  return (
    <Link to="/agents" className={`agent-ticker ${isNew ? 'is-new' : ''}`}>
      <span className={`ticker-dot ${live ? 'on' : ''}`} aria-hidden />
      <span className="ticker-label">Agent activity</span>
      <span className="ticker-agent">{latest.agentDisplayName}</span>
      <span className="ticker-summary">{latest.summary}</span>
      <span className="ticker-when">{relativeTime(latest.createdAt, sim?.currentInstant)}</span>
      <span className="ticker-more">View all →</span>
    </Link>
  );
}
