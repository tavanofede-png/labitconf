# NIETO 2.0 — implementación local para LABITCONF

La referencia es `NIETO Arquitectura Tecnica Completa.docx`, aportada por el usuario. Es material de diseño: describe una visión más amplia que el MVP. Esta reconstrucción parte de una carpeta nueva y conecta OpenArg desde el servidor, sin copiar credenciales ni dependencias del prototipo anterior.

## Componentes

```text
Android Kotlin + Compose
  ├─ guía WhatsApp local, voz Realtime y TTS de guía, accesibilidad opt-in
  ├─ árbol de interfaz procesado en el teléfono
  └─ token de dispositivo + eventos cerrados
                  │ HTTP local / HTTPS para producción
                  ▼
Node.js API :8787 ─── JSON privado local
  ├─ sesiones, consentimientos, familia, alertas
  ├─ agregados institucionales con cohorte mínima
  ├─ relay WebSocket ─── OpenAI gpt-realtime-2.1-mini
  └─ cliente MCP ─── https://mcp.openarg.org/mcp
                  ▲
                  │ proxy /api
Portal React + TypeScript + Vite :5173
  ├─ acceso familiar por token del grupo
  └─ acceso institucional por token del servidor
```

El árbol de accesibilidad no sale de Android. La API recibe la voz o el texto de las consultas al asistente y los transmite a OpenAI para responder sin guardar conversaciones; no recibe capturas ni mensajes de WhatsApp. La conversación de voz requiere activación y consentimiento explícitos. NIETO no solicita contraseñas ni OTP. El servidor conserva eventos de una sesión guiada para calcular progreso y duración de acompañamiento; no observa el uso completo del teléfono.

## Documento de arquitectura y alcance

| Área | Decisión del MVP | Trabajo posterior |
| --- | --- | --- |
| Android primero | Kotlin y Compose; permisos explícitos del sistema. | Pruebas con más dispositivos, idiomas, fabricantes y usuarios. |
| Comprensión de pantalla | Árbol accesible local y reconocimiento conservador para el perfil experimental `whatsapp-location-es@1`, WhatsApp español `2.26.*`. | Validar ese perfil en un teléfono; más guías y versiones; OCR o visión solo cuando aporte valor y con consentimiento. |
| Acompañamiento | Explicación, resaltado local y verificación del siguiente paso. La persona hace cada acción. | Ampliar cobertura y métricas de aprendizaje validadas. |
| Voz | Streaming PCM24k mediante relay autenticado a OpenAI Realtime; TTS nativo para las instrucciones verificadas. | Validar micrófono, reproducción, interrupciones y redes en el dispositivo real. |
| IA | Conversación de voz/texto con `gpt-realtime-2.1-mini`; guía local como fuente de verdad y contexto público OpenArg en instituciones. | Evaluaciones de calidad, más guías y recuperación de los fallos descritos en la auditoría. |
| Familia | Grupos, invitación de un solo uso con vencimiento, permisos separados para progreso y ayuda, revocación. | Identidad y recuperación de cuenta; notificaciones fuera del portal. |
| Instituciones | Métricas reales de sesiones, selección de las 24 jurisdicciones argentinas por código ISO y supresión bajo el mínimo de cohorte. Android registra instalaciones en CABA (`AR-C`) en esta versión. | Elección de territorio desde Android; roles, auditoría operativa, aislamiento por organización y metodología de indicadores. |
| Mapa | Contexto del territorio con cartografía pública; sin ubicación personal. | Más capas justificadas por datos verificables. |
| OpenArg | MCP oficial en backend; descubrimiento real, consultas autenticadas y evidencia de fuente/período. | Validar datasets concretos con una clave vigente y necesidades de cada institución. |
| Persistencia | JSON local con escrituras serializadas y reemplazo atómico, un proceso API. | PostgreSQL, migraciones, backups, retención y operación concurrente. Room/Redis no son requisitos de esta demo. |
| Negocio | Sin cobros ni suscripción activa. | El plan comercial del documento queda fuera de la implementación. |
| Otras plataformas | Android y portal web. | iOS, palabra de activación y funciones predictivas quedan pendientes. |

## Límites de privacidad y de confianza

- Cada instalación tiene una credencial propia. Una sesión solo se consulta o modifica por su titular.
- La persona acepta el vínculo familiar en Android. Progreso y ayuda son permisos independientes. Sin permiso de progreso se omiten sus métricas en la respuesta familiar.
- Las instituciones reciben agregados cuando la cohorte alcanza el mínimo configurado. Un territorio con menos participantes no habilita desgloses de flujos.
- La clave OpenArg no aparece en Android, en el bundle web, en la persistencia ni en las respuestas. El cliente tiene un endpoint oficial fijo para no desviar ese secreto.
- Las fuentes y el período del dato pertenecen a la respuesta pública. Una fecha de consulta no se presenta como actualización del dataset. Si el MCP no aporta una cifra verificable, no se fabrica una.

Los tokens portadores no equivalen a una plataforma de identidad empresarial. El prototipo no ofrece garantía de anonimización fuerte, análisis estadístico de reidentificación ni certificación regulatoria. El mínimo de cohorte es una restricción concreta de respuesta, no una declaración de cumplimiento legal.

## Verificación

`npm run check` valida TypeScript, ejecuta pruebas API aisladas y compila el portal. Los tests del backend usan estado temporal; no deben cargar el archivo de una demo real. `npm run verify:openarg` comprueba el protocolo contra el endpoint oficial y, con clave vigente, la consulta autenticada. No modifica datos de NIETO. Android usa `:app:testDebugUnitTest :app:assembleDebug`; `scripts/android-build.ps1` detecta el JDK y SDK locales en Windows.

La prueba de extremo a extremo de la guía requiere un teléfono con WhatsApp y permisos habilitados. La posición del resaltado, el idioma, los cambios de interfaz y la recuperación al perder el paso necesitan ensayo manual. Si una pantalla no coincide, el comportamiento esperado es pedir ayuda o detenerse; no adivinar coordenadas.

Las verificaciones ejecutadas y los requisitos de la presentación se registran por separado: [DEMO.md](DEMO.md) y la [evidencia MCP](../apps/api/docs/OPENARG-VERIFICATION.md). No se considera completada una integración autenticada por el solo hecho de que `initialize` o `tools/list` responda.
