import { mkdir, readFile, writeFile, rename } from 'node:fs/promises';
import { dirname } from 'node:path';
import { randomUUID } from 'node:crypto';

export const emptyState = () => ({ version: 1, users: [], groups: [], invitations: [], relationships: [], consents: [], sessions: [], alerts: [], audit: [] });

/** Serializes read-modify-write and uses rename to avoid partial JSON after a crash. Single process MVP. */
export class JsonStore {
  constructor(path) { this.path = path; this.queue = Promise.resolve(); this.initialized = false; }
  async init() {
    if (this.initialized) return;
    await mkdir(dirname(this.path), { recursive: true });
    try { JSON.parse(await readFile(this.path, 'utf8')); }
    catch (error) {
      if (error.code !== 'ENOENT') throw new Error('No se pudo leer el archivo de datos. Se conserva sin reemplazarlo.', { cause: error });
      await writeFile(this.path, JSON.stringify(emptyState(), null, 2), { mode: 0o600, flag: 'wx' }).catch((error) => { if (error.code !== 'EEXIST') throw error; });
    }
    this.initialized = true;
  }
  async read() {
    await this.init(); await this.queue;
    return JSON.parse(await readFile(this.path, 'utf8'));
  }
  update(change) {
    const work = this.queue.then(async () => {
      await this.init(); const state = JSON.parse(await readFile(this.path, 'utf8'));
      const result = await change(state);
      const temporary = `${this.path}.${randomUUID()}.tmp`;
      await writeFile(temporary, JSON.stringify(state, null, 2), { mode: 0o600 });
      await rename(temporary, this.path); return result;
    });
    this.queue = work.catch(() => {}); return work;
  }
}
