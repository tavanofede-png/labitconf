# TINO: chat flotante y reportes con fechas

Implementación Android nativa, rama `codex/tino-instituciones`. No se despliega en la landing ni instala en la tablet física.

## Chat flotante

Desde el menú, habilitar **Burbuja flotante** y autorizar superposición de Android. Fuera de TINO, arrastrar la mascota; al soltar se ancla al borde más cercano de los cuatro. Se guarda el borde y una fracción de su longitud, para conservar la posición tras cambios de tamaño. Hay acciones de accesibilidad para fijar cada borde.

Tocar la mascota abre una ventana `TYPE_APPLICATION_OVERLAY` con la misma conversación que `NietoApp`. No abre `MainActivity`. **X** colapsa el chat y conserva el borrador. **Ir a la App** es la única acción del popup que abre la app principal. Se oculta al bloquear el dispositivo, volver a TINO o utilizar la guía accesible existente.

- Texto: usa `/api/assistant/message` y conserva el borrador para reintentar si falla el envío.
- Micrófono: pide autorización mediante una actividad transparente, promueve el servicio a tipo `microphone` mientras esa actividad está visible y transcribe con Vosk español en el dispositivo. El resultado queda editable; no se envía hasta tocar Enviar. La escucha «Hola TINO» se pausa durante el dictado. No se guarda ni se sube audio.
- Clip: selector de Android para PNG/JPEG/WebP/PDF. Se leen solo los archivos seleccionados. El popup se restaura tanto al elegir como al cancelar. Hay vista previa de imágenes, nombre/tamaño y botón para quitar cada adjunto.
- Límites: 3 adjuntos; 2 MiB por archivo; 4 MiB totales. Se valida el formato por firma, MIME y base64 canónico en el backend. No se aceptan URLs remotas ni archivos arbitrarios.
- El backend envía imágenes como `input_image` y PDF como `input_file` de Responses, `store:false`. No escribe los adjuntos en el JSON persistente ni en telemetría. El historial visible conserva nombres y tipos, no los binarios. Los bytes pendientes viven solamente en memoria.
- Los adjuntos se analizan en el turno en que se envían; no se reenvían automáticamente en turnos posteriores. Los nombres que aparecen en el historial no equivalen a volver a entregar el contenido al modelo.

Dependencia externa: una credencial/modelo que responda es necesaria para interpretar imágenes/PDF y para conversación general. Las pruebas usan un adaptador controlado para verificar el transporte; no certifican disponibilidad de la cuenta OpenAI. No se presenta una respuesta prefabricada como análisis de un archivo si el proveedor falla. La prueba real de esta instalación con el PDF ficticio devolvió HTTP 401; no hubo respuesta analizada. Ver `docs/evidence/tino-attachment-provider-probe.json`.

Fuentes técnicas: [Android overlays](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY), [restricciones del micrófono en servicios](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start), [OpenAI file inputs](https://developers.openai.com/api/docs/guides/file-inputs).

## Reportes institucionales

**Exportar reporte · CSV / PDF** abre un calendario de rango con fecha inicial y final; también admite entrada manual. Requiere ambos extremos. Tras seleccionar, se guarda con el selector de documentos de Android, sin pedir permisos de almacenamiento global.

Ambos formatos usan exactamente `apps/android/app/src/main/assets/institution-report-demo-v1.json`: un snapshot fijo nacional, referencia mayo-octubre de 2026. El rango seleccionado aparece en el encabezado del PDF, en todas las filas del CSV y en ambos nombres de archivo. Cambiarlo no recalcula las cifras. La selección de provincia del dashboard tampoco cambia este reporte fijo nacional. El propio reporte lo identifica como datos de demostración.

El PDF se genera en el dispositivo con `PdfDocument`: dos páginas A4, logo TINO, Plus Jakarta Sans y paleta aprobada. Incluye seis indicadores, las diez tareas más pedidas y las 24 jurisdicciones. El CSV usa UTF-8 con BOM, separador punto y coma y celdas entre comillas. Las fechas se interpretan como días UTC del selector para evitar corrimientos por el huso horario argentino.

No requiere un trabajo de exportación remoto ni credenciales institucionales dentro del archivo.

## Validación

- Backend: 59 pruebas, incluidos formato/tamaño/autenticación, payload multimodal y ausencia de bytes en persistencia.
- Android JVM: 16 pruebas, incluidas posiciones de liberación, los cuatro bordes y rotación.
- Prueba Android `VoiceSmokeInstrumentation`, `suite=reports`: PDF de dos páginas, fechas elegidas y cifras CSV iguales para rangos distintos.
- PDF generado por Android extraído y ambas páginas renderizadas/revisadas.
- Calendario nativo: se eligió 02/05/2026 a 08/05/2026; PDF guardado desde la UI con ambas fechas en las dos páginas; CSV guardado desde la UI con 64 filas y el mismo rango.
- Emulador: liberar en el centro ancla al borde, posición conservada al reinstalar, popup sobre Chrome, texto enviado y recibido sin abrir MainActivity, teclado con campo/Enviar visibles.
- Adjuntos en el emulador: PDF y PNG seleccionados desde el selector nativo, popup restaurado automáticamente, miniatura PNG visible y botones para quitar ambos archivos.
- Micrófono: permiso Android aceptado desde el popup, dictado iniciado y detenido sin abrir MainActivity ni producir excepciones nativas. No se certificó una frase acústica real en el emulador.
- Decodificador Vosk en Android: tres comprobaciones con audio sintético aprobadas; no se grabó audio de la computadora.

Las pruebas de contrato con adaptador controlado no se describen como una respuesta real del proveedor de IA. La prueba del decodificador con audio sintético no equivale a validar la acústica de la tablet física.
