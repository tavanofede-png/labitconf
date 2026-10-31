import test from 'node:test';
import assert from 'node:assert/strict';
import { validateGuidedReply } from '../src/guided-reply.mjs';
const screen = { id:'current', controls:[{id:'display',label:'Pantalla',role:'button'}] };
const step = {kind:'step',action:'tap',message:'Tocá Pantalla.',goal:'Cambiar el brillo.',targetApp:'Ajustes',progress:'Esperando que abra Pantalla.',screenId:'current',controlId:'display',endReason:''};
test('one real action carries its current pointer and the complete ephemeral objective',()=>{
  const reply=validateGuidedReply(step,screen);
  assert.equal(reply.message,'Tocá Pantalla.'); assert.equal(reply.context.goal,'Cambiar el brillo.');
  assert.equal(reply.point.controlId,'display'); assert.equal(reply.context.expectsReply,false);
  const home=validateGuidedReply({...step,action:'home',message:'Deslizá desde el borde inferior hacia arriba para volver a Inicio.',controlId:''},screen);
  assert.equal(home.point,undefined); assert.equal(home.action,'home');
});
test('multiple actions guessed targets and home gestures pointing to app controls are rejected',()=>{
  for(const change of [{message:'Abrí Ajustes y tocá Pantalla.'},{message:'Abrí Ajustes. Luego buscá Pantalla.'},
    {message:'1. Abrí Ajustes. 2. Tocá Pantalla.'},{message:'Toco Pantalla por vos.'},{message:'x'.repeat(161)},
    {controlId:'invented'},{screenId:'stale'},{action:'home'},{goal:''},{action:'tap',controlId:''},
    {action:'swipe',controlId:'',message:'Deslizá para volver a Inicio.'}])
    assert.throws(()=>validateGuidedReply({...step,...change},screen));
});
test('conversation remains freely generated and endings carry no farewell or retained goal',()=>{
  const conversation={...step,kind:'conversation',action:'none',message:'Sí, estoy acá. Contame qué te pasa.',screenId:'',controlId:'',goal:'',targetApp:'',progress:''};
  assert.equal(validateGuidedReply(conversation,null).message,conversation.message);
  assert.equal(validateGuidedReply({...conversation,message:'Una respuesta general extensa. '.repeat(20)},null).kind,'conversation');
  assert.deepEqual(validateGuidedReply({...conversation,kind:'end',message:'',endReason:'user_end'},null),{kind:'end',endReason:'user_end'});
  assert.throws(()=>validateGuidedReply({...conversation,kind:'end',message:'Chau',endReason:'user_end'},null));
  assert.throws(()=>validateGuidedReply({...step,kind:'end',message:'',endReason:'task_completed'},screen));
});
