# Auditoría funcional de NIETO para LABITCONF

**Auditoría vigente, 31/10/2026:** la revisión de `c479076` encontró **nueve hallazgos adicionales abiertos y un riesgo previo al despliegue público**. El detalle, prioridades y evidencia están en [AUDIT-2026-10-31.md](AUDIT-2026-10-31.md). Los once hallazgos anteriores A01–A11 conservan sus correcciones en [AUDIT_CLOSURE.md](AUDIT_CLOSURE.md). El texto siguiente conserva el diagnóstico original; no es la lista actual de defectos abiertos. El original sin modificaciones también está en [AUDIT-2026-10-30.md](AUDIT-2026-10-30.md).

Fecha: 30 de octubre de 2026, hora de Argentina. Código auditado: `059e0cc`, rama `main`.

Este informe conserva esa fotografía. La evolución posterior y las correcciones verificadas de cohorte efectiva, invitaciones tardías y actualización institucional están documentadas en [MVP_FUNCTIONAL_SCOPE.md](MVP_FUNCTIONAL_SCOPE.md); no implican que todos los hallazgos estén cerrados.

La implementación es un MVP local con integración real a OpenArg. No está listo para declarar completado el recorrido Android → WhatsApp → familia → institución: faltan validaciones físicas y hay fallos funcionales que las pruebas existentes no cubren. Este informe registra la auditoría; no aplica correcciones ni publica cambios en GitHub.

## Alcance y evidencia

Se revisaron las fuentes Android, API, portales, scripts, pruebas y documentación, contrastándolas con el documento de arquitectura aportado. Las reproducciones adicionales utilizaron estado temporal, clientes simulados y las clases/componentes reales, sin modificar los datos de la aplicación ni enviar mensajes o ubicaciones.

- `npm run check`: TypeScript, 24 pruebas backend/MCP y build web aprobados durante esta auditoría.
- Android: compilación offline aprobada y siete pruebas unitarias del motor y la política de controles ejecutadas nuevamente con `--rerun-tasks` (26 tareas ejecutadas, sin fallos). No hay pruebas instrumentadas del servicio de accesibilidad, voz o integración en un teléfono.
- `npm audit --json`: cero vulnerabilidades conocidas reportadas para las dependencias npm instaladas. Esto no certifica la seguridad de la aplicación ni analiza las dependencias Android.
- `npm run verify:openarg`: handshake, seis herramientas descubiertas y `listar_fuentes` autenticado respondieron correctamente durante esta auditoría. No se volvió a consumir una pregunta de IA ni se revalidó cada indicador territorial.
- Los 63 archivos versionados no contienen los valores configurados de `OPENARG_API_KEY` o `INSTITUTION_TOKEN`. Las credenciales no se imprimieron.
- No hay un teléfono autorizado conectado por ADB. La compilación y los tests de lógica no verifican los controles reales de WhatsApp.

Los probes locales web se pueden repetir con `node .cache/audit-web/family-race.mjs` y `node .cache/audit-web/overview-stale.mjs`. El probe JVM del motor está en `.cache/audit-android/FlowAudit.java`. Son artefactos locales ignorados, no pruebas de regresión incorporadas al repositorio.

## Hallazgos del diagnóstico original

P1 significa corregir antes de confiar en el resultado o usar datos reales. P2 significa un fallo de funcionamiento o entrega que debe resolverse antes de cerrar la experiencia correspondiente.

### A01 P1 La verificación de envío puede acreditar un mensaje antiguo

**Referencia:** `apps/android/app/src/main/java/org/nieto/assistant/flow/GuidanceEngine.kt:69` y `:135`; el contrato `SafeScreen` de la línea 9 no contiene identidad de conversación ni de mensaje.

El motor guarda cuántas tarjetas de ubicación salientes hay visibles al iniciar. Después de confirmar, declara éxito si ese número aumenta. Cambiar a otra conversación con más tarjetas antiguas, o mostrar más historial, es indistinguible de un envío nuevo. Un probe contra la clase real produjo `completed=true` con ese cambio de pantalla. También puede atribuir autonomía a este resultado.

**Falta:** vincular el recorrido a la conversación correcta mediante evidencia mínima procesada localmente, distinguir mensajes nuevos de historial y reiniciar la confirmación si cambia el contexto. No hace falta enviar conversaciones al servidor para resolverlo.

### A02 P1 La cohorte institucional cuenta instalaciones vacías

**Referencia:** `apps/api/src/server.mjs:133` y `:186`.

El mínimo de cinco se calcula con todos los registros de dispositivo del territorio, aunque no tengan sesiones. El alta de dispositivos no requiere autenticación previa. Reproducción: una instalación con actividad estaba protegida; registrar cuatro instalaciones vacías hizo que se publicaran los valores exactos de su única tarea, práctica y duración. Incluso cinco instalaciones legítimas no garantizan cinco aportantes a cada métrica.

**Falta:** definir participantes elegibles, período y contribuyentes efectivos por métrica; aplicar la supresión a estos grupos y controlar altas artificiales. La autenticación institucional sigue siendo necesaria para leer las métricas, pero su titular puede crear los registros que habilitan el agregado.

