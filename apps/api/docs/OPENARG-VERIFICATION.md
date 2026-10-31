# Evidencia OpenArg

## Actualización: contexto provincial reciente

El endpoint territorial ahora usa `src/public-indicators.mjs`: población de 65 años y más del Censo 2022 cuando la tabla consultable acredita esa variable, y dos indicadores ENACOM de internet fijo por provincia. La curaduría CABA 2010 descrita más abajo queda como antecedente verificable y pruebas del módulo anterior; ya no es la fuente del endpoint productivo ni se usa como reemplazo de una consulta fallida.

La nueva exploración autenticada se hizo exclusivamente con herramientas de datos (`buscar_datasets`, `describir_tabla`, `agregar_datos` y una consulta de diccionario con `obtener_datos`). No se invocó `consultar_datos_publicos` ni se consumieron preguntas con IA. Las claves permanecieron en el entorno y no se imprimieron.

- ENACOM: `raw.cache_datos_gob_ar_internet_fija_r9bc6ad492a`, columnas `anio`, `trimestre`, `provincia`, `accesos_cada_100_hogares`; y `raw.cache_datos_gob_ar_internet_fija_r24b95882da`, columna `Mbps`. Ambas acreditaron cobertura 2014–2026 y lectura 2026-07-30. Cada consulta selecciona el año final acreditado, el trimestre máximo de esa provincia mediante `agregar_datos`, y exige una sola fila provincial para el valor. No promedia trimestres ni provincias.
- Verificación real del módulo: CABA, 2026 T1, **118,81 accesos por cada 100 hogares** y **359,28 Mbps**; Salta, mismo trimestre, **71,97 accesos por cada 100 hogares** y **165,09 Mbps**. Todos salieron de `agregar_datos`, una fila exacta por indicador. Son evidencia de prueba, no valores de reemplazo en el código. Los accesos no son personas ni un porcentaje de hogares; pueden superar cien.
- Censo 2022: catálogo provincial por radios censales, columnas `cod_prov`, `cod_variable`, `cod_categoria`, `categoria`, `cantidad`. Se verificaron los grupos quinquenales de `PERSONA_EDADQUI` desde `65 A 69` hasta `105 Y MÁS`. El agregado filtra provincia y variable, y suma exclusivamente esas categorías mediante OpenArg; no suma filas localmente ni combina variables alternativas de edad.
- Salta obtuvo **118.445 personas de 65 años y más en 2022**, sobre 9.226 filas. La fecha de lectura 2026-07-29 no actualiza el período censal. Se conserva fuente de descarga oficial, filas utilizadas y método.
- La tabla CABA 2022 anunciada por el catálogo no devolvió `PERSONA_EDADQUI` en la consulta probada. Ese indicador se omitió con advertencia. No se extrapoló una cifra, ni se sustituyó por el censo 2010. Jujuy no tiene un mapeo censal curado en esta entrega; conserva consultas de conectividad y búsqueda de catálogo.

