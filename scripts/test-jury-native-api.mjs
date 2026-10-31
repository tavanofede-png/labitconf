import assert from 'node:assert/strict';
import WebSocket from 'ws';
const origin=process.env.JURY_TEST_ORIGIN||'https://tino-web.vercel.app';
async function post(path,body,token){const r=await fetch(origin+'/api'+path,{method:'POST',headers:{'Content-Type':'application/json',...(token?{Authorization:`Bearer ${token}`}:{})},body:JSON.stringify(body)});const data=await r.json();assert.ok(r.ok,`${path}: ${r.status} ${JSON.stringify(data.error)}`);return data;}
const login=await post('/auth/demo/login',{email:'test@labitconf.com',password:'0000'});
const invite=await post('/jury?resource=activate',{},login.sessionToken);
await post('/devices/me/activation',{enrollmentCode:invite.enrollmentCode},login.deviceToken);
console.log('NATIVE_DEMO_AUTH_ACTIVATION_PASS');
await new Promise((resolve,reject)=>{
 const socket=new WebSocket(origin.replace('https:','wss:')+'/api/assistant/realtime',{headers:{Authorization:`Bearer ${login.deviceToken}`}});
 const timeout=setTimeout(()=>{socket.terminate();reject(Error('Native voice connection timed out'));},60000);
 socket.on('error',reject);socket.on('message',bytes=>{try{const event=JSON.parse(bytes);if(event.type==='ready'){console.log('NATIVE_REALTIME_READY',event.model);socket.send(JSON.stringify({type:'text',text:'Decime una frase breve para ayudar a agrandar la letra en Android.'}));}if(event.type==='text.done'){console.log('NATIVE_REALTIME_ANSWER',event.text);clearTimeout(timeout);socket.close();resolve();}if(event.type==='error'){clearTimeout(timeout);socket.close();reject(Error(JSON.stringify(event)));}}catch(e){reject(e);}});
});
console.log('NATIVE_REALTIME_PUBLIC_SERVER_PASS');
