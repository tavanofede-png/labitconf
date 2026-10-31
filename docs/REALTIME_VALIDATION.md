# Validación de OpenAI Realtime en NIETO

Integración del modelo solicitado `gpt-realtime-2.1-mini`, realizada después de la auditoría del commit `059e0cc`. La clave fue ingresada por el usuario en `.env`; ninguna herramienta la imprimió y el archivo continúa excluido de Git. No se sustituyó el modelo por otro ni se creó una clave adicional.

## Comprobaciones con OpenAI real

1. **Texto:** `RealtimeService.reply` recibió una solicitud de prueba y el modelo respondió «Conexión lista.» con 15 caracteres.
2. **Audio de salida:** `npm run verify:realtime --workspace @nieto/api` obtuvo una respuesta completa con 62.400 bytes de audio PCM y su transcripción. La prueba duró aproximadamente 2,6 segundos; no representa un compromiso de latencia.
3. **Relay autenticado de NIETO:** se inició un servidor local con estado temporal y una instalación de prueba. Una conexión WebSocket autenticada con su credencial NIETO envió texto y recibió 64.800 bytes PCM. Ese audio generado se devolvió como entrada por bloques `audio.append`; el siguiente turno recibió 81.600 bytes de audio y texto y terminó con estado `completed`. No se activó ningún micrófono ni se usaron conversaciones o ubicaciones personales. El servidor y su estado temporal se eliminaron al terminar.
4. **Negación:** ante «No quiero compartir mi ubicación. No me ofrezcas esa guía», el modelo respondió en modo conversación sin `flowId`.
5. **Sugerencia permitida:** ante un pedido afirmativo para aprender a compartir ubicación, el modelo invocó la oferta permitida y devolvió `whatsapp-location`. La guía no se inició y no se ejecutó ninguna acción externa. Estas dos pruebas no equivalen a una evaluación exhaustiva del comportamiento del modelo.

La integración diferencia `configured` de una verificación real. Un problema futuro de clave, cuota, acceso al modelo o conexión devuelve un error explícito. No hay respuesta fija que se presente como si proviniera de la IA.

## Pruebas de software

Resultado final: **38 pruebas API y 10 pruebas Android aprobadas**, sin fallos. También pasaron TypeScript y el build web. `npm audit` no reportó vulnerabilidades en las dependencias npm instaladas.

Las pruebas de backend usan upstream simulado localmente; no cargan credenciales ni consumen la API. Cubren autorización del dispositivo antes de conectar con OpenAI, cierre de recursos, límites de tamaño y duración, cancelación, filtrado de respuestas antiguas, lista de herramientas permitidas, errores redactados y ausencia de conversaciones o claves en persistencia.

Las comprobaciones Android incluyen el formato PCM, las URL de transporte, límites y aislamiento de turnos cancelados. La captura y reproducción físicas no pueden verificarse mediante estas pruebas de lógica.

## APK compilado

- Versión: `0.2.0-realtime`, código `2`, paquete `org.nieto.assistant`.
- Archivo local: `artifacts/nieto-debug.apk`; coincide con `apps/android/app/build/outputs/apk/debug/app-debug.apk`.
- SHA-256: `C529BC194FABB20ED4A271BEC39031F01FE7057D638907B4D29C90FBD4C3248E`.
- El script de compilación actualiza esta copia después de aprobar las pruebas y comprueba ambos hashes. El APK está excluido de Git.
- Se revisaron los archivos de fuente y el build web, y se descomprimieron en memoria las 122 entradas del APK: no contienen las tres credenciales privadas configuradas en el `.env` local.

## Alcance y privacidad

- Voz: Android → relay WebSocket NIETO autenticado → OpenAI Realtime. El modelo responde con audio incremental y transcripción.
- Micrófono: requiere consentimiento OpenAI, permiso Android y una acción explícita. La captura está limitada a 45 segundos; cancelar o pasar la actividad a segundo plano detiene la captura y la conexión.
- El audio no se escribe a archivos. El backend no persiste las consultas ni las respuestas. Las conversaciones WebSocket viven solo en memoria de esa conexión y se descartan al cerrarla. El tratamiento por OpenAI se rige por los controles de su servicio.
- Texto HTTP: la respuesta usa el mismo modelo, pero cada solicitud recibe solamente la consulta actual y no mantiene historial entre peticiones. Si Android conserva una conexión WebSocket disponible, puede enviar el texto por ella y conservar el contexto de esa conexión.
- Pantalla: no se envían imágenes, el árbol de accesibilidad ni conversaciones de WhatsApp. La voz de IA no verifica botones ni tareas; el motor local conserva esa responsabilidad.
- Herramientas: solamente se permite ofrecer la guía local de WhatsApp. El modelo no puede mandar mensajes, compartir ubicación, avisar a familiares ni registrar progreso.

## Validación física pendiente

Instalar el nuevo APK y comprobar permiso/consentimiento, micrófono, volumen, reproducción, interrupción, cambios de red y cierre al bloquear o cambiar de aplicación. También probar una conversación de varios turnos y la sugerencia de guía en el teléfono. No hay un dispositivo autorizado conectado durante esta integración.

Los hallazgos de verificación de envío, privacidad de cohortes, invitaciones tardías, recuperación de pasos y telemetría de [AUDIT.md](AUDIT.md) no quedan resueltos por incorporar un modelo de conversación. La distribución local del APK se actualiza en el script de compilación, pero publicar una release pública es una tarea separada.

## Documentación oficial

- [Modelo GPT-Realtime-2.1 Mini](https://developers.openai.com/api/docs/models/gpt-realtime-2.1-mini)
- [Conversaciones, audio y herramientas](https://developers.openai.com/api/docs/guides/realtime-conversations)
- [Conexión por WebSocket](https://developers.openai.com/api/docs/guides/voice-websockets)
