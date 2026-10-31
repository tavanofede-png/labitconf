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
export function demoFamily(now = Date.now(), startedAt = now) {
  const iso = minutes => new Date(startedAt - minutes * 60_000).toISOString();
  const profiles = [
    { id: 'marta', name: 'Marta', relationship: 'Mamá', age: 67, birthDate: '1959-05-22', territoryId: 'AR-C', device: 'Samsung Galaxy A15', online: true,
      weeklyScreen: [38, 51, 42, 55, 40, 48, 46], weeklyTino: [16, 20, 14, 22, 12, 16, 18], completedFlows: 30, autonomyChecks: 21,
      learned: 'Crear un grupo de WhatsApp', milestone: 'Hoy creó un grupo por su cuenta.', weeklyGoal: 5, weeklyCompleted: 4,
      location: { label: 'En casa', area: 'Caballito, Ciudad de Buenos Aires', point: [-58.439, -34.618], sharedAt: iso(12), updatedAt: iso(2), expiresAt: iso(-18), approximate: true, radiusMeters: 120 },
      events: [
        ['marta-link', 2, 'safety', 'Revisó un enlace con TINO', 'Navegador', 0, true, 'premios-envio.example', 'No continuó al formulario.', false],
        ['marta-group', 9, 'task', 'Creó un grupo de WhatsApp', 'WhatsApp', 8, false, '', 'Completó los pasos por su cuenta.', true],
        ['marta-recipe', 24, 'website', 'Buscó una receta de cocina', 'Navegador', 10, true, 'paulinacocina.net', 'TINO la acompañó a encontrar una receta.', false],
        ['marta-call', 36, 'app', 'Hizo una videollamada', 'WhatsApp', 10, true, '', 'Recibió 4 minutos de acompañamiento.', false],
        ['marta-news', 51, 'website', 'Leyó las noticias', 'Navegador', 6, false, 'lanacion.com.ar', 'Navegó por su cuenta.', false],
        ['marta-video', 64, 'app', 'Miró un video de jardinería', 'YouTube', 8, false, '', 'Usó la aplicación de forma independiente.', false],
        ['marta-mail', 74, 'task', 'Practicó cómo enviar un correo', 'TINO', 4, true, '', 'Terminó una práctica guiada.', false]
      ] },
    { id: 'ricardo', name: 'Ricardo', relationship: 'Papá', age: 61, birthDate: '1965-02-10', territoryId: 'AR-B', device: 'Motorola Moto G54', online: false,
      weeklyScreen: [64, 72, 58, 80, 61, 74, 68], weeklyTino: [18, 22, 14, 25, 17, 20, 18], completedFlows: 24, autonomyChecks: 15,
      learned: 'Enviar un correo con un adjunto', milestone: 'Envió una foto por correo sin ayuda.', weeklyGoal: 4, weeklyCompleted: 3, location: null,
      events: [
        ['ricardo-mail', 18, 'task', 'Envió un correo con una foto', 'Gmail', 12, false, '', 'Lo resolvió por su cuenta.', true],
        ['ricardo-group', 35, 'task', 'Practicó crear un grupo', 'WhatsApp', 12, true, '', 'Siguió una guía de 12 minutos.', false],
        ['ricardo-news', 52, 'website', 'Consultó noticias deportivas', 'Navegador', 12, false, 'tycsports.com', 'Navegó por su cuenta.', false],
        ['ricardo-video', 75, 'app', 'Miró un video de cocina', 'YouTube', 16, false, '', 'Usó la aplicación por su cuenta.', false],
        ['ricardo-weather', 96, 'website', 'Buscó el pronóstico', 'Navegador', 6, true, 'smn.gob.ar', 'TINO lo acompañó durante la búsqueda.', false],
        ['ricardo-call', 115, 'app', 'Hizo una llamada familiar', 'WhatsApp', 10, false, '', 'Llamó desde la aplicación.', false]
      ] },
    { id: 'lucia', name: 'Lucía', relationship: 'Hermana', age: 32, birthDate: '1994-08-21', territoryId: 'AR-X', device: 'Samsung Galaxy S23', online: true,
      weeklyScreen: [96, 124, 108, 131, 98, 116, 112], weeklyTino: [8, 10, 5, 9, 4, 7, 6], completedFlows: 18, autonomyChecks: 16,
      learned: 'Revisar un enlace antes de abrirlo', milestone: 'Reconoció las señales de un enlace dudoso.', weeklyGoal: 3, weeklyCompleted: 3, location: null,
      events: [
        ['lucia-mail', 6, 'task', 'Organizó sus correos', 'Gmail', 30, false, '', 'Completó la tarea de forma independiente.', true],
        ['lucia-guide', 48, 'task', 'Practicó revisar un enlace', 'Navegador', 6, true, '', 'Revisó las señales con TINO.', false],
        ['lucia-browser', 72, 'app', 'Usó el navegador', 'Navegador', 16, false, '', 'El detalle de los sitios es privado.', false],
        ['lucia-social', 104, 'app', 'Usó Instagram', 'Instagram', 25, false, '', 'Actividad general de la aplicación.', false],
        ['lucia-whatsapp', 146, 'app', 'Usó WhatsApp', 'WhatsApp', 35, false, '', 'Actividad general de la aplicación.', false]
      ] }
  ];
  const members = profiles.map(({ events, ...p }) => {
    const activities = events.map(([id, minutesAgo, kind, title, app, durationMinutes, assisted, domain, detail, independent]) =>
      ({ id, kind, title, app, durationMinutes, assisted, assistedMinutes: assisted ? (id === 'marta-call' ? 4 : durationMinutes) : 0, domain, detail, independent, at: iso(minutesAgo), ...(kind === 'safety' ? { alertId: 'demo-link-1' } : {}) }));
    const appUsage = [...new Set(activities.map(e => e.app))].map(name => ({ name, minutes: activities.filter(e => e.app === name).reduce((n, e) => n + e.durationMinutes, 0) })).filter(a => a.minutes).sort((a, b) => b.minutes - a.minutes);
    const screenMinutes = appUsage.reduce((n, a) => n + a.minutes, 0);
    const weeklyScreen = p.weeklyScreen.map((n, i) => i === 6 ? screenMinutes : n);
    const tinoMinutes = activities.reduce((n, e) => n + e.assistedMinutes, 0);
    const weeklyTino = p.weeklyTino.map((n, i) => i === 6 ? tinoMinutes : n);
    const latest = activities[0];
    return { ...p, permissions: { progress: true, help: true, usage: true, safety: true, websites: p.id !== 'lucia', location: Boolean(p.location) },
      screenMinutes, tinoMinutes, weeklyScreen, weeklyTino, appUsage, activities,
      lastActiveAt: latest.at, lastActivity: latest.title, completedToday: activities.filter(e => e.kind === 'task').length,
      independentToday: activities.filter(e => e.independent).length, autonomyRate: Math.round(p.autonomyChecks / p.completedFlows * 100),
      websites: activities.filter(e => e.kind === 'website'),
      weeklyDays: weeklyScreen.map((screenMinutes, i) => ({ date: new Date(now - (6 - i) * 86_400_000).toISOString(), screenMinutes, tinoMinutes: weeklyTino[i] })) };
  });
  return { origin: 'tino_simulated', label: 'Familia de demostración', snapshotAt: new Date(now).toISOString(),
    group: { id: 'demo-family', name: 'Familia Ríos' }, members,
    alerts: [{ id: 'demo-link-1', memberId: 'marta', eventId: 'marta-link', type: 'suspicious_link', title: 'Un enlace para revisar juntos',
      message: 'Un enlace prometía un premio y pedía datos personales. Marta lo revisó con TINO y no continuó al formulario.',
      domain: 'premios-envio.example', signals: ['Dominio no reconocido', 'Solicitud de datos personales', 'Promesa de un premio'],
      recommendation: 'Contactá a Marta y revisen juntos el origen del mensaje. No hace falta abrir el enlace.', createdAt: iso(2), resolvedAt: null }], joinRequests: [] };
}
