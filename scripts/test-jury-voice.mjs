import {createRequire} from 'node:module';
import assert from 'node:assert/strict';
const require=createRequire('/Users/jazminourens/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/package.json');
const {chromium}=require('playwright');
const browser=await chromium.launch({channel:'chrome',headless:true,args:['--use-fake-device-for-media-stream','--use-fake-ui-for-media-stream','--use-file-for-fake-audio-capture=/private/tmp/tino-voice-test.wav%noloop']});
const origin=process.env.JURY_TEST_ORIGIN||'http://127.0.0.1:5173';
const context=await browser.newContext({viewport:{width:1440,height:1000},permissions:['microphone']});const page=await context.newPage();
try{
 await page.goto(origin);const app=page.frameLocator('iframe');await app.getByRole('button',{name:'DEMO',exact:true}).click();await app.getByText('Hola, soy TINO.',{exact:false}).waitFor();
 await app.getByRole('button',{name:'Hablar con TINO',exact:true}).click();await app.locator('.j-recording').waitFor();await app.getByText('00:05',{exact:true}).waitFor({timeout:12000});
 await page.screenshot({path:'/private/tmp/tino-jury-voice-recording.png'});const transcript=page.waitForResponse(r=>r.url().includes('resource=transcribe'),{timeout:60000});const answer=page.waitForResponse(r=>r.url().includes('resource=chat'),{timeout:65000});
 await app.getByRole('button',{name:'Terminar de hablar',exact:true}).click();const response=await transcript;const result=await response.json();console.log('TRANSCRIPTION',response.status(),result);assert.equal(response.status(),200);assert.match(result.text,/mensaje|whatsapp/i);
 const chat=await answer;assert.equal(chat.status(),200);await app.getByRole('button',{name:'Continuar paso a paso'}).waitFor();await page.screenshot({path:'/private/tmp/tino-jury-voice-result.png'});console.log('VOICE_RECORD_COUNTER_TRANSCRIPT_REAL_CHAT_PASS');
}finally{await browser.close();}
