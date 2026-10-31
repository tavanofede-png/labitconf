# NIETO 2.0

NIETO acompaña a personas con poca experiencia digital para que puedan aprender a usar su teléfono. Esta versión para LABITCONF reúne una aplicación Android, un portal familiar e institucional y un backend que consulta datos públicos mediante el MCP oficial de OpenArg.

El usuario hace cada acción. Android explica y señala pasos de una guía de WhatsApp. Para conversar, envía voz o texto al backend, que conecta con OpenAI Realtime sin guardar conversaciones. El árbol de accesibilidad y las capturas no se envían al servidor ni al modelo. OpenArg aporta contexto público para instituciones y las respuestas conservan sus fuentes.

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

Esta entrega incluye el APK compilado en [artifacts/nieto-debug.apk](artifacts/nieto-debug.apk). Los resultados comprobados y las validaciones pendientes están en [docs/VALIDATION.md](docs/VALIDATION.md).

El enlace del APK corresponde al archivo local de esta computadora; los binarios se excluyen de Git. Quien clone el repositorio debe compilar Android. El script de build actualiza la copia de distribución después de una compilación exitosa.

| Entorno | Dirección en Android |
| --- | --- |
| Emulador Android Studio | `http://10.0.2.2:8787` |
| Teléfono por USB, luego de `adb reverse tcp:8787 tcp:8787` | `http://127.0.0.1:8787` |
| Teléfono en la misma red Wi-Fi | `http://IP-LAN-DE-TU-PC:8787` |

Con Wi-Fi, la computadora y el teléfono deben poder comunicarse y el firewall debe permitir el puerto 8787 en esa red. La API usa `HOST=0.0.0.0` para aceptar esa conexión. HTTP está habilitado para depuración local; una distribución necesita HTTPS y configuración de red de producción.

Para abrir también el portal desde otra computadora, agregá su origen exacto, por ejemplo `http://IP-LAN-DE-TU-PC:5173`, a `CORS_ORIGINS` y reiniciá la API. La conexión nativa de Android no necesita ese origen web.

Registrá la instalación con un nombre. Android conserva su credencial de dispositivo y usa CABA (`AR-C`) como territorio en este MVP; el portal permite consultar contexto público de las 24 jurisdicciones. Habilitá el servicio de accesibilidad desde Ajustes cuando quieras usar la guía; se puede desactivar desde el sistema. La persona elige explícitamente si comparte progreso y solicitudes de ayuda con su familiar, y puede revocar el vínculo.

Para enlazar una familia: creá el grupo en el portal familiar, ingresá su código de un solo uso en Android y aceptá los permisos elegidos desde el teléfono. La familia recibe progreso solo con consentimiento y una alerta únicamente si la persona solicita ayuda. No accede al contenido de la pantalla.

## OpenAI Realtime

La conversación usa exactamente `gpt-realtime-2.1-mini`, con la clave únicamente en el backend. La conexión de voz pasa por `/api/assistant/realtime`, autenticada con la credencial de instalación NIETO. El APK no recibe la clave OpenAI. El modo texto usa el mismo modelo mediante Realtime.

En Android, aceptá el aviso de envío de voz y texto a OpenAI y usá Hablar con NIETO. Tocá Terminar de hablar para recibir la respuesta; la captura tiene un máximo de 45 segundos. Cancelar y pasar la app a segundo plano detienen la captura. No hay escucha permanente ni grabaciones guardadas. Las respuestas de voz se reproducen a medida que llegan y su transcripción aparece en pantalla.

El modelo puede ofrecer iniciar la guía local. No interpreta el árbol de pantalla ni puede tocar controles, compartir ubicación, avisar a la familia o acreditar tareas por su cuenta. La lectura de las instrucciones locales sigue usando el TTS del dispositivo. Los fallos de clave, acceso al modelo o cuota se muestran como errores; no se cambia a otro modelo silenciosamente.

El [contrato Realtime](docs/REALTIME_CONTRACT.md) describe el protocolo y sus límites. Configurar una clave no acredita que la cuenta tenga acceso al modelo: la verificación requiere una consulta real.

## OpenArg real

El backend habla con `https://mcp.openarg.org/mcp` mediante MCP Streamable HTTP: inicializa la sesión, descubre herramientas y realiza la consulta. La clave se lee únicamente del entorno del servidor.

```powershell
npm run verify:openarg
```

Sin clave válida, el portal comunica que falta la configuración. No muestra indicadores de reemplazo. El descubrimiento de herramientas puede verificarse sin credenciales; la consulta de datasets requiere una clave. La evidencia inicial está en `apps/api/docs/OPENARG-VERIFICATION.md`.

