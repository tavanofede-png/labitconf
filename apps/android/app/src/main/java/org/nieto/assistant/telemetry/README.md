# Progreso durable · A05/A06

El motor produce sólo tipos y pasos cerrados. `TelemetrySession` fija al comenzar el `clientSessionId`, servidor y credencial originales; cada evento recibe un `eventId` una sola vez. La práctica reutiliza la sesión guiada, pero lleva un UUID `attemptId` nuevo y su propia duración. Sólo se envían reportes de la persona (`flow_reported`/`practice_reported`), nunca resultados verificados ni autonomía. La duración acompañada se captura al reportar/cancelar y no se amplía con la práctica.

La cola vive en `noBackupFilesDir/telemetry/outbox.bin`, cifrada con AES-GCM y una clave propia de Android Keystore. El reemplazo temporal también está cifrado, se sincroniza a disco y se mueve atómicamente. No contiene capturas, árbol, etiquetas, mensajes, consultas ni respuestas. Máximo 1000 eventos/8 MB; un fallo de almacenamiento o capacidad se informa y no aumenta falsamente el contador. Una cola ilegible se conserva y se indica como no contable, nunca se reemplaza por una vacía.

WorkManager requiere red, intenta enviar al encolar y conserva un trabajo de recuperación cada 15 minutos (Android puede retrasarlo). La cola vacía o totalmente pausada no hace solicitudes HTTP. Cada pasada dura hasta 90 segundos y 20 eventos. Los eventos mantienen FIFO dentro de cada sesión, sin bloquear otras identidades. El servidor crea/reconoce la sesión mediante `clientSessionId`; acepta reenvíos de `eventId` ya procesados. Sólo un acuse `{ok:true}` elimina un evento local.

Los fallos de red/408/429/5xx usan espera exponencial desde 30 segundos, hasta cinco intentos por evento. 401/403 y errores definitivos de protocolo conservan los eventos en pausa. El contador muestra todos los registros aún guardados y cuántos quedan bloqueados detrás de una sesión pausada. El botón **Reintentar con los accesos originales** reinicia esos intentos sin cambiar IDs, servidor ni credenciales. Cambiar el servidor del teléfono nunca migra progreso pendiente a otra persona. Cerrar o reiniciar el proceso no borra los pendientes.

Las pruebas cubren reinicio desde archivo cifrado, red caída, espera persistida, respuesta perdida después de aceptación, duplicación, cambio de identidad, 401/403, límites de intentos y corrupción sin borrado. `GuideProtocolFixtureTest` exporta eventos sintéticos del motor real en `app/build/reports/guide-telemetry-contract.tsv` para comprobar el grafo de la API. No es una prueba física de red/Android Keystore en un teléfono.

# Lectura de avisos · A07

La lectura prioriza `notice` sobre la instrucción, anuncia cambios de bloqueo/estado y vuelve a leer el paso al despejar un bloqueo. Eventos idénticos no repiten el aviso; Repetir/Me perdí son solicitudes explícitas. El inicio comprueba motor e idioma español; datos ausentes, idioma no compatible y errores al sintetizar se mantienen visibles. La primera instrucción se conserva mientras se inicia TTS. Los reportes manuales se anuncian como reportes, sin asegurar una entrega verificada.

Falta comprobar en el dispositivo concreto la voz/idioma instalados, permisos, proceso interrumpido, red real y funcionamiento de WorkManager bajo restricciones del fabricante.
