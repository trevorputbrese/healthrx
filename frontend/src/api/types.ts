// TypeScript shapes mirroring the backend API contracts. Nullable/derived fields are
// optional because the API omits null properties.

export interface EntityRef {
  id: string;
  name: string;
}
export interface NamedRef {
  id: string;
  displayName: string;
}
export interface PatientRef {
  id: string;
  displayName: string;
  diseaseState: string;
}

export type ReferralStatus =
  | 'ELIGIBILITY_IDENTIFIED'
  | 'BENEFITS_INVESTIGATION'
  | 'PRIOR_AUTH_SUBMITTED'
  | 'PRIOR_AUTH_APPROVED'
  | 'PRIOR_AUTH_DENIED'
  | 'FINANCIAL_ASSISTANCE_REVIEW'
  | 'READY_TO_FILL'
  | 'DELIVERY_SCHEDULED'
  | 'ACTIVE_THERAPY'
  | 'CANCELLED';

export type RiskLevel = 'LOW' | 'MEDIUM' | 'HIGH';
export type Priority = 'LOW' | 'MEDIUM' | 'HIGH' | 'URGENT';

export interface PageResponse<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export interface ReferralSummary {
  id: string;
  referralNumber: string;
  patient: PatientRef;
  clinic: EntityRef;
  medication: EntityRef;
  payer: EntityRef;
  owner: NamedRef;
  currentStatus: ReferralStatus;
  priority: Priority;
  receivedAt: string;
  daysSinceReceived?: number;
  priorAuthorizationAgeDays?: number;
  timeToTherapyDays?: number;
  copayAmount: number;
  financialAssistanceSecuredAmount: number;
  openTaskCount: number;
  refillRiskLevel?: RiskLevel;
}

export interface TaskSummary {
  id: string;
  type: string;
  status: string;
  priority: string;
  title: string;
  dueAt?: string;
}

export type TaskStatus = 'OPEN' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED';

/** Tasks-page row: a task with its patient/referral context and full description. */
export interface TaskItem {
  id: string;
  type: string;
  status: TaskStatus;
  priority: Priority;
  title: string;
  description?: string;
  dueAt?: string;
  completedAt?: string;
  createdAt: string;
  patient: NamedRef;
  referralId?: string;
  referralNumber?: string;
  owner: NamedRef;
}
export interface NoteItem {
  id: string;
  author: NamedRef;
  body: string;
  createdAt: string;
}
export interface StatusHistoryItem {
  id: string;
  fromStatus?: string;
  toStatus: string;
  changedAt: string;
  changedBy?: NamedRef;
  note?: string;
}

export interface ReferralDetail {
  id: string;
  referralNumber: string;
  patient: { id: string; displayName: string; dateOfBirth: string; diseaseState: string };
  clinic: EntityRef;
  medication: { id: string; name: string; route: string };
  payer: { id: string; name: string; payerType: string };
  owner: NamedRef;
  currentStatus: ReferralStatus;
  allowedNextStatuses: ReferralStatus[];
  priority: Priority;
  receivedAt: string;
  milestones: Record<string, string | undefined>;
  financials: { copayAmount: number; financialAssistanceRequired: boolean; financialAssistanceSecuredAmount: number };
  metrics: { daysSinceReceived?: number; priorAuthorizationAgeDays?: number; timeToTherapyDays?: number };
  openTasks: TaskSummary[];
  recentNotes: NoteItem[];
  statusHistory: StatusHistoryItem[];
  pendingAgentRecommendations: number;
}

export interface TherapySummary {
  id: string;
  medication: EntityRef;
  status: string;
  startDate?: string;
  currentRefillDueDate?: string;
  adherencePdcPercent?: number;
  refillRiskLevel?: RiskLevel;
  refillRiskReasons: string[];
}
export interface OutreachItem {
  id: string;
  referralId?: string;
  owner: NamedRef;
  channel: string;
  outcome: string;
  occurredAt: string;
  notes?: string;
}
export interface InterventionItem {
  id: string;
  referralId?: string;
  owner: NamedRef;
  interventionType: string;
  summary: string;
  occurredAt: string;
}
export interface PatientDetail {
  id: string;
  demoMrn: string;
  displayName: string;
  dateOfBirth: string;
  diseaseState: string;
  clinic: EntityRef;
  payer: EntityRef;
  primaryOwner: NamedRef;
  therapies: TherapySummary[];
  openTasks: TaskSummary[];
  recentOutreach: OutreachItem[];
  recentInterventions: InterventionItem[];
}

