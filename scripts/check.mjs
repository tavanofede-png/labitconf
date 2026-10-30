import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('..', import.meta.url));
const windows = process.platform === 'win32';

async function npm(args) {
  const command = process.env.npm_execpath ? process.execPath : windows ? 'npm.cmd' : 'npm';
  const commandArgs = process.env.npm_execpath ? [process.env.npm_execpath, ...args] : args;
  const code = await new Promise((resolve) => {
    const child = spawn(command, commandArgs, {
      cwd: root, stdio: 'inherit', windowsHide: true, shell: windows && !process.env.npm_execpath,
    });
    child.once('error', (error) => { console.error(error.message); resolve(1); });
    child.once('exit', (status) => { resolve(status ?? 1); });
  });
  if (code !== 0) process.exit(code);
}

await npm(['run', 'typecheck', '--workspace', '@nieto/web']);
await npm(['run', 'test', '--workspace', '@nieto/api']);
await npm(['run', 'build', '--workspace', '@nieto/web']);
console.log('\n[NIETO] TypeScript, pruebas API y compilación web correctos.');
