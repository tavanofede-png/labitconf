import http from 'node:http';
import { createApp } from './server.mjs';
import { attachRealtimeSocket } from './realtime-socket.mjs';
import { BlobStore } from './blob-store.mjs';
import { JsonStore } from './store.mjs';
import { RealtimeService } from './realtime.mjs';
import { createAccounts } from './accounts.mjs';

// Isolated judge environment: production accounts and data are never imported.
export const juryStore = process.env.VERCEL === '1'
  ? new BlobStore({pathname:'tino/jury-state.json',token:process.env.BLOB_READ_WRITE_TOKEN})
  : new JsonStore(process.env.JURY_STATE_PATH || '/private/tmp/tino-jury-state.json');
export const juryRealtime = new RealtimeService({httpTimeoutMs:55000});
export const juryAccounts = createAccounts({store:juryStore,judgesDemo:true,localDemo:false});
export const juryApp = createApp({store:juryStore,realtime:juryRealtime,allowLocalAssistant:false,
  corsOrigins:['https://tino-web.vercel.app','https://tino-web-belo18.vercel.app',
    ...(process.env.VERCEL_URL?[`https://${process.env.VERCEL_URL}`]:[]),'http://127.0.0.1:5173','http://localhost:5173'],
  accountOptions:{judgesDemo:true,localDemo:false},
  clientAddress:req=>process.env.VERCEL==='1'?(req.headers['x-vercel-forwarded-for']||req.socket.remoteAddress):req.socket.remoteAddress,
  realtimePaths:['/api/assistant/realtime','/api/index'],realtimeRewriteQuery:{path:'assistant/realtime'}});
export const juryServer = attachRealtimeSocket(http.createServer(juryApp),juryApp);
