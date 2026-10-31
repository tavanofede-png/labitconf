export type DemoTask = 'group' | 'location' | 'gmail';
export type DemoScreen = 'launcher' | 'wa-chats' | 'wa-menu' | 'wa-contacts' | 'wa-group-name' | 'wa-chat' | 'wa-attachments' | 'wa-location'
  | 'gmail-name' | 'gmail-birth' | 'gmail-username' | 'gmail-password' | 'gmail-terms' | 'gmail-done';
export type GmailField = 'firstName' | 'lastName' | 'day' | 'month' | 'year' | 'username' | 'password' | 'confirmPassword' | 'acceptedTerms';
export type GmailDraft = Record<Exclude<GmailField, 'acceptedTerms'>, string> & { acceptedTerms: boolean };

export interface DemoState {
  screen: DemoScreen;
  task: DemoTask | null;
  history: DemoScreen[];
  selectedContacts: string[];
  groupName: string;
  groupCreated: boolean;
  chatId: string | null;
  locationShared: boolean;
  sharedLocationChatIds: string[];
  gmail: GmailDraft;
  gmailStage: DemoScreen;
  completedTasks: DemoTask[];
  error: string | null;
}

export type DemoAction =
  | { type: 'START_TASK'; task: DemoTask }
  | { type: 'OPEN_APP'; app: 'whatsapp' | 'gmail' }
  | { type: 'OPEN_MENU' | 'NEW_GROUP' | 'NEXT' | 'CREATE_GROUP' | 'OPEN_ATTACHMENTS' | 'OPEN_LOCATION' | 'SHARE_LOCATION' | 'BACK' | 'HOME' | 'RESET' }
  | { type: 'TOGGLE_CONTACT' | 'OPEN_CHAT'; contactId: string }
  | { type: 'SET_GROUP_NAME'; value: string }
  | { type: 'SET_GMAIL_FIELD'; field: GmailField; value: string | boolean };

export const contacts = [
  { id: 'lucia', name: 'Lucía', initials: 'LU', color: '#ddc7ac', preview: '¡Nos vemos en la plaza!', time: '10:04' },
  { id: 'ana', name: 'Ana', initials: 'AN', color: '#b9d1c1', preview: '¿Armamos el grupo?', time: '09:46' },
  { id: 'pedro', name: 'Pedro', initials: 'PE', color: '#c4cbe0', preview: 'Dale, contá conmigo.', time: '09:32' },
  { id: 'martin', name: 'Martín', initials: 'MA', color: '#e0c3c2', preview: 'Gracias por avisar.', time: 'Ayer' },
] as const;

export const tasks: { id: DemoTask; title: string; description: string }[] = [
  { id: 'group', title: 'Crear un grupo', description: 'Elegí contactos, escribí un nombre y creá un grupo de práctica en WhatsApp.' },
  { id: 'location', title: 'Compartir ubicación', description: 'Compartí una ubicación de ejemplo en un chat, con confirmación antes de enviarla.' },
  { id: 'gmail', title: 'Crear un Gmail', description: 'Recorré el alta de una cuenta de práctica con datos ficticios.' },
];

export const DEMO_PASSWORD = 'DemoSeguro2026!';
export const demoLocation = {
  label: 'Plaza del Congreso', address: 'Ciudad Autónoma de Buenos Aires',
  latitude: -34.6099, longitude: -58.3925, caption: 'Ubicación de prueba · no usa tu GPS',
} as const;

const emptyGmail = (): GmailDraft => ({ firstName: '', lastName: '', day: '', month: '', year: '', username: '', password: '', confirmPassword: '', acceptedTerms: false });

export function createInitialState(): DemoState {
  return { screen: 'launcher', task: null, history: [], selectedContacts: [], groupName: '', groupCreated: false,
    chatId: null, locationShared: false, sharedLocationChatIds: [], gmail: emptyGmail(), gmailStage: 'gmail-name', completedTasks: [], error: null };
}

export const initialState = createInitialState();
const knownContact = (id: string) => contacts.some(contact => contact.id === id);
const complete = (state: DemoState, task: DemoTask): DemoTask[] => state.completedTasks.includes(task) ? state.completedTasks : [...state.completedTasks, task];
const failure = (state: DemoState, error: string): DemoState => ({ ...state, error });
const navigate = (state: DemoState, screen: DemoScreen): DemoState => state.screen === screen ? { ...state, error: null }
  : { ...state, screen, history: [...state.history, state.screen], error: null };
const gmailScreens: DemoScreen[] = ['gmail-name', 'gmail-birth', 'gmail-username', 'gmail-password', 'gmail-terms', 'gmail-done'];