Los datos de NIETO provienen de sus sesiones registradas. Los datos públicos se presentan con fuente y período disponible. La fecha de consulta no equivale a la fecha del dato. Un resultado sin indicador verificable permanece sin cifra; el centro del mapa representa el territorio seleccionado y no la posición de una persona.

Para CABA, el endpoint territorial obtiene por MCP dos indicadores del Censo 2010: población total y personas de 65 años y más. El mapa los identifica como contexto histórico. Se calculan mediante las tablas reales de OpenArg, sin valores de reemplazo en el código. Las demás jurisdicciones ofrecen búsqueda de catálogo sin reutilizar cifras de CABA; las coincidencias del catálogo no constituyen un filtro geográfico estricto.

## Configuración

| Variable | Uso |
| --- | --- |
| `HOST` | Interfaz API; por defecto `0.0.0.0`. |
| `PORT` | Puerto API; por defecto `8787`. `API_PORT` tiene prioridad si se define. |
| `INSTITUTION_TOKEN` | Secreto de acceso institucional, obligatorio para sus rutas. |
| `OPENARG_API_KEY` | Clave del servicio OpenArg, solo en backend. |
| `OPENAI_API_KEY` | Clave para `gpt-realtime-2.1-mini`, solo en backend. |
| `MINIMUM_COHORT` | Mínimo de participantes antes de devolver agregados; nunca menor que 5. |
| `CORS_ORIGINS` | Orígenes permitidos separados por coma; localhost:5173 por defecto. |
| `OPENARG_TIMEOUT_MS` | Tiempo máximo de consulta; por defecto 25000 ms. |
| `DATA_FILE` | Ruta alternativa para el JSON privado; por defecto `apps/api/data/nieto.json`. |

El proxy de desarrollo lee `API_PORT` o `PORT` de `.env` en la raíz, con 8787 como valor predeterminado. Si cambiás el puerto, reiniciá ambos servicios y ajustá la dirección configurada en Android.

Usá una ruta absoluta para `DATA_FILE` si alternás entre comandos desde la raíz y desde `apps/api`. El archivo guarda identidades seudónimas, vínculos, consentimientos, sesiones y alertas. El directorio de datos y `.env` están excluidos de Git; siguen siendo datos privados locales y requieren cuidado y respaldo.

## Verificación y comandos

```powershell
npm run check           # TypeScript, pruebas del backend, build web
npm test                # Pruebas API con estado temporal
npm run build           # Compilación del portal
npm run api             # Solo API
npm run web             # Solo portal
npm run verify:openarg  # Verificación del MCP oficial
npm run verify:realtime # Verificación breve de texto y audio de salida OpenAI
```

La comprobación de software no reemplaza la prueba en un teléfono: permisos, versión de WhatsApp, voz y servicio de accesibilidad necesitan validación real. El [guion de demo](docs/DEMO.md) distingue lo verificable localmente de los pasos que dependen del dispositivo y de OpenArg.

## Alcance de esta entrega

Es un MVP local con una guía versionada para compartir ubicación por WhatsApp, conversación por voz y texto con OpenAI Realtime, consentimiento familiar, eventos de aprendizaje y un portal institucional con mínimo de cohorte. El perfil inicial es experimental: WhatsApp en español `2.26.*` (`whatsapp-location-es@1`), pendiente de validación en el teléfono concreto. La guía reconoce pantallas compatibles y puede detenerse si no encuentra evidencia; nunca pulsa controles ni envía mensajes automáticamente. La duración registrada mide sesiones acompañadas de NIETO, no tiempo total de uso del teléfono.

No hay cobros, suscripciones activas, autenticación empresarial, predicciones, compatibilidad iOS, escucha permanente ni visión remota. La conversación de IA no amplía la compatibilidad de la guía ni corrige por sí sola los hallazgos del [informe de auditoría](docs/AUDIT.md). El almacenamiento JSON de un solo proceso y los tokens portadores son decisiones de prototipo. Para producción hacen falta identidad y recuperación de acceso, base de datos, HTTPS, políticas de retención, controles operativos y validación de accesibilidad con usuarios.

La correspondencia con el documento de arquitectura original y los pendientes están en [docs/IMPLEMENTATION.md](docs/IMPLEMENTATION.md). El contrato está en [docs/API_CONTRACT.md](docs/API_CONTRACT.md). Nada se publica ni se despliega al ejecutar estos comandos.
