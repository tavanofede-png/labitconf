# TINO en Galaxy Tab S10+ · aplicación nativa

## Objetivo y alcance

TINO debe responder a «Hola Tino» desde la aplicación que la persona esté usando, sin abrir un chat, cambiar a una pantalla de TINO ni activar el micrófono mediante un botón en cada consulta. La persona realiza las acciones; TINO conversa y señala controles visibles. El alcance es todo el teléfono: WhatsApp y Gmail son pruebas, y Mi Argentina, ARCA, Ajustes y otras aplicaciones usan la misma observación genérica, sin un catálogo de recorridos admitidos.

Equipo conectado para esta entrega: Samsung Galaxy Tab S10+ Wi-Fi, modelo SM-X820, Android 16/API 36 y One UI 8.5. La configuración inicial de permisos y asistente predeterminado se realiza una vez. Android puede restaurar el servicio al enlazar el asistente después del reinicio y primer desbloqueo; la disponibilidad antes del primer desbloqueo no se acredita con esta implementación.

## Implementación

- `TinoVoiceInteractionService` se registra como asistente predeterminado seleccionable por Android. Su `onReady` restaura la elección persistida de escucha. No se inicia un micrófono desde un receptor genérico de arranque.
- `WakeWordService` mantiene el servicio de micrófono y su notificación con Detener. Vosk reconoce «Hola Tino» localmente; no guarda ni sube audio ambiental. El modelo español está incluido en el APK con SHA256 fijado.
- Al reconocer la frase, se libera el micrófono local antes de la consulta de voz. Un saludo local precede la captura para Realtime. La pausa de voz finaliza el turno sin pulsar botones. La música y ruido fuerte requieren comprobación acústica en el equipo.
- La consulta usa `gpt-realtime-2.1-mini` y la pantalla accesible vigente. El objetivo pertenece al pedido de la persona, no a la app visible. TINO puede explicar cómo salir a Inicio, abrir otra aplicación y continuar allí. La navegación observada distingue botones, gestos o modo desconocido; no inventa coordenadas de Inicio/Atrás.
- Los cambios de app actualizan o limpian el contexto en silencio, sin cerrar la entrada de voz ni generar una respuesta automática. Un control obsoleto no puede conservar su señalador. Cada nueva consulta requiere «Hola Tino» o el control de voz; se retiró la captura automática de respuestas para evitar recoger conversaciones cercanas.
- La pantalla principal es de configuración. La asistencia normal usa voz, una burbuja opcional y el señalador; no contiene un chat ni un catálogo de pasos. Los módulos de familia conservan interfaces específicas.
- La protección se aplica a los datos, no al nombre de la aplicación. Contraseñas, códigos, campos editables y nodos marcados privados no aportan sus valores; los controles vecinos seguros siguen disponibles. TINO no anuncia «pantalla sensible». Las ventanas protegidas por Android no se capturan por otra vía; la conversación y la orientación verbal siguen disponibles. Si la tablet está bloqueada, una invocación puede pedir desbloquearla.

