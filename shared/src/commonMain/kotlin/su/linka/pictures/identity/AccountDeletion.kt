package su.linka.pictures.identity

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Persist the status capability before deletion; it remains usable after account/session erasure. */
internal fun IdentitySession.requestAccountDeletion() {
    if (view.value.busy) return
    telemetry.stop()
    scope.launch {
        val token = onlineToken()
        lock.withLock {
            if (!usable || !registered || token == null || saved.accountAccess != token) {
                publish(problem = true)
                return@withLock
            }
            val previous = saved.deletion
            if (previous != null && previous.status != "not_found") return@withLock
            try {
                val pending = previous?.copy(status = "unconfirmed")
                    ?: PendingDeletion(device.uuid(), encode64(device.randomBytes(32)),
                        accountKey = jsonObject(requireNotNull(saved.registrationResponse)).text("account_key"))
                require(pending.accountKey == jsonObject(requireNotNull(saved.registrationResponse)).text("account_key"))
                invalidateRegistrationRequests()
                val denial = if (saved.commands.lastOrNull()?.preference == "denied") saved.commands
                    else saved.commands + ConsentCommand(device.uuid(), "denied", timestamp(device.nowMillis()))
                require(denial.size <= 32)
                persist(saved.copy(deletion = pending, pending = null, verifyResponse = null,
                    consentDesired = "denied", commands = denial, batches = emptyList(), metricsAccess = null, metricsExpires = null))
                publish(busy = true)
                val body = buildJsonObject {
                    put("request_id", pending.requestId)
                    put("status_secret", pending.statusSecret)
                }.toString()
                val response = call("POST", "/v1/public/account-deletions", token, body)
                require(response.status in setOf(200, 202))
                acceptAccountDeletion(response.body)
            } catch (cancelled: CancellationException) {
                publish(problem = true)
                throw cancelled
            } catch (_: Exception) { publish(problem = true) }
        }
    }
}

internal fun IdentitySession.checkAccountDeletion() {
    if (view.value.busy) return
    scope.launch { lock.withLock {
        val pending = saved.deletion ?: return@withLock
        if (!usable || pending.status in setOf("completed", "rejected", "cancelled")) return@withLock
        publish(busy = true)
        try {
            val body = buildJsonObject {
                put("request_id", pending.requestId)
                put("product_id", IDENTITY_PRODUCT)
            }.toString()
            val response = call("POST", "/v1/public/account-deletions/status", pending.statusSecret, body)
            if (response.status == 404 && pending.status in setOf("unconfirmed", "not_found")) {
                persist(saved.copy(deletion = pending.copy(status = "not_found")))
                publish(problem = true)
            } else {
                require(response.status == 200)
                acceptAccountDeletion(response.body)
            }
        } catch (cancelled: CancellationException) {
            publish(problem = true)
            throw cancelled
        } catch (_: Exception) { publish(problem = true) }
    } }
}

internal fun IdentitySession.dismissAccountDeletion() {
    scope.launch { lock.withLock {
        if (saved.deletion?.status !in setOf("completed", "rejected", "cancelled")) return@withLock
        try {
            persist(saved.copy(deletion = null))
            publish()
            if (registered) telemetry.synchronize()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { publish(problem = true) }
    } }
}

private suspend fun IdentitySession.acceptAccountDeletion(raw: String) {
    val pending = requireNotNull(saved.deletion)
    val body = jsonObject(raw)
    require(body.number("contract_version") == 1L && body.text("product") == IDENTITY_PRODUCT &&
        body.text("request_id") == pending.requestId)
    val status = body.text("status")
    require(status in setOf("requested", "processing", "completed", "rejected", "cancelled"))
    require(pending.status != "processing" || status != "requested")
    val requestedAt = body.text("requested_at").also(::timeMillis)
    require(pending.requestedAt == null || pending.requestedAt == requestedAt)
    val completedAt = body["completed_at"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content?.also(::timeMillis)
    require(status != "completed" || completedAt != null)
    val next = pending.copy(status = status, requestedAt = requestedAt, completedAt = completedAt)
    persist(if (status == "completed") IdentityState(deletion = next) else saved.copy(deletion = next))
    if (status == "completed") registered = false
    publish()
}
