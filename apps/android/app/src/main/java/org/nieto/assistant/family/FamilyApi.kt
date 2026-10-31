package org.nieto.assistant.family

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

class FamilyApiFailure(val status: Int, message: String) : Exception(message)

class FamilyApi {
    companion object {
        private val client = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    }
    suspend fun request(server: String, path: String, token: String? = null, body: JSONObject? = null): JSONObject =
        suspendCancellableCoroutine { continuation ->
            try {
                val request = Request.Builder().url(FamilyStore.normalizeServer(server) + "/api" + path)
                    .header("Accept", "application/json")
                if (token != null) request.header("Authorization", "Bearer $token")
                if (body != null) request.post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                val call = client.newCall(request.build())
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(FamilyApiFailure(0, "No pude conectar con Familia. Revisá el servidor y la conexión."))
                    }
                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            try {
                                if (!response.isSuccessful) throw FamilyApiFailure(response.code, when (response.code) {
                                    401, 403 -> "El acceso familiar ya no está autorizado. Restaurá una credencial válida."
                                    404 -> "El pedido ya no está disponible. Actualizá Familia."
                                    429 -> "Hubo muchas consultas. Esperá un momento y volvé a intentar."
                                    else -> "El servidor no pudo completar la acción familiar."
                                })
                                val raw = response.body?.charStream()?.use { reader ->
                                    val result = StringBuilder(); val buffer = CharArray(4096)
                                    while (true) {
                                        val count = reader.read(buffer); if (count < 0) break
                                        if (result.length + count > 512_000) throw FamilyApiFailure(0, "La respuesta familiar excedió el tamaño permitido.")
                                        result.append(buffer, 0, count)
                                    }; result.toString()
                                }.orEmpty()
                                val result = if (raw.isBlank()) JSONObject() else JSONObject(raw)
                                if (continuation.isActive) continuation.resume(result)
                            } catch (error: Exception) {
                                if (continuation.isActive) continuation.resumeWithException(if (error is FamilyApiFailure) error else FamilyApiFailure(0, "No pude leer la respuesta de Familia."))
                            }
                        }
                    }
                })
            } catch (_: Exception) {
                if (continuation.isActive) continuation.resumeWithException(FamilyApiFailure(0, "Revisá la dirección y la credencial familiar."))
            }
        }
    suspend fun summary(access: FamilyAccess) = FamilySummary.parse(request(access.serverUrl, "/family/summary", access.token))
    suspend fun invitation(access: FamilyAccess) = request(access.serverUrl, "/family/invitations", access.token, JSONObject())
    suspend fun resolve(access: FamilyAccess, id: String) {
        require(id.matches(Regex("[a-f0-9-]{1,64}")))
        request(access.serverUrl, "/family/alerts/$id/resolve", access.token, JSONObject())
    }
}
