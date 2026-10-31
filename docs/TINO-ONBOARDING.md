# Bienvenida, login y onboarding web

Entrada pública: `https://tino-web.vercel.app/app/#bienvenida`. Fuente: `apps/web/src/Onboarding.tsx`, `onboarding-data.ts`, `onboarding.css` dentro del mismo monorepo.

## Recorridos implementados en el frontend

- Usuario: datos personales, foto opcional (JPG/PNG/WebP hasta 1 MB), provincia, preferencias y permisos opcionales, cuatro preguntas con respuestas A–D, revisión y finalización.
- Grupo Familiar / crear: identificador de la persona a acompañar, solicitud de vínculo, perfil propio de quien acompaña, permisos y cuatro preguntas A–D específicas de acompañamiento.
- Grupo Familiar / ingresar: correo del administrador para identificar el grupo, correo propio para verificar a quien ingresa, código de seis dígitos, onboarding para quien usa TINO por primera vez. La aprobación del grupo y el consentimiento de la persona acompañada son independientes.
- Institución: persona responsable, institución, tipo, función y territorio; explicación de datos agregados; tres preguntas A–D; revisión y enlace al portal. Este recorrido no concede privilegios institucionales.
- Login: selección del tipo de cuenta, correo, código, reenvío con espera cuando el servidor lo indique, vencimiento y manejo de errores.

Los formularios validan campos; se pueden revisar y cambiar respuestas. El borrador es voluntario y vive en `sessionStorage` de la pestaña. No contiene códigos ni tokens y nunca se restaura como una sesión autenticada. Micrófono y notificaciones solicitan el permiso real del navegador solo al activarlos. La preferencia de guía de pantalla no concede Accesibilidad de Android.

## Integración de identidad pendiente

El backend recibido no contiene identidad por correo ni un proveedor de envío de códigos. No se implementó un código universal ni autenticación basada en almacenamiento del navegador. La vista previa se activa expresamente, está identificada en todas sus pantallas y no crea cuentas, envía correos ni obtiene acceso a datos privados. La persona mostrada en la búsqueda es un ejemplo rotulado, no un resultado de búsqueda real. La conversación de prueba está rotulada como ejemplo sin IA conectada.

El frontend espera estos contratos, que todavía deben implementarse y desplegarse:

| Endpoint | Solicitud | Respuesta |
| --- | --- | --- |
| `POST /auth/code/request` | `email`, `role`, `purpose`, `adminEmail` opcional | `challengeId`, `expiresAt`, `retryAfterSeconds` |
| `POST /auth/code/verify` | `challengeId`, `code` | `sessionToken`, `role`, `onboardingRequired` |
| `POST /accounts/onboarding` | Bearer token, `role`, `profile`, `preferences`, `answers`, datos de grupo opcionales | Confirmación del servidor |

La implementación del servicio debe verificar el correo asociado al token, permisos por cuenta, límites de intentos y reenvíos, códigos de un solo uso con vencimiento, invitaciones sin enumerar cuentas, autorización de administradores y consentimiento revocable. Los tokens de identidad no se reutilizan automáticamente como tokens del portal familiar o institucional existente.

La publicación de Vercel contiene el frontend estático. No despliega la API ni el servicio de correo. El onboarding nuevo es web; no se compiló ni se instaló una nueva APK Android.

## Verificación

TypeScript y build de producción. Navegación en navegador por los tres tipos de cuenta, creación y acceso a grupo, códigos de prueba claramente rotulados, cuatro opciones por pregunta, revisión de respuestas, finalización sin conceder acceso privado y error visible del acceso real cuando el servicio no está conectado. Bienvenida móvil verificada a 320 px sin desbordamiento horizontal.
