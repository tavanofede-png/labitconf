<img src="apps/web/public/brand/tino-logo.webp" width="160" alt="TINO">

# Con TINO, la tecnología es de todos.

TINO acompaña a las personas que encuentran barreras al usar la tecnología: por voz y chat, entiende lo que necesitan y las guía paso a paso para ganar autonomía.

## Abrí la demo

### [▶ PROBAR TINO — demo interactiva](https://tino-web.vercel.app)

**[Ver el pitch editado de 60 segundos](https://tino-web.vercel.app/pitch.html)** · Marta crea un grupo de WhatsApp, con voz, guía visual y subtítulos.

Abrí el enlace en **Chrome**, desde desktop o tablet. La app se presenta en una Samsung Tab S10+ vertical, con las instrucciones de prueba al costado. No hace falta instalar nada para evaluar la demo web.

1. Permití el micrófono de la computadora en el aviso que aparece al abrir la demo.
2. Tocá **DEMO** para recorrer las pantallas. Usá el ícono de salida para volver al inicio de Android, o quedate en TINO.
3. Decí **«Hola TINO»** y esperá la respuesta hablada; después contale qué necesitás. También podés decir la consulta completa de una vez. TINO responde con audio sin abrir el chat, incluso sobre Android, WhatsApp o Gmail.
4. Seguí las indicaciones y confirmá cada acción. También podés escribir en el chat.

### Tareas para probar por voz

- «Hola TINO, quiero mandar un mensaje por WhatsApp».
- «Hola TINO, ayudame a crear un grupo de WhatsApp».
- «Hola TINO, quiero compartir mi ubicación».
- «Hola TINO, quiero mandar un correo por Gmail».

Los **recuadros verdes** muestran el control del próximo paso. TINO empieza señalando la app en Android y continúa por voz después de cada acción: abrir, elegir contactos, escribir y confirmar. WhatsApp permite mandar mensajes, crear grupos y compartir una ubicación; Gmail permite redactar, revisar y enviar un correo desde la bandeja.

El chat flotante se abre **solo al tocar la burbuja**. La burbuja aparece **fuera de TINO** y se puede arrastrar a los márgenes. **−** minimiza y conserva la conversación; **×** la cierra y la borra. Al volver a TINO, la burbuja se oculta.

## Tres interfaces, una misma app

Abrí el menú **☰** dentro de TINO para cambiar de interfaz.

| Interfaz | Qué podés probar |
| --- | --- |
| **Usuario** | Voz y chat, transcripción, burbuja flotante y acompañamiento en WhatsApp y Gmail. |
| **Grupo familiar** | Actividad por integrante, pedidos de ayuda, avisos y seguimiento. |
| **Instituciones** | Métricas de uso y autonomía, mapa y top de provincias, ranking por app o workflow, filtros por localidad y fechas, contexto territorial de OpenARG y exportación PDF/CSV. |

Desde la pantalla inicial también podés recorrer los **tres onboardings**, con un recorrido específico para instituciones.

## Android para la demostración presencial

El mismo repositorio incluye la aplicación nativa en [apps/android](apps/android). El diseño para la tablet usa orientación **vertical**, con referencia **1752 × 2800 px** en Samsung Galaxy Tab S10+ de **12,4 pulgadas**.

La [versión Android firmada 0.7.0](https://github.com/tavanofede-png/labitconf/releases/tag/v0.7.0-tino) está en Releases. Para compilar el código actual de esta entrega, consultá [las instrucciones Android](apps/android/README.md). La demo web para evaluar el proyecto está en el botón **PROBAR TINO** de arriba.

## Código del equipo

| Directorio | Contenido |
| --- | --- |
| [apps/web](apps/web) | App del jurado, marco de tablet y las tres interfaces. |
| [apps/android](apps/android) | Aplicación Android nativa y pruebas. |
| [apps/api](apps/api) | Backend compartido, Realtime, cuentas, familia, instituciones y OpenARG. |
| [api](api) | Adaptadores HTTP de la demo y del backend en Vercel. |
| [brand/animation](brand/animation) | Fuente Blender y animación transparente de finalización de onboarding. |

Las claves de OpenAI y OpenARG permanecen en el servidor, fuera del código público y del APK.

## Desarrollo

Requiere Node.js 24, npm y las variables privadas de servidor configuradas en `.env`.

```sh
npm ci
node --env-file-if-exists=.env scripts/jury-local.mjs
```

En otra terminal:

```sh
npm run web
```

Para Android, abrí [apps/android](apps/android) en Android Studio. Los contratos, la configuración del backend y los comandos de verificación están en la [documentación técnica](docs/DEVELOPMENT.md).
