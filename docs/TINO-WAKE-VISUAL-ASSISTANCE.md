# «Hola TINO»: asistencia visual fuera de la app

## Recorrido

1. La persona habilita micrófono y escucha «Hola TINO». Vosk reconoce localmente la frase de activación y recoge el pedido posterior. El audio ambiente no se transmite continuamente al modelo.
2. Fuera de TINO, el pedido entra en asistencia visual solamente con autorización para IA y lectura remota de pantalla, y con el servicio de Accesibilidad conectado. Activar escucha no otorga esos permisos. Sin ellos no se promete señalar botones.
3. Android obtiene una instantánea actual de los controles accesibles, sus textos, identificadores y rectángulos. Con permiso separado puede adjuntar una imagen en Android 11 o posterior. Se bloquean pantallas sensibles y se ocultan campos editables y ventanas ajenas; esto no equivale a garantizar que toda información privada de una pantalla permitida quede oculta.
4. El backend envía el objetivo y el contexto actual al modelo. `point_to_control` debe devolver un `screenId` y un `controlId` existentes, más una instrucción. No se aceptan coordenadas inventadas ni objetivos de una pantalla anterior.
5. `AssistantOverlay` dibuja un marco verde nativo que no intercepta toques. TINO dice un paso y la persona toca el control real de la otra aplicación. TINO no pulsa ni envía en su nombre.
6. Los eventos de Accesibilidad invalidan el marco anterior. Después de estabilizarse la pantalla se captura el contexto nuevo y se pide el siguiente paso. Se elimina del contexto conversacional la imagen anterior. La repetición tiene límites de frecuencia y cantidad de pasos.

Ejemplo: señalar el icono visible de WhatsApp → observar que se abrió → identificar el control real para iniciar un grupo → señalarlo. No se fija «el + arriba a la derecha»: la interfaz puede variar, el icono puede no estar visible y WhatsApp puede no estar instalado. En esos casos corresponde pedir una acción o aclaración, no inventar un rectángulo.

## Código

- `presence/WakeVoiceService.kt`: activación local y captura del pedido; pausa durante captura, respuestas y audio de TINO.
- `presence/WakeCommandPolicy.kt`: conversación dentro de TINO, asistencia externa según permisos y órdenes explícitas de detener.
- `NietoApp.handleWakeCommand` y `sendScreenMessage`: captura reciente, tiempo máximo de espera, control de cancelaciones y conexión al circuito visual existente.
- `screen/NietoAccessibilityService.kt`: lectura, cambios y captura de pantalla.
- `screen/AssistantOverlay.kt`: marco y consulta desde superposición.
- `apps/api/src/realtime.mjs`: instrucciones y validación del objetivo contra la pantalla actual.

Las órdenes explícitas de detener recibidas por el reconocedor finalizan la observación. La escucha se pausa mientras TINO habla: no se promete interrupción por voz durante su propia respuesta. También puede detenerse desde los controles de asistencia. Los servicios en segundo plano están sujetos a restricciones de Android y del fabricante; no se promete la disponibilidad privilegiada de Siri.

## Datos y privacidad

Las imágenes se procesan para la asistencia activa y no se guardan como historial en TINO ni se publican en Familia o Instituciones. Esto no constituye una promesa sobre la retención del proveedor externo. El grupo familiar registra «Solicitó ayuda de TINO», no «Creó un grupo», «Envió un correo» ni aprendizaje o autonomía inferidos.

## Verificación: 31 de octubre de 2026

- Backend: 102 pruebas aprobadas. La prueba de avance launcher → WhatsApp usa instantáneas y proveedor simulados; exige controles de cada pantalla, eliminación del contexto anterior y `executed: false`.
- Android: 52 pruebas JVM, compilación y Lint aprobados. Incluye rutas de voz para distintos pedidos, permisos ausentes y cancelación explícita.
- La consulta real de diagnóstico a OpenAI falla con `invalid_api_key`. Accesibilidad está desactivada y WhatsApp no está instalado en el emulador actual. **El recorrido completo con IA real y WhatsApp no está validado.**

Referencia de plataforma: [AccessibilityService de Android](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService).

## Dos recorridos prioritarios de demostración

1. **Crear un grupo de WhatsApp:** abrir la app, encontrar Nuevo grupo, seleccionar participantes, continuar, escribir nombre, revisar y confirmar personalmente su creación.
2. **Enviar un mail:** abrir correo, Redactar, Para, Asunto, Mensaje, adjunto sólo si se pide, revisión explícita y toque personal en Enviar. Si se abre un borrador ya preparado, retomar sin duplicar datos ni pasos. Después se puede orientar para consultar Enviados, sin certificar entrega.

Son etapas orientativas, no un contador que avance solo. En campos ocultos se espera la confirmación de la persona; no se interpreta un cambio de pantalla ni el silencio como dato escrito o revisado. Si varios controles son «Campo de texto», TINO debe pedir aclaración antes de señalar uno ambiguo. La revisión antes de Enviar se incluye en las instrucciones del modelo; no existe un verificador independiente de esa revisión.

La prueba adicional de correo comprueba el protocolo con proveedor simulado: bandeja → Redactar → borrador → confirmación textual de revisión → marco en Enviar, siempre `executed: false`. No evalúa la capacidad de un modelo real de interpretar Gmail ni prueba un envío real. Se mantienen los bloqueos de validación real descritos arriba.
