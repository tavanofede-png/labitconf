import {createRequire} from 'node:module';
import assert from 'node:assert/strict';
const require=createRequire('/Users/jazminourens/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/package.json');const{chromium}=require('playwright');
const origin=process.env.JURY_TEST_ORIGIN||'http://127.0.0.1:5173';
const email=process.env.GUIDED_EMAIL==='1';
const appName=email?'Gmail':'WhatsApp',appId=email?'gmail':'whatsapp';
const secondStep=email?'gm-compose':'wa-plus',thirdStep=email?'gm-to':'wa-search-contact';
const file=process.env.WAKE_AUDIO||(email?'/private/tmp/tino-handsfree-followup.wav':'/private/tmp/tino-handsfree-combined-delayed.wav');
const browser=await chromium.launch({channel:'chrome',headless:true,args:['--use-fake-device-for-media-stream','--use-fake-ui-for-media-stream',`--use-file-for-fake-audio-capture=${file}%noloop`]});
function audible(data){assert.equal(data.model,'gpt-realtime-2.1-mini');assert.equal(data.audio?.voice,'cedar');assert.equal(data.audio?.mimeType,'audio/wav');const pcm=Buffer.from(data.audio.base64,'base64');assert.equal(pcm.toString('ascii',0,4),'RIFF');let peak=0;for(let i=44;i+1<pcm.length;i+=2)peak=Math.max(peak,Math.abs(pcm.readInt16LE(i)));assert.ok(peak>300);}
try{
 const page=await browser.newPage({viewport:{width:1440,height:1000},permissions:['microphone']});
 const errors=[];page.on('pageerror',error=>errors.push(error.message));
 const first=page.waitForResponse(r=>r.url().includes('resource=guide')&&r.status()===200,{timeout:65000});
 await page.goto(origin);const app=page.frameLocator('iframe');const frame=await(await page.locator('iframe').elementHandle()).contentFrame();
 const data=await(await first).json();audible(data);await app.locator(`[data-guide-step="open-${appId}"]`).waitFor();
 await frame.waitForFunction(()=>{const a=document.querySelector('audio[data-tino-voice]');return a&&!a.paused&&!a.muted&&a.volume>0&&a.currentTime>.2;},{},{timeout:20000});
 assert.equal(await app.getByRole('dialog',{name:'Chat flotante de TINO'}).count(),0);assert.equal(await app.locator(`[data-guide-control="app-${appId}"][data-guide-active="true"]`).count(),1);
 console.log('VOICE_REQUEST_HIGHLIGHTS_APP_WITHOUT_OPENING_CHAT_PASS',appName);
 const next=page.waitForResponse(r=>r.url().includes('resource=guide')&&r.status()===200,{timeout:65000});
 await app.getByRole('button',{name:'Abrir '+appName,exact:true}).click();audible(await(await next).json());await app.locator(`[data-guide-step="${secondStep}"]`).waitFor();
 await frame.waitForFunction(()=>{const a=document.querySelector('audio[data-tino-voice]');return a&&!a.paused&&a.currentTime>.2;},{},{timeout:20000});
 console.log('APP_CLICK_AUTOMATICALLY_SPEAKS_NEXT_STEP_PASS',appName);
 const contacts=page.waitForResponse(r=>r.url().includes('resource=guide')&&r.status()===200,{timeout:65000});
 await app.getByRole('button',{name:email?'Redactar':'Nuevo chat',exact:true}).click();audible(await(await contacts).json());await app.locator(`[data-guide-step="${thirdStep}"]`).waitFor();
 await frame.waitForFunction(()=>{const a=document.querySelector('audio[data-tino-voice]');return a&&!a.paused&&a.currentTime>.2;},{},{timeout:20000});
 assert.equal(await app.locator(`[data-guide-control="${email?'gm-to':'wa-search'}"][data-guide-active="true"]`).count(),1);
 console.log('NEXT_CLICK_AUTOMATICALLY_SPEAKS_FIELD_GUIDANCE_PASS',appName);await page.screenshot({path:`/private/tmp/tino-guided-real-voice-${appId}.png`});
 await app.getByRole('button',{name:'Desactivar escucha por voz',exact:true}).click();await frame.waitForFunction(()=>document.querySelector('audio[data-tino-voice]').paused&&document.querySelector('.jury-app').getAttribute('data-microphone-permitted')==='false');
 assert.deepEqual(errors,[]);console.log('GUIDED_VOICE_PAUSE_STOPS_AUDIO_AND_MICROPHONE_PASS');
}finally{await browser.close();}
