package su.linka.pictures.identity

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*

/** A platform root owns one instance; no credentials enter library storage or portable settings. */
class IdentitySession internal constructor(internal val device: IdentityDevice, internal val scope: CoroutineScope) {
    internal val lock = Mutex()
    internal var saved = IdentityState()
    private val mutableView = MutableStateFlow(IdentityView())
    internal val view = mutableView.asStateFlow()
    internal var registered = false
    internal var usable = false
    private var generation = 0L
    private var registrationRevision = 0L
    private val refreshLock = Mutex()
    internal val telemetry = ProductTelemetry(this)

    init { scope.launch { load() } }

    internal suspend fun persist(value: IdentityState, gateOnFailure: Boolean = true) {
        val bytes = identityJson.encodeToString(value).encodeToByteArray()
        require(bytes.size <= 64 * 1024)
        try { device.writeSecure(bytes) }
        catch (cancelled: CancellationException) { if (gateOnFailure) recovery() else telemetry.stop(); throw cancelled }
        catch (failure: Exception) { if (gateOnFailure) recovery() else { telemetry.stop(); publish(problem = true) }; throw failure }
        saved = value
    }

    internal fun publish(problem: Boolean = false, busy: Boolean = false) {
        if (!usable) return
        val pending = saved.pending
        mutableView.value = IdentityView(
            phase = if (registered) RegistrationPhase.REGISTERED else if (pending != null) RegistrationPhase.CHALLENGE else RegistrationPhase.REQUIRED,
            busy = busy, challengeStatus = pending?.status, expiresAt = pending?.expiresAt,
            resendAfter = pending?.resendAfter, retryAt = maxOf(pending?.retryAt ?: 0, if (pending == null) saved.nextBeginAt else 0), problem = problem,
            consent = saved.consentDesired,
            consentPending = saved.consentConflict || saved.commands.isNotEmpty() || saved.consentDesired == "allowed" && !saved.deliveryCompleted,
            consentConflict = saved.consentConflict, hasChallenge = pending != null, registrationRevision = registrationRevision,
            resendAt = maxOf(saved.nextBeginAt, pending?.retryAt ?: 0, pending?.resendAfter?.let(::timeMillis) ?: 0),
            hasReceivedProof = saved.verifyResponse != null)
    }

    private fun recovery() {
        usable = false
        registered = false
        telemetry.stop()
        mutableView.value = IdentityView(RegistrationPhase.RECOVERY, problem = true)
    }

    internal fun retryLoad() { scope.launch { load() } }

