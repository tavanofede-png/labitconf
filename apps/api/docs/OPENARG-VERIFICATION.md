# Evidencia OpenArg

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

`GET /api/openarg/territory?territoryId=AR-C&q=...` admite `q` opcional de hasta 300 caracteres. Combina una sola búsqueda de catálogo con dos indicadores CABA curados. En cada caché vencida verifica primero tabla, columnas y cobertura temporal, y pide ambos agregados a OpenArg. El código productivo no contiene valores numéricos de población de reemplazo. Si cambian estructura, cardinalidad esperada o formato de respuesta, omite el indicador y explica por qué.

Cada indicador conserva `period: "2010"`, `freshness.historical: true`, `freshness.sourceReadAt`, `freshness.retrievedAt`, fuente y método de agregado. La fecha de lectura 2026 no convierte al censo 2010 en población actual. Estos indicadores no se comparan con las métricas actuales de uso o autonomía de NIETO. Otras jurisdicciones conservan indicadores vacíos hasta verificar una fuente específica para ellas. Las coincidencias del catálogo pueden incluir otras jurisdicciones; se advierte que revisar su cobertura sigue siendo necesario.
