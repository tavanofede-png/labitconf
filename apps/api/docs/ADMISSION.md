# Admisión y continuidad de TINO

OpenAI requiere un dispositivo aprobado por el operador. El token institucional y la clave del proveedor permanecen en el backend. Activar continuidad no habilita acceso anónimo ni permite cambiar la política desde Android.

## Alta y revocación

El operador llama a `POST /api/institution/device-invitations` con `{}` y su autorización institucional. El código tiene 48 bits aleatorios, 12 caracteres hexadecimales, vigencia de 15 minutos y un único uso.

Android envía `{name,territoryId,enrollmentCode}` a `POST /api/devices`. Una escritura atómica consume el código y crea el dispositivo aprobado; devuelve su token privado. El servidor conserva hashes de códigos y tokens. Un código inválido o vencido no crea una identidad.

Una instalación anterior puede activar acceso con `POST /api/devices/me/activation`. `GET /api/devices/me` informa su estado. Sin aprobación recibe `403 device_activation_required` antes de conectar al proveedor.

`POST /api/institution/devices/<deviceId>/revoke` con `{}` retira acceso. Una conversación en la misma instancia se cierra inmediatamente; otras instancias comprueban la autorización cada cinco segundos y antes de reservar una conexión. La revocación no borra Familia ni sus consentimientos. Reactivar requiere un código nuevo.

## Continuidad autorizada

`CONTINUOUS_ASSISTANCE=1` es una opción del servidor que requiere autorización explícita del operador para el consumo y sus costos variables. El ejemplo local conserva `0`. La producción de esta entrega usa `1` tras la autorización explícita del usuario.

En ese modo TINO no impone cortes por cantidad diaria de mensajes, veinte turnos ni sesenta pantallas. Una consulta activa puede conservar su objetivo durante una rotación técnica del transporte. Los contadores históricos de reservas permanecen intactos: no se reinician para habilitar continuidad.

La salud publica `continuousAssistance:true`, `dailyLimitsEnforced:false` y `budgetBasis:"operator_authorized_continuous_assistance"`. `limits`, `reservations` y `budget.remaining` son `null`; sus cifras históricas no representan una cuota vigente.

Esto mantiene los límites del proveedor, su saldo, conectividad, permisos y duración máxima de las Functions. No constituye un límite en dólares ni una garantía de disponibilidad ininterrumpida.

## Concurrencia y almacenamiento cloud

La producción usa Blob privado con lecturas consistentes y escrituras condicionales por ETag. Se solicita `Accept-Encoding: identity`: un ETag débil generado por compresión no sirve como condición de escritura. Un estado corrupto o inaccesible nunca se sustituye por uno vacío.

Antes de abrir OpenAI se reserva una lease duradera compartida. Se permite un máximo de **ocho conexiones globales, dos por origen y una por dispositivo**, contando HTTP y WebSocket. El modo continuo conserva estas restricciones. Un upgrade malformado o no autorizado no abre el proveedor.

Solo dentro del runtime Vercel se utiliza su encabezado de IP de ingreso, validado como una dirección única. El servidor local usa la dirección del socket e ignora encabezados de reenvío. Las leases conservan hashes de origen, no la dirección cruda.

El transporte cloud dura como máximo 270 segundos; la lease vence a los 300 ante una caída. `waitUntil` conserva el trabajo de liberación después de desconectar. Un cierre normal iniciado por el servidor espera su liberación antes de cerrar el socket. HTTP libera antes de responder y su lease vence a los 75 segundos. Una escritura fallida no concede conexiones adicionales.

No se guarda voz, pantalla, conversación ni resumen de tarea en Blob. El resumen existe solamente en RAM del cliente y de la conexión durante la consulta activa y su rotación técnica; no es un historial persistido.

## Fin de conversación e inactividad

«Terminar conversación» cierra la conexión y elimina el resumen, conservando la detección local de «Hola Tino». También se elimina después de **30 segundos sin interacción**. La siguiente activación inicia una consulta nueva sin `resume.context`; la autorización de consumo continuo no extiende la memoria de una conversación terminada.

El servidor aplica ese plazo sin refrescarlo por snapshots, limpieza de pantalla, pings, configuración o mensajes inválidos. Espera que termine una captura, generación o reproducción activa y cuenta desde esa actividad real. Si se pierde la confirmación de reproducción, su espera también está acotada. Al vencer, envía el contexto vacío y `conversation.ended` con `reason:"idle"`, libera la lease y cierra normalmente sin solicitar renovación. Un `close` explícito usa `reason:"user_end"`.

`expectsReply` es informativo: no inicia una captura automática ni una nueva llamada al proveedor. La persona activa cada consulta con «Hola Tino» o una acción explícita. Cambiar de aplicación no pide una respuesta ni conserva una sesión sin interacción.

## Política sin continuidad autorizada

Con `CONTINUOUS_ASSISTANCE=0` permanece la política conservadora anterior: cada WebSocket reserva veinte turnos, 600 segundos y sesenta contextos; HTTP reserva un turno y 65 segundos. No es consumo medido y no se reembolsa al desconectar.

| Ámbito diario UTC | Turnos | Segundos | Contextos |
| --- | ---: | ---: | ---: |
| Dispositivo | 60 | 1800 | 180 |
| Origen | 120 | 3600 | 360 |
| Global | 1200 | 36000 | 3600 |

Estas cuotas solo se aplican en ese modo. Las variables `REALTIME_{DEVICE,ORIGIN,GLOBAL}_DAILY_{TURNS,SECONDS,CONTEXTS}` aceptan enteros positivos; los valores inválidos conservan el predeterminado. Un ledger inválido falla sin resetearse. La continuidad es independiente de la concurrencia.

## Límites técnicos conservados

- Captura PCM16 mono de 24 kHz: hasta 45 segundos por turno y 12.000 bytes por bloque.
- Salida: hasta 90 segundos de audio y 12.000 caracteres por turno, con hasta 800 tokens por respuesta del proveedor y continuaciones de herramientas acotadas.
- Límites individuales de payload, frecuencia de mensajes, snapshots y controles por pantalla.
- Renovación del transporte cloud, con memoria de turnos podada y resumen de tarea acotado.

La admisión no verifica una identidad humana única. Los scripts de verificación pertenecen al operador: no imprimen códigos, claves ni tokens y revocan sus dispositivos de prueba.
