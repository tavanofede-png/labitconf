import { DEMO_TERRITORIES } from './dashboard-demo.mjs';
export const POPULATION_TABLE = 'raw.cordoba_estadistica__censo_2022__9e3466dc__v3';
export const CONNECTIVITY_TABLE = 'cache_datos_gob_ar_internet_fija_r18e293e7ce';
const POPULATION_URL = 'https://datosestadistica.cba.gov.ar/dataset/a047806e-e19a-4cdd-b800-b544d436f231/resource/ffaff001-83fd-4416-ad5f-13c33cc117b8/download/cnphv2022_resultados_definitivos_cordoba-1.xlsx';
const INTERNET_URL = 'https://indicadores.enacom.gob.ar/Files/DatosAbiertos/internet_accesos_penetracion_provincias.csv';
const normalize = s => s.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase().replace(/\([^)]*\)/g, '').replace(/[^a-z]/g, '');
const alias = new Map(DEMO_TERRITORIES.map(t => [normalize(t.name), t.id]));
alias.set('caba', 'AR-C'); alias.set('ciudadautonomadebuenosaires', 'AR-C'); alias.set('tierradelfuego', 'AR-V');
alias.set('tierradelfuegoantartidaeislasdelatlanticosur', 'AR-V');

export function parseCsvBlock(text) {
  const csv = /```csv\s*\r?\n([\s\S]*?)```/.exec(text)?.[1];
  if (!csv) throw new Error('OpenARG no devolvió CSV verificable');
  const rows = []; let row = []; let value = ''; let quoted = false;
  for (let i = 0; i < csv.length; i++) {
    const c = csv[i];
    if (c === '"') { if (quoted && csv[i + 1] === '"') { value += '"'; i++; } else quoted = !quoted; }
    else if (c === ',' && !quoted) { row.push(value); value = ''; }
    else if (c === '\n' && !quoted) { row.push(value.replace(/\r$/, '')); rows.push(row); row = []; value = ''; }
    else value += c;
  }
  if (quoted) throw new Error('CSV incompleto');
  if (value || row.length) rows.push([...row, value.replace(/\r$/, '')]);
  const headers = rows.shift();
  return rows.filter(r => r.some(Boolean)).map(r => Object.fromEntries(headers.map((h, i) => [h.trim(), r[i] ?? ''])));
}
export function buildPublicSnapshot(population, connectivity, descriptions = {}) {
  if (!population.text.includes('`' + POPULATION_TABLE + '`') || !connectivity.text.includes('`' + CONNECTIVITY_TABLE + '`')) throw new Error('Tabla de origen incorrecta');
  const popRows = parseCsvBlock(population.text);
  const censusTotal = Number(popRows.find(r => r['Jurisdicción'] === 'Total')?.['Total de población']);
  const provincePopulation = new Map();
  for (const row of popRows) {
    const id = alias.get(normalize(row['Jurisdicción'] ?? '')); const value = Number(row['Total de población']);
    if (!id) continue;
    if (provincePopulation.has(id) || !Number.isSafeInteger(value) || value <= 0) throw new Error('Población incompleta o duplicada');
    provincePopulation.set(id, value);
  }
  if (provincePopulation.size !== 24 || [...provincePopulation.values()].reduce((a, b) => a + b, 0) !== censusTotal) throw new Error('La población provincial no concilia con el total censal');
  const internetRows = parseCsvBlock(connectivity.text);
  const latest = Math.max(...internetRows.map(r => Number(r.anio) * 10 + Number(r.trimestre)));
  const selected = internetRows.filter(r => Number(r.anio) * 10 + Number(r.trimestre) === latest);
  const internet = new Map();
  for (const row of selected) {
    const id = alias.get(normalize(row.provincia ?? '')); const homes = Number(row.accesos_cada_100_hogares); const people = Number(row.accesos_cada_100_habitantes);
    if (!id || internet.has(id) || !Number.isFinite(homes) || homes <= 0 || !Number.isFinite(people) || people <= 0) throw new Error('Conectividad incompleta o duplicada');
    internet.set(id, { homes, people });
  }
  if (internet.size !== 24 || latest % 10 < 1 || latest % 10 > 4) throw new Error('Cobertura de conectividad incompleta');
  const period = `${Math.floor(latest / 10)} T${latest % 10}`;
  return { provider: 'OpenARG', status: 'verified_snapshot', retrievedAt: population.retrievedAt, verifiedOn: population.retrievedAt.slice(0, 10),
    populationPeriod: '2022', connectivityPeriod: period,
    sources: [
      { title: 'INDEC · Censo 2022, resultados definitivos · publicación DGEyC Córdoba', url: POPULATION_URL, period: '2022', table: POPULATION_TABLE, retrievedAt: population.retrievedAt, readAt: descriptions.population?.match(/Leída de la fuente por OpenArg:\s*(\d{4}-\d{2}-\d{2})/)?.[1] ?? null },
      { title: 'ENACOM · Accesos a Internet fijo por provincia', url: INTERNET_URL, period, table: CONNECTIVITY_TABLE, retrievedAt: connectivity.retrievedAt, readAt: descriptions.connectivity?.match(/Leída de la fuente por OpenArg:\s*(\d{4}-\d{2}-\d{2})/)?.[1] ?? null }
    ],
    nationalPopulation: censusTotal,
    provinces: DEMO_TERRITORIES.map(t => ({ territoryId: t.id, name: t.name, population: provincePopulation.get(t.id), populationPeriod: '2022',
      internetPer100Homes: internet.get(t.id).homes, internetPer100People: internet.get(t.id).people, connectivityPeriod: period })),
    notes: ['La población corresponde al Censo 2022, no a una estimación actual.', 'ENACOM mide accesos por 100 hogares: no es porcentaje de hogares conectados y puede superar 100.', 'Conectividad, edad y actividad no permiten inferir discapacidad ni causalidad.'] };
}
export async function fetchDashboardSnapshot(client) {
  const populationDescription = await client.call('describir_tabla', { tabla: POPULATION_TABLE });
  const internetDescription = await client.call('describir_tabla', { tabla: CONNECTIVITY_TABLE });
  if (!populationDescription.text.includes('- Total de población (') || !internetDescription.text.includes('- accesos_cada_100_hogares (')) throw new Error('Cambió el esquema de OpenARG');
  const population = await client.call('obtener_datos', { tabla: POPULATION_TABLE, columnas: ['Jurisdicción', 'Total de población'], limite: 100 });
  const latestYear = Number(/Período \(anio\):\s*\d{4}\s+a\s+(\d{4})/.exec(internetDescription.text)?.[1]);
  if (!Number.isInteger(latestYear)) throw new Error('No se pudo acreditar el año de conectividad');
  const connectivity = await client.call('obtener_datos', { tabla: CONNECTIVITY_TABLE, columnas: ['anio', 'trimestre', 'provincia', 'accesos_cada_100_hogares', 'accesos_cada_100_habitantes'], desde: String(latestYear), hasta: String(latestYear), limite: 100 });
  return buildPublicSnapshot(population, connectivity, { population: populationDescription.text, connectivity: internetDescription.text });
}
