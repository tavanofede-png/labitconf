package org.nieto.assistant.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

class ApiFailure(val status: Int, val code: String, message: String) : Exception(message)

class NietoApi(private val settings: LocalSettings) {
    private val registration = Mutex()
    suspend fun register() = registration.withLock {
        if (settings.token.isBlank()) {
            val reply = request("/devices", "POST", JSONObject().put("name", settings.deviceName).put("territoryId", settings.territoryId), authenticated = false)
            settings.installation(reply.getString("deviceId"), reply.getString("token"))
        }
    }
    suspend fun request(path: String, method: String = "GET", body: JSONObject? = null,
        authenticated: Boolean = true, serverUrl: String = settings.baseUrl,
        installationToken: String = settings.token): JSONObject = withContext(Dispatchers.IO) {
        require(path.startsWith('/') && !path.startsWith("//") && !path.contains("..")) { "Ruta de API inválida." }
        require(serverUrl.isNotBlank()) { "Configurá la conexión del servidor." }
        if (authenticated) require(installationToken.isNotBlank()) { "Primero conectá tu cuenta o este dispositivo." }
        val connection = URI(serverUrl.trimEnd('/') + "/api" + path).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 12_000; connection.readTimeout = 20_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            if (authenticated) connection.setRequestProperty("Authorization", "Bearer $installationToken")
            if (body != null) {
                connection.doOutput = true; connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= 2_097_152) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: ByteArray(0)
            require(bytes.size <= 2_097_152) { "La respuesta del servidor es demasiado grande." }
            val result = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull()
            if (status !in 200..299) {
                val error = result?.optJSONObject("error")
                val code = error?.optString("code").orEmpty()
                val message = if (code == "email_not_configured") "No pudimos completar el acceso. Intentá nuevamente." else error?.optString("message")?.takeIf { it.isNotBlank() }
                    ?: "No pudimos completar la solicitud. Intentá nuevamente."
                throw ApiFailure(status, code, message)
            }
            result ?: throw ApiFailure(status, "invalid_response", "El servidor no devolvió una respuesta válida.")
        } catch (error: java.io.IOException) {
            throw ApiFailure(0, "connection_failed", "No pude conectar con el servidor. Revisá la dirección y la conexión de la tablet.")
        } finally { connection.disconnect() }
    }
}
