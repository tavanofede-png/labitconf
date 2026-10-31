import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {createServer} from '../src/server.mjs';
import {OpenArgClient} from '../src/openarg.mjs';
import {familyVoice} from './fixtures/family-voice.mjs';

async function fixture(t){
 const dir=await mkdtemp(join(tmpdir(),'tino-family-messages-'));let clock=Date.parse('2026-10-31T15:00:00Z');
 const server=createServer({institutionToken:'operator-test',dataFile:join(dir,'state.json'),now:()=>clock,openarg:new OpenArgClient({apiKey:''}),accountOptions:{judgesDemo:true}});
 await new Promise(r=>server.listen(0,'127.0.0.1',r));
 t.after(async()=>{server.closeRealtime();await new Promise(r=>server.close(r));await rm(dir,{recursive:true,force:true})});
 async function call(path,token,body,method=body?'POST':'GET'){
  if(path==='/devices' && body) { const invite=await call('/institution/device-invitations','operator-test',{});body={...body,enrollmentCode:invite.data.enrollmentCode}; }
  const r=await fetch(`http://127.0.0.1:${server.address().port}/api${path}`,{method,headers:{...(body?{'Content-Type':'application/json'}:{}),...(token?{Authorization:`Bearer ${token}`}:{})},...(body?{body:JSON.stringify(body)}:{})});
  return {status:r.status,data:await r.json()};
 }
 return {call,advance:ms=>{clock+=ms}};
}

test('ping and valid Android AAC reach only the consenting recipient, retry once and expire',async t=>{
 const {call,advance}=await fixture(t);
 const person=(await call('/devices',null,{name:'Destinataria de prueba',territoryId:'AR-C'})).data;
 const other=(await call('/devices',null,{name:'Otra persona',territoryId:'AR-C'})).data;
 const group=(await call('/family/groups',null,{name:'Familia de prueba'})).data;
 const stranger=(await call('/family/groups',null,{name:'Otro grupo'})).data;
 assert.equal((await call('/family/link',person.token,{invitationCode:group.invitationCode,permissions:{progress:false,help:true}})).status,200);
 const ping={id:'ping-roundtrip-001',memberId:person.deviceId,type:'ping'};
 const voice={id:'voice-roundtrip-001',memberId:person.deviceId,type:'voice',audioBase64:familyVoice};
 assert.equal((await call('/family/messages',group.caregiverToken,ping)).status,201);
 assert.equal((await call('/family/messages',group.caregiverToken,voice)).status,201);
 assert.equal((await call('/family/messages',group.caregiverToken,voice)).status,201);
 const received=(await call('/family/messages',person.token)).data.messages;
 assert.equal(received.length,2);assert.equal(received.find(m=>m.type==='voice').audioBase64,familyVoice);
 assert.equal(received.find(m=>m.type==='ping').author,'Familia de prueba');
 assert.equal((await call('/family/messages',other.token)).data.messages.length,0);
 assert.equal((await call('/family/messages',stranger.caregiverToken,voice)).status,403);
 assert.equal((await call('/family/messages',group.caregiverToken,{...ping,id:voice.id})).status,409);
 const sent=(await call('/family/summary',group.caregiverToken)).data.messages;
 assert.equal(sent.length,2);assert.equal(sent.find(m=>m.type==='voice').audioBase64,familyVoice);
 assert.equal((await call('/family/link',person.token,{permissions:{help:false}},'PATCH')).status,200);
 assert.equal((await call('/family/messages',person.token)).data.messages.length,0);
 assert.equal((await call('/family/messages',group.caregiverToken,{...voice,id:'voice-after-optout'})).status,403);
 await call('/family/link',person.token,{permissions:{help:true}},'PATCH');advance(86400001);
 assert.equal((await call('/family/messages',person.token)).data.messages.length,0);
});

test('demo ping and playable voice persist per household while Lucia refuses both',async t=>{
 const {call}=await fixture(t);
 async function login(email){
  const challenge=(await call('/auth/code/request',null,{email,role:'family',purpose:'login'})).data;
  return (await call('/auth/code/verify',null,{challengeId:challenge.challengeId,code:'000000'})).data.sessionToken;
 }
 const token=await login('test@labitconf.com');const other=await login('another-family@example.com');
 for(const memberId of ['marta','ricardo'])for(const type of ['ping','voice']){
  const body={id:`demo-${memberId}-${type}`,memberId,type,...(type==='voice'?{audioBase64:familyVoice}:{})};
  assert.equal((await call('/demo/family/actions',token,body)).status,200);
  assert.equal((await call('/demo/family/actions',token,body)).status,200);
 }
 const sent=(await call('/demo/family/dashboard',token)).data.messages;
 assert.equal(sent.length,4);assert.equal(sent.filter(m=>m.type==='voice').length,2);
 assert.ok(sent.filter(m=>m.type==='voice').every(m=>m.audioBase64===familyVoice));
 assert.equal((await call('/demo/family/dashboard',other)).data.messages.length,0);
 assert.equal((await call('/demo/family/actions',token,{id:'demo-marta-voice',memberId:'marta',type:'ping'})).status,409);
 for(const type of ['ping','voice']) assert.equal((await call('/demo/family/actions',token,{id:`blocked-lucia-${type}`,memberId:'lucia',type,...(type==='voice'?{audioBase64:familyVoice}:{})})).status,403);
});
