# TINO: voz, pantalla y continuidad

Modelo fijo `gpt-realtime-2.1-mini`; `OPENAI_API_KEY` solo existe en el backend. TINO acompaña la intención de la persona a través de aplicaciones, ajustes y trámites del teléfono. La app visible es una observación, no define el objetivo ni limita las tareas.

La persona hace cada toque. `point_to_control` solo señala IDs vigentes de una pantalla real. Ninguna herramienta pulsa, abre aplicaciones, completa campos o ejecuta envíos. Sin un control observable TINO puede orientar verbalmente, por ejemplo volver a Inicio mediante el botón o gesto observado. No inventa coordenadas ni considera los hints de apps un inventario exhaustivo. Puede responder preguntas generales sin pantalla.

## Activación y silencio

La escucha local de «Hola Tino» activa una consulta. El saludo no se detecta enviando audio permanentemente a OpenAI. Después de responder, TINO vuelve a esperar la activación explícita: una pregunta del agente no abre el micrófono automáticamente ni captura conversaciones de otras personas. `expectsReply` es informativo.

«Terminar conversación» cierra el transporte y borra el contexto temporal, pero mantiene la escucha local de «Hola Tino». Después de **30 segundos sin interacción** la conversación también termina y su memoria se elimina. Una activación posterior empieza desde cero. Cada acción real observada —toque, escritura, desplazamiento o navegación a otra app— reinicia el plazo de una conversación activa. Las actualizaciones pasivas de pantalla, pings y configuración no lo prolongan. No se corta una captura, generación o reproducción activa; el plazo comienza al terminar esa actividad.

Los cambios de app y snapshots son observaciones silenciosas. `screen.context` con `requestGuidance:false` no genera una respuesta para la persona; tampoco `screen.clear`. Una acción real seguida por una pantalla nueva puede activar la evaluación interna silenciosa descrita abajo. Durante una captura se conservan PCM, bytes y temporizador: al confirmar el turno se utiliza el contexto vigente. El backend no emite avisos hablados espontáneos sobre pantallas sensibles. La protección de datos no equivale a bloquear toda una app.

El cliente requiere permisos y aceptación para asistencia remota. El [contrato de pantalla](SCREEN_ASSISTANCE_CONTRACT.md) define saneamiento y captura. Familia e instituciones no reciben contenido de pantalla o conversación.

## Transporte y admisión

WebSocket `wss://<backend>/api/assistant/realtime`; `ws://` solo para pruebas locales. El handshake lleva `Authorization: Bearer <token de dispositivo>`, nunca tokens por URL ni claves OpenAI. La [admisión](../apps/api/docs/ADMISSION.md) define aprobación, revocación, gasto y concurrencia.

Con `CONTINUOUS_ASSISTANCE=1`, autorizado en el servidor, no hay cortes de TINO por veinte mensajes, sesenta pantallas ni cuota diaria. Permanecen los límites de saldo y servicio del proveedor y los límites técnicos individuales.

