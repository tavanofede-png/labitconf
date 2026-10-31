export type Page = 'bienvenida' | 'instituciones' | 'familia' | 'aplicacion' | 'conexion';
export interface Health {
  status: string;
  version: string;
  openarg: { configured: boolean; connected?: boolean; endpoint: string };
  privacy: { minimumCohort: number };
  android?: { apkAvailable: boolean; downloadUrl: string | null; versionName?: string | null; versionCode?: number | null; sha256?: string | null };
  assistant?: { configured: boolean; model: string; voiceStreaming: boolean; generalConversation: boolean };
}
export interface Overview {
  territoryId: string;
  minimumCohort: number;
  suppressed: boolean;
  participants: number | null;
  metrics: null | { reportedFlows: number | null; practiceReports: number | null; completedFlows: number | null; autonomyChecks: number | null; accompaniedMinutes: number | null; helpRequests: number | null };
  flows: { flowId: string; started: number | null; completed: number | null; reported: number | null; startedCohort?: MetricCohort; completedCohort?: MetricCohort; reportedCohort?: MetricCohort }[];
  period?: { from: string; to: string; days: number; basis: string; timezone: string };
  activity?: MetricCohort & { activePeople: number | null; startedFlows: number | null; startedCohort: MetricCohort };
  difficulties?: MetricCohort & { events: number | null; steps: (MetricCohort & { stepId: string; events: number | null })[] };
  familyResponse?: MetricCohort & { resolvedRequests: number | null; meanResponseMinutes: number | null };
  trend?: (Pick<MetricCohort, 'suppressed' | 'suppressionReason'> & { from: string; to: string; activePeople: number | null })[];
  cohorts?: Record<string, MetricCohort>;
  evidence?: { source: string; consent: string; methodVersion: string; interpretation: string; comparison: string };
  gaps?: string[];
  updatedAt: string;
}
export interface FlowMetric {
  flowId: string; name: string; started: number; completed: number; cancelled: number;
  autonomyChecks: number; averageMinutes: number; repeatedSteps: number; lostEvents: number;
  steps: { stepId: string; completed: number }[];
}
export interface Analytics {
  territoryId: string; period: { from: string; to: string; days?: number }; minimumCohort: number;
  suppressed: boolean; participants: number | null; updatedAt?: string;
  metrics: null | { sessions: number; completedFlows: number; autonomyChecks: number; accompaniedMinutes: number;
    completionRate: number | null; autonomyRate: number | null; averageMinutes: number | null };
  flows: FlowMetric[];
  trend: { date: string; sessions: number; completed: number; autonomyChecks: number }[];
  usage: null | { screenMinutes: number; accompaniedMinutes: number; unaccompaniedMinutes: number;
    categories: { category: string; minutes: number }[] };
  safety: null | { total: number; resolved: number; pending: number; categories: { kind: string; count: number }[] };
  help: null | { total: number; resolved: number };
  definitions?: Record<string, string>;
}
export interface TerritorySummary {
  territoryId: string; name: string; suppressed: boolean; participants: number | null;
  completedFlows: number | null; autonomyRate: number | null;
}
export interface Program {
  id: string; name: string; territoryId: string; startDate: string; endDate: string;
  goal: number; status: 'planned' | 'active' | 'completed'; createdAt: string;
  evaluation: { status: string; before: Analytics | null; after: Analytics | null; changePoints: number | null; note?: string };
}
export interface Source { title: string; url: string }
export interface Dataset {
  id: string; title: string; description: string; sourceUrl: string;
  organization?: string; updatedAt?: string; period?: string;
  resources: { id: string; name: string; format: string; url: string }[];
}
export interface PublicContext {
  territoryId?: string; query?: string; status?: string;
  indicators?: { territoryId?: string; id: string; label: string; value: number | string; unit: string; period: string; sourceUrl: string; sourceTitle: string; freshness?: { historical: boolean; dataPeriod: string; sourceReadAt: string | null; retrievedAt: string }; method?: { note?: string } }[];
  datasets: Dataset[]; sources: Source[]; warnings: string[];
  cached: boolean; fetchedAt: string; rawText?: string;
  gaps?: string[];
  opportunities?: { id: string; title: string; basis: string[]; limitation: string }[];
}
export interface PublicAnswer { answer: string; sources: Source[]; warnings: string[]; fetchedAt: string; cached: boolean }
export interface McpStatus { configured: boolean; connected: boolean; endpoint: string; tools: { name: string; description: string }[]; error?: string | { code: string; message: string }; transportError?: { code: string; message: string } | null; queryError?: { code: string; message: string } | null }
export interface FamilyGroup { id: string; name: string }
export interface FamilySummary {
  group: FamilyGroup;
  members: { id: string; name: string; permissions: { progress: boolean; help: boolean }; reportedFlows?: number; practiceReports?: number; completedFlows?: number; autonomyChecks?: number; accompaniedSeconds?: number }[];
  alerts: { id: string; type: string; message: string; requesterName?: string; memberId?: string; createdAt: string; resolvedAt?: string | null }[];
}
export interface Invitation { invitationCode: string; expiresAt: string }
export interface NewFamily extends Invitation { group: FamilyGroup; caregiverToken: string }

