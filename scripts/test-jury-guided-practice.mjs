import {createRequire} from 'node:module';
import assert from 'node:assert/strict';
const require=createRequire('/Users/jazminourens/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/package.json');
const {chromium}=require('playwright');
const origin=process.env.JURY_TEST_ORIGIN||'http://127.0.0.1:5173';
const browser=await chromium.launch({channel:'chrome',headless:true});
async function step(page,id,target){
 await page.locator(`[data-guide-step="${id}"]`).waitFor();
 assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'No horizontal clipping');
 assert.equal(await page.locator('[data-guide-active="true"]:visible').count(),target?1:0);
 if(target)assert.equal(await page.locator(`[data-guide-control="${target}"][data-guide-active="true"]:visible`).count(),1);
}
async function start(page,text){
 await page.goto(origin+'/app');await page.getByRole('button',{name:'DEMO',exact:true}).click();
 await page.getByRole('button',{name:'Abrir menú'}).click();await page.getByRole('switch',{name:'Respuestas por voz'}).click();await page.getByRole('button',{name:'Cerrar menú'}).click();
 await page.getByRole('textbox',{name:'Mensaje para TINO',exact:true}).fill(text);await page.getByRole('button',{name:'Enviar mensaje',exact:true}).click();
 assert.equal(await page.getByRole('dialog',{name:'Chat flotante de TINO'}).count(),0);
}
try{
 for(const width of [876,390])for(const task of ['group','message','location','email']){
  const page=await browser.newPage({viewport:{width,height:width===876?1400:844}});const errors=[];page.on('pageerror',error=>errors.push(error.message));
  try{
   const texts={group:'Quiero crear un grupo de WhatsApp',message:'Quiero mandar un mensaje por WhatsApp',location:'Quiero compartir mi ubicación por WhatsApp',email:'Quiero mandar un correo por Gmail'};
   await start(page,texts[task]);
   if(task==='email'){
    await step(page,'open-gmail','app-gmail');await page.getByRole('button',{name:'Abrir Gmail',exact:true}).click();
    await step(page,'gm-compose','gm-compose');await page.getByRole('button',{name:'Redactar',exact:true}).click();
    await step(page,'gm-to','gm-to');await page.getByRole('textbox',{name:'Destinatario del correo'}).fill('lucia@example.com');await page.getByRole('textbox',{name:'Asunto del correo'}).click();
    await step(page,'gm-subject','gm-subject');await page.getByRole('textbox',{name:'Asunto del correo'}).fill('Nos vemos el sábado');await page.getByRole('textbox',{name:'Cuerpo del correo'}).click();
    await step(page,'gm-body','gm-body');await page.getByRole('textbox',{name:'Cuerpo del correo'}).fill('Hola Lucía, nos vemos en la plaza.');await page.getByRole('textbox',{name:'Cuerpo del correo'}).press('Tab');
    await step(page,'gm-send','gm-send');await page.screenshot({path:`/private/tmp/tino-gmail-compose-${width}.png`});await page.getByRole('button',{name:'Enviar',exact:true}).click();await step(page,'gm-confirm','gm-confirm');
    await page.getByRole('button',{name:'Confirmar envío',exact:false}).click();await step(page,'email-done',null);
    await page.getByText('Mensaje enviado',{exact:true}).waitFor();assert.equal(await page.locator('.jp-mail-row').count(),1);await page.getByText('Nos vemos el sábado',{exact:true}).click();await page.getByText('Hola Lucía, nos vemos en la plaza.',{exact:true}).waitFor();
   }else{
    await step(page,'open-whatsapp','app-whatsapp');await page.getByRole('button',{name:'Abrir WhatsApp',exact:true}).click();
    await step(page,'wa-plus','wa-plus');await page.getByRole('button',{name:'Nuevo chat',exact:true}).click();
    if(task==='group'){
     await step(page,'wa-new-group','wa-new-group');await page.getByRole('button',{name:'Nuevo grupo',exact:true}).click();
     await step(page,'wa-search-group','wa-search');await page.getByRole('textbox',{name:'Buscar contactos'}).fill('Ana');await step(page,'wa-group-contacts','wa-contact-list');
     await page.getByRole('button',{name:'Ana',exact:true}).click();await step(page,'wa-group-more','wa-contact-list');
     await page.getByRole('textbox',{name:'Buscar contactos'}).fill('Pedro');await page.getByRole('button',{name:'Pedro',exact:true}).click();await step(page,'wa-group-next','wa-next');await page.screenshot({path:`/private/tmp/tino-wa-contacts-${width}.png`});
     await page.getByRole('button',{name:'Siguiente',exact:true}).click();await step(page,'wa-group-name','wa-name');
     await page.getByRole('textbox',{name:'Nombre del grupo'}).fill('Amigos de la plaza');await page.getByRole('textbox',{name:'Nombre del grupo'}).press('Enter');await step(page,'wa-group-create','wa-create');await page.screenshot({path:`/private/tmp/tino-wa-group-name-${width}.png`});
     await page.getByRole('button',{name:'Crear grupo',exact:true}).click();await step(page,'group-done',null);await page.getByText('Creaste el grupo «Amigos de la plaza»',{exact:true}).waitFor();assert.equal(await page.locator('.jp-conversation .incoming').count(),0);
    }else{
     await step(page,'wa-search-contact','wa-search');await page.getByRole('textbox',{name:'Buscar contactos'}).fill('Lucia');await step(page,'wa-contact','wa-contact-list');await page.getByRole('button',{name:'Lucía',exact:true}).click();
     if(task==='message'){
      await step(page,'wa-write','wa-write');await page.getByRole('textbox',{name:'Mensaje de WhatsApp'}).fill('Hola Lucía, nos vemos el sábado.');await page.getByRole('textbox',{name:'Mensaje de WhatsApp'}).press('Tab');await step(page,'wa-send','wa-send');
      await page.getByRole('button',{name:'Enviar por WhatsApp'}).click();await step(page,'message-done',null);assert.equal(await page.locator('.jp-chat-bubble.outgoing').count(),1);
     }else{
      await step(page,'wa-attach','wa-attach');await page.getByRole('button',{name:'Adjuntar',exact:true}).click();await step(page,'wa-location','wa-location');await page.getByRole('button',{name:'Compartir ubicación',exact:true}).click();
      await step(page,'wa-share','wa-share');assert.equal(await page.locator('.jp-location-card').count(),0);await page.getByRole('button',{name:'Enviar ubicación actual',exact:false}).click();await step(page,'location-done',null);assert.equal(await page.locator('.jp-location-card').count(),1);
     }
     await page.screenshot({path:`/private/tmp/tino-guided-result-${task}-${width}.png`});
     await page.getByRole('button',{name:'Volver a chats',exact:true}).click();await page.locator('.jp-list button').filter({hasText:'Ana'}).click();assert.equal(await page.locator('.jp-chat-bubble.outgoing').count(),0);assert.equal(await page.locator('.jp-location-card').count(),0);
    }
   }
   assert.deepEqual(errors,[]);await page.screenshot({path:`/private/tmp/tino-guided-${task}-${width}.png`});console.log('GUIDED_FLOW_PASS',task,width);
  }catch(error){await page.screenshot({path:`/private/tmp/tino-guided-failure-${task}-${width}.png`});throw error;}finally{await page.close();}
 }
 console.log('ALL_FOUR_GUIDED_FLOWS_DESKTOP_AND_MOBILE_PASS');
}finally{await browser.close();}
