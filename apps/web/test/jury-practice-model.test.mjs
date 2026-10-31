import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {transform} from 'esbuild';
const steps=(await import('../../api/src/jury-guide-steps.mjs')).default;
const source=(await readFile(new URL('../src/jury-practice-model.ts',import.meta.url),'utf8')).replace(/import guideSource[^;]+;/,`const guideSource=${JSON.stringify(steps)};`);
const {code}=await transform(source,{loader:'ts',format:'esm',target:'es2022'});
const {createPracticeState,practiceReducer:r,practiceGuide:guide,resolvePracticeTask}=await import('data:text/javascript;base64,'+Buffer.from(code).toString('base64'));
const start=task=>r(createPracticeState(),{type:'START',task});
const apply=(s,...actions)=>actions.reduce(r,s);
test('group guidance advances only with real selections and creates an empty new group without invented incoming messages',()=>{
 let s=start('group');assert.equal(guide(s,'android').target,'app-whatsapp');assert.equal(guide(s,'whatsapp').target,'wa-plus');
 s=r(s,{type:'WA_PLUS'});assert.equal(guide(s,'whatsapp').target,'wa-new-group');
 s=r(s,{type:'WA_NEW_GROUP'});assert.equal(guide(s,'whatsapp').target,'wa-search');
 assert.equal(r(s,{type:'WA_NEXT'}),s);
 s=r(s,{type:'WA_CONTACT',id:'ana'});assert.equal(guide(s,'whatsapp').target,'wa-contact-list');
 s=r(s,{type:'WA_CONTACT',id:'pedro'});assert.equal(guide(s,'whatsapp').target,'wa-next');
 s=r(s,{type:'WA_NEXT'});assert.equal(r(s,{type:'WA_CREATE'}),s);
 s=r(s,{type:'WA_NAME',value:'Amigos de la plaza'});assert.equal(guide(s,'whatsapp').target,'wa-name');
 s=r(s,{type:'WA_NAME_READY'});assert.equal(guide(s,'whatsapp').target,'wa-create');
 s=r(s,{type:'WA_CREATE'});assert.equal(s.complete,true);assert.deepEqual(s.wa.groups[0].members,['ana','pedro']);
 assert.equal(s.wa.messages[s.wa.chatId].length,1);assert.equal(s.wa.messages[s.wa.chatId][0].kind,'system');
 assert.equal(r(s,{type:'WA_CREATE'}),s);assert.equal(guide(s,'android'),null);
});
test('message drafts persist across navigation, sending is explicit and messages belong only to their recipient',()=>{
 let s=start('message');assert.equal(r(s,{type:'WA_SEND'}),s);
 s=apply(s,{type:'WA_CONTACT',id:'lucia'},{type:'WA_DRAFT',value:'Nos vemos mañana.'});
 assert.equal(guide(s,'whatsapp').target,'wa-write');s=r(s,{type:'WA_DRAFT_READY'});assert.equal(guide(s,'whatsapp').target,'wa-send');
 s=apply(s,{type:'WA_BACK'},{type:'WA_CONTACT',id:'ana'});assert.equal(s.wa.drafts.lucia,'Nos vemos mañana.');assert.equal(s.wa.messages.ana,undefined);
 s=apply(s,{type:'WA_BACK'},{type:'WA_CONTACT',id:'lucia'},{type:'WA_SEND'});
 assert.equal(s.complete,true);assert.equal(s.wa.messages.lucia[0].text,'Nos vemos mañana.');assert.equal(s.wa.messages.ana,undefined);assert.equal(r(s,{type:'WA_SEND'}),s);
});
test('location can only be shared after opening attachments and the location picker, never while only previewing',()=>{
 let s=start('location');s=r(s,{type:'WA_CONTACT',id:'pedro'});assert.equal(guide(s,'whatsapp').target,'wa-attach');
 assert.equal(r(s,{type:'WA_SHARE'}),s);s=r(s,{type:'WA_ATTACH'});assert.equal(guide(s,'whatsapp').target,'wa-location');
 s=r(s,{type:'WA_LOCATION'});assert.equal(s.wa.messages.pedro,undefined);assert.equal(guide(s,'whatsapp').target,'wa-share');
 s=r(s,{type:'WA_SHARE'});assert.equal(s.wa.messages.pedro[0].kind,'location');assert.equal(s.wa.messages.lucia,undefined);assert.equal(s.complete,true);
});
test('Gmail highlights each committed field, validates the address, preserves its draft and requires explicit send confirmation',()=>{
 let s=start('email');assert.equal(guide(s,'android').target,'app-gmail');s=r(s,{type:'GM_COMPOSE'});assert.equal(guide(s,'gmail').target,'gm-to');
 s=apply(s,{type:'GM_SET',field:'to',value:'bad'},{type:'GM_READY',field:'to'});assert.equal(guide(s,'gmail').target,'gm-to');assert.equal(r(s,{type:'GM_SEND'}),s);
 s=apply(s,{type:'GM_SET',field:'to',value:'lucia@example.com'},{type:'GM_READY',field:'to'});assert.equal(guide(s,'gmail').target,'gm-subject');
 s=apply(s,{type:'GM_SET',field:'subject',value:'El sábado'},{type:'GM_READY',field:'subject'});assert.equal(guide(s,'gmail').target,'gm-body');
 s=apply(s,{type:'GM_SET',field:'body',value:'Nos vemos en la plaza.'},{type:'GM_READY',field:'body'});assert.equal(guide(s,'gmail').target,'gm-send');
 s=r(s,{type:'GM_BACK'});assert.equal(s.gm.body,'Nos vemos en la plaza.');s=apply(s,{type:'GM_COMPOSE'},{type:'GM_SEND'});assert.equal(s.gm.sent.length,0);assert.equal(guide(s,'gmail').target,'gm-confirm');
 s=r(s,{type:'GM_CONFIRM'});assert.equal(s.gm.sent.length,1);assert.equal(s.gm.sent[0].to,'lucia@example.com');assert.equal(s.gm.folder,'sent');assert.equal(s.complete,true);assert.equal(r(s,{type:'GM_CONFIRM'}),s);
});
test('task routing respects greetings, negations and contact-name questions; cancel and wrong-app navigation preserve user work',()=>{
 assert.equal(resolvePracticeTask('Hola TINO'),null);assert.equal(resolvePracticeTask('No quiero compartir mi ubicación'),null);assert.equal(resolvePracticeTask('Cuáles son los nombres de los contactos'),null);
 assert.equal(resolvePracticeTask('Ayudame a crear un grupo'),'group');assert.equal(resolvePracticeTask('Quiero mandar un mail'),'email');assert.equal(resolvePracticeTask('Compartir mi ubicación'),'location');
 const s=start('group');assert.equal(guide(s,'gmail').target,'android-home');assert.equal(guide(r(s,{type:'CANCEL'}),'android'),null);
});

