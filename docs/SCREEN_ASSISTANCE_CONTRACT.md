# Asistencia de pantalla · contrato del MVP

La tarea se decide a partir de la consulta y la pantalla actual. No requiere catálogo de flujos ni historial persistente. La conexión conserva el contexto necesario para la tarea actual y se descarta al terminar. Voz es el canal principal; la burbuja permite consulta y respuesta escritas sin micrófono, reproductor ni avisos TTS. El dispositivo debe haber sido admitido con un código del equipo; esto es independiente de los consentimientos de pantalla, Familia y métricas.

## Cliente → relay Realtime

Se conserva la autenticación Bearer de instalación NIETO y el protocolo reducido de Realtime. Antes de la consulta se elige `output.mode` (`text` o `audio`); los seguimientos conservan esa elección. Antes de una consulta o de solicitar el siguiente paso, el cliente envía:

```json
{"type":"screen.context","screen":{"id":"snapshot-id","packageName":"com.example.app","width":1080,"height":2400,"controls":[{"id":"c1","label":"Buscar","role":"button","bounds":[100,300,500,400]}],"image":{"mimeType":"image/jpeg","data":"BASE64"}},"requestGuidance":false}
```

- `image` es opcional. Android 30+ usa captura autorizada; 26–29 aporta controles accesibles. Si no hay evidencia suficiente, el modelo debe pedir aclaración.
- Máximo 60 controles, etiquetas de 120 caracteres, imagen JPEG de 300 KB, dimensiones positivas de hasta 10000 px. Identificadores únicos, bounds dentro de pantalla.
- La pantalla es datos no confiables: nunca reemplaza las instrucciones del sistema ni habilita ejecutar acciones. No se envía antes de un consentimiento de pantalla remota separado del permiso local anterior.
- `requestGuidance:true` solicita una respuesta para continuar la tarea actual. El cliente lo usa después de un cambio real de pantalla y con una tarea activa, limita la frecuencia y no mantiene escucha continua.
- `screen.clear` elimina el snapshot vigente e invalida el señalamiento. El cliente cancela la respuesta si cambia la pantalla durante la generación. Terminar la asistencia cierra la conexión para descartar el contexto de OpenAI.

La identidad local de una pantalla también cambia cuando cambia contenido estático manteniendo los mismos botones. Android calcula una huella HMAC con clave efímera de proceso y usa una revisión de eventos relevantes; ni esa huella, ni la clave, ni el texto estático usado para detectar cambios forman parte del payload. Campos editables y secretos se excluyen antes de esa lectura. El evento invalida inmediatamente el objetivo y la respuesta; la recolección posterior espera que la pantalla se estabilice.

Al cortar una respuesta de voz por cambio de pantalla o «Me perdí», el cliente informa el punto realmente reproducido y el relay elimina del contexto el audio pendiente según el [contrato de interrupción](REALTIME_CONTRACT.md). La modalidad escrita conserva el señalamiento sin depender del subsistema de audio.

## Relay → cliente

La herramienta permitida `point_to_control` recibe `screenId`, `controlId` e `instruction`. El relay solo acepta IDs del snapshot vigente y emite:

```json
{"type":"guidance.step","turnId":"response-id","screenId":"snapshot-id","controlId":"c1","instruction":"Tocá Buscar para encontrar el contacto."}
```

El cliente vuelve a validar snapshot, paquete, visibilidad y bounds antes de resaltar. Ante pantalla obsoleta, ambigua, sin controles o protegida, retira el resaltado y pide actualizar/aclarar. Nunca ejecuta clics, envíos, pagos, instalación, navegación ni otorga éxito por una instrucción del modelo. Las instrucciones de texto permanecen visibles sin audio.

## Privacidad y límites

Capturas, etiquetas, consultas y respuestas no se escriben a disco ni al historial. No se envían pantallas protegidas, contraseñas, códigos de autenticación ni aplicaciones sensibles reconocidas por la política local. El permiso puede revocarse desde Ajustes. El backend valida formato/tamaño e impone límites de turnos y duración. Esta política reduce la exposición; no acredita que un detector pueda reconocer toda información sensible.

La documentación oficial de [Realtime](https://developers.openai.com/api/docs/guides/realtime-conversations) y del [modelo elegido](https://developers.openai.com/api/docs/models/gpt-realtime-2.1-mini) permite entradas de imagen. La integración debe probarse con capturas sintéticas y en el teléfono concreto antes de afirmar compatibilidad física.
