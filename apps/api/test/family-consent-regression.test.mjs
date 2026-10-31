import test from 'node:test';
import {randomUUID} from 'node:crypto';
import assert from 'node:assert/strict';
import {mkdtemp,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {createServer} from '../src/server.mjs';
import {OpenArgClient} from '../src/openarg.mjs';
import {demoFamily} from '../src/dashboard-demo.mjs';
import {summarize,institutionOverview,periodFor} from '../src/institution.mjs';

test('a real family link supports granular opt-in, pause, expiring approximate location and complete revocation',async t=>{
 const dir=await mkdtemp(join(tmpdir(),'tino-consent-'));let clock=Date.parse('2026-10-31T15:00:00Z');
 const server=createServer({institutionToken:'operator-test',dataFile:join(dir,'state.json'),now:()=>clock,openarg:new OpenArgClient({apiKey:''})});
 await new Promise(r=>server.listen(0,'127.0.0.1',r));t.after(async()=>{await new Promise(r=>server.close(r));await rm(dir,{recursive:true,force:true});});
 async function call(path,method='GET',body,token){if(path==='/devices' && body){const invite=await call('/institution/device-invitations','POST',{},'operator-test');body={...body,enrollmentCode:invite.data.enrollmentCode};}const r=await fetch(`http://127.0.0.1:${server.address().port}/api${path}`,{method,headers:{...(body?{'Content-Type':'application/json'}:{}),...(token?{Authorization:`Bearer ${token}`}:{})},...(body?{body:JSON.stringify(body)}:{})});return {status:r.status,data:await r.json()};}
 const user=(await call('/devices','POST',{name:'Persona ficticia',territoryId:'AR-C'})).data;
 const group=(await call('/family/groups','POST',{name:'Grupo de prueba'})).data;
 const perms={progress:true,help:true,usage:false,safety:true,websites:false,location:true};
 assert.equal((await call('/family/link','POST',{invitationCode:group.invitationCode,permissions:perms},user.token)).status,200);
 const session=(await call('/assistant/sessions','POST',{flowId:'whatsapp-group',clientSessionId:randomUUID()},user.token)).data;
 const events=[{type:'flow_started'},...['open_whatsapp','new_group','select_people','name_group','review_group','check_group'].flatMap(stepId=>[{type:'step_shown',stepId},{type:'step_completed',stepId}]),{type:'flow_reported',durationSeconds:60}];
 for(const event of events)assert.equal((await call(`/assistant/sessions/${session.id}/events`,'POST',{eventId:randomUUID(),...event},user.token)).status,200);
 const reported=(await call('/family/summary','GET',undefined,group.caregiverToken)).data.members[0];
 assert.equal(reported.helpRequests,1);assert.equal(reported.activities[0].helpRequested,true);assert.equal(reported.activities[0].resultSource,'help_requested');
 assert.match(reported.activities[0].title,/^Ayuda para crear un grupo/);assert.doesNotMatch(reported.activities[0].title,/creó|completó|por su cuenta/i);
 const visual=(await call('/assistant/sessions','POST',{flowId:'screen-assistance',clientSessionId:randomUUID()},user.token)).data;
 for(const type of ['flow_started','assistance_ended','cancelled']) assert.equal((await call(`/assistant/sessions/${visual.id}/events`,'POST',{eventId:randomUUID(),type},user.token)).status,200);
 const partial=(await call('/family/summary','GET',undefined,group.caregiverToken)).data.members[0].activities[0];
 assert.equal(partial.assistanceLevel,'partial');assert.equal(partial.assistanceEndReason,'user_continued_alone');
 assert.equal(partial.outcome,'not_reported');assert.equal(partial.resultSource,'help_requested');
 assert.doesNotMatch(partial.title,/creado|enviado|completado/i);
 assert.equal((await call('/family/location','POST',{latitude:-34.603722,longitude:-58.381592,minutes:120},user.token)).status,400);
 assert.equal((await call('/family/location','POST',{latitude:-34.603722,longitude:-58.381592,minutes:15},user.token)).status,200);
 let snapshot=(await call('/family/summary','GET',undefined,group.caregiverToken)).data;
 assert.deepEqual(snapshot.members[0].location.point,[-58.382,-34.604]);
 assert.equal(snapshot.members[0].permissions.websites,false);
 assert.equal((await call('/family/link','PATCH',{paused:true},user.token)).status,200);
 snapshot=(await call('/family/summary','GET',undefined,group.caregiverToken)).data;
 assert.equal(snapshot.members[0].location,null);assert.equal(snapshot.members[0].permissions.progress,false);
 assert.equal((await call('/family/messages','POST',{id:'paused-ping',memberId:user.deviceId,type:'ping'},group.caregiverToken)).status,403);
 assert.equal((await call('/family/help','POST',{},user.token)).status,403);
 await call('/family/link','PATCH',{paused:false,permissions:{help:false,location:true}},user.token);
 assert.equal((await call('/family/messages','POST',{id:'denied-ping',memberId:user.deviceId,type:'ping'},group.caregiverToken)).status,403);
 await call('/family/location','POST',{latitude:-34.6,longitude:-58.4,minutes:15},user.token);clock+=16*60000;
 assert.equal((await call('/family/summary','GET',undefined,group.caregiverToken)).data.members[0].location,null);
 assert.equal((await call('/family/link','DELETE',undefined,user.token)).status,200);
 assert.equal((await call('/family/summary','GET',undefined,group.caregiverToken)).data.members.length,0);
});

test('institution routes exclude demo and opt-out activity even when the nominal cohort reaches five',()=>{
 const now=Date.parse('2026-10-31T15:00:00Z');
 for(const demo of [true,false]) {
  const state={users:Array.from({length:5},(_,i)=>({id:`u${i}`,territoryId:'AR-C',demo,institutionMetricsConsent:demo})),accounts:[],sessions:Array.from({length:5},(_,i)=>({userId:`u${i}`,createdAt:new Date(now-1000).toISOString(),state:'active',eventCounts:{flow_started:1},completedSteps:[],durationSeconds:20})),alerts:[],usage:[],safety:[]};
  assert.equal(summarize(state,'AR-C',periodFor(30,now),5).suppressed,true);
  assert.equal(institutionOverview(state,'AR-C',now,5).suppressed,true);
 }
});

test('today metrics do not count fictional activity from yesterday after Argentine midnight',()=>{
 const now=Date.parse('2026-10-31T03:05:00Z');const data=demoFamily(now,now);
 const day=t=>new Intl.DateTimeFormat('en-CA',{timeZone:'America/Argentina/Buenos_Aires'}).format(new Date(t));
 for(const person of data.members.filter(m=>m.permissions.progress)){const today=person.activities.filter(e=>day(e.at)===day(now));assert.equal(person.screenMinutes,today.reduce((n,e)=>n+e.durationMinutes,0));assert.equal(person.tinoMinutes,today.reduce((n,e)=>n+e.assistedMinutes,0));assert.equal(person.weeklyScreen.at(-1),person.screenMinutes);}
});
