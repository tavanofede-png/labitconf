import { OpenArgClient } from '../src/openarg.mjs';

const client = new OpenArgClient();
const discovery = await client.discover();
console.log(JSON.stringify({ checkedAt: new Date().toISOString(), configured: discovery.configured,
  status: discovery.status, endpoint: discovery.endpoint, transport: discovery.transport,
  serverInfo: discovery.serverInfo, tools: discovery.tools.map((tool) => tool.name), error: discovery.lastError }, null, 2));
if (discovery.tools.length !== 6) process.exitCode = 1;
if (discovery.configured) {
  try { console.log(JSON.stringify(await client.call('listar_fuentes'), null, 2)); }
  catch (error) { console.error(JSON.stringify({ code: error.code, message: error.message })); process.exitCode = 1; }
} else {
  console.log('Handshake y descubrimiento verificados sin clave. Consultas de datos pendientes de OPENARG_API_KEY.');
}
