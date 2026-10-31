# Correcciones de la auditoría de NIETO

Fecha: 31 de octubre de 2026, hora de Argentina. Entrega **0.5.0-labitconf**, código Android **5**. Punto de partida: `56d03b2`; diagnóstico revisado: [auditoría de c479076](AUDIT-2026-10-31.md).

**Los nueve hallazgos B01–B09 tienen corrección de código o documentación y el escenario R01 de consumo abierto quedó cerrado en el MVP.** La validación reúne **137 pruebas aprobadas** y comprobaciones reales de texto, voz, señalamiento sintético, truncación de audio y revocación. La compatibilidad física y el despliegue de producción siguen pendientes.

## Estado de cada hallazgo

| ID | Resultado | Corrección y evidencia |
| --- | --- | --- |
| B01 | Corregido | Una huella HMAC efímera local incluye cambios de contenido estático; no se exportan texto, clave ni huella. Los eventos relevantes invalidan el objetivo y la respuesta antes de recolectar otra pantalla. Dos vistas con botones iguales y contenido distinto ya no comparten contexto. Los eventos de foco solo ocultan temporalmente el marcador y permiten revalidarlo. Pruebas de cambios de texto, contenido largo, límites de campos y claves independientes de proceso. |
| B02 | Corregido | Elegir escribir activa salida `text`, detiene voz y TTS y evita inicializar `AudioTrack`. Los seguimientos conservan esa elección; hablar vuelve a elegir audio. El backend rechaza audio inesperado en un turno escrito. Regresión de protocolo y verificación real: texto puro con cero bloques de audio; cambio a voz en la misma conexión. |
| B03 | Corregido | La burbuja deshabilita el envío durante ocupación y conserva el borrador hasta la aceptación local. Un rechazo, una pantalla bloqueada o una aceptación tardía no eliminan una consulta nueva. Pruebas del estado de borrador cubren ocupación y cambios de texto mientras hay una entrega pendiente. La aceptación local no garantiza una respuesta exitosa del proveedor; los fallos posteriores se muestran en pantalla. |
| B04 | Corregido | Activar acompañamiento cancela el motor local mientras su sesión aún está vinculada y después la desvincula. El servicio excluye la observación local durante asistencia remota. Se reinicia la deduplicación de anuncios al cambiar de modo o empezar una guía. Pruebas del motor, exclusión y reinicio de avisos. |
| B05 | Corregido | El cliente relaciona cada mensaje de audio con la posición realmente reproducida. Al interrumpir, el relay valida el checkpoint, trunca al punto oído y elimina mensajes posteriores o tardíos. Funciona incluso cuando terminó la generación y quedan bloques por reproducir. `playback.complete` distingue reproducción terminada de generación terminada. Pruebas de segmentos, posiciones inválidas, audio tardío y conservación de lo ya escuchado; OpenAI real confirmó una truncación después de `response.done`. |
| B06 | Corregido | La inicialización demorada del mapa lee el territorio confirmado más reciente, con marcador y zoom correspondientes. Regresión React DOM: cambiar CABA por Salta antes de resolver MapLibre inicializa el centro en `[-65.42, -24.78]`. |
| B07 | Corregido | «Ir al contenido» enfoca `main` sin cambiar el hash ni desmontar la sección. La regresión conserva Familia, su formulario y el mismo DOM, y comprueba el foco de teclado. |
| B08 | Corregido | Verificaciones MCP vinculadas a token y época de solicitud, canceladas al cambiar acceso o desmontar. Una respuesta o error anterior no puede repoblar el estado, aunque la red ignore la cancelación o se vuelva al mismo token. Regresiones React DOM para cierre y cambio de credencial. |
| B09 | Corregido | README Android actualizado a versión 0.5.0/código 5 y resultados `self_reported`. Los contratos vigentes describen modalidad escrita, interrupciones y admisión. La guía no acredita envío verificado ni autonomía. |
| R01 | Escenario cerrado en el MVP | El alta requiere un código de operador aleatorio, de un uso y 15 minutos de vigencia. Los tokens heredados necesitan activación para consumir IA. Hay presupuestos durables y concurrencia por dispositivo, origen directo y servidor; revocar cierra sesiones activas. Pruebas del exploit, códigos concurrentes, reinicio, cuotas y carrera de revocación. El circuito real de admisión y revocación también pasó. |

Las correcciones anteriores [A01–A11](AUDIT_CLOSURE.md) permanecen en la entrega; las suites existentes se ejecutaron junto con las nuevas regresiones.

## Admisión y límites de uso

Desde **Conexión** del portal, el equipo genera un código para el teléfono usando su acceso institucional. La persona lo introduce en Ajustes Android junto con la dirección de la API. Es independiente del código de Familia. El token institucional y las claves de proveedores permanecen fuera del teléfono.

Las instalaciones previas conservan su credencial y sus vínculos familiares. Para volver a conversar con la IA deben activar el acceso con un código nuevo. La revocación del operador afecta la asistencia y cierra su conexión; no cambia los consentimientos familiares ni institucionales.

Antes de abrir OpenAI se reserva capacidad máxima en el JSON privado del servidor:

| Ámbito | Turnos diarios UTC | Tiempo reservado | Pantallas |
| --- | ---: | ---: | ---: |
| Dispositivo | 60 | 30 minutos | 180 |
| Origen directo | 120 | 60 minutos | 360 |
| Servidor | 1200 | 600 minutos | 3600 |

