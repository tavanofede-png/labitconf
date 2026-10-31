import http from 'node:http';
import jury from '../api/jury.mjs';
import { juryApp } from '../apps/api/src/jury-runtime.mjs';
http.createServer((req,res)=>new URL(req.url,'http://localhost').pathname==='/api/jury'?jury(req,res):juryApp(req,res)).listen(8787,'127.0.0.1',()=>console.log('TINO jury API: http://127.0.0.1:8787'));
