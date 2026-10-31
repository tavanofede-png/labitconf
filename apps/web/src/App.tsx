import { lazy, Suspense, useEffect, useState } from 'react';
import { AndroidLogoIcon, ArrowUpRightIcon, BuildingsIcon, CaretRightIcon, GearSixIcon, HeartIcon, IconContext, ShieldCheckIcon, UsersThreeIcon } from '@phosphor-icons/react';
import { api, readSession, writeSession } from './api';
import type { Health, Page } from './api';
import { Institution } from './Institution';
import { Family } from './Family';
import { Application, Connection } from './Setup';
import { Onboarding } from './Onboarding';

const navigation = [
  { id: 'instituciones' as const, label: 'Instituciones', icon: BuildingsIcon, description: 'Panorama territorial' },
  { id: 'familia' as const, label: 'Tu red', icon: UsersThreeIcon, description: 'Personas de confianza' },
  { id: 'aplicacion' as const, label: 'Aplicación', icon: AndroidLogoIcon, description: 'TINO para Android' },
  { id: 'conexion' as const, label: 'Conexión', icon: GearSixIcon, description: 'Configuración local' },
];
function currentPage(): Page { const hash = window.location.hash.slice(1); return navigation.find(item => item.id === hash)?.id ?? 'bienvenida'; }

const DemoPage = lazy(() => import('./demo/Demo').then(module => ({ default: module.Demo })));

function demoView(): 'phone' | 'web' | null {
  const hash = window.location.hash.slice(1);
  if (hash === 'bienvenida' || navigation.some(item => item.id === hash)) return null;
  if (hash === 'demo' || hash === 'web') return 'web';
  if (hash === 'emulador') return 'phone';
  const path = window.location.pathname.replace(/\/+$/, '') || '/';
  if (path === '/demo' || path === '/web') return 'web';
  if (path === '/' || path === '/emulador') return 'phone';
  return null;
}

export function App() {
  const [view, setView] = useState(demoView);
  useEffect(() => {
    const refresh = () => setView(demoView());
    window.addEventListener('hashchange', refresh);
    window.addEventListener('popstate', refresh);
    return () => {
      window.removeEventListener('hashchange', refresh);
      window.removeEventListener('popstate', refresh);
    };
  }, []);
  useEffect(() => {
    document.title = view === 'phone' ? 'TINO · Celular interactivo' : view === 'web' ? 'TINO · Demo web' : 'TINO · Portal';
  }, [view]);
  if (view) return <IconContext.Provider value={{ weight: 'regular', size: 20 }}><Suspense fallback={<div role="status" className="loading-row">Preparando la demostración…</div>}><DemoPage view={view} /></Suspense></IconContext.Provider>;
  return <PortalApp />;
}

function PortalApp() {
  const [page, setPage] = useState<Page>(currentPage);
  const [health, setHealth] = useState<Health | null>(null);
  const [checking, setChecking] = useState(true);
  const [healthTick, setHealthTick] = useState(0);
  const [institutionToken, setInstitutionToken] = useState(() => readSession('institution'));
  const [familyToken, setFamilyToken] = useState(() => readSession('family'));
  const active = navigation.find(item => item.id === page) ?? navigation[0];

  useEffect(() => {
    const onHash = () => { if (window.location.hash !== '#main-content') setPage(currentPage()); }; window.addEventListener('hashchange', onHash);
    return () => window.removeEventListener('hashchange', onHash);
  }, []);

  useEffect(() => {
    if (page === 'bienvenida') return;
    const controller = new AbortController(); let pending = false;
    const check = async () => {
      if (pending) return; pending = true;
      try { const value = await api<Health>('/health', { signal: controller.signal }); if (!controller.signal.aborted) setHealth(value); }
      catch { if (!controller.signal.aborted) setHealth(null); }
      finally { pending = false; if (!controller.signal.aborted) setChecking(false); }
    };
    void check(); const interval = window.setInterval(() => { if (document.visibilityState === 'visible') void check(); }, 30_000);
    return () => { controller.abort(); clearInterval(interval); };
  }, [healthTick, page]);

  function navigate(target: Page) { window.location.hash = target; setPage(target); window.scrollTo({ top: 0, behavior: 'instant' }); }
  function saveInstitution(value: string) { writeSession('institution', value); setInstitutionToken(value); }
  function saveFamily(value: string) { writeSession('family', value); setFamilyToken(value); }

  if (page === 'bienvenida') return <IconContext.Provider value={{ weight: 'regular', size: 20 }}><Onboarding navigate={navigate} /></IconContext.Provider>;

  return <IconContext.Provider value={{ weight: 'regular', size: 20 }}><div className="app-shell"><a href="#main-content" className="skip-link" onClick={event => { event.preventDefault(); document.getElementById('main-content')?.focus(); }}>Ir al contenido</a>
    <aside className="sidebar"><a href="#instituciones" className="brand" aria-label="TINO, ir a Instituciones"><img src="/brand/tino-logo.webp" alt="TINO" className="brand-logo" width="136" height="55" /></a><p className="brand-description">Autonomía digital,<br />con una mano cerca.</p><div className="sidebar-label">ESPACIOS DE ACOMPAÑAMIENTO</div><nav aria-label="Principal">{navigation.map(({ id, label, icon: Icon }) => <a href={`#${id}`} key={id} className={`nav-item ${page === id ? 'active' : ''}`} aria-current={page === id ? 'page' : undefined} onClick={e => { e.preventDefault(); navigate(id); }}><Icon size={21} weight={page === id ? 'fill' : 'regular'} /><span>{label}</span>{page === id && <span className="nav-indicator" />}</a>)}</nav><div className="sidebar-bottom"><div className="privacy-card"><ShieldCheckIcon size={25} weight="duotone" /><strong>La autonomía<br />también es privacidad.</strong><p>Información útil para acompañar. Cada persona decide qué comparte.</p></div><div className="hackathon-label"><span className="status-dot" /><div>LABITCONF <span>2026</span></div><span className="build-label">MVP</span></div><a href="https://openarg.org" className="powered-link" target="_blank" rel="noreferrer">Contexto público con OpenArg <ArrowUpRightIcon size={14} /></a></div></aside>
    <div className="workspace"><div className="topbar"><div className="breadcrumb"><span>TINO</span><CaretRightIcon size={13} /><strong>{active.description}</strong></div><div className="topbar-status"><span className="environment-label">Entorno local</span><span className={`service-state ${!health && !checking ? 'is-offline' : ''}`}><span className={`status-dot ${!health ? 'offline' : ''}`} />{checking ? 'Conectando API' : health ? 'API disponible' : 'API sin conexión'}</span><span className="account-avatar" title="Espacio de la hackathon"><HeartIcon size={17} weight="fill" /></span></div></div>
      <main id="main-content" className="main-content" key={page} tabIndex={-1}>
        {page === 'instituciones' && <Institution token={institutionToken} health={health} connect={() => navigate('conexion')} />}
        {page === 'familia' && <Family token={familyToken} setToken={saveFamily} />}
        {page === 'aplicacion' && <Application health={health} />}
        {page === 'conexion' && <Connection token={institutionToken} setToken={saveInstitution} health={health} refreshHealth={() => setHealthTick(t => t + 1)} />}
      </main><footer className="workspace-footer"><span>TINO · Tu teléfono, a tu manera.</span><a href="/inicio.html">Conocer TINO ↗</a></footer>
    </div>
  </div></IconContext.Provider>;
}
