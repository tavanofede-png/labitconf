import { useEffect, useRef, useState } from 'react';
import type { Dispatch, FormEvent, SetStateAction } from 'react';
import { ArrowLeftIcon, ArrowRightIcon, BuildingsIcon, DeviceMobileIcon, MicrophoneIcon, PaperPlaneTiltIcon, UsersThreeIcon, XIcon, ListIcon, StopIcon, SignOutIcon, SquaresFourIcon, CircleIcon } from '@phosphor-icons/react';
import { JuryDashboard, JuryFamily } from './JuryDashboard';
import { JuryPractice } from './JuryPractice';
import type { JuryPracticeTask } from './JuryPractice';
import { juryApi } from './jury-api';
import './jury.css';
import './jury-bubble.css';
import { JuryAndroidHome, AndroidStatusBar } from './JuryAndroidHome';
import type { JurySurface } from './JuryAndroidHome';
import { useJuryWake } from './useJuryWake';

type Role = 'user' | 'family' | 'institution';
type Message = { role: 'user' | 'assistant'; text: string };
const names: Record<Role,string> = { user: 'Usuario', family: 'Grupo familiar', institution: 'Instituciones' };

export function JuryApp() {
  const [page, setPage] = useState<'welcome'|'onboard'|'complete'|'home'|'login'>('welcome');
  const [role,setRole] = useState<Role>('user'); const [menu,setMenu] = useState(false);
  const [organization,setOrganization] = useState('Laboratorio de inclusión');
  const [surface,setSurface]=useState<JurySurface>('tino');const [apps,setApps]=useState(false);
  const [whatsappTask,setWhatsappTask]=useState<JuryPracticeTask>('message');const [gmailTask,setGmailTask]=useState<JuryPracticeTask>('email');
  const [whatsappOpened,setWhatsappOpened]=useState(false);const [gmailOpened,setGmailOpened]=useState(false);
  const [bubbleOpen,setBubbleOpen]=useState(false);const [chatStarted,setChatStarted]=useState(false);const [chatClosed,setChatClosed]=useState(false);
  const [bubblePosition,setBubblePosition]=useState({right:true,top:Math.max(100,window.innerHeight*.35)});
  const [mic,setMic] = useState(false); const [voiceRead,setVoiceRead] = useState(true); const [notice,setNotice] = useState('');
  const [chatActive,setChatActive]=useState(false);const [wakeMessage,setWakeMessage]=useState<{id:number;text:string}|null>(null);
  const outside=surface!=='tino';const popup=outside&&bubbleOpen;
  const chatVisible=popup||(surface==='tino'&&page==='home'&&role==='user');
  const wake=useJuryWake({enabled:outside&&mic&&!chatActive,onWake:()=>{setChatStarted(true);setChatClosed(false);setBubbleOpen(true);window.speechSynthesis?.cancel();},onMessage:text=>setWakeMessage({id:Date.now(),text})});
  function navigate(next: Role) { setRole(next);setPage('home');setSurface('tino');setChatStarted(true);setChatClosed(false);setBubbleOpen(false);setMenu(false);window.scrollTo(0,0); }
  function back() { setPage('welcome');setBubbleOpen(false); }
  function open(next:JurySurface) {
    if(next!=='tino'&&!mic)void permission(true);
    setMenu(false);setBubbleOpen(false);setSurface(next);setApps(false);window.scrollTo(0,0);
    if(next==='tino'&&page==='home'){setChatStarted(true);setChatClosed(false);}
    if(next==='whatsapp')setWhatsappOpened(true);
    if(next==='gmail')setGmailOpened(true);
  }
  function startPractice(task:JuryPracticeTask) {
    if(task==='email'||task==='gmail'){setGmailTask(task);open('gmail');}
    else {setWhatsappTask(task);open('whatsapp');}
  }
  async function permission(activate=false) {
    if(mic&&!activate) { setMic(false); setNotice(''); return; }
    try { const stream=await navigator.mediaDevices.getUserMedia({audio:true}); stream.getTracks().forEach(t=>t.stop()); setMic(true); setNotice('Micrófono activado.'); }
    catch { setNotice('Permití el micrófono en el navegador. También podés escribir.'); }
  }
  useEffect(()=>{document.title='TINO · La ayuda justa, cuando la necesitás';},[]);
  return <div className={`jury-app j-surface-${surface}`}>
    <div className="j-tino-surface" hidden={outside}>
      <header className="j-header"><button className="j-logo" onClick={back} aria-label="TINO, volver atrás"><img src="/brand/tino-logo.webp" alt="TINO"/></button>{page==='home' && <span className="j-space">{names[role]}</span>}<div className="j-header-end">{page==='welcome'?<button className="j-button j-dark" onClick={()=>navigate('user')}>DEMO</button>:<button className="j-icon-button" aria-label="Abrir menú" onClick={()=>setMenu(true)}><ListIcon size={28}/></button>}<button className="j-icon-button j-exit-app" aria-label="Salir de TINO al inicio de Android" title="Salir al inicio de Android" onClick={()=>open('android')}><SignOutIcon size={27}/></button></div></header>
      {page==='welcome' && <main className="j-welcome"><img className="j-welcome-ghost" src="/brand/tino-mascot.png" alt="TINO te saluda"/><h1>La ayuda justa,<br/>cuando la necesitás.</h1><p>Tu teléfono, a tu manera.</p><div className="j-role-grid">{(['user','family','institution'] as Role[]).map((r,i)=><button className="j-role" key={r} onClick={()=>{setRole(r);setPage('onboard');}}>{i===0?<DeviceMobileIcon/>:i===1?<UsersThreeIcon/>:<BuildingsIcon/>}<strong>{names[r]}</strong><span>{['Quiero ayuda con mi teléfono','Quiero acompañar a alguien','Quiero entender y actuar'][i]}</span><ArrowRightIcon/></button>)}</div><button className="j-account-link" onClick={()=>setPage('login')}>Ya tengo cuenta <ArrowRightIcon size={18}/></button></main>}
      {page==='onboard' && <JuryOnboarding role={role} onBack={()=>setPage('welcome')} onComplete={org=>{if(org)setOrganization(org);setPage('complete');}}/>}
      {page==='complete' && <main className="j-complete"><img src="/brand/tino-listo.webp" alt="TINO hace OK y dice Listo"/><h1>¡Todo listo!</h1><button className="j-button" onClick={()=>navigate(role)}>Empezar con TINO <ArrowRightIcon/></button></main>}
      {page==='login' && <JuryLogin onDone={navigate} onBack={()=>setPage('welcome')}/>}
      {page==='home' && (role==='institution'?<JuryDashboard organization={organization}/>:role==='family'?<JuryFamily/>:null)}
      {menu && <div className="j-drawer-backdrop" onClick={()=>setMenu(false)}><aside className="j-drawer" aria-label="Menú de TINO" onClick={e=>e.stopPropagation()}><div className="j-drawer-top"><img src="/brand/tino-logo.webp" alt="TINO"/><button className="j-icon-button" aria-label="Cerrar menú" onClick={()=>setMenu(false)}><XIcon/></button></div><p className="j-space">Estás en {names[role]}</p>{(['user','family','institution'] as Role[]).map(r=><button className="j-menu-item" key={r} onClick={()=>navigate(r)}>{r==='user'?'TINO Chat':names[r]}<ArrowRightIcon/></button>)}<div className="j-drawer-controls"><h3>Permisos</h3><JurySwitch label="Micrófono" checked={mic} change={()=>void permission()}/><JurySwitch label="Respuestas por voz" checked={voiceRead} change={()=>{setVoiceRead(v=>!v);window.speechSynthesis?.cancel();}}/>{notice && <p role="status">{notice}</p>}<button className="j-button j-outline" onClick={()=>{setMenu(false);setPage('welcome');window.speechSynthesis?.cancel();}}>Volver al inicio</button></div></aside></div>}
    </div>
    {surface==='android'&&<JuryAndroidHome open={open} apps={apps}/>}
    {outside&&<div className={`j-wake-status ${wake.status==='ready'?'is-ready':''}`} role="status"><MicrophoneIcon size={19}/><span>{wake.capturing?'Te escucho…':wake.status==='ready'?'Decí «Hola TINO»':wake.status==='starting'?'Preparando escucha…':wake.error||(mic?'Voz activada':'Activá la voz para decir «Hola TINO»')}</span><button aria-label={mic&&wake.status==='error'?'Reintentar escucha por voz':mic?'Desactivar escucha por voz':'Activar escucha por voz'} onClick={()=>mic&&wake.status==='error'?wake.retry():void permission()}>{mic&&wake.status==='error'?'Reintentar':mic?'Pausar':'Activar voz'}</button></div>}
    {whatsappOpened&&<div className="j-external-app" data-jury-external-screen hidden={surface!=='whatsapp'}><AndroidStatusBar/><div className="jury-practice"><JuryPractice key={whatsappTask} task={whatsappTask}/></div></div>}
    {gmailOpened&&<div className="j-external-app" data-jury-external-screen hidden={surface!=='gmail'}><AndroidStatusBar/><div className="jury-practice"><JuryPractice key={gmailTask} task={gmailTask}/></div></div>}
    {chatStarted&&!chatClosed&&<div className={popup?'j-chat-popup':'j-chat-page'} style={{display:chatVisible?'block':'none'}} role={popup?'dialog':undefined} aria-label={popup?'Chat flotante de TINO':undefined}>
      {popup&&<div className="j-popup-heading"><strong>TINO</strong><div><button className="j-popup-open-app" onClick={()=>{setPage('home');setRole('user');open('tino');}}>Abrir TINO</button><button className="j-icon-button" aria-label="Minimizar conversación" onClick={()=>{wake.cancel();setBubbleOpen(false);}}>−</button><button className="j-icon-button" aria-label="Cerrar y borrar conversación" onClick={()=>{wake.cancel();setWakeMessage(null);setChatActive(false);setBubbleOpen(false);setChatClosed(true);window.speechSynthesis?.cancel();}}><XIcon/></button></div></div>}
      <JuryChat mic={mic} setMic={setMic} voiceRead={voiceRead} visible={chatVisible} startPractice={startPractice} wakeMessage={wakeMessage} wakeCapture={wake.capturing} wakePartial={wake.partial} cancelWake={wake.cancel} finishWake={wake.finish} onActivity={setChatActive}/>
    </div>}
    {outside&&<JuryBubble open={popup} position={bubblePosition} setPosition={setBubblePosition} onOpen={()=>{if(popup)wake.cancel();setChatStarted(true);setChatClosed(false);setBubbleOpen(v=>!v);}}/>}
    <nav className="j-android-nav" aria-label="Navegación de Android"><button aria-label="Atrás en Android" onClick={()=>{if(outside)open('android');else back();}}><ArrowLeftIcon size={22}/></button><button aria-label="Inicio de Android" onClick={()=>open('android')}><CircleIcon size={21}/></button><button aria-label="Ver aplicaciones de Android" onClick={()=>{open('android');setApps(true);}}><SquaresFourIcon size={22}/></button></nav>
  </div>;
}

