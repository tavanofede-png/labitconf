import { useEffect, useRef, useState } from 'react';
import type { KeyboardEvent, ReactNode } from 'react';
import { ArrowClockwiseIcon, ArrowRightIcon, BookOpenIcon, ChartBarIcon, ClockIcon, DownloadSimpleIcon, FlagIcon, GlobeHemisphereWestIcon, LockKeyIcon, MapPinIcon, ShieldCheckIcon, UsersThreeIcon } from '@phosphor-icons/react';
import { api, errorText, localDate } from './api';
import type { Analytics, Health, Program, PublicContext, TerritorySummary } from './api';
import { territories } from './territories';
import { TerritoryMap } from './TerritoryMap';
import { PublicData } from './PublicData';
import { Programs } from './Programs';
import { EmptyState, Notice, Spinner } from './ui';

const sections = [
  { id: 'resumen', label: 'Resumen', icon: ChartBarIcon }, { id: 'territorio', label: 'Territorio', icon: MapPinIcon },
  { id: 'flujos', label: 'Flujos', icon: BookOpenIcon }, { id: 'uso', label: 'Uso', icon: ClockIcon },
  { id: 'seguridad', label: 'Seguridad', icon: ShieldCheckIcon }, { id: 'programas', label: 'Programas', icon: FlagIcon },
] as const;
type Section = typeof sections[number]['id'];
export const format = (value: number | null | undefined, unit = '') => value == null ? '—' : `${new Intl.NumberFormat('es-AR', { maximumFractionDigits: 1 }).format(value)}${unit}`;
const categories: Record<string, string> = { communication: 'Comunicación', services: 'Servicios y trámites', navigation: 'Navegación y mapas', learning: 'Aprendizaje', other: 'Otras categorías', suspicious_link: 'Enlaces sospechosos', permission_request: 'Permisos que requieren atención', sensitive_action: 'Acciones sensibles' };

