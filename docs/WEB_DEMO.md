# Demostración web pública de NIETO

La demostración funciona dentro del navegador y tiene dos presentaciones del mismo recorrido: una pantalla con marco de teléfono Android y una vista web normal. No necesita instalar el APK, conectar un teléfono, usar ADB ni arrancar un emulador del sistema operativo.

## Recorridos y límites

Las pantallas de WhatsApp y Gmail son simulaciones interactivas para la presentación. Permiten practicar crear un grupo, compartir una ubicación de muestra y recorrer la creación de una cuenta de correo. Los cambios ocurren dentro de la demo: **no se crea una cuenta Google, no se envían mensajes a WhatsApp y no se comparte una ubicación real**. Usar los nombres y datos ficticios propuestos; no introducir contraseñas o credenciales reales.

La interfaz debe identificar su carácter de demostración y mostrar el resultado de cada recorrido como simulado. La burbuja de ayuda explica los controles del recorrido de muestra. Esta publicación estática no conecta automáticamente el agente OpenAI, el MCP de OpenArg ni las métricas del backend local del repositorio.

## Rutas

| Dirección | Presentación |
| --- | --- |
| `/` o `/#emulador` | Demostración con marco de teléfono. |
| `/emulador` | Acceso directo a la presentación de teléfono. |
| `/demo` o `/web` | Presentación web normal. |
| `/portal#instituciones` | Portal anterior del proyecto; sus funciones de API requieren un backend separado. |

Las rutas de la demo se reescriben al documento React para que funcionen los enlaces directos y la recarga. La selección visual depende del enrutamiento de la aplicación. `/api/*`, `/downloads/*`, `.env` y los datos internos quedan fuera de esas reescrituras y deben responder 404 en esta publicación.

## Configuración de Vercel

El [vercel.json de la raíz](../vercel.json) usa el monorepo existente:

- Directorio raíz del proyecto Vercel: raíz del repositorio, sin seleccionar `apps/web` como Root Directory.
- Instalación: `npm ci`.
- Compilación: `npm run build`.
- Directorio público: `apps/web/dist`.
- Framework: Vite. No se configuran Functions, Services ni una API de datos para esta demo.

La integración Git de Vercel debe apuntar al repositorio y a su rama de producción elegidos por el equipo. Los commits de esa rama podrán generar nuevos despliegues. El estado de esa conexión y la URL pública se verifican durante la publicación; la presencia de `vercel.json` por sí sola no demuestra que estén conectados.

`.vercelignore` excluye credenciales, estado privado, binarios Android y fuentes del backend de las subidas con la CLI. Se conserva `apps/api/package.json` para resolver el workspace npm. Cuando Vercel clona mediante Git, no se debe confiar en ese archivo como filtro del clon: las credenciales locales nunca deben estar versionadas y solo el directorio compilado es contenido público.

No se necesitan `OPENAI_API_KEY`, `OPENARG_API_KEY`, `INSTITUTION_TOKEN` ni `VERCEL_TOKEN` en el bundle o en variables `VITE_*`. Los valores `VITE_*` son públicos y no deben contener secretos. El backend Android/API anterior conserva sus propios requisitos y no se presenta como desplegado por publicar este frontend.

## Comprobación del build y del despliegue

Desde la raíz:

```powershell
npm run build
node scripts/verify-vercel-build.mjs
```

El verificador comprueba la configuración estática, las rutas permitidas, los assets compilados y la ausencia de las credenciales locales conocidas en los archivos públicos. No imprime sus valores. No sustituye la prueba funcional de los recorridos ni demuestra ausencia de cualquier secreto desconocido.

Después de publicar, verificar también la URL raíz del deployment:

```powershell
node scripts/verify-vercel-build.mjs --url https://URL-DEL-DESPLIEGUE.vercel.app/
```

Esta comprobación solicita las vistas de la demo, los assets de la versión compilada y las rutas que deben permanecer inaccesibles. Una página de autenticación de Vercel, una versión anterior o una respuesta HTML 200 en `/api/health` provoca un fallo. La publicación solicitada debe poder abrirse sin acceder a la cuenta de Vercel.

La comprobación final en navegador debe recorrer las tres tareas en ambas presentaciones, probar Atrás/Inicio/Reiniciar, cambiar entre teléfono y vista normal, revisar errores de formularios y comprobar que no haya desbordamiento horizontal en móvil. El comportamiento Android real del APK pertenece a otra validación.
