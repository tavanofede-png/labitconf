# TINO Android: estado y continuidad

Estado auditado y faltantes: [Auditoría de funcionalidades del 31 de octubre](TINO-FEATURE-AUDIT.md). Esta revisión distingue datos reales, datos demo y recorridos todavía incompletos.

La app real está en `apps/android`, Kotlin + Jetpack Compose, paquete `org.nieto.assistant`. La landing y el frontend React son otros clientes; no se usan como contenedor de la app ni se abren desde su bienvenida.

## Implementación nativa

El menú lateral es único para TINO Chat, Grupo familiar, Instituciones, Guías y Ajustes. Omite el destino que ya está abierto y muestra los interruptores «Burbuja flotante» y «Hola TINO» con su estado real, encima de «Cerrar sesión» en rojo. Ambos servicios y sus permisos pertenecen al dispositivo, por lo que los mismos controles se muestran en todos los espacios.

`POST /accounts/space` cambia de espacio dentro del correo ya verificado, rota la sesión y recupera el perfil correspondiente. No acepta otro correo ni copia autorizaciones entre roles. Una cuenta real nueva completa el onboarding de ese espacio; la autorización institucional y los consentimientos familiares se siguen comprobando en el servidor. Las cuentas de demostración pueden recorrer los tres espacios sin repetir el código ni el onboarding, con sus datos simulados separados de los reales.

`entry/EntryScreens.kt` y `EntryViewModel.kt` agregan bienvenida con Usuario, Grupo Familiar e Institución; perfil propio; foto opcional con selector de archivos Android; permisos reales de micrófono/notificaciones y divulgación antes de Accesibilidad; cuestionarios A–D; revisión; login por código; y conexión configurable al backend. Las pantallas se limitan a un ancho de lectura de 720dp y se adaptan a teléfonos y tablets. El asistente anterior conserva voz Android, chat, guía local de WhatsApp, familia y ajustes.

El espacio familiar nativo consulta miembros y métricas consentidas, genera invitaciones, muestra solicitudes de ayuda y permite atenderlas y aprobar incorporaciones al grupo. El espacio institucional nativo consulta indicadores agregados, flujos, tiempo autorizado, seguridad y contexto OpenARG. La edición completa de programas sigue en el portal web; todavía no se trasladó al espacio Android.

El rediseño familiar toma como referencia el explorador de Grupo familiar de https://nieto-web.vercel.app/: perfil y acciones en un lateral en tablet, selector de integrantes y pestañas en teléfono, resumen compacto, sitios visitados, actividad filtrable con detalles, distribución por app, gráfico semanal seleccionable, aprendizaje, autonomía, ubicación temporal a escala de barrio y avisos revisables. `FamilyComponents.kt` y `FamilyPanels.kt` contienen las ilustraciones nativas, iconos y paneles; no dependen de fotos, mapas o recursos remotos para mostrarse.

`demoFamily` construye la Familia Ríos ficticia (Marta, Ricardo y Lucía) desde eventos: los minutos por aplicación y el total de pantalla se calculan del mismo historial, y el acompañamiento parcial se cuenta sin sumarlo otra vez al tiempo de pantalla. Las semanas terminan en esos mismos valores; edades, prácticas, permisos, sitios y alertas corresponden a cada perfil. El escenario queda anclado durante el día para que actualizar no invente nuevas actividades ni extienda la ubicación. La UI identifica los datos demo y el mapa ilustrativo. Estos sitios e historiales no se recopilan de teléfonos reales; el portal real solo muestra campos autorizados que efectivamente devuelve la API.

`data/LocalSettings.kt` y `NietoApi.kt` completan las clases que faltaban en el checkout. Credenciales y perfil local usan AES-GCM con Android Keystore; cambiar servidor descarta las credenciales asociadas. La API no se ejecuta en el hilo de UI. HTTP se permite solo en APK debug, para USB/emulador; release requiere HTTPS.

## Identidad y permisos en backend

`apps/api/src/accounts.mjs` implementa `/auth/code/request`, `/auth/code/verify`, `/accounts/onboarding`, `/accounts/me`, `/accounts/session` y aprobación de incorporaciones familiares. Los códigos vencen en cinco minutos, admiten cinco intentos, tienen espera entre envíos y solo se usan una vez. Se guardan hashes de códigos y sesiones, no los códigos ni los bearer tokens en claro. Las cuentas no otorgan acceso institucional por elegir un rol: hace falta autorización por `INSTITUTION_EMAILS` en el servidor. Las incorporaciones al grupo requieren aprobación del administrador; compartir progreso y pedidos de ayuda requiere consentimiento separado de la persona acompañada.

