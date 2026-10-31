# Funcionamiento del MVP acordado

## Asistencia en el celular

La persona pide por voz qué necesita hacer. El agente analiza la pantalla que autorizó compartir y responde con el siguiente paso; el teléfono señala el control correspondiente. La consulta también puede escribirse desde la burbuja y la respuesta puede leerse sin audio. La aplicación muestra la respuesta actual, sin un historial de conversaciones guardado.

La conexión conserva el contexto de la tarea actual para poder continuar después de un toque. Este contexto es temporal, termina al cerrar la asistencia y no se almacena en el servidor. La modalidad escrita evita micrófono, reproductor y TTS; se puede elegir voz en la misma conexión. Al interrumpir audio se informa el punto reproducido para eliminar del contexto lo que no se escuchó. El agente no depende de un catálogo de guías: la guía de WhatsApp anterior permanece como recorrido local opcional y termina al activar acompañamiento remoto.

El equipo admite cada teléfono mediante un código de un uso, con vigencia de 15 minutos, generado en Conexión del portal. Las instalaciones anteriores también necesitan activar acceso a IA con un código. Las reservas diarias y la concurrencia se limitan por dispositivo, origen directo y servidor; no son un presupuesto monetario exacto. Ver [admisión y uso](../apps/api/docs/ADMISSION.md).

Android envía controles identificados con sus límites de pantalla y, desde Android 11, una captura JPEG efímera cuando puede sanearla. Android 8–10 aporta controles accesibles. Cada paso del agente se refiere a un `screenId` y `controlId` válidos; el servidor y Android comprueban esos identificadores. Un cambio de pantalla invalida el resaltado anterior. Si una app no expone controles suficientes, no se puede garantizar el señalamiento.

El envío remoto de pantalla exige un consentimiento nuevo y separado de la autorización local anterior. Se excluye el contenido de campos editables y se bloquean contraseñas y pantallas sensibles reconocidas. Un campo permitido puede aportarse como «Campo de texto» con su posición, sin valor, para poder señalarlo. No se guardan capturas ni etiquetas en disco. Este filtro no puede asegurar que detecte toda información sensible; la persona debe poder cancelar y revocar el permiso. NIETO nunca pulsa controles ni acredita que una tarea terminó porque el modelo lo diga.

El protocolo está en [SCREEN_ASSISTANCE_CONTRACT.md](SCREEN_ASSISTANCE_CONTRACT.md).

## Familia fuera del navegador

El mismo APK incluye un acceso familiar nativo. Permite crear o recuperar un grupo, generar invitaciones, consultar los miembros y sus permisos, ver los resúmenes autorizados y marcar pedidos de ayuda atendidos. Guarda la credencial cifrada con Android Keystore, separada de la instalación asistida. No requiere accesibilidad ni micrófono.

Con autorización de notificaciones, WorkManager comprueba pedidos nuevos desde intervalos de 15 minutos. Android puede retrasarlos por batería, Doze o conectividad. Al abrir Familia se consulta inmediatamente. Es una solución nativa de avisos periódicos; no es FCM, push instantáneo ni un servicio de emergencias. Se evita notificar duplicados y se consultan los permisos vigentes. Una revocación remota se conoce en la siguiente consulta. El texto de notificación es genérico.

Cerrar el acceso cancela trabajos y avisos y borra la credencial local. No revoca copias del token ni elimina el grupo del servidor. El [detalle de Familia nativa](../apps/android/app/src/main/java/org/nieto/assistant/family/README.md) documenta almacenamiento, deduplicación y pruebas pendientes.

## Datos institucionales útiles con el MVP

El portal se orienta a decisiones de acompañamiento, con una ventana de 30 días y actualización automática mientras está visible:

