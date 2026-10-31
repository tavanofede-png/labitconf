import {readFileSync,writeFileSync} from 'node:fs';
import {gzipSync} from 'node:zlib';
import {demoFamily,demoInstitution,DEMO_TERRITORIES} from '../apps/api/src/dashboard-demo.mjs';
import {crossPublicContext} from '../apps/api/src/dashboard-context.mjs';
import {TASK_FLOWS} from '../apps/api/src/assistant.mjs';
const publicData=JSON.parse(readFileSync(new URL('../apps/api/src/public-data/openarg-national-verified.json',import.meta.url)));
const now=Date.parse('2026-10-31T15:00:00Z');
const bundle={version:1,referenceAt:new Date(now).toISOString(),family:demoFamily(now),institutions:{},flows:Object.values(TASK_FLOWS)};
const monthKeys=demoInstitution({now}).availableMonths;
for(const territoryId of ['AR',...DEMO_TERRITORIES.map(t=>t.id)])for(const period of ['1','3','6',...monthKeys]){
 const data=demoInstitution({now,territoryId,...(period.length===7?{month:period}:{months:Number(period)})});
 const publicContext=crossPublicContext(data,publicData);
 bundle.institutions[`${territoryId}|${period}`]={...data,territories:publicContext.territories,publicContext};
}
const raw=Buffer.from(JSON.stringify(bundle));const encoded=gzipSync(raw,{level:9}).toString('base64');
writeFileSync(new URL('../apps/android/app/src/main/assets/jury-demo-v1.gz.b64',import.meta.url),encoded+'\n');
console.log(JSON.stringify({datasets:Object.keys(bundle.institutions).length,uncompressed:raw.length,encoded:encoded.length,publicDataRetrievedAt:publicData.retrievedAt}));