### A03 P2 Una invitación tardía puede mostrarse dentro de otra familia

**Referencia:** `apps/web/src/Family.tsx:45`; cambio de token en `:20` y cierre de acceso en `:67`.

Se reprodujo con el componente real y respuestas HTTP controladas: generar invitación para A, cerrar el acceso, restaurar B y recibir finalmente la respuesta de A. El portal muestra el código de A bajo el título de B. La API no cambia el dueño del código: usarlo vincularía el teléfono a A.

**Falta:** cancelar solicitudes al cambiar de identidad, limpiar invitaciones y aplicar respuestas únicamente si pertenecen al token/grupo vigente.

### A04 P2 Me perdí no recupera una guía después de retroceder

**Referencia:** `apps/android/app/src/main/java/org/nieto/assistant/flow/GuidanceEngine.kt:81` y `:118`.

Se reprodujo `ATTACH → LOCATION → volver al chat`. El motor permanece en `LOCATION`, busca el botón Ubicación en una pantalla que ya no lo muestra y deja `target=null`. Pulsar Me perdí y volver al chat no retrocede ni reconstruye el paso; exige cancelar y empezar de nuevo.

**Falta:** recuperación por pantalla reconocida, transiciones de retroceso y una acción clara para retomar desde un punto conocido, conservando las confirmaciones sensibles.

### A05 P2 Los eventos de progreso que fallan por red se descartan

**Referencia:** `apps/android/app/src/main/java/org/nieto/assistant/NietoApp.kt:44` y `:64`.

La cola existe solo en memoria. Cada evento se consume una vez; si la solicitud falla, el bloque catch informa el fallo pero no lo vuelve a encolar ni lo persiste. El contador pendiente no implica que haya algo recuperable. Si se pierde el evento final, familia e institución pueden quedarse sin la tarea o la autonomía alcanzadas. Cerrar el proceso borra el estado pendiente.

**Falta:** cola durable, reintento con espera, identidad original conservada, identificadores idempotentes y recuperación tras reiniciar. Esta pérdida se comprueba en el código; no se provocó un corte de red sobre un teléfono durante la auditoría.

### A06 P2 El backend acepta resultados sin validar el recorrido

**Referencia:** `apps/api/src/server.mjs:227`; conteo de iniciadas en `:196`.

En un servidor aislado, una sesión recién creada aceptó `flow_completed` con cero pasos y sin inicio; luego aceptó `autonomy_completed` sin un intento de práctica. Un evento posterior pudo ampliar la duración ya completada de 30 a 14.400 segundos. Además, el campo iniciadas cuenta sesiones creadas y no eventos de inicio.

**Falta:** validar transiciones y pasos del flujo, separar intentos de práctica, congelar la duración acompañada al completar y distinguir creación, inicio y finalización. Validar el protocolo no sustituye mejorar la evidencia local del envío descrita en A01.

### A07 P2 Los avisos de bloqueo de pantalla no se leen automáticamente

**Referencia:** `apps/android/app/src/main/java/org/nieto/assistant/NietoApp.kt:112` y `flow/GuidanceEngine.kt:56`.

La lectura automática solo ocurre si cambia `instruction`. Una pantalla ajena, sensible o incompatible cambia `notice` y conserva la instrucción anterior; por eso no se anuncia el motivo del bloqueo. El probe del motor confirma esa conservación. La persona que depende de la voz puede seguir oyendo como última referencia una acción que ya no corresponde, aunque la UI visual sí muestre el aviso.

**Falta:** anunciar cambios relevantes de estado y bloqueo, evitar repeticiones constantes y comprobar disponibilidad del idioma y errores de TTS en un teléfono.

### A08 P2 El portal institucional no actualiza las métricas al entrar actividad

**Referencia:** `apps/web/src/Institution.tsx:27` y exportación en `:66`.

El resumen se pide al entrar, cambiar territorio/token o reintentar un error. No hay actualización periódica ni botón de refresco en el estado exitoso. La reproducción con el componente real dejó una tarea completada en el backend y cero en pantalla; la comprobación periódica de salud de la API no vuelve a consultar las métricas. La exportación conserva esa vista vieja.

**Falta:** refresco explícito y/o suscripción o sondeo, fecha visible de la última carga y exportación de datos actualizados.

### A09 P2 La distribución del APK no queda vinculada a la compilación

**Referencia:** `apps/api/src/server.mjs:27`, `scripts/android-build.ps1:27` y `README.md:33`.

La API prioriza la copia de `artifacts/nieto-debug.apk`; el script actualiza solamente el archivo bajo `apps/android/app/build`. Después de una recompilación, puede seguir ofreciéndose la copia anterior. En el estado auditado ambos archivos coinciden: no se afirma que la descarga actual sea distinta.

El enlace del README apunta al APK local excluido de Git. El repositorio público no incluye ese archivo, por lo que no es un enlace de descarga utilizable desde GitHub.

