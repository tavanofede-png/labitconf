# Auditoría de funcionalidades de TINO

Revisión del 31 de octubre de 2026, hora de Argentina. Base revisada: `5daf005` en `codex/tino-instituciones`, más el cambio de indicadores de edad de esta entrega. Se contrastó el documento **NIETO Arquitectura Técnica Completa**, incluidos sus cuadros y criterios de aceptación, con Android, API, portales web, pruebas y respuestas reales de los servicios.

**Conclusión:** la arquitectura completa no está implementada. Hay una app Android navegable, un backend funcional para varias operaciones, guías limitadas y un portal demostrativo con datos públicos reales. El panel familiar y los seis meses de métricas institucionales usan datos TINO ficticios. Una pantalla navegable o un HTTP 200 no certifican un flujo entre dos teléfonos.

Se conservan los cambios de producto posteriores al documento: nombre TINO, público inclusivo y los interruptores de burbuja y «Hola TINO». La tablet física quedó fuera de esta auditoría por instrucción de la usuaria.

## Respuestas a las preguntas concretas

| Pregunta | Respuesta comprobada |
| --- | --- |
| ¿Hay mapa de Argentina como en el deck? | Sí: geometría Georef / IGN de 24 jurisdicciones, círculos verdes de uso y rojos de alertas, tamaño según volumen, selección y filtros. Los puntos son centroides provinciales y los valores visibles de la demo son simulados. No palpitan, no reciben eventos en streaming ni representan incidentes individuales en vivo. |
| ¿Hay datos reales de OpenARG cruzados? | Sí, parcialmente. La API respondió con población censada 2022 y accesos a Internet fijo ENACOM 2026 T1, unidos por código provincial ISO con TINO simulado. Calcula usuarios y solicitudes por 10.000 habitantes. No hay seis integraciones públicas completas ni analítica TINO real de seis meses. |
| ¿Funciona todo Grupo Familiar? | No. Hay operaciones reales de API, pantallas, grabación y reproducción de voz, pero faltan piezas del circuito completo. La aceptación y revocación del vínculo están en un componente Android que quedó sin ruta en el menú actual. GPS, captura de uso, historial web, detección de riesgos, push y pagos no están integrados. |
| ¿Hay links ficticios con thumbnails? | Hay registros ficticios de visitas con título, dominio y horario. Cada fila tiene un globo genérico y abre un detalle interno. No hay thumbnails, previews de páginas ni un registro real de navegación del teléfono. |
| ¿Qué se agregó ahora? | Un distintivo con icono de calendario y edad en cada avatar familiar: Marta 67, Ricardo 61, Lucía 32. Son edades ficticias; no se inventan edades para integrantes reales que no tengan ese dato. |

## Evidencia obtenida en esta revisión