export interface PatientSummary {
  id: string;
  demoMrn: string;
  displayName: string;
  dateOfBirth: string;
  diseaseState: string;
  clinic: EntityRef;
  payer: EntityRef;
  primaryOwner: NamedRef;
  activeReferralCount: number;
  openTaskCount: number;
  therapyCount: number;
  highestRefillRiskLevel?: RiskLevel;
}

export interface TimelineItem {
  id: string;
  type: string;
  occurredAt: string;
  title: string;
  body?: string;
  actor?: NamedRef;
  metadata?: Record<string, unknown>;
}
export interface TimelineResponse {
  items: TimelineItem[];
}

export interface DashboardTiles {
  activePatientsOnTherapy: number;
  medianTimeToTherapyDays?: number;
  medianPriorAuthorizationTurnaroundDays?: number;
  refillRiskCount: number;
  highRefillRiskCount: number;
  averageAdherencePdcPercent?: number;
  financialAssistanceSecuredAmount: number;
  financialAssistanceSecuredCount: number;
  overdueTaskCount: number;
}
export interface DashboardSummary {
  window: { from: string; to: string };
  tiles: DashboardTiles;
  statusCounts: { status: ReferralStatus; count: number }[];
  openTasksByOwner: { owner: NamedRef; count: number }[];
}
export interface TrendBucket {
  from: string;
  to: string;
  referralsReceived: number;
  activatedTherapies: number;
  medianTimeToTherapyDays?: number;
  medianPriorAuthorizationTurnaroundDays?: number;
  averageAdherencePdcPercent?: number;
  refillRiskCount: number;
}
export interface DashboardTrends {
  bucket: string;
  series: TrendBucket[];
}

export interface Option {
  value: string;
  label: string;
}
export interface StatusOption {
  value: ReferralStatus;
  label: string;
  nextStatuses: ReferralStatus[];
}
export interface Lookups {
  referralStatuses: StatusOption[];
  priorities: Priority[];
  clinics: EntityRef[];
  payers: EntityRef[];
  medications: EntityRef[];
  owners: NamedRef[];
  diseaseStates: string[];
  taskTypes: Option[];
  outreachChannels: Option[];
  outreachOutcomes: Option[];
  interventionTypes: Option[];
}

export interface ApiError {
  code: string;
  message: string;
  details?: Record<string, unknown>;
}

export interface SimStatus {
  enabled: boolean;
  currentInstant: string;
  speedSecondsPerSecond: number;
  ambientEnabled: boolean;
  scenarios: string[];
}

// --- Phase 3: Agents view (phase-3-design.md §8/§9) ---

export type RecommendationStatus =
  | 'PENDING'
  | 'APPLYING'
  | 'APPLIED'
  | 'AUTO_APPLIED'
  | 'DISMISSED'
  | 'SUPERSEDED';

export interface AgentStatus {
  name: string;
  displayName: string;
  paused: boolean;
  reachable: boolean;
  lastActivityAt?: string;
  totalRecommendations: number;
  pendingCount: number;
  appliedCount: number;
  autoAppliedCount: number;
}

export interface AgentsResponse {
  agents: AgentStatus[];
}

export interface TraceStep {
  step: string;
  detail?: string;
  tool?: string;
  input?: string;
  result?: string;
}

export interface AgentRecommendation {
  id: string;
  agentName: string;
  agentDisplayName: string;
  patient: NamedRef;
  referralId?: string;
  therapyId?: string;
  taskId?: string;
  status: RecommendationStatus;
  summary: string;
  recommendation: Record<string, unknown>;
  trace: TraceStep[];
  createdAt: string;
  decidedAt?: string;
  decidedBy?: NamedRef;
}
