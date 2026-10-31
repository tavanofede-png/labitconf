# Publicación web TINO

Landing pública: https://tino-web.vercel.app/

Proyecto Vercel `tino-web`, ID `prj_5lg88sl1eKPZlJSUf8Gtsqkzs4oH`, cuenta conectada de Jazmin, equipo `belo18`. Deployment de producción verificado `READY`: `dpl_9TqT4NWq1g99H36pysifT2nStDDg`. Sin contraseña ni Vercel Authentication para visitantes.

Bienvenida y onboarding de los tres perfiles: https://tino-web.vercel.app/app/#bienvenida. La vista previa permite recorrer las pantallas sin crear una cuenta; el correo con códigos requiere implementar y desplegar el servicio de identidad descrito en `TINO-ONBOARDING.md`.

La fuente es `apps/web` de este mismo repositorio. Después de compilar con `npm run build`, `node scripts/prepare-vercel.mjs` empaqueta el resultado en `.vercel/output/static`: la landing ocupa `/` y el frontend React `/app/`. Solo se publican archivos web compilados. La API y su estado privado no se incluyen ni se presentan como desplegados en Vercel.

Esta publicación usó archivos subidos mediante la conexión Vercel, no una integración GitHub automática. No hay un webhook de despliegue ni un fork. La rama local es `codex/tino-instituciones`; `origin` es `https://github.com/tavanofede-png/labitconf.git`.

La conexión GitHub corresponde a `bryantmedia-ai`, colaborador con permiso de escritura. El 30 de octubre de 2026 se verificó la instalación del ChatGPT Codex Connector en la cuenta propietaria `tavanofede-png` y una escritura exitosa en `labitconf`. La instalación únicamente en la cuenta del colaborador no habilitaba la escritura en este repositorio. La publicación del código usa la integración GitHub; el despliegue web sigue siendo independiente hasta configurar la integración Git de Vercel.
