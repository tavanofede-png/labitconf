package org.nieto.assistant.entry

import android.app.Application
import android.util.Patterns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import org.nieto.assistant.data.ApiFailure
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
    val profileStep: Int = 0, val usernameEdited: Boolean = false, val usernameStatus: String = "idle",
    val usernameChecked: String = "", val emailVerified: Boolean = false,
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
    private var usernameJob: Job? = null
    private var answerJob: Job? = null
    private var usernameRevision = 0
    private fun restore(): EntryState = runCatching {
        val data = saved.get<String>("entryDraft")?.let(::JSONObject) ?: return EntryState()
        val fields = data.optJSONObject("fields") ?: JSONObject()
        val answers = data.optJSONObject("answers") ?: JSONObject()
        val prefs = data.optJSONObject("preferences") ?: JSONObject()
        EntryState(page = EntryPage.valueOf(data.optString("page", "WELCOME")), role = AccountRole.valueOf(data.optString("role", "USER")),
            fields = fields.keys().asSequence().associateWith { fields.getString(it) }, answers = answers.keys().asSequence().associateWith { answers.getInt(it) }.filterValues { it in 0..3 },
            preferences = prefs.keys().asSequence().associateWith { prefs.getBoolean(it) }, familyMode = data.optString("familyMode", "create"), question = data.optInt("question").coerceIn(0, questionsFor(AccountRole.valueOf(data.optString("role", "USER"))).lastIndex),
            profileStep = data.optInt("profileStep"), usernameEdited = data.optBoolean("usernameEdited"), emailVerified = app.settings.read("accountSession").isNotBlank(),
            login = data.optBoolean("login"), firstTime = data.optBoolean("firstTime", true))
    }.getOrDefault(EntryState())
    private fun change(block: (EntryState) -> EntryState) {
        mutable.value = block(mutable.value)
        val s = state.value
        // Codes, challenge identifiers, tokens and downloaded private data never enter saved-state bundles.
        val safePage = if (s.page == EntryPage.CODE) if (s.login) EntryPage.LOGIN else EntryPage.PROFILE else s.page
        saved["entryDraft"] = JSONObject().put("page", safePage.name).put("role", s.role.name).put("fields", JSONObject(s.fields))
            .put("answers", JSONObject(s.answers)).put("preferences", JSONObject(s.preferences)).put("familyMode", s.familyMode)
            .put("profileStep", s.profileStep).put("usernameEdited", s.usernameEdited).put("question", s.question).put("login", s.login).put("firstTime", s.firstTime).toString()
    }
    fun move(page: EntryPage) { generation++; job?.cancel(); usernameJob?.cancel(); answerJob?.cancel(); change { it.copy(page = page, busy = false, error = null, message = null, accepted = false) } }
    fun start(role: AccountRole) { generation++; job?.cancel(); listOf("accountSession", "caregiverToken", "institutionToken", "installation").forEach { app.settings.write(it, "") }; mutable.value = EntryState(role = role); move(if (role == AccountRole.FAMILY) EntryPage.FAMILY_CHOICE else EntryPage.PROFILE) }
    fun field(key: String, value: String) = change { it.copy(fields = it.fields + (key to value.take(200)), error = null) }
    fun photo(uri: String) { field("photo", uri); if (state.value.page == EntryPage.PROFILE && currentProfileStep() == ProfileStep.PHOTO && uri.isNotBlank()) nextProfile() }
    fun answer(index: Int) {
        if (state.value.page != EntryPage.QUESTIONS || index !in 0..3) return
        answerJob?.cancel()
        val question = state.value.question
        change { it.copy(answers = it.answers + (questionsFor(it.role)[question].id to index), error = null) }
        val current = generation
        answerJob = viewModelScope.launch { delay(220); if (generation == current && state.value.question == question) nextQuestion() }
    }
    fun currentProfileStep(): ProfileStep { val s = state.value; val steps = profileStepsFor(s.role, s.emailVerified); return steps[s.profileStep.coerceIn(0, steps.lastIndex)] }
    fun profileField(key: String, value: String) {
        if (key == "username") {
            usernameRevision++; usernameJob?.cancel()
            change { it.copy(fields = it.fields + (key to value.trim().lowercase(java.util.Locale.ROOT).take(31)), usernameEdited = true, usernameStatus = "idle", usernameChecked = "", error = null) }
        } else {
            field(key, if (key == "name") value.take(100) else value)
            if (key == "name" && !state.value.usernameEdited) change { it.copy(fields = it.fields + ("username" to suggestedUsername(value)), usernameStatus = "idle", usernameChecked = "") }
        }
    }
    fun lookupUsername() {
        usernameJob?.cancel()
        val revision = ++usernameRevision
        val initial = state.value
        if (initial.page != EntryPage.PROFILE || currentProfileStep() != ProfileStep.USERNAME) return
        val suggest = !initial.usernameEdited
        if (!suggest && !validUsername(initial.field("username"))) { change { it.copy(usernameStatus = "invalid") }; return }
        change { it.copy(usernameStatus = "checking", usernameChecked = "") }
        val current = generation
        usernameJob = viewModelScope.launch {
            delay(350)
            try {
                val reply = app.api.request(if (suggest) "/accounts/username/suggest" else "/accounts/username/check", "POST",
                    JSONObject().put(if (suggest) "name" else "username", initial.field(if (suggest) "name" else "username")),
                    installationToken = app.settings.read("accountSession"), authenticated = app.settings.read("accountSession").isNotBlank())
                if (revision == usernameRevision && current == generation) change { it.copy(fields = if (suggest) it.fields + ("username" to reply.getString("username")) else it.fields,
                    usernameStatus = if (reply.getBoolean("available")) "available" else "taken", usernameChecked = reply.getString("username")) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (revision == usernameRevision && current == generation) change { it.copy(usernameStatus = "error", error = "No pude comprobar el usuario. Revisá la conexión y volvé a intentar.") } }
        }
    }
    fun editProfile() { change { it.copy(profileStep = 0) }; move(EntryPage.PROFILE) }
    fun nextProfile() {
        val s = state.value
        val step = currentProfileStep()
        val error = when (step) {
            ProfileStep.NAME -> if (s.field("name").trim().isEmpty()) "Decinos cómo te llamás." else null
            ProfileStep.USERNAME -> if (!validUsername(s.field("username"))) "Usá entre 3 y 30 letras, números, puntos o guiones bajos." else if (s.usernameStatus != "available" || s.usernameChecked != s.field("username")) "Primero necesitamos comprobar que el usuario esté disponible." else null
            ProfileStep.PHONE -> if (!validPhone(s.field("phone"))) "Ingresá un teléfono válido, con código de área. Es obligatorio." else null
            ProfileStep.EMAIL -> if (!Patterns.EMAIL_ADDRESS.matcher(s.field("email").trim()).matches()) "Ingresá un correo válido." else null
            ProfileStep.ORGANIZATION -> if (s.field("organization").isBlank()) "Ingresá el nombre de tu institución." else null
            else -> null
        }
        if (error != null) { change { it.copy(error = error) }; return }
        val steps = profileStepsFor(s.role, s.emailVerified)
        if (s.profileStep >= steps.lastIndex) submitProfile()
        else change { it.copy(profileStep = it.profileStep + 1, error = null, message = null) }
    }
    fun preference(key: String, enabled: Boolean) = change { it.copy(preferences = it.preferences + (key to enabled)) }
    fun accept(value: Boolean) = change { it.copy(accepted = value) }
    fun firstTime(value: Boolean) = change { it.copy(firstTime = value) }
    fun loginRole(role: AccountRole) = change { it.copy(role = role, challengeId = "", code = "") }
    fun code(value: String) {
        val s = state.value
        if (s.busy || s.page != EntryPage.CODE) return
        val digits = value.filter { it in '0'..'9' }.take(6)
        if (digits == s.code) return
        change { it.copy(code = digits, error = null) }
        if (digits.length == 6) verify()
    }
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
            catch (e: Exception) { if (generation == current) change {
                if (e is ApiFailure && e.code == "username_unavailable") it.copy(page = EntryPage.PROFILE, profileStep = profileStepsFor(it.role, it.emailVerified).indexOf(ProfileStep.USERNAME), usernameEdited = true, usernameStatus = "taken", error = e.message)
                else if (e is ApiFailure && e.code == "invalid_code" && it.page == EntryPage.CODE) it.copy(code = "", error = e.message)
                else it.copy(error = e.message ?: "No pude completar la acción.")
            } }
            finally { if (generation == current) change { it.copy(busy = false) } }
        }
    }
    fun submitProfile() {
        val s = state.value
        if (s.field("name").trim().isEmpty() || !Patterns.EMAIL_ADDRESS.matcher(s.field("email").trim()).matches() || (s.role == AccountRole.INSTITUTION && s.field("organization").isBlank())) {
            change { it.copy(error = "Completá tu nombre, un correo válido y los datos obligatorios.") }; return
        }
        val username = s.field("username")
        if (!validUsername(username)) { change { it.copy(error = "El usuario admite de 3 a 30 letras, números, puntos o guiones bajos.") }; return }
        if (!validPhone(s.field("phone"))) { change { it.copy(error = "Ingresá tu teléfono. Es obligatorio.") }; return }
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
        if (s.busy || s.page != EntryPage.CODE || s.code.length != 6 || s.challengeId.isBlank()) return
        if (runCatching { Instant.parse(s.expiresAt).toEpochMilli() <= System.currentTimeMillis() }.getOrDefault(true)) { change { it.copy(error = "El código venció. Solicitá uno nuevo.") }; return }
        work {
            val reply = app.api.request("/auth/code/verify", "POST", JSONObject().put("challengeId", s.challengeId).put("code", s.code), authenticated = false)
            require(reply.getString("role") == s.role.wire) { "El acceso no corresponde a este espacio." }
            app.settings.write("accountSession", reply.getString("sessionToken"))
            if (reply.getString("role") == "user") app.resetConversation()
            val hasProfile = s.field("name").isNotBlank() && validUsername(s.field("username")) && validPhone(s.field("phone")) && (s.role != AccountRole.INSTITUTION || s.field("organization").isNotBlank())
            if (!reply.optBoolean("onboardingRequired", true)) loadAccount()
            change { it.copy(page = if (reply.optBoolean("onboardingRequired", true)) if (hasProfile) EntryPage.PERMISSIONS else EntryPage.PROFILE else EntryPage.HOME, profileStep = 0, emailVerified = true, code = "", challengeId = "") }
        }
    }
    fun nextQuestion() { val s = state.value; if (s.answers[questionsFor(s.role)[s.question].id] == null) return; if (s.question == questionsFor(s.role).lastIndex) move(EntryPage.REVIEW) else change { it.copy(question = it.question + 1) } }
    fun editQuestion(index: Int) { change { it.copy(question = index) }; move(EntryPage.QUESTIONS) }
    fun back() {
        val s = state.value
        answerJob?.cancel(); usernameJob?.cancel()
        if (s.page == EntryPage.PROFILE && s.profileStep > 0) { change { it.copy(profileStep = it.profileStep - 1, error = null) }; return }
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
    private suspend fun loadAccount() {
        val reply = app.api.request("/accounts/me", installationToken = app.settings.read("accountSession"))
        applyAccount(reply)
        val profile = reply.optJSONObject("profile")
        if (profile != null) {
            val fields = profile.keys().asSequence().associateWith { key -> profile.optString(key) }
            change { it.copy(fields = it.fields + fields) }
            app.settings.write("profile", profile.toString())
            app.settings.deviceName = profile.optString("name")
            app.settings.territoryId = profile.optString("territoryId", "AR-C")
        }
        if (!reply.optBoolean("onboardingRequired", true)) app.settings.write("onboardingComplete", reply.getString("role"))
    }
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
