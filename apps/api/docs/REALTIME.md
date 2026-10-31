# Conversación Realtime de NIETO

El backend usa exclusivamente `gpt-realtime-2.1-mini` para chat, voz y comprensión de pantalla. La clave se lee de `OPENAI_API_KEY` al iniciar la API y nunca se devuelve a clientes, se guarda en el JSON de datos ni se escribe en logs. La integración de OpenArg continúa por separado, con su propia clave y sus rutas institucionales.

## Voz en streaming

El APK se conecta a `/api/assistant/realtime` con su token de dispositivo NIETO en el header `Authorization`. El servidor valida la identidad antes de abrir una conexión WebSocket autenticada con OpenAI. Solo acepta el protocolo reducido definido en [REALTIME_CONTRACT.md](../../../docs/REALTIME_CONTRACT.md); no es un proxy de eventos OpenAI arbitrarios.

Después de recibir `ready`, el cliente puede iniciar la captura por un gesto explícito. Se usa PCM16 little-endian, mono, 24 kHz. La entrada se manda en bloques y la salida se reproduce incrementalmente. La transcripción de la respuesta sale de los eventos Realtime de audio; no se configura un segundo modelo de transcripción. No hay captura permanente, VAD ni escucha en segundo plano. Una consulta de voz puede aparecer en el historial local como consulta de voz sin transcripción del usuario.

La conversación de voz se mantiene en memoria en OpenAI y en los identificadores de la conexión del servidor. Se destruye al desconectar. NIETO no persiste audio, consultas ni respuestas en su archivo JSON. La voz y el texto sí se transmiten al proveedor para generar la respuesta; el cliente debe comunicarlo y obtener aceptación antes de activarlos. La retención del proveedor está sujeta a la configuración y políticas de la cuenta OpenAI; no se afirma retención cero por parte del proveedor.

Límites del backend:

- Una conversación activa por dispositivo y ocho conexiones simultáneas, incluyendo consultas HTTP.
- Veinte intentos de handshake por IP por minuto; hasta 250 mensajes de cliente por ventana de diez segundos.
- Captura máxima de 45 segundos y bloques de hasta 12.000 bytes PCM. Una captura menor a 100 ms no genera respuesta.
- Conexión upstream con máximo de 15 segundos de preparación, respuesta con máximo de 60 segundos y sesión de diez minutos. Se cierra después de dos minutos de inactividad, para permitir que termine de reproducirse una respuesta de hasta 90 segundos.
- Salida acotada por tokens, tamaño de texto y audio; se corta ante congestión de buffers. Contexto de pantalla de hasta 60 controles e imagen JPEG opcional de 300 KB, 60 snapshots/8 MB de imágenes por conexión y al menos 750 ms entre actualizaciones.
- Cancelación de turno y desconexión cierran o limpian los recursos correspondientes. Los paquetes tardíos se relacionan con el identificador de respuesta original y no se atribuyen al turno nuevo.

Al interrumpir una reproducción, el cliente debe cancelar y cerrar la conexión, y abrir una nueva para la siguiente consulta. Android ya sigue ese procedimiento, que descarta el contexto que podría contener audio generado que la persona no escuchó. Mantener la misma sesión después de cortar reproducción requeriría informar la posición realmente escuchada y truncar ese audio en OpenAI; el protocolo actual no ofrece esa operación.

HTTP y WS requieren HTTPS/WSS fuera del prototipo local. El servidor no reintenta automáticamente una generación fallida ni sustituye el modelo. Falta de credencial, acceso al modelo, saldo/cuota y límites se comunican como errores explícitos.

## Pantalla actual y autoridad de la aplicación

Con consentimiento remoto de pantalla separado, el cliente envía `screen.context` según [SCREEN_ASSISTANCE_CONTRACT.md](../../../docs/SCREEN_ASSISTANCE_CONTRACT.md). Aporta IDs, etiquetas, roles y bounds de controles accesibles y, cuando la plataforma lo permite, una captura JPEG. Todo el contenido de pantalla se trata como datos no confiables, sin autoridad para modificar instrucciones. La política local bloquea pantallas sensibles reconocidas; no se garantiza detección universal de información sensible.

