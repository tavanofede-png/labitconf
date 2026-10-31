import { useEffect, useReducer, useRef, useState } from 'react';
import { ArrowCounterClockwiseIcon, ArrowLeftIcon, ArrowRightIcon, BatteryHighIcon, CameraIcon, CaretLeftIcon, CaretRightIcon, CellSignalHighIcon, CheckIcon, DeviceMobileIcon, DeviceTabletIcon, DotsThreeVerticalIcon, EnvelopeSimpleIcon, HouseIcon, MapPinIcon, MicrophoneIcon, PaperclipIcon, PaperPlaneTiltIcon, PlusIcon, SpeakerHighIcon, SpeakerSlashIcon, SquareIcon, UsersThreeIcon, WhatsappLogoIcon, WifiHighIcon, XIcon } from '@phosphor-icons/react';
import { contacts, createInitialState, demoLocation, DEMO_PASSWORD, getDemoEmail, getGuidance, hasSharedLocation, reducer, tasks } from './model';
import type { DemoState, DemoTask } from './model';
import './demo.css';

type Props = { view?: 'phone' | 'web' };
type BrowserRecognition = {
  lang: string; interimResults: boolean; continuous: boolean;
  onresult: ((event: { results: { 0: { 0: { transcript: string } } } }) => void) | null;
  onerror: (() => void) | null; onend: (() => void) | null;
  start: () => void; stop: () => void;
};
type SpeechWindow = Window & { SpeechRecognition?: new () => BrowserRecognition; webkitSpeechRecognition?: new () => BrowserRecognition };

function GoogleWord() { return <div className="d-google-word" aria-label="Google"><span>G</span><span>o</span><span>o</span><span>g</span><span>l</span><span>e</span></div>; }
function DemoMap() { return <div className="d-demo-map" role="img" aria-label="Mapa ilustrativo de una ubicación de ejemplo; no usa tu ubicación"><span className="d-map-dot"><MapPinIcon weight="fill" size={23} /></span><span className="d-map-label">{demoLocation.label} · ubicación de ejemplo</span></div>; }
const avatarTone = (id: string) => id === 'lucia' ? 'peach' : id === 'pedro' ? 'blue' : id === 'martin' ? 'lilac' : '';

type GmailTextField = Exclude<keyof DemoState['gmail'], 'acceptedTerms'>;
function GmailField({ id, field, label, gmail, targetId, onChange, type = 'text', placeholder, readOnly = false }: {
  id: string; field: GmailTextField; label: string; gmail: DemoState['gmail']; targetId: string;
  onChange: (field: GmailTextField, value: string) => void; type?: string; placeholder?: string; readOnly?: boolean;
}) {
  return <div><label className="d-field-label" htmlFor={id}>{label}</label><input id={id} data-demo-control={id} className={`d-input${targetId === id ? ' d-target' : ''}`} type={type} inputMode={type === 'number' ? 'numeric' : 'text'} autoComplete="off" spellCheck={false} value={gmail[field]} placeholder={placeholder} readOnly={readOnly} maxLength={field === 'username' ? 30 : field === 'year' ? 4 : 50} onChange={event => onChange(field, event.target.value)} /></div>;
}

