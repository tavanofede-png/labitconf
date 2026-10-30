import { useEffect, useState } from 'react';
import { AndroidLogoIcon, ArrowUpRightIcon, BuildingsIcon, CaretRightIcon, GearSixIcon, HeartIcon, IconContext, LeafIcon, ShieldCheckIcon, UsersThreeIcon } from '@phosphor-icons/react';
import { api, readSession, writeSession } from './api';
import type { Health, Page } from './api';
import { Institution } from './Institution';
import { Family } from './Family';
import { Application, Connection } from './Setup';

const navigation = [
  { id: 'instituciones' as const, label: 'Instituciones', icon: BuildingsIcon, description: 'Panorama territorial' },
  { id: 'familia' as const, label: 'Familia', icon: UsersThreeIcon, description: 'Acompañamiento familiar' },
  { id: 'aplicacion' as const, label: 'Aplicación', icon: AndroidLogoIcon, description: 'NIETO para Android' },
  { id: 'conexion' as const, label: 'Conexión', icon: GearSixIcon, description: 'Configuración local' },
];
function currentPage(): Page { const hash = window.location.hash.slice(1); return navigation.find(item => item.id === hash)?.id ?? 'instituciones'; }

export function App() {
  const [page, setPage] = useState<Page>(currentPage);
  const [health, setHealth] = useState<Health | null>(null);
  const [checking, setChecking] = useState(true);
  const [healthTick, setHealthTick] = useState(0);
  const [institutionToken, setInstitutionToken] = useState(() => readSession('institution'));
  const [familyToken, setFamilyToken] = useState(() => readSession('family'));
  const active = navigation.find(item => item.id === page)!;

  useEffect(() => {
    const onHash = () => setPage(currentPage()); window.addEventListener('hashchange', onHash);
    return () => window.removeEventListener('hashchange', onHash);
  }, []);

  useEffect(() => {
    const controller = new AbortController(); let pending = false;
    const check = async () => {
      if (pending) return; pending = true;
      try { const value = await api<Health>('/health', { signal: controller.signal }); if (!controller.signal.aborted) setHealth(value); }
      catch { if (!controller.signal.aborted) setHealth(null); }
      finally { pending = false; if (!controller.signal.aborted) setChecking(false); }
    };
    void check(); const interval = window.setInterval(() => { if (document.visibilityState === 'visible') void check(); }, 30_000);
    return () => { controller.abort(); clearInterval(interval); };
  }, [healthTick]);

  function navigate(target: Page) { window.location.hash = target; setPage(target); window.scrollTo({ top: 0, behavior: 'instant' }); }
  function saveInstitution(value: string) { writeSession('institution', value); setInstitutionToken(value); }
  function saveFamily(value: string) { writeSession('family', value); setFamilyToken(value); }

  return <IconContext.Provider value={{ weight: 'regular', size: 20 }}><div className="app-shell"><a href="#main-content" className="skip-link">Ir al contenido</a>
    <aside className="sidebar"><a href="#instituciones" className="brand" aria-label="NIETO, ir a Instituciones"><span className="brand-symbol"><LeafIcon weight="fill" size={24} /></span><span>nieto<span className="brand-period">.</span></span></a><p className="brand-description">Autonomía digital,<br />con una mano cerca.</p><div className="sidebar-label">ESPACIOS DE ACOMPAÑAMIENTO</div><nav aria-label="Principal">{navigation.map(({ id, label, icon: Icon }) => <a href={`#${id}`} key={id} className={`nav-item ${page === id ? 'active' : ''}`} aria-current={page === id ? 'page' : undefined} onClick={e => { e.preventDefault(); navigate(id); }}><Icon size={21} weight={page === id ? 'fill' : 'regular'} /><span>{label}</span>{page === id && <span className="nav-indicator" />}</a>)}</nav><div className="sidebar-bottom"><div className="privacy-card"><ShieldCheckIcon size={25} weight="duotone" /><strong>La autonomía<br />también es privacidad.</strong><p>Información útil para acompañar. Cada persona decide qué comparte.</p></div><div className="hackathon-label"><span className="status-dot" /><div>LABITCONF <span>2026</span></div><span className="build-label">MVP</span></div><a href="https://openarg.org" className="powered-link" target="_blank" rel="noreferrer">Contexto público con OpenArg <ArrowUpRightIcon size={14} /></a></div></aside>
    <div className="workspace"><div className="topbar"><div className="breadcrumb"><span>NIETO</span><CaretRightIcon size={13} /><strong>{active.description}</strong></div><div className="topbar-status"><span className="environment-label">Entorno local</span><span className={`service-state ${!health && !checking ? 'is-offline' : ''}`}><span className={`status-dot ${!health ? 'offline' : ''}`} />{checking ? 'Conectando API' : health ? 'API disponible' : 'API sin conexión'}</span><span className="account-avatar" title="Espacio de la hackathon"><HeartIcon size={17} weight="fill" /></span></div></div>
      <main id="main-content" className="main-content" key={page}>
        {page === 'instituciones' && <Institution token={institutionToken} health={health} connect={() => navigate('conexion')} />}
        {page === 'familia' && <Family token={familyToken} setToken={saveFamily} />}
        {page === 'aplicacion' && <Application health={health} />}
        {page === 'conexion' && <Connection token={institutionToken} setToken={saveInstitution} health={health} refreshHealth={() => setHealthTick(t => t + 1)} />}
      </main><footer className="workspace-footer"><span>NIETO · Aprender, acompañar, volver a intentar.</span><span>Hecho para la autonomía.</span></footer>
    </div>
  </div></IconContext.Provider>;
}
