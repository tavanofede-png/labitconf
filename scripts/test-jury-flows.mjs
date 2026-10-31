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
 await import('./test-jury-guided-practice.mjs');
 console.log('ALL_JURY_FLOWS_PASS');
}finally{await browser.close();}
