import {createRequire} from 'node:module';
import assert from 'node:assert/strict';
const require=createRequire('/Users/jazminourens/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/package.json');
const {chromium}=require('playwright');
const origin=process.env.JURY_TEST_ORIGIN||'http://127.0.0.1:5173';
const negative=process.env.WAKE_NEGATIVE==='1';
const cancelCase=process.env.WAKE_CANCEL==='1';
const audio=process.env.WAKE_AUDIO||(negative?'/private/tmp/tino-wake-not-tino.wav':'/private/tmp/tino-wake-command.wav');
const browser=await chromium.launch({channel:'chrome',headless:true,args:['--use-fake-device-for-media-stream','--use-fake-ui-for-media-stream',`--use-file-for-fake-audio-capture=${audio}%noloop`]});
const context=await browser.newContext({viewport:{width:1440,height:1000},permissions:['microphone']});
const page=await context.newPage();const requests=[];
page.on('request',r=>{if(r.url().includes('resource=chat'))requests.push(JSON.parse(r.postData()||'{}'));});
try{
 await page.goto(origin);const app=page.frameLocator('iframe');
 await app.getByRole('button',{name:'DEMO',exact:true}).click();
 await app.getByText('Hola, soy TINO.',{exact:false}).waitFor();
 assert.equal(await app.locator('.j-floating-bubble').count(),0);
 const reply=negative||cancelCase?null:page.waitForResponse(r=>r.url().includes('resource=chat'),{timeout:65000});
 await app.getByRole('button',{name:'Salir de TINO al inicio de Android'}).click();
 if(process.env.WAKE_SURFACE==='whatsapp')await app.getByRole('button',{name:'Abrir WhatsApp',exact:true}).click();
 if(process.env.WAKE_SURFACE==='gmail')await app.getByRole('button',{name:'Abrir Gmail',exact:true}).click();
 await app.getByText('Decí «Hola TINO»',{exact:true}).waitFor({timeout:20000});
 if(cancelCase){
   await app.getByRole('dialog',{name:'Chat flotante de TINO'}).waitFor({timeout:20000});
   await app.locator('.j-recording').waitFor();await page.waitForTimeout(2000);
   await app.getByRole('button',{name:'Terminar de hablar',exact:true}).click();
   await app.getByRole('textbox',{name:'Mensaje para TINO',exact:true}).waitFor();
   assert.equal(requests.length,0);console.log('HOLA_TINO_ALONE_OPENS_AND_EMPTY_STOP_SENDS_NOTHING_PASS');
 }else if(negative){
   await page.waitForTimeout(10000);
   assert.equal(await app.getByRole('dialog',{name:'Chat flotante de TINO'}).count(),0);
   assert.equal(requests.length,0);
   console.log('HOLA_NIETO_DOES_NOT_ACTIVATE_PASS');
 }else{
   await app.getByRole('dialog',{name:'Chat flotante de TINO'}).waitFor({timeout:20000});
   await app.locator('.j-recording').waitFor();
   await page.screenshot({path:'/private/tmp/tino-desktop-wake-listening.png'});
   const response=await reply;const data=await response.json();
   assert.equal(response.status(),200,JSON.stringify(data));assert.equal(requests.length,1);
   assert.match(requests[0].text,/mandar un (mensaje|correo|mail)/i);
   assert.doesNotMatch(requests[0].text,/hola|latino|nieto/i);
   await app.getByRole('button',{name:'Continuar paso a paso'}).waitFor();
   await app.locator('.j-message-row.assistant').last().evaluate(node=>Promise.all(node.getAnimations().map(animation=>animation.finished)));
   await page.screenshot({path:'/private/tmp/tino-desktop-wake-result.png'});
   if(process.env.WAKE_SURFACE)assert.match(requests[0].screen,new RegExp(process.env.WAKE_SURFACE,'i'));
   console.log('DESKTOP_CHROME_AUDIO_HOLA_TINO_POPUP_TRANSCRIPT_REAL_BACKEND_PASS',JSON.stringify({surface:process.env.WAKE_SURFACE||'android',text:requests[0].text,status:response.status()}));
 }
}finally{await browser.close();}
