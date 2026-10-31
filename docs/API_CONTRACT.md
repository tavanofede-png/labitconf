# NIETO 2.0 — contrato del MVP

Los endpoints adicionales del portal TINO (analytics, territorios, programas, intervalos de uso y eventos de seguridad) están documentados en [TINO-PORTAL.md](TINO-PORTAL.md#rutas-nuevas). Los contratos siguientes siguen siendo compatibles.

Todas las rutas usan JSON y el prefijo `/api`. Los errores tienen `{error:{code,message}}`. Las credenciales se envían como `Authorization: Bearer <token>`. Ninguna captura, árbol accesible ni conversación se guarda en el servidor. Fechas ISO 8601 UTC. Territorio inicial: `AR-C` (Ciudad Autónoma de Buenos Aires).

## Estado y configuración

- `GET /health`: `{status,version,openarg:{configured,connected?,endpoint},privacy:{minimumCohort},...}`. No expone secretos.
- `POST /devices` con `{name,territoryId}` → `{deviceId,token}`. Identidad seudónima de instalación.
- `GET /devices/me` devuelve el dispositivo, su jurisdicción declarada y `institutionMetricsConsent` vigente.
- `PATCH /devices/me` con `{name?,territoryId?,institutionMetricsConsent?}` requiere token de dispositivo. El consentimiento institucional es independiente y está desactivado por defecto.

## Asistente y telemetría

- `POST /assistant/sessions` con `{flowId:"whatsapp-location"|"screen-assistance",clientSessionId:UUID}` → `{id,flowId,createdAt}`. Repetir el mismo identificador devuelve la misma sesión del titular. La asistencia de pantalla registra actividad sin contenido privado.
- `POST /assistant/message` con `{sessionId?,text}` → `{mode:"conversation",instruction,sources:[],model}` sin `flowId`. Usa `gpt-realtime-2.1-mini`; no clasifica por palabras ni propone una guía local. Falta de clave, cuota o acceso devuelve un error explícito. Cada solicitud HTTP recibe solo su texto actual; no tiene memoria entre solicitudes.
- `WS /assistant/realtime` autentica con token de dispositivo NIETO y transmite audio PCM24k y texto usando el mismo modelo. La clave OpenAI solo existe en el backend. El [contrato Realtime](REALTIME_CONTRACT.md) define los mensajes, límites, cancelación y sugerencias de guía. Las conversaciones de voz viven en memoria de la conexión y se eliminan al cerrarla.
- Su extensión `screen.context`/`screen.clear` admite controles y una imagen JPEG efímera con consentimiento remoto. `guidance.step` refiere IDs validados del snapshot actual. Ver [contrato de pantalla](SCREEN_ASSISTANCE_CONTRACT.md); la herramienta solo propone un control y nunca lo pulsa.
- `POST /assistant/sessions/:id/events` con `{eventId:UUID,type,stepId?,durationSeconds?,attemptId?:UUID}` → `{ok:true,duplicate:boolean}`. El servidor exige inicio y pasos en orden, separa prácticas, congela duración al cierre y deduplica de forma durable. Terminales admitidos: `flow_reported`, `practice_reported` y `cancelled`. `flow_completed`, `practice_completed` y `autonomy_completed` devuelven `409 verification_unavailable`. Solo eventos cerrados, nunca texto libre ni pantalla. Ver [protocolo de sesiones](../apps/api/docs/SESSIONS.md).
- `GET /assistant/history` devuelve únicamente sesiones operativas propias, con reportes, `resultSource` e intentos de práctica separados; no contiene consultas ni historial conversacional. Registros anteriores se proyectan como `legacy_unverified` y `autonomyCompleted:false`.

Android valida que el paso propuesto siga correspondiendo a los controles actuales antes de resaltarlo. Nunca ejecuta clics ni gestos. El motor de la guía local permanece separado y conserva sus limitaciones de verificación. Si falta evidencia de pantalla, la asistencia pide aclaración sin inventar coordenadas.

## Familia

- `POST /family/groups` con `{name}` → `{group:{id,name},caregiverToken,invitationCode,expiresAt}`. Código de un solo uso con expiración.
- `POST /family/link` con `{invitationCode,permissions:{progress:boolean,help:boolean}}` y token de dispositivo → `{group:{id,name},permissions}`. La aceptación ocurre en Android por la persona asistida.
- `GET /family/me` con token de dispositivo → `{group:null|{id,name},permissions}`.
- `DELETE /family/link` con token de dispositivo revoca acceso y devuelve `{ok:true}`.
- `POST /family/help` con token de dispositivo → `{ok:true,alertId}`. Solo si la persona consintió `help`. Texto fijo de solicitud, sin conversaciones.
- `GET /family/summary` con token de familiar → `{group:{id,name},members:[{id,name,permissions,completedFlows?,autonomyChecks?,accompaniedSeconds?}],alerts:[{id,type,message,createdAt,resolvedAt?,requesterName?,memberId?}]}`. Las métricas de progreso se omiten sin consentimiento. Las alertas identifican a quien pidió ayuda solo mientras siga vinculado y consienta `help`; se ocultan al revocar. Las rutas institucionales nunca incluyen esta identidad.
- `POST /family/alerts/:id/resolve` con token de familiar → `{ok:true}`.
- `POST /family/invitations` con token de familiar → `{invitationCode,expiresAt}`.

## Instituciones y OpenArg

Las rutas institucionales y OpenArg requieren `INSTITUTION_TOKEN` configurado en el servidor. No se guarda este token en el bundle web.

- `GET /institution/overview?territoryId=AR-C` devuelve ventana de 30 días, métricas y desgloses con cohortes propias. Los resultados disponibles son `reportedFlows` y `practiceReports`, no verificación de tareas o autonomía. `completedFlows`/`autonomyChecks` quedan nulos. Los valores insuficientes permanecen suprimidos. Ver [metodología](../apps/api/docs/INSTITUTIONAL.md).
- `GET /openarg/status` → `{configured,connected,endpoint,tools:[{name,description}],error?}`. Handshake y descubrimiento MCP reales; sin clave, estado pendiente.
- `GET /openarg/search?q=...&territoryId=AR-C` → `{query,datasets:[{id,title,description,sourceUrl,organization?,updatedAt?,period?,resources:[{id,name,format,url}]}],sources:[{title,url}],warnings:[],cached,fetchedAt}`.
- `POST /openarg/ask` con `{question,territoryId}` → `{answer,sources:[{title,url}],warnings:[],fetchedAt,cached}`. La respuesta conserva atribución y declara cuando no hay período o indicador verificable. Usa herramienta MCP real descubierta, sin endpoints o nombres inventados.
- `GET /openarg/territory?territoryId=AR-C&q=...` con `q` opcional de hasta 300 caracteres → `{territoryId,query,indicators:[{id,label,value,unit,period,sourceUrl,sourceTitle,freshness?,method?}],datasets,sources,warnings,cached,fetchedAt}`. CABA tiene dos indicadores históricos del Censo 2010 (población de 65 años y más y total), obtenidos mediante agregados MCP después de verificar estructura y período. Cada indicador declara su vigencia histórica; no describe población actual ni impacto actual de NIETO. Otras jurisdicciones devuelven indicadores vacíos con advertencia hasta verificar una fuente específica. Nunca derivar un número de una descripción o inventarlo. La búsqueda usa el territorio como contexto y puede devolver fuentes de otras jurisdicciones; su cobertura debe verificarse en origen.

## Ejecución local

API `http://localhost:8787`; portal `http://localhost:5173`, proxy `/api` a la API. Android emulador `http://10.0.2.2:8787`; teléfono por LAN o `adb reverse tcp:8787 tcp:8787` y `http://127.0.0.1:8787`. La URL es editable en Android. HTTP solo para depuración. Cada cliente mantiene su token propio. Las pruebas usan estado temporal, nunca datos reales.
