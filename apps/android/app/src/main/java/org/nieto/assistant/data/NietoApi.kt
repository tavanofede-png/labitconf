package org.nieto.assistant.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiFailure(val status:Int,val code:String,message:String):Exception(message) {
    constructor(message:String,status:Int=0):this(status,"request_failed",message)
}

class NietoApi(private val settings:LocalSettings) {
    private val registration=Mutex()
    private val client=OkHttpClient.Builder().connectTimeout(12,TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
    suspend fun register(enrollmentCode:String="")=registration.withLock {
        val code=enrollmentCode.trim().uppercase(java.util.Locale.ROOT)
        if(settings.token.isNotBlank()) {
            if(code.isNotBlank()) {
                require(code.matches(Regex("[A-F0-9]{12}"))) { "Ingresá el código de conexión de 12 caracteres que te dio el equipo." }
                request("/devices/me/activation","POST",JSONObject().put("enrollmentCode",code))
            }
            return@withLock
        }
        require(code.matches(Regex("[A-F0-9]{12}"))) { "Pedí un código de conexión al equipo e ingresalo en Ajustes antes de conectar este teléfono." }
        val reply=request("/devices","POST",JSONObject().put("name",settings.deviceName).put("territoryId",settings.territoryId).put("enrollmentCode",code),authenticated=false)
        require(reply.getString("token").isNotBlank())
        settings.installation(reply.getString("deviceId"),reply.getString("token"))
    }
    suspend fun request(path:String,method:String="GET",body:JSONObject?=null,
        authenticated:Boolean=true,serverUrl:String=settings.baseUrl,installationToken:String=settings.token):JSONObject=withContext(Dispatchers.IO) {
        require(path.startsWith('/') && !path.startsWith("//") && !path.contains("..")) { "Ruta de API inválida." }
        if(installationToken.startsWith("jury-"))throw ApiFailure(401,"invalid_session","Conectá este dispositivo con un código del equipo.")
        require(serverUrl.isNotBlank()) { "Configurá la conexión del servidor." }
        if(authenticated)require(installationToken.isNotBlank()) { "Primero conectá tu cuenta o este dispositivo." }
        suspendCancellableCoroutine { continuation ->
            try {
                val builder=Request.Builder().url(serverUrl.trimEnd('/')+"/api"+path).header("Accept","application/json")
                if(authenticated)builder.header("Authorization","Bearer $installationToken")
                val payload=if(body!=null || method in listOf("POST","PATCH","PUT"))
                    (body?.toString()?:"{}").toRequestBody("application/json; charset=utf-8".toMediaType()) else null
                builder.method(method,payload)
                val timeout=if(path=="/assistant/message")70L else 20L
                val call=client.newBuilder().readTimeout(timeout,TimeUnit.SECONDS).callTimeout(timeout+5,TimeUnit.SECONDS).build().newCall(builder.build())
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object:Callback {
                    override fun onFailure(call:Call,e:IOException) {
                        if(continuation.isActive)continuation.resumeWithException(ApiFailure(0,"connection_failed","No pude conectar con el servidor. Revisá la dirección y la conexión de la tablet."))
                    }
                    override fun onResponse(call:Call,response:Response) {
                        response.use {
                            try {
                                val bytes=response.body?.byteStream()?.use { input ->
                                    val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                                    while(true) { val count=input.read(buffer);if(count<0)break
                                        if(output.size()+count>2_097_152)throw ApiFailure("La respuesta del servidor es demasiado grande.")
                                        output.write(buffer,0,count)
                                    };output.toByteArray()
                                }?:ByteArray(0)
                                val result=runCatching { JSONObject(String(bytes,Charsets.UTF_8)) }.getOrNull()
                                if(!response.isSuccessful) {
                                    val error=result?.optJSONObject("error");val code=error?.optString("code").orEmpty()
                                    val message=when {
                                        code=="device_activation_required"->"La asistencia necesita autorización. Pedí un código al equipo e ingresalo en Ajustes."
                                        path in listOf("/devices","/devices/me/activation") && response.code in listOf(400,401,403,404,409,410)->"El código de conexión no fue aceptado. Pedí uno nuevo al equipo; es de un uso y vence a los 15 minutos."
                                        code=="email_not_configured"->"No pudimos completar el acceso. Intentá nuevamente."
                                        else->error?.optString("message")?.takeIf { it.isNotBlank() }?:"No pudimos completar la solicitud. Intentá nuevamente."
                                    }
                                    throw ApiFailure(response.code,code,message)
                                }
                                val value=result?:throw ApiFailure(response.code,"invalid_response","El servidor no devolvió una respuesta válida.")
                                if(continuation.isActive)continuation.resume(value)
                            }catch(error:Exception) {
                                if(continuation.isActive)continuation.resumeWithException(if(error is ApiFailure)error else ApiFailure("No pude leer la respuesta del servidor."))
                            }
                        }
                    }
                })
            }catch(error:Exception) { if(continuation.isActive)continuation.resumeWithException(if(error is ApiFailure)error else ApiFailure("Revisá la dirección del servidor y volvé a intentar.")) }
        }
    }
}