Cada WebSocket reserva 20 turnos, 10 minutos y 60 pantallas; cada consulta HTTP reserva un turno y 65 segundos. La reserva no se devuelve al cancelar, desconectar o fallar el proveedor. Reiniciar el proceso no reinicia los contadores del día. Se limita cada conexión a 8 MB de imágenes y cada imagen a 300 KB; esos bytes se controlan en el protocolo, no como otro contador del ledger.

La concurrencia predeterminada es ocho conversaciones globales, una por dispositivo y dos por origen directo, compartidas entre HTTP y WebSocket. No se confía en `X-Forwarded-For`. Un proxy compartido cuenta como un único origen y necesita considerar ese límite al desplegar.

Estas reservas son conservadoras: **no representan uso real ni un límite exacto en dólares**. El operador sigue necesitando límites de cuenta y monitoreo del proveedor para una operación productiva. Los datos persistidos son día UTC, identificadores seudónimos y contadores; no se guardan consultas, pantalla o voz. El [contrato de admisión](../apps/api/docs/ADMISSION.md) describe rutas, variables, respuestas y límites.

## Validación ejecutada

- **78 pruebas API**, **10 regresiones web con React DOM real** y **49 pruebas Android/JVM**: **137 aprobadas**, 32 más que la revisión anterior.
- TypeScript y compilación web aprobados. Compilación debug Android y generación de manifiesto/checksum aprobadas. Los avisos conocidos de tamaño de MapLibre, herramientas SDK y APIs deprecadas no impidieron compilar.
- Revisión independiente entre componentes: contratos de admisión web/Android/API y protocolo de modalidad/reproducción. Se corrigieron durante la revisión una carrera generacional del reproductor y la invalidación de marcadores por eventos de foco.
- OpenAI real, `gpt-realtime-2.1-mini`: texto de 15 caracteres con **0 bytes de audio**; respuesta breve de voz con **57.600 bytes PCM**; imagen artificial de **12.421 bytes** con señalamiento válido de Buscar. No se enviaron pantallas personales.
- Integración real en servidor y persistencia temporales: código de operador → alta admitida → texto sin audio → voz en el mismo WebSocket → truncación confirmada por OpenAI después de terminar la generación → revocación y cierre de socket. Se recibieron **86.400 bytes de audio**, sin errores del proveedor; una sola reserva de **20 turnos, 600 segundos y 60 pantallas** quedó registrada. El punto de reproducción fue sintético; no se probó un altavoz físico.
- OpenArg real: transporte MCP conectado, **seis herramientas** descubiertas y `listar_fuentes` autenticado aprobado. Esto comprueba acceso actual al servicio, no la vigencia de cada tabla o indicador territorial.
- Se revisaron **154 archivos de fuentes/build web** y **133 entradas descomprimidas del APK**: no contienen los valores de las tres credenciales configuradas. El `.env` local, los datos de la demo y las claves no se incorporan al commit ni a la release.
- Las pruebas automáticas usan proveedores simulados y datos temporales. Las comprobaciones reales anteriores fueron ensayos adicionales del operador con entradas sintéticas. No se modificaron las identidades ni las sesiones de la demo habitual.

Se agregó [CI para web/API y Android](../.github/workflows/check.yml), sin claves de proveedores y con acciones fijadas a referencias exactas. La comprobación local es independiente del resultado de la ejecución alojada en GitHub.

## Distribución y uso

- [Release 0.5.0-labitconf](https://github.com/tavanofede-png/labitconf/releases/tag/v0.5.0-labitconf).
- [APK 0.5.0](https://github.com/tavanofede-png/labitconf/releases/download/v0.5.0-labitconf/nieto-debug.apk).
- [Manifiesto de versión y SHA-256](https://github.com/tavanofede-png/labitconf/releases/download/v0.5.0-labitconf/android-build.json) y [checksum](https://github.com/tavanofede-png/labitconf/releases/download/v0.5.0-labitconf/SHA256SUMS.txt).

APK `org.nieto.assistant`, código `5`, **12.240.028 bytes**. SHA-256: `4cc14bf6bb7cb17111ee183a7f65e19cb9cfb1d086a0a14ab407d4cd3ae8762b`. La descarga real desde la API local, el manifiesto, su ETag y la salida de Gradle coincidieron.

La distribución es un APK de depuración para la hackathon y necesita un servidor NIETO accesible. Para empezar, generar el código desde Conexión y activarlo en Ajustes Android. Los límites predeterminados se aplican aunque el `.env` anterior no tenga las nuevas variables; el archivo local de credenciales se conserva.

## Pendientes que no reemplaza esta validación

No hay un teléfono autorizado conectado. Falta ejecutar en dispositivo permisos, micrófono, teclado de la burbuja, salida silenciosa, reproducción/Bluetooth, interrupciones, captura y redacción, cambios de contenido y aplicación, rotación y restricciones del fabricante. Las pruebas JVM y del relay real no acreditan estos comportamientos físicos.

Familia necesita comprobar notificaciones con app cerrada, Doze, cortes de red y revocación en el teléfono. Sigue usando sondeo periódico desde 15 minutos y no entrega instantánea garantizada.

El escenario de consumo abierto R01 está cerrado por admisión y límites del MVP; continúan pendientes HTTPS/WSS y hosting estable, identidad empresarial y recuperación de acceso, aislamiento por organización, persistencia de varios procesos, backups, retención/borrado y monitoreo. Un código de operador no prueba identidad humana única. El hash de origen no garantiza anonimato. La cohorte institucional tampoco implica privacidad diferencial.

Enviar una ubicación y aprender a hacerlo siguen siendo **reportes explícitos, no resultados verificados**. No se habilitó autoacreditación, autonomía, acciones automáticas ni historial de conversaciones.
