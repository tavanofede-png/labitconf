# Pitch de TINO · 60 segundos

El guion editable está en `pitch-story.json`. El montaje usa el recorrido grabado de `https://tino-web.vercel.app/app`, voces de narración y personajes generadas con IA, subtítulos, música original sintetizada y comentarios visuales. La recreación web se identifica dentro del video; la imagen del prototipo Android procede de la verificación nativa del proyecto.

Marta es un personaje ilustrativo. El piloto con personas mayores y una institución se presenta como próximo paso, no como una validación ya realizada.

## Reproducir la edición

Se necesitan Node, las dependencias del repositorio, Playwright con Chrome y su FFmpeg de captura, Python con Pillow y numpy, y un ejecutable FFmpeg con H.264/AAC. Las voces usan `gpt-realtime-2.1-mini` y una variable privada local `OPENAI_API_KEY`. Nunca se publica el archivo `.env`.

1. Ejecutar `node --env-file=.env scripts/video/narrate-pitch.mjs` para generar o reutilizar las voces.
2. Agregar cinco segundos de silencio al inicio de `marta.wav` y silencio al final, exportándolo como `.cache/pitch-video/command.wav`, PCM16 mono, 48 kHz.
3. Ejecutar `node scripts/video/record-pitch.mjs`. La grabación usa una entrada de micrófono de prueba para la voz recreada de Marta y realiza los toques sobre la aplicación publicada; no reemplaza respuestas del backend.
4. Ejecutar `python scripts/video/render-pitch.py`, indicando `FFMPEG_PATH` si FFmpeg no está en la ubicación temporal de producción.

Las tomas y las voces quedan en `.cache/pitch-video/`. El máster Full HD, la copia web y el SRT se exportan a `output/tino-pitch/`. El video publicado está en `apps/web/public/video/` y se ve en `/pitch.html` sin cambiar el enlace principal de la demo.

## Estructura

- 0–10 s: Marta, el deseo de estar cerca y el miedo a equivocarse.
- 10–20 s: TINO como acompañante, por voz y dentro de la pantalla.
- 20–45 s: pedido de Marta, WhatsApp, participantes, nombre y confirmación del grupo.
- 45–55 s: prototipo Android y validación técnica de pantalla, controles y guía.
- 55–60 s: piloto propuesto y cierre de marca.

Las frases se condensaron para respetar el minuto, manteniendo el sentido del pitch. Las voces de Marta, TINO y narración están diferenciadas; los subtítulos están integrados y también disponibles como SRT.