El comportamiento se apoya en el [servicio de interacción de voz de Android](https://developer.android.com/reference/android/service/voice/VoiceInteractionService) y en las [restricciones de servicios de micrófono](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start). La selección del asistente no convierte una aplicación de terceros en un componente del sistema con los privilegios de Siri. Las condiciones de batería, bloqueo y reinicio deben verificarse en la tablet objetivo.

## Servicio de producción

Backend: https://tino-api-labitconf.vercel.app. Proyecto Vercel `tino-api-labitconf`, raíz `apps/api`, Node.js 24. El endpoint `/api/health` responde JSON con almacenamiento `vercel_blob_private_cas`, modelo exacto configurado y OpenArg configurado.

El almacenamiento es Blob privado con lecturas al origen y escrituras CAS mediante ETag. Las lecturas solicitan `Accept-Encoding: identity` para conservar el ETag fuerte: en la prueba remota la compresión generaba un validador débil incompatible con las escrituras condicionales. La admisión y los leases se comparten entre instancias: hasta ocho conexiones globales, dos por IP y una por dispositivo.

El titular autorizó expresamente el consumo continuo y su costo. Producción tiene `CONTINUOUS_ASSISTANCE=1`: no aplica topes diarios propios ni el corte anterior de 20 turnos o 60 pantallas. Las conexiones cloud rotan por duración técnica (aviso a los 225 segundos, cierre normal a los 270); durante una conversación activa Android puede conservar un resumen acotado exclusivamente en RAM para completar esa rotación. No conserva permisos para confirmar pagos o envíos de una conexión anterior.

El comportamiento solicitado después de la prueba física es un reinicio de conversación tras 30 segundos sin interacción: se cierra el WebSocket, se destruye la sesión del proveedor y se borran el objetivo, la respuesta y el señalador. Cada acción real de la persona en pantalla reinicia ese plazo: toques, escritura, desplazamientos y cambio de aplicación. Las observaciones pasivas, notificaciones y relojes no cuentan como acciones. Una acción posterior al vencimiento no recupera la tarea descartada.

La próxima invocación comienza desde cero, sin historial. «Terminar conversación» hace ese borrado inmediatamente y vuelve a esperar «Hola Tino»; «Apagar TINO» conserva una elección separada para deshabilitar el micrófono por completo. El cierre también debe respetar expresiones de finalización o cancelación por voz y evidencia de haber completado el objetivo, sin generar una despedida automática ni continuar narrando. La evaluación de finalización en pantalla es silenciosa. Las condiciones, saldo y límites de OpenAI siguen vigentes.

Las claves OpenAI y OpenArg están como secretos del servidor. Se creó una credencial institucional nueva para producción; no se copió la credencial institucional local. El APK no contiene claves del proveedor. El acceso de la tablet se autoriza con un código de un solo uso y conserva su credencial cifrada en Android Keystore.

Los accesos `0000`, OTP simulados, interceptación local de consultas y respuestas prefijadas están deshabilitados en el backend cloud. Las capacidades de adjuntos no habilitadas responden con error explícito.

## Compilación y firma

`scripts/android-release.ps1` compila pruebas, firma el APK release e incorpora la dirección HTTPS de producción. La variante tiene `applicationId` `org.nieto.assistant.tino`, versión `0.7.0-tino`, código 8, SDK objetivo 36 y tráfico HTTP deshabilitado.

La clave de firma y su contraseña están exclusivamente en archivos ignorados de `.cache/signing` y `.cache/secrets`. Es necesario conservar su respaldo seguro para publicar futuras actualizaciones con la misma identidad. Nunca deben subirse al repositorio, al APK o a Vercel.

## Verificación realizada y pendiente

- Identificación de la tablet por ADB: SM-X820, Android 16, One UI 8.5.
- Android instalado: 119 pruebas unitarias aprobadas, incluidas continuidad, silencio ante fallos pasivos, navegación, rótulos de formularios y getters de campos privados que fallan si se intentan leer. Compilación release firmada e instalación correctas. Los cambios posteriores de cierre vocal y acciones en pantalla requieren la compilación y prueba física final.
- Backend: 190 pruebas aprobadas. Incluyen 40 turnos y 80 snapshots silenciosos sin los cortes anteriores, admisión continua autorizada, rótulos de formularios, acciones que reinician los 30 segundos y comprobación silenciosa de tarea completada con protección contra respuestas tardías.
- Dependencias npm de producción: cero vulnerabilidades después de fijar Zod 4.6.6.
- HTTPS público: health 200 con el almacenamiento y modelo esperados, acceso privado sin credencial 401 y `.env` 404.
- HTTP y WebSocket canónicos sin credencial rechazan con 401. Una conversación remota real verificó `gpt-realtime-2.1-mini` y respondió «Conexión lista». El dispositivo de verificación fue revocado y esa revocación persistió después de volver a desplegar. El endpoint de diagnóstico temporal se retiró. Deployment de la prueba: `dpl_79kfexi9ktk7h5yfZbhFdjPjoRZP`. El push integrado `da2c698` disparó posteriormente los despliegues Git de producción `dpl_2tXYLGvXxUX8QuuEf1AED1BKiwgY` (API) y `dpl_3NzXhyj9FV5CJNGS5nPhCNEthHBd` (web), ambos READY.
- Actualización de continuidad y observación universal desplegada: `dpl_2rPEPkhNS2YvofTq5p1JxhCK2ZH7`, READY. Una prueba real de dos conexiones pidió cambiar el brillo estando en WhatsApp y retomó el objetivo en Ajustes después de renovar. El modelo conservó el objetivo de brillo del sistema, sin limitarlo a WhatsApp. Se verificó `renewable:true`, rotación 225/270 segundos y revocación posterior del dispositivo de prueba. La prueba no usó audio ni pantallas personales y no guardó el resumen.
- Borrado por inactividad actualizado en `e4298a5`, deployment Git API `dpl_AgB8E1sA3rPtY6eb4DTAN6ucvTGS`, READY. Preflight HTTPS y WebSocket aprobado sin usar credenciales ni abrir el proveedor. La reanudación anterior sólo sigue vigente para rotaciones técnicas durante una conversación activa; al terminar o vencer los 30 segundos, se descarta todo el diálogo.
- Actualización final de actividad y cierre: `b94ac19`, deployment Git API `dpl_3P4o1Hvo5FEMrui4hDEkj3d3VBwS`, READY. Una comprobación real del modelo con una pantalla artificial confirmó cierre `task_completed` y contexto vacío, sin ninguna salida de voz, texto o señalador. La evaluación se realizó fuera de la conversación del proveedor, con metadatos propios y sin guardar las pantallas. El dispositivo de prueba quedó revocado.
- La tablet se vinculó mediante un código de un solo uso. TINO está seleccionado como asistente predeterminado; permisos de micrófono, notificaciones y accesibilidad configurados.
- Instalación de la variante release y su verificador nativo en la Tab S10+ correctas.
- Prueba de ASR real en la tablet: tres comprobaciones con muestras sintéticas aprobadas (frase de activación, rechazo de frase ajena y reconocimiento de consulta). No demuestra precisión con la voz del usuario ni comportamiento durante un reinicio.

La tablet inició una conexión Realtime autenticada en segundo plano durante la prueba manual. El usuario confirmó respuestas habladas y detectó las restricciones de app y de mensajes que motivaron esta actualización. Se realizó un reinicio real (contador Android 13→14): después del primer desbloqueo, sin abrir TINO, Android restauró Accesibilidad, el asistente predeterminado y actividad de micrófono durante 8,55 segundos. Luego el registro mostró `STOP_WAKE` desde la notificación; el usuario confirmó haber pulsado «Detener escucha» para evitar respuestas a conversaciones ajenas. Esa prueba motivó separar terminar conversación de apagar el activador y retirar la captura automática. La validación física de esos últimos ajustes y del señalador se registra al completar la instalación actualizada.

APK firmado instalado: 91391578 bytes, SHA256 `72a6a07ee540d59e7d14ea3efc0fc1ba90407b8b4037ae59ea910f7cb7c0c9ef`. La firma usa APK v2 y RSA de 3072 bits; la clave privada no forma parte del APK. Las tres pruebas de ASR nativo sintético volvieron a aprobarse en la tablet, ahora con activación final exacta y confianza suficiente en ambas palabras. El micrófono local se observó activo mientras la persona usaba WhatsApp.
