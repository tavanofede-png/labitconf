import { useEffect, useRef, useState } from 'react';
import { DeviceMobileIcon, LockKeyIcon, PlusIcon } from '@phosphor-icons/react';
import { api, errorText, localDate } from './api';
import { Notice, Spinner } from './ui';

type Device = { deviceId: string; realtimeApproved: boolean; approvedAt: string | null; createdAt: string };
type Enrollment = { enrollmentCode: string; expiresAt: string };

export function DeviceAccess({ token }: { token: string }) {
  const [storedDevices, setDevices] = useState<{ ownerToken: string; devices: Device[] } | null>(null);
  const [storedEnrollment, setEnrollment] = useState<(Enrollment & { ownerToken: string }) | null>(null);
  const [failure, setFailure] = useState<{ ownerToken: string; message: string } | null>(null);
  const [busy, setBusy] = useState('');
  const currentToken = useRef(token); currentToken.current = token;
  const request = useRef<AbortController | null>(null);
  const epoch = useRef(0);
  const devices = storedDevices?.ownerToken === token ? storedDevices.devices : null;
  const enrollment = storedEnrollment?.ownerToken === token ? storedEnrollment : null;
  const error = failure?.ownerToken === token ? failure.message : '';

  function begin(action: string) {
    request.current?.abort();
    const controller = new AbortController(); request.current = controller;
    const ownerToken = token; const sequence = ++epoch.current;
    setBusy(action); setFailure(null);
    return { controller, ownerToken, current: () => !controller.signal.aborted && currentToken.current === ownerToken && sequence === epoch.current };
  }

  async function loadDevices() {
    if (!token) return;
    const pending = begin('list');
    try {
      const result = await api<{ devices: Device[] }>('/institution/devices', { token, signal: pending.controller.signal });
      if (pending.current()) setDevices({ ...result, ownerToken: pending.ownerToken });
    } catch (e) { if (pending.current()) setFailure({ ownerToken: pending.ownerToken, message: errorText(e) }); }
    finally { if (pending.current()) setBusy(''); }
  }

  useEffect(() => {
    ++epoch.current; request.current?.abort();
    setDevices(null); setEnrollment(null); setFailure(null); setBusy('');
    void loadDevices();
    return () => { ++epoch.current; request.current?.abort(); };
  }, [token]);

  async function invite() {
    if (!token) return;
    const pending = begin('invite'); setEnrollment(null);
    try {
      const result = await api<Enrollment>('/institution/device-invitations', { token, method: 'POST', body: {}, signal: pending.controller.signal });
      if (pending.current()) setEnrollment({ ...result, ownerToken: pending.ownerToken });
    } catch (e) { if (pending.current()) setFailure({ ownerToken: pending.ownerToken, message: errorText(e) }); }
    finally { if (pending.current()) setBusy(''); }
  }

  async function revoke(deviceId: string) {
    if (!token) return;
    const pending = begin(deviceId);
    try {
      await api(`/institution/devices/${encodeURIComponent(deviceId)}/revoke`, { token, method: 'POST', body: {}, signal: pending.controller.signal });
      if (!pending.current()) return;
      const result = await api<{ devices: Device[] }>('/institution/devices', { token, signal: pending.controller.signal });
      if (pending.current()) setDevices({ ...result, ownerToken: pending.ownerToken });
    } catch (e) { if (pending.current()) setFailure({ ownerToken: pending.ownerToken, message: errorText(e) }); }
    finally { if (pending.current()) setBusy(''); }
  }

  return <section className="panel connection-panel device-access" aria-labelledby="device-access-title">
    <div className="panel-heading"><span className="large-symbol"><DeviceMobileIcon size={29} /></span><span className="badge"><LockKeyIcon size={12} />Aprobación del equipo</span></div>
    <h2 id="device-access-title">Conectar un teléfono</h2>
    <p>Generá un código y compartilo con la persona que va a usar NIETO. Lo ingresa en Ajustes del teléfono para habilitar la asistencia. Cada código sirve una vez y vence a los 15 minutos.</p>
    <button className="button primary" disabled={!token || Boolean(busy)} onClick={() => { void invite(); }}>{busy === 'invite' ? <Spinner /> : <PlusIcon size={18} />}Generar código para teléfono</button>
    {!token && <p className="fine-print">Conectá el acceso institucional para autorizar teléfonos.</p>}
    {enrollment && <div className="invitation-code-box"><span>Código de conexión de un solo uso</span><div><code>{enrollment.enrollmentCode}</code></div><small>Vence el {localDate(enrollment.expiresAt, true)}</small></div>}
    {error && <Notice kind="error">{error}</Notice>}
    {token && <><div className="panel-heading device-list-heading"><h3>Teléfonos registrados</h3><button className="text-button" disabled={Boolean(busy)} onClick={() => { void loadDevices(); }}>{busy === 'list' ? 'Actualizando teléfonos…' : 'Actualizar teléfonos'}</button></div>
      {devices?.length === 0 && <p className="fine-print">Todavía no hay teléfonos registrados en este servidor.</p>}
      {devices?.map(device => <article className="device-access-row" key={device.deviceId}><div><strong>Teléfono {device.deviceId.slice(0, 8)}</strong><span>{device.realtimeApproved ? 'Asistencia habilitada' : 'Asistencia sin autorización'}</span><details><summary>Ver identificador completo</summary><code>{device.deviceId}</code><p>Cotejalo con el identificador que aparece en Ajustes del teléfono.</p></details></div>{device.realtimeApproved && <button className="button secondary small" disabled={Boolean(busy)} aria-label={`Revocar asistencia del teléfono ${device.deviceId}`} onClick={() => { void revoke(device.deviceId); }}>{busy === device.deviceId ? <Spinner /> : null}Revocar asistencia</button>}</article>)}
      <p className="fine-print">Revocar detiene el acceso a la asistencia de IA. Los vínculos y permisos familiares se conservan; para volver a autorizar el teléfono, generá otro código.</p></>}
  </section>;
}
