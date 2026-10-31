import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
const origin=process.env.JURY_TEST_ORIGIN||'http://127.0.0.1:5173';
const turns=[
 {text:'¿Cómo agrando la letra de Android? Respondé brevemente.',voice:false},
 {text:'¿Cómo empiezo a escribir un correo? Respondé brevemente.',voice:false},
 {text:'Hola TINO',voice:true},
];
const results=await Promise.allSettled(turns.map(async turn=>{
 const started=Date.now();const response=await fetch(origin+'/api/jury?resource=chat',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({...turn,clientId:randomUUID()}),signal:AbortSignal.timeout(65000)});
 const data=await response.json();assert.equal(response.status,200,JSON.stringify({voice:turn.voice,status:response.status,code:data.code,error:data.error}));assert.ok(data.instruction?.trim());
 if(turn.voice)assert.equal(data.audio?.mimeType,'audio/wav');else assert.equal(data.audio,undefined);
 return{voice:turn.voice,status:response.status,elapsedMs:Date.now()-started};
}));
for(const result of results)if(result.status==='rejected')console.error(result.reason);
assert.equal(results.filter(result=>result.status==='rejected').length,0,'Every concurrent jury session must complete');
console.log('THREE_SEPARATE_JURY_SESSIONS_ON_ONE_NETWORK_PASS',JSON.stringify(results.map(result=>result.value)));
