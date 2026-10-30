// Curated mapping discovered through the live OpenArg MCP on 2026-10-30.
// Values are never stored here: each value must come from agregar_datos.
export const CABA_POPULATION_TABLE = 'raw.caba__estructura_de_la_poblacion__967ab1ab__v4';
export const CABA_POPULATION_SOURCE = 'https://cdn.buenosaires.gob.ar/datosabiertos/datasets/direccion-general-de-estadisticas-y-censos/estructura-poblacion/pob_cen_sexo__annio__l_nac__g_edad_limpio.xlsx';
const SOURCE_TITLE = 'Estructura de la Población · Buenos Aires Data (CABA)';
const PERIOD = '2010';
const SENIOR_AGES = ['65 - 69', '70 - 74', '75 - 79', '80 - 84', '85 y más'];
const COMMON_FILTERS = [
  { columna: 'sexo', operador: 'en', valores: ['m', 'v'] },
  { columna: 'nacionalidad', operador: 'en', valores: ['Nativo', 'Extranjero'] },
];
const HISTORICAL_WARNING = 'Los indicadores CABA corresponden al Censo 2010: son contexto histórico, no población actual ni una medida del impacto actual de NIETO. La lectura reciente de una fuente no actualiza el período censal.';

function verifyDescription(text) {
  const covered = /Período \(anio\):\s*(\d{4})\s+a\s+(\d{4})/.exec(text);
  const requiredColumns = ['anio', 'sexo', 'grupo_edad', 'nacionalidad', 'cant_poblacion'];
  return text.includes(`tabla \`${CABA_POPULATION_TABLE}\``) && covered &&
    Number(covered[1]) <= Number(PERIOD) && Number(covered[2]) >= Number(PERIOD) &&
    requiredColumns.every((column) => text.includes(`- ${column} (`));
}

export function parsePopulationAggregate(text, expectedRows) {
  // Read the scalar that OpenArg calculated. Never calculate a sum from CSV rows.
  const csv = /```csv\s*\r?\nvalor,filas_usadas\r?\n([0-9]+(?:\.[0-9]+)?),(\d+)\s*\r?\n```/.exec(text);
  if (!text.includes(`\`${CABA_POPULATION_TABLE}\``) || !text.startsWith('suma de cant_poblacion') || !csv) return null;
  const value = Number(csv[1]); const rows = Number(csv[2]);
  if (!Number.isSafeInteger(value) || value <= 0 || rows !== expectedRows) return null;
  return { value, rows };
}

export async function getTerritoryIndicators(openarg, territoryId) {
  if (territoryId !== 'AR-C') return { indicators: [], sources: [], cached: true,
    warnings: ['No hay indicadores numéricos curados y verificados para este territorio. Las coincidencias del catálogo requieren revisar cobertura y período en la fuente.'] };
  let description;
  try {
    description = await openarg.call('describir_tabla', { tabla: CABA_POPULATION_TABLE });
  } catch (error) {
    return { indicators: [], sources: [], cached: false, warnings: [`No se pudo verificar la tabla histórica CABA (${error.code ?? 'upstream_error'}). No se muestran valores de reemplazo.`] };
  }
  if (!verifyDescription(description.text)) return { indicators: [], sources: [], cached: false,
    warnings: ['La tabla CABA cambió de columnas o no acredita el período 2010. No se muestran indicadores hasta verificar su estructura.'] };
  const sourceReadAt = /Leída de la fuente por OpenArg:\s*(\d{4}-\d{2}-\d{2})/.exec(description.text)?.[1] ?? null;
  const warnings = [HISTORICAL_WARNING];
  const freshnessNotice = description.text.split('\n').find((line) => line.startsWith('Nota:'));
  if (freshnessNotice) warnings.push(freshnessNotice.replace(/^Nota:\s*/, ''));
  const indicators = []; let cached = description.cached;
  const specifications = [
    { id: 'caba-population-65-plus-2010', label: 'Personas de 65 años y más · Censo 2010', expectedRows: 20,
      ageFilter: { columna: 'grupo_edad', operador: 'en', valores: SENIOR_AGES } },
    { id: 'caba-population-total-2010', label: 'Población total · Censo 2010', expectedRows: 4,
      ageFilter: { columna: 'grupo_edad', operador: '=', valor: 'TOTAL' } },
  ];
  for (const spec of specifications) {
    try {
      const result = await openarg.call('agregar_datos', { tabla: CABA_POPULATION_TABLE, operacion: 'suma', columna: 'cant_poblacion',
        filtros: [spec.ageFilter, ...COMMON_FILTERS], desde: PERIOD, hasta: PERIOD, limite: 1 });
      cached = cached && result.cached;
      const scalar = parsePopulationAggregate(result.text, spec.expectedRows);
      if (!scalar) { cached = false; warnings.push(`No se pudo validar el agregado de “${spec.label}”. Se omite ese valor.`); continue; }
      indicators.push({ id: spec.id, label: spec.label, value: scalar.value, unit: 'personas', period: PERIOD,
        territoryId: 'AR-C', sourceUrl: CABA_POPULATION_SOURCE, sourceTitle: SOURCE_TITLE,
        freshness: { historical: true, dataPeriod: PERIOD, sourceReadAt, retrievedAt: result.retrievedAt },
        method: { tool: 'agregar_datos', table: CABA_POPULATION_TABLE, operation: 'suma', rowsUsed: scalar.rows,
          note: spec.expectedRows === 20 ? 'Cinco grupos de edad de 65 años y más, ambos sexos y dos categorías de nacionalidad. Excluye las filas TOTAL.' : 'Solo cuatro filas TOTAL por sexo y nacionalidad; no se suman nuevamente los grupos de edad.' } });
    } catch (error) {
      cached = false; warnings.push(`El agregado de “${spec.label}” no estuvo disponible (${error.code ?? 'upstream_error'}). No se reemplazó por un dato guardado.`);
    }
  }
  return { indicators, sources: indicators.length ? [{ title: SOURCE_TITLE, url: CABA_POPULATION_SOURCE }] : [], warnings, cached };
}
