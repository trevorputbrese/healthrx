import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, qs } from './client';
import type {
  AgentRecommendation,
  AgentsResponse,
  DashboardSummary,
  DashboardTrends,
  Lookups,
  PageResponse,
  PatientDetail,
  PatientSummary,
  ReferralDetail,
  ReferralSummary,
  SimStatus,
  TimelineResponse,
} from './types';

// Poll interval that keeps views live while the generator streams events.
const LIVE = 8000;

export interface QueueParams {
  status?: string;
  clinicId?: string;
  diseaseState?: string;
  payerId?: string;
  medicationId?: string;
  ownerId?: string;
  priority?: string;
  search?: string;
  includeCancelled?: boolean;
  page?: number;
  size?: number;
  sort?: string;
}

export interface DashboardParams {
  clinicId?: string;
  diseaseState?: string;
  payerId?: string;
  medicationId?: string;
  ownerId?: string;
}

export function useLookups() {
  return useQuery({
    queryKey: ['lookups'],
    queryFn: () => api.get<Lookups>('/api/lookups'),
    staleTime: 5 * 60_000,
  });
}

export function useReferralQueue(params: QueueParams) {
  return useQuery({
    queryKey: ['referrals', params],
    queryFn: () => api.get<PageResponse<ReferralSummary>>(`/api/referrals${qs({ ...params })}`),
    refetchInterval: LIVE,
  });
}

export function useReferral(id: string | undefined) {
  return useQuery({
    queryKey: ['referral', id],
    queryFn: () => api.get<ReferralDetail>(`/api/referrals/${id}`),
    enabled: !!id,
  });
}

export function usePatients(params: { search?: string; diseaseState?: string; page?: number; size?: number }) {
  return useQuery({
    queryKey: ['patients', params],
    queryFn: () => api.get<PageResponse<PatientSummary>>(`/api/patients${qs({ ...params })}`),
    refetchInterval: LIVE,
  });
}

export function usePatient(id: string | undefined) {
  return useQuery({
    queryKey: ['patient', id],
    queryFn: () => api.get<PatientDetail>(`/api/patients/${id}`),
    enabled: !!id,
    refetchInterval: LIVE,
  });
}

export function usePatientTimeline(id: string | undefined, types: string[]) {
  return useQuery({
    queryKey: ['timeline', id, types],
    queryFn: () => {
      const search = new URLSearchParams();
      types.forEach((t) => search.append('type', t));
      search.set('limit', '100');
      return api.get<TimelineResponse>(`/api/patients/${id}/timeline?${search.toString()}`);
    },
    enabled: !!id,
  });
}

export function useDashboardSummary(params: DashboardParams) {
  return useQuery({
    queryKey: ['dashboard-summary', params],
    queryFn: () => api.get<DashboardSummary>(`/api/dashboard/summary${qs({ ...params })}`),
    refetchInterval: LIVE,
  });
}

export function useDashboardTrends(params: DashboardParams, bucket: string) {
  return useQuery({
    queryKey: ['dashboard-trends', params, bucket],
    queryFn: () => api.get<DashboardTrends>(`/api/dashboard/trends${qs({ ...params, bucket })}`),
    refetchInterval: LIVE,
  });
}

// --- Mutations ---

export function useAdvanceStatus(referralId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: { toStatus: string; changedById: string; note?: string }) =>
      api.patch(`/api/referrals/${referralId}/status`, body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['referral', referralId] });
      qc.invalidateQueries({ queryKey: ['referrals'] });
      qc.invalidateQueries({ queryKey: ['dashboard-summary'] });
    },
  });
}

export function useUpdateFinancials(referralId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: {
      changedById: string;
      copayAmount?: number;
      financialAssistanceSecuredAmount?: number;
      financialAssistanceRequired?: boolean;
      note?: string;
    }) => api.patch(`/api/referrals/${referralId}/financials`, body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['referral', referralId] });
      qc.invalidateQueries({ queryKey: ['referrals'] });
      qc.invalidateQueries({ queryKey: ['dashboard-summary'] });
    },
  });
}

