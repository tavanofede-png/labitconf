package org.nieto.assistant.presence

import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.text.InputType
import android.view.*
import android.view.inputmethod.EditorInfo
import android.widget.*
import org.nieto.assistant.AppState
import org.nieto.assistant.ChatLine
import org.nieto.assistant.R

/** A bounded overlay window; no Activity, WebView or navigation for ordinary conversation. */
internal class FloatingChatPanel(context: Context, draft: String,
    close: () -> Unit, openApp: () -> Unit, private val send: (String) -> Unit,
    private val voice: () -> Unit, attach: (Boolean) -> Unit, private val remove: (Int) -> Unit,
) : LinearLayout(context) {
    private val ink = Color.rgb(16,20,22)
    private val teal = Color.rgb(0,214,201)
    private val muted = Color.rgb(76,91,97)
    private val paper = Color.rgb(243,246,246)
    private val messages = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(14),dp(12),dp(14),dp(12)) }
    private val scroll = ScrollView(context).apply { isFillViewport = false; addView(messages) }
    private val files = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(12),dp(4),dp(12),0) }
    private val status = label("", 13f, muted).apply { setPadding(dp(16),dp(5),dp(16),dp(8)); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
    val input = EditText(context).apply {
        hint = "Escribí o dictá tu mensaje…"; setText(draft); setTextColor(ink); setHintTextColor(muted); textSize = 16f
        background = shape(Color.WHITE, 18f, Color.rgb(208,223,220)); setPadding(dp(12),dp(10),dp(12),dp(10))
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        minLines = 1; maxLines = 3; filters = arrayOf(InputFilter.LengthFilter(1000)); imeOptions = EditorInfo.IME_ACTION_SEND
        contentDescription = "Mensaje para TINO"; setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_SEND) { send(text.toString()); true } else false }
    }
    private val sendButton = icon("send", "Enviar mensaje", teal) { send(input.text.toString()) }
    private val mic = icon("mic", "Dictar mensaje", Color.rgb(227,249,246), voice)
    private var previousChat: List<ChatLine>? = null
    private var previousBusy: Boolean? = null
    var dictating = false
    init {
        orientation = VERTICAL; background = shape(paper, 26f, Color.rgb(196,220,216)); elevation = dp(18).toFloat()
        clipToOutline = true; isFocusableInTouchMode = true
        contentDescription = "Chat flotante de TINO"
        val header = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14),dp(12),dp(8),dp(10)); setBackgroundColor(Color.WHITE)
        }
        header.addView(ImageView(context).apply { setImageResource(R.drawable.tino_mascot); contentDescription = "TINO" }, LayoutParams(dp(48),dp(48)))
        header.addView(LinearLayout(context).apply {
            orientation = VERTICAL; setPadding(dp(10),0,0,0)
            addView(label("TINO", 20f, ink).apply { setTypeface(typeface, Typeface.BOLD) })
            addView(label("Acá, con vos", 12f, muted))
        }, LayoutParams(0,LayoutParams.WRAP_CONTENT,1f))
        header.addView(Button(context).apply {
            text = "Ir a la App"; textSize = 13f; isAllCaps = false; minWidth = 0; minimumWidth = 0; setTextColor(Color.rgb(0,107,100))
            background = shape(Color.rgb(227,249,246), 15f); setPadding(dp(10),0,dp(10),0); contentDescription = "Ir a la App TINO"; setOnClickListener { openApp() }
        }, LayoutParams(LayoutParams.WRAP_CONTENT,dp(44)).apply { marginEnd = dp(4) })
        header.addView(icon("close", "Cerrar chat flotante", Color.WHITE, close), LayoutParams(dp(44),dp(44)))
        addView(header)
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT,0,1f))
        addView(files, LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT))
        val composer = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.BOTTOM; setPadding(dp(8),dp(8),dp(8),0) }
        composer.addView(icon("attach", "Adjuntar captura, imagen o PDF", Color.WHITE) {
            PopupMenu(context, itAnchor ?: composer).apply {
                menu.add("Captura o imagen").setOnMenuItemClickListener { attach(true); true }
                menu.add("Documento PDF o imagen").setOnMenuItemClickListener { attach(false); true }
                show()
            }
        }.also { itAnchor = it }, LayoutParams(dp(44),dp(48)))
        composer.addView(input, LayoutParams(0,LayoutParams.WRAP_CONTENT,1f).apply { marginEnd = dp(4) })
        composer.addView(mic, LayoutParams(dp(44),dp(48)))
        composer.addView(sendButton, LayoutParams(dp(44),dp(48)).apply { marginStart = dp(3) })
        addView(composer)
        addView(status)
        setOnKeyListener { _, key, event -> if (key == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) { close(); true } else false }
        requestFocus()
    }
    private var itAnchor: View? = null
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Let Android dismiss the keyboard first. The visible X always closes the panel itself.
        return super.dispatchKeyEvent(event)
    }
    fun notice(text: String) { status.text = text }
    fun transcript(text: String) { notice(if (text.isBlank()) "Te escucho…" else text) }
    fun appendTranscript(text: String) { input.setText((input.text.toString().trim() + " " + text).trim().take(1000)); input.setSelection(input.length()) }
    fun listening(value: Boolean) {
        dictating = value; mic.background = shape(if (value) teal else Color.rgb(227,249,246), 24f)
        mic.contentDescription = if (value) "Terminar dictado" else "Dictar mensaje"
        notice(if (value) "Te escucho… Tocá el micrófono para terminar." else "Revisá tu mensaje antes de enviarlo.")
        sendButton.isEnabled = !value
    }
    fun attachments(items: List<ChatAttachment>) {
        files.removeAllViews()
        items.forEachIndexed { index, file ->
            val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8),dp(4),0,dp(4)); background = shape(Color.WHITE,12f) }
            if (file.mimeType.startsWith("image/")) thumbnail(file)?.let { row.addView(it, LayoutParams(dp(38),dp(38)).apply { marginEnd = dp(8) }) }
            row.addView(label((if (file.mimeType == "application/pdf") "PDF · " else "") + file.name + " · " + ((file.byteSize + 1023)/1024) + " KB",12f,ink).apply { maxLines = 2 },LayoutParams(0,LayoutParams.WRAP_CONTENT,1f))
            row.addView(icon("close","Quitar ${file.name}",Color.WHITE) { remove(index) },LayoutParams(dp(44),dp(44)))
            files.addView(row, LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(3) })
        }
    }
    fun render(state: AppState) {
        input.isEnabled = !state.busy && !dictating
        sendButton.isEnabled = !state.busy && !dictating
        mic.isEnabled = !state.busy
        if (previousChat == state.chat && previousBusy == state.busy) return
        previousChat = state.chat; previousBusy = state.busy
        messages.removeAllViews()
        state.chat.forEach { message ->
            val row = LinearLayout(context).apply { gravity = if (message.fromUser) Gravity.RIGHT else Gravity.LEFT }
            val card = LinearLayout(context).apply {
                orientation = VERTICAL; setPadding(dp(12),dp(10),dp(12),dp(10)); background = shape(if (message.fromUser) Color.rgb(216,248,243) else Color.WHITE,18f)
            }
            card.addView(label(if (message.fromUser) "Vos" else "TINO",12f,muted).apply { setTypeface(typeface,Typeface.BOLD) })
            if (message.text.isNotBlank()) card.addView(label(message.text,16f,ink).apply { setPadding(0,dp(4),0,0); setTextIsSelectable(true) })
            message.attachments.forEach { file -> card.addView(label((if(file.mimeType == "application/pdf") "PDF · " else "Imagen · ") + file.name,13f,Color.rgb(0,107,100)).apply { setPadding(0,dp(5),0,0) }) }
            row.addView(card,LayoutParams(LayoutParams.WRAP_CONTENT,LayoutParams.WRAP_CONTENT).apply { if (message.fromUser) marginStart = dp(32) else marginEnd = dp(32) })
            messages.addView(row,LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        }
        if (state.busy) messages.addView(label("TINO está escribiendo…",14f,muted).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE })
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }
    private fun thumbnail(file: ChatAttachment): ImageView? = runCatching {
        val bytes = android.util.Base64.decode(file.dataBase64,android.util.Base64.NO_WRAP)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
        require(options.outWidth > 0 && options.outHeight > 0)
        options.inSampleSize = 1
        while (options.outWidth / options.inSampleSize > 160 || options.outHeight / options.inSampleSize > 160) options.inSampleSize *= 2
        options.inJustDecodeBounds = false
        ImageView(context).apply { setImageBitmap(BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)); scaleType = ImageView.ScaleType.CENTER_CROP; contentDescription = "Vista previa de ${file.name}" }
    }.getOrNull()
    private fun label(text: String, size: Float, color: Int) = TextView(context).apply { this.text = text; textSize = size; setTextColor(color); setLineSpacing(dp(2).toFloat(),1f) }
    private fun icon(kind: String, label: String, fill: Int, action: () -> Unit) = FrameLayout(context).apply {
        background = shape(fill,24f); contentDescription = label; isFocusable = true; isClickable = true
        addView(OverlayIcon(context,kind,ink),FrameLayout.LayoutParams(dp(22),dp(22),Gravity.CENTER)); setOnClickListener { action() }
    }
    private fun shape(fill: Int, radius: Float, stroke: Int? = null) = GradientDrawable().apply { setColor(fill); cornerRadius = dp(radius.toInt()).toFloat(); stroke?.let { setStroke(dp(1),it) } }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

