import { useState } from 'react';
import { useLogIntervention, useLogOutreach, useLookups } from '../api/hooks';
import { useActingAs } from '../state/ActingAsContext';
import Modal from './Modal';
import { MutationFooter } from '../pages/ReferralDetailPage';

/**
 * Patient care actions shared by the Patient Workbench and the Tasks page: log an outreach
 * contact or a clinical intervention, optionally linked to a referral.
 */
export function OutreachModal({
  patientId,
  referralId,
  onClose,
}: {
  patientId: string;
  referralId?: string;
  onClose: () => void;
}) {
  const { actorId } = useActingAs();
  const { data: lookups } = useLookups();
  const [channel, setChannel] = useState('PHONE');
  const [outcome, setOutcome] = useState('REACHED');
  const [notes, setNotes] = useState('');
  const mutation = useLogOutreach(patientId);
  return (
    <Modal title="Log outreach" onClose={onClose}>
      <label className="field">
        <span>Channel</span>
        <select value={channel} onChange={(e) => setChannel(e.target.value)}>
          {lookups?.outreachChannels.map((o) => (
            <option key={o.value} value={o.value}>
              {o.label}
            </option>
          ))}
        </select>
      </label>
      <label className="field">
        <span>Outcome</span>
        <select value={outcome} onChange={(e) => setOutcome(e.target.value)}>
          {lookups?.outreachOutcomes.map((o) => (
            <option key={o.value} value={o.value}>
              {o.label}
            </option>
          ))}
        </select>
      </label>
      <label className="field">
        <span>Notes (optional)</span>
        <textarea value={notes} onChange={(e) => setNotes(e.target.value)} rows={3} />
      </label>
      <MutationFooter
        mutation={mutation}
        disabled={!actorId}
        onSubmit={() =>
          mutation.mutate(
            { referralId, ownerId: actorId!, channel, outcome, notes: notes || undefined },
            { onSuccess: onClose },
          )
        }
      />
    </Modal>
  );
}

export function InterventionModal({
  patientId,
  referralId,
  onClose,
}: {
  patientId: string;
  referralId?: string;
  onClose: () => void;
}) {
  const { actorId } = useActingAs();
  const { data: lookups } = useLookups();
  const [interventionType, setInterventionType] = useState('ADHERENCE_COUNSELING');
  const [summary, setSummary] = useState('');
  const mutation = useLogIntervention(patientId);
  return (
    <Modal title="Log clinical intervention" onClose={onClose}>
      <label className="field">
        <span>Type</span>
        <select value={interventionType} onChange={(e) => setInterventionType(e.target.value)}>
          {lookups?.interventionTypes.map((o) => (
            <option key={o.value} value={o.value}>
              {o.label}
            </option>
          ))}
        </select>
      </label>
      <label className="field">
        <span>Summary</span>
        <textarea value={summary} onChange={(e) => setSummary(e.target.value)} rows={4} autoFocus />
      </label>
      <MutationFooter
        mutation={mutation}
        disabled={!actorId || summary.trim() === ''}
        onSubmit={() =>
          mutation.mutate(
            { referralId, ownerId: actorId!, interventionType, summary: summary.trim() },
            { onSuccess: onClose },
          )
        }
      />
    </Modal>
  );
}
