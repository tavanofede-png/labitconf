import { ClockIcon, ShieldCheckIcon } from '@phosphor-icons/react';
import type { Overview } from './api';
import { localDate } from './api';
import { EmptyState } from './ui';

export const metricValue = (value: number | null | undefined) => value == null ? '—' : new Intl.NumberFormat('es-AR', { maximumFractionDigits: 1 }).format(value);
const flowName = (id: string) => id === 'screen-assistance' ? 'Asistencia según la pantalla' : id === 'whatsapp-location' ? 'Guía local de WhatsApp' : id;
const utcDate = (date: string) => new Intl.DateTimeFormat('es-AR', { dateStyle: 'short', timeZone: 'UTC' }).format(new Date(date));

export function InstitutionInsights({ overview }: { overview: Overview }) {
  const minimum = overview.minimumCohort;
  const difficulty = overview.difficulties;
  const response = overview.familyResponse;
  return <section className="institution-insights" aria-label="Decisiones de acompañamiento">
    <div className="insight-period"><div><strong>Lo que ocurrió en NIETO</strong><p>{overview.period ? `${utcDate(overview.period.from)} – ${utcDate(overview.period.to)} · últimos ${overview.period.days} días · ${overview.period.timezone}` : 'Período no informado'}</p></div><span>Actualizado {localDate(overview.updatedAt, true)} · refresco cada 30 s</span></div>
    <div className="insight-grid">
      <article className="panel insight-panel"><div className="eyebrow">ORGANIZAR UN TALLER</div><h2>¿Dónde hace falta acompañar?</h2><p>Repeticiones y pedidos «Me perdí» registrados durante el uso. Sirven para elegir qué practicar.</p><div className="insight-number">{metricValue(difficulty?.events)} <span>dificultades reportadas</span></div>{difficulty?.steps?.length ? <ul className="insight-rows">{difficulty.steps.map(step => <li key={step.stepId}><span>{step.stepId === 'screen-step' ? 'Asistencia según la pantalla' : `Paso: ${step.stepId}`}</span><strong>{metricValue(step.events)}</strong></li>)}</ul> : <p className="fine-print">Se muestran pasos solo cuando reúnen {minimum} contribuyentes consentidos. Un valor oculto no representa cero.</p>}</article>
      <article className="panel insight-panel"><div className="eyebrow">COORDINAR A LA FAMILIA</div><h2>¿Cuánto tarda el acompañamiento?</h2><p>Tiempo entre una solicitud de ayuda y la marca «Atendido» del familiar.</p><div className="insight-number">{metricValue(response?.meanResponseMinutes)} <span>minutos de promedio</span></div><p className="fine-print">{metricValue(response?.resolvedRequests)} solicitudes atendidas en una cohorte publicable. Marcar atendido no demuestra que el problema haya quedado resuelto.</p><div className="privacy-line"><ShieldCheckIcon size={17} />Cada indicador exige su propia cohorte de {minimum} contribuyentes.</div></article>
    </div>
    <article className="panel insight-panel"><div className="panel-heading"><div><div className="eyebrow">USO REAL DEL MVP</div><h2>Qué acompañamientos se usan.</h2></div><ClockIcon size={22} /></div>{overview.flows.length ? <div className="insight-table-wrap"><table className="insight-table"><thead><tr><th>Tipo de acompañamiento</th><th>Sesiones iniciadas</th><th>Finalizaciones reportadas</th></tr></thead><tbody>{overview.flows.map(flow => <tr key={flow.flowId}><td>{flowName(flow.flowId)}</td><td>{metricValue(flow.started)}</td><td>{flow.flowId === 'screen-assistance' ? 'No se acredita automáticamente' : metricValue(flow.reported)}</td></tr>)}</tbody></table></div> : <EmptyState icon={<ShieldCheckIcon size={24} />} title="Esperando una cohorte publicable">Las altas vacías no habilitan estadísticas. Hace falta uso real y consentimiento institucional.</EmptyState>}<p className="fine-print">Las sesiones de asistencia no prueban una tarea completada ni autonomía. Las finalizaciones de la guía local son eventos reportados por el teléfono.</p></article>
    {!!overview.trend?.length && <article className="panel insight-panel"><div className="eyebrow">PREPARAR EL SEGUIMIENTO</div><h2>Personas activas por período.</h2><div className="trend-grid">{overview.trend.map(period => <div key={period.from}><span>{utcDate(period.from)} – {utcDate(period.to)}</span><strong>{metricValue(period.activePeople)}</strong><small>{period.suppressed ? 'Cohorte insuficiente' : 'Personas consentidas'}</small></div>)}</div><p className="fine-print">Ventanas de cinco días en UTC. Cada ventana se evalúa por separado; no se interpreta un período suprimido como una caída de uso.</p></article>}
    {!!overview.gaps?.length && <details className="insight-method"><summary>Alcance y datos pendientes</summary><ul>{overview.gaps.map((gap, index) => <li key={index}>{gap}</li>)}</ul>{overview.evidence && <p>{overview.evidence.interpretation} {overview.evidence.comparison}</p>}</details>}
  </section>;
}
