package org.nieto.assistant.flow

import java.text.Normalizer

/** Exact semantic label AND known resource ID are required; conversation text is never a target. */
object ControlPolicy {
    fun normalize(raw: CharSequence?): String = Normalizer.normalize(raw?.take(100)?.toString().orEmpty().lowercase(java.util.Locale.ROOT), Normalizer.Form.NFD)
        .replace("\\p{M}".toRegex(), "").trim()
    private val labels = mapOf(
        "adjuntar" to Target.ATTACH,
        "adjuntar archivo" to Target.ATTACH,
        "mas opciones para adjuntar" to Target.ATTACH,
        "ubicacion" to Target.LOCATION,
        "enviar mi ubicacion actual" to Target.SEND_LOCATION,
        "enviar tu ubicacion actual" to Target.SEND_LOCATION,
    )
    private val resourceIds = mapOf(
        Target.ATTACH to setOf("input_attach_button", "input_attach_btn", "attach_button", "conversation_attach"),
        Target.LOCATION to setOf("pickfiletype_location", "attachment_location", "location_picker_button"),
        Target.SEND_LOCATION to setOf("send_location", "send_current_location", "current_location", "location_send", "send_location_btn"),
    )
    fun labelTarget(description: CharSequence?, text: CharSequence?): Target? = labels[normalize(description)] ?: labels[normalize(text)]
    fun accepts(target: Target, ancestorResourceIds: Collection<String>): Boolean = ancestorResourceIds.any { it in resourceIds.getValue(target) }
    fun possibleControl(ancestorResourceIds: Collection<String>): Boolean = resourceIds.values.any { allowed -> ancestorResourceIds.any { it in allowed } }
}
