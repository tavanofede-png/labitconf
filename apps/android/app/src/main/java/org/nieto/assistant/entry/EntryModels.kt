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
        EntryQuestion("objective", "¿Qué quiere mejorar tu institución?", listOf("Acceso y autonomía digital", "Talleres y capacitación", "Prevención de riesgos digitales", "Planificación territorial de servicios")),
        EntryQuestion("evidence", "¿Qué información necesitás priorizar?", listOf("Necesidades de ayuda por territorio", "Flujos más pedidos y dificultades", "Uso y aprendizaje en el tiempo", "Resultados de programas")),
        EntryQuestion("work", "¿Cómo vas a usar el portal?", listOf("Consultar indicadores y mapas", "Diseñar programas de acompañamiento", "Evaluar programas existentes", "Preparar informes para mi equipo"))
    )
}
val entryTerritories = listOf("AR-C" to "Ciudad de Buenos Aires", "AR-B" to "Buenos Aires", "AR-X" to "Córdoba", "AR-S" to "Santa Fe", "AR-M" to "Mendoza", "AR-T" to "Tucumán", "AR-A" to "Salta", "AR-E" to "Entre Ríos", "AR-H" to "Chaco", "AR-U" to "Chubut", "AR-K" to "Catamarca", "AR-W" to "Corrientes", "AR-P" to "Formosa", "AR-Y" to "Jujuy", "AR-L" to "La Pampa", "AR-F" to "La Rioja", "AR-N" to "Misiones", "AR-Q" to "Neuquén", "AR-R" to "Río Negro", "AR-J" to "San Juan", "AR-D" to "San Luis", "AR-Z" to "Santa Cruz", "AR-G" to "Santiago del Estero", "AR-V" to "Tierra del Fuego")
