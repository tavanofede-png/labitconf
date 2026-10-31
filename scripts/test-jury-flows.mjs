import {createRequire} from 'node:module';
import assert from 'node:assert/strict';
const require=createRequire('/Users/jazminourens/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/package.json');
const {chromium}=require('playwright');
const browser=await chromium.launch({channel:'chrome',headless:true});
const base=process.env.JURY_TEST_ORIGIN||'http://127.0.0.1:5173';
try{
 const stage=await browser.newPage({viewport:{width:1440,height:1000}});await stage.goto(base);
 await stage.getByRole('heading',{name:'Probá TINO, a tu manera.'}).waitFor();
 await stage.frameLocator('iframe').getByRole('button',{name:'DEMO',exact:true}).waitFor();
 await stage.screenshot({path:'/private/tmp/tino-jury-guide.png',fullPage:true});console.log('JUDGE_GUIDE_STAGE_PASS');await stage.close();
 for(const [index,role] of ['user','family','institution'].entries()){
  const page=await browser.newPage({viewport:{width:1024,height:768}});await page.goto(base+'/app');await page.locator('.j-role').nth(index).click();
  if(role==='institution'){
   await page.getByLabel('Nombre de la institución',{exact:true}).fill('Laboratorio de inclusión');await page.getByRole('button',{name:'Continuar',exact:true}).click();
   for(let step=0;step<3;step++){await page.locator('.j-options button').first().click();await page.getByRole('button',{name:'Continuar',exact:true}).click();}
  }else{
   await page.getByLabel('Nombre y apellido',{exact:true}).fill('Jurado TINO');await page.getByRole('button',{name:'Continuar',exact:true}).click();
   for(let step=0;step<2;step++){await page.locator('.j-options button').first().click();await page.getByRole('button',{name:'Continuar',exact:true}).click();}
  }
  await page.getByLabel('Nombre y apellido',{exact:true}).fill('Jurado TINO');await page.getByLabel(role==='institution'?'Correo institucional':'Correo electrónico',{exact:true}).fill('jurado@example.com');await page.getByLabel('Teléfono',{exact:true}).fill('+541112345678');await page.getByLabel('Localidad',{exact:true}).fill('Buenos Aires');
  await page.getByRole('button',{name:'Finalizar',exact:true}).click();await page.getByRole('heading',{name:'¡Todo listo!'}).waitFor();await page.waitForFunction(()=>{const image=document.querySelector('.j-complete img');return image?.complete&&image?.naturalWidth>0;},{},{timeout:30000});
  await page.getByRole('button',{name:'Empezar con TINO'}).click();await page.screenshot({path:`/private/tmp/tino-onboarding-${role}.png`});console.log('ONBOARDING_PASS',role);await page.close();
 }
 for(const [task,prompt] of [['message','Quiero mandar un mensaje por WhatsApp'],['group','Ayudame a crear un grupo de WhatsApp'],['location','Quiero compartir mi ubicación'],['email','Quiero mandar un mail por Gmail']]){
  const page=await browser.newPage({viewport:{width:1024,height:768}});await page.goto(base+'/app');await page.getByRole('button',{name:'DEMO',exact:true}).click();await page.getByText('Hola, soy TINO.',{exact:false}).waitFor();
  await page.getByRole('textbox',{name:'Mensaje para TINO',exact:true}).fill(prompt);const response=page.waitForResponse(r=>r.url().includes('resource=chat'));await page.getByRole('button',{name:'Enviar mensaje',exact:true}).click();assert.equal((await response).status(),200);
  await page.getByRole('button',{name:'Continuar paso a paso'}).click();await page.locator('.jp-app').waitFor();
  if(task==='message'){await page.locator('.jp-list button').first().click();await page.getByRole('textbox',{name:'Mensaje de WhatsApp'}).fill('Hola Lucía, nos vemos el sábado.');await page.getByRole('button',{name:'Enviar por WhatsApp'}).click();await page.getByText('Hola Lucía, nos vemos el sábado.',{exact:false}).waitFor();}
  if(task==='group'){await page.getByRole('button',{name:'Nuevo grupo',exact:true}).click();await page.locator('.jp-selection button[aria-pressed]').nth(0).click();await page.locator('.jp-selection button[aria-pressed]').nth(1).click();await page.getByRole('button',{name:'Siguiente',exact:true}).click();await page.getByLabel('Nombre del grupo').fill('Amigos de la plaza');await page.getByRole('button',{name:'Crear grupo',exact:false}).click();await page.getByText('Creaste el grupo',{exact:false}).waitFor();}
  if(task==='location'){await page.locator('.jp-list button').first().click();await page.getByRole('button',{name:'Adjuntar',exact:true}).click();await page.getByRole('button',{name:'Compartir ubicación',exact:true}).click();await page.getByRole('button',{name:'Compartir esta ubicación',exact:false}).click();await page.getByText('Ubicación compartida',{exact:false}).waitFor();}
  if(task==='email'){await page.getByRole('button',{name:'Redactar',exact:true}).click();await page.getByRole('textbox',{name:'Destinatario del correo'}).fill('lucia@example.com');await page.getByRole('textbox',{name:'Asunto del correo'}).fill('Nos vemos el sábado');await page.getByRole('textbox',{name:'Cuerpo del correo'}).fill('Hola Lucía, nos vemos en la plaza a las cinco.');await page.getByRole('button',{name:'Enviar',exact:true}).click();await page.getByRole('button',{name:'Confirmar envío',exact:true}).click();await page.getByText('Mensaje enviado',{exact:true}).waitFor();}
  await page.screenshot({path:`/private/tmp/tino-practice-${task}.png`});console.log('PRACTICE_REAL_AI_PASS',task);await page.close();
 }
 console.log('ALL_JURY_FLOWS_PASS');
}finally{await browser.close();}