function validBirth(gmail: GmailDraft): boolean {
  if (!/^\d{1,2}$/.test(gmail.day) || !/^\d{1,2}$/.test(gmail.month) || !/^\d{4}$/.test(gmail.year)) return false;
  const day = Number(gmail.day), month = Number(gmail.month), year = Number(gmail.year);
  if (year < 1900 || year > 2026 || month < 1 || month > 12 || day < 1 || day > 31) return false;
  const date = new Date(Date.UTC(year, month - 1, day));
  // Fixed demonstration date, independent of the visitor's timezone or device clock.
  return date.getUTCFullYear() === year && date.getUTCMonth() === month - 1 && date.getUTCDate() === day && date.getTime() <= Date.UTC(2026, 9, 31);
}

function validUsername(username: string): boolean {
  return /^[a-z0-9][a-z0-9.]{4,28}[a-z0-9]$/.test(username) && !username.includes('..');
}

const gmailFieldScreen: Record<GmailField, DemoScreen> = {
  firstName: 'gmail-name', lastName: 'gmail-name', day: 'gmail-birth', month: 'gmail-birth', year: 'gmail-birth',
  username: 'gmail-username', password: 'gmail-password', confirmPassword: 'gmail-password', acceptedTerms: 'gmail-terms',
};

function nextGmail(state: DemoState): DemoState {
  const { gmail } = state;
  if (state.screen === 'gmail-name') {
    if (!gmail.firstName.trim() || !gmail.lastName.trim()) return failure(state, 'Completá nombre y apellido ficticios para continuar.');
    return { ...navigate(state, 'gmail-birth'), gmailStage: 'gmail-birth' };
  }
  if (state.screen === 'gmail-birth') {
    if (!validBirth(gmail)) return failure(state, 'Ingresá una fecha de nacimiento de ejemplo válida.');
    return { ...navigate(state, 'gmail-username'), gmailStage: 'gmail-username' };
  }
  if (state.screen === 'gmail-username') {
    if (!validUsername(gmail.username)) return failure(state, 'Usá entre 6 y 30 letras, números o puntos. No incluyas @ ni puntos seguidos.');
    return { ...navigate(state, 'gmail-password'), gmailStage: 'gmail-password' };
  }
  if (state.screen === 'gmail-password') {
    if (gmail.password !== DEMO_PASSWORD || gmail.confirmPassword !== DEMO_PASSWORD) return failure(state, 'Tocá «Usar clave de prueba». No ingreses una contraseña personal.');
    return { ...navigate(state, 'gmail-terms'), gmailStage: 'gmail-terms' };
  }
  if (state.screen === 'gmail-terms') {
    if (!gmail.acceptedTerms) return failure(state, 'Aceptá las condiciones de esta práctica para crear la cuenta de ejemplo.');
    // Revalidate the whole draft: viewing the final screen alone never creates an account.
    if (!gmail.firstName.trim() || !gmail.lastName.trim() || !validBirth(gmail) || !validUsername(gmail.username)
      || gmail.password !== DEMO_PASSWORD || gmail.confirmPassword !== DEMO_PASSWORD) return failure(state, 'Revisá los datos de la práctica antes de terminar.');
    return { ...state, screen: 'gmail-done', gmailStage: 'gmail-done', history: ['launcher'],
      completedTasks: complete(state, 'gmail'), error: null };
  }
  return state;
}

