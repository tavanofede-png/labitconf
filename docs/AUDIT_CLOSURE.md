# Cierre de los 11 hallazgos

Fecha: 31 de octubre de 2026. Entrega: **0.4.0-labitconf**, código Android **4**. Se hizo `git pull --ff-only origin main`: `main` estaba actualizado. Se conservaron los cambios locales de asistencia de pantalla y Familia nativa. El [informe original](AUDIT-2026-10-30.md) corresponde a `059e0cc` y se mantiene como evidencia histórica.

Los once defectos identificados tienen corrección y comprobación de software. Esto no acredita compatibilidad con un teléfono o versión de WhatsApp sin ensayarlos físicamente, ni completa los requisitos de producción del informe original.

| ID | Estado | Corrección y evidencia |
| --- | --- | --- |
| A01 | Corregido | Se eliminó el contador de tarjetas como prueba de envío. Solo un botón explícito registra `flow_reported` o `practice_reported`, con `self_reported`. No hay autoacreditación ni autonomía. Historial antiguo, cambio de chat y scroll invalidan confirmaciones locales; pruebas del motor cubren esos casos. |
| A02 | Corregido | Cada métrica, paso, flujo y ventana exige su propia cohorte de contribuyentes efectivos con consentimiento institucional vigente. Altas vacías, falta de consentimiento, otro territorio y registros fuera de período no desbloquean valores. Pruebas `institution.test.mjs` y API. |
| A03 | Corregido | Solicitudes canceladas al cambiar identidad y respuestas vinculadas al token original. La invitación de A nunca se muestra en B, incluso si HTTP ignora la cancelación. Regresión con React DOM real en `apps/web/test/regression.test.mjs`. |
| A04 | Corregido | Retrocesos y «Me perdí» reconstruyen el paso desde controles reconocidos; volver atrás invalida todos los pasos y autorizaciones posteriores. Cambiar conversación o salir de WhatsApp exige reconfirmar. Pruebas del motor y protocolo. |
| A05 | Corregido | Outbox cifrada con Android Keystore y escritura atómica, fuera del backup. Conserva sesión, UUID de evento, servidor y credencial de origen; reintenta con espera y recupera tras reinicio. Solo elimina tras `{ok:true}`; pausa rechazos y permite reintento explícito. Creación/eventos idempotentes en servidor. Pruebas de reinicio, corte, acuse perdido, identidad, corrupción y deduplicación. |
| A06 | Corregido | El servidor exige inicio, pasos mostrados/completados en orden e intentos de práctica independientes. Congela duración al reporte; nuevos eventos no pueden ampliarla. Rechaza `flow_completed` y `autonomy_completed` mientras no exista verificador semántico. Reenvíos idénticos no duplican; un UUID con distinto contenido se rechaza. |
| A07 | Corregido | TTS prioriza avisos de bloqueo frente a la instrucción anterior, deduplica avisos repetidos y refleja idioma/engine/errores disponibles. Pruebas de política de anuncios; permisos, voz y disponibilidad reales requieren teléfono. |
| A08 | Corregido | Sondeo cada 30 segundos al estar visible y al volver al portal; fecha de actualización visible. Exportar consulta un snapshot nuevo y descarta respuestas antiguas o de otra identidad. Tres regresiones web verifican frescura, exportación y limpieza de temporizadores. |
| A09 | Corregido | Única salida `artifacts/nieto-debug.apk` con manifiesto de versión y SHA-256. Publicación local atómica; API valida el hash y descarga desde el mismo descriptor verificado, sin recurrir a copias de Gradle. Pruebas de APK obsoleto/corrupto y cliente desconectado. Release pública versionada con APK, manifiesto y checksum. |
| A10 | Corregido | Eliminada la clasificación por palabras y retirada `offer_guided_flow` del agente. Solo puede señalar controles actuales; el chat no redirige a una guía local ni devuelve `flowId`. Pruebas fuerzan ofertas upstream ante negación, cancelación y otra tarea: se rechazan sin crear sesiones ni emitir ofertas. La guía local sigue disponible por acción explícita en Android. |
| A11 | Corregido | Estado de transporte MCP separado del error de consulta. Una recuperación de `discover` limpia el fallo de transporte; no oculta cuota o credenciales. Un valor cacheado tampoco acredita recuperación de consulta. Pruebas de desconexión, reconexión, cuota y caché; portal muestra ambos estados por separado. |

## Validación ejecutada

- **65 pruebas API**, **3 regresiones web** con React DOM y **37 pruebas Android**: **105 pruebas**, sin fallos.
- TypeScript y build web aprobados. `npm run check` incluye las regresiones web. El build mantiene el aviso conocido de tamaño de MapLibre; no hubo error de compilación.
- Integración motor real de Android → API local: **43 eventos** aceptados y **43 reenvíos** identificados como duplicados; guía de **42 segundos** congelada y prácticas separadas de **17 segundos**, sin autonomía acreditada. No usó proveedores ni datos personales.
- Descarga real del APK desde la API local: versión y ETag coinciden con el SHA-256 del manifiesto y del archivo compilado.
- Revisión independiente de grafo, outbox, avisos, distribución y regresiones web; se corrigieron los problemas encontrados durante esa revisión.
- Se revisaron 139 archivos de fuentes/build web y las 133 entradas no vacías del APK: no contienen las tres credenciales configuradas en el `.env` local. `.env`, datos privados, cachés y binarios permanecen excluidos de Git; la release publica solo APK, manifiesto sin secretos y checksum.
- Las pruebas de esta fase usaron servicios simulados o locales y no consumieron OpenAI/OpenArg. La evidencia live previa se conserva en [MVP_FUNCTIONAL_SCOPE.md](MVP_FUNCTIONAL_SCOPE.md).

## Distribución

- [Release v0.4.0-labitconf](https://github.com/tavanofede-png/labitconf/releases/tag/v0.4.0-labitconf).
- [Descargar APK](https://github.com/tavanofede-png/labitconf/releases/download/v0.4.0-labitconf/nieto-debug.apk).
- [SHA256SUMS](https://github.com/tavanofede-png/labitconf/releases/download/v0.4.0-labitconf/SHA256SUMS.txt).
- SHA-256 APK: `b6072c3cc3139be6e873edb7373978c6b57edb0a9468e5dfc745ef0165b7f562`.

## Límites que permanecen

Enviar una ubicación y aprender a hacerlo **no se consideran verificados**: el MVP registra el reporte de la persona. Un verificador futuro requiere evidencia semántica de conversación y mensaje probada en dispositivos reales. Los registros anteriores se presentan como `legacy_unverified`; no se convierten en resultados verificados.

La cohorte no demuestra identidad humana única ni garantiza privacidad diferencial o resistencia a altas coordinadas. Continúan pendientes identidad, despliegue HTTPS, operación de producción y evaluación de impacto. Los avisos familiares son periódicos, sujetos a las restricciones de Android.

No hay un teléfono autorizado conectado. Falta comprobar físicamente WhatsApp, pantalla, micrófono, TTS, notificaciones y pérdida/recuperación de red. Esa comprobación se informa como pendiente y no se sustituye con los tests de lógica.
