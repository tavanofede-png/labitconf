import {createRequire} from 'node:module';
import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
const require=createRequire('/Users/jazminourens/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/package.json');
const {chromium}=require('playwright');
const root=fileURLToPath(new URL('../../',import.meta.url)),work=path.join(root,'.cache/pitch-video/capture');await mkdir(work,{recursive:true});
const browser=await chromium.launch({channel:'chrome',headless:true,args:['--use-fake-device-for-media-stream','--use-fake-ui-for-media-stream',`--use-file-for-fake-audio-capture=${path.join(root,'.cache/pitch-video/command.wav')}%noloop`]});
const context=await browser.newContext({viewport:{width:390,height:844},deviceScaleFactor:2,permissions:['microphone'],recordVideo:{dir:work,size:{width:390,height:844}}});
const t0=Date.now();const page=await context.newPage();const video=page.video();const shots=[];const errors=[];page.on('pageerror',e=>errors.push(e.message));
const elapsed=()=>+( (Date.now()-t0)/1000).toFixed(3);
const delay=ms=>page.waitForTimeout(ms);
function voice(id){return page.waitForResponse(r=>r.url().includes('resource=guide')&&r.status()===200&&JSON.parse(r.request().postData()||'{}').stepId===id,{timeout:65000}).then(async r=>{const data=await r.json();if(!data.audio?.base64)throw Error('Missing real guide audio: '+id);await writeFile(path.join(work,id+'.wav'),Buffer.from(data.audio.base64,'base64'));return data;});}
async function ready(id,promise){await page.locator(`[data-guide-step="${id}"]`).waitFor();await promise;await page.waitForFunction(()=>{const a=document.querySelector('audio[data-tino-voice]');return a&&!a.paused&&a.currentTime>.12;},{},{timeout:15000});}
async function tap(locator){const b=await locator.boundingBox();if(!b)throw Error('Tap target not visible');await page.evaluate(({x,y})=>{const el=document.createElement('div');Object.assign(el.style,{position:'fixed',left:x-25+'px',top:y-25+'px',width:'50px',height:'50px',border:'3px solid rgba(255,255,255,.95)',background:'rgba(0,229,138,.22)',borderRadius:'50%',pointerEvents:'none',zIndex:'99999'});document.body.append(el);el.animate([{transform:'scale(.45)',opacity:1},{transform:'scale(1.4)',opacity:0}],{duration:550,easing:'ease-out'}).finished.then(()=>el.remove());},{x:b.x+b.width/2,y:b.y+b.height/2});await delay(90);await locator.click();}
async function scene(id,action,nextStep){
 const target=await page.locator('[data-guide-active="true"]:visible').boundingBox();const shot={id,start:elapsed(),target};
 await page.screenshot({path:path.join(work,id+'.png')});await delay(id==='name'?400:650);
 const nextVoice=nextStep?voice(nextStep):null;
 shot.tap=elapsed();await action();if(nextStep)await page.locator(`[data-guide-step="${nextStep}"]`).waitFor();await delay(350);shot.end=elapsed();shots.push(shot);
 if(nextVoice)await ready(nextStep,nextVoice);console.log('RECORDED',id,shot.start,shot.end);
}
try{
 const initial=voice('open-whatsapp');await page.goto('https://tino-web.vercel.app/app');
 await page.addStyleTag({content:'*{cursor:none!important}'});
 await page.getByRole('button',{name:'DEMO',exact:true}).click();await page.getByRole('button',{name:'Salir de TINO al inicio de Android'}).click();
 await delay(500);const listeningStart=elapsed();await ready('open-whatsapp',initial);const listeningEnd=elapsed();shots.push({id:'listening',start:Math.max(listeningStart,5),end:Math.min(listeningEnd,listeningStart+6),target:null});
 await scene('launcher',()=>tap(page.getByRole('button',{name:'Abrir WhatsApp',exact:true})),'wa-plus');
 await scene('plus',()=>tap(page.getByRole('button',{name:'Nuevo chat',exact:true})),'wa-new-group');
 await scene('new-group',()=>tap(page.getByRole('button',{name:'Nuevo grupo',exact:true})),'wa-search-group');
 await scene('contacts',async()=>{await tap(page.getByRole('textbox',{name:'Buscar contactos'}));await page.getByRole('textbox',{name:'Buscar contactos'}).pressSequentially('Lucía',{delay:80});},'wa-group-contacts');
 await scene('select-lucia',()=>tap(page.getByRole('button',{name:'Lucía',exact:true})),'wa-group-more');
 await scene('select-martin',async()=>{await page.getByRole('textbox',{name:'Buscar contactos'}).fill('Martín');await delay(180);await tap(page.getByRole('button',{name:'Martín',exact:true}));},'wa-group-next');
 await scene('next',()=>tap(page.getByRole('button',{name:'Siguiente',exact:true})),'wa-group-name');
 await scene('name',async()=>{await tap(page.getByRole('textbox',{name:'Nombre del grupo'}));await page.getByRole('textbox',{name:'Nombre del grupo'}).pressSequentially('Nietos de Marta',{delay:95});await page.getByRole('textbox',{name:'Nombre del grupo'}).press('Enter');},'wa-group-create');
 await scene('create',()=>tap(page.getByRole('button',{name:'Crear grupo',exact:true})),'group-done');
 const done={id:'done',start:elapsed(),target:null};await page.screenshot({path:path.join(work,'done.png')});await delay(5500);done.end=elapsed();shots.push(done);
 if(await page.locator('.jp-conversation .incoming').count())throw Error('A fresh group must not invent incoming messages.');
 if(errors.length)throw Error(errors.join('; '));
 await writeFile(path.join(work,'shots.json'),JSON.stringify({source:'https://tino-web.vercel.app/app',recordedAt:new Date().toISOString(),viewport:{width:390,height:844},shots},null,2));
 console.log('ACTUAL_WEB_DEMO_GROUP_CAPTURE_PASS');
}finally{await context.close();await video.saveAs(path.join(work,'raw-demo.webm'));await browser.close();}
