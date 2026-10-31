package org.nieto.assistant.entry

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One native editable value keeps paste, selection and backspace intact across six visual slots. */
@Composable fun TinoCodeInput(value: String, enabled: Boolean, onChange: (String) -> Unit) {
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    var focused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    LaunchedEffect(value) {
        if (field.text != value) field = TextFieldValue(value, TextRange(value.length))
        if (value.isEmpty() && enabled) { focus.requestFocus(); keyboard?.show() }
    }
    BasicTextField(value = field, onValueChange = { next ->
        val digits = next.text.filter { it in '0'..'9' }.take(6)
        val cursor = next.text.take(next.selection.end).count { it in '0'..'9' }.coerceAtMost(digits.length)
        field = TextFieldValue(digits, TextRange(cursor))
        if (digits != value) {
            if (digits.length == 6) { keyboard?.hide(); focusManager.clearFocus() }
            onChange(digits)
        }
    }, enabled = enabled, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.Transparent), cursorBrush = SolidColor(Color.Transparent),
        modifier = Modifier.widthIn(max = 440.dp).fillMaxWidth().focusRequester(focus).onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = "Código de verificación"; stateDescription = "${value.length} de 6 números ingresados" },
        decorationBox = { input ->
            Box {
                Row(Modifier.fillMaxWidth().clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(6) { index ->
                        val active = focused && field.selection.start.coerceAtMost(5) == index
                        Surface(shape = RoundedCornerShape(14.dp), color = if (active) Color(0xFFE3F9F6) else Color.White,
                            border = BorderStroke(if (active) 2.dp else 1.dp, if (active) Color(0xFF007C74) else Color(0xFFBFCFCD)),
                            modifier = Modifier.weight(1f).heightIn(min = 64.dp).clickable(enabled = enabled) {
                                val position = index.coerceAtMost(field.text.length)
                                field = field.copy(selection = if (position < field.text.length) TextRange(position, position + 1) else TextRange(position))
                                focus.requestFocus(); keyboard?.show()
                            }) {
                            Box(Modifier.padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                                Text(value.getOrNull(index)?.toString().orEmpty(), fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF101416))
                            }
                        }
                    }
                }
                // The actual editor stays mounted for the IME and accessible text editing.
                Box(Modifier.size(1.dp).alpha(0f)) { input() }
            }
        })
}
