import { useEffect, useRef, useState } from 'react';
import { ArrowRightIcon, DatabaseIcon, MagnifyingGlassIcon } from '@phosphor-icons/react';
import { api, errorText, localDate } from './api';
import type { Analytics, Health, PublicAnswer, PublicContext } from './api';
import type { Territory } from './territories';
import { EmptyState, Notice, SourceLink, Spinner } from './ui';

export function PublicData({ token, health, territory, analytics, context, setContext }: {
  token: string; health: Health | null; territory: Territory; analytics: Analytics | null;
  context: PublicContext | null; setContext: (context: PublicContext | null) => void;
}) {
  const [query, setQuery] = useState('población, discapacidad, conectividad y acceso a servicios');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [question, setQuestion] = useState('');
  const [answer, setAnswer] = useState<PublicAnswer | null>(null);
  const [asking, setAsking] = useState(false);
  const request = useRef<AbortController | null>(null);
  const askRequest = useRef<AbortController | null>(null);
  useEffect(() => () => { request.current?.abort(); askRequest.current?.abort(); }, [territory.id, token]);
  async function search() {
    request.current?.abort(); const controller = new AbortController(); request.current = controller;
    setBusy(true); setError(''); setContext(null);
    try { const result = await api<PublicContext>(`/openarg/territory?territoryId=${territory.id}&q=${encodeURIComponent(query.trim())}`, { token, signal: controller.signal });
      if (!controller.signal.aborted) setContext(result);
    } catch (e) { if (!controller.signal.aborted) setError(errorText(e)); }
    finally { if (!controller.signal.aborted) setBusy(false); }
  }
  async function ask() {
    askRequest.current?.abort(); const controller = new AbortController(); askRequest.current = controller;
    setAsking(true); setError(''); setAnswer(null);
    try { const result = await api<PublicAnswer>('/openarg/ask', { token, method: 'POST', body: { question: question.trim(), territoryId: territory.id }, signal: controller.signal });
      if (!controller.signal.aborted) setAnswer(result);
    } catch (e) { if (!controller.signal.aborted) setError(errorText(e)); }
    finally { if (!controller.signal.aborted) setAsking(false); }
  }
  const indicators = context?.indicators?.filter(i => i.territoryId === territory.id) ?? [];
  return <section className="panel evidence-panel" aria-labelledby="evidence-title">
    <div className="panel-heading"><div><div className="eyebrow">OPENARG × TINO · {territory.short}</div><h2 id="evidence-title">Datos para entender el contexto.</h2></div><a className="openarg-mark" href="https://mcp.openarg.org/" target="_blank" rel="noreferrer">OpenARG ↗</a></div>
    <p className="panel-description">Cruzá las necesidades de ayuda registradas en TINO con las fuentes públicas del mismo territorio. Cada conjunto conserva su período y procedencia.</p>
    <form className="evidence-search" onSubmit={e => { e.preventDefault(); void search(); }}><div><label htmlFor="dataset-query">Contexto que querés explorar</label><input id="dataset-query" value={query} onChange={e => setQuery(e.target.value)} maxLength={300} required minLength={3} /></div><button className="button primary" disabled={!token || !health?.openarg.configured || busy || query.trim().length < 3}>{busy ? <Spinner /> : <MagnifyingGlassIcon size={18} />}{busy ? 'Consultando fuentes…' : 'Cruzar con OpenARG'}</button></form>
    {!health?.openarg.configured && <p className="fine-print">Para traer datos reales, configurá la clave de OpenARG en el servidor desde Conexión.</p>}
    {error && <Notice kind="error">{error}</Notice>}
    {context && <>
      <div className="evidence-stamp">{context.cached ? 'Respuesta en caché' : 'Consulta MCP'} · {localDate(context.fetchedAt, true)}</div>
      <div className="context-comparison"><article><span className="eyebrow">ACTIVIDAD TINO</span><h3>{territory.name}</h3><strong>{analytics?.participants == null ? '—' : analytics.participants.toLocaleString('es-AR')} <small>personas activas</small></strong><p>{analytics ? `${localDate(analytics.period.from)} — ${localDate(analytics.period.to)}` : 'Sin un período de actividad disponible'}</p><p>{analytics?.flows.length ? `Ayuda registrada: ${analytics.flows.map(f => f.name).join(', ')}.` : 'Sin desglose publicable de flujos.'}</p></article><article><span className="eyebrow">CONTEXTO PÚBLICO OPENARG</span>{indicators.length ? indicators.map(indicator => <div className="context-indicator" key={indicator.id}><span>{indicator.label}</span><strong>{typeof indicator.value === 'number' ? indicator.value.toLocaleString('es-AR') : indicator.value} <small>{indicator.unit}</small></strong><p>Período de la fuente: {indicator.period || 'No informado'}</p><SourceLink url={indicator.sourceUrl}>{indicator.sourceTitle}</SourceLink></div>) : <EmptyState icon={<DatabaseIcon size={24} />} title="Sin indicador numérico verificado">Las fuentes del catálogo se pueden explorar; todavía no acreditan una cifra para esta jurisdicción.</EmptyState>}</article></div>
      <p className="method-note">Cruce contextual por territorio, sin unir identidades. Un censo histórico no es un denominador de cobertura actual. Los datos poblacionales no describen la edad, discapacidad o situación de quienes usan TINO.</p>
      {context.warnings.map((warning, i) => <Notice key={i}>{warning}</Notice>)}
      <details className="sources-details"><summary>Explorar {context.datasets.length} fuentes del catálogo</summary>{context.datasets.map(dataset => <article className="dataset-row" key={dataset.id}><DatabaseIcon size={20} /><div><h3>{dataset.title}</h3><p>{dataset.description}</p><div className="dataset-meta"><span>{dataset.organization}</span><span>Período: {dataset.period || 'Revisar en la fuente'}</span></div><SourceLink url={dataset.sourceUrl}>Ver fuente oficial</SourceLink></div></article>)}{!context.datasets.length && <p className="fine-print">No se encontraron datasets para esta búsqueda.</p>}</details>
      {!!context.rawText && <details className="raw-result"><summary>Respuesta original de OpenARG</summary><pre>{context.rawText}</pre></details>}
    </>}
    <details className="public-ask"><summary>Hacer una pregunta a las fuentes públicas</summary><p className="fine-print">Usa una pregunta de la cuota de OpenARG. No incluyas datos personales ni conversaciones de TINO.</p><form onSubmit={e => { e.preventDefault(); void ask(); }}><label htmlFor="public-question">Tu pregunta sobre {territory.short}</label><textarea id="public-question" rows={2} minLength={5} maxLength={500} required value={question} onChange={e => setQuestion(e.target.value)} placeholder="¿Qué fuentes describen barreras de acceso a servicios digitales?" /><button className="button secondary" disabled={!token || !health?.openarg.configured || asking || question.trim().length < 5}>{asking ? <Spinner /> : <ArrowRightIcon size={18} />}{asking ? 'Consultando…' : 'Preguntar a OpenARG'}</button></form>{answer && <div className="answer-panel"><p className="answer-text">{answer.answer}</p>{answer.warnings.map((warning, index) => <Notice key={index}>{warning}</Notice>)}<div className="sources-footer">{answer.sources.map((source, i) => <SourceLink key={i} url={source.url}>{source.title}</SourceLink>)}</div></div>}</details>
  </section>;
}
