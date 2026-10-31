# Demo NIETO para LABITCONF

Este guion usa datos producidos por la aplicación y respuestas del MCP oficial. No requiere simular usuarios, progreso, cifras de inclusión ni una consulta autenticada inexistente.

## Preparación

1. Instalá dependencias y conservá el `.env` local. Configurá el token institucional, `OPENARG_API_KEY` y `OPENAI_API_KEY` según las funciones que vayas a mostrar. Ejecutá `npm run check`.
2. Corré `npm run verify:openarg`. Distinguí el handshake y descubrimiento de herramientas de una consulta de datos autorizada. Si falta clave o cupo, comunicá ese estado y no prometas la parte pública como disponible.
3. Iniciá `npm run dev`. Abrí el portal y comprobá `/api/health`.
4. Compilá e instalá Android con `./scripts/android-build.ps1` o Android Studio. En Conexión del portal generá un código de teléfono con la credencial institucional. Configurá la URL de la API y ese código en Ajustes Android; una instalación anterior también debe activar su acceso a IA. El código vence a los 15 minutos y no es la invitación familiar. Verificá conectividad con el teléfono. Aceptá los permisos de pantalla y OpenAI; el micrófono se requiere solo para hablar.
5. Usá dispositivos y cuentas de prueba con consentimiento. Para compartir ubicación, elegí un destinatario de prueba y comprobá quién está en la conversación antes de confirmar.

## Recorrido sugerido, 4–6 minutos

**Problema y propuesta.** «Muchas personas necesitan que alguien les explique dónde tocar. NIETO acompaña el aprendizaje en el teléfono: explica un paso, señala el control y espera a que la persona lo haga».

**Teléfono.** Conectá una instalación mediante su código, nombre y jurisdicción declarada. Autorizá la asistencia remota de pantalla desde Ajustes. Abrí una app compatible de prueba, tocá la burbuja y pedí ayuda por voz. Mostrá el control señalado y realizá el toque manualmente; el cambio debe invalidar la indicación anterior antes del siguiente paso. Repetí un pedido por escrito con respuesta escrita, sin activar micrófono ni reproducir audio. Comprobá también cambiar contenido manteniendo los mismos botones, interrumpir una explicación y enviar otra consulta mientras hay una respuesta pendiente: el borrador debe conservarse. NIETO mantiene el contexto de esa tarea, sin guardar historial. No muestres contraseñas ni información personal. Si no encuentra controles suficientes, mostrale al jurado el límite real.

**Conversación.** La respuesta usa `gpt-realtime-2.1-mini` y puede reproducirse por voz o leerse. Con permiso remoto, el modelo recibe controles saneados y una captura efímera disponible desde Android 11; nunca ejecuta acciones. Antes de la demo, probá voz, captura, redacción, cambios de pantalla y cancelación en ese teléfono. La prueba real con imagen artificial y los límites están en [MVP_FUNCTIONAL_SCOPE.md](MVP_FUNCTIONAL_SCOPE.md).

**Familia.** En otro teléfono, abrí Familia nativa y creá o recuperá el grupo. Generá una invitación y aceptala desde el teléfono asistido con sus permisos elegidos. Solicitá ayuda y abrí Familia en el otro teléfono para consultar inmediatamente y marcar el pedido atendido. Mostrá la activación de notificaciones; la comprobación en segundo plano es periódica desde 15 minutos y puede retrasarse por Android. No prometas una notificación instantánea en una demo de pocos minutos. Revocá el vínculo para demostrar el control de la persona.

**Institución.** Ingresá con el token institucional local. Mostrá el territorio, las fuentes públicas y la fecha o período que entregue OpenArg. Formulá una consulta pública y abrí una fuente. Explicá que OpenArg se integra mediante su MCP y que la clave permanece en el backend.

**Cohorte.** El consentimiento institucional es independiente y está desactivado por defecto. Cada indicador necesita al menos cinco contribuyentes efectivos que lo hayan autorizado en ese territorio y período. Mostrá las métricas disponibles y los valores suprimidos; no reduzcas el umbral ni cargues identidades ficticias para obtener un gráfico. Uso y dificultades sirven para planificar acompañamientos, sin afirmar autonomía demostrada.

Se corrigió el umbral de instalaciones vacías señalado en la auditoría. El mínimo de cohorte sigue sin constituir una garantía de anonimato frente a todas las comparaciones. La guía local opcional mantiene limitaciones en la evidencia de finalización; revisá [AUDIT.md](AUDIT.md) antes de afirmar que una tarea quedó completada.

**Cierre.** «La guía genera eventos de aprendizaje. La familia ve lo que la persona autoriza. La institución puede combinar agregados permitidos con contexto público trazable de OpenArg».

## Qué afirmar y qué comprobar

| Afirmación | Evidencia necesaria |
| --- | --- |
| «Usamos el MCP oficial de OpenArg» | Handshake, lista de herramientas y llamada real; ver documento de verificación MCP. |
| «Consultamos datos públicos» | Clave vigente y respuesta real con su fuente; no basta `tools/list`. |
| «Conversamos con OpenAI Realtime» | Audio y texto reales del modelo solicitado; pruebas del relay y comprobación física del teléfono. |
| «La guía acompaña esta versión de WhatsApp» | Ejecución en el teléfono concreto, con controles identificados. |
| «La familia recibió una solicitud de ayuda» | Acción explícita desde Android y alerta visible con permiso vigente. |
| «El portal muestra progreso reportado» | Eventos reales, reportes explícitos de la persona y permiso de progreso aceptado; no acreditan envío verificado ni autonomía. |
| «El portal aplica cohortes de contribuyentes efectivos» | Consentimiento institucional vigente y al menos cinco personas por métrica; valores y subgrupos insuficientes permanecen suprimidos. |

## Recuperación durante la demo

- **Sin clave, cuota o conectividad OpenArg:** mostrale al jurado el estado real y la integración de transporte verificada. No reemplaces la respuesta por datos precargados que parezcan en vivo.
- **Sin conexión Android a la API:** revisá URL, red y `adb reverse`. La IP del emulador no sirve para un teléfono físico.
- **WhatsApp no reconocido:** regresá a un paso conocido o cancelá. Las pantallas cambian entre versiones e idiomas.
- **Conversación no disponible:** comprobá clave, cuota y conexión OpenAI. Usá texto si falla el micrófono; también necesita el modelo remoto. Las instrucciones de la guía usan el TTS local del dispositivo.
- **Pocos participantes:** presentá la supresión como comportamiento esperado. Las sesiones acompañadas no representan todo el uso del teléfono ni un indicador probado de autonomía poblacional.

La demo local no incluye pagos reales, cuentas empresariales ni certificación de cumplimiento. Esas funciones no deben presentarse como terminadas.
