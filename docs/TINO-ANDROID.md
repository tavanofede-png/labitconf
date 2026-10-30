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

## Verificación actual

Las 34 pruebas de API y los 7 tests unitarios Android pasan. Gradle completó `assembleDebug` y `testDebugUnitTest`. APK en `apps/android/app/build/outputs/apk/debug/app-debug.apk` (aproximadamente 11 MB), instalada correctamente en la tablet física.

Verificación física realizada: bienvenida, conexión USB al backend, registro de instalación, ingreso de una consulta por teclado y respuesta de la API visible en la app. Todavía deben verificarse en la tablet el login por correo con proveedor real, los otros roles completos, micrófono, permisos de Accesibilidad y el recorrido real sobre WhatsApp. OpenARG no tiene una clave configurada en esta Mac; no se acreditan datos públicos en vivo.

`scripts/android-viewer.mjs` muestra imágenes en vivo de Android y transfiere toques/teclado al dispositivo seleccionado con `ANDROID_SERIAL`; por defecto usa `emulator-5554`. Solo escucha en loopback y exige origen y nonce para cada acción. En dispositivos físicos no permite cambiar resolución/densidad. Fue validado con la tablet: la orientación de los toques se calcula con las dimensiones de la captura real, no con la orientación natural del dispositivo. No recrea la app con HTML ni usa el frontend React.
