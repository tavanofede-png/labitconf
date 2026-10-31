package org.nieto.assistant.entry

enum class AccountRole(val label: String, val wire: String, val subtitle: String) {
    USER("Usuario", "user", "Quiero ayuda con mi teléfono"),
    FAMILY("Grupo Familiar", "family", "Quiero acompañar a alguien"),
    INSTITUTION("Institución", "institution", "Quiero entender y actuar")
}
enum class EntryPage { WELCOME, FAMILY_CHOICE, FIND_PERSON, JOIN_FAMILY, PROFILE, LOGIN, CODE, PERMISSIONS, QUESTIONS, REVIEW, HOME, CONNECTION }
data class EntryQuestion(val id: String, val title: String, val options: List<String>)
fun questionsFor(role: AccountRole): List<EntryQuestion> = when (role) {
    AccountRole.USER -> listOf(
        EntryQuestion("purpose", "¿Para qué usarías TINO?", listOf("Comunicarme: mensajes, fotos y videollamadas", "Resolver trámites y usar servicios digitales", "Entender lo que aparece en mi pantalla", "Aprender nuevas funciones del teléfono")),
        EntryQuestion("interaction", "¿Cómo preferís recibir ayuda?", listOf("Escuchar instrucciones por voz", "Leer instrucciones por chat", "Ver indicaciones y botones destacados", "Combinar voz, texto e indicaciones visuales")),
        EntryQuestion("pace", "¿Cuánto acompañamiento querés?", listOf("Una indicación por vez, con tiempo para probar", "Una explicación completa antes de empezar", "Ayuda solo cuando yo la pida", "Que TINO me pregunte y lo ajustemos juntos")),
        EntryQuestion("priority", "¿Qué te gustaría priorizar?", listOf("Leer y comprender los textos", "Usar la voz para interactuar", "Revisar enlaces y permisos con confianza", "Practicar para ganar autonomía"))
    )
    AccountRole.FAMILY -> listOf(
        EntryQuestion("relationship", "¿Desde qué lugar acompañás?", listOf("Soy familiar", "Soy una amistad o persona cercana", "Soy una persona cuidadora o de apoyo", "Formo parte de un equipo de acompañamiento")),
        EntryQuestion("support", "¿Para qué usarías el grupo?", listOf("Acompañar su aprendizaje y progreso", "Recibir pedidos de ayuda", "Revisar señales de seguridad junto a esa persona", "Combinar progreso, ayuda y seguridad autorizados")),
        EntryQuestion("frequency", "¿Cómo preferís recibir novedades?", listOf("Solo cuando la persona solicite ayuda", "Un resumen diario", "Un resumen semanal", "Avisos relevantes y un resumen periódico")),
        EntryQuestion("guidance", "¿Qué te ayudaría a acompañar mejor?", listOf("Explicaciones simples para orientar a distancia", "Preparar indicaciones breves por voz", "Ver el progreso en un resumen visual", "Más detalle de las tareas autorizadas"))
    )
    AccountRole.INSTITUTION -> listOf(
        EntryQuestion("size", "¿Cuántas personas forman parte de la institución?", listOf("1 persona", "2 a 10 personas", "11 a 100 personas", "+100 personas")),
        EntryQuestion("institutionType", "¿Qué tipo de institución es?", listOf("Educativa", "Gubernamental", "Empresa", "Centro comunitario u ONG")),
        EntryQuestion("objective", "¿Qué están buscando con TINO?", listOf("Entender dónde está la brecha tecnológica", "Analizar comportamientos de uso y métricas", "Implementar TINO en nuestra institución", "Diseñar programas de inclusión digital"))
    )
}
val entryTerritories = listOf("AR-C" to "Ciudad de Buenos Aires", "AR-B" to "Buenos Aires", "AR-X" to "Córdoba", "AR-S" to "Santa Fe", "AR-M" to "Mendoza", "AR-T" to "Tucumán", "AR-A" to "Salta", "AR-E" to "Entre Ríos", "AR-H" to "Chaco", "AR-U" to "Chubut", "AR-K" to "Catamarca", "AR-W" to "Corrientes", "AR-P" to "Formosa", "AR-Y" to "Jujuy", "AR-L" to "La Pampa", "AR-F" to "La Rioja", "AR-N" to "Misiones", "AR-Q" to "Neuquén", "AR-R" to "Río Negro", "AR-J" to "San Juan", "AR-D" to "San Luis", "AR-Z" to "Santa Cruz", "AR-G" to "Santiago del Estero", "AR-V" to "Tierra del Fuego")

enum class ProfileStep(val field: String) {
    NAME("name"), USERNAME("username"), PHONE("phone"), EMAIL("email"), BIRTH_DATE("birthDate"), TERRITORY("territoryId"),
    PHOTO("photo"), ORGANIZATION("organization"), POSITION("position")
}
fun profileStepsFor(role: AccountRole, emailVerified: Boolean): List<ProfileStep> = buildList {
    add(ProfileStep.NAME); add(ProfileStep.USERNAME); add(ProfileStep.PHONE)
    if (!emailVerified) add(ProfileStep.EMAIL)
    add(ProfileStep.BIRTH_DATE)
    add(ProfileStep.TERRITORY)
    if (role == AccountRole.INSTITUTION) { add(ProfileStep.ORGANIZATION); add(ProfileStep.POSITION) }
    add(ProfileStep.PHOTO)
}
fun suggestedUsername(name: String): String {
    val base = java.text.Normalizer.normalize(name.trim(), java.text.Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "").lowercase(java.util.Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), ".").trim('.').take(24).trimEnd('.').ifBlank { "usuario" }
    return if (base.length < 3) "$base.tino" else base
}
fun validUsername(value: String) = Regex("[a-z0-9_.]{3,30}").matches(value)
fun validPhone(value: String) = value.length <= 40 && Regex("\\+?[0-9 ()-]+").matches(value.trim()) && value.count { it in '0'..'9' } in 8..15
fun validBirthDate(value: String, today: java.time.LocalDate = java.time.LocalDate.now()): Boolean = runCatching {
    val date = java.time.LocalDate.parse(value)
    value.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) && date <= today && java.time.temporal.ChronoUnit.YEARS.between(date, today) <= 125
}.getOrDefault(false)
