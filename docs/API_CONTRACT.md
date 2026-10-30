# NIETO 2.0 — contrato del MVP

Los endpoints adicionales del portal TINO (analytics, territorios, programas, intervalos de uso y eventos de seguridad) están documentados en [TINO-PORTAL.md](TINO-PORTAL.md#rutas-nuevas). Los contratos siguientes siguen siendo compatibles.

Todas las rutas usan JSON y el prefijo `/api`. Los errores tienen `{error:{code,message}}`. Las credenciales se envían como `Authorization: Bearer <token>`. Ninguna captura, árbol accesible ni conversación se guarda en el servidor. Fechas ISO 8601 UTC. Territorio inicial: `AR-C` (Ciudad Autónoma de Buenos Aires).

## Estado y configuración

- `GET /health`: `{status,version,openarg:{configured,connected?,endpoint},privacy:{minimumCohort},...}`. No expone secretos.
- `POST /devices` con `{name,territoryId}` → `{deviceId,token}`. Identidad seudónima de instalación.
- `PATCH /devices/me` con `{name?,territoryId?}` requiere token de dispositivo.

## Asistente y telemetría

- `POST /assistant/sessions` con `{flowId:"whatsapp-location"}` → `{id,flowId,createdAt}`.
- `POST /assistant/message` con `{sessionId?,text}` → `{mode,instruction,flowId?,sources?}`. El MVP usa guías verificadas y búsqueda pública; no se presenta como conversación general si no hay un modelo configurado.
- `POST /assistant/sessions/:id/events` con `{type,stepId?,durationSeconds?}` → `{ok:true}`. Tipos: `flow_started`, `step_shown`, `step_completed`, `step_repeated`, `user_lost`, `flow_completed`, `autonomy_completed`, `cancelled`. Solo eventos cerrados; no texto libre ni árbol de pantalla. `durationSeconds` se limita a un intervalo razonable. El servidor verifica pertenencia de sesión.
- `GET /assistant/history` → `{sessions:[{id,flowId,state,createdAt,completedAt?,durationSeconds,completedSteps,autonomyCompleted}]}`. Solo historial propio.

El motor Android identifica y verifica cada paso en el dispositivo. Nunca ejecuta clics ni gestos. Compartir ubicación requiere confirmación local explícita. Si no reconoce la versión o la pantalla, pide ayuda sin inventar coordenadas.

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

- `GET /institution/overview?territoryId=AR-C` → `{territoryId,minimumCohort,suppressed,participants,metrics:{completedFlows,autonomyChecks,accompaniedMinutes,helpRequests}|null,flows:[{flowId,started,completed}],updatedAt}`. Aplicar mínimo de cohorte antes de devolver cualquier desglose NIETO.
- `GET /openarg/status` → `{configured,connected,endpoint,tools:[{name,description}],error?}`. Handshake y descubrimiento MCP reales; sin clave, estado pendiente.
- `GET /openarg/search?q=...&territoryId=AR-C` → `{query,datasets:[{id,title,description,sourceUrl,organization?,updatedAt?,period?,resources:[{id,name,format,url}]}],sources:[{title,url}],warnings:[],cached,fetchedAt}`.
- `POST /openarg/ask` con `{question,territoryId}` → `{answer,sources:[{title,url}],warnings:[],fetchedAt,cached}`. La respuesta conserva atribución y declara cuando no hay período o indicador verificable. Usa herramienta MCP real descubierta, sin endpoints o nombres inventados.
- `GET /openarg/territory?territoryId=AR-C&q=...` con `q` opcional de hasta 300 caracteres → `{territoryId,query,indicators:[{id,label,value,unit,period,sourceUrl,sourceTitle,freshness?,method?}],datasets,sources,warnings,cached,fetchedAt}`. CABA tiene dos indicadores históricos del Censo 2010 (población de 65 años y más y total), obtenidos mediante agregados MCP después de verificar estructura y período. Cada indicador declara su vigencia histórica; no describe población actual ni impacto actual de NIETO. Otras jurisdicciones devuelven indicadores vacíos con advertencia hasta verificar una fuente específica. Nunca derivar un número de una descripción o inventarlo. La búsqueda usa el territorio como contexto y puede devolver fuentes de otras jurisdicciones; su cobertura debe verificarse en origen.

## Ejecución local

API `http://localhost:8787`; portal `http://localhost:5173`, proxy `/api` a la API. Android emulador `http://10.0.2.2:8787`; teléfono por LAN o `adb reverse tcp:8787 tcp:8787` y `http://127.0.0.1:8787`. La URL es editable en Android. HTTP solo para depuración. Cada cliente mantiene su token propio. Las pruebas usan estado temporal, nunca datos reales.
