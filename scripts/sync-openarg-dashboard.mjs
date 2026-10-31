import { writeFile } from 'node:fs/promises';
import { OpenArgClient } from '../apps/api/src/openarg.mjs';
import { fetchDashboardSnapshot } from '../apps/api/src/openarg-dashboard.mjs';
const snapshot = await fetchDashboardSnapshot(new OpenArgClient());
await writeFile(new URL('../apps/api/src/public-data/openarg-national-verified.json', import.meta.url), JSON.stringify(snapshot, null, 2) + '\n');
console.log(`OpenARG verificado: ${snapshot.provinces.length} jurisdicciones · Censo ${snapshot.populationPeriod} · ENACOM ${snapshot.connectivityPeriod}.`);
