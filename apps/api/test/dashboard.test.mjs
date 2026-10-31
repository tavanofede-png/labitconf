import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { readFileSync } from 'node:fs';
import { createServer } from '../src/server.mjs';
import { OpenArgClient } from '../src/openarg.mjs';
import { demoInstitution, demoFamily, ageOn } from '../src/dashboard-demo.mjs';
import { crossPublicContext } from '../src/dashboard-context.mjs';
import { parseCsvBlock, buildPublicSnapshot, POPULATION_TABLE, CONNECTIVITY_TABLE } from '../src/openarg-dashboard.mjs';
import { createModelAdapter } from '../src/model-adapter.mjs';
const now = Date.parse('2026-10-30T15:00:00Z');
const publicData = JSON.parse(readFileSync(new URL('../src/public-data/openarg-national-verified.json', import.meta.url)));
test('family scenario reconciles shared action durations and omits all private metrics', () => {
  const family = demoFamily(now);
  for (const member of family.members.filter(m=>m.permissions.progress)) {
    assert.equal(member.screenMinutes, member.appUsage.reduce((n, a) => n + a.minutes, 0));
    assert.equal(member.screenMinutes, member.activities.reduce((n, a) => n + a.durationMinutes, 0));
    assert.equal(member.tinoMinutes, member.activities.reduce((n, a) => n + a.assistedMinutes, 0));
    assert.ok(member.tinoMinutes <= member.screenMinutes);
    assert.equal(member.weeklyScreen.at(-1), member.screenMinutes);
    assert.equal(member.weeklyTino.at(-1), member.tinoMinutes);
    assert.equal(member.weeklyDays.at(-1).screenMinutes, member.screenMinutes);
    assert.equal(ageOn(member.birthDate, now), member.age);
    assert.equal(member.requestsToday, member.activities.filter(e => e.helpRequested).length);
    assert.equal(member.autonomyRate, undefined); assert.equal(member.autonomyChecks,undefined); assert.equal(member.completedFlows,undefined);
    for (let i = 1; i < member.activities.length; i++) {
      const later = member.activities[i - 1], earlier = member.activities[i];
      assert.ok(Date.parse(earlier.at) <= Date.parse(later.at) - later.durationMinutes * 60_000, 'screen events do not overlap');
    }
  }
  const marta = family.members[0], alert = family.alerts[0];
  assert.ok(marta.screenMinutes > 50); assert.ok(marta.tinoMinutes > 20);
  assert.equal(marta.activities.find(e => e.id === alert.eventId).alertId, alert.id);
  assert.equal(marta.activities.find(e => e.id === alert.eventId).at, alert.createdAt);
  assert.ok(Date.parse(marta.location.sharedAt) < Date.parse(marta.location.updatedAt));
  assert.ok(Date.parse(marta.location.expiresAt) > now);
  assert.equal(family.members[2].permissions.websites, false); assert.equal(family.members[2].websites, undefined);
  const later = demoFamily(now + 3_600_000, now);
  assert.equal(later.members[0].lastActiveAt, marta.lastActiveAt, 'refresh does not invent new activity');
  assert.equal(later.alerts[0].createdAt, alert.createdAt);
  assert.ok(Date.parse(later.members[0].location.expiresAt) < now + 3_600_000, 'location expires rather than extending on refresh');
});
test('six months reconcile distinct participants, workflow totals, provincial totals and bounded rates', () => {
  const data = demoInstitution({ now });
  assert.equal(data.months.length, 6); assert.equal(data.territories.length, 24); assert.equal(data.flows.length, 10);
  assert.equal(data.metrics.requests, data.flows.reduce((n, f) => n + f.count, 0));
  assert.equal(data.metrics.activeUsers, data.territories.reduce((n, t) => n + t.activeUsers, 0));
  assert.equal(data.metrics.requests, data.trend.reduce((n, t) => n + t.requests, 0));
  assert.ok(data.metrics.activeUsers < data.trend.reduce((n, t) => n + t.activeUsers, 0));
  assert.ok(data.metrics.unsafeLinks <= data.metrics.checkedLinks);
  assert.ok(data.metrics.autonomyRate <= 100 && data.metrics.averageAge > 18 && data.metrics.averageAge < 90);
  const may = demoInstitution({ now, territoryId: 'AR-X', month: '2026-05' });
  assert.equal(may.period.to, '2026-05-31'); assert.equal(may.trend.length, 1);
  assert.equal(may.metrics.activeUsers, may.territories.find(t => t.id === 'AR-X').activeUsers);
  assert.throws(() => demoInstitution({ now, month: '2026-11' }));
});
test('public context joins all 24 provinces by ISO and preserves independent reference periods', () => {
  const data = demoInstitution({ now, territoryId: 'AR-V' }); const context = crossPublicContext(data, publicData);
  assert.equal(context.territories.length, 24); assert.equal(context.provinces.length, 24);
  assert.equal(context.indicators[0].value, publicData.provinces.find(p => p.territoryId === 'AR-V').population);
  assert.equal(context.crosses[0].denominatorPeriod, '2022'); assert.equal(context.connectivityPeriod, '2026 T1');
  assert.ok(context.provinces.find(p => p.territoryId === 'AR-C').internetPer100Homes > 100);
  assert.equal(context.territories.reduce((n,t)=>n+t.population,0), publicData.nationalPopulation);
});
test('CSV handles quoted jurisdictions, rejects incomplete rows and never turns a failed total into a population', () => {
  assert.equal(parseCsvBlock('```csv\nname,value\n"Tierra, del Fuego",2\n```')[0].name, 'Tierra, del Fuego');
  assert.throws(() => parseCsvBlock('```csv\nname,value\n"broken,2\n```'));
  assert.throws(() => buildPublicSnapshot({ text: `\`${POPULATION_TABLE}\`\n\`\`\`csv\nJurisdicción,Total de población\nTotal,100\n\`\`\`` }, { text: `\`${CONNECTIVITY_TABLE}\`\n\`\`\`csv\nanio,trimestre,provincia,accesos_cada_100_hogares,accesos_cada_100_habitantes\n2026,1,CABA,100,50\n\`\`\`` }));
});
test('birth dates are calendar-valid and ages use completed birthdays without inventing missing values', () => {
  assert.equal(ageOn('2000-10-31', now), 25); assert.equal(ageOn('2000-10-30', now), 26);
  assert.equal(ageOn('2000-02-29', now), 26); assert.equal(ageOn('2001-02-29', now), null);
  assert.equal(ageOn('2027-01-01', now), null); assert.equal(ageOn(undefined, now), null);
});
test('OpenAI adapter requests no response storage, bounds output, preserves roles and returns only assistant text', async () => {
  let request;
  const model = createModelAdapter({ apiKey: 'test-only', fetchImpl: async (url, options) => { request = { url, ...options }; return new Response(JSON.stringify({ output: [{ type: 'message', content: [{ type: 'output_text', text: 'Respuesta de prueba.' }] }] }), { status: 200 }); } });
  assert.equal(await model({ system: 'TINO', messages: [{ role: 'user', content: 'Una consulta' }], flows: [], task: null }), 'Respuesta de prueba.');
  const body = JSON.parse(request.body); assert.equal(body.store, false); assert.equal(body.max_output_tokens, 700); assert.equal(body.input[0].role, 'user');
});
test('demo dashboards are role-bound, actions are group-isolated, and demo institutions never access private analytics', async t => {
  let clock = now;
  const dir = await mkdtemp(join(tmpdir(), 'tino-dashboards-'));
  const server = createServer({ dataFile: join(dir, 'state.json'), now: () => clock, institutionToken: '', openarg: new OpenArgClient({ apiKey: '' }), accountOptions: { judgesDemo: true } });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(async () => { await new Promise(r => server.close(r)); await rm(dir, { recursive: true, force: true }); });
  const req = async (path, token, body, method = body ? 'POST' : 'GET') => { const r = await fetch(`http://127.0.0.1:${server.address().port}/api${path}`, { method, headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}) }, ...(body ? { body: JSON.stringify(body) } : {}) }); return { status: r.status, body: await r.json() }; };
  const login = async (email, role) => {
    const code = await req('/auth/code/request', null, { email, role, purpose: 'login' });
    const verified = await req('/auth/code/verify', null, { challengeId: code.body.challengeId, code: '000000' });
    assert.equal(verified.status, 200); assert.equal(verified.body.onboardingRequired, false); return verified.body.sessionToken;
  };
  const institution = await login('institution@example.com', 'institution');
  const family = await login('family@example.com', 'family'); const other = await login('other@example.com', 'family');
  assert.equal((await req('/demo/institution/dashboard')).status, 403);
  assert.equal((await req('/demo/institution/dashboard', family)).status, 403);
  assert.equal((await req('/institution/analytics', institution)).status, 503);
  assert.equal((await req('/demo/institution/dashboard?months=6', institution)).body.flows.length, 10);
  assert.equal((await req('/demo/institution/dashboard?territoryId=invalid', institution)).status, 400);
  assert.equal((await req('/demo/family/actions', family, { id: 'ping-demo-001', memberId: 'marta', type: 'ping' })).status, 200);
  assert.equal((await req('/demo/family/dashboard', family)).body.messages.length, 1);
  assert.equal((await req('/demo/family/dashboard', other)).body.messages.length, 0);
  const before = (await req('/demo/family/dashboard', family)).body;
  assert.equal((await req('/demo/family/actions', family, { id: 'resolve-wrong-member', memberId: 'ricardo', type: 'resolve', alertId: 'demo-link-1' })).status, 400);
  assert.equal((await req('/demo/family/actions', family, { id: 'resolve-family-link', memberId: 'marta', type: 'resolve', alertId: 'demo-link-1' })).status, 200);
  clock += 60_000;
  const after = (await req('/demo/family/dashboard', family)).body;
  assert.equal(after.members[0].lastActiveAt, before.members[0].lastActiveAt);
  assert.equal(after.alerts[0].createdAt, before.alerts[0].createdAt);
  assert.ok(Date.parse(after.alerts[0].resolvedAt) >= Date.parse(after.alerts[0].createdAt));
  assert.equal((await req('/demo/family/dashboard', other)).body.alerts[0].resolvedAt, null);
  for(const type of ['ping','voice','location_request','resolve']) assert.equal((await req('/demo/family/actions', family, { id: 'blocked-lucia-'+type, memberId:'lucia',type })).status,403);
  assert.equal((await req('/demo/family/actions', family, { id: 'voice-demo-001', memberId: 'marta', type: 'voice', audioBase64: 'not-a-recording' })).status, 400);
  const device = (await req('/devices', null, { name: 'Prueba de entrega', territoryId: 'AR-C' })).body;
  const invite = (await req('/family/invitations', family, {})).body;
  assert.equal((await req('/family/link', device.token, { invitationCode: invite.invitationCode, permissions: { progress: false, help: true } })).status, 200);
  const message = { id: 'delivery-demo-001', memberId: device.deviceId, type: 'ping' };
  assert.equal((await req('/family/messages', other, message)).status, 403);
  assert.equal((await req('/family/messages', family, message)).status, 201);
  assert.equal((await req('/family/messages', device.token)).body.messages.length, 1);
  assert.equal((await req('/family/messages', family, message)).status, 201);
  assert.equal((await req('/family/messages', device.token)).body.messages.length, 1);
  assert.equal((await req('/family/link', device.token, undefined, 'DELETE')).status, 200);
  assert.equal((await req('/family/messages', device.token)).body.messages.length, 0);
});


