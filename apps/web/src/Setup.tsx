import { useEffect, useRef, useState } from 'react';
import { ArrowRightIcon, ArrowSquareOutIcon, CheckCircleIcon, DatabaseIcon, KeyIcon, LinkSimpleIcon, LockKeyIcon, MicrophoneIcon, ShieldCheckIcon, SignOutIcon, TerminalWindowIcon } from '@phosphor-icons/react';
import { api, errorText } from './api';
import type { Health, McpStatus } from './api';
import { Notice, Spinner } from './ui';
import { DeviceAccess } from './DeviceAccess';

export function Connection({ token, setToken, health, refreshHealth }: { token: string; setToken: (value: string) => void; health: Health | null; refreshHealth: () => void }) {
  const [draft, setDraft] = useState('');
  const [storedMcp, setMcp] = useState<(McpStatus & { ownerToken: string }) | null>(null);
  const mcp = storedMcp?.ownerToken === token ? storedMcp : null;
  const [failure, setFailure] = useState<{ ownerToken: string; message: string } | null>(null);
  const error = failure?.ownerToken === token ? failure.message : '';
  const [busy, setBusy] = useState(false);
  const currentToken = useRef(token); currentToken.current = token;
  const verification = useRef<AbortController | null>(null);
  const verificationEpoch = useRef(0);
  useEffect(() => {
    ++verificationEpoch.current; verification.current?.abort();
    setMcp(null); setFailure(null); setBusy(false);
    return () => { ++verificationEpoch.current; verification.current?.abort(); };
  }, [token]);
  async function verify() {
    if (!token) return;
    verification.current?.abort();
    const controller = new AbortController(); verification.current = controller;
    const epoch = ++verificationEpoch.current; const ownerToken = token;
    const current = () => !controller.signal.aborted && currentToken.current === ownerToken && verificationEpoch.current === epoch;
    setBusy(true); setFailure(null); setMcp(null); refreshHealth();
    try { const status = await api<McpStatus>('/openarg/status', { token, signal: controller.signal }); if (current()) setMcp({ ...status, ownerToken }); }
    catch (e) { if (current()) setFailure({ ownerToken, message: errorText(e) }); }
    finally { if (current()) setBusy(false); }
  }
  const mcpError = mcp?.error ? typeof mcp.error === 'string' ? mcp.error : mcp.error.message : '';
  return <>
    <header className="page-heading"><div><div className="eyebrow"><span className="tiny-line" />CONFIGURACIÓN LOCAL</div><h1>Todo conectado.<br /><span>Cada permiso, a la vista.</span></h1><p>El acceso institucional y la integración con datos públicos se configuran por separado.</p></div><button className="button secondary" onClick={refreshHealth}><ArrowRightIcon size={18} />Actualizar estado</button></header>
    <div className="connection-grid"><section className="panel connection-panel"><div className="panel-heading"><span className="large-symbol"><KeyIcon size={29} weight="duotone" /></span><span className={`badge ${token ? 'green' : ''}`}>{token ? 'Acceso cargado' : 'Acceso pendiente'}</span></div><h2>Acceso institucional</h2><p>Ingresá el valor de <code>INSTITUTION_TOKEN</code> del archivo <code>.env</code> del servidor. Habilita métricas agregadas y las consultas públicas.</p><form onSubmit={e => { e.preventDefault(); setToken(draft.trim()); setDraft(''); }}><label htmlFor="institution-token">Credencial del equipo</label><input id="institution-token" type="password" autoComplete="off" spellCheck={false} value={draft} onChange={e => setDraft(e.target.value)} placeholder="Ingresar credencial institucional" required /><button className="button primary full" disabled={!draft.trim()}><LockKeyIcon size={18} />{token ? 'Cambiar acceso' : 'Conectar acceso'}<ArrowRightIcon size={18} /></button></form>{token && <button className="text-button disconnect" onClick={() => { setToken(''); setDraft(''); }}><SignOutIcon />Cerrar acceso institucional</button>}<p className="fine-print">Se conserva en esta pestaña hasta cerrarla. No se incorpora al código público del portal.</p></section>
      <section className="panel connection-panel"><div className="panel-heading"><span className="large-symbol"><DatabaseIcon size={29} weight="duotone" /></span><span className={`badge ${mcp?.connected ? 'green' : ''}`}>{mcp?.connected ? 'MCP conectado' : health?.openarg?.configured ? 'Clave configurada' : 'Clave pendiente'}</span></div><h2>OpenArg por MCP</h2><p>La clave de OpenArg vive en el backend. Las consultas conservan las fuentes oficiales y declaran las fechas que no están informadas.</p><div className="endpoint-box"><span>TRANSPORTE HTTP</span><code>https://mcp.openarg.org/mcp</code></div><button className="button secondary full" onClick={() => { void verify(); }} disabled={!token || busy}>{busy ? <Spinner /> : <LinkSimpleIcon size={18} />}{busy ? 'Verificando MCP…' : 'Verificar conexión real'}<ArrowRightIcon size={18} /></button><a className="text-button external-action" href="https://openarg.org/desarrolladores" target="_blank" rel="noreferrer">Obtener una clave de OpenArg <ArrowSquareOutIcon /></a><p className="fine-print">La verificación descubre las herramientas del servidor. Una consulta de datos además requiere una clave válida y cuota disponible.</p></section></div>
    {error && <Notice kind="error">{error}</Notice>}
    {mcp?.transportError && <Notice kind="error">Conexión MCP: {mcp.transportError.message}</Notice>}
    {mcp?.queryError && <Notice>Última consulta de datos: {mcp.queryError.message} La conexión de transporte se verifica por separado.</Notice>}
    {mcpError && !mcp?.transportError && !mcp?.queryError && <Notice>{mcpError}</Notice>}
    {mcp?.connected && <Notice kind="success">El servidor respondió al protocolo MCP y publicó {mcp.tools.length} herramientas. La disponibilidad de datos se comprueba al realizar una consulta.</Notice>}
    {!!mcp?.tools.length && <section className="panel tools-panel"><div className="eyebrow">DESCUBRIMIENTO EN VIVO</div><h2>Herramientas del MCP</h2><div className="tool-list">{mcp.tools.map(tool => <article key={tool.name}><CheckCircleIcon size={19} /><div><code>{tool.name}</code><p>{tool.description}</p></div></article>)}</div></section>}
    <DeviceAccess token={token} />
    <section className="panel connection-panel realtime-status"><div className="panel-heading"><span className="large-symbol"><MicrophoneIcon size={29} weight="duotone" /></span><span className={`badge ${health?.assistant?.configured ? 'green' : ''}`}>{health?.assistant?.configured ? 'Clave configurada' : 'Clave pendiente'}</span></div><h2>Voz con OpenAI Realtime</h2><p>NIETO usa <code>gpt-realtime-2.1-mini</code> para conversar por voz o texto. La clave <code>OPENAI_API_KEY</code> se configura en el servidor. El micrófono se activa desde el teléfono; el audio y la consulta se envían a OpenAI para responder.</p><p className="fine-print">Este estado indica que hay una clave configurada. El acceso al modelo, la cuota y la reproducción se comprueban con una consulta real. La asistencia puede enviar controles y una captura actual a OpenAI cuando la persona autoriza pantalla remota. Familia e instituciones no reciben ese contenido.</p><a className="text-button external-action" href="https://developers.openai.com/api/docs/models/gpt-realtime-2.1-mini" target="_blank" rel="noreferrer">Documentación de Realtime <ArrowSquareOutIcon /></a></section>
    <section className="panel setup-instructions"><div className="panel-heading"><div><div className="eyebrow">PARA EL EQUIPO DE LA HACKATHON</div><h2>Configuración del servidor</h2></div><TerminalWindowIcon size={25} /></div><ol className="numbered-list compact"><li><span>01</span><div><strong>Abrí el archivo .env en la raíz.</strong><p><code>INSTITUTION_TOKEN</code> es la credencial privada del portal. <code>OPENARG_API_KEY</code> recibe la clave emitida por OpenArg.</p></div></li><li><span>02</span><div><strong>Reiniciá la API después de guardar.</strong><p>Desde la carpeta del proyecto: <code>npm run dev</code>. El servidor carga las variables al iniciarse.</p></div></li><li><span>03</span><div><strong>Conectá el acceso y verificá el MCP.</strong><p>La clave de OpenArg no se ingresa en el navegador ni se distribuye en el APK.</p></div></li></ol><div className="server-status"><span className={`status-dot ${health ? '' : 'offline'}`} /><strong>{health ? `API ${health.version} disponible` : 'API sin conexión'}</strong><span>Almacenamiento local del MVP · datos persistentes en el servidor</span></div></section>
    <div className="page-footnote"><ShieldCheckIcon size={17} /><p>Acceso local para la hackathon. La autenticación de organizaciones y el despliegue de producción quedan para la siguiente fase.</p></div>
  </>;
}

export { Application } from './ApplicationView';
