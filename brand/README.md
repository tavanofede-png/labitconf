# Identidad TINO

Logo oficial entregado por el equipo el 30 de octubre de 2026. Se extrajo el fondo blanco mediante la herramienta integrada de imágenes; se preservó el wordmark minúsculo. El ícono usa las letras `ti` y el punto turquesa de la identidad.

## Archivos maestros

- `tino-logo-transparent.png`: wordmark recortado, canal alfa real, sin fondo blanco, incluido el interior de la o.
- `tino-icon-transparent.png`: monograma cuadrado, 1024 × 1024, con transparencia y espacio de seguridad.
- `tino-app-icon-1024.png`: ícono sobre fondo neutro #F3F6F6 para launcher y exportaciones.
- `tino-web-icon-192.png`: ícono de acceso web.

La web usa copias optimizadas en `apps/web/public/brand/`, favicon ICO y `site.webmanifest`, incluido apple-touch-icon de 180px y tamaños 192/512 para accesos instalables. El manifiesto no añade soporte offline por sí solo.

Android usa el logo dentro de la cabecera, el monograma en la burbuja y un launcher adaptativo con variantes API 26/33, monocromo temático para API 33 y PNG de cinco densidades. El nombre visible cambió a TINO; el package y las clases técnicas permanecen compatibles.

`scripts/prepare-brand.mjs` valida el canal alfa y prepara las escalas. Requiere Sharp, solo como herramienta de preparación. No retoca ni diseña las imágenes; la extracción y el monograma fueron producidos con la herramienta integrada de imágenes, sin CLI/API alternativa.

## Prompts finales

Extracción: «Use case: background-extraction. Remove ONLY the white background, including the white counter inside the o, and deliver the exact lowercase wordmark tino on a genuinely transparent alpha background. Preserve the supplied letter shapes, distinctive rounded heavy t, i, n, o, spacing, proportions, black #101416 letterforms and turquoise #00D6C9 dot over the i. Tight horizontal composition. Flat two-color logo, no shadow, texture, border or mockup.»

Ícono: «Use case: logo-brand. Square application icon foreground / favicon symbol for TINO. Create a clean flat ti monogram taken faithfully from the first two letters of the official wordmark: rounded lowercase t and i stem in black #101416, round turquoise #00D6C9 dot above the i. Only ti, centered in a square transparent canvas, approximately 62% width/height for Android adaptive safe space. No robot, character, gradients, outlines, shadows, container or mockup.»

## Verificación Android

Los recursos se instalaron en el código fuente. Este workspace no tiene JDK ni Android SDK, por lo que no se generó un APK nuevo ni se instaló en un teléfono. El APK existente del compañero no se modifica al cambiar los recursos del repositorio: hay que recompilarlo e instalarlo para ver la nueva identidad en el dispositivo.

Además, el checkout recibido referencia `org.nieto.assistant.data.LocalSettings` y `org.nieto.assistant.data.NietoApi`, pero no contiene sus archivos fuente. El patrón `data/` del `.gitignore` coincidía con ese paquete; se agregó una excepción limitada al código Android para que no vuelva a omitirlo. El compañero debe incluir esas clases antes de compilar; no se sustituyeron por implementaciones inventadas en este cambio visual.
