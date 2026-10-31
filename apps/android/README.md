# NIETO Android · Labitconf

Aplicación Kotlin + Compose, Android 8+ (API 26), compile/target 36. Versión **0.5.0-labitconf (código 5)**. La asistencia principal entiende una tarea con la pantalla actual; la guía local de ubicación por WhatsApp queda como práctica opcional.

## Compilar

Desde raíz:

~~~powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/android-build.ps1
# Con dependencias descargadas:
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/android-build.ps1 -Offline
~~~

JDK 17+, platform 36/build-tools35.0.0. El script descubre herramientas locales existentes, ejecuta tests y compila. Sólo tras éxito actualiza atómicamente artifacts/nieto-debug.apk y verifica SHA256 contra app/build/outputs/apk/debug/app-debug.apk. Dependencias nuevas requieren Maven la primera vez.

## Asistencia de pantalla

1. El operador genera en el portal Conexión un código de conexión de 12 caracteres, de un uso y 15 minutos. Guardar dirección del servidor y ese código en Ajustes para registrar o activar el teléfono. Las instalaciones anteriores requieren activación para IA; Familia y telemetría conservan su credencial. El código sólo vive en el formulario y se limpia al ser aceptado; nunca se ingresa la credencial institucional en Android. El token NIETO se cifra con Android Keystore; ninguna clave de OpenAI/OpenARG llega al APK. Debug admite HTTP/WS local, release requiere HTTPS/WSS.
2. Aceptar consentimiento remoto nuevo para controles, consultas y voz. El permiso local de la versión anterior no autoriza envío. Las capturas tienen una autorización adicional desactivada por defecto.
3. Habilitar NIETO en Accesibilidad. Activar acompañamiento termina cualquier guía local anterior; ambos modos son excluyentes. Abrir la app objetivo y tocar la burbuja N. La burbuja permite escribir la tarea, leer el paso actual, repetir, pedir otra explicación y actualizar pantalla. Una consulta escrita elige salida silenciosa y no abre AudioTrack ni TTS; el modo se conserva para los siguientes pasos hasta iniciar voz explícitamente. El borrador se conserva cuando el envío no es aceptado o NIETO está ocupado. No muestra ni persiste historial.
4. Hablar abre una actividad transparente visible encima de la app objetivo. Sólo ese gesto y el permiso Android permiten micrófono. El contexto se captura antes de abrirla y se revalida al enviar; un permiso demorado o pantalla cambiada descarta el snapshot preparado. Salir apaga el micrófono; la sesión de tarea puede continuar en la burbuja si terminó la respuesta.
5. El agente devuelve una instrucción y un controlId/screenId. Sólo se resalta un control único, vigente y dentro de bounds. Nunca se ejecutan clics, gestos, escritura, envíos ni navegación. La persona realiza cada acción.
6. Cambios reales invalidan el marcador y cancelan la respuesta anterior, incluso con iguales botones y distinto contenido. Se usa una revisión local y HMAC con clave efímera de texto estático; ni ese texto ni su digest salen en el protocolo. Después de estabilizar se envía contexto para el siguiente paso, limitado a un seguimiento cada 4 segundos y hasta 18 seguimientos automáticos por tarea. Actualizaciones manuales respetan 1 segundo mínimo. El servidor limita a 20 turnos totales por conexión, incluyendo consultas manuales; al alcanzar un límite se informa el error y debe reiniciarse la asistencia.
7. Apagar o revocar cancela micrófono/reproducción, cierra la conexión y borra snapshot, respuesta y contexto efímero. El servidor descarta la conversación upstream al cerrar. Los bloques ya enviados no se pueden retirar.

El protocolo screen.context/screen.clear/guidance.step se describe en docs/SCREEN_ASSISTANCE_CONTRACT.md. Audio PCM16 signed little-endian 24kHz mono, bloques 100ms, máximo 45segundos de entrada y 90 segundos de salida. AudioTrack reproduce incrementalmente únicamente en modo voz; no hay SpeechRecognizer ni transcripción de entrada adicional. Al interrumpir, se informa el mensaje y los milisegundos efectivamente reproducidos para truncar el contexto pendiente. `playback.complete` se envía sólo después de vaciar la reproducción. El texto visible es salida del modelo. Las instrucciones locales pueden usar TTS Android; la asistencia escrita silencia también los avisos TTS.

## Captura y privacidad

Android 11+ (API 30) usa AccessibilityService.takeScreenshot únicamente con consentimiento adicional y durante asistencia activa. Bitmap/JPEG viven en memoria; nunca se escriben archivos. JPEG máximo 300 KB, reducido a 1280 px. El contenido de todos los campos editables se excluye del payload y se enmascara antes de codificar imagen. Un campo no secreto sólo aporta ID, bounds y la etiqueta constante Campo de texto, sin leer valor, hint ni descripción. Un árbol truncado o editable sin bounds permite sólo controles. Teclado u otra app visible también impide imagen; otras ventanas del sistema se enmascaran. Se revalida la pantalla después de recibir la captura.

Android 8–10 aporta controles accesibles y avisa que no hay captura. Contraseñas, autenticación, permisos del sistema y apps sensibles reconocidas bloquean el contexto. Pantallas protegidas nunca se eluden. La política es preventiva y no garantiza detectar toda información privada; evitá abrir contenido privado mientras acompañamiento está activo. Las capturas autorizadas pueden incluir contenido personal visible fuera de los campos ocultados.

No se solicitan ubicación, cámara, contactos ni SMS. La burbuja usa TYPE_ACCESSIBILITY_OVERLAY; no requiere permiso general de superposición. No hay grabación permanente ni escucha en segundo plano.

## Métricas y Familia

La asistencia dinámica registra sólo sesión, eventos cerrados de paso/repetición/dificultad/cancelación y duración. Nunca registra texto, paquete, captura ni IDs de pantalla en métricas; tampoco acredita finalización ni autonomía por lo que dice el agente. La guía local reconoce controles de WhatsApp 2.26.* en español y registra sólo el reporte explícito de la persona (`self_reported`). No verifica que se envió un mensaje nuevo ni acredita autonomía. El alcance vigente se documenta en [MVP_FUNCTIONAL_SCOPE.md](../../docs/MVP_FUNCTIONAL_SCOPE.md).

El consentimiento institucional se desactiva por defecto y es independiente de Familia. Se configura mediante GET/PATCH /api/devices/me y permite estadísticas agregadas de actividad y dificultades de los últimos 30 días. Revocarlo excluye la actividad de agregados.

Familia abre FamilyActivity nativa: crear/restaurar grupo, invitaciones, miembros, consentimientos, resumen y solicitudes atendidas. La credencial del grupo es independiente de la instalación. El chequeo periódico WorkManager es aproximado (mínimo 15 minutos) y depende de Android/red/batería; consultar la pantalla actualiza inmediatamente. No es una alerta de emergencia ni entrega instantánea garantizada. Familia nunca recibe pantalla ni conversación.

## Validación

Los tests cubren motor local, límites PCM, descarte de turnos cancelados, controles sensibles, targets obsoletos, bounds y políticas Familia. Compilar y pasar tests no acredita compatibilidad física. Pendiente por dispositivo: permiso y micrófono real, reproducción/BT, actividad transparente, escritura/IME en overlay, captura/redacción y bloqueo, pantalla girada, cambios de app, segundo plano, Familia y notificaciones.