Para correo real configurar `AUTH_SECRET`, `RESEND_API_KEY` y `AUTH_EMAIL_FROM` con un remitente autorizado. Sin eso el servicio devuelve un error, no dice que envió un email. `TINO_LOCAL_DEMO=1` permite códigos en la terminal **solo con conexiones de loopback**, y Android identifica ese recorrido como prueba local sin correo. Este modo no está activado por defecto.

## Continuar desde la computadora de Federico

1. Actualizar la rama compartida con los cambios Android. El PR anterior contiene la base TINO: https://github.com/tavanofede-png/labitconf/pull/1.
2. Desde la raíz, ejecutar `npm ci` y luego `npm run test`.
3. Configurar `.env` en esa computadora, sin compartir claves por chat ni incluirlas en Git. Para la prueba física por USB, API en el puerto 8787.
4. Compilar con `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/android-build.ps1 -Install`. El script busca JDK/SDK ya instalados y acepta `JAVA_HOME`/`ANDROID_HOME`. Requiere Android SDK 36 y Build Tools 35.0.0.
5. Autorizar depuración USB en la tablet. Ejecutar `adb reverse tcp:8787 tcp:8787`, iniciar `npm run api` en la computadora, abrir TINO y configurar `http://127.0.0.1:8787` en Conexión. No usar la URL de la landing.
6. Ejecutar los recorridos en la tablet: usuario/código/perfil/cuestionario, chat, micrófono, autorización de Accesibilidad, invitación y consentimiento familiar, alerta y revocación. El perfil de guía existente admite WhatsApp `2.26.*` en español; debe comprobarse la versión y las pantallas reales antes de afirmar compatibilidad.

La tablet Samsung SM-X820 con Android 16 se conectó después a esta Mac por USB y autorizó depuración. Se instaló la APK compilada, se abrió la bienvenida nativa y se verificó la conexión con `adb reverse` y el chat contra la API real.

## Acceso de jueces sin onboarding

Activar `TINO_JUDGES_DEMO=1` en el backend (`TINO_DEMO_EMAIL=test@labitconf.com`). Desde **Ya tengo cuenta → Usuario**, ingresar **test@labitconf.com**, tocar **Enviar código** y completar los casilleros con **000-000**. La app abre directamente el asistente con un perfil de prueba completo. La verificación del código es simulada; no se envía correo. El onboarding sigue disponible para cuentas nuevas.

`POST /api/auth/demo/login { email, password }` devuelve una sesión real de API para una identidad de demostración. Su cuenta y dispositivo son distintos de cualquier cuenta real con el mismo correo. La contraseña pública de prueba no otorga permisos de Android, familiares ni institucionales. Varios jueces pueden ingresar a la vez y cerrar sesión de forma independiente. Desactivar `TINO_JUDGES_DEMO` deshabilita también las sesiones demo existentes. La clave 0000 solo es válida en este endpoint de demo habilitado explícitamente.

Para recorrer el alta en el mock, **Verificar mi correo** abre «Te llegó un código a tu mail» y acepta **000-000** (`000000`). El código se muestra en seis casilleros, uno por número: el foco visual avanza al escribir y completar el sexto dispara la verificación automática. Se puede pegar el código completo, borrar o tocar un número para corregirlo; un código rechazado limpia los casilleros para reintentar. En modo demo no requiere proveedor de correo; cada desafío es independiente y conserva expiración, intentos y uso único. Esas identidades se guardan como cuentas de demo separadas, y no obtienen autorización institucional real por escribir un correo autorizado. Fuera del modo demo se conserva el envío real con código aleatorio.

## Inicio en el chat

En el mock, **Ya tengo cuenta → correo → 000-000** abre directamente el chat de TINO, incluso si antes se inició un alta incompleta con ese correo. El login de demo precarga el perfil; el alta nueva conserva su recorrido separado. El inicio muestra la mascota oficial arriba, el menú lateral que se desliza desde la derecha y el compositor fijo con escritura, voz y envío. El menú abre Mi grupo familiar, Guías y Ajustes; muestra permisos y Cerrar sesión en rojo con su icono. No ofrece Chat si ya está abierto. El inicio anterior con tarjetas y navegación inferior fue reemplazado por esta conversación. Una nueva sesión limpia la conversación anterior en memoria.

## Conversación y recorridos de la demo