Las fuentes ENACOM son [accesos por provincia](https://indicadores.enacom.gob.ar/Files/DatosAbiertos/internet_accesos_penetracion_provincias.xlsx) y [velocidad por provincia](https://indicadores.enacom.gob.ar/Files/DatosAbiertos/internet_velocidad_media_descarga_provincias.xlsx). La evidencia censal Salta enlaza la [descarga oficial INDEC 2022](https://infra.datos.gob.ar/catalog/indec/dataset/48/distribution/48.17/download/66-salta-2022.zip).

Hay 23 mapeos censales del catálogo, verificados en ejecución antes de consultar valores; esto no afirma que todas las tablas publicadas por el MCP contengan todas las variables del archivo original. La UI conserva vacíos explícitos cuando falta estructura, categoría, filas o fuente. `evidence`, `gaps` y `opportunities` explican la procedencia y las limitaciones. Las oportunidades son hipótesis de planificación, no personas identificadas, demanda demostrada ni impacto de NIETO. No existe un cruce verificado entre edad y conectividad individual. Comparar exige igual período, territorio y definición.

## Antecedente del transporte

Verificación real del 30 de octubre de 2026, 18:27 UTC, sin credenciales ni datos personales:

- `POST https://mcp.openarg.org/mcp`, `initialize`: HTTP 200; protocolo `2025-03-26`; servidor `openarg`.
- `tools/list`: HTTP 200; seis herramientas anunciadas: `listar_fuentes`, `buscar_datasets`, `describir_tabla`, `obtener_datos`, `agregar_datos`, `consultar_datos_publicos`.
- `tools/call` → `listar_fuentes`, sin Authorization: HTTP 200 con `result.isError=true` y falta de clave. El cliente comprueba el error MCP además del estado HTTP.
- El handshake inicial no consultó datasets autenticados. La verificación autenticada y los indicadores incorporados después se detallan a continuación.

Documentación primaria consultada:

- https://mcp.openarg.org/llms.txt
- https://mcp.openarg.org/herramientas.html
- https://openarg.org/desarrolladores
- https://modelcontextprotocol.io/specification/2025-03-26/basic/transports

El cliente Node implementado completó ese mismo handshake y descubrimiento contra producción el 30 de octubre de 2026 a las 18:33 UTC. El script de prueba no imprimió ni usó claves existentes.

El transporte hace initialize → initialized → tools/list → tools/call. Admite JSON y SSE, conserva Mcp-Session-Id y usa timeouts. Un límite por minuto se reintenta una sola vez únicamente si Retry-After (HTTP) o el mensaje MCP indica segundos explícitos que no superan cinco segundos; en otros casos comunica cuándo intentar nuevamente. Los timeouts no repiten consultas que podrían consumir cupo. Cachea respuestas públicas cinco minutos y consolida consultas iguales en curso.

El estado separa `transportConnected`, `transportError`, `queryError`, `lastTransportSuccessAt`, `lastTransportCheckAt` y `lastSuccessAt` (consulta). `discover()` valida handshake/listado o vuelve a consultar `tools/list`: una recuperación limpia el error de transporte sin consumir una consulta de IA. No borra errores independientes de cuota o credenciales de la última consulta. Solo una consulta nueva exitosa limpia `queryError`; leer una respuesta cacheada no simula recuperación. `GET /api/openarg/status` expone ambos errores y `GET /api/health` usa el estado de transporte para `openarg.connected`.

La clave se obtiene solamente de `OPENARG_API_KEY` del proceso del backend. No se incluye en clientes web/Android, logs, respuestas ni persistencia. Cada resultado conserva texto original, fuentes enlazadas, período solicitado, evidencia temporal textual y fecha de consulta separada. La fecha de consulta nunca se usa como fecha del dato. No se transmiten identidades de los dispositivos, conversaciones de WhatsApp, pantallas ni coordenadas a OpenArg. Sí se transmite la consulta pública que el usuario institucional escribe y el nombre del territorio elegido; su documentación informa que retiene las preguntas asociadas a la clave.

El parser de búsqueda sigue el formato Markdown numerado que publica el proyecto oficial: https://github.com/colossus-lab/openarg-mcp/blob/main/mcp_publico/core.py (`format_search`). Si cambia el formato, conserva el texto original y muestra una advertencia, sin fabricar datasets. Ninguna descripción se transforma en un indicador numérico. Las 24 jurisdicciones ISO se usan como contexto de búsqueda; esa referencia geográfica no acredita cobertura estadística del resultado.

## Indicadores históricos CABA verificados con MCP autenticado

Se realizaron ocho consultas de datos con la nueva clave configurada por el usuario, siempre leída a memoria desde el entorno. No se usó `consultar_datos_publicos` ni se consumió el cupo de preguntas con IA.

1. `buscar_datasets`: población y personas mayores de CABA.
2. `describir_tabla`: se descartó una tabla de porcentajes de adultos mayores de 2012–2015 porque el denominador por hogar no era suficientemente claro para el indicador propuesto.
3. `buscar_datasets`, portal `caba`: proyecciones, sexo y grupos de edad.
4. `describir_tabla`: se descartó un XLS con encabezados mal estructurados, sin columna de fecha verificable; no se usó su aparente año 2025 como dato.
5. `describir_tabla` de `raw.caba__estructura_de_la_poblacion__967ab1ab__v4`: columnas `anio`, `sexo`, `grupo_edad`, `nacionalidad`, `cant_poblacion`; cobertura 1855–2010; último dato 2010; leída de su fuente el 9 de mayo de 2026.
6. `obtener_datos` para 2010 confirmó 76 filas, sexos `m`/`v`, nacionalidades `Nativo`/`Extranjero`, cinco grupos de edad desde 65 hasta `85 y más`, y cuatro filas adicionales `TOTAL`. Sumar todos los grupos más `TOTAL` duplicaría la población.
7. `agregar_datos`, `suma` de `cant_poblacion`, período 2010 y cinco grupos `65 - 69`, `70 - 74`, `75 - 79`, `80 - 84`, `85 y más`: **474.071 personas de 65 años y más**, calculadas por OpenArg sobre 20 filas.
8. `agregar_datos`, misma columna y período, filtrando solo `grupo_edad=TOTAL`: **2.890.151 personas**, calculadas por OpenArg sobre cuatro filas. No se sumaron filas localmente.

Fuente de descarga oficial, obtenida en el resultado de búsqueda del catálogo CABA:

https://cdn.buenosaires.gob.ar/datosabiertos/datasets/direccion-general-de-estadisticas-y-censos/estructura-poblacion/pob_cen_sexo__annio__l_nac__g_edad_limpio.xlsx

El endpoint de aquella versión combinaba una búsqueda con dos indicadores CABA curados. La versión actual mantiene `q` opcional de hasta 300 caracteres y reemplaza esa curaduría por el módulo provincial descrito al inicio. No hay valores numéricos de reemplazo. Si cambia estructura, cardinalidad esperada o formato de respuesta, se omite el indicador y se explica el límite.

Los indicadores de aquella curaduría conservaban `period: "2010"`, `freshness.historical: true`, fecha de lectura, fecha de consulta, fuente y método. La fecha de lectura 2026 no convierte al censo 2010 en población actual. Las coincidencias de búsqueda del catálogo siguen pudiendo incluir otras jurisdicciones; no se convierten automáticamente en indicadores territoriales.