export class ApiError extends Error {
  code: string;
  status: number;
  constructor(message: string, code: string, status: number) { super(message); this.code = code; this.status = status; }
}
export async function api<T>(path: string, options: { token?: string; method?: string; body?: unknown; signal?: AbortSignal } = {}): Promise<T> {
  const signal = options.signal ? AbortSignal.any([options.signal, AbortSignal.timeout(65_000)]) : AbortSignal.timeout(65_000);
  const response = await fetch(`/api${path}`, {
    method: options.method ?? 'GET', signal,
    headers: { ...(options.body !== undefined ? { 'Content-Type': 'application/json' } : {}), ...(options.token ? { Authorization: `Bearer ${options.token}` } : {}) },
    ...(options.body !== undefined ? { body: JSON.stringify(options.body) } : {}),
  });
  const result = await response.json().catch(() => null);
  if (!response.ok) throw new ApiError(result?.error?.message ?? `No se pudo completar la solicitud (${response.status}).`, result?.error?.code ?? 'request_failed', response.status);
  return result as T;
}
export function errorText(error: unknown): string {
  if (error instanceof Error && error.name === 'TimeoutError') return 'La consulta tardó demasiado. Podés volver a intentarlo.';
  if (error instanceof TypeError) return 'No pudimos conectar con la API. Revisá que el servidor esté en ejecución.';
  return error instanceof Error ? error.message : 'No se pudo completar la solicitud.';
}
export function safeUrl(url?: string): string | undefined {
  if (!url) return undefined;
  try { const u = new URL(url); return ['http:', 'https:'].includes(u.protocol) ? u.href : undefined; } catch { return undefined; }
}
export function readSession(key: string): string { try { return sessionStorage.getItem(`nieto.${key}`) ?? ''; } catch { return ''; } }
export function writeSession(key: string, value: string) { try { if (value) sessionStorage.setItem(`nieto.${key}`, value); else sessionStorage.removeItem(`nieto.${key}`); } catch { /* Session remains usable in memory when storage is unavailable. */ } }
export function localDate(date: string | null | undefined, time = false) {
  if (!date) return 'Sin fecha informada';
  const d = new Date(date);
  return Number.isNaN(d.getTime()) ? 'Sin fecha informada' : new Intl.DateTimeFormat('es-AR', { dateStyle: 'short', ...(time ? { timeStyle: 'short' as const } : {}) }).format(d);
}

export interface MetricCohort { suppressed: boolean; suppressionReason: string | null; contributors: number | null; minimumCohort: number }
