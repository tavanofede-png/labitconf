# Funcionamiento del MVP acordado

## Asistencia en el celular

La persona pide por voz qué necesita hacer. El agente analiza la pantalla que autorizó compartir y responde con el siguiente paso; el teléfono señala el control correspondiente. La consulta también puede escribirse desde la burbuja y la respuesta puede leerse sin audio. La aplicación muestra la respuesta actual, sin un historial de conversaciones guardado.

La conexión conserva el contexto de la tarea actual para poder continuar después de un toque. Este contexto es temporal, termina al cerrar la asistencia y no se almacena en el servidor. El agente no depende de un catálogo de guías: la guía de WhatsApp anterior permanece como recorrido local opcional.

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

- Cierre de compilación el 31 de octubre de 2026: **65 pruebas API, 3 web y 37 Android aprobadas**, sin fallos. TypeScript y build web aprobados. La revisión independiente de permisos, credenciales, snapshots y límites no dejó bloqueantes de código.
- APK local `artifacts/nieto-debug.apk`, versión `0.4.0-labitconf`, código `4`, paquete `org.nieto.assistant`. SHA-256: `b6072c3cc3139be6e873edb7373978c6b57edb0a9468e5dfc745ef0165b7f562`. Coincide con el APK producido por Gradle; no se incluye en Git.
- OpenAI real recibió una imagen artificial de 12.421 bytes y dos controles, y devolvió un señalamiento válido de Buscar mediante `point_to_control` con el modelo `gpt-realtime-2.1-mini`. No se enviaron capturas personales. Duración observada de esa prueba: 3,4 segundos, sin compromiso de latencia.
- OpenArg real devolvió población de 65 años o más de Salta del Censo 2022 y conectividad provincial de 2026. Cada territorio requiere que sus propios filtros y agregados pasen la verificación.
- El portal se comprobó en navegador con estado sintético aislado: cohortes publicables y suprimidas, valores nulos, datos de seguimiento y vista de 390 píxeles sin desborde. Esos datos de QA no se incorporaron al producto ni a la persistencia normal.
- Las regresiones locales verificaron que una invitación tardía del grupo A no aparezca en el B y que el panorama institucional se actualice cada 30 segundos.
- Falta validación en teléfonos reales: micrófono y actividad transparente, captura/redacción, cambios de pantalla, controles de distintas apps, resaltados, notificaciones con la app cerrada y restricciones del fabricante. Las pruebas de software y el relay real no reemplazan esa comprobación.

El informe [AUDIT.md](AUDIT.md) conserva los hallazgos de la versión `059e0cc`. Esta entrega cambia la asistencia, corrige la cohorte efectiva y las regresiones web mencionadas; el [cierre A01–A11](AUDIT_CLOSURE.md) documenta las correcciones de esta entrega. La guía solo registra reportes explícitos; envío verificado, autonomía y requisitos de producción siguen fuera de lo acreditado.