En Vercel cada transporte cloud dura como máximo **270.000 ms** y anuncia renovación a los **225.000 ms**. Android considera un margen conservador de 210 segundos antes de empezar otro turno: captura de hasta 45 segundos, generación de hasta 60, reproducción encolada de hasta 90 y 15 de margen de red. Solo una rotación técnica durante una consulta activa puede conservar su resumen en RAM. El cierre por inactividad o por «Terminar conversación» es normal y no renovable: borra la tarea. La escucha local continúa sin transporte abierto.

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
| `user.activity` con `kind:"tap"`, `"scroll"`, `"text"` o `"navigation"` | Informa una acción real y reinicia los 30 segundos de una conversación activa; no contiene valores, texto escrito, IDs ni coordenadas. No pide respuesta, abre conexión ni revive una conversación vencida. |
| `resume.context` con `context` | Restaura el resumen al rotar una conexión durante una consulta activa, antes de su primer turno; no pide una respuesta. Nunca se usa después de terminar o de 30 segundos de inactividad. |
| `cancel` con `playback` opcional | Cancela el turno y limpia captura y reproducción pendientes. |
| `playback.complete` con `turnId` | Confirma que el audio se terminó de reproducir realmente. |
| `close` | Termina la conversación, borra el resumen y cierra transporte y recursos; conserva el detector local de «Hola Tino». |

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
  "session": {"maxSessionMs": 270000, "renewAfterMs": 225000, "idleTimeoutMs": 30000, "renewable": true}
}
```

- `renewal.required`: incluye `reason:"transport_rotation"` y `session`. Es una indicación técnica de renovación durante una consulta activa, no voz ni error de tarea. No se emite para inactividad.
- `conversation.ended`: incluye `reason:"idle"`, `"user_end"` o `"task_completed"`; exige descartar el resumen, cortar salida pendiente y no reconectar ni retomar esa conversación automáticamente. Lo precede un `task.context` vacío y sigue un cierre normal.
- `task.context`: comunica `context` actualizado por el modelo.
- `response.started`: identifica `turnId` y `outputMode`.
- `audio.delta`: incluye `turnId`, `itemId`, `contentIndex` y audio PCM base64.
- `text.delta` y `text.done`: texto de respuesta o su transcripción; no son un registro persistido de la persona.
- `guidance.step`: contiene `turnId`, `screenId`, `controlId` e `instruction` para un control actual.
- `response.done`: contiene `turnId`, `status` y `expectsReply`. Terminar generación no significa terminar reproducción ni completar la tarea.
- `cancelled`: descarta la salida parcial cancelada.
- `error`: código, mensaje redactado y `fatal`, sin claves, headers, objetos de proveedor ni transcripción de entrada.

`expectsReply` corresponde únicamente al turno recién terminado y comienza en `false`. Es información sobre la respuesta del modelo: no autoriza una captura, no reactiva el micrófono ni renueva el plazo de inactividad. La siguiente consulta requiere «Hola Tino» o una acción explícita de la persona. Retomar un resumen fuerza `expectsReply:false`.

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

La herramienta del modelo admite además `endReason:"task_completed"` o `"user_end"` únicamente con las tres cadenas vacías y `expectsReply:false`. Ese dato no forma parte del resumen ni de `resume.context`. Un pedido de terminar se interpreta por su intención y contexto, sin depender de una lista literal de frases. Completar un paso intermedio conserva el objetivo cuando quedan pasos pendientes. Un fin válido cierra inmediatamente, sin otra respuesta de herramienta, despedida ni narración automática; eventos tardíos de texto o audio se descartan. Si el modelo vacía la tarea sin indicar razón, se trata como `user_end`.

Android conserva ese objeto solamente en RAM durante la consulta activa y su rotación técnica, y solo entonces lo envía como `resume.context`. El servidor lo etiqueta como datos no confiables: no es historial, instrucción de sistema ni autorización. Una petición actual tiene prioridad. «Terminar conversación», 30 segundos sin interacción, revocar acceso, cambiar servidor/token o cerrar sesión eliminan el resumen del cliente; la conexión anterior elimina su copia al cerrar. Después de terminar o de inactividad nunca se envía `resume.context`. No se guarda en Blob, archivos, preferencias, métricas ni logs.

La caducidad del servidor también usa 30 segundos por defecto y no se refresca con snapshots, limpieza de pantalla, configuración ni mensajes inválidos. Mientras hay captura, generación o reproducción pendiente, espera que esa actividad termine. La reproducción tiene además una espera acotada por la duración máxima del audio y un margen de red si se pierde su confirmación: ningún paquete de finalización ausente conserva memoria indefinidamente.

`user.activity` tiene exactamente dos propiedades: `type` y `kind`. Solo se acepta para una conversación ya iniciada o retomada; una conexión preparada sin consulta no conserva memoria por esos mensajes. Android agrupa ráfagas con intervalo mínimo de 300 ms y conserva su último evento. Cada evento válido recibido reinicia el plazo; el servidor agrupa el trabajo de evaluación con intervalo mínimo de 250 ms y conserva el límite global de frecuencia del socket. Una actividad recibida después del vencimiento termina la conversación en vez de resucitarla. El evento no se reenvía al modelo ni se persiste.

## Evaluación silenciosa de finalización

Con continuidad autorizada, una acción real seguida por un snapshot diferente y estable durante dos segundos puede comprobar si terminó el objetivo completo. No hay polling: snapshots pasivos, cambios de ID sin contenido nuevo y actividad sin pantalla nueva no invocan esta evaluación. Se conserva únicamente una copia reducida de controles previa a la acción en RAM, sin imágenes adicionales. Ver una app de destino, un botón final o un slider no demuestra haber completado una tarea; ante evidencia insuficiente se mantiene el silencio.

La comprobación usa la misma conexión OpenAI con `response.create`, `conversation:"none"`, `output_modalities:["text"]`, metadata `nieto_probe_id` y herramienta forzada `report_task_completion`. Lleva el objetivo, progreso y controles reducidos anterior/actual como datos no confiables. Tiene hasta 200 tokens, diez segundos, una evaluación activa y separación mínima de dos segundos. No escribe en la conversación, no envía texto o audio al cliente, no consume un turno de usuario ni reinicia el plazo de inactividad. No se reintenta automáticamente y solo actúa en el modo de consumo continuo autorizado.

`completed:true` requiere el ID del snapshot vigente y al menos un ID de evidencia real, visible y no protegido; herramientas, tipos, IDs o propiedades ajenos se descartan. Su resultado solo se acepta si no cambiaron tarea, acción ni pantalla y no hay consulta o reproducción activa. La intención y evidencia del objetivo completo las evalúa el modelo: no hay recetas por aplicación ni una afirmación de autonomía verificada.

Una nueva consulta o acción cancela la evaluación por su `response_id`; si ese ID llega tarde se cancela al recibirlo. Metadata y mapas separados impiden asignar sus eventos al turno principal, incluso cuando llegan tarde. Un error, timeout, respuesta inválida o prueba de un paso intermedio conserva el silencio. Un fin aceptado emite `conversation.ended` con `reason:"task_completed"` y vuelve a esperar «Hola Tino».

El servidor conserva estados recientes acotados para cancelar y descartar eventos tardíos. Eliminar un corte por mensajes no implica conservar mapas o audio sin límite.

## Compatibilidad y verificación

`POST /api/assistant/message` conserva consultas de texto y admisión. En cloud no las sustituye por recetas prefijadas ni otro modelo; adjuntos no habilitados producen un error explícito. Un fin silencioso responde con `ended:true`, `reason` e `instruction:""`, sin pedir otra generación. La continuidad de voz y objetivo utiliza WebSocket.

`GET /api/health` informa configuración sin secretos; configurado no significa probado. Las pruebas usan upstream local controlado. `scripts/verify-cloud-assistant.mjs --preflight` verifica salud y rechazo HTTP/WS sin consumir una respuesta de IA. Su modo completo hace una comprobación real acotada y revoca el dispositivo de prueba. Comprobar transporte no sustituye la prueba física de micrófono, activación y señalización.

Fuentes: [Realtime](https://developers.openai.com/api/docs/guides/realtime-conversations), [eventos del servidor Realtime](https://developers.openai.com/api/reference/resources/realtime/server-events), [respuestas fuera de la conversación y cancelación](https://developers.openai.com/api/reference/resources/realtime/client-events), [WebSockets Vercel](https://vercel.com/docs/functions/websockets), [waitUntil](https://vercel.com/docs/functions/functions-api-reference/vercel-functions-package#waituntil).