El chat autenticado recibe texto libre y conserva el contexto en memoria. `apps/api/src/assistant.mjs` separa la conversación de las acciones: «WhatsApp» por sí solo no dispara compartir ubicación. El mock puede ayudar con otras consultas sobre fotos, letra, Wi-Fi y contactos; no representa un modelo general conectado. Los borradores de correo piden destinatario, asunto y mensaje en tres turnos y muestran una revisión antes de abrir la app de correo.

Las guías `whatsapp-group` y `email-send` tienen sesiones propias, progreso por paso, cancelación y finalización explícita del usuario. El botón de inicio abre WhatsApp o un borrador mediante la app de correo instalada; TINO no crea el grupo ni envía el mensaje por su cuenta. El usuario revisa y realiza la acción final en la app correspondiente. Las instrucciones pueden seguirse en el chat o en la burbuja si se habilita Accesibilidad. Esta burbuja de pasos no lee el contenido de WhatsApp ni de los correos. El resultado se registra como `user_confirmed`; no se presenta como verificación visual automática. El correo y su contenido no se guardan en el JSON de métricas.

### Conexión de IA a cargo de Fede

Por indicación del usuario, la API del modelo se conecta al final. El punto de conexión es `apps/api/src/model-adapter.mjs`: reemplazar `generateReply = null` por una función asíncrona `({ system, messages, flows, task }) => string`. El servidor pasa instrucciones y los últimos turnos, admitiendo solo roles `user` y `assistant` del cliente. Las claves quedan en el backend. El login, las guías y la app Android conservan sus contratos. No hace falta cambiar la interfaz ni limitar las respuestas a las acciones de demo.

Contrato de chat: `POST /api/assistant/message` con Bearer de la cuenta, `text`, `conversationId` y `history` opcional (hasta 12 turnos). La respuesta contiene `instruction`, `mode`, `flowId` opcional y `emailDraft` opcional. El contexto de formularios se mantiene acotado en RAM y aislado por sesión/conversación; no se persisten mensajes. Los botones de acción solo usan flujos permitidos, no instrucciones arbitrarias del modelo.

## Verificación actual

Pasan 46 pruebas de API y 12 tests unitarios Android. La API cubre acceso de demo con test@labitconf.com / 0000, omisión del onboarding, sesiones concurrentes y aislamiento frente a cuentas reales. La verificación de instalación de cada APK nueva se informa por separado: el login test@labitconf.com / 0000 fue instalado y verificado en la Samsung, y las nuevas revisiones se verifican por separado.

Verificación física anterior: bienvenida, conexión USB al backend, registro de instalación, ingreso de una consulta por teclado y respuesta visible de la API. Todavía deben verificarse en la tablet el nuevo acceso demo, micrófono, permisos de Accesibilidad y el recorrido real sobre WhatsApp. OpenARG no tiene una clave configurada en esta Mac; no se acreditan datos públicos en vivo.

`scripts/android-viewer.mjs` muestra imágenes en vivo de Android y transfiere toques/teclado al dispositivo seleccionado con `ANDROID_SERIAL`; por defecto usa `emulator-5554`. Solo escucha en loopback y exige origen y nonce para cada acción. En dispositivos físicos no permite cambiar resolución/densidad. Fue validado con la tablet: la orientación de los toques se calcula con las dimensiones de la captura real, no con la orientación natural del dispositivo. No recrea la app con HTML ni usa el frontend React.


## Burbuja y «Hola TINO»

El menú del chat muestra dos interruptores independientes arriba de Cerrar sesión. Empiezan apagados. La burbuja usa SYSTEM_ALERT_WINDOW, autorizado en Ajustes de Android; es un acceso flotante y arrastrable con la mascota oficial que abre el chat. No solicita lectura de pantalla. Se oculta dentro de TINO, con el dispositivo bloqueado y cuando la guía de Accesibilidad ya muestra una burbuja. Su servicio foreground declara specialUse y explica el uso en el manifest; este uso requiere revisión al publicar en Google Play.

«Hola TINO» solicita consentimiento, micrófono y notificaciones. presence/WakeVoiceService.kt mantiene un servicio foreground de tipo microphone iniciado desde la app visible. Usa Vosk Android 0.3.75 y el modelo español pequeño 0.42, incluidos en la APK. Detecta la frase localmente, emite un sonido y escucha hasta 15 segundos para la consulta. Solo esa consulta transcrita pasa al chat existente. No se guardan ni transmiten grabaciones ni texto ambiental. Se pausa durante la respuesta y la voz de TINO. El botón de micrófono utiliza el mismo motor si el servicio está activo, para evitar dos consumidores del micrófono.

