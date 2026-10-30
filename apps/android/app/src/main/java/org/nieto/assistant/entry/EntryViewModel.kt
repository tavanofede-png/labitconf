package org.nieto.assistant.entry

import android.app.Application
import android.util.Patterns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.nieto.assistant.NietoApp
import java.time.Instant

data class EntryState(
    val page: EntryPage = EntryPage.WELCOME, val role: AccountRole = AccountRole.USER,
    val fields: Map<String, String> = mapOf("territoryId" to "AR-C", "organizationType" to "community"),
    val answers: Map<String, Int> = emptyMap(), val preferences: Map<String, Boolean> = emptyMap(),
    val familyMode: String = "create", val question: Int = 0, val login: Boolean = false,
    val firstTime: Boolean = true, val accepted: Boolean = false, val busy: Boolean = false,
    val error: String? = null, val message: String? = null, val challengeId: String = "",
    val expiresAt: String = "", val resendAt: Long = 0, val code: String = "", val delivery: String = "email",
    val dashboard: JSONObject? = null, val context: JSONObject? = null, val invitationCode: String = ""
) { fun field(key: String) = fields[key].orEmpty() }

class EntryViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    private val app = application as NietoApp
    private val mutable = MutableStateFlow(restore())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var generation = 0
    private fun restore(): EntryState = runCatching {
        val data = saved.get<String>("entryDraft")?.let(::JSONObject) ?: return EntryState()
        val fields = data.optJSONObject("fields") ?: JSONObject()
        val answers = data.optJSONObject("answers") ?: JSONObject()
        val prefs = data.optJSONObject("preferences") ?: JSONObject()
        EntryState(page = EntryPage.valueOf(data.optString("page", "WELCOME")), role = AccountRole.valueOf(data.optString("role", "USER")),
            fields = fields.keys().asSequence().associateWith { fields.getString(it) }, answers = answers.keys().asSequence().associateWith { answers.getInt(it) }.filterValues { it in 0..3 },
            preferences = prefs.keys().asSequence().associateWith { prefs.getBoolean(it) }, familyMode = data.optString("familyMode", "create"), question = data.optInt("question").coerceIn(0, 2),
            login = data.optBoolean("login"), firstTime = data.optBoolean("firstTime", true))
    }.getOrDefault(EntryState())
    private fun change(block: (EntryState) -> EntryState) {
        mutable.value = block(mutable.value)
        val s = state.value
        // Codes, challenge identifiers, tokens and downloaded private data never enter saved-state bundles.
        val safePage = if (s.page == EntryPage.CODE) if (s.login) EntryPage.LOGIN else EntryPage.PROFILE else s.page
        saved["entryDraft"] = JSONObject().put("page", safePage.name).put("role", s.role.name).put("fields", JSONObject(s.fields))
            .put("answers", JSONObject(s.answers)).put("preferences", JSONObject(s.preferences)).put("familyMode", s.familyMode)
            .put("question", s.question).put("login", s.login).put("firstTime", s.firstTime).toString()
    }
    fun move(page: EntryPage) { generation++; job?.cancel(); change { it.copy(page = page, busy = false, error = null, message = null, accepted = false) } }
    fun start(role: AccountRole) { generation++; job?.cancel(); listOf("accountSession", "caregiverToken", "institutionToken", "installation").forEach { app.settings.write(it, "") }; mutable.value = EntryState(role = role); move(if (role == AccountRole.FAMILY) EntryPage.FAMILY_CHOICE else EntryPage.PROFILE) }
    fun field(key: String, value: String) = change { it.copy(fields = it.fields + (key to value.take(200)), error = null) }
    fun photo(uri: String) = field("photo", uri)
    fun answer(index: Int) = change { it.copy(answers = it.answers + (questionsFor(it.role)[it.question].id to index)) }
    fun preference(key: String, enabled: Boolean) = change { it.copy(preferences = it.preferences + (key to enabled)) }
    fun accept(value: Boolean) = change { it.copy(accepted = value) }
    fun firstTime(value: Boolean) = change { it.copy(firstTime = value) }
    fun loginRole(role: AccountRole) = change { it.copy(role = role, challengeId = "", code = "") }
    fun code(value: String) = change { it.copy(code = value.filter(Char::isDigit).take(6), error = null) }
    fun message(value: String) = change { it.copy(message = value) }
    fun login() { change { it.copy(login = true, firstTime = false) }; move(EntryPage.LOGIN) }
    fun family(mode: String) { change { it.copy(familyMode = mode, login = mode == "join", firstTime = true) }; move(if (mode == "join") EntryPage.JOIN_FAMILY else EntryPage.FIND_PERSON) }
    fun findPerson() {
        if (state.value.field("targetIdentifier").trim().length < 3) { change { it.copy(error = "Ingresá su usuario, correo o teléfono.") }; return }
        // No unauthenticated people directory: the server resolves the invitation after account verification.
        move(EntryPage.PROFILE)
    }
    private fun work(block: suspend () -> Unit) {
        if (state.value.busy) return
        val current = generation
        change { it.copy(busy = true, error = null, message = null) }
        job = viewModelScope.launch {
            try { block() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == current) change { it.copy(error = e.message ?: "No pude completar la acción.") } }
            finally { if (generation == current) change { it.copy(busy = false) } }
        }
    }
    fun submitProfile() {
        val s = state.value
        if (s.field("name").trim().isEmpty() || !Patterns.EMAIL_ADDRESS.matcher(s.field("email").trim()).matches() || (s.role == AccountRole.INSTITUTION && s.field("organization").isBlank())) {
            change { it.copy(error = "Completá tu nombre, un correo válido y los datos obligatorios.") }; return
        }
        val username = s.field("username")
        if (username.isNotBlank() && !Regex("[A-Za-z0-9_.]{3,30}").matches(username)) { change { it.copy(error = "El usuario admite de 3 a 30 letras, números, puntos o guiones bajos.") }; return }
        if (app.settings.read("accountSession").isNotBlank()) move(EntryPage.PERMISSIONS) else requestCode()
    }
    fun requestCode() {
        val s = state.value
        if (System.currentTimeMillis() < s.resendAt) { change { it.copy(error = "Esperá un momento antes de solicitar otro código.") }; return }
        if (!Patterns.EMAIL_ADDRESS.matcher(s.field("email").trim()).matches() || (s.page == EntryPage.JOIN_FAMILY && !Patterns.EMAIL_ADDRESS.matcher(s.field("adminEmail")).matches())) { change { it.copy(error = "Ingresá un correo válido.") }; return }
        work {
            val body = JSONObject().put("email", s.field("email").trim()).put("role", s.role.wire).put("purpose", if (s.familyMode == "join" && s.role == AccountRole.FAMILY) "family_join" else if (s.login) "login" else "signup")
            if (s.role == AccountRole.FAMILY && s.familyMode == "join") body.put("adminEmail", s.field("adminEmail").trim())
            val reply = app.api.request("/auth/code/request", "POST", body, authenticated = false)
            change { it.copy(page = EntryPage.CODE, challengeId = reply.getString("challengeId"), expiresAt = reply.getString("expiresAt"), code = "", delivery = reply.optString("delivery", "email"), resendAt = System.currentTimeMillis() + reply.optLong("retryAfterSeconds", 60) * 1000) }
        }
    }
    fun verify() {
        val s = state.value
        if (s.code.length != 6 || s.challengeId.isBlank()) return
        if (runCatching { Instant.parse(s.expiresAt).toEpochMilli() <= System.currentTimeMillis() }.getOrDefault(true)) { change { it.copy(error = "El código venció. Solicitá uno nuevo.") }; return }
        work {
            val reply = app.api.request("/auth/code/verify", "POST", JSONObject().put("challengeId", s.challengeId).put("code", s.code), authenticated = false)
            require(reply.getString("role") == s.role.wire) { "El acceso no corresponde a este espacio." }
            app.settings.write("accountSession", reply.getString("sessionToken"))
            change { it.copy(page = if (reply.optBoolean("onboardingRequired", true)) EntryPage.PROFILE else EntryPage.HOME, code = "", challengeId = "") }
            if (!reply.optBoolean("onboardingRequired", true)) loadAccount()
        }
    }
    fun nextQuestion() { val s = state.value; if (s.answers[questionsFor(s.role)[s.question].id] == null) return; if (s.question == questionsFor(s.role).lastIndex) move(EntryPage.REVIEW) else change { it.copy(question = it.question + 1) } }
    fun editQuestion(index: Int) { change { it.copy(question = index) }; move(EntryPage.QUESTIONS) }
    fun back() {
        val s = state.value
        if (s.page == EntryPage.QUESTIONS && s.question > 0) { change { it.copy(question = it.question - 1) }; return }
        move(when (s.page) {
            EntryPage.FIND_PERSON, EntryPage.JOIN_FAMILY -> EntryPage.FAMILY_CHOICE
            EntryPage.PROFILE -> if (s.role == AccountRole.FAMILY) if (s.familyMode == "join") EntryPage.JOIN_FAMILY else EntryPage.FIND_PERSON else EntryPage.WELCOME
            EntryPage.CODE -> if (s.login) if (s.role == AccountRole.FAMILY && s.familyMode == "join") EntryPage.JOIN_FAMILY else EntryPage.LOGIN else EntryPage.PROFILE
            EntryPage.PERMISSIONS -> EntryPage.PROFILE
            EntryPage.QUESTIONS -> EntryPage.PERMISSIONS
            EntryPage.REVIEW -> EntryPage.QUESTIONS
            else -> EntryPage.WELCOME
        })
    }
    fun finish() {
        val s = state.value
        if (!s.accepted || questionsFor(s.role).any { s.answers[it.id] == null }) return
        work {
            val profile = JSONObject(s.fields.filterKeys { it !in listOf("adminEmail", "targetIdentifier", "photo") })
            val reply = app.api.request("/accounts/onboarding", "POST", JSONObject().put("role", s.role.wire).put("profile", profile)
                .put("preferences", JSONObject(s.preferences)).put("answers", JSONObject(s.answers)).put("familyMode", s.familyMode)
                .put("adminEmail", s.field("adminEmail")).put("targetIdentifier", s.field("targetIdentifier")), installationToken = app.settings.read("accountSession"))
            applyAccount(reply)
            app.settings.write("profile", JSONObject(s.fields).toString())
            app.settings.write("onboardingComplete", s.role.wire)
            app.settings.territoryId = s.field("territoryId").ifBlank { "AR-C" }
            app.settings.deviceName = s.field("name")
            change { it.copy(page = EntryPage.HOME, message = "Tu configuración quedó guardada.", dashboard = reply) }
        }
    }
    private fun applyAccount(reply: JSONObject) {
        reply.optString("deviceToken").takeIf { it.isNotBlank() }?.let { app.settings.installation(reply.getString("deviceId"), it) }
        reply.optString("caregiverToken").takeIf { it.isNotBlank() }?.let { app.settings.write("caregiverToken", it) }
        reply.optString("institutionToken").takeIf { it.isNotBlank() }?.let { app.settings.write("institutionToken", it) }
        change { it.copy(invitationCode = reply.optString("invitationCode")) }
    }
    private suspend fun loadAccount() { val reply = app.api.request("/accounts/me", installationToken = app.settings.read("accountSession")); applyAccount(reply); val p = reply.optJSONObject("profile"); if (p != null) change { it.copy(fields = it.fields + p.keys().asSequence().associateWith { key -> p.optString(key) }) } }
    fun guest() = work { app.api.register(); change { it.copy(role = AccountRole.USER, page = EntryPage.HOME, message = "Asistente conectado a este dispositivo. Podés crear una cuenta después.") } }
    fun dashboard() = work {
        if (app.settings.read("accountSession").isNotBlank()) loadAccount()
        val s = state.value
        val token = app.settings.read(if (s.role == AccountRole.FAMILY) "caregiverToken" else "institutionToken")
        val path = if (s.role == AccountRole.FAMILY) "/family/summary" else "/institution/analytics?territoryId=${s.field("territoryId").ifBlank { "AR-C" }}&days=30"
        val data = app.api.request(path, installationToken = token)
        change { it.copy(dashboard = data) }
        if (s.role == AccountRole.INSTITUTION) {
            val context = app.api.request("/openarg/territory?territoryId=${s.field("territoryId").ifBlank { "AR-C" }}", installationToken = token)
            change { it.copy(context = context) }
        }
    }
    fun invitation() = work { val reply = app.api.request("/family/invitations", "POST", JSONObject(), installationToken = app.settings.read("caregiverToken")); change { it.copy(invitationCode = reply.getString("invitationCode"), message = "Invitación válida por 15 minutos. La persona acepta los permisos desde su dispositivo.") } }
    fun resolveAlert(id: String) = work { app.api.request("/family/alerts/$id/resolve", "POST", JSONObject(), installationToken = app.settings.read("caregiverToken")); val data = app.api.request("/family/summary", installationToken = app.settings.read("caregiverToken")); change { it.copy(dashboard = data) } }
    fun approveMember(id: String) = work { app.api.request("/accounts/family/requests/$id/approve", "POST", JSONObject(), installationToken = app.settings.read("accountSession")); val data = app.api.request("/family/summary", installationToken = app.settings.read("caregiverToken")); change { it.copy(dashboard = data, message = "Incorporación aprobada. Los permisos de las personas acompañadas se conservan.") } }
    fun connect(url: String) = work { app.settings.baseUrl = url; app.api.request("/health", authenticated = false); change { it.copy(message = "Conexión verificada con el servidor.") } }
    fun logout() = work {
        val token = app.settings.read("accountSession")
        if (token.isNotBlank()) app.api.request("/accounts/session", "DELETE", installationToken = token)
        listOf("accountSession", "caregiverToken", "institutionToken", "onboardingComplete", "installation").forEach { app.settings.write(it, "") }
        mutable.value = EntryState(); saved.remove<String>("entryDraft")
    }
}