test('wrong navigation points back to the right task rather than switching the goal',()=>{
 let group=r(start('group'),{type:'WA_CONTACT',id:'ana'});assert.equal(guide(group,'whatsapp').target,'wa-back');
 group=r(group,{type:'WA_BACK'});assert.equal(guide(group,'whatsapp').target,'wa-plus');
 const message=apply(start('message'),{type:'WA_PLUS'},{type:'WA_NEW_GROUP'});assert.equal(guide(message,'whatsapp').target,'wa-back');
 const email=r(start('email'),{type:'GM_OPEN',id:1});assert.equal(guide(email,'gmail').target,'gm-back');
});

test('writing in a newly created group cannot erase its completed state or manufacture incoming replies',()=>{
 let s=apply(start('group'),{type:'WA_PLUS'},{type:'WA_NEW_GROUP'},{type:'WA_CONTACT',id:'ana'},{type:'WA_CONTACT',id:'pedro'},{type:'WA_NEXT'},{type:'WA_NAME',value:'La plaza'},{type:'WA_CREATE'});
 s=apply(s,{type:'WA_DRAFT',value:'Hola equipo'},{type:'WA_SEND'});
 assert.equal(s.complete,true);assert.equal(guide(s,'whatsapp').id,'group-done');assert.equal(s.wa.messages[s.wa.chatId].length,2);assert.equal(s.wa.messages[s.wa.chatId][1].outgoing,true);
});
