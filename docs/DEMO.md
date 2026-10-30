# Demo NIETO para LABITCONF

Este guion usa datos producidos por la aplicación y respuestas del MCP oficial. No requiere simular usuarios, progreso, cifras de inclusión ni una consulta autenticada inexistente.

## Preparación

1. Instalá dependencias, creá `.env`, generá el token institucional y configurá una clave OpenArg nueva. Ejecutá `npm run check`.
2. Corré `npm run verify:openarg`. Distinguí el handshake y descubrimiento de herramientas de una consulta de datos autorizada. Si falta clave o cupo, comunicá ese estado y no prometas la parte pública como disponible.
3. Iniciá `npm run dev`. Abrí el portal y comprobá `/api/health`.
4. Compilá e instalá Android con Android Studio. Configurá la URL de la API y verificá conectividad con el teléfono. Habilitá voz y accesibilidad desde el sistema.
5. Usá dispositivos y cuentas de prueba con consentimiento. Para compartir ubicación, elegí un destinatario de prueba y comprobá quién está en la conversación antes de confirmar.

## Recorrido sugerido, 4–6 minutos

**Problema y propuesta.** «Muchas personas necesitan que alguien les explique dónde tocar. NIETO acompaña el aprendizaje en el teléfono: explica un paso, señala el control y espera a que la persona lo haga».

**Teléfono.** Registrá una instalación con nombre de prueba; Android usa CABA como territorio en esta versión. Abrí la guía para compartir ubicación por WhatsApp. Mostrá la explicación de voz y el resaltado en una pantalla que la guía reconozca. Avanzá manualmente. Señalá que el usuario mantiene el control y que NIETO no envía la ubicación por él. Si la versión de WhatsApp no coincide, mostrale al jurado la detención y explicá que ampliar cobertura sigue pendiente.

**Familia.** En el portal, creá un grupo y mostrale el código al teléfono. Aceptá desde Android los permisos elegidos. Solicitá ayuda desde la app; actualizá el portal y mostrá la alerta. Si hay permiso de progreso, mostrale al jurado los eventos generados por esa sesión. Revocá el vínculo si querés demostrar el control de la persona.

**Institución.** Ingresá con el token institucional local. Mostrá el territorio, las fuentes públicas y la fecha o período que entregue OpenArg. Formulá una consulta pública y abrí una fuente. Explicá que OpenArg se integra mediante su MCP y que la clave permanece en el backend.

**Cohorte.** Con menos de cinco instalaciones reales de prueba en el territorio, el resultado esperado es «datos insuficientes» o agregado suprimido. Esa es la demostración del límite de privacidad. No reduzcas el umbral ni cargues identidades ficticias para conseguir un gráfico. Con cinco o más instalaciones consentidas, mostrá las métricas que hayan producido sus sesiones.

**Cierre.** «La guía genera eventos de aprendizaje. La familia ve lo que la persona autoriza. La institución puede combinar agregados permitidos con contexto público trazable de OpenArg».

## Qué afirmar y qué comprobar

| Afirmación | Evidencia necesaria |
| --- | --- |
| «Usamos el MCP oficial de OpenArg» | Handshake, lista de herramientas y llamada real; ver documento de verificación MCP. |
| «Consultamos datos públicos» | Clave vigente y respuesta real con su fuente; no basta `tools/list`. |
| «La guía acompaña esta versión de WhatsApp» | Ejecución en el teléfono concreto, con controles identificados. |
| «La familia recibió una solicitud de ayuda» | Acción explícita desde Android y alerta visible con permiso vigente. |
| «El portal muestra progreso» | Sesiones reales completadas y permiso de progreso aceptado. |
| «Se protegen cohortes pequeñas» | Respuesta institucional suprimida bajo el mínimo; sin desgloses ni identidades. |

## Recuperación durante la demo

- **Sin clave, cuota o conectividad OpenArg:** mostrale al jurado el estado real y la integración de transporte verificada. No reemplaces la respuesta por datos precargados que parezcan en vivo.
- **Sin conexión Android a la API:** revisá URL, red y `adb reverse`. La IP del emulador no sirve para un teléfono físico.
- **WhatsApp no reconocido:** regresá a un paso conocido o cancelá. Las pantallas cambian entre versiones e idiomas.
- **Voz no disponible:** usá el texto visible. El reconocimiento nativo depende de servicios y permisos del dispositivo.
- **Pocos participantes:** presentá la supresión como comportamiento esperado. Las sesiones acompañadas no representan todo el uso del teléfono ni un indicador probado de autonomía poblacional.

La demo local no incluye pagos reales, cuentas empresariales, un modelo de IA general ni certificación de cumplimiento. Esas funciones no deben presentarse como terminadas.