| Pregunta | Dato real del MVP | Límite de interpretación |
| --- | --- | --- |
| ¿Hay uso sostenido? | Personas activas, sesiones iniciadas y ventanas de cinco días. | El dispositivo registrado no demuestra una identidad única. |
| ¿Qué conviene practicar en un taller? | Repeticiones y «Me perdí», agregadas por paso cuando hay muestra. | No se registra el texto privado de la consulta ni la pantalla. |
| ¿Cuánto acompañamiento se brinda? | Minutos de sesiones acompañadas. | No es uso total del celular ni tiempo ahorrado. |
| ¿Cómo funciona la ayuda familiar? | Pedidos y tiempo hasta la marca «Atendido». | Esa marca no verifica la resolución del problema. |
| ¿Cuál es el contexto territorial? | Población de 65 años o más del Censo 2022 y conectividad ENACOM por MCP. | Datos públicos con período propio; no prueban efecto de NIETO ni acceso digital específico de mayores. |

El consentimiento institucional es independiente del familiar y está desactivado por defecto. Cada cifra, paso, tipo de sesión y período necesita su propia cohorte mínima de cinco contribuyentes efectivos con consentimiento vigente. Las altas vacías no habilitan los datos de una sola persona. La revocación excluye a esa instalación de los agregados posteriores. Un valor nulo se presenta como suprimido, nunca como cero. No se afirma anonimato frente a todos los ataques de comparación ni privacidad diferencial.

Los indicadores públicos se calculan por MCP desde tablas verificadas, conservando fuente, período y método. Accesos fijos cada 100 hogares puede superar 100 y no equivale a porcentaje de hogares o personas conectadas. Los datos que no se puedan verificar se omiten. La fecha de lectura no cambia la antigüedad del dato.

## Evidencia y validación pendiente

- Entrega 0.5.0 del 31 de octubre de 2026: **78 pruebas API, 10 web y 49 Android aprobadas**, sin fallos. TypeScript y build web aprobados. El [informe de correcciones B01–B09 y R01](AUDIT_FIXES-2026-10-31.md) distingue las comprobaciones de software de los pendientes físicos.
- APK local `artifacts/nieto-debug.apk`, versión `0.5.0-labitconf`, código `5`, paquete `org.nieto.assistant`. El manifiesto `artifacts/android-build.json` y [SHA256SUMS publicado](https://github.com/tavanofede-png/labitconf/releases/download/v0.5.0-labitconf/SHA256SUMS.txt) identifican el binario exacto. El APK no se incluye en Git.
- OpenAI real recibió una imagen artificial de 12.421 bytes y dos controles, y devolvió un señalamiento válido de Buscar mediante `point_to_control` con el modelo `gpt-realtime-2.1-mini`. No se enviaron capturas personales. Duración observada de esa prueba: 3,4 segundos, sin compromiso de latencia.
- OpenArg real devolvió población de 65 años o más de Salta del Censo 2022 y conectividad provincial de 2026. Cada territorio requiere que sus propios filtros y agregados pasen la verificación.
- El portal se comprobó en navegador con estado sintético aislado: cohortes publicables y suprimidas, valores nulos, datos de seguimiento y vista de 390 píxeles sin desborde. Esos datos de QA no se incorporaron al producto ni a la persistencia normal.
- Las regresiones locales verificaron identidad de invitaciones y códigos, actualización institucional, selección territorial durante carga demorada, navegación accesible y verificación MCP después de cerrar acceso.
- La entrega 0.5.0 también verificó contra OpenAI real la alternancia texto/voz en una conexión, la truncación de una respuesta ya generada y el cierre por revocación. OpenArg completó descubrimiento de seis herramientas y una consulta autenticada `listar_fuentes`; no se revalidaron todas las tablas territoriales.
- Falta validación en teléfonos reales: micrófono y actividad transparente, captura/redacción, cambios de pantalla, controles de distintas apps, resaltados, notificaciones con la app cerrada y restricciones del fabricante. Las pruebas de software y el relay real no reemplazan esa comprobación.

El [estado de auditoría vigente](AUDIT.md) enlaza las correcciones B01–B09/R01 de esta entrega y conserva los antecedentes A01–A11. La guía solo registra reportes explícitos; envío verificado, autonomía y requisitos de producción siguen fuera de lo acreditado.