/** Browser-local practice only. No account, message, GPS request or remote operation is performed. */
export function reducer(state: DemoState, action: DemoAction): DemoState {
  switch (action.type) {
    case 'RESET': return createInitialState();
    case 'HOME': return { ...state, screen: 'launcher', history: [], error: null };
    case 'START_TASK': {
      if (!tasks.some(task => task.id === action.task)) return state;
      const base = { ...state, task: action.task, history: ['launcher'] as DemoScreen[], error: null,
        completedTasks: state.completedTasks.filter(task => task !== action.task) };
      if (action.task === 'group') return { ...base, screen: 'wa-chats', selectedContacts: [], groupName: '', groupCreated: false, chatId: null, locationShared: false,
        sharedLocationChatIds: state.sharedLocationChatIds.filter(id => id !== 'group') };
      if (action.task === 'location') return { ...base, screen: 'wa-chats', chatId: null, locationShared: false, sharedLocationChatIds: [] };
      return { ...base, screen: 'gmail-name', gmailStage: 'gmail-name', gmail: emptyGmail() };
    }
    case 'OPEN_APP':
      if (action.app === 'whatsapp') return navigate({ ...state, task: state.task === 'gmail' ? null : state.task }, 'wa-chats');
      if (action.app === 'gmail') return navigate({ ...state, task: 'gmail' }, state.gmailStage);
      return state;
    case 'OPEN_MENU': return state.screen === 'wa-chats' ? navigate(state, 'wa-menu') : state;
    case 'NEW_GROUP': return ['wa-menu', 'wa-chats'].includes(state.screen)
      ? navigate({ ...state, task: 'group', selectedContacts: [], groupName: '', groupCreated: false, chatId: null, locationShared: false,
        sharedLocationChatIds: state.sharedLocationChatIds.filter(id => id !== 'group'),
        completedTasks: state.completedTasks.filter(task => task !== 'group') }, 'wa-contacts') : state;
    case 'TOGGLE_CONTACT':
      if (state.screen !== 'wa-contacts' || !knownContact(action.contactId)) return state;
      return { ...state, error: null, selectedContacts: state.selectedContacts.includes(action.contactId)
        ? state.selectedContacts.filter(id => id !== action.contactId) : [...state.selectedContacts, action.contactId] };
    case 'NEXT':
      if (state.screen === 'wa-contacts') return state.selectedContacts.length >= 2
        ? navigate(state, 'wa-group-name') : failure(state, 'Elegí al menos dos contactos de prueba para el grupo.');
      return nextGmail(state);
    case 'SET_GROUP_NAME': return state.screen === 'wa-group-name'
      ? { ...state, groupName: action.value.slice(0, 60), error: null } : state;
    case 'CREATE_GROUP':
      if (state.screen !== 'wa-group-name') return state;
      if (state.selectedContacts.length < 2 || !state.selectedContacts.every(knownContact)) return failure(state, 'Elegí al menos dos contactos de prueba.');
      if (!state.groupName.trim()) return failure(state, 'Escribí un nombre para el grupo de práctica.');
      return { ...state, screen: 'wa-chat', history: ['launcher', 'wa-chats'], groupName: state.groupName.trim(),
        groupCreated: true, chatId: 'group', locationShared: false, sharedLocationChatIds: state.sharedLocationChatIds.filter(id => id !== 'group'),
        completedTasks: complete(state, 'group'), error: null };
    case 'OPEN_CHAT':
      if (state.screen !== 'wa-chats' || (!knownContact(action.contactId) && !(action.contactId === 'group' && state.groupCreated))) return state;
      return navigate({ ...state, chatId: action.contactId, locationShared: hasSharedLocation(state, action.contactId) }, 'wa-chat');
    case 'OPEN_ATTACHMENTS': return state.screen === 'wa-chat' && state.chatId ? navigate(state, 'wa-attachments') : state;
    case 'OPEN_LOCATION': return state.screen === 'wa-attachments' && state.chatId ? navigate(state, 'wa-location') : state;
    case 'SHARE_LOCATION':
      if (state.screen !== 'wa-location' || !state.chatId) return state;
      return { ...state, screen: 'wa-chat', history: ['launcher', 'wa-chats'], task: 'location', locationShared: true,
        sharedLocationChatIds: hasSharedLocation(state) ? state.sharedLocationChatIds : [...state.sharedLocationChatIds, state.chatId],
        completedTasks: complete(state, 'location'), error: null };
    case 'SET_GMAIL_FIELD': {
      if (gmailFieldScreen[action.field] !== state.screen) return state;
      if (action.field === 'acceptedTerms') return typeof action.value === 'boolean'
        ? { ...state, gmail: { ...state.gmail, acceptedTerms: action.value }, error: null } : state;
      if (typeof action.value !== 'string') return state;
      if ((action.field === 'password' || action.field === 'confirmPassword') && action.value !== '' && action.value !== DEMO_PASSWORD)
        return failure(state, 'Usá únicamente la clave de prueba. No ingreses una contraseña personal.');
      const max = ['day', 'month'].includes(action.field) ? 2 : action.field === 'year' ? 4 : 60;
      const value = action.field === 'username' ? action.value.trim().toLowerCase().slice(0, 30) : action.value.slice(0, max);
      return { ...state, gmail: { ...state.gmail, [action.field]: value }, error: null };
    }
    case 'BACK': {
      const previous = state.history.at(-1);
      if (!previous) return { ...state, screen: 'launcher', history: [], error: null };
      return { ...state, screen: previous, history: state.history.slice(0, -1), error: null,
        gmailStage: gmailScreens.includes(previous) ? previous : state.gmailStage };
    }
    default: return state;
  }
}

