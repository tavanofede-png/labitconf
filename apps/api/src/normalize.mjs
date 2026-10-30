import { createHash } from 'node:crypto';

function sourceUrl(raw) {
  try { const url = new URL(raw); return ['http:', 'https:'].includes(url.protocol) ? url.href : null; }
  catch { return null; }
}

/** Parse the public MCP's actual format_search output, not imagined JSON fields. */
export function normalizeSearch(result, query) {
  const datasets = [];
  const blocks = result.text.split(/(?=^\d+\. \*\*)/m);
  for (const block of blocks) {
    const heading = block.match(/^\d+\. \*\*(.+?)\*\*\s*\(([^\n]*)\)/);
    if (!heading) continue;
    const official = block.match(/Fuente:\s*\[[^\]]*\]\((https?:\/\/[^\s)]+)\)/);
    const url = sourceUrl(official?.[1]);
    const tableLine = block.match(/Tablas consultables:\s*(.+)/)?.[1] ?? '';
    const resources = [...tableLine.matchAll(/`([^`]+)`/g)].map((m) => ({ id: m[1], name: m[1], format: 'OpenArg table', url }));
    const description = block.split('\n').slice(1).map((l) => l.trim()).filter((l) => l && !/^(Tablas consultables:|Sin tabla consultable|Fuente:|Usá `describir_tabla`)/.test(l)).join(' ');
    datasets.push({ id: createHash('sha256').update(`${heading[1]}|${url ?? ''}`).digest('hex').slice(0, 20),
      title: heading[1], description, sourceUrl: url, organization: heading[2], resources });
  }
  const warnings = [...result.warnings];
  warnings.push('La búsqueda usa el territorio como contexto, sin un filtro geográfico estricto: puede devolver fuentes de otras jurisdicciones. Verificá su cobertura en la fuente.');
  if (!datasets.length && !/No encontr[ée] datasets/i.test(result.text)) warnings.push('OpenArg respondió con un formato sin datasets estructurables. Se conserva el texto original.');
  if (datasets.length) warnings.push('El catálogo no informa un período verificado. La fecha de consulta no equivale a la vigencia del dato.');
  return { query, datasets, sources: datasets.filter((d) => d.sourceUrl).map((d) => ({ title: d.title, url: d.sourceUrl })),
    warnings, cached: result.cached, fetchedAt: result.retrievedAt, rawText: result.text };
}

export function normalizeAnswer(result) {
  const warnings = [...result.warnings];
  const section = result.text.split(/\*\*Advertencias\*\*/i)[1]?.split(/\n\n/)[0];
  if (section) warnings.push(...section.split('\n').map((line) => line.replace(/^\s*-\s*/, '').trim()).filter(Boolean));
  if (!result.period.evidence.length) warnings.push('La respuesta no incluye un período verificable estructurado. Consultá la fuente oficial antes de usar el indicador.');
  return { answer: result.text, sources: result.sources, warnings, fetchedAt: result.retrievedAt, cached: result.cached };
}
