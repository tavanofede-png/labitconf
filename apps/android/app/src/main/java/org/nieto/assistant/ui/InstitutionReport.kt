package org.nieto.assistant.ui

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import org.json.JSONObject
import org.nieto.assistant.R
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One versioned fixture for both formats. Requested dates label the export, never mutate its figures. */
internal object InstitutionReport {
    private val dateFormat=DateTimeFormatter.ofPattern("dd/MM/yyyy")
    fun day(millis: Long): LocalDate=Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
    fun date(millis: Long)=day(millis).format(dateFormat)
    fun filename(start: Long,end: Long,extension: String)="TINO-reporte-${day(start)}_${day(end)}.$extension"
    fun validate(start: Long,end: Long) { require(start<=end) { "La fecha final debe ser igual o posterior a la inicial." } }
    private fun data(context: Context)=context.assets.open("institution-report-demo-v1.json").bufferedReader().use { JSONObject(it.readText()) }
    private fun value(value: Double)=String.format(Locale.forLanguageTag("es-AR"),if(value%1.0==0.0) "%,.0f" else "%,.1f",value)
    private val definitions=listOf(
        Triple("activeUsers","Personas activas",""),Triple("averageAge","Edad media"," años"),Triple("screenMinutesPerDay","Pantalla por día"," min"),
        Triple("autonomyRate","Autonomía digital","%"),Triple("requests","Tareas solicitadas",""),Triple("unsafeLinks","Enlaces inseguros",""))
    fun csv(context: Context,start: Long,end: Long): ByteArray {
        validate(start,end); val data=data(context); val metrics=data.getJSONObject("metrics")
        fun cell(v: Any?)="\""+v.toString().replace("\"","\"\"")+"\""
        val rows=mutableListOf(listOf("escenario","fecha_desde","fecha_hasta","periodo_fixture","seccion","territorio","metrica","valor","unidad"))
        fun row(section: String,territory: String,metric: String,n: Any,unit: String="") { rows+=listOf("tino_demo_fixed_v1",date(start),date(end),data.getString("referencePeriod"),section,territory,metric,n.toString(),unit) }
        definitions.forEach { (key,label,unit)->row("resumen","Argentina",label,metrics.get(key),unit.trim()) }
        data.getJSONArray("flows").objects().forEach { row("tareas","Argentina",it.getString("name"),it.getInt("count"),"solicitudes") }
        data.getJSONArray("territories").objects().forEach { t -> row("territorio",t.getString("name"),"Personas activas",t.getInt("activeUsers"),"personas");row("territorio",t.getString("name"),"Enlaces inseguros",t.getInt("unsafeLinks"),"alertas") }
        return ("\uFEFF"+rows.joinToString("\r\n") { row->row.joinToString(";") { cell(it) } }+"\r\n").toByteArray(Charsets.UTF_8)
    }
    fun pdf(context: Context,start: Long,end: Long): ByteArray {
        validate(start,end); val data=data(context); val metrics=data.getJSONObject("metrics")
        val ink=Color.rgb(16,20,22); val muted=Color.rgb(76,91,97); val teal=Color.rgb(0,214,201); val dark=Color.rgb(0,107,100); val paper=Color.rgb(243,246,246); val red=Color.rgb(185,44,60)
        val regular=context.resources.getFont(R.font.plus_jakarta_sans)
        val bold=Typeface.create(regular,Typeface.BOLD)
        val logo=BitmapFactory.decodeResource(context.resources,R.drawable.tino_logo)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        val document=PdfDocument()
        try {
            fun text(c: Canvas,s: String,x: Float,y: Float,size: Float=10f,color: Int=ink,strong: Boolean=false) { paint.color=color;paint.textSize=size;paint.typeface=if(strong)bold else regular;c.drawText(s,x,y,paint) }
            fun fit(c: Canvas,s: String,x: Float,y: Float,width: Float,size: Float=10f,color: Int=ink,strong: Boolean=false) { paint.typeface=if(strong)bold else regular;var scaled=size;paint.textSize=scaled;while(paint.measureText(s)>width && scaled>6f){scaled-=.25f;paint.textSize=scaled};text(c,s,x,y,scaled,color,strong) }
            fun box(c: Canvas,x: Float,y: Float,w: Float,h: Float,color: Int,radius: Float=12f) { paint.color=color;c.drawRoundRect(x,y,x+w,y+h,radius,radius,paint) }
            fun page(number: Int): PdfDocument.Page {
                val page=document.startPage(PdfDocument.PageInfo.Builder(595,842,number).create());val c=page.canvas
                c.drawColor(Color.WHITE);box(c,0f,0f,595f,8f,teal,0f)
                val ratio=logo.width.toFloat()/logo.height;c.drawBitmap(logo,null,RectF(36f,28f,36f+34f*ratio,62f),paint)
                text(c,"INSTITUCIONES",420f,49f,10f,muted,true)
                box(c,36f,772f,523f,1f,Color.rgb(220,230,228),0f)
                text(c,"TINO · Accesibilidad digital para todas las personas",36f,795f,8f,muted)
                text(c,"${number} / 2",529f,795f,8f,muted)
                return page
            }
            val p1=page(1);val c=p1.canvas
            text(c,"Reporte de impacto digital",36f,108f,26f,ink,true)
            text(c,"Necesidades, aprendizaje y seguridad en Argentina",36f,131f,11f,muted)
            box(c,36f,151f,523f,58f,Color.rgb(227,249,246))
            text(c,"RANGO SELECCIONADO",51f,172f,8f,dark,true)
            text(c,"${date(start)}  al  ${date(end)}",51f,194f,17f,ink,true)
            box(c,392f,168f,152f,24f,teal,12f);text(c,"DATOS DE DEMOSTRACIÓN",402f,184f,7.5f,ink,true)
            definitions.forEachIndexed { i,(key,label,unit)->
                val x=36f+(i%3)*179f;val y=229f+(i/3)*83f
                box(c,x,y,165f,71f,paper)
                text(c,label,x+12,y+20,9f,muted)
                fit(c,value(metrics.getDouble(key))+unit,x+12,y+51,141f,22f,if(key=="unsafeLinks")red else ink,true)
            }
            text(c,"Las 10 tareas más solicitadas",36f,422f,16f,ink,true)
            val flows=data.getJSONArray("flows").objects();val max=flows.maxOf { it.getInt("count") }.toFloat()
            flows.forEachIndexed { i,f ->
                val y=449f+i*27f
                fit(c,"${i+1}. ${f.getString("name")}",36f,y,261f,9f)
                box(c,306f,y-8,169f,7f,paper,3f);box(c,306f,y-8,169f*f.getInt("count")/max,7f,if(i==0)dark else teal,3f)
                text(c,value(f.getDouble("count")),489f,y,9f,ink,true)
            }
            text(c,"Escenario fijo v1. Las cifras no cambian al elegir otro rango de fechas.",36f,743f,8f,muted)
            document.finishPage(p1)
            val p2=page(2);val d=p2.canvas
            text(d,"Una mirada por territorio",36f,106f,24f,ink,true)
            text(d,"Período solicitado: ${date(start)} al ${date(end)}",36f,129f,10f,muted)
            box(d,36f,150f,523f,27f,ink,6f)
            text(d,"PROVINCIA / JURISDICCIÓN",47f,168f,8f,Color.WHITE,true)
            text(d,"USO DE TINO",354f,168f,8f,Color.WHITE,true);text(d,"ENLACES INSEGUROS",451f,168f,7.5f,Color.WHITE,true)
            data.getJSONArray("territories").objects().sortedByDescending { it.getInt("activeUsers") }.forEachIndexed { i,t ->
                val y=177f+i*18f
                if(i%2==0)box(d,36f,y,523f,18f,paper,0f)
                fit(d,t.getString("name"),47f,y+12,286f,8f)
                text(d,value(t.getDouble("activeUsers")),365f,y+12,8f,dark,true)
                text(d,value(t.getDouble("unsafeLinks")),486f,y+12,8f,red,true)
            }
            box(d,36f,638f,523f,110f,Color.rgb(227,249,246))
            text(d,"Cómo leer este reporte",51f,660f,12f,ink,true)
            val notes=listOf("Escenario de demostración TINO, versión fija 1.","Las cifras son ficticias y agregadas; no describen personas identificables.","El calendario personaliza el período del encabezado y del CSV.","La selección de fechas no filtra ni recalcula las cifras de esta demo.","Serie de referencia del fixture: ${data.getString("referencePeriod")}.")
            notes.forEachIndexed { i,line->fit(d,line,51f,679f+i*12f,493f,8f,muted) }
            document.finishPage(p2)
            return ByteArrayOutputStream().use { document.writeTo(it);it.toByteArray() }
        } finally { document.close();logo.recycle() }
    }
}
