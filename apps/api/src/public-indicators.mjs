// Table identifiers and category definitions were discovered from the real MCP.
// No numeric indicator is stored here; every displayed value is an MCP aggregate.
export const INTERNET_TABLE = 'raw.cache_datos_gob_ar_internet_fija_r9bc6ad492a';
export const SPEED_TABLE = 'raw.cache_datos_gob_ar_internet_fija_r24b95882da';
const INTERNET_SOURCE = 'https://indicadores.enacom.gob.ar/Files/DatosAbiertos/internet_accesos_penetracion_provincias.xlsx';
const SPEED_SOURCE = 'https://indicadores.enacom.gob.ar/Files/DatosAbiertos/internet_velocidad_media_descarga_provincias.xlsx';
const PROVINCES = { 'AR-C': 'CABA', 'AR-B': 'Buenos Aires', 'AR-X': 'Córdoba', 'AR-S': 'Santa Fe', 'AR-M': 'Mendoza', 'AR-T': 'Tucumán', 'AR-A': 'Salta', 'AR-E': 'Entre Ríos', 'AR-H': 'Chaco', 'AR-U': 'Chubut', 'AR-K': 'Catamarca', 'AR-W': 'Corrientes', 'AR-P': 'Formosa', 'AR-Y': 'Jujuy', 'AR-L': 'La Pampa', 'AR-F': 'La Rioja', 'AR-N': 'Misiones', 'AR-Q': 'Neuquén', 'AR-R': 'Río Negro', 'AR-J': 'San Juan', 'AR-D': 'San Luis', 'AR-Z': 'Santa Cruz', 'AR-G': 'Santiago Del Estero', 'AR-V': 'Tierra Del Fuego' };
const CENSUS_PREFIX = 'cache_datos_gob_ar_censo_nacional_de_poblaci_n_hoga_';
// ISO -> province INDEC, distribution, filename, table suffix, raw schema.
export const CENSUS_TABLES = {
  'AR-C': [2, 1, '02-caba', 'r1e1ffea05f', true], 'AR-B': [6, 2, '06-buenos-aires', 'r0ffca22dd3'],
  'AR-K': [10, 3, '10-catamarca', 'r94905601a0'], 'AR-X': [14, 4, '14-cordoba', 're5cea37c00'],
  'AR-W': [18, 5, '18-corrientes', 'reee0c58d52', true], 'AR-H': [22, 6, '22-chaco', 'r9341faa926'],
  'AR-U': [26, 7, '26-chubut', 'r88598102d1'], 'AR-E': [30, 8, '30-entre-rios', 'r795578ac95'],
  'AR-P': [34, 9, '34-formosa', 'r426ebe2682', true],
  'AR-L': [42, 11, '42-la-pampa', 'r71d5614311', true], 'AR-F': [46, 12, '46-la-rioja', 'r6e2a7139d3'],
  'AR-M': [50, 13, '50-mendoza', 'r00a262a346', true], 'AR-N': [54, 14, '54-misiones', 'rb835dc668d'],
  'AR-Q': [58, 15, '58-neuquen', 'r03bf1d554d'], 'AR-R': [62, 16, '62-rio-negro', 'r6dd444eedc'],
  'AR-A': [66, 17, '66-salta', 'r26c1b369bd'], 'AR-J': [70, 18, '70-san-juan', 'r6bc6429baf', true],
  'AR-D': [74, 19, '74-san-luis', 'r476c64fec3'], 'AR-Z': [78, 20, '78-santa-cruz', 'rab5390e77a'],
  'AR-S': [82, 21, '82-santa-fe', 'r3665c91659'], 'AR-G': [86, 22, '86-santiago-del-estero', 'rc5c2727ada'],
  'AR-T': [90, 23, '90-tucuman', 'rf6f76ea44a'], 'AR-V': [94, 24, '94-tierra-del-fuego', 'r961df4312e', true],
};
const SENIOR_CATEGORIES = ['65 A 69', '70 A 74', '75 A 79', '80 A 84', '85 A 89', '90 A 94', '95 A 99', '100 A 104', '105 Y MÁS'];
const sourceRead = text => /Leída de la fuente por OpenArg:\s*(\d{4}-\d{2}-\d{2})/.exec(text)?.[1] ?? null;
const note = text => text.split('\n').find(line => line.startsWith('Nota:'))?.replace(/^Nota:\s*/, '');
const schema = (text, table, source, columns) => text.includes(`tabla \`${table}\``) && text.includes(source) && columns.every(column => text.includes(`- ${column} (`));

