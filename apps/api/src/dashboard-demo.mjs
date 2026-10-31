import { readFileSync } from 'node:fs';
export const DEMO_TERRITORIES = JSON.parse(readFileSync(new URL('./public-data/territories.json', import.meta.url), 'utf8'));
export const WORKFLOWS = [
  ['whatsapp-group', 'Crear un grupo de WhatsApp', 19], ['email-send', 'Enviar un correo', 16],
  ['whatsapp-location', 'Compartir ubicación', 13], ['video-call', 'Hacer una videollamada', 11],
  ['share-photo', 'Compartir una foto', 10], ['health-appointment', 'Pedir un turno de salud', 9],
  ['check-link', 'Revisar un enlace', 8], ['phone-settings', 'Ajustar letra y sonido', 6],
  ['online-form', 'Completar un formulario', 5], ['maps-route', 'Encontrar una dirección', 3],
];
const round = n => Math.round(n * 10) / 10;
const total = (rows, key) => rows.reduce((n, r) => n + r[key], 0);
function random(seed) { let a = seed >>> 0; return () => { a += 0x6D2B79F5; let t = Math.imul(a ^ a >>> 15, 1 | a); t ^= t + Math.imul(t ^ t >>> 7, 61 | t); return ((t ^ t >>> 14) >>> 0) / 4294967296; }; }
export function ageOn(birthDate, date) {
  if (typeof birthDate !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(birthDate)) return null;
  const birth = new Date(`${birthDate}T00:00:00Z`); const at = new Date(date);
  if (!Number.isFinite(+birth) || birth.toISOString().slice(0, 10) !== birthDate || birth > at) return null;
  const age = at.getUTCFullYear() - birth.getUTCFullYear() - (at.toISOString().slice(5, 10) < birthDate.slice(5) ? 1 : 0);
  return age >= 0 && age <= 125 ? age : null;
}
export function demoInstitution({ now = Date.now(), territoryId = 'AR', months = 6, month = null } = {}) {
  if (!['AR', ...DEMO_TERRITORIES.map(t => t.id)].includes(territoryId) || ![1, 3, 6].includes(months)) throw new Error('Filtro inválido');
  const calendarDay = new Intl.DateTimeFormat('en-CA', { timeZone: 'America/Argentina/Buenos_Aires', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date(now));
  const at = new Date(`${calendarDay}T12:00:00Z`);
  const availableMonths = Array.from({ length: 6 }, (_, i) => new Date(Date.UTC(at.getUTCFullYear(), at.getUTCMonth() - 5 + i, 1)).toISOString().slice(0, 7));
  if (month && !availableMonths.includes(month)) throw new Error('Mes fuera del período');
  const chosen = month ? [month] : availableMonths.slice(-months);
  const last = chosen.at(-1); const end = new Date(Math.min(+at, Date.UTC(Number(last.slice(0, 4)), Number(last.slice(5, 7)), 1) - 1));
  const weights = { 'AR-B': 18, 'AR-C': 11, 'AR-X': 8, 'AR-S': 7, 'AR-M': 4, 'AR-T': 3 };
  const facts = []; const people = new Map(); const demographics = new Map();
  for (const [ti, territory] of DEMO_TERRITORIES.entries()) {
    const pool = Math.round(110 * (weights[territory.id] ?? 1.2 + ti % 3 * .35));
    const rng = random(97001 + ti * 127);
    const births = Array.from({ length: pool }, (_, i) => {
      const age = Math.floor(20 + Math.pow(rng(), .55) * 67);
      const birth = `${at.getUTCFullYear() - age}-${String(1 + Math.floor(rng() * 12)).padStart(2, '0')}-${String(1 + Math.floor(rng() * 28)).padStart(2, '0')}`;
      return { id: `${territory.id}-${i}`, age: ageOn(birth, end), birthDate: birth };
    });
    demographics.set(territory.id, births);
    for (const [mi, key] of availableMonths.entries()) {
      if (!chosen.includes(key)) continue;
      const r = random(202610 + ti * 107 + mi * 31);
      const active = Math.floor(pool * (.53 + mi * .065 + r() * .07));
      const cohort = births.slice(0, active);
      if (territoryId === 'AR' || territoryId === territory.id) cohort.forEach(p => people.set(p.id, p));
      const days = key === at.toISOString().slice(0, 7) ? at.getUTCDate() : new Date(Number(key.slice(0, 4)), Number(key.slice(5, 7)), 0).getDate();
      const requests = Math.round(active * (5.4 + r() * 3));
      const completed = Math.round(requests * (.69 + mi * .025));
      const independent = Math.round(completed * (.31 + mi * .048 + r() * .025));
      const checkedLinks = Math.round(requests * (1.7 + r()));
      const unsafeLinks = Math.round(checkedLinks * (.075 + (ti % 5) * .012 + r() * .02));
      const personDays = active * days;
      facts.push({ territoryId: territory.id, month: key, active, personDays, requests, completed, independent,
        screenMinutes: Math.round(personDays * (31 + r() * 14)), tinoMinutes: Math.round(personDays * (8 + r() * 6)),
        unsafeLinks, checkedLinks, ageTotal: cohort.reduce((n, p) => n + ageOn(p.birthDate, new Date(Math.min(+at, Date.UTC(Number(key.slice(0, 4)), Number(key.slice(5, 7)), 1) - 1))), 0),
        flows: WORKFLOWS.map(([id, name, weight], i) => ({ id, name, count: i === 9 ? requests - WORKFLOWS.slice(0, 9).reduce((n, [, , w]) => n + Math.floor(requests * w / 100), 0) : Math.floor(requests * weight / 100) })) });
    }
  }
  const selected = facts.filter(f => territoryId === 'AR' || f.territoryId === territoryId);
  const summarize = rows => ({ activeUsers: Math.max(0, ...rows.map(r => r.active)), requests: total(rows, 'requests'), completed: total(rows, 'completed'),
    independent: total(rows, 'independent'), unsafeLinks: total(rows, 'unsafeLinks'), checkedLinks: total(rows, 'checkedLinks'),
    screenMinutes: total(rows, 'screenMinutes'), tinoMinutes: total(rows, 'tinoMinutes'), personDays: total(rows, 'personDays') });
  const sum = summarize(selected);
  const territories = DEMO_TERRITORIES.map(t => {
    const s = summarize(facts.filter(f => f.territoryId === t.id));
    const cohort = demographics.get(t.id).slice(0, s.activeUsers);
    return { ...t, ...s, averageAge: round(total(cohort, 'age') / Math.max(1, cohort.length)),
      autonomyRate: round(s.independent * 100 / Math.max(1, s.completed)), screenMinutesPerDay: round(s.screenMinutes / Math.max(1, s.personDays)),
      tinoMinutesPerDay: round(s.tinoMinutes / Math.max(1, s.personDays)), unsafePerThousand: round(s.unsafeLinks * 1000 / Math.max(1, s.checkedLinks)) };
  });
  const trend = chosen.map(key => {
    const rows = selected.filter(f => f.month === key); const s = summarize(rows);
    return { month: key, ...s, activeUsers: total(rows, 'active'), averageAge: round(total(rows, 'ageTotal') / Math.max(1, total(rows, 'active'))),
      autonomyRate: round(s.independent * 100 / Math.max(1, s.completed)), screenMinutesPerDay: round(s.screenMinutes / Math.max(1, s.personDays)) };
  });
  const ages = [...people.values()].map(p => p.age);
  const flows = WORKFLOWS.map(([id, name]) => ({ id, name, count: selected.reduce((n, f) => n + f.flows.find(x => x.id === id).count, 0) })).sort((a, b) => b.count - a.count);
  return { origin: 'tino_simulated', label: 'Escenario demo · datos TINO simulados', generatedAt: new Date(now).toISOString(), territoryId, months: chosen, availableMonths,
    period: { from: `${chosen[0]}-01`, to: end.toISOString().slice(0, 10) },
    metrics: { ...sum, activeUsers: people.size, averageAge: round(ages.reduce((a, b) => a + b, 0) / Math.max(1, ages.length)),
      screenMinutesPerDay: round(sum.screenMinutes / Math.max(1, sum.personDays)), tinoMinutesPerDay: round(sum.tinoMinutes / Math.max(1, sum.personDays)),
      autonomyRate: round(sum.independent * 100 / Math.max(1, sum.completed)), completionRate: round(sum.completed * 100 / Math.max(1, sum.requests)),
      unsafePerThousand: round(sum.unsafeLinks * 1000 / Math.max(1, sum.checkedLinks)) },
    flows, trend, territories, ageBands: [[18, 34], [35, 49], [50, 64], [65, 125]].map(([from, to]) => ({ label: to === 125 ? '65+' : `${from}–${to}`, count: ages.filter(age => age >= from && age <= to).length })),
    definitions: { activeUsers: 'Personas distintas con actividad en el período. No suma usuarios mensuales.', averageAge: 'Edad cumplida al corte, a partir de fechas de nacimiento sintéticas; no edad de toda la población.',
      screenMinutesPerDay: 'Minutos de pantalla / días-persona activos del escenario.', autonomyRate: 'Prácticas repetidas sin ayuda / tareas completadas. No es tiempo sin acompañamiento.',
      unsafeLinks: 'Alertas simuladas, no sitios reales analizados.', map: 'Centroides provinciales agregados. No representa ubicaciones de personas.',
      impact: 'La evolución es simulada y descriptiva; no prueba impacto causal.' } };
}

export function demoFamily(now = Date.now()) {
  const names = [['marta', 'Marta', 'Mamá', 67], ['ricardo', 'Ricardo', 'Papá', 61], ['lucia', 'Lucía', 'Hermana', 32]];
  return { origin: 'tino_simulated', label: 'Familia de demostración', group: { id: 'demo-family', name: 'Nuestra familia' },
    members: names.map(([id, name, relationship, age], i) => ({ id, name, relationship, age, territoryId: ['AR-C', 'AR-B', 'AR-X'][i],
      permissions: { progress: true, help: true, usage: true, safety: true, location: i === 0 },
      screenMinutes: 34 + i * 9, tinoMinutes: 12 + i * 3, completedFlows: 18 + i * 4, autonomyChecks: 10 + i * 3,
      autonomyRate: 56 + i * 4, weeklyScreen: [28, 36, 24, 41, 30, 38, 34].map(n => n + i * 6),
      weeklyTino: [13, 15, 10, 14, 11, 13, 12].map(n => n + i * 2),
      learned: ['Crear un grupo de WhatsApp', 'Enviar un correo', 'Revisar un enlace'][i],
      activities: [{ title: 'Completó una tarea con TINO', detail: 'Enviar un correo', at: new Date(now - 32 * 60_000).toISOString() }, { title: 'Practicó por su cuenta', detail: 'Compartir una foto', at: new Date(now - 3 * 3600_000).toISOString() }],
      location: i === 0 ? { label: 'Ciudad de Buenos Aires', point: [-58.4459, -34.6144], sharedAt: new Date(now - 12 * 60_000).toISOString(), expiresAt: new Date(now + 18 * 60_000).toISOString(), approximate: true } : null })),
    alerts: [{ id: 'demo-link-1', memberId: 'marta', type: 'suspicious_link', title: 'Un enlace para revisar juntos', message: 'Marta pidió ayuda para revisar un enlace.', createdAt: new Date(now - 3600_000).toISOString(), resolvedAt: null }], joinRequests: [] };
}
