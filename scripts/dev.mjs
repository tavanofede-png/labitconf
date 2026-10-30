import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('..', import.meta.url));
const windows = process.platform === 'win32';
const children = new Set();
let stopping = false;

function run(command, args, options = {}) {
  const child = spawn(command, args, {
    cwd: root,
    stdio: 'inherit',
    windowsHide: true,
    detached: !windows,
    ...options,
  });
  children.add(child);
  child.once('error', (error) => {
    console.error(`[NIETO] No se pudo iniciar ${command}: ${error.message}`);
    void stop(1);
  });
  child.once('exit', (code, signal) => {
    children.delete(child);
    if (!stopping) {
      console.error(`[NIETO] Un servicio terminó (${signal ?? code ?? 'sin código'}). Cerrando el resto.`);
      void stop(code === 0 ? 0 : 1);
    }
  });
  return child;
}

async function terminate(child) {
  if (!child.pid) return;
  if (windows) {
    // npm puede tener descendientes. El PID es siempre un hijo creado aquí.
    const killed = await new Promise((resolve) => {
      const killer = spawn('taskkill.exe', ['/PID', String(child.pid), '/T', '/F'], {
        windowsHide: true, stdio: 'ignore',
      });
      killer.once('error', () => resolve(false));
      killer.once('exit', (code) => resolve(code === 0));
    });
    if (!killed) {
      // PowerShell 7 ofrece Parent sin WMI, útil en terminales que bloquean
      // taskkill. Sólo se detiene el árbol de este hijo, sin buscar por nombre.
      const cleanup = `$nietoRootId=${child.pid}; `
        + '$nietoProcesses=@(Get-Process | ForEach-Object { try { '
        + '[pscustomobject]@{Id=$_.Id;ParentId=$_.Parent.Id} } catch {} }); '
        + 'function Stop-NietoTree([int]$nietoId) { '
        + 'foreach($nietoChild in $nietoProcesses) { if($nietoChild.ParentId -eq $nietoId) { Stop-NietoTree $nietoChild.Id } }; '
        + 'Stop-Process -Id $nietoId -Force -ErrorAction SilentlyContinue }; '
        + 'Stop-NietoTree $nietoRootId';
      await new Promise((resolve) => {
        const fallback = spawn('pwsh.exe', ['-NoLogo', '-NoProfile', '-NonInteractive', '-Command', cleanup], {
          windowsHide: true, stdio: 'ignore',
        });
        fallback.once('error', () => { child.kill(); resolve(); });
        fallback.once('exit', () => { if (child.exitCode === null) child.kill(); resolve(); });
      });
    }
    return;
  }
  try { process.kill(-child.pid, 'SIGTERM'); } catch { return; }
  await new Promise((resolve) => {
    const timeout = setTimeout(() => {
      try { process.kill(-child.pid, 'SIGKILL'); } catch { /* already closed */ }
      resolve();
    }, 3_000);
    child.once('exit', () => { clearTimeout(timeout); resolve(); });
  });
}

async function stop(code) {
  if (stopping) return;
  stopping = true;
  await Promise.all([...children].map(terminate));
  process.exitCode = code;
}

process.once('SIGINT', () => { void stop(0); });
process.once('SIGTERM', () => { void stop(0); });
// Un supervisor Node puede cerrar el canal IPC para detener la demo.
process.once('disconnect', () => { void stop(0); });
if (windows) process.once('SIGBREAK', () => { void stop(0); });

console.log('[NIETO] Direcciones predeterminadas: API http://localhost:8787 · Portal http://localhost:5173');
console.log('[NIETO] Ctrl+C cierra los dos servicios.');
run(process.execPath, ['--env-file-if-exists=.env', 'apps/api/src/server.mjs']);
if (process.env.npm_execpath) {
  run(process.execPath, [process.env.npm_execpath, 'run', 'dev', '--workspace', '@nieto/web']);
} else {
  run(windows ? 'npm.cmd' : 'npm', ['run', 'dev', '--workspace', '@nieto/web'], { shell: windows });
}
