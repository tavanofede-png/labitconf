import { useEffect, useState } from 'react';
import { AndroidLogoIcon, ArrowUpRightIcon, ArrowsClockwiseIcon, DownloadSimpleIcon } from '@phosphor-icons/react';
import './ProductionInstall.css';

export const APK_DOWNLOAD_URL = 'https://github.com/tavanofede-png/labitconf/releases/download/v0.7.0-tino/tino-release.apk';
export const HEALTH_URL = 'https://tino-api-labitconf.vercel.app/api/health';

type ServiceStatus = { state: 'checking' | 'online' | 'offline'; checkedAt: Date | null };

export function ProductionInstall() {
  const [status, setStatus] = useState<ServiceStatus>({ state: 'checking', checkedAt: null });
  const [revision, setRevision] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    let disposed = false;
    setStatus(previous => ({ ...previous, state: 'checking' }));
    const timeout = window.setTimeout(() => controller.abort(), 8_000);

    async function checkService() {
      try {
        const response = await fetch(HEALTH_URL, { signal: controller.signal, cache: 'no-store', credentials: 'omit' });
        const body: unknown = response.ok ? await response.json() : null;
        const online = response.ok && typeof body === 'object' && body !== null && 'status' in body && body.status === 'ok';
        if (!disposed) setStatus({ state: online ? 'online' : 'offline', checkedAt: new Date() });
      } catch {
        if (!disposed) setStatus({ state: 'offline', checkedAt: new Date() });
      } finally {
        window.clearTimeout(timeout);
      }
    }

    void checkService();
    return () => { disposed = true; controller.abort(); window.clearTimeout(timeout); };
  }, [revision]);

  const statusLabel = status.state === 'online' ? 'Servicio disponible' : status.state === 'offline' ? 'Sin conexión verificada' : 'Comprobando conexión';

  return <div className="install-page">
    <a className="skip-link" href="#install-main">Ir al contenido</a>
    <header className="install-header">
      <a href="/" className="install-brand" aria-label="TINO, inicio"><img src="/brand/tino-logo.webp" alt="TINO" width="136" height="55" /></a>
      <nav aria-label="Espacios de acompañamiento">
        <a href="/portal#familia">Tu red <ArrowUpRightIcon size={15} aria-hidden="true" /></a>
        <a href="/portal#instituciones">Instituciones <ArrowUpRightIcon size={15} aria-hidden="true" /></a>
      </nav>
    </header>

    <main id="install-main" className="install-main" tabIndex={-1}>
      <section className="install-hero" aria-labelledby="install-title">
        <div className="install-introduction">
          <p className="install-eyebrow"><AndroidLogoIcon size={19} aria-hidden="true" /> TINO PARA ANDROID <span>v0.7.0</span></p>
          <h1 id="install-title">Tu teléfono.<br /><span>Con una mano cerca.</span></h1>
          <p className="install-lead">Pedí ayuda con tu voz. TINO te acompaña por las aplicaciones de tu teléfono, sigue tu tarea entre ellas y te señala dónde tocar.</p>
          <a className="install-download" href={APK_DOWNLOAD_URL}><DownloadSimpleIcon size={23} aria-hidden="true" /><span>Descargar TINO para Android</span></a>
          <p className="install-download-note">APK · Instalación directa en tu dispositivo</p>
          <div className="install-device"><AndroidLogoIcon size={25} aria-hidden="true" /><div><span>Dispositivo objetivo</span><strong>Samsung Galaxy Tab S10+ · Android 16</strong></div></div>
        </div>

        <aside className="install-service" aria-labelledby="install-service-title">
          <div className="install-service-top"><p className="install-eyebrow">CONEXIÓN DE TINO</p><span className="install-service-symbol" aria-hidden="true">↗</span></div>
          <h2 id="install-service-title">Listo para<br />acompañarte.</h2>
          <p className="install-service-description">La aplicación se instala en Android. Desde esta página podés descargarla y consultar su conexión.</p>
          <div className="install-service-status" data-service-state={status.state} role="status" aria-live="polite" aria-atomic="true">
            <span className="install-status-dot" aria-hidden="true" />
            <strong>{statusLabel}</strong>
          </div>
          <p className="install-service-detail">{status.state === 'online' ? 'El servidor respondió correctamente a la consulta de estado.' : status.state === 'offline' ? 'No pudimos verificar el servidor. Revisá tu conexión y volvé a intentar.' : 'Consultando el servidor de la aplicación…'}</p>
          <div className="install-service-bottom"><span>{status.checkedAt ? `Consulta: ${status.checkedAt.toLocaleTimeString('es-AR', { hour: '2-digit', minute: '2-digit' })}` : 'Estado en tiempo real'}</span><button type="button" disabled={status.state === 'checking'} onClick={() => setRevision(value => value + 1)}><ArrowsClockwiseIcon size={17} aria-hidden="true" /> Actualizar</button></div>
          <p className="install-verification-note">Esta consulta comprueba el servidor. El micrófono, «Hola Tino» y el inicio tras reiniciar se verifican en la tablet.</p>
        </aside>
      </section>

      <section className="install-setup" aria-labelledby="install-setup-title">
        <div className="install-setup-heading"><p className="install-eyebrow">AL INSTALAR</p><h2 id="install-setup-title">Configurás una vez.<br />Después, hablás.</h2><p>Abrí TINO para completar la configuración inicial que te pide Android.</p></div>
        <div className="install-permissions">
          <div><strong>Micrófono y notificaciones</strong><p>Autorizá la voz y la notificación que permite controlar la escucha.</p></div>
          <div><strong>Accesibilidad y asistencia</strong><p>Habilitá TINO en Accesibilidad y aceptá el permiso para observar y señalar los controles de la pantalla.</p></div>
          <div><strong>Asistente predeterminado</strong><p>Elegí TINO y habilitá «Hola Tino» en la configuración inicial.</p></div>
        </div>
      </section>

      <section className="install-voice" aria-labelledby="install-voice-title"><div><p className="install-eyebrow">EN TODO TU TELÉFONO</p><h2 id="install-voice-title">«Hola Tino, ayudame<br />con este trámite».</h2></div><p>Pedí ayuda desde donde estés. TINO te explica cómo llegar a la aplicación que necesitás y cómo continuar allí, un paso a la vez. Mi Argentina, ARCA, el correo o la configuración del teléfono: la tarea la elegís vos. TINO responde cuando se lo pedís.</p></section>
    </main>

    <footer className="install-footer"><span>TINO · Tu teléfono, a tu manera.</span><a href="/portal#bienvenida">Ingresar al portal <ArrowUpRightIcon size={15} aria-hidden="true" /></a></footer>
  </div>;
}
