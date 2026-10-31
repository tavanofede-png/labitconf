package org.nieto.assistant.presence

import android.content.Context
import org.json.JSONObject
import org.nieto.assistant.demo.JuryDemoStore
import org.nieto.assistant.demo.PracticeFlow
import org.nieto.assistant.demo.PracticeSession
import org.nieto.assistant.data.ApiFailure
import java.util.UUID

/** Android JSON, encrypted-store contract and bundled public-context smoke; no external effects. */
internal fun juryDemoSmoke(context:Context):Int {
    val compressed=android.util.Base64.decode(context.assets.open("jury-demo-v1.gz.b64").use{it.readBytes()},android.util.Base64.DEFAULT)
    val bundle=java.util.zip.GZIPInputStream(compressed.inputStream()).use{JSONObject(it.bufferedReader().readText())}
    val values=mutableMapOf<String,String>();var clock=1793458800000L
    val demo=JuryDemoStore({values[it].orEmpty()},{k,v->values[k]=v},{bundle},{clock})
    var checks=0
    fun expect(value:Boolean){check(value);checks++}
    fun request(path:String,method:String="GET",body:JSONObject?=null)=demo.request(path,method,body)!!
    fun denied(code:String,action:()->Unit){try{action();error("Expected $code")}catch(e:ApiFailure){expect(e.code==code)}}
    expect(demo.request("/accounts/me","GET",null)==null)
    expect(demo.request("/auth/code/request","POST",JSONObject().put("email","someone@example.com").put("purpose","login"))==null)
    fun challenge()=request("/auth/code/request","POST",JSONObject().put("email","test@labitconf.com").put("role","user").put("purpose","login")).getString("challengeId")
    var id=challenge()
    denied("invalid_code"){request("/auth/code/verify","POST",JSONObject().put("challengeId",id).put("code","123456"))}
    clock+=300001
    denied("invalid_code"){request("/auth/code/verify","POST",JSONObject().put("challengeId",id).put("code","000000"))}
    id=challenge()
    val account=request("/auth/code/verify","POST",JSONObject().put("challengeId",id).put("code","000000"))
    expect(account.getBoolean("demo") && !account.getBoolean("onboardingRequired"))
    denied("invalid_code"){request("/auth/code/verify","POST",JSONObject().put("challengeId",id).put("code","000000"))}
    val family=request("/demo/family/dashboard");val members=family.getJSONArray("members")
    val lucia=(0 until members.length()).map{members.getJSONObject(it)}.single{it.getString("id")=="lucia"}
    expect(!lucia.has("activities") && !lucia.has("screenMinutes") && !lucia.has("websites"))
    denied("help_not_consented"){request("/demo/family/actions","POST",JSONObject().put("memberId","lucia").put("type","ping").put("id","test-ping-lucia"))}
    val ping=JSONObject().put("memberId","marta").put("type","ping").put("id","test-ping-marta")
    request("/demo/family/actions","POST",ping);request("/demo/family/actions","POST",ping)
    expect(request("/family/messages").getJSONArray("messages").length()==1)
    denied("idempotency_conflict"){request("/demo/family/actions","POST",JSONObject(ping.toString()).put("type","location_request"))}
    request("/family/link","PATCH",JSONObject().put("paused",true))
    expect(request("/family/messages").getJSONArray("messages").length()==0)
    request("/family/link","PATCH",JSONObject().put("paused",false))
    expect(request("/family/messages").getJSONArray("messages").length()==1)
    clock+=86400001
    expect(request("/family/messages").getJSONArray("messages").length()==0)
    for(key in bundle.getJSONObject("institutions").keys()) {
        val parts=key.split('|');val period=if(parts[1].length==7)"month" else "months"
        val data=request("/demo/institution/dashboard?territoryId=${parts[0]}&$period=${parts[1]}")
        expect(data.has("publicContext") && data.has("territories") && data.has("metrics"))
    }
    denied("invalid_filter"){request("/demo/institution/dashboard?territoryId=XX&months=6")}
    for(flow in PracticeFlow.entries) {
        var s=PracticeSession(flow).next().next()
        if(flow==PracticeFlow.WHATSAPP)s=s.next().toggleContact("Ana Ríos").toggleContact("Pedro López").next().edit("groupName","Familia TINO").next()
        else s=s.edit("to","judge@example.com").next().edit("subject","Prueba TINO").next().edit("body","Hola, esta es una práctica.").next()
        expect(!s.next().complete)
        expect(s.copy(reviewed=true).next().complete)
        val report=JSONObject().put("id",UUID.randomUUID().toString()).put("flowId",if(flow==PracticeFlow.WHATSAPP)"whatsapp-group" else "email-send")
        request("/demo/practice/report","POST",report);request("/demo/practice/report","POST",report)
    }
    expect(request("/assistant/history").getJSONArray("sessions").length()==2)
    for(role in listOf("family","institution","user"))expect(request("/accounts/space","POST",JSONObject().put("role",role)).getString("role")==role)
    denied("demo_scope"){request("/not-an-api")}
    request("/accounts/session","DELETE");expect(!demo.active)
    expect(demo.request("/accounts/me","GET",null)==null)
    return checks
}