function JuryBubble({open,onOpen,position,setPosition}:{open:boolean;onOpen:()=>void;position:{right:boolean;top:number};setPosition:Dispatch<SetStateAction<{right:boolean;top:number}>>}) {
 const [dragging,setDragging]=useState(false);const drag=useRef<{x:number;y:number;top:number;moved:boolean}|null>(null);
 useEffect(()=>{const resize=()=>setPosition(p=>({...p,top:Math.max(16,Math.min(p.top,window.innerHeight-140))}));resize();window.addEventListener('resize',resize);return()=>window.removeEventListener('resize',resize);},[setPosition]);
 return <button className={`j-floating-bubble ${dragging?'is-dragging':''}`} aria-label={open?'Minimizar TINO':'Abrir chat flotante de TINO'} aria-expanded={open} style={{top:position.top,left:position.right?'auto':14,right:position.right?14:'auto'}} onPointerDown={e=>{e.currentTarget.setPointerCapture(e.pointerId);drag.current={x:e.clientX,y:e.clientY,top:position.top,moved:false};setDragging(true);}} onPointerMove={e=>{const start=drag.current;if(!start)return;if(Math.abs(e.clientX-start.x)+Math.abs(e.clientY-start.y)>5)start.moved=true;if(start.moved){e.currentTarget.style.left=`${Math.max(8,Math.min(window.innerWidth-78,e.clientX-34))}px`;e.currentTarget.style.right='auto';setPosition(p=>({...p,top:Math.max(15,Math.min(window.innerHeight-83,start.top+e.clientY-start.y))}));}}} onPointerUp={e=>{const start=drag.current;drag.current=null;setDragging(false);setPosition(p=>({...p,right:e.clientX>window.innerWidth/2}));e.currentTarget.style.left=e.clientX>window.innerWidth/2?'auto':'14px';e.currentTarget.style.right=e.clientX>window.innerWidth/2?'14px':'auto';if(!start?.moved)onOpen();}} onPointerCancel={()=>{drag.current=null;setDragging(false);}} onClick={e=>{if(e.detail===0)onOpen();}}><img src="/brand/tino-mascot.png" alt=""/></button>;
}

