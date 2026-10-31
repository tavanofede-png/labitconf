import { useEffect, useState } from 'react';
import { BatteryFullIcon, WifiHighIcon } from '@phosphor-icons/react';
import './jury-android.css';

export type JurySurface = 'tino' | 'android' | 'whatsapp' | 'gmail';
type Props = { open: (surface: JurySurface) => void; apps: boolean; target?: string|null };

export function AndroidStatusBar() {
  const [now, setNow] = useState(() => new Date());
  useEffect(() => { const timer = setInterval(() => setNow(new Date()), 30000); return () => clearInterval(timer); }, []);
  return <div className="j-android-status" aria-label="Estado de Android"><time>{now.toLocaleTimeString('es-AR', { hour: '2-digit', minute: '2-digit', hour12: false })}</time><span><WifiHighIcon size={19}/><BatteryFullIcon size={22}/></span></div>;
}

export function JuryAndroidHome({ open, apps, target }: Props) {
  const now = new Date();
  return <main className="j-android-home" data-jury-external-screen aria-label="Inicio de Android">
    <AndroidStatusBar/>
    <div className="j-android-widget"><span>{now.toLocaleDateString('es-AR', { weekday: 'long', day: 'numeric', month: 'long' })}</span><strong>{apps ? 'Aplicaciones' : now.toLocaleTimeString('es-AR', { hour: '2-digit', minute: '2-digit', hour12: false })}</strong></div>
    <div className="j-android-wallpaper-orbit" aria-hidden="true"/>
    <div className="j-android-apps" aria-label="Aplicaciones de la tablet">
      <button onClick={() => open('whatsapp')} aria-label="Abrir WhatsApp" data-guide-control="app-whatsapp" data-guide-active={target==='app-whatsapp'||undefined} aria-describedby={target==='app-whatsapp'?'jury-current-guide':undefined}><span><img src="/brand/apps/whatsapp.svg" alt=""/></span><strong>WhatsApp</strong></button>
      <button onClick={() => open('tino')} aria-label="Abrir la app TINO"><span className="j-android-tino"><img src="/brand/tino-mascot.png" alt=""/></span><strong>TINO</strong></button>
      <button onClick={() => open('gmail')} aria-label="Abrir Gmail" data-guide-control="app-gmail" data-guide-active={target==='app-gmail'||undefined} aria-describedby={target==='app-gmail'?'jury-current-guide':undefined}><span><img src="/brand/apps/gmail.svg" alt=""/></span><strong>Gmail</strong></button>
    </div>
  </main>;
}