El backend valida esquema, tamaño, dimensiones, IDs únicos de controles y bounds dentro de pantalla. Solo conserva el snapshot actual en memoria; lo reemplaza y elimina el item anterior en OpenAI al actualizarlo. El ID puede conservarse si Android identifica la misma pantalla. `screen.clear` cancela la generación, elimina el snapshot vigente y retira su autoridad para señalar. Terminar la asistencia cierra la conexión para descartar el contexto de la tarea. Capturas, controles, texto y audio no se escriben al archivo JSON ni a logs.

La función `point_to_control(screenId,controlId,instruction)` solo emite `guidance.step` si el turno y los IDs corresponden al snapshot vigente. Nunca acepta coordenadas inventadas ni ejecuta toques, envíos, navegación, pagos o instalaciones. Una imagen sin controles accesibles no permite señalar regiones arbitrarias. Android revalida paquete, identidad y bounds antes de resaltar; el usuario ejecuta cada acción. No se acredita finalización ni autonomía a partir del modelo.

La única herramienta del agente es `point_to_control`. `offer_guided_flow` fue retirada: ni palabras como WhatsApp ni una función inesperada del proveedor pueden devolver `guidance.offer`, `mode:"guided"` o un `flowId`. La guía local solo se abre desde la elección explícita en la UI. Los modelos no registran eventos de progreso ni solicitudes familiares. La asistencia genérica registra únicamente actividad operacional bajo `screen-assistance` y un paso `screen-step`. No hay un verificador semántico habilitado: el backend rechaza `flow_completed`, `practice_completed` y `autonomy_completed` en todos los flujos. El recorrido local solo admite un reporte explícito de la persona después de validar el orden de pasos, según el [protocolo de sesiones](SESSIONS.md).

El modelo puede responder preguntas generales de asistencia digital. La información específica de una pantalla no disponible debe presentarse como límite. La comprensión visual es probabilística; requiere la validación física del teléfono concreto y no resuelve por sí misma los defectos de verificación de la guía local registrados en [AUDIT.md](../../../docs/AUDIT.md).

## Chat HTTP

`POST /api/assistant/message` conserva el pedido `{text,sessionId?}`. El servidor abre una sesión Realtime en modalidad texto para esa solicitud, la cierra al terminar y responde con `{mode:"conversation",instruction,sources:[],model}`. Cada solicitud HTTP tiene contexto independiente; no hay historial conversacional entre llamadas. El campo `sessionId` solo comprueba pertenencia de una sesión guiada, no añade mensajes privados del pasado al modelo ni permite seleccionar un flujo.

Sin clave configurada responde 503 `openai_not_configured`. No usa la respuesta fija anterior como reemplazo de IA. Los tests existentes deshabilitan explícitamente las credenciales para evitar llamadas a un servicio externo por accidente.

## Estado y comprobación

`GET /api/health` contiene `assistant.configured`, `model`, `voiceStreaming`, `generalConversation`, `mode` y `screenInput`. Configurado indica la presencia de una clave, no que la cuenta ya haya pasado una llamada de prueba.

Desde la raíz:

```powershell
npm run verify:realtime --workspace @nieto/api
npm run verify:realtime --workspace @nieto/api -- --text
npm run verify:screen --workspace @nieto/api
node --test apps/api/test/realtime.test.mjs
```

Las verificaciones usan la clave configurada y realizan solicitudes pequeñas. Realtime comprueba audio o modalidad texto; screen usa exclusivamente una imagen artificial incluida como fixture y verifica un señalamiento validado del botón Buscar. Imprimen solo metadatos y errores redactados. La prueba real de imagen de 12.421 bytes y función `point_to_control` pasó con el modelo exacto durante esta implementación; no verifica capturas, permisos, micrófono ni reproducción física del teléfono. Los tests automatizados usan un servidor WS local de prueba sin clave real.

Fuentes oficiales consultadas: [modelo](https://developers.openai.com/api/docs/models/gpt-realtime-2.1-mini), [conversaciones y eventos](https://developers.openai.com/api/docs/guides/realtime-conversations) y [conexión WebSocket](https://developers.openai.com/api/docs/guides/voice-websockets).
