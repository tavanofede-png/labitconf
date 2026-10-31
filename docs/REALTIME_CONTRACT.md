# TINO: voz, pantalla y continuidad

Modelo fijo `gpt-realtime-2.1-mini`; `OPENAI_API_KEY` solo existe en el backend. TINO acompaña la intención de la persona a través de aplicaciones, ajustes y trámites del teléfono. La app visible es una observación, no define el objetivo ni limita las tareas.

La persona hace cada toque. `point_to_control` solo señala IDs vigentes de una pantalla real. Ninguna herramienta pulsa, abre aplicaciones, completa campos o ejecuta envíos. Sin un control observable TINO puede orientar verbalmente, por ejemplo volver a Inicio mediante el botón o gesto observado. No inventa coordenadas ni considera los hints de apps un inventario exhaustivo. Puede responder preguntas generales sin pantalla.

## Activación y silencio

La escucha local de «Hola Tino» activa una consulta. El saludo no se detecta enviando audio permanentemente a OpenAI. Una pregunta del agente puede habilitar una única ventana breve de respuesta; el silencio no inicia un ciclo de escucha y respuestas.

Los cambios de app y snapshots son observaciones silenciosas. `screen.context` con `requestGuidance:false` no genera una respuesta; tampoco `screen.clear`. Durante una captura se conservan PCM, bytes y temporizador: al confirmar el turno se utiliza el contexto vigente. El backend no emite avisos hablados espontáneos sobre pantallas sensibles. La protección de datos no equivale a bloquear toda una app.

El cliente requiere permisos y aceptación para asistencia remota. El [contrato de pantalla](SCREEN_ASSISTANCE_CONTRACT.md) define saneamiento y captura. Familia e instituciones no reciben contenido de pantalla o conversación.

## Transporte y admisión

WebSocket `wss://<backend>/api/assistant/realtime`; `ws://` solo para pruebas locales. El handshake lleva `Authorization: Bearer <token de dispositivo>`, nunca tokens por URL ni claves OpenAI. La [admisión](../apps/api/docs/ADMISSION.md) define aprobación, revocación, gasto y concurrencia.

Con `CONTINUOUS_ASSISTANCE=1`, autorizado en el servidor, no hay cortes de TINO por veinte mensajes, sesenta pantallas ni cuota diaria. Permanecen los límites de saldo y servicio del proveedor y los límites técnicos individuales.

En Vercel cada transporte cloud dura como máximo **270.000 ms** y anuncia renovación a los **225.000 ms**. Android considera un margen conservador de 210 segundos antes de empezar otro turno: captura de hasta 45 segundos, generación de hasta 60, reproducción encolada de hasta 90 y 15 de margen de red. El cierre por renovación o inactividad continua es normal y renovable: no cancela la tarea. La escucha local puede continuar sin transporte abierto.

## Mensajes del cliente

| Mensaje | Comportamiento |
| --- | --- |
| `output.mode` con `mode:"text"` o `"audio"` | Selecciona modalidad antes del turno, sin captura o respuesta activa. |
| `audio.start` | Inicia captura y cancela una respuesta anterior. |
| `audio.append` con `audio` base64 | PCM16 little-endian, 24.000 Hz, mono; máximo 12.000 bytes por bloque. |
| `audio.commit` | Confirma entre 100 ms y 45 segundos de captura y solicita respuesta. |
| `text` con `text` | Consulta de hasta 1.000 caracteres, en la modalidad elegida. |
| `screen.context` | Reemplaza la observación. Con `requestGuidance:false` solo actualiza. Durante captura no genera respuesta, incluso si llega `true`. |
| `screen.clear` | Retira snapshot y targets anteriores, conservando una captura en curso. |
| `resume.context` con `context` | Restaura el resumen al abrir una conexión, antes de su primer turno; no pide una respuesta. |
| `cancel` con `playback` opcional | Cancela el turno y limpia captura y reproducción pendientes. |
| `playback.complete` con `turnId` | Confirma que el audio se terminó de reproducir realmente. |
| `close` | Cierra transporte y recursos. |