private class OverlayIcon(context: Context, private val kind: String, color: Int): View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color=color; style=Paint.Style.STROKE; strokeWidth=2f; strokeCap=Paint.Cap.ROUND; strokeJoin=Paint.Join.ROUND }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas); canvas.save(); canvas.scale(width/24f,height/24f)
        when(kind) {
            "close" -> { canvas.drawLine(6f,6f,18f,18f,paint); canvas.drawLine(18f,6f,6f,18f,paint) }
            "send" -> { canvas.drawLine(12f,20f,12f,4f,paint); canvas.drawLine(5f,11f,12f,4f,paint); canvas.drawLine(19f,11f,12f,4f,paint) }
            "mic" -> { canvas.drawRoundRect(9f,2f,15f,14f,3f,3f,paint); canvas.drawArc(5f,5f,19f,19f,0f,180f,false,paint); canvas.drawLine(12f,19f,12f,22f,paint); canvas.drawLine(8f,22f,16f,22f,paint) }
            else -> { val path=Path().apply { moveTo(9f,16f); lineTo(16f,9f); cubicTo(20f,5f,14f,0f,11f,4f); lineTo(4f,12f); cubicTo(-1f,18f,6f,25f,11f,20f); lineTo(19f,12f) }; canvas.drawPath(path,paint) }
        }
        canvas.restore()
    }
}
