export type Page = 'instituciones' | 'familia' | 'aplicacion' | 'conexion';
export interface Health {
  status: string;
  version: string;
  openarg: { configured: boolean; connected?: boolean; endpoint: string };
  privacy: { minimumCohort: number };
  android?: { apkAvailable: boolean; downloadUrl: string | null };
  assistant?: { configured: boolean; model: string; voiceStreaming: boolean; generalConversation: boolean };
}
export interface Overview {
  territoryId: string;
  minimumCohort: number;
  suppressed: boolean;
  participants: number | null;
  metrics: null | { completedFlows: number; autonomyChecks: number; accompaniedMinutes: number; helpRequests: number };
  flows: { flowId: string; started: number; completed: number }[];
  updatedAt: string;
}
export interface Source { title: string; url: string }
export interface Dataset {
  id: string; title: string; description: string; sourceUrl: string;
  organization?: string; updatedAt?: string; period?: string;
  resources: { id: string; name: string; format: string; url: string }[];
}
export interface PublicContext {
  territoryId?: string; query?: string; status?: string;
  indicators?: { id: string; label: string; value: number | string; unit: string; period: string; sourceUrl: string; sourceTitle: string }[];
  datasets: Dataset[]; sources: Source[]; warnings: string[];
  cached: boolean; fetchedAt: string; rawText?: string;
}
export interface PublicAnswer { answer: string; sources: Source[]; warnings: string[]; fetchedAt: string; cached: boolean }
export interface McpStatus { configured: boolean; connected: boolean; endpoint: string; tools: { name: string; description: string }[]; error?: string | { code: string; message: string } }
export interface FamilyGroup { id: string; name: string }
export interface FamilySummary {
  group: FamilyGroup;
  members: { id: string; name: string; permissions: { progress: boolean; help: boolean }; completedFlows?: number; autonomyChecks?: number; accompaniedSeconds?: number }[];
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
