import http from 'node:http';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { resolve } from 'node:path';
import { randomBytes, timingSafeEqual } from 'node:crypto';

// Local mirror of the explicitly selected Android device. It never renders the React frontend.
const execute = promisify(execFile);
const root = resolve(import.meta.dirname, '..');
const adbPath = process.env.ADB_PATH || resolve(root, '.cache/android-toolchain/sdk/platform-tools/adb');
const serial = process.env.ANDROID_SERIAL || 'emulator-5554';
if (!/^[A-Za-z0-9._:-]{1,100}$/.test(serial)) throw new Error('Identificador Android inválido.');
const port = Number(process.env.ANDROID_VIEWER_PORT) || 4190;
const origin = `http://127.0.0.1:${port}`;
const nonce = randomBytes(32).toString('hex');
const deviceLabel = serial.startsWith('emulator-') ? 'Android · emulador' : 'Android · tablet USB';
const waitingMessage = serial.startsWith('emulator-') ? 'Esperando al emulador Android…' : 'Esperando la conexión USB de la tablet…';
const adb = (...args) => execute(adbPath, ['-s', serial, ...args], { encoding: 'buffer', maxBuffer: 12 * 1024 * 1024, timeout: 15_000 });
let capture = null;
const screenshot = () => capture ??= adb('exec-out', 'screencap', '-p').then(r => r.stdout).finally(() => { capture = null; });
const html = `<!doctype html><html lang="es"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>TINO · ${deviceLabel}</title>
<style>*{box-sizing:border-box}body{margin:0;background:#e6eeed;color:#101416;font:15px system-ui}header{display:flex;justify-content:space-between;align-items:center;padding:16px 22px;background:#f3f6f6;border-bottom:1px solid #cedbd9}h1{font-size:19px;margin:0}header small{display:block;color:#4c5b61;margin-top:4px}.controls{display:flex;gap:8px;flex-wrap:wrap}button,input{font:inherit;border:1px solid #b5c9c6;border-radius:10px;padding:11px 14px;background:#fff;color:#006b64}button{cursor:pointer}button:hover{background:#d9f6ef}main{display:flex;justify-content:center;padding:20px}.device{border:9px solid #101416;border-radius:25px;overflow:hidden;line-height:0;background:#101416;box-shadow:0 15px 50px #102b2b25}#screen{display:block;width:auto;height:calc(100vh - 240px);max-width:94vw;object-fit:contain;cursor:crosshair;touch-action:none}.toolbar{padding:0 20px 20px;display:flex;justify-content:center;gap:8px;flex-wrap:wrap}input{width:min(420px,65vw);color:#101416}#status{text-align:center;min-height:24px;color:#4c5b61;margin:0 0 12px}button:focus-visible,input:focus-visible{outline:3px solid #00d6c9;outline-offset:3px}@media(max-width:600px){header{align-items:flex-start;gap:12px;flex-direction:column}#screen{height:calc(100vh - 310px)}main{padding:12px}}</style>
<header><div><h1>TINO · ${deviceLabel}</h1><small>Pantalla en vivo del dispositivo conectado. Tocá para usar la app.</small></div><div class="controls">${serial.startsWith('emulator-') ? '<button id="phone">Teléfono</button><button id="tablet">Tablet</button>' : ''}<button id="launch">Abrir TINO</button></div></header>
<main><div class="device"><img id="screen" alt="Pantalla en vivo de la app Android TINO" draggable="false"></div></main>
<p id="status" role="status">Conectando con Android…</p><div class="toolbar"><button id="back">← Atrás</button><button id="up">↑ Subir</button><button id="down">↓ Bajar</button><form id="typing"><input id="text" aria-label="Texto para el campo seleccionado en Android" placeholder="Tocá un campo en Android y escribí acá" maxlength="500" autocomplete="off"><button>Escribir</button></form><button id="enter">↵ Aceptar</button></div>
<script>const nonce=${JSON.stringify(nonce)};const screen=document.querySelector('#screen'),status=document.querySelector('#status');let busy=false;async function action(body){try{const r=await fetch('/action',{method:'POST',headers:{'Content-Type':'application/json','X-Tino-Viewer':nonce},body:JSON.stringify(body)});if(!r.ok)throw Error('No se pudo completar la acción en Android.');status.textContent='Android conectado · interacción real';await frame()}catch(e){status.textContent=e.message}}async function frame(){if(busy||document.hidden)return;busy=true;try{const r=await fetch('/screen',{cache:'no-store'});if(!r.ok)throw Error();const blob=await r.blob();const prior=screen.src;screen.src=URL.createObjectURL(blob);screen.onload=()=>{if(prior.startsWith('blob:'))URL.revokeObjectURL(prior)};status.textContent='Android conectado · pantalla en vivo'}catch{status.textContent=${JSON.stringify(waitingMessage)}}finally{busy=false}}let down=null;screen.onpointerdown=e=>{screen.setPointerCapture(e.pointerId);const b=screen.getBoundingClientRect();down={x:(e.clientX-b.left)/b.width,y:(e.clientY-b.top)/b.height}};screen.onpointerup=e=>{if(!down)return;const b=screen.getBoundingClientRect();const end={x:(e.clientX-b.left)/b.width,y:(e.clientY-b.top)/b.height};action({type:Math.hypot(end.x-down.x,end.y-down.y)>.035?'swipe':'tap',...down,end});down=null};for(const id of ['back','up','down','enter','phone','tablet','launch']){const control=document.querySelector('#'+id);if(control)control.onclick=()=>action({type:id})}document.querySelector('#typing').onsubmit=e=>{e.preventDefault();action({type:'text',text:document.querySelector('#text').value});document.querySelector('#text').value=''};setInterval(frame,1000);frame();</script></html>`;