export function Demo({ view = 'phone' }: Props) {
  const [state, dispatch] = useReducer(reducer, undefined, createInitialState);
  const [device, setDevice] = useState<'phone' | 'tablet'>('phone');
  const [bubbleOpen, setBubbleOpen] = useState(false);
  const [question, setQuestion] = useState('');
  const [reply, setReply] = useState('');
  const [voiceEnabled, setVoiceEnabled] = useState(false);
  const [voiceNotice, setVoiceNotice] = useState('');
  const [listening, setListening] = useState(false);
  const recognition = useRef<BrowserRecognition | null>(null);
  const dictationGeneration = useRef(0);
  const appBody = useRef<HTMLDivElement>(null);
  const guidance = getGuidance(state);
  const activeContact = contacts.find(contact => contact.id === state.chatId);
  const waScreen = state.screen.startsWith('wa-');
  const gmailScreen = state.screen.startsWith('gmail-');
  const locationInChat = hasSharedLocation(state);
  const completed = state.screen === 'gmail-done' || (state.screen === 'wa-chat' && (locationInChat || (state.task === 'group' && state.groupCreated && state.chatId === 'group')));
  const silentReply = useRef(guidance.instruction); silentReply.current = guidance.instruction;

  useEffect(() => {
    appBody.current?.scrollTo?.({ top: 0 });
    setReply('');
  }, [state.screen]);

  useEffect(() => {
    if (!voiceEnabled || !('speechSynthesis' in window)) return;
    window.speechSynthesis.cancel();
    let disposed = false;
    const utterance = new SpeechSynthesisUtterance(guidance.instruction);
    utterance.lang = 'es-AR'; utterance.rate = 0.88;
    utterance.onerror = event => {
      if (!disposed && !['canceled', 'interrupted'].includes(event.error)) setVoiceNotice('La lectura por voz no está disponible. Podés seguir el paso escrito.');
    };
    window.speechSynthesis.speak(utterance);
    return () => { disposed = true; window.speechSynthesis.cancel(); };
  }, [guidance.instruction, voiceEnabled]);

  useEffect(() => () => { ++dictationGeneration.current; recognition.current?.stop(); window.speechSynthesis?.cancel(); }, []);

  const target = (id: string, base = '') => `${base}${guidance.targetId === id && (!completed || id === 'demo-home') ? ' d-target' : ''}`;
  function stopDictation() { ++dictationGeneration.current; recognition.current?.stop(); recognition.current = null; setListening(false); }
  function startTask(task: DemoTask) {
    stopDictation();
    dispatch({ type: 'START_TASK', task }); setQuestion(''); setReply('');
  }
  function reset() {
    stopDictation(); window.speechSynthesis?.cancel();
    dispatch({ type: 'RESET' }); setQuestion(''); setReply(''); setBubbleOpen(false); setVoiceNotice('');
  }
  function ask(text: string) {
    if (!text.trim()) return;
    setReply(`Guía de práctica: ${silentReply.current}`);
    setQuestion('');
  }
  function listen() {
    if (listening) { stopDictation(); setVoiceNotice('Dictado detenido. Podés revisar la consulta escrita.'); return; }
    const browser = window as SpeechWindow;
    const Recognition = browser.SpeechRecognition ?? browser.webkitSpeechRecognition;
    if (!Recognition) { setVoiceNotice('Tu navegador no ofrece dictado. Escribí tu consulta o seguí el paso señalado.'); setBubbleOpen(true); return; }
    stopDictation();
    const instance = new Recognition(); recognition.current = instance;
    const generation = ++dictationGeneration.current;
    const current = () => generation === dictationGeneration.current && recognition.current === instance;
    instance.lang = 'es-AR'; instance.interimResults = false; instance.continuous = false;
    instance.onresult = event => { if (current()) { setQuestion(event.results[0][0].transcript.slice(0, 300)); setBubbleOpen(true); setListening(false); } };
    instance.onerror = () => { if (current()) { setListening(false); setVoiceNotice('No pude usar el micrófono. Podés escribir tu consulta.'); } };
    instance.onend = () => { if (current()) setListening(false); };
    try { instance.start(); setListening(true); setVoiceNotice('El dictado de tu navegador está activo. Tocá el micrófono para detenerlo.'); }
    catch { setListening(false); setVoiceNotice('No pude iniciar el dictado. La guía escrita sigue disponible.'); }
  }
  function toggleVoice() {
    if (!('speechSynthesis' in window)) { setVoiceNotice('La lectura por voz no está disponible en este navegador.'); return; }
    setVoiceEnabled(value => !value); setVoiceNotice('');
  }
  function appBack() { dispatch({ type: 'BACK' }); }
  function openApp(app: 'whatsapp' | 'gmail') { dispatch({ type: 'OPEN_APP', app }); }
  function GmailError() { return state.error ? <p role="alert" className="d-form-error">{state.error}</p> : null; }
  const fieldProps = { gmail: state.gmail, targetId: guidance.targetId, onChange: (field: GmailTextField, value: string) => dispatch({ type: 'SET_GMAIL_FIELD', field, value }) };
  function GmailActions({ final = false }: { final?: boolean }) {
    return <div className="d-google-actions"><button className="d-google-back" onClick={appBack}><ArrowLeftIcon size={14} /> Atrás</button><button id="gmail-next" data-demo-control="gmail-next" className={target('gmail-next', 'd-primary-action google')} onClick={() => dispatch({ type: 'NEXT' })}>{final ? 'Crear cuenta de ejemplo' : 'Siguiente'}<ArrowRightIcon size={16} /></button></div>;
  }

  const launcher = <div className="d-app-launcher">
    <div><div className="d-clock">10:08</div><p className="d-launcher-date">Sábado, 31 de octubre</p><div className="d-launcher-widget"><strong>Un paso a la vez.</strong>Elegí una aplicación y practicá con TINO.</div></div>
    <div><div className="d-launcher-apps"><button id="app-whatsapp" aria-label="Abrir WhatsApp de práctica" data-demo-control="app-whatsapp" className={target('app-whatsapp')} onClick={() => openApp('whatsapp')}><span className="d-app-icon wa"><WhatsappLogoIcon size={36} weight="fill" /></span>WhatsApp</button><button id="app-gmail" aria-label="Abrir Gmail de práctica" data-demo-control="app-gmail" className={target('app-gmail')} onClick={() => openApp('gmail')}><span className="d-app-icon gmail"><EnvelopeSimpleIcon size={32} weight="duotone" /></span>Gmail</button><button aria-label="Abrir ayuda de TINO" onClick={() => setBubbleOpen(true)}><span className="d-app-icon nieto">N</span>TINO</button></div><div className="d-launcher-search"><span className="d-google-letter">G</span><span>Tu espacio para practicar</span><MicrophoneIcon size={17} /></div></div>
  </div>;

  let content;
  if (state.screen === 'launcher') content = launcher;
  else if (waScreen) {
    const contactsScreen = state.screen === 'wa-contacts';
    const namingScreen = state.screen === 'wa-group-name';
    const locationScreen = state.screen === 'wa-location';
    const chatScreen = ['wa-chat', 'wa-attachments'].includes(state.screen);
    const chatIsGroup = state.groupCreated && state.chatId === 'group';
    const chatName = chatIsGroup ? state.groupName : activeContact?.name ?? 'Lucía';
    content = <>
      <header className="d-app-header">
        {state.screen !== 'wa-chats' && state.screen !== 'wa-menu' && <button aria-label="Volver en WhatsApp" className="d-icon-button" onClick={appBack}><ArrowLeftIcon size={21} /></button>}
        {chatScreen && <span className="d-avatar">{chatIsGroup ? <UsersThreeIcon size={23} /> : activeContact?.initials ?? 'LU'}</span>}
        <div className="d-header-text"><h2>{contactsScreen ? 'Nuevo grupo' : namingScreen ? 'Nuevo grupo' : locationScreen ? 'Enviar ubicación' : chatScreen ? chatName : 'WhatsApp'}</h2>{contactsScreen ? <p>{state.selectedContacts.length} participantes seleccionados</p> : chatScreen ? <p>{chatIsGroup ? `${state.selectedContacts.length + 1} participantes` : 'Contacto de ejemplo'}</p> : namingScreen ? <p>Elegí un nombre para el grupo</p> : null}</div>
        {!chatScreen && !contactsScreen && !namingScreen && !locationScreen && <button id="wa-menu" data-demo-control="wa-menu" className={target('wa-menu', 'd-icon-button')} aria-label="Más opciones de WhatsApp" onClick={() => dispatch({ type: 'OPEN_MENU' })}><DotsThreeVerticalIcon size={24} weight="bold" /></button>}
      </header>
      {(state.screen === 'wa-chats' || state.screen === 'wa-menu') && <><div className="d-app-tabs"><span className="is-active">CHATS</span><span>NOVEDADES</span><span>LLAMADAS</span></div><div className="d-app-body" ref={appBody}><p className="d-app-intro">Tus conversaciones de práctica</p>{state.groupCreated && <button className="d-chat-list-item" onClick={() => dispatch({ type: 'OPEN_CHAT', contactId: 'group' })}><span className="d-avatar"><UsersThreeIcon size={22} /></span><span className="d-chat-list-copy"><strong>{state.groupName}</strong><span>Creaste este grupo</span></span><CheckIcon size={16} /></button>}{contacts.map(contact => <button id={`chat-${contact.id}`} data-demo-control={`chat-${contact.id}`} key={contact.id} className={target(`chat-${contact.id}`, 'd-chat-list-item')} onClick={() => dispatch({ type: 'OPEN_CHAT', contactId: contact.id })}><span className={`d-avatar ${avatarTone(contact.id)}`}>{contact.initials}</span><span className="d-chat-list-copy"><strong>{contact.name}</strong><span>{contact.preview}</span></span><small className="d-chat-time">{contact.time}</small></button>)}</div>{state.screen === 'wa-menu' && <><button className="d-menu-close" aria-label="Cerrar menú de WhatsApp" onClick={appBack} /><div className="d-menu"><button id="new-group" data-demo-control="new-group" className={target('new-group')} onClick={() => dispatch({ type: 'NEW_GROUP' })}><UsersThreeIcon size={20} />Nuevo grupo</button><button onClick={appBack}><ArrowLeftIcon size={18} />Volver a los chats</button></div></>}</>}
      {contactsScreen && <><div className="d-selected-people">{state.selectedContacts.length ? contacts.filter(contact => state.selectedContacts.includes(contact.id)).map(contact => <div className="d-selected-person" key={contact.id}><span className={`d-avatar ${avatarTone(contact.id)}`}>{contact.initials}</span>{contact.name.split(' ')[0]}</div>) : <span className="d-empty-selected">Elegí a las personas que vas a sumar.</span>}</div><div className="d-app-body" ref={appBody}>{contacts.map(contact => <button id={`contact-${contact.id}`} data-demo-control={`contact-${contact.id}`} className={target(`contact-${contact.id}`, 'd-person-option')} key={contact.id} aria-pressed={state.selectedContacts.includes(contact.id)} onClick={() => dispatch({ type: 'TOGGLE_CONTACT', contactId: contact.id })}><span className={`d-avatar ${avatarTone(contact.id)}`}>{contact.initials}</span><span>{contact.name}<small>Contacto de demostración</small></span><span className="d-person-check"><CheckIcon size={14} weight="bold" /></span></button>)}</div><div className="d-app-bottom">{state.error && <p role="alert" className="d-form-error">{state.error}</p>}<button id="contacts-next" data-demo-control="contacts-next" className={target('contacts-next', 'd-primary-action')} onClick={() => dispatch({ type: 'NEXT' })}>Siguiente<ArrowRightIcon size={18} /></button></div></>}
      {namingScreen && <><div className="d-app-body d-group-form" ref={appBody}><div className="d-group-photo"><CameraIcon size={31} /></div><label className="d-field-label" htmlFor="group-name">Nombre del grupo</label><input id="group-name" data-demo-control="group-name" className={target('group-name', 'd-input')} placeholder="Por ejemplo: Familia cerca" autoComplete="off" value={state.groupName} maxLength={60} onChange={event => dispatch({ type: 'SET_GROUP_NAME', value: event.target.value })} /><p className="d-form-note">Un nombre que tus contactos reconozcan.</p><p className="d-form-note">Participantes: {contacts.filter(contact => state.selectedContacts.includes(contact.id)).map(contact => contact.name).join(', ')}</p>{state.error && <p role="alert" className="d-form-error">{state.error}</p>}</div><div className="d-app-bottom"><button id="create-group" data-demo-control="create-group" className={target('create-group', 'd-primary-action')} onClick={() => dispatch({ type: 'CREATE_GROUP' })}><CheckIcon size={18} />Crear grupo</button></div></>}
      {chatScreen && <><div className="d-chat-thread" ref={appBody}><span className="d-chat-date">HOY · DEMOSTRACIÓN</span><p className="d-chat-security">Esta conversación usa contactos de ejemplo. Nada se envía a WhatsApp.</p>{chatIsGroup ? <><p className="d-system-message">Creaste el grupo «{state.groupName}»</p><p className="d-system-message">Agregaste a {contacts.filter(contact => state.selectedContacts.includes(contact.id)).map(contact => contact.name).join(' y ')}.</p></> : <div className="d-message">Hola, cuando puedas mandame dónde nos encontramos.<small>10:06</small></div>}{locationInChat && <div className="d-message sent"><div className="d-location-preview"><DemoMap /><strong>Ubicación de ejemplo</strong><span>{demoLocation.label}, {demoLocation.address}</span></div><small>10:08 <CheckIcon size={12} /></small></div>}</div>{completed && <div className="d-sent-banner"><CheckIcon size={15} />{locationInChat ? 'Ubicación de ejemplo compartida' : 'Grupo de práctica creado'}</div>}<div className="d-chat-composer"><div className="d-composer-field"><span>Mensaje de práctica</span><button id="attach" data-demo-control="attach" aria-label="Adjuntar en el chat" className={target('attach', 'd-icon-button')} onClick={() => dispatch({ type: 'OPEN_ATTACHMENTS' })}><PaperclipIcon size={24} /></button></div><button className="d-send-circle" aria-label="Abrir la ayuda de TINO" onClick={() => setBubbleOpen(true)}><MicrophoneIcon size={20} /></button></div>{state.screen === 'wa-attachments' && <div className="d-attachment-sheet"><div className="d-sheet-title">Compartir en el chat<button className="d-icon-button" aria-label="Cerrar adjuntos" onClick={appBack}><XIcon size={16} /></button></div><div className="d-attachment-grid"><button disabled><span className="d-attachment-symbol purple"><EnvelopeSimpleIcon size={23} /></span>Documento</button><button disabled><span className="d-attachment-symbol peach"><CameraIcon size={23} /></span>Cámara</button><button disabled><span className="d-attachment-symbol blue"><SquareIcon size={22} /></span>Galería</button><button id="location-option" data-demo-control="location-option" className={target('location-option')} onClick={() => dispatch({ type: 'OPEN_LOCATION' })}><span className="d-attachment-symbol"><MapPinIcon size={25} weight="fill" /></span>Ubicación</button><button disabled><span className="d-attachment-symbol peach"><MicrophoneIcon size={23} /></span>Audio</button><button disabled><span className="d-attachment-symbol blue"><UsersThreeIcon size={24} /></span>Contacto</button></div></div>}</>}
      {locationScreen && <><div className="d-app-body" ref={appBody}><DemoMap /><div className="d-location-option"><span><MapPinIcon size={22} weight="fill" /></span><span><strong>{demoLocation.label}</strong><small>Ubicación ficticia para este recorrido</small></span></div><p className="d-location-caption">Vas a compartir este punto de ejemplo con {chatName}. La demo no solicita GPS ni conoce tu ubicación real.</p><p className="d-location-review">Revisá el destinatario y el lugar. Si están bien, tocá el botón para compartir.</p></div><div className="d-app-bottom"><button id="share-location" data-demo-control="share-location" className={target('share-location', 'd-primary-action')} onClick={() => dispatch({ type: 'SHARE_LOCATION' })}><PaperPlaneTiltIcon size={18} weight="fill" />Compartir ubicación de ejemplo</button></div></>}
    </>;
  } else if (gmailScreen) {
    const accountAddress = getDemoEmail(state) || 'tu.nombre@gmail.example';
    content = <><div className="d-google-header"><GoogleWord /></div><div className="d-google-body" ref={appBody}>
      {state.screen === 'gmail-name' && <><h2>Creá una cuenta<br />de ejemplo</h2><p className="d-google-subtitle">Para practicar Gmail. Usá un nombre ficticio.</p><GmailField {...fieldProps} id="gmail-first-name" field="firstName" label="Nombre" placeholder="María" /><GmailField {...fieldProps} id="gmail-last-name" field="lastName" label="Apellido" placeholder="López" /><button className="d-muted-action" onClick={() => { dispatch({ type: 'SET_GMAIL_FIELD', field: 'firstName', value: 'María' }); dispatch({ type: 'SET_GMAIL_FIELD', field: 'lastName', value: 'López' }); }}>Usar nombre de ejemplo</button><GmailError /></>}
      {state.screen === 'gmail-birth' && <><h2>Información básica</h2><p className="d-google-subtitle">En este ejercicio, elegí una fecha de ejemplo.</p><GmailField {...fieldProps} id="gmail-birth-day" field="day" label="Día" type="number" placeholder="12" /><label className="d-field-label" htmlFor="gmail-birth-month">Mes</label><select id="gmail-birth-month" data-demo-control="gmail-birth-month" className={target('gmail-birth-month', 'd-select')} value={state.gmail.month} onChange={event => dispatch({ type: 'SET_GMAIL_FIELD', field: 'month', value: event.target.value })}><option value="">Elegir mes</option>{['Enero', 'Febrero', 'Marzo', 'Abril', 'Mayo', 'Junio', 'Julio', 'Agosto', 'Septiembre', 'Octubre', 'Noviembre', 'Diciembre'].map((month, index) => <option key={month} value={index + 1}>{month}</option>)}</select><GmailField {...fieldProps} id="gmail-birth-year" field="year" label="Año" type="number" placeholder="1964" /><button className="d-muted-action" onClick={() => { dispatch({ type: 'SET_GMAIL_FIELD', field: 'day', value: '12' }); dispatch({ type: 'SET_GMAIL_FIELD', field: 'month', value: '5' }); dispatch({ type: 'SET_GMAIL_FIELD', field: 'year', value: '1964' }); }}>Usar fecha de ejemplo</button><GmailError /></>}
      {state.screen === 'gmail-username' && <><h2>Elegí tu dirección</h2><p className="d-google-subtitle">Un nombre corto para tu correo de práctica.</p><GmailField {...fieldProps} id="gmail-username" field="username" label="Nombre de usuario" placeholder="maria.lopez64" /><p className="d-form-note">Tu dirección de ejemplo termina en <strong>@gmail.example</strong></p><button className="d-muted-action" onClick={() => dispatch({ type: 'SET_GMAIL_FIELD', field: 'username', value: 'maria.lopez64' })}>Usar dirección de ejemplo</button><GmailError /></>}
      {state.screen === 'gmail-password' && <><h2>Una contraseña<br />para practicar</h2><p className="d-google-subtitle">No escribas una contraseña personal. Usaremos una clave de prueba.</p><div className="d-password-demo"><span>CLAVE DISPONIBLE SOLO PARA ESTA DEMO</span><code>{DEMO_PASSWORD}</code></div><button id="gmail-password" data-demo-control="gmail-password" className={target('gmail-password', 'd-primary-action d-password-action')}  onClick={() => { dispatch({ type: 'SET_GMAIL_FIELD', field: 'password', value: DEMO_PASSWORD }); dispatch({ type: 'SET_GMAIL_FIELD', field: 'confirmPassword', value: DEMO_PASSWORD }); }}>{state.gmail.password ? <CheckIcon size={17} /> : <PlusIcon size={17} />}{state.gmail.password ? 'Clave de prueba colocada' : 'Usar clave de prueba'}</button><GmailField {...fieldProps} id="gmail-password-preview" field="password" label="Contraseña de demostración" type="password" readOnly /><GmailField {...fieldProps} id="gmail-confirm-password" field="confirmPassword" label="Confirmación" type="password" readOnly /><GmailError /></>}
      {state.screen === 'gmail-terms' && <><h2>Revisá tu cuenta</h2><p className="d-google-subtitle">Falta un último paso antes de terminar.</p><div className="d-google-review"><strong>{state.gmail.firstName} {state.gmail.lastName}</strong><span className="d-account-address">{accountAddress}</span></div><div className="d-google-terms"><h3>En esta demostración</h3><ul><li>La cuenta existe solo dentro de este recorrido.</li><li>El formulario no se envía a Google ni crea una cuenta real.</li><li>Al reiniciar se borra el ejercicio.</li></ul></div><label id="gmail-accept" data-demo-control="gmail-accept" className={target('gmail-accept', 'd-terms-check')}><input type="checkbox" checked={state.gmail.acceptedTerms} onChange={event => dispatch({ type: 'SET_GMAIL_FIELD', field: 'acceptedTerms', value: event.target.checked })} /><span>Entiendo y quiero crear esta cuenta de ejemplo.</span></label><GmailError /></>}
      {state.screen === 'gmail-done' && <><div className="d-google-mark"><CheckIcon size={36} /></div><h2>Tu cuenta de ejemplo<br />está lista</h2><p className="d-google-subtitle">Completaste el recorrido de creación.</p><div className="d-google-review"><strong>{state.gmail.firstName} {state.gmail.lastName}</strong><span className="d-account-address">{accountAddress}</span></div><p className="d-tick-line"><CheckIcon size={17} /><span>Practicaste cada paso. No se creó una cuenta real en Google.</span></p><div className="d-inline-submit"><button className="d-primary-action google" onClick={() => dispatch({ type: 'HOME' })}>Volver al inicio<HouseIcon size={17} /></button></div></>}
    </div>{state.screen !== 'gmail-done' && <GmailActions final={state.screen === 'gmail-terms'} />}</>;
  }

  const helper = bubbleOpen && <aside className="d-bubble-panel" aria-label="Ayuda de TINO"><div className="d-bubble-heading"><strong>TINO está acá</strong><button className="d-icon-button" aria-label="Cerrar ayuda" onClick={() => setBubbleOpen(false)}><XIcon size={17} /></button></div><p>{guidance.instruction}</p><label htmlFor="demo-question">Pedí el paso actual de la práctica</label><form onSubmit={event => { event.preventDefault(); ask(question); }}><input id="demo-question" value={question} onChange={event => setQuestion(event.target.value.slice(0, 300))} placeholder="Repetí el paso actual" maxLength={300} autoComplete="off" /><button aria-label="Consultar el paso de práctica" disabled={!question.trim()}><ArrowRightIcon size={18} /></button></form>{reply && <p className="d-bubble-answer" role="status">{reply}</p>}<button className="d-quiet-button" onClick={listen}><MicrophoneIcon size={15} />{listening ? 'Detener dictado' : 'Dictar una consulta'}</button></aside>;
  const app = <div className="d-phone-app">{content}{helper}</div>;
  const bubble = <button className="d-bubble-toggle" aria-label={bubbleOpen ? 'Cerrar burbuja de TINO' : 'Abrir burbuja de TINO'} aria-expanded={bubbleOpen} onClick={() => setBubbleOpen(value => !value)}>N</button>;

  return <div className={`demo-site ${view === 'web' ? 'is-web-view' : ''}`}>
    <a className="d-skip-link" href="#demo-content" onClick={event => { event.preventDefault(); document.getElementById('demo-content')?.focus(); }}>Ir a la demostración</a>
    <header className="d-site-header"><a className="d-brand" href="#emulador" aria-label="TINO, inicio de la demostración"><span className="d-brand-symbol">N</span>tino.<small>LABITCONF 2026</small></a><nav className="d-view-tabs" aria-label="Vista de la demostración"><a href="#emulador" aria-current={view === 'phone' ? 'page' : undefined}><DeviceMobileIcon size={17} />Emulador</a><a href="#demo" aria-current={view === 'web' ? 'page' : undefined}><SquareIcon size={16} />Vista web</a></nav></header>
    <main className="d-page" id="demo-content" tabIndex={-1}><header className="d-page-heading"><div><div className="d-kicker">Probalo, a tu ritmo</div><h1>{view === 'phone' ? 'Tu primer paso. Con una mano cerca.' : 'La misma ayuda, en una vista más amplia.'}</h1><p className="d-heading-copy">{view === 'phone' ? 'Tocá el celular, elegí una tarea y dejá que TINO te acompañe.' : 'Practicá cada recorrido desde el navegador, sin el marco del teléfono.'}</p></div><span className="d-demo-tag">Demostración · datos de ejemplo</span></header>
    <div className="d-layout">
      {view === 'phone' ? <section className="d-stage" aria-label="Emulador web de Android"><div className="d-stage-toolbar"><div className="d-device-switch"><button aria-pressed={device === 'phone'} onClick={() => setDevice('phone')}><DeviceMobileIcon size={16} />Teléfono</button><button aria-pressed={device === 'tablet'} onClick={() => setDevice('tablet')}><DeviceTabletIcon size={17} />Tablet</button></div><button className="d-quiet-button" onClick={() => setBubbleOpen(true)}>Abrir TINO<ArrowRightIcon size={14} /></button></div><div className={`d-phone ${device === 'tablet' ? 'is-tablet' : ''}`}><div className="d-display"><div className="d-status-bar"><span>10:08</span><span className="d-status-icons"><WifiHighIcon size={12} weight="bold" /><CellSignalHighIcon size={11} weight="fill" /><BatteryHighIcon size={15} weight="fill" /></span></div>{app}<nav className="d-android-nav" aria-label="Navegación Android de práctica"><button aria-label="Atrás en el celular" onClick={appBack}><CaretLeftIcon size={21} /></button><button id="demo-home" data-demo-control="demo-home" className={target('demo-home')} aria-label="Inicio Android" onClick={() => dispatch({ type: 'HOME' })}><HouseIcon size={18} /></button><button aria-label="Mostrar aplicaciones de práctica" onClick={() => dispatch({ type: 'HOME' })}><SquareIcon size={15} /></button></nav></div>{bubble}</div><p className="d-stage-caption"><DeviceMobileIcon size={13} />Android simulado · podés tocar cada control</p></section> : <section className="d-stage d-normal-stage" aria-label="Aplicaciones de práctica en vista web"><div className="d-normal-topbar"><div><SquareIcon size={16} /><strong>Tu espacio de práctica</strong></div><button id="demo-home" data-demo-control="demo-home" className={target('demo-home', 'd-quiet-button')} onClick={() => dispatch({ type: 'HOME' })}><HouseIcon size={16} />Inicio</button></div><div className="d-normal-app">{app}<div className="d-normal-helpbar"><span>Una mano cerca, cuando la necesites.</span>{bubble}</div></div></section>}
      <aside className="d-guide" aria-label="Guía de práctica de TINO"><div className="d-guide-brand"><span className="d-guide-mark">N</span>Una mano cerca.</div><p className="d-guide-label">Elegí qué querés practicar</p><h2>Vos hacés cada toque.<br />Vamos paso a paso.</h2><p className="d-guide-lead">Estos recorridos son para probar la experiencia. Podés volver atrás y empezar de nuevo.</p><div className="d-task-picker">{tasks.map(task => <button key={task.id} className="d-task-option" aria-pressed={state.task === task.id} onClick={() => startTask(task.id)}><span className="d-task-icon">{task.id === 'group' ? <UsersThreeIcon size={21} /> : task.id === 'location' ? <MapPinIcon size={21} /> : <EnvelopeSimpleIcon size={21} />}</span><span><strong>{task.title}</strong><small>{task.id === 'group' ? 'En WhatsApp' : task.id === 'location' ? 'En una conversación' : 'Una cuenta de práctica'}</small></span><CaretRightIcon size={15} /></button>)}</div><div className="d-current-guide" aria-live="polite" aria-atomic="true"><small>GUÍA DE PRÁCTICA · {guidance.title}</small><p>{guidance.instruction}</p></div><div className="d-guide-progress" aria-hidden="true">{Array.from({ length: guidance.total }, (_, index) => <span key={index} className={index < guidance.step ? 'is-done' : ''} />)}</div><div className="d-progress-label"><span>{completed ? 'Recorrido terminado' : `Paso ${Math.min(guidance.step, guidance.total)} de ${guidance.total}`}</span><span>Sin apuro.</span></div><p className="d-guide-hint">El borde verde muestra el control de este paso. Lo tocás vos.</p><div className="d-guide-actions"><button className="d-assist-button" aria-pressed={voiceEnabled} onClick={toggleVoice}>{voiceEnabled ? <SpeakerSlashIcon size={16} /> : <SpeakerHighIcon size={16} />}{voiceEnabled ? 'Silenciar guía' : 'Escuchar el paso'}</button><button className="d-assist-button" onClick={reset}><ArrowCounterClockwiseIcon size={15} />Reiniciar</button></div>{voiceNotice && <p role="status" className="d-guide-hint">{voiceNotice}</p>}{completed && <div className="d-guide-success"><h3>Lo hiciste paso a paso.</h3><p>Completaste una práctica. Elegí otra tarea o reiniciá para volver a intentarlo.</p></div>}<p className="d-guide-disclosure">La guía sigue el recorrido elegido. Las aplicaciones, contactos, mensajes y ubicaciones son simulados. Los formularios se quedan en esta página; no se crean cuentas reales ni se envían mensajes a WhatsApp.</p><p className="d-guide-disclosure"><a className="d-exit-link" href="/portal#instituciones">Conocer el portal de TINO <ArrowRightIcon size={12} /></a></p></aside>
    </div><p className="d-footer-note">Practicá con datos ficticios. La sesión vive en esta página y se borra al recargar. El dictado y la lectura opcionales dependen de los servicios de voz de tu navegador.</p></main>
  </div>;
}
