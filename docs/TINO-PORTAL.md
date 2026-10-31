# Portal institucional TINO

Implementación sobre el repositorio `tavanofede-png/labitconf`, rama `codex/tino-instituciones`. Referencias leídas: documento completo de arquitectura NIETO, los tres mockups locales de asistente/red/instituciones y el código Android, API y web existente. Las búsquedas de NIETO y TINO en el Drive conectado no encontraron otros decks.

El alcance del producto incluye el portal institucional completo. La arquitectura define qué construir; el estado del código y las pruebas determinan qué se puede afirmar que funciona hoy. El público de TINO incluye cualquier persona que necesite apoyo digital, personas con discapacidad, personas mayores y necesidades temporales o situacionales. No se infieren diagnósticos a partir del uso.

## Entradas web

- `/inicio.html`: landing TINO, adaptada al brand kit, con entrada al portal.
- `/#instituciones`: portal institucional con seis vistas.
- `/#familia`: red de personas de confianza; conserva los contratos familiares del Android actual.
- `/#aplicacion`: conexión y descarga del Android disponible en el servidor.
- `/#conexion`: credencial institucional, estado API y verificación del MCP.

Se conservaron React, TypeScript, Vite, MapLibre y la API Node existente. No se cambiaron el identificador Android `org.nieto.assistant`, las rutas de descarga, los nombres de los workspaces ni las claves de sesión existentes: el cambio de marca no debe romper compatibilidad con el teléfono de tu compañero.

## Vistas y contratos

| Vista | Comportamiento implementado | Fuente |
| --- | --- | --- |
| Resumen | Personas activas, tareas completas, porcentaje de prácticas autónomas, tiempo acompañado, demanda y evolución | Eventos reales almacenados por la API |
| Territorio | Selección de 24 jurisdicciones, listado de cohortes, mapa de referencia y contexto público | Agregados TINO y MCP OpenARG |
| Flujos | Inicios, completados, cancelados, autonomía, duración, repeticiones, eventos Me perdí y pasos completados | Sesiones y eventos registrados |
| Uso | Tiempo total, acompañado, sin acompañamiento y categorías, cuando existen intervalos con permiso | `POST /usage/batch` |
| Seguridad | Señales categorizadas, pendientes y resueltas; pedidos de ayuda separados | `POST /safety/events`, resolución y alertas de la red |
| Programas | Crear programas, persistirlos, cambiar su estado y comparar ventanas de igual duración | Programas y eventos del territorio |

Filtros de 7, 30 o 90 días; actualización de métricas cada 30 segundos con la pestaña visible; exportación JSON del informe con contexto público y metodología. Las consultas MCP se ejecutan explícitamente y usan la caché del cliente existente; no se repiten al refrescar las métricas.

## Cruce de OpenARG con TINO

La unión es contextual por código territorial. La web presenta los agregados de TINO en su ventana de actividad junto a indicadores OpenARG de esa jurisdicción, con el período de la fuente por separado. No une personas ni envía eventos privados a OpenARG.

La implementación recibida ya verifica una tabla de población histórica de CABA, su esquema y sus agregados. Se conserva esa validación. Los indicadores disponibles allí son población total y población de 65 años y más del Censo 2010. Son contexto histórico, no una descripción de quién usa TINO ni un denominador de penetración actual. No se calcula cobertura dividiendo actividad reciente por población de 2010.

Para discapacidad, conectividad, educación, salud y otros territorios se puede consultar el catálogo y sus fuentes. Para convertir sus resultados en indicadores numéricos comparables hace falta verificar tablas, campos, períodos y cobertura, y agregar adaptadores de indicadores como `territory.mjs`. Encontrar un dataset por búsqueda no verifica un indicador ni filtra estrictamente una jurisdicción.

## Métricas y privacidad

Personas activas son instalaciones seudónimas con al menos una sesión, intervalo de uso, señal o pedido de ayuda en la ventana. No es el número de personas registradas ni una estimación de población. La tasa de autonomía es prácticas autónomas registradas sobre recorridos completos. El tiempo sin acompañamiento no se interpreta como autonomía validada.

Un territorio requiere al menos `MINIMUM_COHORT` participantes activos (mínimo 5). Las colecciones de sesiones, uso, seguridad y ayuda tienen su propio umbral. Una distribución se omite por completo si una categoría no lo alcanza, para evitar reconstruirla por resta. Los valores no publicables son `null`/listas vacías, nunca un cero inventado. Esto limita exposición pero no constituye anonimización formal ni evita todos los ataques por consultas sucesivas.

