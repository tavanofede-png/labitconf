# Demostración web pública de NIETO

La demostración funciona dentro del navegador y tiene dos presentaciones del mismo recorrido: una pantalla con marco de teléfono Android y una vista web normal. No necesita instalar el APK, conectar un teléfono, usar ADB ni arrancar un emulador del sistema operativo.

## Enlaces públicos

- [Celular interactivo](https://labitconf-demo.vercel.app/emulador).
- [Vista web normal](https://labitconf-demo.vercel.app/demo).
- [Repositorio público](https://github.com/tavanofede-png/labitconf).

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

El proyecto Vercel `labitconf-demo` (`prj_QSUtOb17dsZRyaUHDJZg4YKhS8Ff`) pertenece al equipo `federicos-projects-45a151a0` y está vinculado a `tavanofede-png/labitconf`, con producción desde `main`. La conexión fue confirmada mediante el contexto Git de Vercel, y el primer build clona el commit `6b3fa30ac6912ae65a6e3a801de926352d9b7b35`. Los siguientes pushes a `main` deben desplegar automáticamente. La protección de acceso se desactivó solo en este nuevo proyecto para permitir pruebas públicas sin cuenta de Vercel.

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
node scripts/verify-vercel-build.mjs --url https://labitconf-demo.vercel.app/
```

Esta comprobación solicita las vistas de la demo, los assets de la versión compilada y las rutas que deben permanecer inaccesibles. Una página de autenticación de Vercel, una versión anterior o una respuesta HTML 200 en `/api/health` provoca un fallo. La publicación solicitada debe poder abrirse sin acceder a la cuenta de Vercel.

La comprobación final en navegador debe recorrer las tres tareas en ambas presentaciones, probar Atrás/Inicio/Reiniciar, cambiar entre teléfono y vista normal, revisar errores de formularios y comprobar que no haya desbordamiento horizontal en móvil. El comportamiento Android real del APK pertenece a otra validación.

## Resultado de la entrega · 31 de octubre de 2026

- Primer despliegue de producción: `dpl_814JazHA4ZwUY7CRxzdQbf4KogjF`, estado `READY`, desde el commit de implementación `6b3fa30`.
- TypeScript y 23 pruebas web aprobadas: 9 del modelo de práctica, 4 de sus interfaces y 10 regresiones del portal. Las 78 pruebas de API también pasaron durante la comprobación del proyecto; sus fuentes no cambian en esta entrega.
- Build local y build de Vercel aprobados. El verificador encontró 24 archivos públicos y cero coincidencias con las tres credenciales locales configuradas.
- Comprobación HTTP pública sin autenticación aprobada: las cinco rutas web y sus assets entregan la versión compilada; las cinco rutas de API, datos y archivos privados comprobadas responden 404.
- QA en Chrome local y sobre `https://labitconf-demo.vercel.app`: los tres recorridos completos en cada presentación, validaciones de formularios, cambio de vista sin perder el borrador, Atrás, Inicio y reinicio. Ubicaciones asociadas al destinatario correcto. Sin errores de página o consola, llamadas a API ni solicitudes externas durante los recorridos con voz desactivada.
- Revisión visual a 1440×900, 1366×768 y 390×844; sin desbordamiento horizontal. El marco cabe en la pantalla de laptop y la ayuda de la vista normal no se superpone con el botón de compartir ubicación.

La lectura y el dictado son funciones opcionales del navegador y dependen de su disponibilidad y permisos. No se validó audio con micrófono real en esta entrega. El dictado puede usar servicios externos propios del navegador. El asistente de la demo devuelve el paso de la práctica actual; no interpreta libremente cualquier pedido ni usa OpenAI Realtime.
