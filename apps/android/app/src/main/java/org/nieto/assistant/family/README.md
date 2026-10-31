# Familia nativa

`FamilyActivity` permite crear/restaurar un grupo, generar una invitación, consultar miembros con sus permisos y métricas autorizadas, y marcar pedidos de ayuda como atendidos mediante la API real de NIETO. No requiere navegador, micrófono ni accesibilidad. El acceso familiar tiene preferencias y alias AES-GCM de Android Keystore separados de la credencial del dispositivo asistido. La pantalla impide capturas del sistema. El respaldo de la credencial se copia solo mediante un botón explícito y se marca como sensible en Android 13+.

El selector distingue **Este teléfono recibe ayuda** de **Acompaño a mi familia**. El primer rol permite aceptar un código con permisos de progreso y ayuda inicialmente desactivados, actualizar el vínculo, pedir ayuda y revocar el vínculo en el servidor. Usa la credencial de dispositivo ya existente; crear/restaurar un grupo de cuidador nunca sustituye esa credencial. Abrir una notificación selecciona el rol de cuidador.

Cerrar acceso elimina la credencial local, su cursor y los avisos; cancela el trabajo periódico. No revoca el token en servidor ni borra el grupo. Una copia privada del token permite restaurar. Un 401/403 borra el acceso local y cancela avisos y trabajo; se necesita restaurar una credencial válida.

Los avisos son **notificaciones locales por consultas periódicas**, no FCM ni push instantáneo. Se activan expresamente y requieren `POST_NOTIFICATIONS` en Android 13+, además del canal habilitado. WorkManager solicita una comprobación con red cada 15 minutos, con primera comprobación periódica después de 15 minutos; Android puede retrasarla por batería, Doze o conectividad. Abrir la actividad comprueba inmediatamente y existe un botón de actualización. No es un servicio de emergencias.

La primera respuesta de cada acceso establece una base sin notificar pedidos antiguos. El cursor persistido usa fecha e identificadores del mismo instante para evitar repeticiones y no retrocede ante respuestas vacías o antiguas. Se notifican solo pedidos nuevos pendientes asociados a un miembro que actualmente autoriza ayuda. En cada consulta se retiran avisos cuyos pedidos ya fueron atendidos o cuyo consentimiento fue revocado; la revocación remota no puede conocerse antes de la siguiente consulta. El texto de notificación es genérico y no incluye nombres, pantalla ni conversaciones. Solo se persisten la credencial cifrada, la dirección, configuración y marcas/IDs de deduplicación; nombres, métricas y pedidos quedan en memoria.

Las solicitudes HTTP se cancelan al abandonar la actividad y al detener el Worker. Una respuesta antigua no puede restaurar una sesión cerrada: cada acceso tiene una generación independiente. Las pruebas unitarias verifican deduplicación, fechas iguales, consentimiento, pedidos resueltos, fechas inválidas y cursores que no retroceden. Falta validación física de permiso/canal, recepción en segundo plano, restricciones del fabricante, restauración y cierre local.

Documentación oficial utilizada para WorkManager 2.12.0 estable y las restricciones del sistema:

- [Versiones de WorkManager](https://developer.android.com/jetpack/androidx/releases/work)
- [Trabajo periódico y demoras](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)
- [Permiso de notificaciones](https://developer.android.com/develop/ui/views/notifications/notification-permission)
