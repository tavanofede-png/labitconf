import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {transform} from 'esbuild';

const source=await readFile(new URL('../src/jury-api.ts',import.meta.url),'utf8');
const {code}=await transform(source,{loader:'ts',format:'esm',target:'es2022'});
const {juryApi}=await import('data:text/javascript;base64,'+Buffer.from(code).toString('base64'));
const reply=(status,value)=>new Response(JSON.stringify(value),{status,headers:{'Content-Type':'application/json'}});

test('a temporary chat storage outage retries once with the same identity, payload and deadline',async()=>{
 const original=globalThis.fetch;const calls=[];
 globalThis.fetch=async(url,options)=>{calls.push({url,options});return calls.length===1?reply(503,{error:{code:'storage_unavailable'}}):reply(200,{instruction:'Te escucho.',audio:{mimeType:'audio/wav',base64:'test'}});};
 try {
  const result=await juryApi('chat',{text:'Hola TINO',voice:true});
  assert.equal(result.instruction,'Te escucho.');assert.equal(calls.length,2);
  assert.equal(calls[0].options.body,calls[1].options.body);
  assert.match(JSON.parse(calls[0].options.body).clientId,/^[a-f0-9-]{36}$/i);
  assert.equal(calls[0].options.signal,calls[1].options.signal);
 }finally{globalThis.fetch=original;}
});

test('provider errors and profile mutations cannot trigger an automatic second request',async()=>{
 const original=globalThis.fetch;let calls=0;
 try{
  for(const [resource,status,errorCode] of [['chat',502,'openai_response_incomplete'],['profile',503,'storage_unavailable']]){
   calls=0;globalThis.fetch=async()=>{calls++;return reply(status,{error:{code:errorCode,message:'Falló la prueba'}});};
   await assert.rejects(juryApi(resource,{text:'Hola TINO',role:'user'}),/Falló la prueba/);assert.equal(calls,1);
  }
 }finally{globalThis.fetch=original;}
});

test('cancelling a failed preflight prevents its retry and leaves the provider untouched',async()=>{
 const original=globalThis.fetch;const abort=new AbortController();let calls=0;
 globalThis.fetch=async()=>{calls++;abort.abort();return reply(503,{error:{code:'storage_unavailable'}});};
 try{await assert.rejects(juryApi('chat',{text:'Hola TINO'},'',abort.signal),error=>error.name==='AbortError');assert.equal(calls,1);}
 finally{globalThis.fetch=original;}
});
