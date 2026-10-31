# NIETO Realtime

Modelo fijo `gpt-realtime-2.1-mini`. La clave `OPENAI_API_KEY` solo existe en el entorno del backend. La voz y las consultas se envían a OpenAI después de la aceptación en el cliente. La extensión de [asistencia de pantalla](SCREEN_ASSISTANCE_CONTRACT.md) permite controles saneados y una captura efímera con consentimiento remoto separado. La IA propone un paso sobre un control existente; ninguna función toca controles, comparte ubicación ni acredita tareas completadas. Familia e instituciones no reciben el contenido de la pantalla.

## Transporte

WebSocket `/api/assistant/realtime`, usando `ws://` solo para el APK debug local y `wss://` en producción. El cliente adjunta `Authorization: Bearer <token de dispositivo NIETO>` al handshake (no query string ni clave OpenAI). El servidor autentica antes de conectar con OpenAI y limita conexiones y uso. Conversación en memoria por conexión; se elimina al desconectar. Un micrófono solo se activa desde un gesto explícito.

Audio de entrada y salida: PCM firmado little-endian de 16 bits, 24.000 Hz, un canal. Máximo 45 segundos de micrófono por turno; el cliente se detiene automáticamente y el servidor aplica el límite de bytes. No hay VAD ni escucha permanente. El usuario toca Hablar y luego Terminar de hablar.

### Mensajes del cliente

- `{type:"audio.start"}`: comienza un turno nuevo, cancela una respuesta anterior si existe y limpia el audio de entrada pendiente. Solo después de `ready`.
- `{type:"audio.append",audio:"<base64 PCM>"}`: un bloque de hasta 12.000 bytes decodificados (preferidos 2.400–4.800 bytes). Solo entre start y commit. No admite imágenes, instrucciones de sistema ni mensajes arbitrarios del protocolo OpenAI.
- `{type:"audio.commit"}`: termina la captura y pide respuesta con audio. Audio mínimo 100 ms y máximo 45 segundos. Captura inválida devuelve error sin generar respuesta.
- `{type:"text",text:"..."}`: alternativa de texto en esta misma conversación; máximo 1.000 caracteres. Devuelve audio y su transcripción. Solo un turno activo.
- `{type:"cancel"}`: cancela captura y respuesta, limpia buffers. El cliente también vacía su reproducción.
- `{type:"close"}`: termina sesión y libera captura, reproducción y conexión upstream.

### Mensajes del servidor

- `{type:"ready",model:"gpt-realtime-2.1-mini",audio:{encoding:"pcm16",sampleRate:24000,channels:1},maxInputSeconds:45}`: sesión configurada y lista. No basta que se abra el socket TCP.
- `{type:"response.started",turnId:"..."}`: identifica la nueva respuesta. El cliente descarta audio/texto con identificador anterior.
- `{type:"audio.delta",turnId:"...",audio:"<base64 PCM>"}`: audio incremental para reproducción inmediata sin escribir archivos.
- `{type:"text.delta",turnId:"...",delta:"..."}` y `{type:"text.done",turnId:"...",text:"..."}`: texto de salida o transcripción de la respuesta de voz. Evitar duplicarla en el chat.
- `{type:"guidance.step",turnId:"...",screenId:"...",controlId:"...",instruction:"..."}`: paso sobre un control existente del snapshot actual. Es la única herramienta del agente; no hay ofertas de guías locales ni redirección por palabras clave.
- `{type:"response.done",turnId:"...",status:"completed"|"cancelled"|"failed"}`: terminó la generación; permitir vaciar el audio ya encolado antes de liberar el reproductor. Una finalización no completa no se presenta como éxito.
- `{type:"cancelled"}`: el servidor canceló el turno. El cliente descarta reproducción y texto parcial de ese turno.
- `{type:"error",code:"...",message:"...",fatal:false|true}`: error de configuración, autorización, cuota, red, captura o protocolo. Mensajes redactados; no incluye objetos de proveedor, claves, headers o transcripciones de entrada. Un error fatal cierra el socket.

Las respuestas OpenAI identificadas como pertenecientes a turnos cancelados no deben etiquetarse como la respuesta nueva. Se necesita relacionar response.id con turnId.

## Texto HTTP existente

`POST /api/assistant/message` conserva `{text,sessionId?}` y devuelve `{mode:"conversation",instruction,sources:[],model}` sin `flowId`. La respuesta viene del mismo modelo mediante Realtime en modalidad texto. La falta de clave, cuota o acceso devuelve error explícito. HTTP usa solo la solicitud actual; el WebSocket conserva contexto efímero por conexión. La guía local se abre exclusivamente desde su control explícito en Android.

## Estado y verificación

`GET /api/health` añade `assistant:{configured,model,voiceStreaming,generalConversation,...}` sin secretos. Configurado no significa validado con una llamada real. El script de verificación, cuando hay clave, hace una solicitud pequeña y termina. Las pruebas automatizadas usan upstream local falso, nunca credenciales del proyecto ni datos reales.

Fuentes oficiales consultadas: https://developers.openai.com/api/docs/models/gpt-realtime-2.1-mini y https://developers.openai.com/api/docs/guides/realtime-conversations y https://developers.openai.com/api/docs/guides/voice-websockets.
