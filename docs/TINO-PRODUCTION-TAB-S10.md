# TINO en Galaxy Tab S10+ · aplicación nativa

## Objetivo y alcance

TINO debe responder a «Hola Tino» desde la aplicación que la persona esté usando, sin abrir un chat, cambiar a una pantalla de TINO ni activar el micrófono mediante un botón en cada consulta. La persona realiza las acciones; TINO conversa y señala controles visibles. Las pantallas de Gmail y WhatsApp pertenecen a las aplicaciones instaladas.

Equipo conectado para esta entrega: Samsung Galaxy Tab S10+ Wi-Fi, modelo SM-X820, Android 16/API 36 y One UI 8.5. La configuración inicial de permisos y asistente predeterminado se realiza una vez. Android puede restaurar el servicio al enlazar el asistente después del reinicio y primer desbloqueo; la disponibilidad antes del primer desbloqueo no se acredita con esta implementación.

## Implementación

- `TinoVoiceInteractionService` se registra como asistente predeterminado seleccionable por Android. Su `onReady` restaura la elección persistida de escucha. No se inicia un micrófono desde un receptor genérico de arranque.
- `WakeWordService` mantiene el servicio de micrófono y su notificación con Detener. Vosk reconoce «Hola Tino» localmente; no guarda ni sube audio ambiental. El modelo español está incluido en el APK con SHA256 fijado.
- Al reconocer la frase, se libera el micrófono local antes de la consulta de voz. Un saludo local precede la captura para Realtime. La pausa de voz finaliza el turno sin pulsar botones. La música y ruido fuerte requieren comprobación acústica en el equipo.
- La consulta usa `gpt-realtime-2.1-mini` y la pantalla accesible vigente. Los cambios de app actualizan o limpian el contexto sin cerrar el micrófono. Un control obsoleto no puede conservar su señalador.
- La pantalla principal es de configuración. La asistencia normal usa voz, una burbuja opcional y el señalador; no contiene un chat ni un catálogo de pasos. Los módulos de familia conservan interfaces específicas.
- Las pantallas sensibles no se envían: TINO puede conversar sin leerlas ni señalar contraseñas, códigos o controles privados. Si la tablet está bloqueada, la respuesta local solicita desbloquearla.

El comportamiento se apoya en el [servicio de interacción de voz de Android](https://developer.android.com/reference/android/service/voice/VoiceInteractionService) y en las [restricciones de servicios de micrófono](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start). La selección del asistente no convierte una aplicación de terceros en un componente del sistema con los privilegios de Siri. Las condiciones de batería, bloqueo y reinicio deben verificarse en la tablet objetivo.

## Servicio de producción

Backend: https://tino-api-labitconf.vercel.app. Proyecto Vercel `tino-api-labitconf`, raíz `apps/api`, Node.js 24. El endpoint `/api/health` responde JSON con almacenamiento `vercel_blob_private_cas`, modelo exacto configurado y OpenArg configurado.

El almacenamiento es Blob privado con lecturas al origen y escrituras CAS mediante ETag. Las lecturas solicitan `Accept-Encoding: identity` para conservar el ETag fuerte: en la prueba remota la compresión generaba un validador débil incompatible con las escrituras condicionales. No usa el JSON efímero de una Function. La admisión de teléfonos, presupuesto y leases se comparten entre instancias: hasta ocho conexiones globales, dos por IP y una por dispositivo. Cada sesión cloud se limita a 270 segundos; el cliente puede volver a conectar, y la escucha local no necesita una conexión permanente al proveedor.

Las claves OpenAI y OpenArg están como secretos del servidor. Se creó una credencial institucional nueva para producción; no se copió la credencial institucional local. El APK no contiene claves del proveedor. El acceso de la tablet se autoriza con un código de un solo uso y conserva su credencial cifrada en Android Keystore.

Los accesos `0000`, OTP simulados, interceptación local de consultas y respuestas prefijadas están deshabilitados en el backend cloud. Las capacidades de adjuntos no habilitadas responden con error explícito.

## Compilación y firma

`scripts/android-release.ps1` compila pruebas, firma el APK release e incorpora la dirección HTTPS de producción. La variante tiene `applicationId` `org.nieto.assistant.tino`, versión `0.7.0-tino`, código 8, SDK objetivo 36 y tráfico HTTP deshabilitado.

La clave de firma y su contraseña están exclusivamente en archivos ignorados de `.cache/signing` y `.cache/secrets`. Es necesario conservar su respaldo seguro para publicar futuras actualizaciones con la misma identidad. Nunca deben subirse al repositorio, al APK o a Vercel.

## Verificación realizada y pendiente

- Identificación de la tablet por ADB: SM-X820, Android 16, One UI 8.5.
- Android integrado con el trabajo del equipo: 84 pruebas unitarias aprobadas y compilación release firmada correcta.
- Backend integrado: 134 pruebas aprobadas, con persistencia, concurrencia, revocación y desactivación de modos simulados.
- Dependencias npm de producción: cero vulnerabilidades después de fijar Zod 4.6.6.
- HTTPS público: health 200 con el almacenamiento y modelo esperados, acceso privado sin credencial 401 y `.env` 404.
- HTTP y WebSocket canónicos sin credencial rechazan con 401. Una conversación remota real verificó `gpt-realtime-2.1-mini` y respondió «Conexión lista». El dispositivo de verificación fue revocado y esa revocación persistió después de volver a desplegar. El endpoint de diagnóstico temporal se retiró. Deployment comprobado: `dpl_79kfexi9ktk7h5yfZbhFdjPjoRZP`.
- La tablet se vinculó mediante un código de un solo uso. TINO está seleccionado como asistente predeterminado; permisos de micrófono, notificaciones y accesibilidad configurados.
- Instalación de la variante release y su verificador nativo en la Tab S10+ correctas.
- Prueba de ASR real en la tablet: tres comprobaciones con muestras sintéticas aprobadas (frase de activación, rechazo de frase ajena y reconocimiento de consulta). No demuestra precisión con la voz del usuario ni comportamiento durante un reinicio.

El resultado final de APK, ASR nativo, conversación remota, señalador sobre apps y restauración tras reiniciar se incorpora después de ejecutar esas comprobaciones en la tablet. No se considera una validación de producción completa solo porque compilen el APK o el sitio web.
