import test from 'node:test';
import assert from 'node:assert/strict';
import { getPublicTerritoryIndicators, INTERNET_TABLE, SPEED_TABLE, parsePublicScalar } from '../src/public-indicators.mjs';

const censusSource = 'https://infra.datos.gob.ar/catalog/indec/dataset/48/distribution/48.17/download/66-salta-2022.zip';
const accessSource = 'https://indicadores.enacom.gob.ar/Files/DatosAbiertos/internet_accesos_penetracion_provincias.xlsx';
const speedSource = 'https://indicadores.enacom.gob.ar/Files/DatosAbiertos/internet_velocidad_media_descarga_provincias.xlsx';
const censusTable = 'cache_datos_gob_ar_censo_nacional_de_poblaci_n_hoga_r26c1b369bd';
const scalar = (table, operation, column, value, rows = 1, source = table === censusTable ? censusSource : table === INTERNET_TABLE ? accessSource : speedSource) => `${operation} de ${column} en \`${table}\` sobre ${rows} filas, calculado por OpenArg. Fuente: ${source}\n\`\`\`csv\nvalor,filas_usadas\n${value},${rows}\n\`\`\``;
function fake({ badRows = false, badSchema = false } = {}) {
  const calls = [];
  return { calls, async call(tool, args) {
    calls.push({ tool, args });
    const census = args.tabla === censusTable; const source = census ? censusSource : args.tabla === INTERNET_TABLE ? accessSource : speedSource;
    let text;
    if (tool === 'describir_tabla') {
      const columns = census ? ['cod_prov', 'cod_variable', 'cod_categoria', 'categoria', 'cantidad', 'codigo'] : ['anio', 'trimestre', 'provincia', args.tabla === INTERNET_TABLE ? 'accesos_cada_100_hogares' : 'Mbps'];
      text = `Censo Nacional de Población, Hogares y Viviendas 2022 ${source} tabla \`${args.tabla}\`\nPeríodo (anio): 2014 a 2026\nLeída de la fuente por OpenArg: 2026-07-30\n${(badSchema ? columns.slice(1) : columns).map(column => `- ${column} (text)`).join('\n')}`;
    } else text = scalar(args.tabla, args.operacion, args.columna, census ? 118445 : args.columna === 'trimestre' ? 1 : args.tabla === INTERNET_TABLE ? 71.97 : 165.09, census ? 9226 : badRows && args.operacion === 'promedio' ? 2 : 1);
    return { text, cached: false, retrievedAt: '2026-10-30T12:00:00Z' };
  } };
}

test('territorial values come from verified MCP aggregates for exact province and period', async () => {
  const client = fake(); const result = await getPublicTerritoryIndicators(client, 'AR-A');
  assert.equal(result.indicators.length, 3); assert.deepEqual(result.indicators.map(entry => entry.value), [118445, 71.97, 165.09]);
  assert.equal(result.indicators[0].period, '2022'); assert.equal(result.indicators[1].period, '2026 T1');
  assert.ok(result.indicators.every(entry => entry.territoryId === 'AR-A' && entry.sourceUrl.startsWith('https:')));
  const census = client.calls.find(entry => entry.args.columna === 'cantidad');
  assert.equal(census.args.filtros[0].valor, '66'); assert.equal(census.args.filtros[1].valor, 'PERSONA_EDADQUI'); assert.equal(census.args.filtros[2].valores.includes('TOTAL'), false);
  const internet = client.calls.find(entry => entry.args.columna === 'accesos_cada_100_hogares');
  assert.deepEqual(internet.args.filtros, { provincia: 'Salta', trimestre: 1 }); assert.equal(internet.args.desde, '2026');
  assert.equal(result.opportunities.length, 1); assert.ok(result.gaps.length);
});

test('schema changes and duplicate province-quarter rows suppress values instead of estimating', async () => {
  const invalid = fake({ badSchema: true }); const absent = await getPublicTerritoryIndicators(invalid, 'AR-A');
  assert.deepEqual(absent.indicators, []); assert.equal(invalid.calls.some(entry => entry.tool === 'agregar_datos'), false);
  const duplicate = await getPublicTerritoryIndicators(fake({ badRows: true }), 'AR-A');
  assert.equal(duplicate.indicators.length, 1); assert.equal(duplicate.indicators[0].period, '2022');
});

test('an uncurated census territory never reuses CABA or another provincial population', async () => {
  const client = fake(); const result = await getPublicTerritoryIndicators(client, 'AR-Y');
  assert.equal(result.indicators.some(entry => entry.id.startsWith('population-65')), false);
  assert.equal(client.calls.some(entry => entry.args.tabla === censusTable), false);
  assert.ok(result.indicators.every(entry => entry.territoryId === 'AR-Y'));
});

test('public scalar rejects wrong table, missing cells and invalid cardinality', () => {
  assert.equal(parsePublicScalar(scalar(INTERNET_TABLE, 'promedio', 'accesos_cada_100_hogares', 123, 2), INTERNET_TABLE, 'promedio', 'accesos_cada_100_hogares', { oneRow: true }), null);
  assert.equal(parsePublicScalar('Sin resultado', INTERNET_TABLE, 'promedio', 'accesos_cada_100_hogares'), null);
  assert.equal(parsePublicScalar(scalar(SPEED_TABLE, 'promedio', 'Mbps', 10), INTERNET_TABLE, 'promedio', 'Mbps'), null);
});