export function useAddNote(referralId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: { authorId: string; body: string }) =>
      api.post(`/api/referrals/${referralId}/notes`, body),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['referral', referralId] }),
  });
}

export function useLogOutreach(patientId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: {
      referralId?: string;
      ownerId: string;
      channel: string;
      outcome: string;
      notes?: string;
    }) => api.post(`/api/patients/${patientId}/outreach`, body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['patient', patientId] });
      qc.invalidateQueries({ queryKey: ['timeline', patientId] });
    },
  });
}

export function useLogIntervention(patientId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: { referralId?: string; ownerId: string; interventionType: string; summary: string }) =>
      api.post(`/api/patients/${patientId}/interventions`, body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['patient', patientId] });
      qc.invalidateQueries({ queryKey: ['timeline', patientId] });
    },
  });
}

// --- Simulation control (proxied through the API to the generator) ---

export function useSimStatus() {
  return useQuery({
    queryKey: ['sim-status'],
    queryFn: () => api.get<SimStatus>('/api/simulation/status'),
    refetchInterval: 3000,
    retry: false,
  });
}

export function useSimControl() {
  const qc = useQueryClient();
  const refreshAll = () => {
    qc.invalidateQueries({ queryKey: ['sim-status'] });
    qc.invalidateQueries({ queryKey: ['referrals'] });
    qc.invalidateQueries({ queryKey: ['dashboard-summary'] });
    qc.invalidateQueries({ queryKey: ['dashboard-trends'] });
    qc.invalidateQueries({ queryKey: ['patient'] });
  };
  return {
    start: useMutation({ mutationFn: () => api.post('/api/simulation/start', {}), onSuccess: refreshAll }),
    stop: useMutation({ mutationFn: () => api.post('/api/simulation/stop', {}), onSuccess: refreshAll }),
    setSpeed: useMutation({
      mutationFn: (value: number) => api.post(`/api/simulation/speed?value=${value}`, {}),
      onSuccess: refreshAll,
    }),
    scenario: useMutation({
      mutationFn: (name: string) => api.post(`/api/simulation/scenario/${name}`, {}),
      onSuccess: refreshAll,
    }),
  };
}

export function useResetDemo() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => api.post('/api/admin/reset', {}),
    // Wipe + reseed touches everything: invalidate all queries so the whole UI refreshes.
    onSuccess: () => qc.invalidateQueries(),
  });
}

// --- Phase 3: Agents view (poll fast so decisions read as live on stage) ---
const AGENTS_LIVE = 4000;

export function useAgents() {
  return useQuery({
    queryKey: ['agents'],
    queryFn: () => api.get<AgentsResponse>('/api/agents'),
    refetchInterval: AGENTS_LIVE,
  });
}

export function useAgentRecommendations(params: { status?: string; agent?: string; page?: number }) {
  return useQuery({
    queryKey: ['agent-recommendations', params],
    queryFn: () =>
      api.get<PageResponse<AgentRecommendation>>(
        `/api/agents/recommendations${qs({ size: 25, ...params })}`,
      ),
    refetchInterval: AGENTS_LIVE,
  });
}

export function useRecommendationDecision(kind: 'approve' | 'dismiss') {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, decidedById }: { id: string; decidedById: string }) =>
      api.post<AgentRecommendation>(`/api/agents/recommendations/${id}/${kind}`, { decidedById }),
    onSettled: () => {
      // An applied recommendation changes risk, fills, outreach — refresh everything.
      qc.invalidateQueries();
    },
  });
}

export function useAgentPause() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ name, paused }: { name: string; paused: boolean }) =>
      api.post<{ agent: string; paused: boolean }>(`/api/agents/${name}/${paused ? 'pause' : 'resume'}`, {}),
    onSettled: () => qc.invalidateQueries({ queryKey: ['agents'] }),
  });
}
