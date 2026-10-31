# Conversación Realtime de NIETO

El backend usa exclusivamente `gpt-realtime-2.1-mini` para el chat y la voz del asistente. La clave se lee de `OPENAI_API_KEY` al iniciar la API y nunca se devuelve a clientes, se guarda en el JSON de datos ni se escribe en logs. La integración de OpenArg continúa por separado, con su propia clave y sus rutas institucionales.

## Voz en streaming

El APK se conecta a `/api/assistant/realtime` con su token de dispositivo NIETO en el header `Authorization`. El servidor valida la identidad antes de abrir una conexión WebSocket autenticada con OpenAI. Solo acepta el protocolo reducido definido en [REALTIME_CONTRACT.md](../../../docs/REALTIME_CONTRACT.md); no es un proxy de eventos OpenAI arbitrarios.

Después de recibir `ready`, el cliente puede iniciar la captura por un gesto explícito. Se usa PCM16 little-endian, mono, 24 kHz. La entrada se manda en bloques y la salida se reproduce incrementalmente. La transcripción de la respuesta sale de los eventos Realtime de audio; no se configura un segundo modelo de transcripción. No hay captura permanente, VAD ni escucha en segundo plano. Una consulta de voz puede aparecer en el historial local como consulta de voz sin transcripción del usuario.

La conversación de voz se mantiene en memoria en OpenAI y en los identificadores de la conexión del servidor. Se destruye al desconectar. NIETO no persiste audio, consultas ni respuestas en su archivo JSON. La voz y el texto sí se transmiten al proveedor para generar la respuesta; el cliente debe comunicarlo y obtener aceptación antes de activarlos. La retención del proveedor está sujeta a la configuración y políticas de la cuenta OpenAI; no se afirma retención cero por parte del proveedor.

Límites del backend:

- Una conversación activa por dispositivo y ocho conexiones simultáneas, incluyendo consultas HTTP.
- Veinte intentos de handshake por IP por minuto; hasta 250 mensajes de cliente por ventana de diez segundos.
- Captura máxima de 45 segundos y bloques de hasta 12.000 bytes PCM. Una captura menor a 100 ms no genera respuesta.
- Conexión upstream con máximo de 15 segundos de preparación, respuesta con máximo de 60 segundos y sesión de diez minutos. Se cierra después de dos minutos de inactividad, para permitir que termine de reproducirse una respuesta de hasta 90 segundos.
- Salida acotada por tokens, tamaño de texto y audio; se corta ante congestión de buffers.
- Cancelación de turno y desconexión cierran o limpian los recursos correspondientes. Los paquetes tardíos se relacionan con el identificador de respuesta original y no se atribuyen al turno nuevo.

Al interrumpir una reproducción, el cliente debe cancelar y cerrar la conexión, y abrir una nueva para la siguiente consulta. Android ya sigue ese procedimiento, que descarta el contexto que podría contener audio generado que la persona no escuchó. Mantener la misma sesión después de cortar reproducción requeriría informar la posición realmente escuchada y truncar ese audio en OpenAI; el protocolo actual no ofrece esa operación.

HTTP y WS requieren HTTPS/WSS fuera del prototipo local. El servidor no reintenta automáticamente una generación fallida ni sustituye el modelo. Falta de credencial, acceso al modelo, saldo/cuota y límites se comunican como errores explícitos.

## Guías y autoridad de la aplicación

El modelo conversa, pero no ve la pantalla. No se envían imágenes, mensajes de WhatsApp ni el árbol de accesibilidad. Su única función habilitada, `offer_guided_flow`, puede ofrecer `whatsapp-location`. El servidor verifica el nombre y los argumentos y comunica `guidance.offer`; el usuario inicia la guía explícitamente en el cliente. Esa función no ejecuta toques, envíos, llamadas, solicitudes familiares ni eventos de progreso. El motor Android conserva la verificación local del procedimiento y sus confirmaciones.

El modelo puede responder preguntas generales de asistencia digital. La información específica de una pantalla no disponible debe presentarse como límite; esta integración no resuelve los defectos del motor local registrados en [AUDIT.md](../../../docs/AUDIT.md).

## Chat HTTP

`POST /api/assistant/message` conserva el contrato `{text,sessionId?}`. El servidor abre una sesión Realtime en modalidad texto para esa solicitud, la cierra al terminar y responde con `{mode,instruction,flowId?,sources:[],model}`. Cada solicitud HTTP tiene contexto independiente; no hay historial conversacional entre llamadas. El campo `sessionId` solo comprueba pertenencia de una sesión guiada, no añade mensajes privados del pasado al modelo.

Sin clave configurada responde 503 `openai_not_configured`. No usa la respuesta fija anterior como reemplazo de IA. Los tests existentes deshabilitan explícitamente las credenciales para evitar llamadas a un servicio externo por accidente.

## Estado y comprobación

`GET /api/health` contiene `assistant.configured`, `model`, `voiceStreaming`, `generalConversation`, `mode` y `screenInput:false`. Configurado indica la presencia de una clave, no que la cuenta ya haya pasado una llamada de prueba.

Desde la raíz:

```powershell
npm run verify:realtime --workspace @nieto/api
npm run verify:realtime --workspace @nieto/api -- --text
node --test apps/api/test/realtime.test.mjs
```

Las dos verificaciones usan la clave configurada y realizan una solicitud pequeña. La primera comprueba salida de audio y texto a partir de una entrada de texto; la segunda comprueba modalidad texto. Imprimen solo metadatos y errores redactados. No prueban el micrófono ni la reproducción física del teléfono. Los tests usan un servidor WS local de prueba sin clave real.

Fuentes oficiales consultadas: [modelo](https://developers.openai.com/api/docs/models/gpt-realtime-2.1-mini), [conversaciones y eventos](https://developers.openai.com/api/docs/guides/realtime-conversations) y [conexión WebSocket](https://developers.openai.com/api/docs/guides/voice-websockets).