`playback` contiene `{turnId,itemId,contentIndex,audioEndMs}`. La posición procede de lo reproducido, no de bytes recibidos. El servidor valida el checkpoint, trunca lo oído y elimina audio posterior no escuchado. Una posición inválida no afecta otro turno; eventos tardíos no se etiquetan como la respuesta nueva.

## Mensajes del servidor

`ready` confirma configuración del proveedor, no solamente apertura del socket:

```json
{
  "type": "ready",
  "model": "gpt-realtime-2.1-mini",
  "outputMode": "audio",
  "audio": {"encoding": "pcm16", "sampleRate": 24000, "channels": 1},
  "maxInputSeconds": 45,
  "session": {"maxSessionMs": 270000, "renewAfterMs": 225000, "renewable": true}
}
```

- `renewal.required`: incluye `reason` y `session`. Es una indicación técnica de renovación, no voz ni error de tarea.
- `task.context`: comunica `context` actualizado por el modelo.
- `response.started`: identifica `turnId` y `outputMode`.
- `audio.delta`: incluye `turnId`, `itemId`, `contentIndex` y audio PCM base64.
- `text.delta` y `text.done`: texto de respuesta o su transcripción; no son un registro persistido de la persona.
- `guidance.step`: contiene `turnId`, `screenId`, `controlId` e `instruction` para un control actual.
- `response.done`: contiene `turnId`, `status` y `expectsReply`. Terminar generación no significa terminar reproducción ni completar la tarea.
- `cancelled`: descarta la salida parcial cancelada.
- `error`: código, mensaje redactado y `fatal`, sin claves, headers, objetos de proveedor ni transcripción de entrada.

`expectsReply` corresponde únicamente al turno recién terminado y comienza en `false`. Si es `true`, Android puede abrir una sola ventana de seguimiento de ocho segundos después de vaciar el audio. El silencio la cancela sin confirmar un turno vacío. Retomar un resumen fuerza `expectsReply:false`: no repite una invitación anterior a hablar.

## Resumen en RAM

La herramienta `set_task_context` actualiza:

```json
{
  "goal": "Objetivo expresado por la persona",
  "targetApp": "App de destino, aunque otra esté abierta",
  "progress": "Avance observado, sin valores de campos",
  "expectsReply": false
}
```

Límites: `goal` 600 caracteres, `targetApp` 80, `progress` 400 y JSON UTF-8 de hasta 4096 bytes. No admite propiedades de comando, credenciales, OTP, PIN ni datos bancarios reconocibles. Para vaciar el objetivo se envían las tres cadenas vacías. Las confirmaciones sensibles para enviar, pagar o dar permisos se vuelven a solicitar cuando corresponda después de renovar.

Android conserva ese objeto solamente en RAM entre transportes y lo envía como `resume.context`. El servidor lo etiqueta como datos no confiables: no es historial, instrucción de sistema ni autorización. Una petición actual tiene prioridad. Detener asistencia, revocar acceso, cambiar servidor/token o cerrar sesión elimina el resumen del cliente; la conexión anterior elimina su copia al cerrar. No se guarda en Blob, archivos, preferencias, métricas ni logs.

El servidor conserva estados recientes acotados para cancelar y descartar eventos tardíos. Eliminar un corte por mensajes no implica conservar mapas o audio sin límite.

## Compatibilidad y verificación

`POST /api/assistant/message` conserva consultas de texto y admisión. En cloud no las sustituye por recetas prefijadas ni otro modelo; adjuntos no habilitados producen un error explícito. La continuidad de voz y objetivo utiliza WebSocket.

`GET /api/health` informa configuración sin secretos; configurado no significa probado. Las pruebas usan upstream local controlado. `scripts/verify-cloud-assistant.mjs --preflight` verifica salud y rechazo HTTP/WS sin consumir una respuesta de IA. Su modo completo hace una comprobación real acotada y revoca el dispositivo de prueba. Comprobar transporte no sustituye la prueba física de micrófono, activación y señalización.

Fuentes: [Realtime](https://developers.openai.com/api/docs/guides/realtime-conversations), [WebSockets Vercel](https://vercel.com/docs/functions/websockets), [waitUntil](https://vercel.com/docs/functions/functions-api-reference/vercel-functions-package#waituntil).
