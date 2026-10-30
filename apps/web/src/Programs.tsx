import { useEffect, useRef, useState } from 'react';
import { FlagIcon, PlusIcon } from '@phosphor-icons/react';
import { api, errorText, localDate } from './api';
import type { Program } from './api';
import { EmptyState, Notice, Spinner } from './ui';

const format = (value: number | null | undefined) => value == null ? '—' : new Intl.NumberFormat('es-AR', { maximumFractionDigits: 1 }).format(value);
export function Programs({ territoryId, territoryName, programs, token, refresh }: { territoryId: string; territoryName: string; programs: Program[]; token: string; refresh: () => void }) {
  const [formOpen, setFormOpen] = useState(false); const [name, setName] = useState('');
  const [startDate, setStartDate] = useState(''); const [endDate, setEndDate] = useState(''); const [goal, setGoal] = useState(60);
  const [error, setError] = useState(''); const [busy, setBusy] = useState(false); const [statusBusy, setStatusBusy] = useState('');
  const mounted = useRef(true);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  async function create() {
    setBusy(true); setError('');
    try { await api('/institution/programs', { token, method: 'POST', body: { name: name.trim(), territoryId, startDate, endDate, goal } }); if (mounted.current) { setFormOpen(false); setName(''); refresh(); } }
    catch (e) { if (mounted.current) setError(errorText(e)); }
    finally { if (mounted.current) setBusy(false); }
  }
  async function changeStatus(program: Program, status: Program['status']) {
    setStatusBusy(program.id); setError('');
    try { await api(`/institution/programs/${program.id}`, { token, method: 'PATCH', body: { status } }); if (mounted.current) refresh(); }
    catch (e) { if (mounted.current) setError(errorText(e)); }
    finally { if (mounted.current) setStatusBusy(''); }
  }
  return <>
    <article className="panel padded"><div className="panel-heading"><div><div className="eyebrow">INTERVENCIONES Y RESULTADOS</div><h2>De los datos a la acción.</h2></div><button className="button primary" disabled={!token} onClick={() => setFormOpen(v => !v)} aria-expanded={formOpen} aria-controls="program-form"><PlusIcon size={18} />Crear programa</button></div><p className="panel-description">Organizá talleres e intervenciones en {territoryName}. Compará la tasa de prácticas autónomas antes y durante el programa.</p>
    {error && <Notice kind="error">{error}</Notice>}
    {formOpen && <form className="program-form" id="program-form" onSubmit={e => { e.preventDefault(); void create(); }}><div className="program-name"><label htmlFor="program-name">Nombre del programa</label><input id="program-name" maxLength={100} minLength={2} required value={name} onChange={e => setName(e.target.value)} placeholder="Por ejemplo, Herramientas para la vida digital" /></div><div><label htmlFor="program-start">Inicio</label><input id="program-start" type="date" required value={startDate} onChange={e => setStartDate(e.target.value)} /></div><div><label htmlFor="program-end">Fin</label><input id="program-end" type="date" required min={startDate} value={endDate} onChange={e => setEndDate(e.target.value)} /></div><div><label htmlFor="program-goal">Meta de autonomía (%)</label><input id="program-goal" type="number" min={0} max={100} required value={goal} onChange={e => setGoal(Number(e.target.value))} /></div><div className="program-name"><button className="button primary" disabled={busy || name.trim().length < 2}>{busy ? <Spinner /> : <FlagIcon size={18} />}{busy ? 'Guardando…' : 'Guardar programa'}</button></div></form>}
    </article>
    {!programs.length ? <article className="panel"><EmptyState icon={<FlagIcon size={28} />} title="Un taller puede empezar acá.">Creá el primer programa del territorio. Las evaluaciones se calculan con los eventos registrados, respetando la cohorte mínima.</EmptyState></article> : programs.map(program => <article key={program.id} className="panel padded program-card"><div className="panel-heading"><div><div className="eyebrow">{territoryName}</div><h2>{program.name}</h2></div><label><span className="sr-only">Estado de {program.name}</span><select aria-label={`Estado de ${program.name}`} value={program.status} disabled={statusBusy === program.id} onChange={e => { void changeStatus(program, e.target.value as Program['status']); }}><option value="planned">Planificado</option><option value="active">Activo</option><option value="completed">Finalizado</option></select></label></div><p className="program-dates">{program.startDate} → {program.endDate} · Meta: {program.goal}% de prácticas autónomas</p><div className="program-metrics"><div><span>Antes del programa</span><strong>{format(program.evaluation.before?.metrics?.autonomyRate)}{program.evaluation.before?.metrics?.autonomyRate != null ? '%' : ''}</strong><small>{program.evaluation.before ? `${localDate(program.evaluation.before.period.from)} — ${localDate(program.evaluation.before.period.to)}` : 'Sin ventana de medición'}</small></div><div><span>Durante el programa</span><strong>{format(program.evaluation.after?.metrics?.autonomyRate)}{program.evaluation.after?.metrics?.autonomyRate != null ? '%' : ''}</strong><small>{program.evaluation.after ? `${localDate(program.evaluation.after.period.from)} — ${localDate(program.evaluation.after.period.to)}` : 'Sin ventana de medición'}</small></div><div><span>Cambio observado</span><strong>{format(program.evaluation.changePoints)}{program.evaluation.changePoints != null ? ' pp' : ''}</strong><small>{program.evaluation.status === 'not_started' ? 'La medición comienza en la fecha de inicio' : program.evaluation.status === 'available' ? 'Diferencia en puntos porcentuales' : 'Se requiere cohorte suficiente en ambas ventanas'}</small></div></div><p className="method-note">{program.evaluation.note ?? 'Las ventanas tienen igual duración. No se atribuye causalidad al programa.'}</p></article>)}
  </>;
}