export function getDemoEmail(state: DemoState): string { return state.gmail.username ? `${state.gmail.username}@gmail.example` : ''; }
export function hasSharedLocation(state: DemoState, chatId: string | null = state.chatId): boolean {
  return chatId !== null && state.sharedLocationChatIds.includes(chatId);
}
export function taskStatus(state: DemoState, task: DemoTask): 'complete' | 'active' | 'available' {
  return state.completedTasks.includes(task) ? 'complete' : state.task === task ? 'active' : 'available';
}

export interface DemoGuidance { title: string; instruction: string; targetId: string; step: number; total: number }
export function getGuidance(state: DemoState): DemoGuidance {
  const group = state.task === 'group';
  const title = tasks.find(task => task.id === state.task)?.title ?? 'Guía de práctica';
  const guide = (instruction: string, targetId: string, step: number, total: number): DemoGuidance => ({ title, instruction, targetId, step, total });
  switch (state.screen) {
    case 'launcher': return guide('Elegí WhatsApp o Gmail para comenzar una práctica. Los datos son de ejemplo.', state.task === 'gmail' ? 'app-gmail' : 'app-whatsapp', 1, 1);
    case 'wa-chats': return group ? guide('Tocá los tres puntos de arriba para abrir el menú de WhatsApp.', 'wa-menu', 1, 5)
      : guide('Abrí el chat de Lucía para compartir una ubicación de ejemplo.', 'chat-lucia', 1, 5);
    case 'wa-menu': return guide('Elegí «Nuevo grupo».', 'new-group', 2, 5);
    case 'wa-contacts': {
      if (state.selectedContacts.length < 2) {
        const next = state.selectedContacts.includes('ana') ? 'lucia' : 'ana';
        return guide('Elegí dos contactos de prueba. Tocá su nombre para seleccionarlos.', `contact-${next}`, 3, 5);
      }
      return guide('Ya elegiste los contactos. Tocá la flecha para continuar.', 'contacts-next', 3, 5);
    }
    case 'wa-group-name': return state.groupName.trim() ? guide('Tocá la tilde para crear el grupo de práctica.', 'create-group', 4, 5)
      : guide('Escribí un nombre, por ejemplo «Amigos de la plaza».', 'group-name', 4, 5);
    case 'wa-chat':
      if (hasSharedLocation(state)) return guide('Compartiste la ubicación de ejemplo en este chat. No usamos tu GPS ni enviamos nada a WhatsApp.', 'demo-home', 5, 5);
      if (group && state.groupCreated && state.chatId === 'group') return guide('Creaste el grupo de práctica. Podés volver al inicio y probar otro recorrido.', 'demo-home', 5, 5);
      return guide('Tocá el clip para ver qué podés compartir en el chat.', 'attach', 2, 5);
    case 'wa-attachments': return guide('Elegí «Ubicación» entre las opciones para adjuntar.', 'location-option', 3, 5);
    case 'wa-location': return guide('Revisá la ubicación de ejemplo y confirmá que querés compartirla.', 'share-location', 4, 5);
    case 'gmail-name': return guide('Escribí un nombre y apellido ficticios; después tocá «Siguiente».', !state.gmail.firstName.trim() ? 'gmail-first-name' : !state.gmail.lastName.trim() ? 'gmail-last-name' : 'gmail-next', 1, 6);
    case 'gmail-birth': return guide('Completá una fecha de nacimiento de ejemplo y tocá «Siguiente».', !state.gmail.day ? 'gmail-birth-day' : !state.gmail.month ? 'gmail-birth-month' : !state.gmail.year ? 'gmail-birth-year' : validBirth(state.gmail) ? 'gmail-next' : 'gmail-birth-day', 2, 6);
    case 'gmail-username': return guide('Elegí un nombre de usuario de práctica. El dominio de ejemplo no crea una cuenta de Google.', validUsername(state.gmail.username) ? 'gmail-next' : 'gmail-username', 3, 6);
    case 'gmail-password': return guide('Usá la clave de prueba con el botón. No ingreses una contraseña personal.', state.gmail.password === DEMO_PASSWORD && state.gmail.confirmPassword === DEMO_PASSWORD ? 'gmail-next' : 'gmail-password', 4, 6);
    case 'gmail-terms': return guide('Revisá los datos y aceptá las condiciones de la práctica antes de terminar.', state.gmail.acceptedTerms ? 'gmail-next' : 'gmail-accept', 5, 6);
    case 'gmail-done': return guide('Terminaste el alta de ejemplo. Esta cuenta es una simulación y no recibe correos.', 'demo-home', 6, 6);
  }
}