export function Institution({ token, health, connect }: { token: string; health: Health | null; connect: () => void }) {
  const [territoryId, setTerritoryId] = useState('AR-C'); const [days, setDays] = useState(30);
  const [section, setSection] = useState<Section>('resumen');
  const [analyticsResponse, setAnalytics] = useState<Analytics | null>(null);
  const [territorial, setTerritorial] = useState<TerritorySummary[]>([]); const [programs, setPrograms] = useState<Program[]>([]);
  const [context, setContext] = useState<PublicContext | null>(null);
  const [loading, setLoading] = useState(false); const [error, setError] = useState(''); const [tick, setTick] = useState(0);
  const tabs = useRef<HTMLDivElement>(null);
  const territory = territories.find(t => t.id === territoryId) ?? territories[0];
  const analytics = analyticsResponse?.territoryId === territoryId && analyticsResponse.period.days === days ? analyticsResponse : null;
  const minimum = analytics?.minimumCohort ?? health?.privacy.minimumCohort ?? 5;
  useEffect(() => { setContext(null); }, [territoryId, token]);
  useEffect(() => {
    setAnalytics(null); setTerritorial([]); setPrograms([]); setError(''); setLoading(false);
    if (!token) return;
    const controller = new AbortController(); let inFlight = false;
    async function load(initial = false) {
      if (inFlight) return; inFlight = true; if (initial) setLoading(true);
      try {
        const options = { token, signal: controller.signal };
        const [data, map, interventions] = await Promise.all([
          api<Analytics>(`/institution/analytics?territoryId=${territoryId}&days=${days}`, options),
          api<{ territories: TerritorySummary[] }>(`/institution/territories?days=${days}`, options),
          api<{ programs: Program[] }>(`/institution/programs?territoryId=${territoryId}`, options),
        ]);
        if (!controller.signal.aborted) { setAnalytics(data); setTerritorial(map.territories); setPrograms(interventions.programs); setError(''); }
      } catch (e) { if (!controller.signal.aborted) setError(errorText(e)); }
      finally { inFlight = false; if (!controller.signal.aborted) setLoading(false); }
    }
    void load(true);
    const timer = window.setInterval(() => { if (document.visibilityState === 'visible') void load(); }, 30_000);
    return () => { controller.abort(); clearInterval(timer); };
  }, [token, territoryId, days, tick]);
  function exportSummary() {
    const report = { title: 'TINO · Inteligencia territorial', territory: territory.name, exportedAt: new Date().toISOString(), analytics, territories: territorial, programs, publicContext: context, methodology: 'Solo agregados publicables. Una celda omitida no representa cero. Contexto público y uso conservan períodos independientes.' };
    const url = URL.createObjectURL(new Blob([JSON.stringify(report, null, 2)], { type: 'application/json' }));
    const link = document.createElement('a'); link.href = url; link.download = `tino-${territoryId}-${days}dias.json`; link.click(); window.setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  function tabKey(event: KeyboardEvent, index: number) {
    const next = event.key === 'ArrowRight' ? (index + 1) % sections.length : event.key === 'ArrowLeft' ? (index + sections.length - 1) % sections.length : event.key === 'Home' ? 0 : event.key === 'End' ? sections.length - 1 : -1;
    if (next < 0) return; event.preventDefault(); setSection(sections[next].id); tabs.current?.querySelectorAll<HTMLButtonElement>('button')[next].focus();
  }
  const metrics = analytics?.metrics;
  const publicData = <PublicData key={territoryId} token={token} health={health} territory={territory} analytics={analytics} context={context?.territoryId === territoryId ? context : null} setContext={setContext} />;
  const map = <article className="panel summary-map"><PanelTitle eyebrow="CONTEXTO TERRITORIAL" title={territory.name} /><TerritoryMap territory={territory} indicator={context?.territoryId === territoryId ? context.indicators?.find(i => i.id.includes('total')) : undefined} /><div className="map-footer"><GlobeHemisphereWestIcon size={17} />Cartografía de referencia. Los marcadores no representan personas.</div></article>;
  return <>
    <header className="page-heading institution-heading"><div><div className="eyebrow"><span className="tiny-line" />TINO INSTITUCIONES</div><h1>Datos para cerrar<br /><span>la brecha digital.</span></h1><p>Uso agregado de TINO + contexto público de OpenARG. Entendé las barreras y acompañá con mejores decisiones.</p></div><button className="button secondary export-button" onClick={exportSummary} disabled={!analytics}><DownloadSimpleIcon size={18} />Exportar informe</button></header>
    <div className="institution-toolbar"><div className="live-label"><span className="status-dot" />Inteligencia territorial</div><div className="filters"><label className="territory-select"><MapPinIcon size={18} /><span className="sr-only">Territorio</span><select aria-label="Territorio" value={territoryId} onChange={e => setTerritoryId(e.target.value)}>{territories.map(t => <option key={t.id} value={t.id}>{t.name}</option>)}</select></label><label className="territory-select"><ClockIcon size={18} /><span className="sr-only">Período</span><select aria-label="Período" value={days} onChange={e => setDays(Number(e.target.value))}><option value={7}>Últimos 7 días</option><option value={30}>Últimos 30 días</option><option value={90}>Últimos 90 días</option></select></label><button className="icon-button" aria-label="Actualizar métricas" disabled={loading || !token} onClick={() => setTick(n => n + 1)}><ArrowClockwiseIcon /></button></div></div>
    <div className="institution-tabs" role="tablist" aria-label="Vistas institucionales" ref={tabs}>{sections.map(({ id, label, icon: Icon }, index) => <button type="button" key={id} id={`institution-tab-${id}`} role="tab" aria-selected={section === id} tabIndex={section === id ? 0 : -1} aria-controls={`institution-panel-${id}`} onKeyDown={event => tabKey(event, index)} onClick={() => setSection(id)}><Icon size={18} weight={section === id ? 'fill' : 'regular'} />{label}</button>)}</div>
    {!token && <Notice>Conectá tu acceso institucional para consultar datos del portal. <button className="text-button" onClick={connect}>Conectar acceso <ArrowRightIcon size={16} /></button></Notice>}
    {error && <Notice kind="error">{error} <button className="text-button" onClick={() => setTick(n => n + 1)}>Reintentar</button></Notice>}
    {loading && <div className="loading-inline" role="status"><Spinner />Actualizando datos de la API…</div>}
    {analytics?.suppressed && <div className="privacy-line"><LockKeyIcon size={17} />Se necesitan al menos {minimum} personas activas por grupo para mostrar resultados. Los datos no publicables aparecen como «—».</div>}
    <section id={`institution-panel-${section}`} role="tabpanel" aria-labelledby={`institution-tab-${section}`} tabIndex={0} className="institution-view" aria-busy={loading}>
      {section === 'resumen' && <>
        <div className="stats-grid"><Metric label="Personas activas" value={format(analytics?.participants)} hint="Actividad en el período seleccionado" icon={<UsersThreeIcon />} /><Metric label="Tareas completadas" value={format(metrics?.completedFlows)} hint="Recorridos guiados finalizados" icon={<BookOpenIcon />} /><Metric label="Autonomía digital" value={format(metrics?.autonomyRate, '%')} hint="Prácticas sin ayuda / tareas completadas" icon={<ShieldCheckIcon />} /><Metric label="Tiempo acompañado" value={format(metrics?.accompaniedMinutes, ' min')} hint="Tiempo registrado en sesiones TINO" icon={<ClockIcon />} /></div>
        <div className="institution-grid">{map}<article className="panel padded"><PanelTitle eyebrow="APRENDIZAJE" title="Los recorridos más pedidos" />{analytics?.flows.length ? <div className="ranking-list">{[...analytics.flows].sort((a, b) => b.started - a.started).map((flow, index) => <div key={flow.flowId}><span className="ranking-number">{String(index + 1).padStart(2, '0')}</span><div><strong>{flow.name}</strong><div className="data-bar"><span style={{ width: `${flow.completed * 100 / flow.started}%` }} /></div><small>{flow.completed} completados de {flow.started} iniciados</small></div></div>)}</div> : <Unavailable title="Sin recorridos publicables" minimum={minimum} />}<button className="text-button" onClick={() => setSection('flujos')}>Explorar los flujos <ArrowRightIcon size={16} /></button></article></div>
        <Trend analytics={analytics} minimum={minimum} />{publicData}
      </>}
      {section === 'territorio' && <><div className="institution-grid">{map}<article className="panel padded"><PanelTitle eyebrow="24 JURISDICCIONES" title="Actividad por territorio" /><div className="territory-list">{territories.map(item => { const data = territorial.find(row => row.territoryId === item.id); return <button key={item.id} className={item.id === territoryId ? 'selected' : ''} aria-pressed={item.id === territoryId} onClick={() => setTerritoryId(item.id)}><span>{item.name}</span><strong>{format(data?.participants)}</strong><span className="sr-only">personas activas</span></button>; })}</div><p className="fine-print">Cada jurisdicción tiene su propio umbral. «—» no equivale a cero.</p></article></div>{publicData}</>}
      {section === 'flujos' && <article className="panel padded"><PanelTitle eyebrow="DEMANDA Y APRENDIZAJE" title="Dónde avanzamos. Dónde cuesta." />{analytics?.flows.length ? <><div className="table-scroll"><table><thead><tr>{['Recorrido', 'Inicios', 'Completados', 'Cancelados', 'Autónomos', 'Min. promedio', 'Repeticiones', '«Me perdí»'].map(label => <th scope="col" key={label}>{label}</th>)}</tr></thead><tbody>{analytics.flows.map(flow => <tr key={flow.flowId}><th scope="row">{flow.name}</th><td>{flow.started}</td><td>{flow.completed}</td><td>{flow.cancelled}</td><td>{flow.autonomyChecks}</td><td>{format(flow.averageMinutes)}</td><td>{flow.repeatedSteps}</td><td>{flow.lostEvents}</td></tr>)}</tbody></table></div>{analytics.flows.map(flow => <details key={flow.flowId} className="sources-details"><summary>Pasos completados · {flow.name}</summary>{flow.steps.length ? <Bars rows={flow.steps.map(step => ({ label: step.stepId, value: step.completed }))} unit="finalizaciones" /> : <Unavailable title="Desglose de pasos protegido" minimum={minimum} />}</details>)}</> : <Unavailable title="Todavía no hay flujos publicables" minimum={minimum} />}<p className="method-note">La finalización y la práctica autónoma son eventos distintos. Una sesión en curso no se cuenta como abandono; las cancelaciones se muestran por separado.</p></article>}
      {section === 'uso' && <><div className="stats-grid"><Metric label="Tiempo de pantalla" value={format(analytics?.usage?.screenMinutes, ' min')} hint="Intervalos de uso enviados con permiso" icon={<ClockIcon />} /><Metric label="Con acompañamiento" value={format(analytics?.usage?.accompaniedMinutes, ' min')} hint="Dentro de esos intervalos de uso" icon={<UsersThreeIcon />} /><Metric label="Sin acompañamiento" value={format(analytics?.usage?.unaccompaniedMinutes, ' min')} hint="No equivale a autonomía validada" icon={<ShieldCheckIcon />} /><Metric label="Sesión guiada promedio" value={format(metrics?.averageMinutes, ' min')} hint="Duración de sesiones TINO" icon={<BookOpenIcon />} /></div><article className="panel padded"><PanelTitle eyebrow="USO DEL TELÉFONO" title="Diferentes formas de usar la tecnología." />{analytics?.usage?.categories.length ? <Bars rows={analytics.usage.categories.map(row => ({ label: categories[row.category] ?? row.category, value: row.minutes }))} unit="min" /> : <Unavailable title="Sin categorías de uso publicables" minimum={minimum} />}<p className="method-note">El tiempo de pantalla requiere intervalos enviados con permiso desde el teléfono. No se estima a partir de la duración de las sesiones guiadas.</p></article></>}
      {section === 'seguridad' && <><div className="stats-grid"><Metric label="Señales registradas" value={format(analytics?.safety?.total)} hint="Eventos de seguridad del dispositivo" icon={<ShieldCheckIcon />} /><Metric label="Pendientes" value={format(analytics?.safety?.pending)} hint="Sin resolución registrada" icon={<ClockIcon />} /><Metric label="Resueltas" value={format(analytics?.safety?.resolved)} hint="Confirmadas desde el teléfono" icon={<ShieldCheckIcon />} /><Metric label="Pedidos de ayuda" value={format(analytics?.help?.total)} hint="Solicitudes a la red; no detecciones de fraude" icon={<UsersThreeIcon />} /></div><article className="panel padded"><PanelTitle eyebrow="SEÑALES PARA ACOMPAÑAR" title="Entender el riesgo, sin exponer a nadie." />{analytics?.safety?.categories.length ? <Bars rows={analytics.safety.categories.map(row => ({ label: categories[row.kind] ?? row.kind, value: row.count }))} unit="señales" /> : <Unavailable title="Sin categorías de seguridad publicables" minimum={minimum} />}<p className="method-note">Este portal muestra categorías agregadas, sin enlaces visitados, identidades, diagnósticos, mensajes ni capturas. Una señal no confirma fraude.</p></article></>}
      {section === 'programas' && <Programs key={`${territoryId}-${token ? 'connected' : 'disconnected'}`} territoryId={territoryId} territoryName={territory.name} programs={programs.filter(p => p.territoryId === territoryId)} token={token} refresh={() => setTick(n => n + 1)} />}
    </section>
    <div className="page-footnote"><ShieldCheckIcon size={18} /><p>Datos agregados, nunca personas. Mínimo de {minimum} participantes por grupo. {analytics?.updatedAt ? `Actualizado: ${localDate(analytics.updatedAt, true)}.` : ''}</p></div>
  </>;
}
export function PanelTitle({ eyebrow, title }: { eyebrow: string; title: string }) { return <div className="panel-heading"><div><div className="eyebrow">{eyebrow}</div><h2>{title}</h2></div></div>; }
function Metric({ label, value, hint, icon }: { label: string; value: string; hint: string; icon: ReactNode }) { return <article className="stat"><div className="stat-label">{label}{icon}</div><div className="stat-value">{value}</div><p className="stat-hint">{hint}</p></article>; }
function Unavailable({ title, minimum }: { title: string; minimum: number }) { return <EmptyState icon={<LockKeyIcon size={25} />} title={title}>Se muestra información cuando existe una medición y cada grupo alcanza {minimum} personas. Una celda protegida o no medida no representa cero.</EmptyState>; }
function Bars({ rows, unit }: { rows: { label: string; value: number }[]; unit: string }) { const maximum = Math.max(1, ...rows.map(row => row.value)); return <div className="bars">{rows.map(row => <div className="bar-row" key={row.label}><div><strong>{row.label}</strong><span>{format(row.value)} {unit}</span></div><div className="data-bar"><span style={{ width: `${row.value * 100 / maximum}%` }} /></div></div>)}</div>; }
function Trend({ analytics, minimum }: { analytics: Analytics | null; minimum: number }) {
  const rows = analytics?.trend ?? []; const max = Math.max(1, ...rows.map(row => row.sessions));
  return <article className="panel padded"><PanelTitle eyebrow="EVOLUCIÓN" title="Más práctica, más autonomía." />{rows.length ? <><div className="trend-chart" role="img" aria-label="Sesiones diarias y prácticas autónomas. Valores exactos en la tabla inferior.">{rows.map(day => <div key={day.date}><div className="trend-track"><span style={{ height: `${day.sessions * 100 / max}%` }} /><i style={{ height: `${day.autonomyChecks * 100 / max}%` }} /></div><span>{day.date.slice(5).split('-').reverse().join('/')}</span></div>)}</div><div className="chart-legend"><span>● Sesiones iniciadas</span><span>● Prácticas autónomas</span></div><details className="sources-details"><summary>Ver valores de la evolución</summary><div className="table-scroll"><table><thead><tr><th>Fecha UTC</th><th>Sesiones</th><th>Completadas</th><th>Autónomas</th></tr></thead><tbody>{rows.map(day => <tr key={day.date}><td>{day.date}</td><td>{day.sessions}</td><td>{day.completed}</td><td>{day.autonomyChecks}</td></tr>)}</tbody></table></div></details></> : <Unavailable title="La evolución aparece con una cohorte suficiente" minimum={minimum} />}</article>;
}
