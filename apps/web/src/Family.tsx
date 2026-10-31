import { useEffect, useRef, useState } from 'react';
import { ArrowRightIcon, BellRingingIcon, CheckCircleIcon, CheckIcon, CopyIcon, EyeIcon, EyeSlashIcon, HeartIcon, LinkSimpleIcon, LockKeyIcon, PlusIcon, ShieldCheckIcon, SignOutIcon, UserCircleIcon, UsersThreeIcon } from '@phosphor-icons/react';
import { api, errorText, localDate } from './api';
import type { FamilySummary, Invitation, NewFamily } from './api';
import { EmptyState, Notice, Spinner } from './ui';

export function Family({ token, setToken }: { token: string; setToken: (value: string) => void }) {
  const [name, setName] = useState('');
  const [restoreToken, setRestoreToken] = useState('');
  const [summary, setSummary] = useState<FamilySummary | null>(null);
  const [storedInvitation, setInvitation] = useState<(Invitation & { ownerToken: string }) | null>(null);
  const invitation = storedInvitation?.ownerToken === token ? storedInvitation : null;
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(false);
  const [copied, setCopied] = useState('');
  const [showToken, setShowToken] = useState(false);
  const [refresh, setRefresh] = useState(0);
  const [resolving, setResolving] = useState('');
  const currentToken = useRef(token); currentToken.current = token;
  const mutation = useRef<AbortController | null>(null);
  useEffect(() => { setBusy(false); setResolving(''); return () => mutation.current?.abort(); }, [token]);

  useEffect(() => {
    setSummary(null); setError('');
    if (!token) return;
    const controller = new AbortController(); let inFlight = false;
    const load = async (initial = false) => {
      if (inFlight || (document.visibilityState !== 'visible' && !initial)) return;
      inFlight = true; if (initial) setLoading(true);
      try { const data = await api<FamilySummary>('/family/summary', { token, signal: controller.signal }); if (!controller.signal.aborted) { setSummary(data); setError(''); } }
      catch (e) { if (!controller.signal.aborted) setError(errorText(e)); }
      finally { inFlight = false; if (!controller.signal.aborted) setLoading(false); }
    };
    void load(true);
    const timer = window.setInterval(() => { void load(); }, 15_000);
    const onVisibility = () => { if (document.visibilityState === 'visible') void load(); };
    document.addEventListener('visibilitychange', onVisibility);
    return () => { controller.abort(); window.clearInterval(timer); document.removeEventListener('visibilitychange', onVisibility); };
  }, [token, refresh]);

  async function createGroup() {
    mutation.current?.abort(); const controller = new AbortController(); mutation.current = controller; const ownerToken = token;
    setBusy(true); setError('');
    try { const result = await api<NewFamily>('/family/groups', { method: 'POST', body: { name: name.trim() }, signal: controller.signal }); if (!controller.signal.aborted && currentToken.current === ownerToken) { setInvitation({ invitationCode: result.invitationCode, expiresAt: result.expiresAt, ownerToken: result.caregiverToken }); setToken(result.caregiverToken); } }
    catch (e) { if (!controller.signal.aborted && currentToken.current === ownerToken) setError(errorText(e)); }
    finally { if (!controller.signal.aborted && currentToken.current === ownerToken) setBusy(false); }
  }

  async function newInvitation() {
    mutation.current?.abort(); const controller = new AbortController(); mutation.current = controller; const ownerToken = token;
    setBusy(true); setResolving(''); setError('');
    try { const result = await api<Invitation>('/family/invitations', { token, method: 'POST', body: {}, signal: controller.signal }); if (!controller.signal.aborted && currentToken.current === ownerToken) setInvitation({ ...result, ownerToken }); }
    catch (e) { if (!controller.signal.aborted && currentToken.current === ownerToken) setError(errorText(e)); }
    finally { if (!controller.signal.aborted && currentToken.current === ownerToken) setBusy(false); }
  }

  async function resolveAlert(id: string) {
    mutation.current?.abort(); const controller = new AbortController(); mutation.current = controller; const ownerToken = token;
    setBusy(false); setResolving(id); setError('');
    try { await api(`/family/alerts/${encodeURIComponent(id)}/resolve`, { token, method: 'POST', body: {}, signal: controller.signal }); if (!controller.signal.aborted && currentToken.current === ownerToken) setRefresh(r => r + 1); }
    catch (e) { if (!controller.signal.aborted && currentToken.current === ownerToken) setError(errorText(e)); }
    finally { if (!controller.signal.aborted && currentToken.current === ownerToken) setResolving(''); }
  }

  async function copy(value: string, label: string) {
    try { await navigator.clipboard.writeText(value); setCopied(label); setTimeout(() => setCopied(''), 2400); }
    catch { setError('No pudimos copiar. Seleccioná el texto visible y copialo manualmente.'); }
  }

  const activeAlerts = summary?.alerts.filter(a => !a.resolvedAt) ?? [];

  return <>
    <header className="page-heading"><div><div className="eyebrow"><span className="tiny-line" />TINO FAMILIA</div><h1>Una mano cerca.<br /><span>Su autonomía, primero.</span></h1><p>Un espacio para acompañar, con los permisos que cada persona elige compartir.</p></div>{token && <button className="button secondary" onClick={() => { setToken(''); setInvitation(null); setShowToken(false); }}><SignOutIcon size={18} />Cerrar acceso</button>}</header>
    <Notice>También podés acompañar desde el acceso familiar de la app Android. Permite crear o recuperar el grupo y recibir notificaciones fuera del navegador mediante comprobaciones periódicas desde 15 minutos, sujetas a batería y red. Al abrir la app se actualiza de inmediato.</Notice>
    {error && <Notice kind="error">{error}{token && <button className="text-button" onClick={() => setRefresh(r => r + 1)}>Reintentar</button>}</Notice>}
    {!token ? <div className="family-onboarding"><article className="panel create-family"><span className="large-symbol"><UsersThreeIcon size={35} weight="duotone" /></span><div className="eyebrow">EMPEZAR A ACOMPAÑAR</div><h2>Creá tu grupo familiar.</h2><p>Vas a recibir un código de invitación. La persona asistida lo ingresa en TINO y decide qué compartir.</p><form onSubmit={e => { e.preventDefault(); void createGroup(); }}><label htmlFor="family-name">Nombre del grupo</label><input id="family-name" placeholder="Por ejemplo, Nuestra familia" maxLength={60} minLength={2} required value={name} onChange={e => setName(e.target.value)} autoComplete="off" /><button className="button primary full" disabled={busy || name.trim().length < 2}>{busy ? <Spinner /> : <PlusIcon size={18} />}Crear grupo y obtener código<ArrowRightIcon size={18} /></button></form><div className="fine-print icon-text"><LockKeyIcon size={16} />Grupo privado. Sin conversaciones ni capturas compartidas.</div></article><article className="family-principles"><div className="eyebrow">ASÍ FUNCIONA</div><h2>Acompañar también<br />es respetar el espacio.</h2><ol className="numbered-list"><li><span>01</span><div><strong>Invitá, sin imponer.</strong><p>Compartí el código con la persona que usa TINO.</p></div></li><li><span>02</span><div><strong>La decisión es suya.</strong><p>Puede habilitar progreso y pedidos de ayuda por separado.</p></div></li><li><span>03</span><div><strong>Estén cerca cuando lo pida.</strong><p>Una solicitud aparece acá. La persona puede revocar el acceso cuando quiera.</p></div></li></ol><details className="restore-access"><summary>Ya tengo un grupo</summary><form onSubmit={e => { e.preventDefault(); setToken(restoreToken.trim()); setRestoreToken(''); }}><label htmlFor="restore-token">Credencial de acceso familiar</label><input id="restore-token" type="password" value={restoreToken} onChange={e => setRestoreToken(e.target.value)} required autoComplete="off" spellCheck={false} /><button className="button secondary" disabled={!restoreToken.trim()}>Recuperar acceso <ArrowRightIcon /></button></form><p className="fine-print">Usá la credencial que guardaste al crear el grupo.</p></details></article></div> : <>
      <div className="family-group-bar"><span className="family-avatar"><HeartIcon size={24} weight="fill" /></span><div><div className="eyebrow">TU GRUPO</div><h2>{summary?.group.name ?? (loading ? 'Cargando grupo…' : 'Acceso familiar')}</h2></div><span className="badge green"><LockKeyIcon size={12} />Privado</span></div>
      <section className="family-grid"><article className="panel members-panel"><div className="panel-heading"><div><div className="eyebrow">VÍNCULOS</div><h2>Personas que acompañás</h2></div><UsersThreeIcon size={24} /></div>
        {loading ? <div className="loading-row"><Spinner />Cargando permisos y vínculos…</div> : !summary?.members.length ? <EmptyState icon={<LinkSimpleIcon size={29} />} title="Todo empieza con una invitación">Todavía no hay una persona vinculada. Cuando acepte el código desde Android, aparecerá aquí con los permisos que eligió.</EmptyState> : summary.members.map(member => <article className="member-row" key={member.id}><div className="member-name"><UserCircleIcon size={39} weight="duotone" /><div><h3>{member.name}</h3><span>Permisos elegidos desde su teléfono</span></div></div><div className="permission-chips"><span className={`badge ${member.permissions.progress ? 'green' : ''}`}>{member.permissions.progress ? <CheckIcon /> : <LockKeyIcon />}Progreso {member.permissions.progress ? 'compartido' : 'privado'}</span><span className={`badge ${member.permissions.help ? 'green' : ''}`}>{member.permissions.help ? <CheckIcon /> : <LockKeyIcon />}Ayuda {member.permissions.help ? 'habilitada' : 'privada'}</span></div>{member.permissions.progress && <div className="member-stats"><div><strong>{member.reportedFlows ?? 0}</strong><span>Envíos reportados</span></div><div><strong>{member.practiceReports ?? 0}</strong><span>Prácticas reportadas</span></div><div><strong>{Math.round((member.accompaniedSeconds ?? 0) / 60)}</strong><span>Minutos con TINO</span></div></div>}</article>)}
      </article><article className="panel invitation-panel"><div className="eyebrow">CONEXIÓN FAMILIAR</div><h2>Un código.<br />Una decisión propia.</h2><p>En el teléfono, abrí <strong>TINO → Familia</strong>. Ingresá el código y elegí los permisos.</p>{invitation ? <div className="invitation-code-box"><span>Código de un solo uso</span><div><code>{invitation.invitationCode}</code><button className="icon-button" aria-label="Copiar código de invitación" onClick={() => { void copy(invitation.invitationCode, 'code'); }}>{copied === 'code' ? <CheckIcon /> : <CopyIcon />}</button></div><small>Vence el {localDate(invitation.expiresAt, true)}</small></div> : <div className="invitation-placeholder"><LinkSimpleIcon size={29} /><span>Generá una invitación para vincular un teléfono.</span></div>}<button className="button primary full" disabled={busy || !summary} onClick={() => { void newInvitation(); }}>{busy ? <Spinner /> : <PlusIcon size={18} />}{invitation ? 'Generar otro código' : 'Generar invitación'}</button><p className="fine-print">La vinculación solo se completa cuando la persona acepta en su dispositivo.</p></article></section>
      <article className="panel alerts-panel"><div className="panel-heading"><div><div className="eyebrow">ACOMPAÑAMIENTO</div><h2>Pedidos de ayuda <span className="count-badge">{activeAlerts.length}</span></h2></div><span className="small-status"><span className="status-dot" />Se actualiza cada 15 segundos</span></div>{!summary?.alerts.length ? <EmptyState icon={<BellRingingIcon size={29} />} title="Por ahora, todo tranquilo.">Cuando alguien de tu grupo pida acompañamiento desde TINO, verás su solicitud acá.</EmptyState> : <div className="alert-list">{summary.alerts.map(alert => <article className={`alert-row ${alert.resolvedAt ? 'resolved' : ''}`} key={alert.id}><span className="alert-symbol">{alert.resolvedAt ? <CheckCircleIcon size={23} /> : <BellRingingIcon size={23} weight="duotone" />}</span><div><strong>{alert.requesterName ? `${alert.requesterName} · ` : ""}{alert.message}</strong><span>{localDate(alert.createdAt, true)}{alert.resolvedAt ? ` · Atendido el ${localDate(alert.resolvedAt, true)}` : ' · Pendiente de acompañamiento'}</span></div>{!alert.resolvedAt && <button className="button secondary small" disabled={resolving === alert.id} onClick={() => { void resolveAlert(alert.id); }}>{resolving === alert.id ? <Spinner /> : <CheckIcon />}Marcar atendido</button>}</article>)}</div>}</article>
      <details className="access-backup"><summary><LockKeyIcon size={17} />Guardar mi acceso familiar</summary><p>Este acceso se conserva en esta pestaña hasta cerrarla. Guardá la credencial en un lugar privado para volver a entrar. Quien la tenga podrá ver los permisos, resúmenes y alertas de este grupo.</p><div className="secret-row"><input aria-label="Credencial familiar" value={token} type={showToken ? 'text' : 'password'} readOnly autoComplete="off" spellCheck={false} /><button className="icon-button" aria-label={showToken ? 'Ocultar credencial' : 'Mostrar credencial'} onClick={() => setShowToken(v => !v)}>{showToken ? <EyeSlashIcon /> : <EyeIcon />}</button><button className="button secondary" onClick={() => { void copy(token, 'token'); }}>{copied === 'token' ? <CheckIcon /> : <CopyIcon />}Copiar credencial</button></div></details>
    </>}
    <div className="page-footnote"><ShieldCheckIcon size={17} /><p>El control queda en manos de la persona asistida. La familia no puede ver la pantalla, los mensajes ni las conversaciones.</p></div>
  </>;
}