    private suspend fun load() = lock.withLock {
        try {
            val bytes = device.readSecure()
            require(bytes == null || bytes.size <= 64 * 1024)
            saved = bytes?.let { identityJson.decodeFromString<IdentityState>(it.decodeToString(throwOnInvalidSequence = true)) } ?: IdentityState()
            require(saved.version == 1)
            registered = false
            saved.registrationResponse?.let { response ->
                val account = jsonObject(response)
                validateReceipt(device, account.text("registration_receipt"), jsonObject(requireNotNull(saved.trustedKey)),
                    requireNotNull(saved.installation), account.text("account_key"), account.getValue("account_profile").jsonObject.text("holder_role"))
                registered = true
            }
            usable = true
            publish()
            if (saved.verifyResponse != null) {
                try { finishVerification() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { publish(problem = true) }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { recovery() }
        if (usable && registered) telemetry.synchronize()
    }

    internal suspend fun call(method: String, path: String, bearer: String? = null, body: String? = null): IdentityHttpResponse {
        val response = device.request(method, IDENTITY_ORIGIN + path, bearer, body)
        require(response.body.encodeToByteArray().size <= 32 * 1024)
        return response
    }

    private suspend fun installation() {
        if (saved.installationRefresh != null) return
        require(device.nowMillis() >= saved.nextBeginAt)
        if (saved.bootstrapBody == null) persist(saved.copy(bootstrapBody = buildJsonObject {
            put("request_id", device.uuid()); put("product_id", IDENTITY_PRODUCT); put("platform", device.platform)
            put("preference", "unknown"); put("policy_version", INSTALLATION_POLICY); put("recorded_at", timestamp(device.nowMillis()))
        }.toString()))
        val response = call("POST", "/v1/public/installations", body = saved.bootstrapBody)
        if (response.status == 429) persist(saved.copy(nextBeginAt = retryDeadline(device.nowMillis(), response.retryAfterSeconds)))
        require(response.status in 200..299)
        val body = jsonObject(response.body)
        require(body.text("product") == IDENTITY_PRODUCT && body.text("platform") == device.platform && body.text("preference") == "unknown")
        require(opaqueKey.matches(body.text("installation_key")) && body.text("refresh_token").isNotBlank())
        persist(saved.copy(installation = body.text("installation_key"), installationRefresh = body.text("refresh_token")))
    }

    /** Invalidates old callbacks before waiting for the transport/mutex. */
    internal fun changeEmail() {
        generation++
        scope.launch { lock.withLock {
            if (!usable) return@withLock
            try { persist(saved.copy(pending = null, verifyResponse = null)); publish() }
            catch (_: Exception) { if (usable) publish(problem = true) }
        } }
    }

    internal fun resend() = begin("", "self", resend = true)

    internal fun begin(email: String, role: String, poll: Boolean = false, resend: Boolean = false) {
        val currentGeneration = generation
        if (view.value.busy) return
        scope.launch { lock.withLock {
            if (!usable || view.value.busy || currentGeneration != generation) return@withLock
            publish(busy = true)
            try {
                installation()
                if (currentGeneration != generation) return@withLock
                if (!poll) {
                    require(device.nowMillis() >= saved.nextBeginAt)
                    val old = saved.pending
                    require(old == null || old.resendAfter?.let { timeMillis(it) <= device.nowMillis() } == true)
                    if (resend) require(old != null && old.status in setOf("queued", "pending", "verified", "delivery_failed", "expired", "locked", "cancelled") &&
                        device.nowMillis() >= old.retryAt && saved.verifyResponse == null)
                    val selectedEmail = if (resend) jsonObject(requireNotNull(old).body).text("email") else email.trim()
                    val selectedRole = if (resend) requireNotNull(old).role else role
                    require(selectedRole in setOf("self", "representative") && selectedEmail.length in 3..254 && '@' in selectedEmail && selectedEmail.none { it.isISOControl() })
                    // Only an admitted new proof invalidates the prior response generation.
                    generation++
                    val nonce = encode64(device.randomBytes(32)).also { require(decode64(it).size == 32) }
                    val body = buildJsonObject {
                        put("request_id", device.uuid()); put("product_id", IDENTITY_PRODUCT); put("email", selectedEmail)
                        put("purpose", "product_registration"); put("client_nonce", nonce); put("holder_role", selectedRole)
                    }.toString()
                    persist(saved.copy(pending = PendingChallenge(body, selectedRole, nonce), verifyResponse = null))
                }
                val requestGeneration = if (poll) currentGeneration else currentGeneration + 1
                if (requestGeneration != generation) return@withLock
                val pending = requireNotNull(saved.pending)
                require(device.nowMillis() >= pending.retryAt)
                val response = call("POST", "/v1/public/email/challenges", saved.installationRefresh, pending.body)
                if (requestGeneration != generation) return@withLock
                if (response.status == 429) {
                    val retryAt = retryDeadline(device.nowMillis(), response.retryAfterSeconds)
                    persist(saved.copy(nextBeginAt = maxOf(saved.nextBeginAt, retryAt), pending = pending.copy(retryAt = retryAt)))
                    publish(problem = true)
                } else {
                    require(response.status in 200..299)
                    val body = jsonObject(response.body)
                    val status = body.text("status")
                    require(body.number("contract_version") == 1L && status in setOf("queued", "pending", "verified", "delivery_failed", "expired", "locked", "cancelled"))
                    val id = body.text("challenge_id").also { require(uuidPattern.matches(it)) }
                    val expires = body.text("expires_at").also { timeMillis(it) }
                    val resend = body.text("resend_after").also { timeMillis(it) }
                    persist(saved.copy(nextBeginAt = maxOf(saved.nextBeginAt, timeMillis(resend)), pending = pending.copy(id = id, status = status, expiresAt = expires, resendAfter = resend)))
                    publish(problem = status == "verified")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { publish(problem = true) }
        } }
    }

    internal fun verify(code: String) {
        val currentGeneration = generation
        scope.launch { lock.withLock {
            if (!usable || currentGeneration != generation) return@withLock
            publish(busy = true)
            try {
                require(Regex("[0-9]{6}").matches(code))
                val pending = requireNotNull(saved.pending)
                require(pending.status == "pending" && device.nowMillis() >= pending.retryAt)
                val response = call("POST", "/v1/public/email/challenges/${requireNotNull(pending.id)}/verify", saved.installationRefresh,
                    buildJsonObject { put("product_id", IDENTITY_PRODUCT); put("code", code); put("client_nonce", pending.nonce) }.toString())
                if (currentGeneration != generation) return@withLock
                if (response.status == 429) {
                    val retryAt = retryDeadline(device.nowMillis(), response.retryAfterSeconds)
                    persist(saved.copy(nextBeginAt = maxOf(saved.nextBeginAt, retryAt), pending = pending.copy(retryAt = retryAt)))
                    publish(problem = true)
                    return@withLock
                }
                require(response.status == 200)
                // Preserve a consumed proof across a failed JWKS fetch; it is not yet local admission.
                persist(saved.copy(verifyResponse = response.body))
                if (currentGeneration == generation) finishVerification(currentGeneration)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { publish(problem = true) }
        } }
    }

    internal fun resumeVerification() { scope.launch { lock.withLock {
        if (!usable) return@withLock
        try { finishVerification() } catch (_: Exception) { publish(problem = true) }
    } } }

    private suspend fun finishVerification(expectedGeneration: Long = generation) {
        val raw = requireNotNull(saved.verifyResponse)
        val response = jsonObject(raw)
        validateAccountResponse(response, requireNotNull(saved.installation))
        val receipt = response.text("registration_receipt")
        val header = jsonObject(decode64(receipt.split('.').first()).decodeToString(throwOnInvalidSequence = true))
        val keysResponse = call("GET", "/.well-known/jwks.json")
        if (expectedGeneration != generation) return
        require(keysResponse.status == 200)
        val keys = jsonObject(keysResponse.body).getValue("keys").jsonArray
        val key = keys.map { it.jsonObject }.single { it.text("kid") == header.text("kid") }
        val role = requireNotNull(saved.pending).role
        val account = response.text("account_key")
        require(response.getValue("account_profile").jsonObject.text("holder_role") == role)
        validateReceipt(device, receipt, key, requireNotNull(saved.installation), account, role)
        val token = response.getValue("account_token").jsonObject
        require(token.text("token_type") == "Bearer")
        require(decode64(response.text("refresh_token")).size == 32)
        timeMillis(token.text("expires_at"))
        val previous = saved
        persist(saved.copy(registrationResponse = raw, trustedKey = key.toString(), verifyResponse = null, pending = null,
            accountRefresh = response.text("refresh_token"), accountAccess = token.text("access_token"), accountExpires = token.text("expires_at")))
        if (expectedGeneration != generation) {
            persist(previous.copy(pending = null, verifyResponse = null))
            publish()
            return
        }
        registered = true
        registrationRevision++
        publish()
        telemetry.synchronize()
    }

    /** Clear the rotating capability durably before transmission: an uncertain result must never replay it. */
    internal suspend fun onlineToken(): String? = refreshLock.withLock {
        lock.withLock {
            if (!usable || !registered) return@withLock null
            if (saved.accountExpires?.let { timeMillis(it) > device.nowMillis() + 30000 } == true) return@withLock saved.accountAccess
            val refresh = saved.accountRefresh ?: return@withLock null
            try {
                val evidence = JsonObject(jsonObject(requireNotNull(saved.registrationResponse)).filterKeys {
                    it !in setOf("refresh_token", "account_token", "refresh_expires_at")
                }).toString()
                persist(saved.copy(registrationResponse = evidence, accountRefresh = null, accountAccess = null, accountExpires = null))
                val result = call("POST", "/v1/public/account-sessions/refresh", refresh, "{}")
                require(result.status == 200)
                val response = jsonObject(result.body)
                validateAccountResponse(response, requireNotNull(saved.installation))
                val original = jsonObject(requireNotNull(saved.registrationResponse))
                require(response.number("contract_version") == 1L && response.text("product") == IDENTITY_PRODUCT &&
                    response.text("installation_key") == saved.installation && response.text("account_key") == original.text("account_key"))
                require(decode64(response.text("refresh_token")).size == 32)
                val token = response.getValue("account_token").jsonObject
                require(token.text("token_type") == "Bearer")
                timeMillis(token.text("expires_at"))
                persist(saved.copy(accountRefresh = response.text("refresh_token"), accountAccess = token.text("access_token"), accountExpires = token.text("expires_at")))
                saved.accountAccess
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }
    }
}
