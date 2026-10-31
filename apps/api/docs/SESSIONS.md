# Protocolo de sesiones y entrega idempotente

La API valida el orden del recorrido y recibe reportes operacionales. No hay un verificador semántico de mensajes de WhatsApp habilitado. Ningún resultado acredita un envío, aprendizaje o autonomía.

## Creación y reintentos

`POST /api/assistant/sessions` requiere el token original del dispositivo y `{flowId,clientSessionId}`. Los flujos admitidos son `whatsapp-location` y `screen-assistance`; `clientSessionId` es un UUID generado una sola vez y conservado por la cola del cliente. Repetir la misma creación devuelve HTTP 201 y el mismo `{id,flowId,createdAt}`. Cambiar el flujo conservando ese identificador devuelve 409 `idempotency_conflict`.

`POST /api/assistant/sessions/:id/events` acepta únicamente `{eventId,type,stepId?,durationSeconds?,attemptId?}`. `eventId` es un UUID estable para ese evento y su payload original. Los eventos de práctica llevan un UUID `attemptId` propio del intento. Se conserva el origen (servidor e identidad), sin reenviar una cola a otra instalación.

Un evento válido devuelve `{ok:true,duplicate:false}`. Repetir el mismo ID, sesión y payload devuelve `{ok:true,duplicate:true}`, incluso después del cierre. No aumenta contadores ni cambia duración. Reutilizar el ID con otro payload o sesión devuelve 409 `idempotency_conflict`. La deduplicación persiste junto con el cambio de estado en la misma escritura serializada del JSON y sobrevive al reinicio. Solo se almacenan hashes de identificadores de idempotencia y payloads cerrados; no se guarda el cuerpo del pedido ni texto libre.

IDs ausentes o inválidos devuelven 400. La cola debe conservar los errores permanentes para revisión, sin regenerar IDs ni sustituir identidad. La garantía cubre una aplicación de cada evento aceptado mientras se conserva el archivo de datos; no es una garantía de entrega de red o de persistencia ante pérdida del disco. El JSON continúa siendo de una sola instancia.

## Recorrido guiado y reporte

Estados: `created → active → reported | cancelled`. Crear una sesión no cuenta como inicio. `flow_started` la activa. El recorrido usa estos pasos canónicos:

`open_chat → attach → location → confirm → share → verify`

Cada paso debe recibir `step_shown` y después `step_completed` antes de mostrar el siguiente. Mostrar un paso anterior permite recuperarse y elimina las validaciones de ese paso y los posteriores, incluida la confirmación de privacidad. No se puede saltar directamente a verificar o reportar.

Después de completar `verify`, la declaración explícita de la persona se registra con `flow_reported`. Se guarda `state:"reported"`, `resultSource:"self_reported"` y `reportedAt`. La duración acompañada queda congelada al reportar o cancelar. `durationSeconds` solo se admite en los eventos terminales, como entero entre 0 y 14.400. Los cambios posteriores no pueden ampliar esa duración.

`flow_completed`, `practice_completed` y `autonomy_completed` devuelven 409 `verification_unavailable`: un indicador enviado por el cliente, firma en el APK o respuesta del modelo no sustituye evidencia semántica confiable.

## Práctica separada

Tras un recorrido reportado, `practice_started` requiere un `attemptId` nuevo. Cada intento tiene estado, pasos, duración y origen del reporte propios. Solo puede existir un intento activo; se debe terminar o cancelar antes de comenzar otro. Todos sus eventos incluyen ese mismo `attemptId`.

La práctica recorre los seis pasos y termina con `practice_reported`, nunca acredita autonomía. `step_repeated` o `user_lost` marca `assisted:true` en el servidor. Cancelar con `attemptId` cierra únicamente ese intento; no cancela el recorrido guiado ya reportado ni modifica su duración.

Por sesión se admiten hasta 5.000 eventos y cien prácticas. Repetir un evento ya aceptado sigue respondiendo como duplicado aunque se haya alcanzado el límite.

`screen-assistance` admite inicio, pasos mostrados, repeticiones, dificultades y cancelación con el paso fijo `screen-step`. No admite completar pasos, reportar tareas ni iniciar prácticas: el modelo solo acompaña y señala controles.

## Lectura de datos heredados

El historial incluye `practiceAttempts` y devuelve siempre `autonomyCompleted:false`. Los registros antiguos con `state:"completed"` se presentan como `state:"reported"` y `resultSource:"legacy_unverified"`, sin modificar el registro original. Una antigua marca de autonomía es únicamente un reporte de práctica heredado. Familia e institución usan `reportedFlows` y `practiceReports`; los campos antiguos de finalización verificada o autonomía no se usan para acreditar resultados.

Las fechas corresponden a la recepción en la API. Una entrega diferida puede desplazar la actividad hacia la ventana de recepción; no se reconstruye la hora exacta de la acción en el teléfono.