export function parsePublicScalar(text, table, operation, column, { oneRow = false, integer = false } = {}) {
  const match = /```csv\s*\r?\nvalor,filas_usadas\r?\n([0-9]+(?:\.[0-9]+)?),(\d+)\s*\r?\n```/.exec(text);
  if (!match || !text.startsWith(`${operation} de ${column} en \`${table}\``)) return null;
  const value = Number(match[1]); const rows = Number(match[2]);
  if (!Number.isFinite(value) || value < 0 || !Number.isSafeInteger(rows) || rows <= 0 || (oneRow && rows !== 1) || (integer && !Number.isSafeInteger(value))) return null;
  return { value, rows };
}

async function connectivity(openarg, territoryId) {
  const indicators = []; const warnings = []; let cached = true;
  const population = PROVINCES[territoryId]; if (!population) return { indicators, warnings, cached };
  for (const spec of [
    { table: INTERNET_TABLE, source: INTERNET_SOURCE, column: 'accesos_cada_100_hogares', id: 'fixed-internet-accesses', label: 'Accesos de internet fijo cada 100 hogares', unit: 'accesos / 100 hogares', explanation: 'Es densidad de accesos, no porcentaje de hogares conectados ni personas usuarias. Puede superar 100.' },
    { table: SPEED_TABLE, source: SPEED_SOURCE, column: 'Mbps', id: 'fixed-internet-speed', label: 'Velocidad media de descarga de internet fijo', unit: 'Mbps', explanation: 'Promedio publicado por ENACOM para internet fijo; no mide calidad por persona, móvil ni habilidades digitales.' },
  ]) {
    try {
      const description = await openarg.call('describir_tabla', { tabla: spec.table }); cached &&= description.cached;
      const covered = /Período \(anio\):\s*(\d{4})\s+a\s+(\d{4})/.exec(description.text);
      if (!schema(description.text, spec.table, spec.source, ['anio', 'trimestre', 'provincia', spec.column]) || !covered) throw new Error('unverified_schema');
      const year = covered[2];
      const latest = await openarg.call('agregar_datos', { tabla: spec.table, operacion: 'maximo', columna: 'trimestre', desde: year, hasta: year, filtros: { provincia: population }, limite: 1 }); cached &&= latest.cached;
      const quarter = parsePublicScalar(latest.text, spec.table, 'maximo', 'trimestre', { integer: true });
      if (!quarter || quarter.value < 1 || quarter.value > 4 || quarter.rows > 4) throw new Error('unverified_period');
      const result = await openarg.call('agregar_datos', { tabla: spec.table, operacion: 'promedio', columna: spec.column, desde: year, hasta: year, filtros: { provincia: population, trimestre: quarter.value }, limite: 1 }); cached &&= result.cached;
      const scalar = parsePublicScalar(result.text, spec.table, 'promedio', spec.column, { oneRow: true });
      if (!scalar || !result.text.includes(spec.source)) throw new Error('unverified_value');
      const period = `${year} T${quarter.value}`;
      indicators.push({ id: `${spec.id}-${territoryId}`, label: spec.label, value: scalar.value, unit: spec.unit, period, territoryId,
        sourceUrl: spec.source, sourceTitle: 'ENACOM · Internet fija por provincia',
        freshness: { historical: false, dataPeriod: period, sourceReadAt: sourceRead(description.text), retrievedAt: result.retrievedAt },
        method: { tool: 'agregar_datos', table: spec.table, operation: 'promedio', rowsUsed: scalar.rows, note: `Una fila de la provincia y trimestre exactos. ${spec.explanation}` } });
      if (note(description.text)) warnings.push(note(description.text));
    } catch (error) { cached = false; warnings.push(`No se pudo verificar “${spec.label}” para este territorio (${error.code ?? 'unverified_data'}); se omite el valor.`); }
  }
  warnings.push('La conectividad provincial describe infraestructura, no acceso o habilidades de personas mayores ni resultados de NIETO. Los accesos por 100 hogares no son un porcentaje de hogares.');
  return { indicators, warnings, cached };
}