export function JurySwitch({label,checked,change}:{label:string;checked:boolean;change:()=>void}) { return <button className="j-switch-row" role="switch" aria-checked={checked} onClick={change}><span>{label}</span><span className={`j-switch ${checked?'is-on':''}`}><i/></span></button>; }

function JuryChat({mic,setMic,voiceRead,visible,startPractice,wakeMessage,wakeCapture,wakePartial,cancelWake,finishWake,onActivity}:{mic:boolean;setMic:(v:boolean)=>void;voiceRead:boolean;visible:boolean;startPractice:(task:JuryPracticeTask)=>void;wakeMessage:{id:number;text:string}|null;wakeCapture:boolean;wakePartial:string;cancelWake:()=>void;finishWake:()=>void;onActivity:(active:boolean)=>void}) {
  const [messages,setMessages]=useState<Message[]>([]); const [text,setText]=useState(''); const [typing,setTyping]=useState(true);
  const [listening,setListening]=useState(false); const [seconds,setSeconds]=useState(0); const [partial,setPartial]=useState(''); const [error,setError]=useState('');
  const [suggestion,setSuggestion]=useState<JuryPracticeTask|null>(null); const recorder=useRef<MediaRecorder|null>(null);const audioStream=useRef<MediaStream|null>(null);const recordingTimeout=useRef<ReturnType<typeof setTimeout>|undefined>(undefined);const startingRecorder=useRef(false); const busy=useRef(false); const cancelled=useRef(false); const end=useRef<HTMLDivElement>(null);
  const request=useRef<AbortController|null>(null);const visibleRef=useRef(visible);
  const [sending,setSending]=useState(false);const [speaking,setSpeaking]=useState(false);const consumedWake=useRef<number|undefined>(undefined);
  useEffect(()=>{onActivity(sending||speaking||listening||!!partial);},[sending,speaking,listening,partial,onActivity]);
  useEffect(()=>{if(!voiceRead){window.speechSynthesis?.cancel();setSpeaking(false);}},[voiceRead]);
  useEffect(()=>{if(wakeMessage&&consumedWake.current!==wakeMessage.id){consumedWake.current=wakeMessage.id;void send(wakeMessage.text,true);}},[wakeMessage]);
  useEffect(()=>{const timer=setTimeout(()=>{setMessages(current=>current.length?current:[{role:'assistant',text:'Hola, soy TINO. Contame qué necesitás hacer con tu teléfono. Podés escribirme o hablarme.'}]);if(!busy.current)setTyping(false);},2000);return()=>{clearTimeout(timer);clearTimeout(recordingTimeout.current);cancelled.current=true;request.current?.abort();if(recorder.current?.state==='recording')recorder.current.stop();audioStream.current?.getTracks().forEach(track=>track.stop());window.speechSynthesis?.cancel();};},[]);
  useEffect(()=>{visibleRef.current=visible;if(!visible){setSpeaking(false);window.speechSynthesis?.cancel();cancelled.current=true;if(recorder.current?.state==='recording')recorder.current.stop();audioStream.current?.getTracks().forEach(track=>track.stop());setListening(false);}},[visible]);
  useEffect(()=>{if(!listening&&!wakeCapture)return;setSeconds(0);const timer=setInterval(()=>setSeconds(s=>s+1),1000);return()=>clearInterval(timer);},[listening,wakeCapture]);
  useEffect(()=>{end.current?.scrollIntoView({behavior:'smooth',block:'nearest'});},[messages,typing]);
  async function send(value:string,spoken=false) {
    const clean=value.trim();if(!clean||busy.current)return;busy.current=true;setSending(true);setError('');setText('');setSuggestion(null);
    const history=[...messages,{role:'user' as const,text:clean}];setMessages(history);setTyping(true);const began=Date.now();
    const controller=new AbortController();request.current=controller;
    try { const reply=await juryApi<{instruction:string}>('chat',{text:clean,history:messages,screen:document.querySelector('[data-jury-external-screen]:not([hidden])')?.textContent?.slice(0,2000)||''},'',controller.signal); await new Promise(resolve=>setTimeout(resolve,Math.max(0,2000-(Date.now()-began))));if(controller.signal.aborted)return;setMessages(m=>[...m,{role:'assistant',text:reply.instruction}]);
      const normalized=clean.toLowerCase();if(/grupo/.test(normalized))setSuggestion('group');else if(/ubicaci[oó]n|locaci[oó]n/.test(normalized))setSuggestion('location');else if(/crear.*(gmail|cuenta)/.test(normalized))setSuggestion('gmail');else if(/gmail|correo|mail/.test(normalized))setSuggestion('email');else if(/mensaje|whatsapp|wasap|wsp/.test(normalized))setSuggestion('message');
      if(spoken&&voiceRead&&visibleRef.current&&'speechSynthesis'in window){const utterance=new SpeechSynthesisUtterance(reply.instruction);utterance.lang='es-AR';utterance.voice=window.speechSynthesis.getVoices().find(v=>v.lang.startsWith('es'))||null;utterance.onend=()=>setSpeaking(false);utterance.onerror=()=>setSpeaking(false);setSpeaking(true);window.speechSynthesis.speak(utterance);}
    } catch(e){if(!controller.signal.aborted){setError(e instanceof Error?e.message:'No pude enviar la consulta.');setText(clean);}}finally{busy.current=false;setSending(false);setTyping(false);}
  }
  async function record() {
    if(listening){recorder.current?.stop();return;}
    if(startingRecorder.current||busy.current)return;startingRecorder.current=true;
    if(!navigator.mediaDevices?.getUserMedia||!window.MediaRecorder){setError('Abrí el link en un navegador actualizado para usar el micrófono.');startingRecorder.current=false;return;}
    cancelled.current=false;window.speechSynthesis?.cancel();setError('');setPartial('');setSeconds(0);
    try {
      const stream=await navigator.mediaDevices.getUserMedia({audio:{echoCancellation:true,noiseSuppression:true}});if(!visibleRef.current){stream.getTracks().forEach(track=>track.stop());return;}audioStream.current=stream;setMic(true);
      const mime=['audio/webm;codecs=opus','audio/mp4','audio/webm'].find(type=>MediaRecorder.isTypeSupported(type));
      const instance=new MediaRecorder(stream,mime?{mimeType:mime,audioBitsPerSecond:64000}:undefined);recorder.current=instance;const chunks:BlobPart[]=[];
      instance.ondataavailable=event=>{if(event.data.size)chunks.push(event.data);};
      instance.onstop=()=>{clearTimeout(recordingTimeout.current);stream.getTracks().forEach(track=>track.stop());recorder.current=null;audioStream.current=null;setListening(false);if(cancelled.current)return;setTyping(true);setPartial('Transcribiendo…');const controller=new AbortController();request.current=controller;
        void(async()=>{try{const blob=new Blob(chunks,{type:instance.mimeType});const base64=await new Promise<string>((resolve,reject)=>{const reader=new FileReader();reader.onload=()=>resolve(String(reader.result).split(',')[1]);reader.onerror=reject;reader.readAsDataURL(blob);});const result=await juryApi<{text:string}>('transcribe',{audioBase64:base64,mimeType:instance.mimeType},'',controller.signal);if(controller.signal.aborted)return;setTyping(false);await send(result.text,true);}catch(e){if(!controller.signal.aborted){setTyping(false);setError(e instanceof Error?e.message:'No pude transcribir la grabación.');}}finally{setPartial('');}})();
      };
      instance.start(250);setListening(true);recordingTimeout.current=setTimeout(()=>{if(instance.state==='recording')instance.stop();},45000);
    }catch{setMic(false);setListening(false);setError('Permití el micrófono: Menú > Permisos > Activar micrófono.');}finally{startingRecorder.current=false;}
  }
  return <main className="j-chat" data-microphone-permitted={mic}><div className="j-chat-intro"><img src="/brand/tino-mascot.png" alt="TINO"/><h1>¿En qué te ayudo hoy?</h1></div><div className="j-messages" aria-live="polite">{messages.map((m,i)=><div key={i} className={`j-message-row ${m.role}`} >{m.role==='assistant'&&<img className="j-avatar" src="/brand/tino-mascot.png" alt=""/>}<div className="j-message">{m.text}</div></div>)}{typing&&<div className="j-message-row assistant"><img className="j-avatar" src="/brand/tino-mascot.png" alt=""/><span className="j-dots" aria-label="TINO está preparando una respuesta"><i/><i/><i/></span></div>}{suggestion&&<button className="j-button j-outline j-continue" onClick={()=>startPractice(suggestion)}>Continuar paso a paso <ArrowRightIcon/></button>}<div ref={end}/></div><div className="j-composer-wrap">{error&&<p className="j-error" role="alert">{error}</p>}{!listening&&!wakeCapture&&partial&&<p role="status">{partial}</p>}{listening||wakeCapture?<div className="j-recording"><span className="j-rec-dot"/><strong>{String(Math.floor(seconds/60)).padStart(2,'0')}:{String(seconds%60).padStart(2,'0')}</strong><span className="j-wave">▂ ▅ ▃ █ ▄ ▆ ▂ ▅</span><span>{wakeCapture?wakePartial||'Te escucho…':partial||'Te escucho…'}</span><button aria-label="Cancelar grabación" className="j-icon-button" onClick={()=>{cancelWake();cancelled.current=true;if(recorder.current?.state==='recording')recorder.current.stop();setListening(false);}}><XIcon/></button><button className="j-round" aria-label="Terminar de hablar" onClick={wakeCapture?finishWake:record}><StopIcon weight="fill"/></button></div>:<form className="j-composer" onSubmit={e=>{e.preventDefault();void send(text);}}><input aria-label="Mensaje para TINO" value={text} onChange={e=>setText(e.target.value)} placeholder="Escribile a TINO…" maxLength={1000}/><button type="button" className="j-round j-pale" aria-label="Hablar con TINO" disabled={typing} onClick={record}><MicrophoneIcon/></button><button className="j-round" aria-label="Enviar mensaje" disabled={!text.trim()||typing}><PaperPlaneTiltIcon weight="fill"/></button></form>}</div></main>;
}