Los agregados institucionales no incluyen nombres, tokens, identificadores de dispositivo, URLs, textos privados ni capturas. Se conservó la autenticación institucional del backend. La credencial es compartida por el equipo de este prototipo; todavía no implementa identidad empresarial, MFA, organizaciones aisladas o permisos RBAC. No publicar esta API como un servicio multiinstitución de producción sin completar esos controles.

## Rutas nuevas

- `GET /api/institution/analytics?territoryId=AR-C&days=30`: métricas, flujos, evolución, uso, seguridad, pedidos de ayuda y definiciones.
- `GET /api/institution/territories?days=30`: cohortes de 24 jurisdicciones, sin identidades.
- `GET /api/institution/programs?territoryId=AR-C`: programas persistidos con evaluación agregada.
- `POST /api/institution/programs`: `{name,territoryId,startDate,endDate,goal}`. Fechas ISO y meta de autonomía entre 0 y 100.
- `PATCH /api/institution/programs/:id`: `{status:"planned"|"active"|"completed"}`.
- `POST /api/usage/batch`, autenticado como dispositivo: `{consent:true,intervals:[{id,category,startedAt,endedAt,accompaniedSeconds}]}`. Categorías: `communication`, `services`, `navigation`, `learning`, `other`. Máximo 50 intervalos, terminados, sin superposición, de hasta 24 horas dentro de los últimos 31 días. IDs idempotentes por dispositivo. `consent:true` es una afirmación del dispositivo; no sustituye el permiso Android ni una futura gestión central de revocación/retención.
- `POST /api/safety/events`, autenticado como dispositivo: `{id,kind,category}`. Tipos: `suspicious_link`, `permission_request`, `sensitive_action`. Categorías: `messaging`, `browser`, `banking`, `other`. IDs idempotentes, sin URL ni texto libre.
- `PATCH /api/safety/events/:id`, autenticado por el mismo dispositivo: `{resolved:true}`.

La evaluación de programas compara el tiempo transcurrido desde el inicio con una ventana anterior de igual duración. Exige cohortes válidas en ambas ventanas. El resultado se expresa en puntos porcentuales y es descriptivo: no atribuye causalidad, no selecciona asistentes al taller y no controla cambios en la composición de participantes.

## Configuración y límites actuales

Usar los comandos existentes `npm install`, configurar `.env` con `INSTITUTION_TOKEN` y `OPENARG_API_KEY`, luego `npm run dev`. La nueva web usa Plus Jakarta Sans autoalojada y la paleta `#00D6C9`, `#101416`, `#4C5B61`, `#F3F6F6`; `#006B64` da contraste en textos y controles sobre blanco.

En este workspace no se recibió `OPENARG_API_KEY`. Se verificaron en vivo `initialize` y `tools/list` del endpoint oficial, con seis herramientas anunciadas. No se ejecutó una consulta autenticada ni se descargaron datos públicos con una clave. Las pruebas de los agregados de CABA usan respuestas de referencia, claramente separadas de una consulta real.

El Android recibido envía sesiones, progreso y solicitudes de ayuda. Aún no envía intervalos de uso total ni señales de seguridad a las rutas nuevas; hay que implementar esos emisores y sus permisos en el teléfono. La API y las vistas ya pueden recibir y mostrar dichos registros reales sin cambiarlas. Tampoco se añadió un clasificador automático de fraude.

La marca oficial ya está instalada en la web y en los recursos Android. Ver [brand/README.md](../brand/README.md) para los archivos maestros, el launcher y las limitaciones de compilación observadas. La copia recibida omite las clases Android `LocalSettings` y `NietoApi`; debe completarse ese código antes de compilar un APK nuevo.

La persistencia actual sigue siendo JSON de un solo proceso. Se agregaron colecciones compatibles de forma diferida, sin sobrescribir datos existentes. Producción requiere las piezas de la arquitectura aún ausentes: base de datos, identidad por organización, retención, HTTPS y operación. No se implementaron pagos, iOS, palabra de activación ni un modelo de IA general en este cambio.

## Verificación

Las pruebas de API verifican autorización, cohortes activas, filtros, ausencia de identidades, supresión de distribuciones pequeñas, intervalos idempotentes/no superpuestos, resolución por el dispositivo titular, validación y persistencia de programas y ventanas comparables. Se mantienen todas las pruebas previas del protocolo MCP, familias y guías.

Las pruebas de navegador usan una API real y datos sintéticos en un almacén temporal aislado. El contexto OpenARG de esa prueba es una respuesta fixture, no una consulta autenticada. Se comprueban las seis vistas, exportación, programas, teclado, filtros y responsive. No se cargan esos datos de prueba en el almacenamiento habitual ni se presentan como usuarios reales.