**Falta:** una única salida de distribución, actualización atómica después de compilar y verificar hash, y una release/artefacto descargable con versión. Publicarlo requiere una tarea posterior; esta auditoría no creó releases.

### A10 P2 El chat confunde negaciones y otras tareas con compartir ubicación

**Referencia:** `apps/api/src/server.mjs:216`.

La clasificación es una expresión regular con dos respuestas fijas. Se verificó que No quiero compartir mi ubicación devuelve el flujo de compartir ubicación. Consultas distintas que contengan WhatsApp también terminan en esa guía. Android no pulsa ni envía automáticamente, pero la respuesta contradice la intención expresada.

**Falta:** tratar cancelaciones y negaciones, reconocer la tarea concreta y explicar el alcance sin redirigir a una operación sensible no solicitada. No es obligatorio incorporar un modelo de IA para corregir esta clasificación básica.

### A11 P2 El estado del MCP puede conservar un error después de recuperarse

**Referencia:** `apps/api/src/openarg.mjs:148`.

Se reprodujo una primera llamada discover con fallo de red y una segunda con handshake y herramientas correctos. La segunda no limpia `lastError`, por lo que el portal puede continuar mostrando desconexión hasta realizar otra consulta de datos exitosa.

**Falta:** separar el estado de transporte y el estado de consulta autenticada, y limpiar/actualizar el error que corresponda después de recuperar la conexión.

## Qué falta del producto y qué puede esperar

| Área | Situación y trabajo pendiente |
| --- | --- |
| Primer recorrido real | Instalar y probar en el teléfono de la demo: voz, permisos, árbol real, marca, confirmación, envío y práctica. El perfil de WhatsApp `2.26.*` en español es experimental; no se acreditó compatibilidad física. |
| Uso sin PC | El APK usa una URL configurable de la API local. Falta un backend y portal desplegados con HTTPS y operación estable si la presentación debe funcionar sin la computadora del equipo. |
| Asistente y tutoriales | Solo hay una guía codificada. La consulta de voz/texto sugiere tocar Empezar esta guía; no la inicia por sí misma (`NietoApp.kt:144`). Falta el resto de tutoriales del documento y el orquestador de conversación, pantalla, seguridad, familia y fuera de alcance. Las frases del chat hoy no son respuestas de IA general. OCR y visión remota pueden esperar si el primer flujo queda resuelto con accesibilidad. |
| Historial personal | Existe el endpoint de historial, pero Android no lo presenta como un historial o resumen personal. Tampoco restaura la sesión después de cerrar el proceso. |
| Familia | Existe vinculación, permisos y alerta en el portal. Faltan notificaciones fuera de la pestaña, recuperación de acceso, credenciales independientes por cuidador, cambio directo de permisos y versión de política de consentimiento. |
| OpenArg | La integración autenticada existe. Los dos indicadores numéricos curados son de CABA y Censo 2010. Faltan fuentes recientes y más indicadores de mayores, conectividad y acceso a servicios, además de cobertura territorial verificada. Las otras provincias tienen búsqueda de catálogo, no esos indicadores. |
| Instituciones | Faltan datos elegibles y metodología de autonomía, períodos y denominadores, detalle de bloqueos/abandono, programas y evaluación antes/después. La selección de provincia en el portal no cambia que Android registra CABA. El mapa actual orienta el territorio y no identifica necesidades por barrio ni mide impacto. |
| Uso y seguridad | No existe medición del uso total del teléfono por aplicaciones/categorías, detección de enlaces sospechosos ni alertas de seguridad automáticas. Solo hay duración de sesiones guiadas y solicitud manual de ayuda. |
| Producción | Faltan identidad y recuperación, roles y aislamiento entre organizaciones, tokens con expiración/rotación, base de datos multiinstancia, backups y restauración, retención y exportación/borrado de cuenta, monitoreo y contratos OpenAPI con tipos compartidos. El JSON y los tokens actuales son de prototipo local. |
| Futuro explícito | Pagos/suscripción, iOS, palabra de activación y modelos predictivos no hacen falta para demostrar el primer recorrido de la hackathon. No deben anunciarse como disponibles. |

## Orden de cierre recomendado

1. Corregir A01 y A04, validar el recorrido en el teléfono concreto y añadir pruebas con contextos reales anonimizados localmente.
2. Corregir A03 y A02 antes de usar grupos familiares o agregados institucionales con datos reales.
3. Resolver A05, A06 y A07; probar una pérdida de conexión y recuperación sin duplicar ni perder resultados.
4. Resolver A08 y A09; probar el circuito completo APK → API → familia → institución con la versión distribuida.
5. Resolver A10 y A11 y completar las pruebas de errores y reconexión.
6. Preparar despliegue y distribución si la demo requiere independencia de la computadora, y elegir datos OpenArg recientes que respalden el caso institucional.

El cierre requiere una demostración real de extremo a extremo y nuevas pruebas de regresión para los fallos encontrados. Las 31 pruebas actuales cubren partes de la lógica; no equivalen a una aplicación funcionalmente validada en un teléfono.
