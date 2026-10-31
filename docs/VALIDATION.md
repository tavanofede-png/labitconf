# Validación de NIETO 2.0

Verificación realizada el 30 de octubre de 2026. La entrega es un MVP local; no acredita la arquitectura de producción completa.

Este documento registra la validación anterior a incorporar OpenAI Realtime. Las nuevas pruebas, cambios de voz y limitaciones están en [REALTIME_VALIDATION.md](REALTIME_VALIDATION.md). El conteo de 31 pruebas y el hash del APK de esta página corresponden a la versión anterior.

La evolución posterior incorpora asistencia de pantalla, Familia nativa y nuevos datos institucionales. Su evidencia y límites vigentes están en [MVP_FUNCTIONAL_SCOPE.md](MVP_FUNCTIONAL_SCOPE.md).

## Resultados comprobados

- `npm run check`: TypeScript, 24 pruebas API/MCP y compilación web aprobados. El build incluye un worker separado de MapLibre y carga el mapa mediante importación dinámica. Con las siete pruebas Android, son 31 pruebas aprobadas.
- Android: `:app:testDebugUnitTest :app:assembleDebug` finalizó correctamente. Siete pruebas cubren la política de controles y el motor de guía.
- APK debug firmado, aplicación `org.nieto.assistant`, Android mínimo API 26 y objetivo API 36. Solo solicita Internet y micrófono; el servicio de accesibilidad se autoriza por separado.
- La descarga HTTP respondió 200 con `application/vnd.android.package-archive` y 10.843.600 bytes. Su SHA256 coincide con el archivo compilado: `CB6F4E677D7984B4B8B8ADCE44A4B29A3008D1D66409CDACD5213AAC550F1DDC`.
- Instalación de dependencias revisada tras actualizar Vite, MapLibre y Rollup: cero vulnerabilidades reportadas por npm al instalar las versiones finales.
- El build web final fue revisado contra las credenciales configuradas: ninguno de sus archivos JavaScript, HTML o CSS contiene la clave OpenArg ni la credencial institucional.
- Navegador: vistas de escritorio y móvil de 390 px, sin desbordamiento horizontal en el circuito comprobado. El mapa cargó cartografía y controles sin nuevos errores de consola después de configurar su worker.
- Circuito familiar web/API en un servidor y archivo de datos separados: crear grupo, obtener código, registrar dispositivo de prueba, aceptar permisos, solicitar ayuda, identificar quién la pidió, marcarla atendida y revocar el vínculo. Tras revocar desaparecieron el miembro y sus alertas del portal.
- Instituciones: acceso con su credencial de prueba, supresión de la cohorte pequeña, cambio de CABA a Córdoba y consulta deshabilitada cuando falta la clave OpenArg.
- Tras cargar la clave, consulta autenticada desde la interfaz y a través del proxy del portal: catálogo real y dos indicadores históricos de CABA. Se comprobaron el valor en el mapa, las tarjetas, la advertencia de período y los enlaces oficiales. La clave permaneció exclusivamente en backend.
- Revisión independiente del backend: separación de roles y grupos, revocación, omisión de progreso sin permiso y paso de cohorte suprimida con cuatro participantes a publicable con cinco.
- Cierre del runner probado mediante desconexión IPC que ejecuta el mismo procedimiento de cierre que las señales: API e hijos del gestor terminaron sin procesos huérfanos. La señal física de Ctrl+C de Windows no se verificó directamente en el terminal restringido.

Las pruebas de navegador usan un dispositivo de prueba registrado mediante la API. No sustituyen ejecutar el APK en un teléfono.

## Integración externa verificada

El cliente MCP completó contra `https://mcp.openarg.org/mcp` el ciclo `initialize` → `notifications/initialized` → `tools/list`. El servidor de producción publicó seis herramientas reales. Los ensayos locales también verifican JSON, SSE, errores MCP dentro de respuestas HTTP 200, tiempos de espera, cuota, caché y reintentos acotados.

La evidencia y las herramientas descubiertas están en [OPENARG-VERIFICATION.md](../apps/api/docs/OPENARG-VERIFICATION.md). Después de que el usuario configurara `OPENARG_API_KEY`, `npm run verify:openarg` completó también `listar_fuentes` autenticado. Una búsqueda de población a través del portal respondió con ocho datasets y fuentes oficiales.

La exploración de tablas verificó un conjunto oficial de CABA para el Censo 2010. El endpoint territorial consulta su esquema y calcula mediante `agregar_datos` 474.071 personas de 65 años y más y 2.890.151 habitantes totales. La consulta de mayores utiliza veinte filas (cinco edades por dos sexos por dos nacionalidades); el total utiliza solamente cuatro filas de edad `TOTAL`. No se suman simultáneamente edades y totales. Los valores se obtienen en ejecución y no se codifican como respuesta de reemplazo. Las pruebas controlan cambios de esquema, período o cardinalidad antes de permitir publicar un resultado.

La UI mostró ambos valores, su fuente oficial y el período 2010; el mapa exhibió el indicador de mayores como contexto histórico. La lectura de la fuente informada por OpenArg (9 de mayo de 2026) permanece separada del período censal. Otras jurisdicciones no reutilizan estos valores. El catálogo puede incluir coincidencias de otras jurisdicciones y lo advierte explícitamente.

## Verificaciones pendientes en la hackathon

1. Mantener la clave únicamente en `.env`. Si cambia o se agota la cuota, reiniciar y ejecutar `npm run verify:openarg`. Revisar las fuentes y períodos antes de interpretar los resultados; el indicador CABA verificado es histórico, no una estimación de población actual.
2. Instalar el APK en un teléfono autorizado, configurar la dirección de la API y comprobar voz, permisos, burbuja, controles reales y verificación del envío de ubicación.
3. Validar el perfil experimental `whatsapp-location-es@1` con `com.whatsapp` versión `2.26.*` en español. Si no coincide el árbol, la guía debe detenerse y el perfil requiere ajuste con evidencia del dispositivo.
4. Probar invitación, ayuda y revocación desde el APK; repetir el recorrido sin pistas para verificar el registro de autonomía.

La app no incorpora pagos, suscripciones, conversación de IA general, visión remota, analítica del uso total del teléfono, autenticación institucional empresarial ni publicación en tiendas. Estos pendientes están descritos en [IMPLEMENTATION.md](IMPLEMENTATION.md).
