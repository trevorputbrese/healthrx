import { createContext, ReactNode, useContext, useEffect, useMemo, useState } from 'react';
import { useLookups } from '../api/hooks';
import type { NamedRef } from '../api/types';

const STORAGE_KEY = 'healthrx.actingAs';

interface ActingAsValue {
  actorId?: string;
  actor?: NamedRef;
  actors: NamedRef[];
  setActorId: (id: string) => void;
  ready: boolean;
}

const ActingAsContext = createContext<ActingAsValue | undefined>(undefined);

export function ActingAsProvider({ children }: { children: ReactNode }) {
  const { data: lookups } = useLookups();
  const [actorId, setActorIdState] = useState<string | undefined>(
    () => localStorage.getItem(STORAGE_KEY) ?? undefined,
  );

  const actors = useMemo(() => lookups?.owners ?? [], [lookups]);

  // Default to the first active care team member once lookups load.
  useEffect(() => {
    if (actors.length === 0) return;
    const valid = actorId && actors.some((a) => a.id === actorId);
    if (!valid) {
      setActorIdState(actors[0].id);
      localStorage.setItem(STORAGE_KEY, actors[0].id);
    }
  }, [actors, actorId]);

  const setActorId = (id: string) => {
    setActorIdState(id);
    localStorage.setItem(STORAGE_KEY, id);
  };

  const value: ActingAsValue = {
    actorId,
    actor: actors.find((a) => a.id === actorId),
    actors,
    setActorId,
    ready: actors.length > 0 && !!actorId,
  };

  return <ActingAsContext.Provider value={value}>{children}</ActingAsContext.Provider>;
}

export function useActingAs(): ActingAsValue {
  const ctx = useContext(ActingAsContext);
  if (!ctx) {
    throw new Error('useActingAs must be used within ActingAsProvider');
  }
  return ctx;
}