async function census(openarg, territoryId) {
  const spec = CENSUS_TABLES[territoryId];
  if (!spec) return { indicators: [], warnings: ['No hay tabla censal curada para este territorio; no se extrapolan cifras de otra provincia.'], cached: false };
  const [province, distribution, filename, suffix, raw] = spec;
  const table = `${raw ? 'raw.' : ''}${CENSUS_PREFIX}${suffix}`;
  const source = `https://infra.datos.gob.ar/catalog/indec/dataset/48/distribution/48.${distribution}/download/${filename}-2022.zip`;
  try {
    const description = await openarg.call('describir_tabla', { tabla: table });
    if (!schema(description.text, table, source, ['cod_prov', 'cod_variable', 'cod_categoria', 'categoria', 'cantidad', 'codigo']) || !description.text.includes('Censo Nacional de Población, Hogares y Viviendas 2022')) throw new Error('unverified_schema');
    const result = await openarg.call('agregar_datos', { tabla: table, operacion: 'suma', columna: 'cantidad',
      filtros: [{ columna: 'cod_prov', operador: '=', valor: String(province) }, { columna: 'cod_variable', operador: '=', valor: 'PERSONA_EDADQUI' }, { columna: 'categoria', operador: 'en', valores: SENIOR_CATEGORIES }], limite: 1 });
    const scalar = parsePublicScalar(result.text, table, 'suma', 'cantidad', { integer: true });
    if (!scalar || scalar.value <= 0 || !result.text.includes(source)) throw new Error('unverified_value');
    return { indicators: [{ id: `population-65-plus-2022-${territoryId}`, label: 'Personas de 65 años y más · Censo 2022', value: scalar.value, unit: 'personas', period: '2022', territoryId,
      sourceUrl: source, sourceTitle: 'INDEC · Censo Nacional 2022 por radio censal', freshness: { historical: true, dataPeriod: '2022', sourceReadAt: sourceRead(description.text), retrievedAt: result.retrievedAt },
      method: { tool: 'agregar_datos', table, operation: 'suma', rowsUsed: scalar.rows, note: 'Suma por radio censal de PERSONA_EDADQUI, categorías de 65 a 105 años y más. Solo provincia seleccionada; no se suman variables alternativas de edad.' } }],
      warnings: ['Censo 2022 es una referencia histórica de población, no una estimación actual de personas mayores ni evidencia de su acceso digital.', ...(note(description.text) ? [note(description.text)] : [])], cached: description.cached && result.cached };
  } catch (error) { return { indicators: [], warnings: [`No se pudo validar población mayor del Censo 2022 (${error.code ?? 'unverified_data'}); no se reemplaza por cifras estimadas.`], cached: false }; }
}

export async function getPublicTerritoryIndicators(openarg, territoryId) {
  // Two independent, bounded chains; each chain keeps dependent RPCs sequential.
  const [seniors, internet] = await Promise.all([census(openarg, territoryId), connectivity(openarg, territoryId)]);
  const indicators = [...seniors.indicators, ...internet.indicators];
  return { indicators, sources: indicators.map(indicator => ({ title: indicator.sourceTitle, url: indicator.sourceUrl })),
    warnings: [...new Set([...seniors.warnings, ...internet.warnings])], cached: seniors.cached && internet.cached,
    evidence: { provider: 'OpenArg MCP', method: 'describir_tabla + agregar_datos', valuesFromLiveAggregates: true, compatiblePeriodsRequired: true },
    gaps: ['No hay cruce verificado entre edad y conectividad individual.', 'No se proyecta población 2022 al presente ni se atribuye infraestructura provincial al impacto de NIETO.', 'Las tablas del catálogo pueden cambiar o incorporar datos posteriores a la última lectura.'],
    opportunities: [{ id: 'digital-support', title: 'Orientar acompañamiento digital para personas mayores', basis: indicators.filter(indicator => indicator.id.startsWith('population-65') || indicator.id.startsWith('fixed-internet-accesses')).map(indicator => indicator.id),
      limitation: 'Hipótesis de planificación: requiere relevamiento local. No identifica personas, demanda insatisfecha ni beneficiarios.' }].filter(opportunity => opportunity.basis.length >= 2) };
}
