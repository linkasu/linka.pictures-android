@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package su.linka.pictures.identity

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import su.linka.pictures.application.communication.CommunicationEvent
import su.linka.pictures.application.ports.AudioPhase
import su.linka.pictures.domain.InteractionMode
import su.linka.pictures.domain.InputStyle

/** Bounded, device-only outbox. No free-form product content can reach this API. */
internal class ProductTelemetry(private val identity: IdentitySession) {
    private val device get() = identity.device
    private val failures = CoroutineExceptionHandler { _, _ -> stop(); identity.publish(problem = true) }
    private var worker: Job? = null
    private var sender: Job? = null
    private var activeEpoch: ULong = 0u
    private var stopped = true
    private var intentGeneration = 0L
    private var sessionId: String? = null
    private var sessionEnded = true
    private var pendingEnqueues = 0
    private val foregroundWindows = mutableSetOf<String>()
    private val seen = mutableSetOf<String>()

    internal fun stop() {
        stopped = true
        activeEpoch = 0u
        sender?.cancel()
    }

    internal fun setConsent(allowed: Boolean) {
        intentGeneration++
        stop()
        val (recordedAt, requestId) = try { timestamp(device.nowMillis()) to device.uuid() }
            catch (_: Exception) { identity.publish(problem = true); return }
        identity.scope.launch(failures) {
            identity.lock.withLock {
                if (!identity.usable || !identity.registered) return@withLock
                try {
                    val state = identity.saved
                    val command = ConsentCommand(requestId, if (allowed) "allowed" else "denied", recordedAt)
                    if (state.commands.lastOrNull()?.preference == command.preference) return@withLock
                    // Reserve the final slot for withdrawal; never drop a queued denial to make room for enable.
                    require(state.commands.size < if (allowed) 31 else 32)
                    identity.persist(state.copy(commands = state.commands + command, consentDesired = command.preference, consentConflict = false,
                        batches = emptyList(), metricsAccess = null, metricsExpires = null))
                    identity.publish()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { identity.publish(problem = true); return@withLock }
            }
            synchronize()
        }
    }

    internal fun synchronize() {
        if (worker?.isActive == true) return
        worker = identity.scope.launch(failures) {
            repeat(4) { attempt ->
                try {
                    val more = identity.lock.withLock {
                        if (!identity.usable || !identity.registered) return@withLock false
                        if (identity.saved.consentConflict) return@withLock false
                        val generation = intentGeneration
                        var command = identity.saved.commands.firstOrNull()
                        if (command != null) {
                            if (command.body == null) {
                                val body = buildJsonObject {
                                    put("request_id", command!!.requestId)
                                    put("expected_version", JsonPrimitive(identity.saved.preferenceVersion))
                                    put("preference", command!!.preference)
                                    put("policy_version", INSTALLATION_POLICY); put("recorded_at", command!!.recordedAt)
                                }.toString()
                                command = command.copy(body = body)
                                identity.persist(identity.saved.copy(commands = listOf(command) + identity.saved.commands.drop(1)))
                            }
                            val response = identity.call("PUT", "/v1/public/installations/telemetry-preference", identity.saved.installationRefresh, command.body)
                            if (response.status == 409) {
                                // A newer server intent is never overwritten with a fabricated timestamp/CAS.
                                readPreference()
                                identity.persist(identity.saved.copy(commands = emptyList(), batches = emptyList(), consentConflict = true))
                                stop()
                                identity.publish(problem = true)
                                return@withLock false
                            }
                            require(response.status == 200)
                            acceptPreference(jsonObject(response.body))
                            require(identity.saved.preference == command.preference)
                            identity.persist(identity.saved.copy(commands = identity.saved.commands.drop(1)))
                        } else readPreference()
                        identity.publish()
                        if (identity.saved.commands.isNotEmpty()) return@withLock true
                        if (generation != intentGeneration) return@withLock true
                        if (identity.saved.consentDesired == "allowed" && identity.saved.preference == "allowed" && identity.saved.deliveryCompleted && identity.saved.consentEpoch > 0u &&
                            !identity.saved.metricsAccess.isNullOrBlank() && identity.saved.metricsExpires?.let { timeMillis(it) > device.nowMillis() + 30000 } == true) {
                            activeEpoch = identity.saved.consentEpoch
                            stopped = false
                            if (sessionId != null) productOnce("app_open", "app:${sessionId}")
                            send()
                            false
                        } else {
                            stop()
                            identity.saved.consentDesired == "allowed" && identity.saved.preference == "allowed" && !identity.saved.deliveryCompleted
                        }
                    }
                    if (!more) return@launch
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { identity.publish(problem = true) }
                if (attempt < 3) delay(1000L shl attempt)
            }
        }
    }

    private suspend fun readPreference() {
        val response = identity.call("GET", "/v1/public/installations/telemetry-preference", identity.saved.installationRefresh)
        require(response.status == 200)
        acceptPreference(jsonObject(response.body))
    }

    private suspend fun acceptPreference(body: JsonObject) {
        require(body.text("product") == IDENTITY_PRODUCT && body.text("installation_key") == identity.saved.installation)
        val preference = body.text("preference").also { require(it in setOf("unknown", "allowed", "denied")) }
        val version = body.getValue("version").jsonPrimitive.let { require(!it.isString); it.content.toULong() }
        val epoch = body.getValue("consent_epoch").jsonPrimitive.let { require(!it.isString); it.content.toULong() }
        require(version >= identity.saved.preferenceVersion)
        val delivery = body.text("delivery_state").also { require(it in setOf("pending", "completed")) }
        require(body.text("policy_version").isNotBlank())
        timeMillis(body.text("recorded_at"))
        val token = body["metrics_token"]?.takeUnless { it == JsonNull }?.jsonObject
        token?.let { require(it.text("token_type") == "Bearer"); timeMillis(it.text("expires_at")) }
        if (preference != "allowed" || epoch != identity.saved.consentEpoch || token == null) stop()
        identity.persist(identity.saved.copy(preference = preference, preferenceVersion = version, consentEpoch = epoch,
            deliveryCompleted = delivery == "completed", metricsAccess = token?.text("access_token"), metricsExpires = token?.text("expires_at"),
            batches = if (preference == "allowed") identity.saved.batches.filter { it.epoch == epoch } else emptyList()))
    }

    /** Root lifecycle, not a composition instance; overlapping windows share the foreground session. */
    internal fun foreground(window: String, visible: Boolean, configurationChange: Boolean = false) {
        if (visible) {
            if (foregroundWindows.isEmpty() && (sessionId == null || sessionEnded)) {
                sessionId = try { device.uuid() } catch (_: Exception) { return }
                seen.clear()
            }
            sessionEnded = false
            foregroundWindows += window
            productOnce("app_open", "app:${sessionId}")
            synchronize()
        } else {
            foregroundWindows -= window
            // Retain the last ID for real terminal callbacks arriving after background interruption.
            if (foregroundWindows.isEmpty() && !configurationChange) sessionEnded = true
        }
    }

    internal fun productOnce(kind: String, operation: String) {
        if (!canCollect() || operation in seen) return
        if (seen.size >= 1024) return
        seen += operation
        product(kind)
    }

    internal fun product(kind: String) {
        require(kind in setOf("app_open", "set_list_open", "set_open", "open_settings", "editor_open", "card_select", "set_save"))
        enqueue("product", kind, emptyMap())
    }

    internal fun communication(event: CommunicationEvent) {
        when (event) {
            CommunicationEvent.BoardOpened -> product("set_open")
            CommunicationEvent.CardSelected -> product("card_select")
            is CommunicationEvent.UtteranceFinished -> utterance(when (event.phase) {
                AudioPhase.COMPLETED -> "completed"
                AudioPhase.FAILED -> "failed"
                else -> "cancelled"
            }, when {
                event.mode == InteractionMode.DIRECT -> "direct"
                event.style == InputStyle.LITERAL -> "without-space"
                else -> "standard"
            })
        }
    }

    internal fun utterance(result: String, mode: String) {
        require(result in setOf("completed", "failed", "cancelled") && mode in setOf("standard", "direct", "without-space"))
        enqueue("outcome", "utterance_completed", mapOf("result" to result, "mode" to mode))
    }

    internal fun setSaved(created: Boolean, count: Int, completed: Boolean) {
        if (count <= 0) {
            if (completed) product("set_save")
            return
        }
        enqueue("outcome", "set_saved", mapOf("result" to if (completed) "completed" else "failed",
            "source" to if (created) "created" else "edited", "count_bucket" to when {
                count == 1 -> "one"; count <= 5 -> "two_to_five"; count <= 20 -> "six_to_twenty"; else -> "more_than_twenty"
            }))
    }

    internal fun transfer(import: Boolean, completed: Boolean) = enqueue("outcome", "transfer_completed",
        mapOf("result" to if (completed) "completed" else "failed", "source" to if (import) "import" else "export"))

    private fun canCollect() = !stopped && activeEpoch > 0u && identity.usable && identity.registered && sessionId != null &&
        !identity.saved.metricsAccess.isNullOrBlank() && identity.saved.metricsExpires?.let { timeMillis(it) > device.nowMillis() } == true

    private fun enqueue(stream: String, kind: String, fields: Map<String, String>) {
        if (!canCollect() || identity.saved.batches.size >= 16 || pendingEnqueues >= 16) return
        val epoch = activeEpoch
        val session = sessionId ?: return
        val (now, recordId, batchId) = try { Triple(timestamp(device.nowMillis()), device.uuid(), device.uuid()) }
            catch (_: Exception) { return }
        pendingEnqueues++
        identity.scope.launch(failures) {
            identity.lock.withLock {
                if (!canCollect() || activeEpoch != epoch || identity.saved.batches.size >= 16) return@withLock
                val app = buildJsonObject {
                    put("version", device.appVersion); put("build", device.appBuild); put("platform", device.platform); put("os_version", device.osVersion)
                    put("locale", device.locale.takeIf { it in setOf("ru", "ru-RU", "en", "en-US") } ?: "other")
                }
                val record = buildJsonObject {
                    put("record_id", recordId); put("occurred_at", now); put("kind", kind); put("app_session_id", session); put("app", app)
                    fields.forEach { (key, value) -> put(key, value) }
                }
                val body = buildJsonObject {
                    put("schema_version", 2); put("batch_id", batchId); put("stream", stream); put("sent_at", now)
                    put("scope", buildJsonObject { put("product", IDENTITY_PRODUCT); put("subject_key", identity.saved.installation); put("consent_epoch", JsonPrimitive(epoch)) })
                    put("records", JsonArray(listOf(record)))
                }.toString()
                try { identity.persist(identity.saved.copy(batches = identity.saved.batches + FrozenBatch(batchId, epoch, body)), gateOnFailure = false) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { return@withLock }
                send()
            }
        }.invokeOnCompletion { pendingEnqueues-- }
    }

    private fun send() {
        if (sender?.isActive == true || !canCollect()) return
        sender = identity.scope.launch(failures) {
            repeat(16) {
                identity.lock.withLock {
                    if (!canCollect()) return@launch
                    val batch = identity.saved.batches.firstOrNull() ?: return@launch
                    if (batch.epoch != activeEpoch || batch.attempts >= 4 ||
                        timeMillis(jsonObject(batch.body).text("sent_at")) < device.nowMillis() - 6 * 86400000L) {
                        identity.persist(identity.saved.copy(batches = identity.saved.batches.drop(1)), gateOnFailure = false)
                        return@withLock
                    }
                    if (batch.nextRetryAt > device.nowMillis()) return@launch
                    try {
                        if (identity.saved.metricsExpires?.let { timeMillis(it) > device.nowMillis() + 30000 } != true) {
                            val tokenResponse = identity.call("POST", "/v1/public/installations/token", identity.saved.installationRefresh, "{}")
                            if (tokenResponse.status == 429) {
                                identity.persist(identity.saved.copy(batches = listOf(batch.copy(nextRetryAt = retryDeadline(device.nowMillis(), tokenResponse.retryAfterSeconds))) + identity.saved.batches.drop(1)), gateOnFailure = false)
                                return@launch
                            }
                            require(tokenResponse.status == 200)
                            val body = jsonObject(tokenResponse.body)
                            require(body.text("product") == IDENTITY_PRODUCT && body.text("installation_key") == identity.saved.installation &&
                                body.getValue("consent_epoch").jsonPrimitive.let { !it.isString && it.content.toULong() == activeEpoch })
                            val token = body.getValue("metrics_token").jsonObject
                            require(token.text("token_type") == "Bearer")
                            timeMillis(token.text("expires_at"))
                            identity.persist(identity.saved.copy(metricsAccess = token.text("access_token"), metricsExpires = token.text("expires_at")), gateOnFailure = false)
                        }
                        if (!canCollect() || batch.epoch != activeEpoch) return@launch
                        identity.persist(identity.saved.copy(batches = listOf(batch.copy(attempts = batch.attempts + 1)) + identity.saved.batches.drop(1)), gateOnFailure = false)
                        val response = device.request("POST", "https://metrics.nkolinka.ru/v2/batches", identity.saved.metricsAccess, batch.body,
                            idempotencyKey = batch.id)
                        if (response.status == 202 || response.status in 400..499 && response.status !in setOf(401, 429)) {
                            identity.persist(identity.saved.copy(batches = identity.saved.batches.drop(1)), gateOnFailure = false)
                        } else {
                            if (response.status == 401) identity.persist(identity.saved.copy(metricsAccess = null, metricsExpires = null), gateOnFailure = false)
                            identity.persist(identity.saved.copy(batches = listOf(batch.copy(
                                attempts = if (response.status == 429) batch.attempts else batch.attempts + 1,
                                nextRetryAt = retryDeadline(device.nowMillis(), response.retryAfterSeconds))) + identity.saved.batches.drop(1)), gateOnFailure = false)
                            return@launch
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        if (identity.usable) identity.persist(identity.saved.copy(batches = listOf(batch.copy(attempts = batch.attempts + 1,
                            nextRetryAt = retryDeadline(device.nowMillis(), null))) + identity.saved.batches.drop(1)), gateOnFailure = false)
                        return@launch
                    }
                }
            }
        }
    }
}
