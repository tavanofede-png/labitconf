# NIETO Android · Labitconf

Aplicación **nueva**, Kotlin + Jetpack Compose. Identificador `org.nieto.assistant`; no reemplaza otra instalación del proyecto anterior. Android 8+ (API 26), compile/target 36. Versión `0.2.0-realtime` (versionCode 2). Conversación con voz streaming de OpenAI Realtime y lectura nativa Android para las instrucciones del motor de guías. Sin grabación persistente ni escucha en segundo plano.

## Compilar e instalar

Desde la raíz:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/android-build.ps1
# Caché ya disponible, sin descargar dependencias:
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/android-build.ps1 -Offline
# Instalar después de compilar y probar:
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/android-build.ps1 -Offline -Install
```

El script usa `JAVA_HOME` y `ANDROID_HOME`; en esta máquina puede descubrir herramientas instaladas previamente. Reutiliza exclusivamente JDK/SDK/Gradle wrapper/cachés, ningún código ni credencial de la app anterior. Todo el estado de compilación nuevo queda dentro del workspace. Se requiere JDK 17+, Android platform 36 y build-tools 35.0.0.

APK: `app/build/outputs/apk/debug/app-debug.apk`. Después de compilar y pasar los tests, el script actualiza atómicamente `artifacts/nieto-debug.apk` y comprueba su SHA256. Pruebas: `:app:testDebugUnitTest`; cubren transiciones del motor, confirmación, bloqueo de pantalla ajena/sensible/versión desconocida, cancelación y práctica autónoma, además del formato PCM y el descarte de turnos de voz cancelados. No sustituyen verificar captura/reproducción en el teléfono.

## Conectar con la API

En **Ajustes → Conexión del prototipo**, ingresá la URL del servidor sin `/api`. `Guardar y conectar` crea una instalación seudónima real mediante `/api/devices`. El token se cifra con AES-GCM y Android Keystore; no se muestra ni se incluye en el APK. Un cambio de servidor descarta las credenciales locales de la dirección anterior. Las copias de seguridad Android están desactivadas.

- Emulador: `http://10.0.2.2:8787`.
- USB: ejecutar `adb reverse tcp:8787 tcp:8787` y configurar `http://127.0.0.1:8787`.
- Wi-Fi: servidor accesible por su IP LAN y puerto 8787.

HTTP/WS se permite **solo en el build debug**. Release requiere HTTPS/WSS. No se incorpora ninguna clave OpenARG ni OpenAI: se consume únicamente la API de NIETO. El backend debe tener `OPENAI_API_KEY` configurada para la conversación.

## Voz y chat con Realtime

Antes de la primera consulta, NIETO solicita consentimiento separado para enviar voz y texto a OpenAI mediante el servidor del equipo. Se puede revocar desde Ajustes. Las guías locales y Familia no dependen de ese permiso.

`Hablar con NIETO` abre un WebSocket hacia `/api/assistant/realtime` con la credencial NIETO del dispositivo en un header de autorización. El micrófono comienza solamente tras el permiso Android y el evento `ready` del servidor. Captura PCM16 little-endian, 24 kHz, mono, en bloques de 100 ms; el audio se envía mientras se habla. No hay SpeechRecognizer ni modelo de transcripción adicional en este recorrido.

`Terminar de hablar` apaga el micrófono, termina el envío y pide una respuesta. El límite local y del servidor es 45 segundos de entrada. La respuesta de IA se reproduce incrementalmente mediante AudioTrack, con el texto de salida/transcripción de la respuesta visible. La entrada se presenta como Consulta por voz; no se inventa una transcripción de lo que dijo la persona. Las guías verificadas siguen usando TTS local.

Cancelar, cambiar de pestaña, salir de NIETO o cambiar de servidor apaga la captura/reproducción y cierra el socket. La cola de audio es acotada y los mensajes de turnos anteriores se descartan. Cancelar no retira los bloques que ya llegaron al servidor o a OpenAI. El audio no se escribe en archivos y la conversación vive en memoria. Las consultas de texto usan HTTP o el mismo socket si ya hay una conversación de voz lista; las solicitudes HTTP se cancelan al abandonar el asistente.

La función de IA puede **ofrecer** `whatsapp-location`; no inicia el flujo sin el botón de la persona, no toca controles y no registra resultados. El modelo no ve la pantalla. La interpretación de accesibilidad y la verificación permanecen en el motor local, con los límites descritos a continuación.

## Guía local experimental

