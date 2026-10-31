import {createRequire} from 'node:module';
import assert from 'node:assert/strict';
import {writeFile} from 'node:fs/promises';
const require=createRequire('/Users/jazminourens/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/package.json');
const {chromium}=require('playwright');
const origin=process.env.JURY_TEST_ORIGIN||'http://127.0.0.1:5173';
const audio=process.env.WAKE_AUDIO||'/private/tmp/tino-wake-alone.wav';
const followup=process.env.VOICE_FOLLOWUP==='1';const negative=process.env.WAKE_NEGATIVE==='1';
const combined=process.env.VOICE_COMBINED==='1';const surface=process.env.VOICE_SURFACE||'';
const browser=await chromium.launch({channel:'chrome',headless:true,args:['--use-fake-device-for-media-stream','--use-fake-ui-for-media-stream',`--use-file-for-fake-audio-capture=${audio}%noloop`]});
const page=await browser.newPage({viewport:{width:1440,height:1000},permissions:['microphone']});
const requests=[];const errors=[];
page.on('request',r=>{if((r.url().includes('resource=chat')||r.url().includes('resource=guide')))requests.push(JSON.parse(r.postData()||'{}'));});
page.on('pageerror',error=>errors.push(error.message));
function checkAudio(data){
 assert.equal(data.audio?.mimeType,'audio/wav');const wav=Buffer.from(data.audio.base64,'base64');
 assert.equal(wav.toString('ascii',0,4),'RIFF');assert.equal(wav.readUInt32LE(24),24000);
 assert.ok(wav.length>24044);let peak=0;for(let i=44;i+1<wav.length;i+=2)peak=Math.max(peak,Math.abs(wav.readInt16LE(i)));
 assert.ok(peak>300,`Real voice must contain audible PCM; peak=${peak}`);return wav;
}
try{
 const firstReply=negative?null:page.waitForResponse(r=>(r.url().includes('resource=chat')||r.url().includes('resource=guide')),{timeout:65000});
 await page.goto(origin);const app=page.frameLocator('iframe');const frame=await page.locator('iframe').elementHandle();const appFrame=await frame.contentFrame();
 await appFrame.waitForFunction(()=>document.querySelector('.jury-app')?.getAttribute('data-microphone-permitted')==='true',{},{timeout:20000});
 console.log('MICROPHONE_REQUESTED_ON_LOAD_WITHOUT_PAGE_CLICK_PASS');
 if(surface){await app.getByRole('button',{name:'Salir de TINO al inicio de Android'}).click();if(surface==='whatsapp')await app.getByRole('button',{name:'Abrir WhatsApp',exact:true}).click();if(surface==='gmail')await app.getByRole('button',{name:'Abrir Gmail',exact:true}).click();}
 if(negative){
   await page.waitForTimeout(10000);assert.equal(requests.length,0);assert.equal(await app.getByRole('dialog').count(),0);console.log('HOLA_NIETO_DOES_NOT_ACTIVATE_PASS');
 }else{
   const response=await firstReply;const data=await response.json();assert.equal(response.status(),200,JSON.stringify(data));
   const wav=checkAudio(data);await writeFile('/private/tmp/tino-real-voice-response.wav',wav);
   const nextReply=followup?page.waitForResponse(r=>(r.url().includes('resource=chat')||r.url().includes('resource=guide')),{timeout:65000}):null;
   await appFrame.waitForFunction(()=>{const a=document.querySelector('audio[data-tino-voice]');return a&&!a.paused&&a.currentTime>.2&&!a.muted&&a.volume>0;},{},{timeout:20000});
   assert.equal(await app.getByRole('dialog',{name:'Chat flotante de TINO'}).count(),0);
   assert.ok(requests[0].voice);if(combined){assert.match(requests[0].stepId,/open-whatsapp|open-gmail/);}else assert.match(requests[0].text,/hola tino/i);
   console.log('HOLA_TINO_REAL_BACKEND_AUDIO_PLAYING_WITHOUT_CLICK_OR_POPUP_PASS',JSON.stringify({duration:wav.readUInt32LE(40)/48000,text:data.instruction}));
   await page.screenshot({path:'/private/tmp/tino-handsfree-voice-speaking.png'});
   if(!surface&&await app.getByRole('button',{name:'Salir de TINO al inicio de Android'}).count())await app.getByRole('button',{name:'Salir de TINO al inicio de Android'}).click();
   if(followup)await app.getByRole('button',{name:'Abrir Gmail',exact:true}).click();
   if(followup){
     const reply=await nextReply;const value=await reply.json();assert.equal(reply.status(),200,JSON.stringify(value));checkAudio(value);
     assert.equal(requests[1].stepId,'open-gmail');
     await appFrame.waitForFunction(()=>{const a=document.querySelector('audio[data-tino-voice]');return a&&!a.paused&&a.currentTime>.2;},{},{timeout:20000});
     assert.equal(await app.getByRole('dialog',{name:'Chat flotante de TINO'}).count(),0);
     console.log('FOLLOWUP_WITHOUT_WAKE_REAL_VOICE_AND_NO_POPUP_PASS',JSON.stringify({step:requests[1].stepId}));
   }
   await app.getByRole('button',{name:'Abrir chat flotante de TINO',exact:true}).click();
   await app.getByRole('dialog',{name:'Chat flotante de TINO'}).waitFor();
   assert.ok(await app.locator('.j-message-row.assistant .j-message').count());
   console.log('CHAT_OPENS_ONLY_ON_MANUAL_BUBBLE_CLICK_PASS');
   await app.getByRole('button',{name:'Desactivar escucha por voz',exact:true}).click();
   await appFrame.waitForFunction(()=>document.querySelector('.jury-app')?.getAttribute('data-microphone-permitted')==='false'&&document.querySelector('audio[data-tino-voice]').paused);
   console.log('PAUSE_STOPS_MICROPHONE_AND_VOICE_OUTPUT_PASS');
 }
 assert.deepEqual(errors,[]);
}catch(error){await page.screenshot({path:'/private/tmp/tino-handsfree-failure.png'});console.log('VISIBLE_VOICE_STATUS',await page.locator('iframe').contentFrame().locator('.j-wake-status').allTextContents());throw error;}finally{await browser.close();}