test('shared family demo has twelve TINO actions and distinguishes explicit partial help from reported outcomes',()=>{
 const family=demoFamily(now);
 for(const member of family.members.filter(m=>m.permissions.progress)){
  assert.ok(member.activities.length>=10);
  assert.ok(member.activities.every(e=>e.helpRequested===true));
  assert.ok(member.activities.some(e=>e.assistanceLevel==='full'));
  assert.ok(member.activities.some(e=>e.assistanceLevel==='partial'));
  assert.ok(member.activities.some(e=>e.title==='Grupo de WhatsApp creado'));
  assert.ok(member.activities.some(e=>e.title==='Email enviado'));
  for(const event of member.activities){
   assert.equal(event.independent,undefined);
   assert.ok(event.assistedMinutes<=event.durationMinutes);
   if(event.assistanceLevel==='partial')assert.equal(event.assistanceEndReason,'user_continued_alone');
   if(event.outcome==='reported_complete')assert.equal(event.resultSource,'simulated_self_report');
   else assert.equal(event.resultSource,'help_requested');
  }
 }
});

test('Lucia shares location only and no usage, activity, online status or derived metrics reach the client',()=>{
 const lucia=demoFamily(now).members.find(m=>m.id==='lucia');
 assert.deepEqual(lucia.permissions,{progress:false,help:false,usage:false,safety:false,websites:false,location:true});
 assert.ok(lucia.location.point.length===2);
 for(const key of ['activities','websites','screenMinutes','tinoMinutes','weeklyScreen','weeklyTino','weeklyDays','appUsage','requestsToday','helpRequests','weeklyRequests','lastActiveAt','lastActivity','online'])assert.equal(Object.hasOwn(lucia,key),false,key);
});