Perfil `whatsapp-location-es@1`, `com.whatsapp`, versiones `2.26.*`, idioma español. Este perfil **no acredita una prueba en teléfono real** y no promete compatibilidad universal. Otras versiones, WhatsApp Business, otros idiomas, A/B tests y árboles de accesibilidad diferentes se rechazan con una explicación. Ampliar versiones requiere prueba manual y actualización de perfil/tests.

1. Se inicia una sesión real de API; la persona abre WhatsApp y elige una conversación.
2. La guía reconoce un único control clickeable con etiqueta exacta de adjuntar y un campo de mensaje identificado por su ID; el texto del campo se ignora.
3. Solo avanza cuando aparece un único control `Ubicación` real.
4. Solo avanza cuando aparece `Enviar mi ubicación actual` o `Enviar tu ubicación actual`.
5. NIETO pide confirmación explícita local. La persona debe revisar la conversación destinataria. Hasta confirmar, no marca el control de envío.
6. La persona toca en WhatsApp. NIETO nunca ejecuta clics, escribe ni hace gestos.
7. Solo registra finalización si vuelve a la conversación y aumenta el número de tarjetas visibles de ubicación **salientes con estado enviado/entregado/leído**, dentro de un contenedor de mensaje reconocido. La evidencia permanece local. Si la estructura no permite esa comprobación, dice que no pudo verificar, incluso si la persona realizó el envío correctamente.
8. La práctica vuelve a recorrer el procedimiento sin pistas. Conserva la confirmación de privacidad. Pedir `Repetir` o `Me perdí` durante la práctica elimina el crédito de autonomía de ese intento.

La comprobación por tarjetas visibles es deliberadamente limitada: historial desplazado, controles sin etiqueta, imágenes de mapas no accesibles y estados de envío distintos pueden impedir verificar el resultado. No debe usarse como prueba de entrega universal ni ampliar la compatibilidad sin validación física. No se marca una coordenada aproximada cuando falta un control; duplicados ambiguos también se rechazan.

## Consentimiento y privacidad

**Ajustes → Conocer y activar la guía** muestra una divulgación antes de abrir la autorización de Accesibilidad de Android. El consentimiento local se puede revocar en NIETO; también se puede deshabilitar el servicio desde Android. La observación se realiza solo durante una guía activa, se limita a WhatsApp y termina al cancelar o completar.

Se ignoran campos editables, nodos contraseña, teclados y ventanas fuera de la app. No se hacen capturas, OCR ni análisis remoto de pantalla. El árbol nunca sale del teléfono. Los marcadores solo aparecen sobre WhatsApp. La burbuja usa `TYPE_ACCESSIBILITY_OVERLAY`, no pide permiso de superposición general. No se solicitan permisos de ubicación, cámara, contactos ni SMS.

El chat se mantiene en memoria y envía su texto al endpoint de asistente/OpenAI para obtener respuesta. No debe incluir contraseñas ni códigos privados. La conversación de voz requiere internet y el backend con acceso al modelo exacto `gpt-realtime-2.1-mini`; un error de clave, cuota o modelo se informa y no se presenta como respuesta de IA. La lectura de guías por TTS depende del motor/idioma instalado.

El servidor recibe solo eventos cerrados de progreso, paso y duración (máximo 4 horas). Si un evento falla no se declara sincronizado; queda indicado como pendiente en la UI. El MVP no implementa cola durable ni reintentos tras reinicio. El historial no se infiere a partir de eventos fallidos.

La duración corresponde al primer intento acompañado. La práctica comparte la sesión, registra el resultado autónomo por separado y no suma la espera entre intentos ni agrega tiempo acompañado. Cada evento en memoria conserva el servidor y la credencial de su instalación original: cambiar de conexión nunca reenvía métricas a otra identidad.

## Familia

El familiar crea un grupo en el portal. La persona asistida ingresa su código en **Familia** y acepta granularmente progreso y/o solicitudes de ayuda; ambos están desactivados por defecto. `Pedir ayuda` hace un POST real y solo informa éxito con respuesta del servidor. No realiza llamadas ni sustituye un servicio de emergencias. La revocación elimina el vínculo en servidor. Cambiar permisos requiere revocar y aceptar una invitación nueva.

## Validación física pendiente

Para afirmar el flujo completo, probar un teléfono con WhatsApp compatible: permisos, voz, árbol real, superposición sin cubrir controles, confirmación, nueva tarjeta saliente, práctica autónoma, invitación y alerta visibles en portal. `adb devices` debe mostrar un dispositivo autorizado. La compilación y los tests unitarios no sustituyen esta verificación.
