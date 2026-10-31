package org.nieto.assistant.demo

import org.json.JSONArray
import org.json.JSONObject
import org.nieto.assistant.data.ApiFailure
import java.net.URI
import java.net.URLDecoder
import java.time.Instant
import java.util.UUID

/** Isolated fictional account. These local markers are never credentials for the real backend. */
class JuryDemoStore(private val read: (String)->String, private val write: (String,String)->Unit,
    private val bundle: ()->JSONObject, private val now: ()->Long = System::currentTimeMillis) {
    val active get()=read("juryActive")=="true"
    private fun fail(status: Int,code: String,text: String): Nothing=throw ApiFailure(status,code,text)
    private fun objectAt(key: String)=read(key).takeIf{it.isNotBlank()}?.let(::JSONObject)?:JSONObject()
    private fun messages()=read("juryMessages").takeIf{it.isNotBlank()}?.let(::JSONArray)?:JSONArray()
    private fun stamp()=Instant.ofEpochMilli(now()).toString()
    private fun profile(role: String)=JSONObject().put("name","Invitado TINO").put("email","test@labitconf.com").put("username","tino_demo")
        .put("phone","+541155550100").put("birthDate","1990-05-15").put("territoryId","AR-C").put("organization",if(role=="institution")"Laboratorio de inclusión" else "")
    private fun account(): JSONObject {
        val role=read("juryRole").ifBlank{"user"}
        return JSONObject().put("role",role).put("demo",true).put("offlineDemo",true).put("onboardingRequired",false)
            .put("sessionToken","jury-session-$role").put("deviceId","jury-marta").put("deviceToken","jury-device")
            .put("caregiverToken","jury-family").put("institutionToken","jury-institution").put("profile",profile(role))
    }
    @Synchronized fun request(path: String,method: String,body: JSONObject?): JSONObject? {
        val route=path.substringBefore('?')
        if(route=="/auth/code/request" && body?.optString("email")?.trim()?.equals("test@labitconf.com",true)==true) {
            if(body.optString("purpose")!="login")return null
            val role=body.optString("role");if(role !in listOf("user","family","institution"))fail(400,"invalid_role","Elegí un perfil.")
            val challenge=JSONObject().put("id","jury-${UUID.randomUUID()}").put("role",role).put("expiresAt",now()+300000).put("attempts",0)
            write("juryChallenge",challenge.toString())
            return JSONObject().put("challengeId",challenge.getString("id")).put("expiresAt",Instant.ofEpochMilli(challenge.getLong("expiresAt")).toString()).put("retryAfterSeconds",0).put("delivery","demo")
        }
        if(route=="/auth/code/verify" && body?.optString("challengeId")?.startsWith("jury-")==true) {
            val c=objectAt("juryChallenge")
            if(c.optString("id")!=body.optString("challengeId") || c.optLong("expiresAt")<=now() || c.optInt("attempts")>=5)fail(400,"invalid_code","El código venció. Solicitá uno nuevo.")
            c.put("attempts",c.optInt("attempts")+1);write("juryChallenge",c.toString())
            if(body.optString("code")!="000000")fail(400,"invalid_code","Ese código no coincide. Revisalo e intentá nuevamente.")
            write("juryActive","true");write("juryRole",c.getString("role"));write("juryChallenge","")
            if(read("juryStartedAt").isBlank())write("juryStartedAt",now().toString())
            return account()
        }
        if(!active)return null
        if(route=="/accounts/session" && method=="DELETE") {write("juryActive","");return JSONObject().put("ok",true)}
        if(route=="/accounts/me")return account()
        if(route=="/accounts/space") {
            val role=body?.optString("role");if(role !in listOf("user","family","institution"))fail(400,"invalid_role","Elegí un perfil.")
            write("juryRole",role!!);return account()
        }
        if(route=="/devices/me") {
            val d=objectAt("juryDevice")
            if(method=="PATCH" && body!=null)body.keys().forEach { d.put(it,body.get(it)) }
            write("juryDevice",d.toString())
            return JSONObject(d.toString()).put("territoryId",d.optString("territoryId","AR-C")).put("institutionMetricsConsent",d.optBoolean("institutionMetricsConsent"))
        }
        if(route=="/devices")return JSONObject().put("deviceId","jury-marta").put("token","jury-device")
        if(route=="/health")return JSONObject().put("status","ok").put("mode","offline_jury_demo")
        if(route=="/demo/institution/dashboard") {
            val query=URI(path).rawQuery.orEmpty().split('&').mapNotNull{val pair=it.split('=',limit=2);if(pair.size==2)URLDecoder.decode(pair[0],"UTF-8") to URLDecoder.decode(pair[1],"UTF-8") else null}.toMap()
            val period=query["month"]?.takeIf{it.isNotBlank()}?:query["months"]?:"6"
            val key="${query["territoryId"]?:"AR"}|$period"
            return bundle().getJSONObject("institutions").optJSONObject(key)?.let{JSONObject(it.toString())}?:fail(400,"invalid_filter","Elegí una provincia y un período disponibles.")
        }
        if(route in listOf("/demo/family/dashboard","/family/summary"))return family()
        if(route in listOf("/demo/family/actions","/family/messages") && method=="POST") {
            val value=body?:fail(400,"invalid_message","Elegí un aviso.")
            val member=family().getJSONArray("members").let{a->(0 until a.length()).map{a.getJSONObject(it)}.find{it.optString("id")==value.optString("memberId")}}
                ?:fail(404,"member_not_found","No encontramos esa persona.")
            if(!member.getJSONObject("permissions").optBoolean("help"))fail(403,"help_not_consented","Esta persona no comparte acompañamiento ni avisos.")
            val type=value.optString("type");if(type !in listOf("ping","voice","location_request","resolve"))fail(400,"invalid_message","Elegí un aviso válido.")
            if(type=="resolve") {write("juryResolved",stamp());return JSONObject().put("ok",true)}
            val id=value.optString("id");if(!Regex("^[A-Za-z0-9_-]{8,80}$").matches(id))fail(400,"invalid_id","El aviso no tiene un identificador válido.")
            if(type=="voice") {
                val raw=runCatching{java.util.Base64.getDecoder().decode(value.optString("audioBase64"))}.getOrNull()
                if(raw==null || raw.size !in 100..131072 || String(raw,4,4,Charsets.US_ASCII)!="ftyp")fail(400,"invalid_voice","Grabá un aviso de voz válido de hasta 30 segundos.")
            }
            val all=messages();val previous=(0 until all.length()).map{all.getJSONObject(it)}.find{it.optString("id")==id}
            if(previous!=null && listOf("memberId","type","audioBase64").any{previous.optString(it)!=value.optString(it)})fail(409,"idempotency_conflict","Este envío corresponde a otro aviso. Grabá uno nuevo.")
            if(previous==null) {
                val m=JSONObject(value.toString()).put("createdAt",stamp()).put("expiresAt",Instant.ofEpochMilli(now()+86400000).toString()).put("author","Tu grupo TINO")
                all.put(m);while(all.length()>30)all.remove(0);write("juryMessages",all.toString())
            }
            return JSONObject().put("ok",true).put("simulated",true)
        }
        if(route=="/family/messages")return JSONObject().put("messages",visibleMessages().let{all->JSONArray().apply{for(i in 0 until all.length())if(all.getJSONObject(i).optString("memberId")=="marta")put(all.getJSONObject(i))}})
        if(route=="/family/me")return JSONObject().put("group",JSONObject().put("name","Familia Ríos")).put("permissions",family().getJSONArray("members").getJSONObject(0).getJSONObject("permissions")).put("paused",read("juryPaused")=="true")
        if(route=="/family/help")return JSONObject().put("ok",true).put("simulated",true)
        if(route=="/family/link" && method=="POST") {
            if(body?.optString("invitationCode")!="TINO2026")fail(400,"invalid_invitation","Revisá el código de invitación.")
            write("juryPaused","false")
            body.optJSONObject("permissions")?.let { write("juryPermissions",it.toString()) }
            return JSONObject().put("ok",true)
        }
        if(route=="/family/link" && method=="PATCH") {
            body?.optJSONObject("permissions")?.let{val p=objectAt("juryPermissions");it.keys().forEach{k->if(k in listOf("progress","help","usage","safety","location","websites")){if(it.get(k) !is Boolean)fail(400,"invalid_permissions","Elegí un permiso válido.");p.put(k,it.getBoolean(k))}};write("juryPermissions",p.toString())}
            if(body?.has("paused")==true)write("juryPaused",body.optBoolean("paused").toString())
            return JSONObject().put("ok",true)
        }
        if(route=="/family/link" && method=="DELETE") {write("juryPaused","true");return JSONObject().put("ok",true)}
        if(route=="/family/invitations")return JSONObject().put("invitationCode","TINO2026").put("expiresAt",Instant.ofEpochMilli(now()+900000).toString())
        if(route=="/assistant/flows")return JSONObject().put("flows",bundle().getJSONArray("flows"))
        if(route=="/assistant/history")return JSONObject().put("sessions",read("juryPracticeHistory").takeIf{it.isNotBlank()}?.let(::JSONArray)?:JSONArray())
        if(route=="/demo/practice/report") {
            val id=body?.optString("id").orEmpty();val flow=body?.optString("flowId").orEmpty()
            if(flow !in listOf("whatsapp-group","email-send") || !Regex("^[a-f0-9-]{36}$").matches(id))fail(400,"invalid_practice","La práctica no tiene un formato válido.")
            val all=read("juryPracticeHistory").takeIf{it.isNotBlank()}?.let(::JSONArray)?:JSONArray()
            if((0 until all.length()).none{all.getJSONObject(it).optString("id")==id})all.put(JSONObject().put("id",id).put("flowId",flow).put("createdAt",stamp()).put("state","reported").put("resultSource","practice_simulated"))
            while(all.length()>30)all.remove(0);write("juryPracticeHistory",all.toString());return JSONObject().put("ok",true)
        }
        if(route=="/assistant/message") {
            val text=java.text.Normalizer.normalize(body?.optString("text").orEmpty().lowercase(),java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"),"")
            val flow=when {Regex("no quiero|cancel|no mandar|no enviar|no crear").containsMatchIn(text)->null
                text.contains("grupo") && Regex("whatsapp|wasap|wsp").containsMatchIn(text)->"whatsapp-group"
                Regex("mail|correo|email").containsMatchIn(text)->"email-send";else->null}
            return JSONObject().put("mode","practice").put("instruction",if(flow==null)"Podés practicar crear un grupo de WhatsApp o enviar un mail, y explorar Familia e Instituciones desde el menú. Elegí una práctica para empezar." else "Vamos paso a paso. Tocá el botón para abrir la práctica guiada; TINO va a señalar cada control.").apply{if(flow!=null)put("flowId",flow)}
        }
        fail(409,"demo_scope","Esta acción no forma parte de la práctica. Podés volver al chat y elegir uno de los recorridos.")
    }
    private fun visibleMessages(): JSONArray {
        val members=familyMembers();return JSONArray().apply {
            val all=messages();for(i in (all.length()-1) downTo 0){val m=all.getJSONObject(i);if(Instant.parse(m.getString("expiresAt")).toEpochMilli()>now() && members.any{it.optString("id")==m.optString("memberId")&&it.getJSONObject("permissions").optBoolean("help")})put(m)}
        }
    }
    private fun familyMembers(): List<JSONObject> {
        val copy=JSONObject(bundle().getJSONObject("family").toString());shift(copy)
        val a=copy.getJSONArray("members");val overrides=objectAt("juryPermissions")
        return (0 until a.length()).map{a.getJSONObject(it)}.onEach{m->
            if(m.optString("id")=="marta") {
                val p=m.getJSONObject("permissions");overrides.keys().forEach{k->p.put(k,overrides.optBoolean(k))}
                if(read("juryPaused")=="true")p.keys().forEach{k->p.put(k,false)}
                if(!p.optBoolean("progress"))listOf("activities","lastActivity","lastActiveAt","helpRequests","requestsToday","weeklyRequests","tinoMinutes","weeklyTino","online").forEach{m.remove(it)}
                if(!p.optBoolean("usage"))listOf("screenMinutes","appUsage","weeklyScreen","weeklyDays").forEach{m.remove(it)}
                if(!p.optBoolean("websites"))m.remove("websites")
                if(!p.optBoolean("location"))m.remove("location")
            }
        }
    }
    private fun family(): JSONObject {
        val f=JSONObject(bundle().getJSONObject("family").toString());shift(f)
        f.put("members",JSONArray(familyMembers())).put("snapshotAt",stamp()).put("messages",visibleMessages())
        val alerts=f.getJSONArray("alerts");if(read("juryResolved").isNotBlank())for(i in 0 until alerts.length())alerts.getJSONObject(i).put("resolvedAt",read("juryResolved"))
        val allowed=familyMembers().filter{it.getJSONObject("permissions").optBoolean("safety")}.map{it.getString("id")}.toSet()
        f.put("alerts",JSONArray((0 until alerts.length()).map{alerts.getJSONObject(it)}.filter{it.optString("memberId") in allowed}))
        return f
    }
    private fun shift(value: Any) {
        val anchor=read("juryStartedAt").toLongOrNull()?:now();val reference=Instant.parse(bundle().getString("referenceAt")).toEpochMilli()
        when(value) {
            is JSONObject -> value.keys().asSequence().toList().forEach{k->val v=value.get(k);if(v is String && Regex("^\\d{4}-\\d{2}-\\d{2}T.*Z$").matches(v))runCatching{value.put(k,Instant.ofEpochMilli(Instant.parse(v).toEpochMilli()+anchor-reference).toString())} else if(v is JSONObject || v is JSONArray)shift(v)}
            is JSONArray -> for(i in 0 until value.length()){val v=value.get(i);if(v is JSONObject || v is JSONArray)shift(v)}
        }
    }
}
