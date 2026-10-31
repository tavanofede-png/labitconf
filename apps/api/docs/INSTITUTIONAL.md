# Métricas institucionales del MVP

`GET /api/institution/overview?territoryId=AR-C` conserva token institucional privado y compatibilidad con `participants`, `metrics` y `flows`. La ventana fija es de 30 días hasta la consulta. No hay filtros arbitrarios por persona, familia, edad o paso que permitan construir cohortes complementarias.

## Consentimiento y supresión

Cada instalación tiene `institutionMetricsConsent:false` por defecto. Es un consentimiento separado de compartir progreso o pedir ayuda a familiares. `GET /api/devices/me` lo devuelve; `PATCH /api/devices/me` acepta el booleano explícito. Revocarlo excluye también las contribuciones anteriores de esa instalación de los agregados posteriores. La configuración no borra los registros operacionales personales del prototipo.

La cohorte cuenta contribuyentes efectivos y consentidos del territorio: sesiones con al menos un evento o ayudas solicitadas en la ventana. Crear una instalación o una sesión vacía no aporta a la cohorte. Cada valor tiene además su propia cohorte: cinco personas activas no habilitan la tarea completada, ayuda o dificultad aportada por una sola persona. Cada paso, flujo y ventana de tendencia aplica el mismo mínimo. Se devuelve `null` cuando una métrica está suprimida; nunca se reemplaza por cero. Los pasos y flujos sin un agregado publicable se omiten.

El mínimo efectivo es cinco aunque la configuración solicite uno menor. `contributors` solo se publica cuando alcanza el mínimo. La autenticación del prototipo identifica instalaciones, no verifica personas únicas ni impide identidades artificiales; esa limitación sigue explícita en `gaps`.

## Contrato adicional

- `period:{from,to,days:30,basis:"session_created_and_help_requested",timezone:"UTC"}`: selecciona sesiones creadas y ayudas solicitadas en el período. No se reconstruye un historial fechado de pasos antiguos.
- `activity:{suppressed,suppressionReason,contributors,minimumCohort,activePeople,startedFlows,startedCohort}`: iniciadas exige `flow_started`; crear sesión no cuenta inicio.
- `metrics:{reportedFlows,practiceReports,completedFlows,autonomyChecks,accompaniedMinutes,helpRequests}`: cada campo admite `null`; el bloque es `null` si falta cohorte general. Los dos primeros son reportes explícitos, con cohortes propias. `completedFlows` y `autonomyChecks` permanecen `null`, con motivo `verification_unavailable`: no existe un verificador semántico habilitado.
- `difficulties:{suppressed,suppressionReason,contributors,minimumCohort,events,steps}`: suma `user_lost` y `step_repeated`. Los pasos publicados contienen `{stepId,events,...cohort}` y no texto de pantalla.
- `familyResponse:{suppressed,suppressionReason,contributors,minimumCohort,resolvedRequests,meanResponseMinutes}`: resoluciones y media entre solicitud y resolución; solo solicitantes consentidos y alertas no revocadas.
- `flows:[{flowId,started,reported,completed,startedCohort,reportedCohort,completedCohort}]`: cohortes independientes; `completed` permanece `null`. `screen-assistance` registra acompañamiento y dificultades, sin aceptar reportes de tareas, finalización o autonomía.
- `trend:[{from,to,suppressed,suppressionReason,activePeople}]`: seis ventanas consecutivas de cinco días; cada ventana requiere cinco aportantes propios.
- `cohorts:Record<string,{suppressed,suppressionReason,contributors,minimumCohort}>`, `evidence`, `gaps` y `updatedAt` explican fuente, método, límites y fecha de consulta.

Son registros operacionales reportados por el cliente. No prueban que una acción ocurrió en otra app, que hubo aprendizaje, autonomía, bienestar o impacto causal. No se mide el uso total del teléfono. Cambios en personas consentidas pueden cambiar un agregado aun con igual ventana; solo comparar igual territorio, período y versión de método.

El archivo JSON almacena eventos cerrados y contadores, nunca consultas, capturas, etiquetas, paquetes de apps o respuestas del asistente. La telemetría de pantalla usa exclusivamente `flowId:"screen-assistance"` y `stepId:"screen-step"`.

El [protocolo de sesiones](SESSIONS.md) valida pasos e intentos de práctica separados, deduplica entregas con IDs estables y congela la duración guiada al reportar. Los registros heredados de finalización o autonomía solo cuentan como reportes no verificados. Las fechas son de recepción; una cola sin conexión puede desplazar la actividad a la ventana de recepción. No se anuncia ejecución exacta en tiempo real.
