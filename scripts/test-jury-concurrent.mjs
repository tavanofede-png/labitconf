import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {transform} from 'esbuild';
const origin=process.env.JURY_TEST_ORIGIN||'http://127.0.0.1:5173';
const {code}=await transform(await readFile(new URL('../apps/web/src/jury-api.ts',import.meta.url),'utf8'),{loader:'ts',format:'esm',target:'es2022'});
const source='data:text/javascript;base64,'+Buffer.from(code).toString('base64');
// Separate module instances represent separate browser clients. Only resolve
// their relative URLs here: every request still reaches the real public API.
const nativeFetch=globalThis.fetch;
globalThis.fetch=(url,options)=>nativeFetch(new URL(url,origin),options);
const turns=[
 {text:'¿Cómo agrando la letra de Android? Respondé brevemente.',voice:false},
 {text:'¿Cómo empiezo a escribir un correo? Respondé brevemente.',voice:false},
 {text:'Hola TINO',voice:true},
];
const results=await Promise.allSettled(turns.map(async(turn,index)=>{
 const {juryApi}=await import(source+'#client-'+index);
 const started=Date.now();const data=await juryApi('chat',turn);assert.ok(data.instruction?.trim());
 if(turn.voice)assert.equal(data.audio?.mimeType,'audio/wav');else assert.equal(data.audio,undefined);
 return{voice:turn.voice,status:200,elapsedMs:Date.now()-started};
}));
globalThis.fetch=nativeFetch;
for(const result of results)if(result.status==='rejected')console.error(result.reason);
assert.equal(results.filter(result=>result.status==='rejected').length,0,'Every concurrent jury session must complete');
console.log('THREE_SEPARATE_JURY_SESSIONS_ON_ONE_NETWORK_PASS',JSON.stringify(results.map(result=>result.value)));
