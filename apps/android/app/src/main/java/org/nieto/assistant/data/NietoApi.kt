package org.nieto.assistant.data

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiFailure(message: String, val status: Int = 0) : Exception(message)
class NietoApi(private val settings: LocalSettings) {
    private val client = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
    suspend fun request(path: String, method: String = "GET", body: JSONObject? = null, authenticated: Boolean = true,
        serverUrl: String = settings.baseUrl, installationToken: String? = null): JSONObject =
        suspendCancellableCoroutine { continuation ->
            try {
                val request = Request.Builder().url(serverUrl + "/api" + path).header("Accept", "application/json")
                if (authenticated) {
                    val credential = installationToken ?: settings.token
                    if (credential.isBlank()) throw ApiFailure("Conectá este teléfono al servidor desde Ajustes.")
                    request.header("Authorization", "Bearer $credential")
                }
                val requestBody = if (body != null || method in listOf("POST", "PATCH", "PUT"))
                    (body?.toString() ?: "{}").toRequestBody("application/json; charset=utf-8".toMediaType()) else null
                request.method(method, requestBody)
                val timeout = if (path == "/assistant/message") 70L else 20L
                val call = client.newBuilder().readTimeout(timeout, TimeUnit.SECONDS).callTimeout(timeout + 5, TimeUnit.SECONDS)
                    .build().newCall(request.build())
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(ApiFailure("No pude conectarme. Revisá la dirección del servidor y la conexión del teléfono."))
                    }
                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            try {
                                val text = response.body?.charStream()?.use { reader ->
                                    val result = StringBuilder(); val buffer = CharArray(4096)
                                    while (true) {
                                        val count = reader.read(buffer)
                                        if (count < 0) break
                                        if (result.length + count > 512_000) throw ApiFailure("La respuesta del servidor es demasiado grande.")
                                        result.append(buffer, 0, count)
                                    }; result.toString()
                                }.orEmpty()
                                val publicError = if (!response.isSuccessful) runCatching { JSONObject(text).optJSONObject("error") }.getOrNull() else null
                                if (!response.isSuccessful) throw ApiFailure(when {
                                    publicError?.optString("code") == "device_activation_required" -> "La asistencia necesita autorización. Pedí un código al equipo e ingresalo en Ajustes."
                                    path in listOf("/devices", "/devices/me/activation") && response.code in listOf(400, 401, 403, 404, 409, 410) ->
                                        "El código de conexión no fue aceptado. Pedí uno nuevo al equipo; es de un solo uso y vence a los 15 minutos."
                                    response.code in listOf(401, 403) -> "El servidor no autorizó esta acción. Revisá la conexión y los permisos."
                                    response.code == 404 -> "No encontré el recurso. Revisá el código de invitación o la dirección del servidor."
                                    response.code == 409 -> if (path == "/assistant/message") "Ya hay una respuesta de voz activa. Cancelala y volvé a intentar." else "Esta invitación ya fue usada o el teléfono ya está vinculado."
                                    response.code == 429 -> "Hubo muchas consultas. Esperá un momento e intentá de nuevo."
                                    else -> publicError?.optString("message")?.take(180)
                                        ?.takeIf { it.isNotBlank() } ?: "El servidor no pudo completar la acción (HTTP ${response.code})."
                                }, response.code)
                                val result = if (text.isBlank()) JSONObject() else JSONObject(text)
                                if (continuation.isActive) continuation.resume(result)
                            } catch (error: Exception) {
                                if (continuation.isActive) continuation.resumeWithException(if (error is ApiFailure) error else ApiFailure("No pude leer la respuesta del servidor."))
                            }
                        }
                    }
                })
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(if (error is ApiFailure) error else ApiFailure("Revisá la dirección del servidor y volvé a intentar."))
            }
        }

    suspend fun register(enrollmentCode: String = "") {
        val code = enrollmentCode.trim().uppercase(java.util.Locale.ROOT)
        if (settings.token.isNotBlank()) {
            if (code.isNotBlank()) {
                require(code.matches(Regex("[A-F0-9]{12}"))) { "Ingresá el código de conexión de 12 caracteres que te dio el equipo." }
                request("/devices/me/activation", "POST", JSONObject().put("enrollmentCode", code))
            }
            return
        }
        require(code.matches(Regex("[A-F0-9]{12}"))) { "Pedí un código de conexión al equipo e ingresalo en Ajustes antes de conectar este teléfono." }
        val response = request("/devices", "POST", JSONObject().put("name", settings.deviceName).put("territoryId", settings.territoryId).put("enrollmentCode", code), false)
        val credential = response.getString("token")
        require(credential.isNotBlank())
        settings.token = credential
        settings.deviceId = response.getString("deviceId")
    }
}
