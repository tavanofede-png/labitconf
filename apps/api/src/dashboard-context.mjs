import { readFileSync } from 'node:fs';
import { fetchDashboardSnapshot } from './openarg-dashboard.mjs';
const verified = JSON.parse(readFileSync(new URL('./public-data/openarg-national-verified.json', import.meta.url), 'utf8'));
export function createDashboardContext(openarg) {
  let cached = structuredClone(verified); let pending = null; let lastAttempt = 0;
  return async () => {
    if (!openarg.status().configured || Date.now() - Date.parse(cached.retrievedAt) < 86400000 || Date.now() - lastAttempt < 3600000) return cached;
    if (!pending) { lastAttempt = Date.now(); pending = fetchDashboardSnapshot(openarg).then(value => { cached = value; return cached; }).catch(() => cached).finally(() => { pending = null; }); }
    return pending;
  };
}
export function crossPublicContext(demo, context) {
  const scope = context.provinces.filter(p => demo.territoryId === 'AR' || p.territoryId === demo.territoryId);
  const population = scope.reduce((n,p) => n+p.population,0);
  const selected = demo.territories.filter(t => demo.territoryId === 'AR' || t.id === demo.territoryId);
  const participants = demo.metrics?.activeUsers ?? null;
  const simulated = demo.origin === 'tino_simulated';
  const indicators = [{id:'population-2022',territoryId:demo.territoryId,label:demo.territoryId === 'AR' ? 'Población censada · Argentina' : 'Población censada',value:population,unit:'personas',period:context.populationPeriod,sourceTitle:context.sources[0].title,sourceUrl:context.sources[0].url}];
  if (scope.length === 1) indicators.push({id:'internet-access',territoryId:scope[0].territoryId,label:'Accesos a Internet fijo por 100 hogares',value:scope[0].internetPer100Homes,unit:'accesos / 100 hogares',period:context.connectivityPeriod,sourceTitle:context.sources[1].title,sourceUrl:context.sources[1].url});
  return {...context,indicators,crosses:participants !== null ? [{territoryId:demo.territoryId,label:'Uso TINO por 10.000 habitantes · base censal 2022',value:Math.round(participants/population*10000*10)/10,numerator:participants,denominator:population,denominatorPeriod:'2022',unit:(simulated?'usuarios demo':'usuarios TINO')+' / 10.000 habitantes',sourceUrl:context.sources[0].url,note:(simulated?'Numerador TINO simulado. ':'')+'Base censal 2022: no mide cobertura poblacional actual ni impacto.'}] : [],
    territories:demo.territories.map(t=>{const p=context.provinces.find(p=>p.territoryId===t.id);return {...t, population:p?.population??null,populationPeriod:'2022',internetPer100Homes:p?.internetPer100Homes??null,connectivityPeriod:context.connectivityPeriod,usersPer10000:p && t.activeUsers != null?Math.round(t.activeUsers/p.population*100000)/10:null,requestsPer10000:p && t.requests != null?Math.round(t.requests/p.population*100000)/10:null};}),
    note:'Cruces por código provincial ISO. La población conserva su base 2022 y los accesos de ENACOM su trimestre; no se comparan períodos como si fueran equivalentes.',geography:{provider:'Georef / IGN',sourceUrl:'https://apis.datos.gob.ar/georef/api/provincias.geojson',coverage:'24 jurisdicciones'}};
}
