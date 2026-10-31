import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,readFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {validateAttachments,attachmentInput,MAX_ATTACHMENT_BYTES} from '../src/chat-attachments.mjs';
import {createModelAdapter} from '../src/model-adapter.mjs';
import {createServer} from '../src/server.mjs';
import {OpenArgClient} from '../src/openarg.mjs';
const file=(mimeType,data,name='prueba.pdf')=>({name,mimeType,dataBase64:Buffer.from(data).toString('base64')});
const pdf=file('application/pdf','%PDF-1.4\n%fictional attachment test only\n');
const png=file('image/png',Buffer.from([137,80,78,71,13,10,26,10,0,0,0,0]),'captura.png');

test('attachments reject MIME spoofing, noncanonical base64, URLs, excessive count and excessive bytes',()=>{
  assert.equal(validateAttachments([pdf,png]).length,2);
  for(const invalid of [[{...pdf,mimeType:'image/png'}],[{...pdf,dataBase64:'a==='}],[{...pdf,file_url:'https://example.com'}],[pdf,pdf,pdf,pdf],[{...pdf,name:'../archivo.pdf'}],[file('application/pdf',Buffer.concat([Buffer.from('%PDF-'),Buffer.alloc(MAX_ATTACHMENT_BYTES)]))]]) assert.throws(()=>validateAttachments(invalid));
});
test('model adapter sends explicit PDF and image bytes as multimodal input, without response storage',async()=>{
  let body;
  const model=createModelAdapter({apiKey:'test-key-not-real',fetchImpl:async(_,options)=>{body=JSON.parse(options.body);return {ok:true,json:async()=>({output:[{type:'message',content:[{type:'output_text',text:'Documento recibido en prueba.'}]}]})};}});
  await model({system:'test',messages:[{role:'assistant',content:'Antes'},{role:'user',content:'Mirá estos archivos'}],flows:[],task:null,attachments:[pdf,png]});
  assert.equal(body.store,false); assert.equal(body.input[0].content,'Antes');
  assert.deepEqual(body.input[1].content,attachmentInput('Mirá estos archivos',[pdf,png]));
  assert.match(body.input[1].content[1].file_data,/^data:application\/pdf;base64,/);
  assert.match(body.input[1].content[2].image_url,/^data:image\/png;base64,/);
});
test('authenticated chat forwards files over HTTP without persisting content or bypassing attachment analysis with a demo-flow match',async t=>{
  const dir=await mkdtemp(join(tmpdir(),'tino-files-')); const stateFile=join(dir,'state.json'); let received;
  const server=createServer({dataFile:stateFile,openarg:new OpenArgClient({apiKey:''}),accountOptions:{judgesDemo:true},assistantOptions:{generateReply:async input=>{received=input;return 'Respuesta del modelo de prueba.';}}});
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  t.after(async()=>{await new Promise(resolve=>server.close(resolve));await rm(dir,{recursive:true,force:true});});
  const request=async(path,body,token)=>{const r=await fetch(`http://127.0.0.1:${server.address().port}/api${path}`,{method:'POST',headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{})},body:JSON.stringify(body)});return {status:r.status,body:await r.json()};};
  assert.equal((await request('/assistant/message',{text:'hola',attachments:[pdf]})).status,401);
  const login=await request('/auth/demo/login',{email:'test@labitconf.com',password:'0000'});const token=login.body.sessionToken;
  const result=await request('/assistant/message',{text:'Quiero enviar un correo con este PDF',attachments:[pdf],conversationId:'overlay'},token);
  assert.equal(result.status,200);assert.equal(result.body.instruction,'Respuesta del modelo de prueba.');assert.deepEqual(received.attachments,[pdf]);
  assert.equal((await request('/assistant/message',{text:'Mirá',attachments:[{...pdf,mimeType:'image/jpeg'}]},token)).status,400);
  const large=file('application/pdf',Buffer.concat([Buffer.from('%PDF-1.4\n'),Buffer.alloc(30000,32)]));
  assert.equal((await request('/assistant/message',{attachments:[large]},token)).status,200);
  assert.equal((await readFile(stateFile,'utf8')).includes(large.dataBase64),false);
  assert.equal((await readFile(stateFile,'utf8')).includes(pdf.dataBase64),false);
});
test('missing model never claims to have read a supplied document',async()=>{
  const {createAssistant}=await import('../src/assistant.mjs');
  await assert.rejects(createAssistant().reply({key:'x',text:'Qué dice',attachments:[pdf]}),/No pude analizar/);
});
