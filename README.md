# NIETO 2.0

NIETO acompaña a personas con poca experiencia digital para que puedan aprender a usar su teléfono. Esta versión para LABITCONF reúne una aplicación Android, un portal familiar e institucional y un backend que consulta datos públicos mediante el MCP oficial de OpenArg.

El usuario hace cada acción. Desde la burbuja, pide ayuda por voz o texto; OpenAI Realtime analiza la pantalla actual autorizada y propone un paso vinculado a un control real. Android lo señala sin pulsarlo. La tarea conserva contexto efímero, sin historial guardado. El mismo APK incluye acceso familiar nativo y avisos periódicos fuera del navegador. OpenArg aporta contexto público para instituciones con fuentes y períodos verificables.

## Inicio rápido

Requisitos: Node.js **24 LTS** recomendado, mínimo **22.15**, y npm. Desde la raíz:

```powershell
npm install
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
node -e "console.log(require('node:crypto').randomBytes(32).toString('hex'))"
```

Si todavía no hay una credencial institucional, pegá el resultado del último comando en `INSTITUTION_TOKEN` dentro de `.env`. La entrega local ya incluye una credencial aleatoria en ese archivo. Completá `OPENARG_API_KEY` con una clave de OpenArg para consultar datos públicos y `OPENAI_API_KEY` con la clave para conversar con NIETO. No pegues ninguna clave en archivos web, Android, capturas o commits. Conservá un `.env` ya configurado.

```powershell
npm run dev
```