function JuryOnboarding({role,onBack,onComplete}:{role:Role;onBack:()=>void;onComplete:(organization:string)=>void}) {
  const [step,setStep]=useState(0); const [values,setValues]=useState<Record<string,string>>({});const [busy,setBusy]=useState(false);const [error,setError]=useState('');
  const institution=[{key:'organization',title:'¿Cómo se llama tu institución?',options:[]},{key:'size',title:'¿Cuántas personas forman parte?',options:['1','2–10','11–100','+100']},{key:'type',title:'¿Qué tipo de institución es?',options:['Educativa','Gubernamental','Empresa','Centro comunitario','ONG','Otra']},{key:'objective',title:'¿Qué están buscando con TINO?',options:['Entender dónde está la brecha tecnológica','Analizar comportamientos de uso y métricas','Implementar TINO en nuestra institución','Diseñar programas de inclusión digital']},{key:'contact',title:'Por último, ¿con quién hablamos?',options:[]}];
  const personal=[{key:'name',title:role==='family'?'¿Cómo te llamás?':'Empecemos por tu nombre.',options:[]},{key:'purpose',title:role==='family'?'¿Cómo querés acompañar?':'¿Para qué usarías TINO?',options:role==='family'?['Acompañar el aprendizaje','Recibir pedidos de ayuda','Revisar señales de seguridad','Combinar progreso, ayuda y seguridad']:['Comunicarme con otras personas','Resolver trámites y servicios','Entender lo que aparece en pantalla','Aprender funciones del teléfono']},{key:'interaction',title:'¿Cómo preferís recibir ayuda?',options:['Por voz','Por chat','Con indicaciones visuales','Combinando voz y chat']},{key:'contact',title:'Por último, tus datos de contacto.',options:[]}];
  const steps=role==='institution'?institution:personal;const current=steps[step];
  async function next(e:FormEvent) {e.preventDefault();if(step<steps.length-1){setStep(step+1);return;}setBusy(true);try{await juryApi('profile',{role,...values});onComplete(values.organization||'');}catch(e){setError(e instanceof Error?e.message:'No pude guardar el perfil.');}finally{setBusy(false);}}
  const field=(key:string,label:string,type='text')=><label className="j-field">{label}<input type={type} required value={values[key]||''} onChange={e=>setValues(v=>({...v,[key]:e.target.value}))}/></label>;
  return <main className="j-onboarding"><button className="j-back" onClick={()=>step?setStep(step-1):onBack()}><ArrowLeftIcon/>Volver</button><div className="j-progress">{steps.map((_,i)=><i key={i} className={i<=step?'done':''}/>)}</div><span className="j-space">{names[role]} · {step+1} de {steps.length}</span><form onSubmit={next}><h1>{current.title}</h1>{current.options.length?<div className="j-options">{current.options.map((v,i)=><button type="button" key={v} className={values[current.key]===v?'selected':''} onClick={()=>setValues(old=>({...old,[current.key]:v}))}><span>{String.fromCharCode(65+i)}</span>{v}</button>)}</div>:current.key==='contact'?<div className="j-contact-grid">{field('name','Nombre y apellido')}{field('email',role==='institution'?'Correo institucional':'Correo electrónico','email')}{field('phone','Teléfono','tel')}{field('locality','Localidad')}</div>:field(current.key,role==='institution'?'Nombre de la institución':'Nombre y apellido')}{error&&<p role="alert" className="j-error">{error}</p>}<button className="j-button" disabled={busy||(current.options.length>0&&!values[current.key])}>{busy?'Guardando…':step===steps.length-1?'Finalizar':'Continuar'}<ArrowRightIcon/></button></form></main>;
}

