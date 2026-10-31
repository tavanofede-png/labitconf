# TINO Android: estado y continuidad

La app real está en `apps/android`, Kotlin + Jetpack Compose, paquete `org.nieto.assistant`. La landing y el frontend React son otros clientes; no se usan como contenedor de la app ni se abren desde su bienvenida.

## Implementación nativa

`entry/EntryScreens.kt` y `EntryViewModel.kt` agregan bienvenida con Usuario, Grupo Familiar e Institución; perfil propio; foto opcional con selector de archivos Android; permisos reales de micrófono/notificaciones y divulgación antes de Accesibilidad; cuestionarios A–D; revisión; login por código; y conexión configurable al backend. Las pantallas se limitan a un ancho de lectura de 720dp y se adaptan a teléfonos y tablets. El asistente anterior conserva voz Android, chat, guía local de WhatsApp, familia y ajustes.

El espacio familiar nativo consulta miembros y métricas consentidas, genera invitaciones, muestra solicitudes de ayuda y permite atenderlas y aprobar incorporaciones al grupo. El espacio institucional nativo consulta indicadores agregados, flujos, tiempo autorizado, seguridad y contexto OpenARG. La edición completa de programas sigue en el portal web; todavía no se trasladó al espacio Android.

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

En el mock, **Ya tengo cuenta → correo → 000-000** abre directamente el chat de TINO, incluso si antes se inició un alta incompleta con ese correo. El login de demo precarga el perfil; el alta nueva conserva su recorrido separado. El inicio muestra la mascota oficial arriba, el menú en la esquina superior derecha y el compositor fijo con escritura, voz y envío. El menú abre Chat, Mi grupo familiar, Guías, Ajustes y Cerrar sesión. El inicio anterior con tarjetas y navegación inferior fue reemplazado por esta conversación. Una nueva sesión limpia la conversación anterior en memoria.

## Verificación actual

Pasan 41 pruebas de API y 10 tests unitarios Android. La API cubre acceso de demo con test@labitconf.com / 0000, omisión del onboarding, sesiones concurrentes y aislamiento frente a cuentas reales. La verificación de instalación de cada APK nueva se informa por separado: el login test@labitconf.com / 0000 fue instalado y verificado en la Samsung, y las nuevas revisiones se verifican por separado.

Verificación física anterior: bienvenida, conexión USB al backend, registro de instalación, ingreso de una consulta por teclado y respuesta visible de la API. Todavía deben verificarse en la tablet el nuevo acceso demo, micrófono, permisos de Accesibilidad y el recorrido real sobre WhatsApp. OpenARG no tiene una clave configurada en esta Mac; no se acreditan datos públicos en vivo.

`scripts/android-viewer.mjs` muestra imágenes en vivo de Android y transfiere toques/teclado al dispositivo seleccionado con `ANDROID_SERIAL`; por defecto usa `emulator-5554`. Solo escucha en loopback y exige origen y nonce para cada acción. En dispositivos físicos no permite cambiar resolución/densidad. Fue validado con la tablet: la orientación de los toques se calcula con las dimensiones de la captura real, no con la orientación natural del dispositivo. No recrea la app con HTML ni usa el frontend React.
