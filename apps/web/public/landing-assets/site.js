const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
const menu = $('#menu');
const menuToggle = $('.menu-toggle');
menuToggle.addEventListener('click', () => {
  const open = menuToggle.getAttribute('aria-expanded') !== 'true';
  menuToggle.setAttribute('aria-expanded', String(open));
  menuToggle.setAttribute('aria-label', open ? 'Cerrar navegación' : 'Abrir navegación');
  menu.classList.toggle('open', open);
});
$$('a', menu).forEach(link => link.addEventListener('click', () => {
  menu.classList.remove('open');
  menuToggle.setAttribute('aria-expanded', 'false');
  menuToggle.setAttribute('aria-label', 'Abrir navegación');
}));

// Native dialogs handle Escape, focus trapping and focus restoration.
function openDialog(dialog) { dialog.showModal(); }
$$('[data-close]').forEach(button => button.addEventListener('click', () => button.closest('dialog').close()));
$$('dialog').forEach(dialog => dialog.addEventListener('click', event => {
  if (event.target !== dialog) return;
  const rect = dialog.getBoundingClientRect();
  if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) dialog.close();
}));

const preferences = $('#preferences');
['#access-trigger', '#open-preferences', '#open-access-link'].forEach(selector => $(selector).addEventListener('click', event => {
  event.preventDefault();
  openDialog(preferences);
}));
const preferenceControls = { textSize: $('#text-size'), contrast: $('#high-contrast'), motion: $('#reduce-motion') };
let savedPreferences = {};
try { savedPreferences = JSON.parse(localStorage.getItem('tino-preferences') || '{}') || {}; } catch { /* Preferences remain usable when storage is unavailable. */ }
preferenceControls.textSize.value = ['normal', 'large', 'xlarge'].includes(savedPreferences.textSize) ? savedPreferences.textSize : 'normal';
preferenceControls.contrast.checked = savedPreferences.contrast === true;
preferenceControls.motion.checked = savedPreferences.motion === true;
function applyPreferences() {
  const settings = { textSize: preferenceControls.textSize.value, contrast: preferenceControls.contrast.checked, motion: preferenceControls.motion.checked };
  document.documentElement.dataset.textSize = settings.textSize;
  document.documentElement.dataset.contrast = String(settings.contrast);
  document.documentElement.dataset.motion = settings.motion ? 'reduced' : 'normal';
  try { localStorage.setItem('tino-preferences', JSON.stringify(settings)); } catch { /* Do not block reading if browser storage is disabled. */ }
}
Object.values(preferenceControls).forEach(control => control.addEventListener('change', applyPreferences));
applyPreferences();

const tabs = $$('[role="tab"]');
function activateTab(tab, focus = false) {
  tabs.forEach(item => {
    const selected = item === tab;
    item.setAttribute('aria-selected', String(selected));
    item.tabIndex = selected ? 0 : -1;
    $('#' + item.getAttribute('aria-controls')).hidden = !selected;
  });
  if (focus) tab.focus();
}
tabs.forEach((tab, index) => {
  tab.addEventListener('click', () => activateTab(tab));
  tab.addEventListener('keydown', event => {
    let next;
    if (event.key === 'ArrowRight') next = (index + 1) % tabs.length;
    else if (event.key === 'ArrowLeft') next = (index - 1 + tabs.length) % tabs.length;
    else if (event.key === 'Home') next = 0;
    else if (event.key === 'End') next = tabs.length - 1;
    else return;
    event.preventDefault();
    activateTab(tabs[next], true);
  });
});
$$('[data-permission]').forEach(control => control.addEventListener('change', () => {
  const key = control.dataset.permission;
  $$(`[data-private="${key}"]`).forEach(item => item.hidden = !control.checked);
  $$(`[data-hidden="${key}"]`).forEach(item => item.hidden = control.checked);
  $('#permissions-status').textContent = 'Vista de ejemplo actualizada. Los permisos reales se gestionan desde el teléfono de la persona.';
}));

const examples = {
  photo: { question: 'Tino, quiero enviar una foto por WhatsApp.', steps: ['Abrí WhatsApp y elegí la conversación a la que querés enviar la foto.', 'Buscá el botón para adjuntar. Según tu teléfono, puede aparecer como un clip o un signo +. Tocá «Galería» o «Fotos».', 'Elegí la foto y revisá que sea la que querés compartir. Cuando estés listo, tocá el botón de enviar.', '¡Listo! Terminaste el ejemplo. Podés volver a practicar cuando quieras.'] },
  text: { question: 'Tino, necesito que el texto del teléfono se vea más grande.', steps: ['Abrí la aplicación Ajustes de tu teléfono. Suele tener un ícono de engranaje.', 'Usá el buscador de Ajustes y escribí «tamaño de fuente». El nombre puede variar según tu teléfono.', 'Abrí la opción de tamaño de fuente y mové el control hasta que te resulte cómodo leer. Podés volver a cambiarlo cuando quieras.', 'Terminaste el ejemplo. En esta web también podés ajustar el tamaño del texto desde el botón de accesibilidad.'] },
  link: { question: 'Tino, recibí un enlace que no conozco. ¿Qué hago?', steps: ['Antes de abrirlo, revisá quién lo envió y qué te está pidiendo. ¿Esperabas ese mensaje?', 'Prestá atención al dominio y a los errores en el texto. La urgencia o una promesa inesperada pueden ser señales de alerta. Eso no alcanza para confirmar que sea seguro.', 'Si te genera dudas, no ingreses contraseñas ni códigos. Entrá por tu cuenta a la app o al sitio oficial y consultá con una persona de confianza.', 'Terminaste el ejemplo. Esta demostración no analizó ningún enlace real.'] }
};
let demoStep = 0;
function renderDemo() {
  const example = examples[$('#demo-task').value];
  $('#demo-question').textContent = '“' + example.question + '”';
  $('#demo-answer').textContent = example.steps[demoStep];
  $('#demo-progress').textContent = `${demoStep + 1} de ${example.steps.length} · Conversación de ejemplo`;
  $('#demo-back').disabled = demoStep === 0;
  $('#demo-next').textContent = demoStep === example.steps.length - 1 ? 'Volver a practicar ↺' : demoStep === example.steps.length - 2 ? 'Terminé este paso →' : 'Ya estoy ahí →';
}
$('#demo-task').addEventListener('change', () => { demoStep = 0; renderDemo(); });
$('#demo-back').addEventListener('click', () => { demoStep = Math.max(0, demoStep - 1); renderDemo(); });
$('#demo-next').addEventListener('click', () => { demoStep = (demoStep + 1) % examples[$('#demo-task').value].steps.length; renderDemo(); });
$$('[data-open-demo]').forEach(button => button.addEventListener('click', () => { demoStep = 0; renderDemo(); openDialog($('#demo')); }));