function JuryLogin({onDone,onBack}:{onDone:(r:Role)=>void;onBack:()=>void}) {
  const [email,setEmail]=useState('');const [code,setCode]=useState('');const [challenge,setChallenge]=useState('');const [busy,setBusy]=useState(false);const [error,setError]=useState('');
  async function submit(e:FormEvent){e.preventDefault();setBusy(true);setError('');try{const path=challenge?'verify':'request';const response=await fetch(`/api/auth/code/${path}`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(challenge?{challengeId:challenge,code}:{email,role:'user',purpose:'login'})});const data=await response.json();if(!response.ok)throw new Error(data.error?.message||'No pudimos completar el acceso.');if(challenge){sessionStorage.setItem('tino.account',data.sessionToken);onDone(data.role);}else setChallenge(data.challengeId);}catch(e){setError(e instanceof Error?e.message:'No pudimos conectar.');}finally{setBusy(false);}}
  return <main className="j-onboarding"><button className="j-back" onClick={onBack}><ArrowLeftIcon/>Volver</button><form onSubmit={submit}><h1>Qué bueno verte de nuevo.</h1><label className="j-field">{challenge?'Código de acceso':'Correo electrónico'}<input required type={challenge?'text':'email'} value={challenge?code:email} onChange={e=>challenge?setCode(e.target.value):setEmail(e.target.value)}/></label>{error&&<p className="j-error" role="alert">{error}</p>}<button className="j-button" disabled={busy}>{busy?'Conectando…':challenge?'Ingresar':'Continuar'}<ArrowRightIcon/></button></form></main>;
}