- Consulta autenticada y no cacheada a OpenARG con `obtener_datos` sobre `raw.cordoba_estadistica__censo_2022__9e3466dc__v3`: respondió 28 filas y total de población censada de **45.892.285**. La capa de normalización conserva las 24 jurisdicciones y excluye totales/notas del conjunto provincial. La definición también coincide con la publicación oficial de INDEC sobre [resultados definitivos del Censo 2022](https://censo.gob.ar/index.php/en-la-argentina-hay-107-mujeres-por-cada-100-varones/).
- `GET /api/demo/institution/dashboard?months=6&territoryId=AR`: HTTP 200, `origin: tino_simulated`, seis meses, 24 provincias y `publicContext.status: verified_snapshot`. La carga pública servida conserva período 2022 y 2026 T1; la fecha de consulta no se presenta como período del indicador.
- Ejemplo servido para CABA: base censal 3.121.707 personas, 118,81 accesos de Internet fijo por 100 hogares y 1.102 usuarios TINO **simulados**; el cruce redondeado es 3,5 usuarios demo por 10.000 habitantes. Accesos por 100 hogares no equivale a porcentaje de hogares conectados.
- El denominador nacional es población censada total, no población mayor, personas con discapacidad ni estimación de habitantes de 2026. El cruce es descriptivo; no demuestra impacto causal.
- Prueba real del proveedor de IA configurado en esta Mac: **HTTP 401, `invalid_api_key`**, sin respuesta del modelo. El health actual anuncia conversación general porque hay una clave configurada; eso no acredita una conexión válida.
- Configuración local comprobada sin revelar valores: demo de jueces activada, correo real sin proveedor configurado, `AUTH_SECRET` no configurado y allowlist institucional no configurada. Hay credencial institucional compartida para las rutas protegidas.
- Se reejecutaron **55 pruebas API y 13 pruebas unitarias Android**; la APK compiló. No son 68 pruebas entre teléfonos ni validación de envío real en WhatsApp/Gmail.
- Un probe aislado del agregador publicó cinco participantes y cinco sesiones cuando los cinco perfiles eran demo y habían rechazado `preferences.aggregates`. Solo la edad media aplica esa preferencia en el código actual. **Falta excluir demo y aplicar consentimiento a todas las métricas de la vía real.**
- Dos probes JVM adicionales sobre el motor compilado reprodujeron riesgos que la suite existente no cubre: un aumento de tarjetas de ubicación puede dar una finalización sin distinguir conversación/mensaje, y volver atrás desde Ubicación más «Me perdí» no recupera el paso de Adjuntar.
- `origin/main` contiene `46e0795`, con código Realtime de Fede. Esa integración todavía **no está fusionada en esta rama ni en la APK auditada**. No se afirma que la configuración de la computadora de Fede tenga el mismo resultado que la API local auditada.

Resultados públicos y sin credenciales: [respuesta de servicios](evidence/tino-runtime-audit-2026-10-31.json) [probes del motor](evidence/tino-guidance-audit-2026-10-31.json) y [probe de consentimiento](evidence/tino-consent-audit-2026-10-31.json).

## Asistente y Android

| Funcionalidad de la arquitectura | Estado actual | Lo que falta |
| --- | --- | --- |
| Android nativo, marca, navegación, tres perfiles y logo al chat | Implementado; compilado y probado en emulador | QA completa en dispositivos y accesibilidad, recuperación de sesión al abrir en frío. |
| Onboarding por pasos, usuario único, teléfono y nacimiento | UI y validaciones implementadas; datos se guardan | Usar respuestas/preferencias para adaptar realmente tono, ritmo, voz e interfaz. El cuestionario por sí solo no personaliza el asistente. |
| Login por correo y seis casilleros | Demo funcional con `000000`; backend de códigos existe | Proveedor/remitente real, secreto de autenticación estable y prueba de entrega. El flujo actual de jueces no envía correos. |
| Chat con una IA general | Adaptador Responses implementado | La clave de esta API local falla con 401. Revalidar una respuesta real. El indicador de salud debe distinguir configuración de disponibilidad. |
| Voz manual y lectura de respuestas | SpeechRecognizer y TTS nativos implementados | Validar servicio de reconocimiento, idioma, errores y recorrido completo en el dispositivo final. |
| Realtime de Fede | Código presente en `origin/main`, separado | Integrarlo preservando la UI y comprobar una sesión real. No confundir código en otra rama con función instalada. |
| Burbuja flotante de la mascota | Servicio y permiso de superposición implementados | QA con otras apps, bloqueo, revocación, batería y distintos fabricantes. No lee la pantalla por sí misma. |
| «Hola TINO» local | Vosk, servicio foreground, activación y ventana de consulta implementados; hay pruebas de audio anteriores | Prueba sostenida en el hardware final y bajo políticas de batería. No es asistente predeterminado del sistema ni reinicio automático tras matar el proceso. |
| Notificación persistente del asistente | Implementada para servicios de presencia | No sustituye el push de avisos familiares, que falta. |
| Quick Settings, widget y shortcuts especiales | Ausentes | Tile, widget/atajo y recorridos de activación. El icono normal del launcher sí existe. |
| Árbol accesible y highlight | Implementados localmente para controles permitidos de WhatsApp | Generalizar a otras pantallas y versiones con perfiles verificados. No hay comprensión universal de cualquier app. |
| OCR local y visión remota | Ausentes | Captura temporal, redacción local, permisos y análisis. Actualmente no hay pipeline de captura/visión. |
| Ubicación por WhatsApp | Motor con confirmación, highlight, repetición y práctica | Corregir la identificación de un envío nuevo y la recuperación al retroceder; validar contra WhatsApp real `2.26.*` en español. |
| Crear grupo WhatsApp y enviar correo | Guías estructuradas, apertura de apps y borrador de correo | El avance y finalización son confirmados por la persona. No se verifica automáticamente el grupo creado ni que el correo haya salido. No hay prueba E2E actual en las apps externas. |
| Otros tutoriales iniciales | No hay flujos verificados para videollamada, guardar/bloquear contacto, Maps, documentos o ajustes de texto/permisos | Implementar cada procedimiento, compatibilidad y criterios de éxito. Una respuesta textual genérica no equivale a un tutorial verificado. |
| Autonomía y progreso | Eventos y práctica sin pistas para ubicación; API de agregados | Extender práctica verificable a otros flujos y corregir el falso positivo del motor. La demo familiar no acredita aprendizaje real. |
| Offline y sincronización | Guía local y cola de eventos en memoria | Persistencia durable, reintentos idempotentes y recuperación. Los eventos fallidos se consumen sin reencolado. Iniciar/avanzar algunas guías depende de la API. |
| Historial personal | API conserva sesiones y eventos estructurados; chat en memoria | Vista de historial de ayuda integrada y política de retención. No es necesario guardar conversaciones privadas para ofrecer historial de tareas. |
| Ocho modos del orquestador | Hay conversación, detección de tres guías y políticas parciales | Contexto de pantalla multimodal, consultas OpenARG conversacionales, seguridad automática, escalamiento y protocolo de emergencia configurado. |
| iOS | No implementado | Cliente y screen sharing explícito. El documento lo contempla como fase posterior; sigue fuera del producto actual. |

## Grupo familiar

| Funcionalidad | Estado actual | Lo que falta |
| --- | --- | --- |
| Crear grupo e invitar por código | Backend y UI de invitación implementados; código de un uso y 15 minutos | Validar el recorrido actual entre dos clientes reales. La demo de perfiles es independiente de los miembros realmente vinculados. |
| QR de invitación | No implementado | Generación, presentación y lectura. El código textual sí existe. |
| Ingresar por correo del administrador y aprobar incorporación | Pantallas y API implementadas; pruebas de servidor | Correo real y QA nativa del circuito completo. |
| Aceptar o revocar desde el usuario asistido | API y componente `FamilyContent` existentes | **Reconectar su acceso en la navegación actual.** `NietoScreen` conserva la sección 1, pero ningún destino del menú llama a esa sección; «Grupo familiar» abre el rol cuidador. |
| Consentimientos separados | El vínculo real acepta `progress` y `help`; permite revocación total | Tiempo de pantalla, categorías, aplicaciones, seguridad, sitios y ubicación necesitan contratos y controles reales. El diálogo del cuidador muestra permisos; no los concede. Falta versión de política y pausa/edición granular. |
| Foto de perfil | Selector local implementado en onboarding | No hay sincronización de esa foto ni avatar real compartido con la familia. |
| Perfiles y edades | Tres perfiles ficticios, selector, cumpleaños de onboarding; distintivos de edad agregados en esta entrega | No confundir con familiares reales encontrados o actividad tomada de sus dispositivos. La API familiar real no está entregando estos perfiles enriquecidos. |
| Cortes temporales del escenario demo | Las sumas por app y acompañamiento concilian | Corregir el corte de día: a las 00:05 de Argentina el fixture conserva eventos de la noche anterior dentro de valores rotulados «hoy». [Reproducción](evidence/tino-demo-dates-audit-2026-10-31.json). |
| Tiempo de pantalla, apps y semana | Demo coherente; API `/usage/batch` admite intervalos autorizados | Implementar el recolector Android, permisos de Usage Access y envío. El manifiesto no solicita acceso a estadísticas de uso. |
| Últimos sitios y previews | Título/dominio/hora ficticios; detalle interno | Thumbnails/previews, enlaces navegables si se desean y, para datos reales, una fuente autorizada de navegación. Hoy no se captura historial de Chrome/Samsung Internet. |
| Aprendizaje y autonomía familiar | Panel demo y métricas reales de sesiones en API | Recibir telemetría fiable del usuario, UI de práctica para todos los flujos y no atribuir resultados por confirmación manual como si fueran observados. |
| Enviar ping | API real con vínculo y `help`; demo guarda una acción ficticia | En la demo no existe un teléfono de Marta recibiéndolo. La recepción real consulta mensajes mientras está abierto el chat; falta push/background y confirmación de recepción. |
| Aviso de voz | Grabación, preview, envío y reproducción implementados; API valida audio y acceso | Prueba actual entre dos dispositivos, notificación en background y, si se quiere «que lo diga en voz alta» sin tocar, reproducción/TTS con consentimiento. No se afirma esa reproducción automática. |
| Pedir ubicación | Se envía un aviso con consentimiento de ayuda | La respuesta actual propone una guía de WhatsApp. No alimenta el mapa familiar de TINO. |
| Ubicación familiar temporal | Mapa de barrio ilustrativo, reloj y expiración de datos demo | GPS, permiso, consentimiento temporal, endpoint de publicación, actualización y revocación reales. El manifiesto no solicita ubicación y la API real devuelve `location: null`. |
| Pedir ayuda y atender aviso | Rutas reales `/family/help` y resolución; pruebas API | Acceso al vínculo del usuario y recepción oportuna en Android. El panel nativo no refresca continuamente. No se acreditó un E2E actual entre dos teléfonos. |
| Riesgos, interrupciones y cambios de patrón | Almacenamiento de eventos de seguridad y alerta demo | Detectores Android, clasificación, reglas de variación/interrupción y escalamiento automático a la familia. No existe un analizador de los sitios reales que visita la persona. |
| Actualización de familia | Web consulta periódicamente; Android carga al entrar y tras acciones propias | Actualizar cuando llega actividad de otro dispositivo, fecha de sincronización y manejo de desconexión. |
| Suscripción de USD 5 | Modelo comercial definido; sin cobro implementado | Checkout, altas/bajas, renovaciones, webhooks y permisos por plan. |

## Instituciones y OpenARG

| Funcionalidad | Estado actual | Lo que falta |
| --- | --- | --- |
| Mapa argentino y filtros | Geometría real, puntos agregados y filtros de provincia/período/capa | Animación si se desea, actualización por eventos y datos TINO realmente recogidos. No hay posiciones individuales, por privacidad. |
| Dos fuentes públicas | Censo 2022 y ENACOM 2026 T1 comprobados en el dashboard; conexión MCP verificada hoy | Ampliar indicadores y catálogo según los cruces elegidos. El cache se conserva por 24 horas, no es una consulta nueva por cada toque. |
| Demografía y contexto extendido | Hay además un adaptador histórico específico de CABA; no se reconsultó hoy | Cobertura nacional de edad, hogares unipersonales, educación, ingresos, salud, instituciones, transporte y servicios. No inferir discapacidad a partir de edad o conectividad. |
| Seis tarjetas institucionales | Activos, edad, pantalla/día, autonomía, solicitudes y enlaces; demo calculada | Sus numeradores son TINO simulado. Top 10 y evolución de seis meses también lo son. La ruta real agrega eventos disponibles, pero faltan recolectores y cohortes reales para demostrar ese escenario. |
| Cruce OpenARG y TINO | Join provincial ISO, bases censales, conectividad y tasas por 10.000 habitantes | Más cruces explícitos y definiciones comparables. Tener seis métricas junto a una fuente no significa seis cruces públicos completos. |
| Privacidad de agregados | Umbral 5 y supresión implementados y probados; la edad exige consentimiento | **Aplicar opt in a las demás métricas y excluir contribuciones demo de la vía real.** El probe aislado mostró publicación con cinco perfiles demo y opt out. La supresión conservadora puede dejar sin puntos el mapa real. |
| Programas y medición antes/durante | API y portal React con creación/estado/evaluación | Integración en la interfaz Android actual, cohortes reales y validación de la evaluación. El cambio observado no prueba causalidad. |
| Reportes | CSV del panel implementado | Jobs/reportes programados, formatos adicionales e integraciones B2B, si se mantienen en alcance. |
| Login y administración institucional | Credencial compartida y allowlist de correos en servidor | Organizaciones aisladas, roles internos, MFA, administración y operación con varias instituciones. |

## Plataforma, seguridad y entrega

| Área | Estado actual y pendiente |
| --- | --- |
| Infraestructura | Backend Node HTTP y archivo JSON de un proceso, con escritura atómica. Faltan base durable multiusuario, migraciones, backups, jobs y observabilidad de producción. Las tecnologías recomendadas en el documento no son por sí solas features: no es obligatorio reemplazar Node HTTP por NestJS para completar un flujo. |
| Contratos | Hay documentación y tipos manuales; faltan OpenAPI y generación compartida de tipos. |
| Secretos y acceso | Keystore/AES-GCM y claves fuera del APK implementados. Faltan secreto estable de auth, tokens cortos con refresh rotado, MFA y política operativa de rotación. Esta auditoría no publica claves. |
| Cuenta y datos | Faltan exportación/eliminación de cuenta y política completa de retención. Borrar una sesión no equivale a eliminar la cuenta. |
| Telemetría | Hay eventos y límites de API. Faltan persistencia/reintento móvil, instrumentación de uso/seguridad y trazabilidad durable entre dispositivos. |
| Distribución | APK debug instalado en emulador; no hay release firmado y publicado en Play Store ni validación final en la tablet. El posible artefacto prioritario `artifacts/nieto-debug.apk` no existe hoy, por lo que la descarga local usa el build actual; no se afirma que hoy sirva una copia vieja. Sigue faltando un pipeline de distribución versionado. |
| QA | Automatización no sustituye WhatsApp/Gmail reales, dos usuarios vinculados, voz física, TalkBack, fuente ampliada, batería, permisos revocados, corte de red y proceso reiniciado. |
| Documentación | Hay documentos históricos con estados anteriores. Este informe fija el estado auditado y no toma sus promesas como prueba de implementación. |

## Prioridad para cerrar la demo

Antes de cargar usuarios reales, corregir el filtro de consentimiento institucional y la separación demo/real. El umbral de cinco no reemplaza el permiso de cada aportante.

1. Reconectar aceptación/revocación del vínculo del usuario y validar invitación → consentimiento → ayuda → resolución con dos clientes.
2. Recuperar una respuesta real del proveedor de IA; integrar la rama Realtime de Fede si esa es la ruta elegida y probarla en esta APK.
3. Corregir los dos problemas del motor de ubicación; validar el flujo real en la versión de WhatsApp del equipo.
4. Definir qué se demostrará como guía confirmada manualmente para grupo/correo y qué debe quedar verificado por pantalla.
5. Resolver la actualización de paneles cuando entra actividad de otro dispositivo y la recepción de avisos fuera del chat abierto.
6. Agregar thumbnails ficticios si se quieren mostrar en el pitch y corregir los cortes de día del escenario demo; implementar aparte los recolectores reales y sus consentimientos.
7. Para producción completa: GPS temporal, uso/seguridad reales, permisos granulares, fuentes públicas adicionales, pagos, infraestructura y seguridad operativa.

No se asigna un porcentaje de avance: contar pantallas, rutas o tests no mide el cierre de estos recorridos.

## Referencias de código

- [Mapa Android](../apps/android/app/src/main/java/org/nieto/assistant/ui/ArgentinaMap.kt) y [dashboard institucional](../apps/android/app/src/main/java/org/nieto/assistant/ui/InstitutionDashboard.kt).
- [Cruce de fuentes](../apps/api/src/dashboard-context.mjs), [normalización OpenARG](../apps/api/src/openarg-dashboard.mjs) y [datos demo](../apps/api/src/dashboard-demo.mjs).
- [Navegación](../apps/android/app/src/main/java/org/nieto/assistant/ui/AppMenu.kt), [secciones de usuario](../apps/android/app/src/main/java/org/nieto/assistant/MainActivity.kt), [API familiar y telemetría](../apps/api/src/server.mjs).
- [Panel familiar y edades](../apps/android/app/src/main/java/org/nieto/assistant/ui/FamilyPanels.kt), [componentes familiares](../apps/android/app/src/main/java/org/nieto/assistant/ui/FamilyComponents.kt) y [permisos Android](../apps/android/app/src/main/AndroidManifest.xml).
- [Motor de ubicación](../apps/android/app/src/main/java/org/nieto/assistant/flow/GuidanceEngine.kt), [colector accesible](../apps/android/app/src/main/java/org/nieto/assistant/screen/NietoAccessibilityService.kt), [guías manuales](../apps/api/src/assistant.mjs).
- [Vosk y activación](../apps/android/app/src/main/java/org/nieto/assistant/presence/WakeVoiceService.kt), [cliente móvil y cola](../apps/android/app/src/main/java/org/nieto/assistant/NietoApp.kt), [adaptador de IA](../apps/api/src/model-adapter.mjs).
