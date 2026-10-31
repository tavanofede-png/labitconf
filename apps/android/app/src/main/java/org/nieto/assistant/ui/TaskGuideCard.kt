package org.nieto.assistant.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nieto.assistant.flow.GuidedTask
import org.nieto.assistant.flow.EmailDraft

@Composable fun TaskGuideCard(task: GuidedTask, busy: Boolean, onNext: () -> Unit, onOpen: () -> Unit, onRepeat: () -> Unit, onCancel: () -> Unit, onDismiss: () -> Unit, showOverlaySetup: Boolean, onOverlaySetup: () -> Unit) {
    Surface(shape = RoundedCornerShape(22.dp), color = Color.White, border = BorderStroke(1.dp, Color(0xFFBFDAD6)), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(task.title, color = Color(0xFF101416), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            if (task.active) {
                Text("Paso ${task.index + 1} de ${task.steps.size}", color = Color(0xFF4C5B61), fontSize = 15.sp)
                LinearProgressIndicator(progress = { (task.index + 1f) / task.steps.size }, modifier = Modifier.fillMaxWidth(), color = Color(0xFF007C74))
                Text(task.current.instruction, fontSize = 19.sp, lineHeight = 28.sp)
                Button(onClick = onNext, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) { Text(if (task.current.final) task.completionLabel else "Ya lo hice", fontSize = 17.sp) }
                OutlinedButton(onClick = onOpen, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) { Text(if (task.flowId == "email-send") "Volver a mi correo" else "Volver a WhatsApp") }
                if (showOverlaySetup) TextButton(onClick = onOverlaySetup) { Text("Mostrar ayuda sobre la app") }
                Row { TextButton(onClick = onRepeat) { Text("Escuchar de nuevo") }; TextButton(onClick = onCancel, enabled = !busy) { Text("Cancelar guía") } }
            } else {
                Text(if (task.completed) "Guía completada · confirmado por vos" else "Guía cancelada", color = Color(0xFF4C5B61), fontSize = 17.sp)
                TextButton(onClick = onDismiss) { Text("Listo") }
            }
        }
    }
}

@Composable fun EmailDraftPreview(draft: EmailDraft) {
    Surface(shape = RoundedCornerShape(18.dp), color = Color(0xFFE3F9F6), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Revisá tu borrador", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("Para: ${draft.to}", fontSize = 16.sp)
            Text("Asunto: ${draft.subject}", fontSize = 16.sp)
            Text(draft.body, fontSize = 18.sp, lineHeight = 26.sp)
        }
    }
}
