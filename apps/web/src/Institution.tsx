import { useEffect, useRef, useState } from 'react';
import { ArrowDownIcon, ArrowRightIcon, ArrowUpRightIcon, BookOpenIcon, ChartBarIcon, CheckCircleIcon, ClockIcon, DatabaseIcon, DownloadSimpleIcon, GlobeHemisphereWestIcon, LockKeyIcon, MagnifyingGlassIcon, MapPinIcon, PaperPlaneTiltIcon, ShieldCheckIcon, UsersThreeIcon } from '@phosphor-icons/react';
import { api, errorText, localDate } from './api';
import type { Dataset, Health, Overview, PublicAnswer, PublicContext } from './api';
import { territories } from './territories';
import { TerritoryMap } from './TerritoryMap';
import { EmptyState, Notice, SourceLink, Spinner } from './ui';
import { InstitutionInsights, metricValue } from './InstitutionInsights';

export function Institution({ token, health, connect }: { token: string; health: Health | null; connect: () => void }) {
  const [territoryId, setTerritoryId] = useState('AR-C');
  const territory = territories.find(t => t.id === territoryId) ?? territories[0];
  const [overview, setOverview] = useState<Overview | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [context, setContext] = useState<PublicContext | null>(null);
  const [query, setQuery] = useState('personas mayores, hogares unipersonales y conectividad');
  const [dataBusy, setDataBusy] = useState(false);
  const [dataError, setDataError] = useState('');
  const [question, setQuestion] = useState('');
  const [answer, setAnswer] = useState<PublicAnswer | null>(null);
  const [asking, setAsking] = useState(false);
  const [askError, setAskError] = useState('');
  const [refresh, setRefresh] = useState(0);
  const [exporting, setExporting] = useState(false);
  const dataRequest = useRef<AbortController | null>(null);
  const askRequest = useRef<AbortController | null>(null);
  const exportRequest = useRef<AbortController | null>(null);
  const overviewEpoch = useRef(0);
  const currentView = useRef(''); currentView.current = `${token}\0${territoryId}`;
  useEffect(() => { setExporting(false); return () => exportRequest.current?.abort(); }, [token, territoryId]);

  useEffect(() => {
    setOverview(null); setError('');
    if (!token) return;
    const controller = new AbortController(); let inFlight = false;
    setLoading(true);
    const load = async () => {
      if (inFlight || controller.signal.aborted) return;
      inFlight = true;
      const epoch = ++overviewEpoch.current;
      try { const result = await api<Overview>(`/institution/overview?territoryId=${encodeURIComponent(territoryId)}`, { token, signal: controller.signal }); if (!controller.signal.aborted && epoch === overviewEpoch.current) { setOverview(result); setError(''); } }
      catch (e) { if (!controller.signal.aborted && epoch === overviewEpoch.current) setError(errorText(e)); }
      finally { inFlight = false; if (!controller.signal.aborted) setLoading(false); }
    };
    void load();
    const timer = window.setInterval(() => { if (document.visibilityState === 'visible') void load(); }, 30_000);
    const onVisibility = () => { if (document.visibilityState === 'visible') void load(); };
    document.addEventListener('visibilitychange', onVisibility);
    return () => { controller.abort(); window.clearInterval(timer); document.removeEventListener('visibilitychange', onVisibility); };
  }, [token, territoryId, refresh]);

  useEffect(() => {
    dataRequest.current?.abort(); askRequest.current?.abort();
    setContext(null); setAnswer(null); setDataError(''); setAskError(''); setDataBusy(false); setAsking(false);
    return () => { dataRequest.current?.abort(); askRequest.current?.abort(); };
  }, [territoryId, token]);

  useEffect(() => {
    if (!token || !health?.openarg?.configured) return;
    const controller = new AbortController(); dataRequest.current = controller;
    setDataBusy(true); setDataError('');
    api<PublicContext>(`/openarg/territory?q=${encodeURIComponent('personas mayores conectividad acceso a internet')}&territoryId=${encodeURIComponent(territoryId)}`, { token, signal: controller.signal })
      .then(result => { if (!controller.signal.aborted) setContext(result); })
      .catch(e => { if (!controller.signal.aborted) setDataError(errorText(e)); })
      .finally(() => { if (!controller.signal.aborted) setDataBusy(false); });
    return () => controller.abort();
  }, [territoryId, token, health?.openarg?.configured]);

  async function searchContext() {
    dataRequest.current?.abort();
    const controller = new AbortController(); dataRequest.current = controller;
    setDataBusy(true); setDataError(''); setContext(null);
    try {
      const result = await api<PublicContext>(`/openarg/territory?q=${encodeURIComponent(query.trim())}&territoryId=${territory.id}`, { token, signal: controller.signal });
      if (!controller.signal.aborted) setContext(result);
    } catch (e) { if (!controller.signal.aborted) setDataError(errorText(e)); }
    finally { if (!controller.signal.aborted) setDataBusy(false); }
  }

  async function askPublic() {
    askRequest.current?.abort();
    const controller = new AbortController(); askRequest.current = controller;
    setAsking(true); setAskError(''); setAnswer(null);
    try {
      const result = await api<PublicAnswer>('/openarg/ask', { token, method: 'POST', body: { question: question.trim(), territoryId }, signal: controller.signal });
      if (!controller.signal.aborted) setAnswer(result);
    } catch (e) { if (!controller.signal.aborted) setAskError(errorText(e)); }
    finally { if (!controller.signal.aborted) setAsking(false); }
  }

  async function exportSummary() {
    const view = currentView.current; const controller = new AbortController();
    const epoch = ++overviewEpoch.current;
    exportRequest.current?.abort(); exportRequest.current = controller; setExporting(true); setError('');
    try {
      const latest = await api<Overview>(`/institution/overview?territoryId=${encodeURIComponent(territoryId)}`, { token, signal: controller.signal });
      if (controller.signal.aborted || currentView.current !== view) return;
      if (epoch === overviewEpoch.current) setOverview(latest);
      const blob = new Blob([JSON.stringify({ title: 'NIETO · Resumen territorial', territory: territory.name, exportedAt: new Date().toISOString(), overview: latest, publicContext: context, note: 'Las celdas suprimidas no representan cero. No contiene datos individuales.' }, null, 2)], { type: 'application/json' });
      const url = URL.createObjectURL(blob); const link = document.createElement('a');
      link.href = url; link.download = `nieto-${territory.id}-resumen.json`; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (e) { if (!controller.signal.aborted && currentView.current === view) setError(errorText(e)); }
    finally { if (!controller.signal.aborted && currentView.current === view) setExporting(false); }
  }

  const metrics = overview?.metrics;
  const minimum = overview?.minimumCohort ?? health?.privacy?.minimumCohort ?? 5;
  const statItems = [
    { label: 'Personas activas', value: overview?.activity?.activePeople, icon: CheckCircleIcon, hint: 'Uso real con consentimiento institucional' },
    { label: 'Sesiones iniciadas', value: overview?.activity?.startedFlows, icon: BookOpenIcon, hint: 'Acompañamientos solicitados' },
    { label: 'Minutos acompañados', value: metrics?.accompaniedMinutes, icon: ClockIcon, hint: 'Tiempo registrado durante las sesiones' },
    { label: 'Pedidos de ayuda', value: metrics?.helpRequests, icon: UsersThreeIcon, hint: 'Acompañamiento solicitado' },
  ];

  return <>
    <header className="page-heading"><div><div className="eyebrow"><span className="tiny-line" />PORTAL INSTITUCIONAL</div><h1>Acompañar mejor.<br /><span>Conocer el contexto.</span></h1><p>Autonomía digital y datos públicos para llegar a quienes necesitan una mano.</p></div><button className="button secondary export-button" onClick={() => { void exportSummary(); }} disabled={!overview || exporting}>{exporting ? <Spinner /> : <DownloadSimpleIcon size={18} />}{exporting ? 'Actualizando resumen…' : 'Exportar resumen'}</button></header>

    <div className="view-toolbar"><div className="view-label"><ChartBarIcon size={19} weight="bold" /><strong>Panorama territorial</strong><span className="badge">MVP · LABITCONF</span></div><label className="territory-select"><MapPinIcon size={18} /><span className="sr-only">Territorio</span><select aria-label="Territorio" value={territoryId} onChange={e => { dataRequest.current?.abort(); askRequest.current?.abort(); setContext(null); setAnswer(null); setTerritoryId(e.target.value); }}>{territories.map(t => <option key={t.id} value={t.id}>{t.name}</option>)}</select></label></div>

    <section className="stats-grid" aria-label="Indicadores de acompañamiento">{statItems.map(({ label, value, icon: Icon, hint }) => <article className="stat" key={label}><div className="stat-label">{label}<Icon size={19} /></div><div className="stat-value">{loading ? <span className="skeleton-number" /> : value === undefined || value === null ? '—' : new Intl.NumberFormat('es-AR', { maximumFractionDigits: 1 }).format(value)}</div><div className="stat-hint">{value === undefined || value === null ? <LockKeyIcon size={13} /> : <ArrowUpRightIcon size={13} />}{value === undefined || value === null ? 'Esperando una cohorte publicable' : hint}</div></article>)}</section>

    {!token && <Notice>Conectá el acceso institucional para ver métricas y consultar OpenArg. <button className="text-button" onClick={connect}>Configurar acceso <ArrowRightIcon /></button></Notice>}
    {error && <Notice kind="error">{error} <button className="text-button" onClick={() => setRefresh(n => n + 1)}>Reintentar</button></Notice>}
    {overview?.suppressed && <div className="privacy-line"><ShieldCheckIcon size={17} />Cada indicador necesita al menos {minimum} personas que hayan consentido y contribuido a esa métrica en el período. Las instalaciones sin uso no habilitan valores.</div>}

    {overview && overview.territoryId === territoryId && <InstitutionInsights overview={overview} />}

    <section className="territory-grid">
      <article className="panel map-panel"><div className="panel-heading"><div><div className="eyebrow">01 / TERRITORIO</div><h2>El lugar importa.</h2></div><span className="small-status"><span className="status-dot" />Cartografía de referencia</span></div><TerritoryMap territory={territory} indicator={context?.territoryId === territoryId ? context.indicators?.[0] : undefined} /><div className="map-footer"><GlobeHemisphereWestIcon size={17} /><span>Contexto público por provincia. El marcador orienta la consulta y no representa personas.</span></div></article>
      <article className="panel public-panel"><div className="panel-heading"><div><div className="eyebrow">02 / CONTEXTO PÚBLICO</div><h2>Datos con origen.</h2></div><span className="openarg-mark">OpenArg <ArrowUpRightIcon size={14} /></span></div><p className="panel-description">Buscá información sobre personas mayores, conectividad y acceso a servicios en <strong>{territory.short}</strong>.</p><div className="source-principles"><div><DatabaseIcon weight="duotone" /><span>Consulta al catálogo oficial</span></div><div><ClockIcon weight="duotone" /><span>Período y vigencia visibles</span></div><div><ShieldCheckIcon weight="duotone" /><span>Sin datos personales de NIETO</span></div></div>
        <form className="public-search" onSubmit={e => { e.preventDefault(); void searchContext(); }}><label htmlFor="dataset-query">¿Qué datos querés buscar?</label><textarea id="dataset-query" value={query} onChange={e => setQuery(e.target.value)} maxLength={300} rows={2} required minLength={3} /><button className="button primary full" disabled={!token || !health?.openarg?.configured || dataBusy || query.trim().length < 3}>{dataBusy ? <Spinner /> : <MagnifyingGlassIcon size={18} />}{dataBusy ? 'Consultando OpenArg…' : 'Consultar datos del territorio'}{!dataBusy && <ArrowRightIcon size={18} />}</button></form>
        <p className="fine-print">{!health?.openarg?.configured ? 'La consulta se habilita al configurar la clave de OpenArg en el servidor.' : 'Cada búsqueda usa el MCP real. Los resultados se cachean para cuidar la cuota.'}</p>
      </article>
    </section>

    {dataError && <Notice kind="error">{dataError}</Notice>}
    {context && context.territoryId === territoryId && <section className="panel results-panel"><div className="panel-heading"><div><div className="eyebrow">RESULTADO DE OPENARG</div><h2>Fuentes para {territory.short}</h2></div><span className="meta">{context.cached ? 'Desde caché · ' : ''}{localDate(context.fetchedAt, true)}</span></div>
      {context.warnings?.map((warning, i) => <Notice key={i}>{warning}</Notice>)}
      {!!context.opportunities?.length && <div className="source-principles">{context.opportunities.map(opportunity => <div key={opportunity.id}><BookOpenIcon weight="duotone" /><span><strong>{opportunity.title}</strong><br />{opportunity.limitation}</span></div>)}</div>}
      {!!context.indicators?.length && <div className="public-indicators">{context.indicators.map(indicator => <article key={indicator.id}><span>{indicator.label}</span><strong>{typeof indicator.value === 'number' ? new Intl.NumberFormat('es-AR', { maximumFractionDigits: 1 }).format(indicator.value) : indicator.value} <small>{indicator.unit}</small></strong><span>Período: {indicator.period || 'No informado'}{indicator.freshness?.historical ? ' · contexto histórico' : ''}</span>{indicator.method?.note && <p className="fine-print">{indicator.method.note}</p>}<SourceLink url={indicator.sourceUrl}>{indicator.sourceTitle}</SourceLink></article>)}</div>}
      {context.datasets?.length ? <div className="dataset-list">{context.datasets.map((dataset, index) => <DatasetItem dataset={dataset} index={index} key={`${dataset.id}-${index}`} />)}</div> : <EmptyState icon={<DatabaseIcon size={27} />} title="Sin datasets disponibles para esta búsqueda">Podés probar con otro tema o una jurisdicción más amplia. No se completan resultados con datos inventados.</EmptyState>}
      {!!context.sources?.length && <div className="sources-footer"><strong>Fuentes devueltas por OpenArg</strong>{context.sources.map((source, index) => <SourceLink key={index} url={source.url}>{source.title}</SourceLink>)}</div>}
      {!!context.rawText && !context.datasets?.length && <details className="raw-result"><summary>Ver respuesta original del MCP</summary><pre>{context.rawText}</pre></details>}
      {!!context.gaps?.length && <details className="insight-method"><summary>Qué no permiten concluir estas fuentes</summary><ul>{context.gaps.map((gap, index) => <li key={index}>{gap}</li>)}</ul></details>}
    </section>}

    <section className="bottom-grid"><article className="panel learning-panel"><div className="panel-heading"><div><div className="eyebrow">GUÍA LOCAL OPCIONAL</div><h2>Práctica acompañada.</h2></div><BookOpenIcon size={23} /></div><div className="flow-row"><span className="flow-icon"><PaperPlaneTiltIcon size={25} weight="duotone" /></span><div><strong>Compartir ubicación por WhatsApp</strong><p>Guía local · eventos reportados por el teléfono</p></div><span className="badge">Experimental</span></div><div className="flow-columns"><div><span>Iniciadas</span><strong>{metricValue(overview?.flows.find(f => f.flowId === 'whatsapp-location')?.started)}</strong></div><ArrowRightIcon size={18} /><div><span>Finalizaciones reportadas</span><strong>{metricValue(overview?.flows.find(f => f.flowId === 'whatsapp-location')?.reported)}</strong></div></div><p className="fine-print">Una sesión reportada no demuestra autonomía ni verifica por sí sola que la persona haya completado su objetivo. La asistencia por IA decide los pasos según la pantalla actual.</p></article>
      <article className="panel public-question"><div className="eyebrow">PREGUNTA A LOS DATOS PÚBLICOS</div><h2>Una pregunta puede<br />orientar un taller.</h2><form onSubmit={e => { e.preventDefault(); void askPublic(); }}><label className="sr-only" htmlFor="public-question">Pregunta para OpenArg</label><textarea id="public-question" placeholder="¿Qué datos hay sobre conectividad de personas mayores en este territorio?" value={question} onChange={e => setQuestion(e.target.value)} maxLength={500} minLength={5} required rows={3} /><button className="button secondary" disabled={!token || !health?.openarg?.configured || asking || question.trim().length < 5}>{asking ? <Spinner /> : <ArrowUpRightIcon size={18} />}{asking ? 'Analizando fuentes…' : 'Preguntar a OpenArg'}</button></form><p className="fine-print">Consulta pública con IA de OpenArg. Consume una pregunta de su cuota. OpenArg puede registrar la consulta asociada a la clave. No incluyas datos personales.</p></article></section>
    {askError && <Notice kind="error">{askError}</Notice>}
    {answer && <article className="panel answer-panel"><div className="panel-heading"><h2>Respuesta de OpenArg</h2><span className="meta">{localDate(answer.fetchedAt, true)}</span></div><div className="answer-text">{answer.answer}</div>{answer.warnings?.map((w, i) => <Notice key={i}>{w}</Notice>)}<div className="sources-footer">{answer.sources?.map((s, i) => <SourceLink url={s.url} key={i}>{s.title}</SourceLink>)}</div></article>}
    <div className="page-footnote"><ShieldCheckIcon size={17} /><p>Las instituciones ven resultados agregados. Los mensajes, las capturas y la ubicación individual permanecen fuera de este portal.</p><a href="https://mcp.openarg.org" target="_blank" rel="noreferrer">Acerca del MCP <ArrowUpRightIcon /></a></div>
  </>;
}

function DatasetItem({ dataset, index }: { dataset: Dataset; index: number }) {
  return <article className="dataset-row"><span className="dataset-number">{String(index + 1).padStart(2, '0')}</span><div><div className="dataset-title"><h3>{dataset.title}</h3>{dataset.organization && <span className="badge">{dataset.organization}</span>}</div><p>{dataset.description}</p><div className="dataset-meta"><span>Período: {dataset.period || 'no informado por la fuente'}</span><span>Actualización: {dataset.updatedAt ? localDate(dataset.updatedAt) : 'no informada'}</span></div><div className="dataset-resources"><SourceLink url={dataset.sourceUrl}>Consultar fuente oficial</SourceLink>{dataset.resources?.map((resource, i) => <SourceLink url={resource.url} key={`${resource.id}-${i}`}>{resource.name}{resource.format ? ` · ${resource.format}` : ''}<ArrowDownIcon size={12} /></SourceLink>)}</div></div></article>;
}
