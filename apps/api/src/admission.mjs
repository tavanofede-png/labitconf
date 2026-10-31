import { createHash, randomBytes } from 'node:crypto';
import { RealtimeError } from './realtime.mjs';

const hash = value => createHash('sha256').update(value).digest('hex');
const validLimit = (value, fallback) => Number.isSafeInteger(Number(value)) && Number(value) > 0 ? Number(value) : fallback;
export const RESERVATIONS = Object.freeze({ ws: { turns: 20, seconds: 600, contexts: 60 }, http: { turns: 1, seconds: 65, contexts: 0 } });
const defaults = { device: { turns: 60, seconds: 1800, contexts: 180 }, origin: { turns: 120, seconds: 3600, contexts: 360 }, global: { turns: 1200, seconds: 36000, contexts: 3600 } };
const zero = () => ({ turns: 0, seconds: 0, contexts: 0 });
export const directOrigin = value => String(value ?? 'unknown').replace(/^::ffff:/i, '').toLowerCase();

/** One-process, atomic admission and conservative UTC-day resource reservations. No content is recorded. */
export class RealtimeAdmission {
  constructor(store, { now = Date.now, limits = {} } = {}) {
    this.store = store; this.now = now;
    this.limits = Object.fromEntries(Object.entries(defaults).map(([scope, values]) => [scope, Object.fromEntries(Object.entries(values).map(([metric, fallback]) => [metric,
      validLimit(limits[scope]?.[metric] ?? process.env[`REALTIME_${scope.toUpperCase()}_DAILY_${metric.toUpperCase()}`], fallback)]))]));
  }
  async invitation() {
    const code = randomBytes(6).toString('hex').toUpperCase(); const current = this.now();
    const expiresAt = new Date(current + 15 * 60_000).toISOString();
    await this.store.update(state => {
      state.deviceInvitations = (state.deviceInvitations ?? []).filter(item => !item.usedAt && Date.parse(item.expiresAt) > current);
      if (state.deviceInvitations.length >= 100) throw new RealtimeError('too_many_invitations', 'Ya hay muchos códigos vigentes. Esperá a que venzan.', 429);
      state.deviceInvitations.push({ codeHash: hash(code), expiresAt });
    });
    return { enrollmentCode: code, expiresAt };
  }
  activate(state, user, suppliedCode) {
    const code = typeof suppliedCode === 'string' ? suppliedCode.trim().toUpperCase() : '';
    const current = this.now();
    const invitation = /^[A-F0-9]{12}$/.test(code) && state.deviceInvitations?.find(item => item.codeHash === hash(code) && !item.usedAt && Date.parse(item.expiresAt) > current);
    if (!invitation) throw new RealtimeError('invalid_enrollment_code', 'Ingresá un código de conexión vigente del equipo. Se usa una sola vez.', 403);
    invitation.usedAt = new Date(current).toISOString();
    user.realtimeApproved = true; user.realtimeApprovedAt = invitation.usedAt; delete user.realtimeRevokedAt;
  }
  async reserve(deviceId, origin, kind) {
    const amount = RESERVATIONS[kind];
    if (!amount) throw new Error('Unknown reservation kind');
    return this.store.update(state => {
      const user = state.users.find(item => item.id === deviceId);
      if (!user || user.realtimeApproved !== true) throw new RealtimeError('device_activation_required', 'Conectá este teléfono con un código vigente del equipo para habilitar la asistencia.', 403);
      const day = new Date(this.now()).toISOString().slice(0, 10);
      if (state.realtimeUsage?.day !== day) state.realtimeUsage = { day, devices: {}, origins: {}, global: zero() };
      const usage = state.realtimeUsage;
      const originHash = hash(directOrigin(origin));
      const records = { device: usage.devices[deviceId] ?? zero(), origin: usage.origins[originHash] ?? zero(), global: usage.global };
      if (Object.values(records).some(record => !record || Object.keys(amount).some(metric => !Number.isSafeInteger(record[metric]) || record[metric] < 0))) throw new RealtimeError('realtime_budget_unavailable', 'No pude validar el presupuesto de asistencia. Consultá al equipo.', 503);
      for (const [scope, record] of Object.entries(records)) for (const [metric, quantity] of Object.entries(amount)) {
        if (record[metric] + quantity > this.limits[scope][metric]) throw new RealtimeError('realtime_daily_budget', 'Se alcanzó el presupuesto diario de asistencia. Volvé a intentar mañana o consultá al equipo.', 429);
      }
      for (const record of Object.values(records)) for (const [metric, quantity] of Object.entries(amount)) record[metric] += quantity;
      usage.devices[deviceId] = records.device; usage.origins[originHash] = records.origin;
      return { day, reserved: { ...amount } };
    });
  }
  status() { return { admission: 'operator_single_use_code', budgetBasis: 'reserved_maximum_per_connection_utc_day', reservations: RESERVATIONS, limits: this.limits }; }
  usage(state) {
    const day = new Date(this.now()).toISOString().slice(0, 10);
    const saved = state.realtimeUsage?.day === day ? state.realtimeUsage : { devices: {}, global: zero() };
    return { day, globalReserved: saved.global, device: deviceId => {
      const reserved = saved.devices[deviceId] ?? zero();
      return { reserved, remaining: Object.fromEntries(Object.entries(this.limits.device).map(([key, limit]) => [key, Math.max(0, limit - reserved[key])])) };
    } };
  }
}