Ambos servicios se detienen desde el menú o su notificación y al cerrar sesión. No se reinician al arrancar el dispositivo ni tras un cierre forzado; Android puede detenerlos. No reemplazan al asistente predeterminado del sistema ni usan su DSP de bajo consumo. Probar ruido, batería, llamadas y pantalla apagada en la tablet antes de afirmar equivalencia con un asistente del sistema.

Gradle prepareVoiceModel descarga el modelo oficial y verifica SHA-256 09b239888f633ef2f0b4e09736e3d9936acfd810bc65d53fad45261762c6511f. Cachea el ZIP en .cache/voice-models y genera assets fuera de Git. Las licencias viajan en assets. La primera compilación requiere Internet y la APK aumenta de tamaño.

Verificación de voz: tres pruebas de instrumentación con el decodificador Android real y audio sintético generado localmente: reconoce «Hola Tino», rechaza una frase común y reconoce una consulta de correo. No usan el micrófono. Ejecutar assembleDebugAndroidTest, instalar app-debug-androidTest.apk y ejecutar adb shell am instrument -w -r org.nieto.assistant.test/org.nieto.assistant.presence.VoiceSmokeInstrumentation. Esto no sustituye la prueba acústica en la tablet.

Fuentes: https://developer.android.com/develop/background-work/services/fgs/service-types, https://alphacephei.com/vosk/models y https://github.com/alphacep/vosk-android-demo.

## Familia e Instituciones — nueva interfaz nativa

Familia e Instituciones usan pantallas Compose propias, con el kit TINO, tarjetas adaptativas, filtros, estados de carga y control de permisos. Las cuentas demo de los tres roles pueden ingresar con test@labitconf.com y código 000000 desde Ya tengo cuenta; la separación por rol no otorga acceso a métricas reales. Las instituciones demo usan solo /demo/institution/dashboard. Las familias demo usan solo su escenario y acciones aisladas por grupo.

Instituciones muestra seis indicadores: participantes distintos, edad media, pantalla por día-persona, prácticas autónomas sobre tareas completadas, solicitudes de tareas y alertas de enlaces inseguros. Incluye diez tareas, seis meses calendario (corte de Argentina), selección de mes/provincia, tendencias, edades, exportación CSV y mapa provincial con capas verdes de uso y rojas de alertas. La actividad TINO es simulada y se identifica como tal. El mapa utiliza geometría Georef/IGN, agregada por provincia; no muestra sitios concretos ni personas.

Los denominadores reales se obtuvieron mediante MCP autenticado de OpenARG: población del Censo 2022 (INDEC, publicación DGEyC Córdoba) y accesos a Internet fijo de ENACOM, último trimestre disponible 2026 T1. El parser valida 24 jurisdicciones, duplicados, períodos y conciliación exacta del total censal. ENACOM mide accesos por cien hogares, no porcentaje de hogares, y puede superar 100. Las fuentes y fechas se conservan en apps/api/src/public-data/openarg-national-verified.json. El cruce usa códigos ISO y no convierte una base censal histórica en población actual ni demuestra impacto causal. La actualización autenticada se ejecuta con node --env-file=.env scripts/sync-openarg-dashboard.mjs; los filtros no consumen consultas repetidas. El backend conserva la última captura verificada si la fuente no responde y limita nuevos intentos.

Familia incluye personas seleccionables, resumen, actividad, ubicación temporal cuando existe, alertas atendibles, invitaciones de un solo uso, pings y avisos de voz M4A de hasta 30 segundos. Los mensajes familiares reales requieren el permiso help, se consultan en el chat del destinatario, caducan a las 24 horas y se purgan al consultar o escribir mensajes. No hay envío a WhatsApp ni notificaciones push externas. La demo familiar usa nombres, actividad y ubicaciones ficticias. Las cuentas reales mantienen privados los datos no autorizados; el grupo no puede cambiar esos permisos.

El onboarding agrega fecha de nacimiento con selector Android y validación de fecha. La media real de edad solo se publica con consentimiento estadístico y cohorte suficiente; nunca devuelve fechas individuales. El backend conserva compatibilidad con perfiles anteriores sin fecha.

El adaptador OpenAI usa Responses API con store:false y claves del entorno. La clave recibida en esta sesión fue rechazada con HTTP 401 invalid_api_key; la existencia del adaptador no acredita conexión de IA exitosa. La configuración se mantiene exclusivamente en .env ignorado por Git. Los cambios Realtime de Fede llegaron por separado a origin/main y se preservan allí.