Abrí [el portal local](http://localhost:5173). La API escucha en [localhost:8787/api/health](http://localhost:8787/api/health). `Ctrl+C` cierra ambos servicios. También podés usar `./scripts/start-local.ps1`. Desde Bash, el equivalente a copiar la configuración es `cp .env.example .env`.

Ingresá el token institucional en **Conexión**. El portal conserva por separado las credenciales institucional y familiar en `sessionStorage`, hasta cerrar la pestaña o cerrar sesión; no forman parte del bundle. Un familiar crea su propio grupo y recibe una credencial distinta, un código de invitación y su vencimiento. Guardá la credencial familiar desde el botón explícito de respaldo: permite restaurar el acceso a ese grupo.

## Conectar Android

El proyecto nativo está en `apps/android`. Abrilo con Android Studio y esperá la sincronización de Gradle. La aplicación permite editar la dirección de la API:

En Windows también podés ejecutar `./scripts/android-build.ps1` si tenés JDK y Android SDK instalados. Compila las pruebas unitarias y el APK de depuración. El paquete es `org.nieto.assistant`, requiere Android 8.0/API 26 o posterior y se compila con SDK 36. El resultado esperado está en `apps/android/app/build/outputs/apk/debug/app-debug.apk`.

Descargá el [APK público 0.5.0-labitconf](https://github.com/tavanofede-png/labitconf/releases/download/v0.5.0-labitconf/nieto-debug.apk) desde la [release de la hackathon](https://github.com/tavanofede-png/labitconf/releases/tag/v0.5.0-labitconf), junto con su manifiesto y [SHA256SUMS](https://github.com/tavanofede-png/labitconf/releases/download/v0.5.0-labitconf/SHA256SUMS.txt). Es una compilación de depuración. La [auditoría vigente](docs/AUDIT.md) distingue las correcciones comprobadas de la validación física pendiente.

La copia local está en `artifacts/nieto-debug.apk`; los binarios no se incluyen en Git. Para recompilar, el script actualiza atómicamente el APK, `android-build.json` y `SHA256SUMS.txt`. La API solo sirve esa salida canónica si su versión, tamaño y hash coinciden; no recurre a otra copia de Gradle.

| Entorno | Dirección en Android |
| --- | --- |
| Emulador Android Studio | `http://10.0.2.2:8787` |
| Teléfono por USB, luego de `adb reverse tcp:8787 tcp:8787` | `http://127.0.0.1:8787` |
| Teléfono en la misma red Wi-Fi | `http://IP-LAN-DE-TU-PC:8787` |

Con Wi-Fi, la computadora y el teléfono deben poder comunicarse y el firewall debe permitir el puerto 8787 en esa red. La API usa `HOST=0.0.0.0` para aceptar esa conexión. HTTP está habilitado para depuración local; una distribución necesita HTTPS y configuración de red de producción.

Para abrir también el portal desde otra computadora, agregá su origen exacto, por ejemplo `http://IP-LAN-DE-TU-PC:5173`, a `CORS_ORIGINS` y reiniciá la API. La conexión nativa de Android no necesita ese origen web.

En el portal, abrí Conexión con la credencial institucional y generá un código de conexión de teléfono. Es de un solo uso y vence a los 15 minutos. En Ajustes de Android ingresá la URL, el código, un nombre y la jurisdicción declarada, sin GPS. Una instalación anterior conserva su credencial y necesita activar el acceso al asistente con un código nuevo. El código del teléfono es independiente de la invitación familiar; el token institucional y las claves de proveedores permanecen en el equipo del operador.

Android conserva su credencial de dispositivo; el portal permite consultar contexto público de las 24 jurisdicciones. Habilitá accesibilidad y elegí por separado si autorizás enviar la pantalla al agente. La persona decide si comparte progreso y solicitudes de ayuda con su familiar, puede revocar el vínculo y puede aceptar o revocar por separado el uso de métricas institucionales. El equipo puede revocar el acceso a IA de un teléfono sin modificar sus permisos familiares.

Para enlazar una familia: creá el grupo desde el acceso familiar nativo de Android o desde el portal, ingresá su código de un solo uso en el teléfono asistido y aceptá los permisos elegidos. La familia recibe progreso solo con consentimiento y una alerta únicamente si la persona solicita ayuda. No accede al contenido de la pantalla. En el teléfono familiar, activá los avisos y el permiso de notificaciones. Android comprueba pedidos con WorkManager desde intervalos de 15 minutos, sujetos a batería y red; al abrir Familia actualiza de inmediato. No es push instantáneo ni un servicio de emergencias. Cerrar el acceso borra la credencial local y cancela comprobaciones y avisos, sin borrar el grupo del servidor.

## OpenAI Realtime

La conversación usa exactamente `gpt-realtime-2.1-mini`, con la clave únicamente en el backend. La conexión de voz pasa por `/api/assistant/realtime`, autenticada con la credencial de instalación NIETO. El APK no recibe la clave OpenAI. El modo texto usa el mismo modelo mediante Realtime.

En Android, aceptá el aviso de envío de voz y texto a OpenAI y usá Hablar con NIETO. Tocá Terminar de hablar para recibir la respuesta; la captura tiene un máximo de 45 segundos. Cancelar y pasar la app a segundo plano detienen la captura. No hay escucha permanente ni grabaciones guardadas. Las respuestas de voz se reproducen a medida que llegan y su transcripción aparece en pantalla.

La asistencia recibe un snapshot con controles y, en Android 11+, una captura JPEG efímera. Android 8–10 aporta controles accesibles. Este envío exige un consentimiento nuevo de pantalla remota, separado del permiso de guía local. Se excluye el contenido de los campos editables y se bloquean pantallas sensibles reconocidas; un campo permitido puede representarse por su posición y una etiqueta genérica para señalarlo, sin leer su valor. Si la captura no puede sanearse, se usan solo los controles permitidos. El servidor valida que el control elegido exista en el snapshot y Android lo revalida antes de señalar. Cambiar de pantalla invalida la indicación anterior. El modelo no puede tocar controles, compartir ubicación, avisar a la familia ni acreditar tareas por su cuenta.

La burbuja permite escribir y leer el paso actual sin audio: la modalidad escrita no inicia reproductor ni micrófono y silencia los avisos TTS del acompañamiento. La voz se inicia por un gesto explícito y una actividad transparente para respetar el permiso de micrófono de Android. Activar uno de los modos de acompañamiento termina la guía anterior. Terminar la tarea descarta la sesión remota. La guía local de WhatsApp queda como recorrido opcional. Los fallos de clave, acceso al modelo o cuota se muestran como errores; no se cambia a otro modelo silenciosamente.

La admisión y los presupuestos diarios de uso se conservan en el JSON privado del servidor. Cada conexión reserva un máximo de turnos, tiempo, pantallas e imágenes antes de abrir OpenAI; se aplican límites por dispositivo, origen de red y servidor, además de concurrencia. Son reservas conservadoras de capacidad, no mediciones de consumo real ni un límite monetario de la cuenta OpenAI. El [contrato de admisión](apps/api/docs/ADMISSION.md) detalla los valores, la revocación y la recuperación. Sin código válido no se entrega una credencial nueva capaz de consumir IA.

El [contrato Realtime](docs/REALTIME_CONTRACT.md) describe el protocolo y sus límites. Configurar una clave no acredita que la cuenta tenga acceso al modelo: la verificación requiere una consulta real.

## OpenArg real

El backend habla con `https://mcp.openarg.org/mcp` mediante MCP Streamable HTTP: inicializa la sesión, descubre herramientas y realiza la consulta. La clave se lee únicamente del entorno del servidor.

```powershell
npm run verify:openarg
```

Sin clave válida, el portal comunica que falta la configuración. No muestra indicadores de reemplazo. El descubrimiento de herramientas puede verificarse sin credenciales; la consulta de datasets requiere una clave. La evidencia inicial está en `apps/api/docs/OPENARG-VERIFICATION.md`.

Los datos de NIETO provienen de sus sesiones registradas. Los datos públicos se presentan con fuente y período disponible. La fecha de consulta no equivale a la fecha del dato. Un resultado sin indicador verificable permanece sin cifra; el centro del mapa representa el territorio seleccionado y no la posición de una persona.

El endpoint territorial obtiene por MCP población de 65 años o más del Censo 2022 e indicadores provinciales ENACOM de accesos fijos cada 100 hogares y velocidad media, según disponibilidad verificada. Cada agregado conserva fuente, período, método y fecha de lectura. Los accesos cada 100 hogares pueden superar 100: no son un porcentaje de personas conectadas ni miden la conectividad específica de personas mayores. Una fuente no disponible o que no pase la validación queda sin valor.

Las instituciones reciben métricas de los últimos 30 días para organizar acompañamientos: personas activas, sesiones, tiempo acompañado, dificultades reportadas, pedidos de ayuda y tiempo hasta que la familia los marca atendidos. Cada cifra y desglose exige cinco contribuyentes efectivos con consentimiento institucional vigente, independiente del familiar. Un valor suprimido nunca equivale a cero. Estos registros operativos no prueban autonomía ni impacto causal.

## Configuración

| Variable | Uso |
| --- | --- |
| `HOST` | Interfaz API; por defecto `0.0.0.0`. |
| `PORT` | Puerto API; por defecto `8787`. `API_PORT` tiene prioridad si se define. |
| `INSTITUTION_TOKEN` | Secreto de acceso institucional, obligatorio para sus rutas. |
| `OPENARG_API_KEY` | Clave del servicio OpenArg, solo en backend. |
| `OPENAI_API_KEY` | Clave para `gpt-realtime-2.1-mini`, solo en backend. |
| `MINIMUM_COHORT` | Mínimo de contribuyentes efectivos consentidos por indicador; nunca menor que 5. |
| `CORS_ORIGINS` | Orígenes permitidos separados por coma; localhost:5173 por defecto. |
| `OPENARG_TIMEOUT_MS` | Tiempo máximo de consulta; por defecto 25000 ms. |
| `DATA_FILE` | Ruta alternativa para el JSON privado; por defecto `apps/api/data/nieto.json`. |

El proxy de desarrollo lee `API_PORT` o `PORT` de `.env` en la raíz, con 8787 como valor predeterminado. Si cambiás el puerto, reiniciá ambos servicios y ajustá la dirección configurada en Android.

Usá una ruta absoluta para `DATA_FILE` si alternás entre comandos desde la raíz y desde `apps/api`. El archivo guarda identidades seudónimas, vínculos, consentimientos, sesiones y alertas. El directorio de datos y `.env` están excluidos de Git; siguen siendo datos privados locales y requieren cuidado y respaldo.

## Verificación y comandos

```powershell
npm run check           # TypeScript, pruebas del backend, build web
npm test                # Pruebas API con estado temporal
npm run test:web        # Regresiones de identidad, sondeo y exportación con React DOM
npm run build           # Compilación del portal
npm run api             # Solo API
npm run web             # Solo portal
npm run verify:openarg  # Verificación del MCP oficial
npm run verify:realtime # Verificación breve de texto y audio de salida OpenAI
npm run verify:screen   # Pantalla artificial y señalamiento validado con OpenAI real
```

La comprobación de software no reemplaza la prueba en un teléfono: permisos, versión de WhatsApp, voz y servicio de accesibilidad necesitan validación real. El [guion de demo](docs/DEMO.md) distingue lo verificable localmente de los pasos que dependen del dispositivo y de OpenArg.

## Alcance de esta entrega

Es un MVP local de asistencia por voz y texto basada en la pantalla, acceso familiar nativo y portal institucional con datos operativos consentidos y contexto público de OpenArg. Requiere validación física de micrófono, capturas, resaltados y notificaciones en el teléfono concreto. La guía local opcional mantiene un perfil experimental de WhatsApp español `2.26.*` (`whatsapp-location-es@1`). La duración registrada mide sesiones acompañadas de NIETO, no tiempo total de uso del teléfono.

No hay cobros, suscripciones activas, autenticación empresarial, predicciones, compatibilidad iOS ni escucha permanente. El [alcance funcional actual](docs/MVP_FUNCTIONAL_SCOPE.md) distingue lo implementado y su validación. El [informe de auditoría](docs/AUDIT.md) conserva la fotografía de la versión anterior. El almacenamiento JSON de un solo proceso y los tokens portadores son decisiones de prototipo. Para producción hacen falta identidad y recuperación de acceso, base de datos, HTTPS, políticas de retención, controles operativos y validación de accesibilidad con usuarios.

La correspondencia con el documento de arquitectura original y los pendientes están en [docs/IMPLEMENTATION.md](docs/IMPLEMENTATION.md). El contrato está en [docs/API_CONTRACT.md](docs/API_CONTRACT.md). Nada se publica ni se despliega al ejecutar estos comandos.
