package org.nieto.assistant.demo

enum class PracticeFlow(val title: String) { WHATSAPP("Crear un grupo de WhatsApp"), EMAIL("Enviar un mail") }

/** Interactive exercises only. This state machine never opens or sends to an external app. */
data class PracticeSession(val flow: PracticeFlow, val step: Int=0, val contacts: Set<String> = emptySet(),
    val groupName: String="", val to: String="", val subject: String="", val body: String="",
    val reviewed: Boolean=false, val error: String?=null) {
    init { require(step in 0..6) }
    val complete get()=step==6
    val stage get()=if(flow==PracticeFlow.WHATSAPP) listOf("launch","chats","new_group","people","name","review","done")[step]
        else listOf("launch","inbox","to","subject","body","review","done")[step]
    val instruction get()=if(flow==PracticeFlow.WHATSAPP) listOf(
        "Primero, tocá WhatsApp. El marco verde te muestra dónde.","Buenísimo. Ahora tocá el botón de nuevo chat.",
        "Elegí Nuevo grupo para empezar.","Elegí a una o más personas y después tocá Continuar.",
        "Escribí un nombre para tu grupo y tocá Continuar.","Revisá el nombre y las personas. Confirmá la revisión y tocá Crear grupo.",
        "¡Listo! Creaste tu grupo de práctica. Podés repetirlo cuando quieras.")[step]
        else listOf("Primero, tocá la app de correo.","Ahora tocá Redactar para empezar un mail.",
        "Escribí la dirección de la persona destinataria y tocá Continuar.","Completá el asunto: una frase corta sobre tu mensaje.",
        "Escribí el mensaje. Después vamos a revisarlo juntos.","Revisá destinatario, asunto y mensaje. Confirmá la revisión y tocá Enviar.",
        "¡Muy bien! Tu mail de práctica está en Enviados.")[step]
    fun next(): PracticeSession {
        if(complete)return this
        val problem=when(stage) {
            "people" -> if(contacts.isEmpty()) "Elegí al menos una persona para el grupo." else null
            "name" -> if(groupName.isBlank()) "Escribí un nombre para el grupo." else null
            "to" -> if(!Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(to.trim())) "Ingresá un correo completo, por ejemplo jueces@example.com." else null
            "subject" -> if(subject.isBlank()) "Agregá un asunto para el mail." else null
            "body" -> if(body.isBlank()) "Escribí tu mensaje antes de continuar." else null
            "review" -> if(flow==PracticeFlow.WHATSAPP && (contacts.isEmpty() || groupName.isBlank())) "Revisá el nombre y las personas del grupo." else if(flow==PracticeFlow.EMAIL && (!Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(to.trim()) || subject.isBlank() || body.isBlank())) "Revisá los datos del mail." else if(!reviewed) "Confirmá que revisaste los datos antes de terminar." else null
            else -> null
        }
        return if(problem!=null)copy(error=problem) else copy(step=step+1,error=null)
    }
    fun back()=copy(step=(step-1).coerceAtLeast(0),reviewed=false,error=null)
    fun toggleContact(name: String): PracticeSession {
        require(name in PEOPLE)
        return copy(contacts=if(name in contacts)contacts-name else contacts+name,reviewed=false,error=null)
    }
    fun edit(field: String,value: String): PracticeSession {
        val clean=value.take(if(field=="body")2000 else 120)
        return when(field){"groupName"->copy(groupName=clean);"to"->copy(to=clean);"subject"->copy(subject=clean);"body"->copy(body=clean);else->error("Unknown practice field")}
            .copy(reviewed=false,error=null,step=if(complete)5 else step)
    }
    companion object { val PEOPLE=listOf("Ana Ríos","Pedro López","Sofía García") }
}
