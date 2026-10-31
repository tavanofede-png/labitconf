# Admisión y presupuesto de asistencia

La API exige aprobación del equipo para habilitar OpenAI. El token institucional nunca se introduce en Android. No hay un bypass de admisión por localhost, red privada, variable de modo o credencial de dispositivo anterior.

## Conectar un teléfono

1. Con `Authorization: Bearer <INSTITUTION_TOKEN>`, el operador llama a `POST /api/institution/device-invitations` con `{}`. Recibe `{enrollmentCode,expiresAt}`. El portal permite generar este código desde Conexión.
2. La persona introduce el código de 12 caracteres hexadecimales en Android. `POST /api/devices` recibe `{name,territoryId,enrollmentCode}` y devuelve `{deviceId,token,realtimeApproved:true}`.
3. El código tiene 48 bits aleatorios, vence a los 15 minutos y se consume una sola vez dentro de la misma escritura atómica que crea o activa el dispositivo. El servidor conserva solo su hash. Un código inválido, usado o vencido devuelve `403 invalid_enrollment_code`; no crea una identidad.

Una instalación anterior conserva su token para Familia, consentimiento y telemetría, pero `realtimeApproved` ausente equivale a falso. Para habilitar IA debe llamar con ese token a `POST /api/devices/me/activation` y `{enrollmentCode}`. `GET /api/devices/me` informa el estado vigente. Un dispositivo no aprobado recibe `403 device_activation_required` antes de conectar al proveedor.

El alta depende de que `INSTITUTION_TOKEN` esté configurado y el operador entregue un código vigente. No se generan credenciales públicas predeterminadas.

## Consultar y revocar

`GET /api/institution/devices`, con acceso del operador, devuelve:

```json
{
  "devices": [{
    "deviceId": "UUID",
    "realtimeApproved": true,
    "approvedAt": "ISO-8601 o null",
    "createdAt": "ISO-8601",
    "budget": {
      "reserved": {"turns": 0, "seconds": 0, "contexts": 0},
      "remaining": {"turns": 60, "seconds": 1800, "contexts": 180}
    }
  }],
  "budget": {
    "admission": "operator_single_use_code",
    "budgetBasis": "reserved_maximum_per_connection_utc_day",
    "day": "YYYY-MM-DD",
    "globalReserved": {"turns": 0, "seconds": 0, "contexts": 0},
    "reservations": {},
    "limits": {}
  }
}
```

`reservations` y `limits` contienen los montos y límites reales de las tablas siguientes. No se exponen nombres, territorios, tokens, pantalla, consultas, Familia ni la IP de las instalaciones. El ID que aparece en Android permite reconocer el teléfono.

`POST /api/institution/devices/<deviceId>/revoke` con `{}` desactiva el acceso a IA y cierra una conversación activa inmediatamente. No borra ni altera Familia, sus permisos o el historial de reportes. Volver a habilitar requiere otro código de un uso. Revocar no devuelve presupuesto consumido.

## Reservas durables y concurrencia

Antes de abrir una conexión al proveedor se reserva **el máximo permitido para esa conexión**, en una escritura serializada y atómica del JSON privado. La reserva se aplica a tres ámbitos simultáneos: dispositivo, origen directo y total del servidor. Si alguno excede su límite, no se carga ninguno y no se conecta con OpenAI.

| Transporte | Turnos reservados | Segundos de conexión reservados | Contextos de pantalla reservados |
| --- | ---: | ---: | ---: |
| WebSocket | 20 | 600 | 60 |
| Texto HTTP | 1 | 65 | 0 |

Una reserva no es una medición de uso real: una conversación breve sigue consumiendo el monto completo. Tampoco se reembolsa al cancelar, desconectar o fallar el proveedor. Esto evita recuperar presupuesto mediante reconexiones o fallos inducidos. Es una política conservadora de recursos; **no equivale a un límite exacto en dólares**.

Los límites de sesión impiden ampliar la reserva: máximo 20 turnos, 10 minutos de conexión y 60 snapshots; cada turno admite hasta 45 segundos de entrada PCM, 90 segundos de salida y hasta 800 tokens por respuesta del proveedor, con una única continuación de herramienta. El total de imágenes por conexión está limitado a 8 MB y cada imagen a 300 KB. El ledger no cuenta bytes: estos topes se aplican al protocolo dentro de cada conexión reservada. El plazo HTTP total es como máximo 65 segundos. Los overrides internos de duración o turnos solo pueden reducir los máximos.

| Ámbito diario UTC | Turnos | Segundos de conexión | Contextos |
| --- | ---: | ---: | ---: |
| Dispositivo | 60 | 1800 (30 min) | 180 |
| Origen directo | 120 | 3600 (60 min) | 360 |
| Servidor | 1200 | 36000 (600 min) | 3600 |

El ledger conserva únicamente el día UTC, UUID de dispositivo, hash SHA-256 del origen normalizado y contadores de reservas. No persiste voz, consultas, imágenes ni etiquetas. Las direcciones IPv4 representadas como IPv6 `::ffff:x.x.x.x` se normalizan a IPv4. No se confía en `X-Forwarded-For`. El hash de IP es un dato seudónimo, no anonimato garantizado. El cambio de día reemplaza los contadores; reiniciar el proceso durante el día no los reinicia.

Además se permiten como máximo ocho conversaciones simultáneas del servidor, una por dispositivo y **dos por origen directo** por defecto, compartidas entre HTTP y WebSocket. El origen de un proxy compartido se trata como una sola red; el despliegue debe considerar esa restricción. Un upgrade WebSocket malformado no reserva presupuesto ni deja un slot ocupado. Una revocación durante la reserva vuelve a comprobarse y no puede abrir después el proveedor.

## Variables

| Variable | Predeterminado |
| --- | ---: |
| `REALTIME_DEVICE_DAILY_TURNS` | 60 |
| `REALTIME_DEVICE_DAILY_SECONDS` | 1800 |
| `REALTIME_DEVICE_DAILY_CONTEXTS` | 180 |
| `REALTIME_ORIGIN_DAILY_TURNS` | 120 |
| `REALTIME_ORIGIN_DAILY_SECONDS` | 3600 |
| `REALTIME_ORIGIN_DAILY_CONTEXTS` | 360 |
| `REALTIME_GLOBAL_DAILY_TURNS` | 1200 |
| `REALTIME_GLOBAL_DAILY_SECONDS` | 36000 |
| `REALTIME_GLOBAL_DAILY_CONTEXTS` | 3600 |
| `REALTIME_MAX_CONNECTIONS_PER_ORIGIN` | 2 |

Los límites diarios aceptan enteros positivos; un valor inválido conserva el predeterminado. El límite de concurrencia por origen queda entre uno y siete con el máximo global actual de ocho. `429 realtime_daily_budget` indica presupuesto agotado; `429 realtime_origin_busy` indica concurrencia de esa red. Un ledger persistido con contadores inválidos falla con `503 realtime_budget_unavailable`; no se reinicia para habilitar consumo. Aumentar los límites requiere una decisión del operador y reiniciar la API.

Estas barreras cierran el consumo abierto del MVP. No verifican una identidad humana única ni sustituyen gestión empresarial de acceso, facturación del proveedor, monitoreo o persistencia de varios procesos. Los scripts locales de verificación de proveedor son herramientas del operador: usan la clave directamente y no son rutas públicas de la API ni se incluyen en este ledger.