http.createServer(async (req, res) => {
  res.setHeader('Cache-Control', 'no-store'); res.setHeader('X-Content-Type-Options', 'nosniff'); res.setHeader('X-Frame-Options', 'DENY');
  try {
    if (req.headers.host !== `127.0.0.1:${port}`) { res.writeHead(403).end(); return; }
    if (req.url === '/' && req.method === 'GET') { res.setHeader('Content-Type', 'text/html; charset=utf-8'); res.end(html); return; }
    if (req.url === '/screen' && req.method === 'GET') { res.setHeader('Content-Type', 'image/png'); res.end(await screenshot()); return; }
    if (req.url !== '/action' || req.method !== 'POST' || req.headers.origin !== origin || req.headers['x-tino-viewer']?.length !== nonce.length || !timingSafeEqual(Buffer.from(req.headers['x-tino-viewer']), Buffer.from(nonce))) { res.writeHead(403).end(); return; }
    let raw = ''; for await (const part of req) { raw += part; if (raw.length > 4096) throw new Error('Request too large'); }
    const body = JSON.parse(raw);
    const pixels = await screenshot();
    if (pixels.length < 24 || pixels.toString('ascii', 1, 4) !== 'PNG') throw new Error('No display');
    // PNG dimensions follow the current rotation; wm size reports the natural device orientation.
    const width = pixels.readUInt32BE(16), height = pixels.readUInt32BE(20);
    const point = p => { if (![p.x, p.y].every(v => Number.isFinite(v) && v >= 0 && v <= 1)) throw new Error('Invalid point'); return [Math.round(p.x * (width - 1)), Math.round(p.y * (height - 1))].map(String); };
    if (body.type === 'tap') await adb('shell', 'input', 'tap', ...point(body));
    else if (body.type === 'swipe') await adb('shell', 'input', 'swipe', ...point(body), ...point(body.end), '400');
    else if (body.type === 'back') await adb('shell', 'input', 'keyevent', '4');
    else if (body.type === 'enter') await adb('shell', 'input', 'keyevent', '66');
    else if (body.type === 'launch') await adb('shell', 'am', 'start', '-n', 'org.nieto.assistant/.MainActivity');
    else if ((body.type === 'phone' || body.type === 'tablet') && serial.startsWith('emulator-')) {
      await adb('shell', 'wm', 'size', body.type === 'tablet' ? '1200x1920' : '1080x1920');
      await adb('shell', 'wm', 'density', body.type === 'tablet' ? '240' : '420');
    } else if (body.type === 'up' || body.type === 'down') await adb('shell', 'input', 'swipe', String(Math.round(width / 2)), String(Math.round(height * (body.type === 'down' ? .8 : .3))), String(Math.round(width / 2)), String(Math.round(height * (body.type === 'down' ? .3 : .8))), '450');
    else if (body.type === 'text' && typeof body.text === 'string' && /^[\x20-\x7e]{1,500}$/.test(body.text)) await adb('shell', 'input', 'text', "'" + body.text.replaceAll("'", "'\\''").replaceAll(' ', '%s') + "'");
    else { res.writeHead(400).end(); return; }
    res.setHeader('Content-Type', 'application/json'); res.end('{"ok":true}');
  } catch { res.writeHead(503).end('Android no está disponible para esta acción.'); }
}).listen(port, '127.0.0.1', () => console.log(`Visor del dispositivo Android TINO: ${origin}`));
