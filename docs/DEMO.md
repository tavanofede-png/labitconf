# TINO · La tech, de todos.

Demo Android 0.6.0 para LABITCONF. El frontend es nativo (Kotlin + Compose), no una página web dentro de una APK.

## Ingreso de jurados

1. Abrir TINO → **Ya tengo cuenta**.
2. Seleccionar Usuario y escribir **test@labitconf.com**.
3. Solicitar el código e ingresar **000000** en las seis casillas. La verificación es automática y abre directamente el chat, sin onboarding.
4. El menú superior derecho permite pasar a Grupo familiar e Instituciones sin volver a ingresar.

Este acceso es una cuenta ficticia local. No envía correos, no depende del backend ni requiere una clave de IA. Cerrar sesión vuelve a la bienvenida. El resto de las cuentas conserva el flujo del backend.

## Dos recorridos que se pueden probar

Desde el chat, **Aprendé con TINO** abre prácticas interactivas. También se puede escribir “crear un grupo de WhatsApp” o “enviar un mail”.

- **Grupo:** abrir WhatsApp en la práctica → nuevo chat → nuevo grupo → elegir participantes → escribir nombre → revisar → confirmar. Se puede retroceder, repetir la indicación o volver a empezar.
- **Correo:** abrir Correo en la práctica → redactar → destinatario válido → asunto → mensaje → revisión explícita → enviar dentro de la práctica. El resumen conserva exactamente los datos ingresados.

Las prácticas están identificadas en pantalla. **No crean grupos reales ni envían correos fuera de TINO.** El marco verde señala controles reales del ejercicio y los botones responden a las acciones. La voz usa el sintetizador de Android; su disponibilidad depende de las voces instaladas. El dictado depende del servicio de reconocimiento del dispositivo.

## Familia e instituciones

Familia contiene perfiles y actividad ficticios, edad, tablas de asistencia y permisos por persona. Lucía comparte únicamente ubicación; las demás métricas están bloqueadas. Los pings y avisos de voz del perfil Marta pueden verse en TINO Chat en este mismo dispositivo. No simulan entrega a un segundo teléfono. Requieren autorización de ayuda, vencen y no se duplican al reintentar.

Instituciones incluye mapa animado, 25 filtros territoriales y nueve períodos (225 combinaciones), métricas de seis meses y exportación con fechas. La actividad TINO es ficticia. El contexto territorial es un **snapshot verificado de OpenARG**, consultado el 31/10/2026: población del Censo 2022 y conectividad ENACOM, con enlaces y períodos de origen. La versión sin conexión no realiza consultas en vivo ni presenta el mapa como telemetría real.

Los reportes CSV/PDF usan el fixture estable y muestran el rango elegido, tal como se pidió para la demo. No contienen conversaciones ni datos personales reales.

## Guía real sobre otras apps: estado comprobado

La ruta de asistencia sobre WhatsApp/Gmail reales permanece en el proyecto para cuentas normales. Necesita backend disponible, credencial válida del proveedor, consentimiento para la asistencia visual y Accesibilidad habilitada por la persona en Android. No se debe presentar una práctica como evidencia de un envío real.

La última comprobación del proveedor en esta Mac devolvió `invalid_api_key`. No quedó certificado un recorrido externo completo con esa credencial. El permiso de Accesibilidad de la Samsung estaba desactivado al revisarlo. Los tests de protocolo con respuestas simuladas no sustituyen esa prueba real.

## Compilar y verificar

Requiere Node 22+, JDK 17 y SDK Android 36.

```sh
npm ci
npm run check
npm run prepare:jury
apps/android/gradlew -p apps/android :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
```

APK: `artifacts/nieto-debug.apk`; versión/hash/tamaño: `artifacts/android-build.json`. La APK no contiene `.env` ni claves de proveedor. La firma debug de GitHub Actions difiere de una compilación local: Android puede impedir actualizar una instalación con otra firma. No borrar datos para resolverlo sin permiso de la persona.

Pruebas nativas adicionales, sin abrir micrófono ni enviar mensajes externos:

```sh
adb -s SERIAL install -r artifacts/nieto-debug.apk
adb -s SERIAL install -r apps/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s SERIAL shell am instrument -w -e suite jury-demo org.nieto.assistant.test/org.nieto.assistant.presence.VoiceSmokeInstrumentation
adb -s SERIAL shell am instrument -w -e suite family-audio org.nieto.assistant.test/org.nieto.assistant.presence.VoiceSmokeInstrumentation
adb -s SERIAL shell am instrument -w -e suite reports org.nieto.assistant.test/org.nieto.assistant.presence.VoiceSmokeInstrumentation
```

El visor local muestra solamente Android en su ruta principal. Los controles técnicos existen únicamente en `?debug=1` para pruebas. La landing no se despliega desde este workflow. GitHub Actions publica una APK descargable y su SHA-256 después de pasar los checks.
