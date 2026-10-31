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

/** Fictional, internally reconciled household. No phone history or real location is read. */
export const DEMO_FAMILY_VERSION = 2;
export function demoFamily(now = Date.now(), startedAt = now) {
  const localDay=value=>new Intl.DateTimeFormat('en-CA',{timeZone:'America/Argentina/Buenos_Aires',year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date(value));
  const iso=minutes=>new Date(startedAt-minutes*60000).toISOString();
  const profiles = [
    { id: 'marta', name: 'Marta', relationship: 'Mamá', age: 67, birthDate: '1959-05-22', territoryId: 'AR-C', device: 'Samsung Galaxy A15', online: true,
      weeklyScreen: [68, 81, 72, 85, 70, 78, 76], weeklyTino: [36, 40, 34, 42, 32, 36, 38],
      location: { label: 'En casa', area: 'Caballito, Ciudad de Buenos Aires', point: [-58.439, -34.618], sharedAt: iso(12), updatedAt: iso(2), expiresAt: iso(-18), approximate: true, radiusMeters: 120 },
      events: [
        ['marta-link', 2, 'safety', 'Enlace revisado', 'Navegador', 3, 'full', 'premios-envio.example', false],
        ['marta-group', 12, 'task', 'Grupo de WhatsApp creado', 'WhatsApp', 8, 'full', '', true],
        ['marta-mail', 27, 'task', 'Email enviado', 'Gmail', 7, 'full', '', true],
        ['marta-argentina', 44, 'task', 'Mi Argentina', 'Mi Argentina', 9, 'partial', '', true],
        ['marta-recipe', 58, 'website', 'Receta encontrada', 'Navegador', 10, 'partial', 'paulinacocina.net', true],
        ['marta-call', 72, 'task', 'Videollamada realizada', 'WhatsApp', 10, 'partial', '', true],
        ['marta-photo', 88, 'task', 'Foto compartida', 'WhatsApp', 5, 'full', '', true],
        ['marta-maps', 105, 'task', 'Ruta consultada', 'Google Maps', 6, 'partial', '', true],
        ['marta-contact', 122, 'task', 'Contacto agregado', 'Contactos', 5, 'full', '', true],
        ['marta-turno', 140, 'website', 'Turno médico solicitado', 'Navegador', 8, 'full', 'buenosaires.gob.ar', true],
        ['marta-pdf', 159, 'task', 'Documento PDF abierto', 'Archivos', 4, 'partial', '', true],
        ['marta-weather', 178, 'website', 'Pronóstico consultado', 'Navegador', 4, 'full', 'smn.gob.ar', true]
      ] },
    { id: 'ricardo', name: 'Ricardo', relationship: 'Papá', age: 61, birthDate: '1965-02-10', territoryId: 'AR-B', device: 'Motorola Moto G54', online: false,
      weeklyScreen: [74, 82, 78, 90, 81, 84, 88], weeklyTino: [38, 42, 34, 45, 37, 40, 38], location: null,
      events: [
        ['ricardo-mail', 6, 'task', 'Email enviado', 'Gmail', 12, 'full', '', true],
        ['ricardo-group', 25, 'task', 'Grupo de WhatsApp creado', 'WhatsApp', 12, 'full', '', true],
        ['ricardo-argentina', 43, 'task', 'Mi Argentina', 'Mi Argentina', 8, 'partial', '', true],
        ['ricardo-weather', 61, 'website', 'Pronóstico consultado', 'Navegador', 6, 'partial', 'smn.gob.ar', true],
        ['ricardo-file', 76, 'task', 'Archivo adjuntado a un borrador', 'Gmail', 7, 'full', '', true],
        ['ricardo-contact', 91, 'task', 'Contacto agregado', 'Contactos', 4, 'partial', '', true],
        ['ricardo-photo', 108, 'task', 'Foto compartida', 'WhatsApp', 6, 'full', '', true],
        ['ricardo-maps', 124, 'task', 'Ruta consultada', 'Google Maps', 9, 'partial', '', true],
        ['ricardo-call', 142, 'task', 'Videollamada realizada', 'WhatsApp', 10, 'full', '', true],
        ['ricardo-recipe', 162, 'website', 'Receta encontrada', 'Navegador', 8, 'partial', 'paulinacocina.net', true],
        ['ricardo-pdf', 179, 'task', 'Documento PDF abierto', 'Archivos', 5, 'full', '', true],
        ['ricardo-bookmark', 195, 'website', 'Sitio guardado', 'Navegador', 4, 'partial', 'argentina.gob.ar', true]
      ] },
    { id: 'lucia', name: 'Lucía', relationship: 'Hermana', age: 32, birthDate: '1994-08-21', territoryId: 'AR-X', device: 'Samsung Galaxy S23',
      location: { label: 'Ubicación compartida', area: 'Nueva Córdoba, Córdoba', point: [-64.187, -31.427], sharedAt: iso(10), updatedAt: iso(1), expiresAt: iso(-20), approximate: true, radiusMeters: 150 } }
  ];
  const members = profiles.map(({ events = [], ...p }) => {
    if(p.id === 'lucia') return { ...p, permissions: { progress: false, help: false, usage: false, safety: false, websites: false, location: true } };
    const activities = events.map(([id, minutesAgo, kind, title, app, durationMinutes, assistanceLevel, domain, complete]) => {
      const partial=assistanceLevel==='partial';
      return { id, kind, title, app, durationMinutes, assisted: true, assistanceLevel,
        assistedMinutes: partial ? Math.max(1,Math.round(durationMinutes*.4)) : durationMinutes, domain,
        detail: complete ? (partial ? 'Escenario ficticio: la persona eligió seguir por su cuenta y después confirmó el resultado.' : 'Escenario ficticio: la persona confirmó el resultado tras recibir ayuda de TINO.') : 'Se registró acompañamiento de TINO, sin confirmar otro resultado.',
        helpRequested: true, outcome: complete ? 'reported_complete' : 'not_reported',
        resultSource: complete ? 'simulated_self_report' : 'help_requested',
        assistanceEndReason: partial ? 'user_continued_alone' : 'guided_session_ended',
        assistanceStartedAt: iso(minutesAgo + durationMinutes),
        assistanceEndedAt: iso(minutesAgo + durationMinutes - (partial ? Math.max(1,Math.round(durationMinutes*.4)) : durationMinutes)),
        ...(complete ? { outcomeReportedAt: iso(minutesAgo) } : {}),
        at: iso(minutesAgo), ...(kind === 'safety' ? { alertId: 'demo-link-1' } : {}) };
    });
    const todayActivities=activities.filter(e=>localDay(e.at)===localDay(now));
    const appUsage = [...new Set(todayActivities.map(e => e.app))].map(name => ({ name, minutes: todayActivities.filter(e => e.app === name).reduce((n, e) => n + e.durationMinutes, 0) })).filter(a => a.minutes).sort((a, b) => b.minutes - a.minutes);
    const screenMinutes = appUsage.reduce((n, a) => n + a.minutes, 0);
    const weeklyScreen = p.weeklyScreen.map((n, i) => i === 6 ? screenMinutes : n);
    const tinoMinutes = todayActivities.reduce((n, e) => n + e.assistedMinutes, 0);
    const weeklyTino = p.weeklyTino.map((n, i) => i === 6 ? tinoMinutes : n);
    return { ...p, permissions: { progress: true, help: true, usage: true, safety: true, websites: true, location: Boolean(p.location) },
      screenMinutes, tinoMinutes, weeklyScreen, weeklyTino, appUsage, activities,
      helpRequests: activities.length, weeklyRequests: activities.length,
      lastActiveAt: activities[0].at, lastActivity: activities[0].title, requestsToday: todayActivities.length,
      websites: activities.filter(e => e.kind === 'website'),
      weeklyDays: weeklyScreen.map((screenMinutes, i) => ({ date: new Date(now - (6 - i) * 86_400_000).toISOString(), screenMinutes, tinoMinutes: weeklyTino[i] })) };
  });
  return { origin: 'tino_simulated', label: 'Familia de demostración', schemaVersion: DEMO_FAMILY_VERSION, snapshotAt: new Date(now).toISOString(),
    group: { id: 'demo-family', name: 'Familia Ríos' }, members,
    alerts: [{ id: 'demo-link-1', memberId: 'marta', eventId: 'marta-link', type: 'suspicious_link', title: 'Un enlace para revisar juntos',
      message: 'Un enlace prometía un premio y pedía datos personales. Marta pidió ayuda a TINO para revisarlo.',
      domain: 'premios-envio.example', signals: ['Dominio no reconocido', 'Solicitud de datos personales', 'Promesa de un premio'],
      recommendation: 'Contactá a Marta y revisen juntos el origen del mensaje. No hace falta abrir el enlace.', createdAt: iso(2), resolvedAt: null }], joinRequests: [] };
}
