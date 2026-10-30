import test from 'node:test';
import assert from 'node:assert/strict';
import { CABA_POPULATION_TABLE, getTerritoryIndicators, parsePopulationAggregate } from '../src/territory.mjs';

// Public response shapes observed against the live OpenArg MCP, with historical values.
const description = `**Estructura de la Población** — tabla \`${CABA_POPULATION_TABLE}\`
Período (anio): 1855 a 2010
Frescura: Último dato: 2010 · Leída de la fuente por OpenArg: 2026-05-09
Columnas:
- anio (bigint)
- sexo (text)
- grupo_edad (text)
- nacionalidad (text)
- cant_poblacion (double precision)`;
const aggregate = (value, rows) => `suma de cant_poblacion en \`${CABA_POPULATION_TABLE}\` sobre ${rows} filas, calculado por OpenArg. Fuente: Estructura de la Población\n\`\`\`csv\nvalor,filas_usadas\n${value},${rows}\n\`\`\``;

function client({ descriptionText = description, badRows = false } = {}) {
  const calls = [];
  return { calls, call: async (tool, args) => {
    calls.push({ tool, args });
    if (tool === 'describir_tabla') return { text: descriptionText, cached: true };
    const seniors = args.filtros[0].operador === 'en';
    return { text: aggregate(seniors ? '474071.0' : '2890151.0', badRows ? 3 : (seniors ? 20 : 4)), retrievedAt: '2026-10-30T00:00:00Z', cached: true };
  } };
}

test('curated territory uses verified server aggregates with explicit historical period and no duplicate TOTAL', async () => {
  const openarg = client(); const context = await getTerritoryIndicators(openarg, 'AR-C');
  assert.deepEqual(context.indicators.map((i) => i.value), [474071, 2890151]);
  assert.ok(context.indicators.every((i) => i.period === '2010' && i.freshness.historical && i.freshness.sourceReadAt === '2026-05-09'));
  assert.equal(openarg.calls[0].tool, 'describir_tabla');
  const [seniorArgs, totalArgs] = openarg.calls.slice(1).map((call) => call.args);
  assert.equal(seniorArgs.desde, '2010'); assert.equal(seniorArgs.hasta, '2010');
  assert.deepEqual(seniorArgs.filtros[0].valores, ['65 - 69', '70 - 74', '75 - 79', '80 - 84', '85 y más']);
  assert.equal(totalArgs.filtros[0].valor, 'TOTAL');
  assert.equal(context.sources.length, 1); assert.equal(context.cached, true);
});

test('different territory cannot accidentally reuse CABA values', async () => {
  const openarg = client(); const result = await getTerritoryIndicators(openarg, 'AR-B');
  assert.deepEqual(result.indicators, []); assert.equal(openarg.calls.length, 0);
});

test('changed schema or unavailable period suppresses indicators before requesting aggregates', async () => {
  const openarg = client({ descriptionText: description.replace('1855 a 2010', '1855 a 2001') });
  assert.deepEqual((await getTerritoryIndicators(openarg, 'AR-C')).indicators, []); assert.equal(openarg.calls.length, 1);
});

test('missing source cells or unexpected aggregate cardinality never become a zero or fallback number', async () => {
  const context = await getTerritoryIndicators(client({ badRows: true }), 'AR-C');
  assert.deepEqual(context.indicators, []); assert.equal(parsePopulationAggregate('descripción 474071', 20), null);
  assert.equal(parsePopulationAggregate(aggregate('0', 20), 20), null);
  assert.equal(parsePopulationAggregate(aggregate('474071.0', 19), 20), null);
});
