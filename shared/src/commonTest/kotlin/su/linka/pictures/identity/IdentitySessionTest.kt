@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package su.linka.pictures.identity

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import kotlin.test.*

class IdentitySessionTest {
    private val installation = "a".repeat(64)
    private val account = "b".repeat(64)
    private val key get() = buildJsonObject {
        put("kid", "test"); put("kty", "OKP"); put("crv", "Ed25519"); put("x", encode64(ByteArray(32) { 1 }))
    }

    private class Device : IdentityDevice {
        override val platform = "android"
        override val appVersion = "1.4"
        override val appBuild = "5"
        override val osVersion = "35"
        override val locale = "private-locale-must-not-leak"
        var now = 1794096000000L
        var secure: ByteArray? = null
        var failWrite = false
        var beforeWrite: suspend (ByteArray) -> Unit = {}
        var sequence = 0
        var requests = mutableListOf<Triple<String, String?, String?>>()
        val metricsIdempotencyKeys = mutableListOf<String?>()
        var handler: suspend (String, String?, String?) -> IdentityHttpResponse = { _, _, _ -> throw IdentityDeviceFailure() }
        override fun nowMillis() = now
        override fun randomBytes(size: Int) = ByteArray(size) { (it + sequence++).toByte() }
        override fun verifyEd25519(publicKey: ByteArray, message: ByteArray, signature: ByteArray) = signature.all { it == 1.toByte() }
        override suspend fun readSecure() = secure
        override suspend fun writeSecure(value: ByteArray) { beforeWrite(value); if (failWrite) throw IdentityDeviceFailure(); secure = value.copyOf() }
        override suspend fun request(method: String, url: String, bearer: String?, body: String?, idempotencyKey: String?): IdentityHttpResponse {
            requests += Triple(url, bearer, body)
            if (url == "https://metrics.nkolinka.ru/v2/batches") metricsIdempotencyKeys += idempotencyKey
            return handler(url, bearer, body)
        }
    }

    private fun receipt(overrides: Map<String, JsonElement> = emptyMap(), header: JsonObject = buildJsonObject {
        put("alg", "EdDSA"); put("kid", "test"); put("typ", "JWT")
    }): String {
        val claims = buildJsonObject {
            put("contract_version", 1); put("iss", IDENTITY_ORIGIN); put("sub", account)
            put("aud", "linka-product-registration"); put("product", IDENTITY_PRODUCT); put("subject_type", "registration_receipt")
            put("installation_key", installation); put("holder_role", "self"); put("policy_version", REGISTRATION_POLICY)
            put("verified_at", 1); put("iat", 2); put("jti", "00000000-0000-4000-8000-000000000000")
        }.toMutableMap().apply { putAll(overrides) }
        return "${encode64(header.toString().encodeToByteArray())}.${encode64(JsonObject(claims).toString().encodeToByteArray())}.${encode64(ByteArray(64) { 1 })}"
    }

    private fun registration(receipt: String = receipt()) = buildJsonObject {
        put("contract_version", 1); put("product", IDENTITY_PRODUCT); put("account_key", account); put("installation_key", installation)
        put("account_profile", buildJsonObject {
            put("product", IDENTITY_PRODUCT); put("account_key", account); put("version", 1); put("holder_role", "self")
            put("country", ""); put("region", ""); put("updated_at", "2026-11-08T00:00:00Z")
        })
        put("account_token", buildJsonObject { put("access_token", "account-token"); put("token_type", "Bearer"); put("expires_at", "2020-01-01T00:00:00Z") })
        put("refresh_token", encode64(ByteArray(32) { 3 })); put("refresh_expires_at", "2028-01-01T00:00:00Z")
        put("registration_receipt", receipt); put("metrics_token", JsonNull)
    }.toString()

    private fun registeredState() = IdentityState(installation = installation, installationRefresh = "installation-refresh",
        registrationResponse = registration(), trustedKey = key.toString(), accountRefresh = encode64(ByteArray(32) { 3 }))

    @Test fun receiptRejectsWrongPurposeIssuerInstallationAndInjectedKeys() {
        val device = Device()
        validateReceipt(device, receipt(), key, installation, account, "self")
        listOf("iss" to "https://attacker.invalid", "aud" to "metrics", "product" to "other", "subject_type" to "account",
            "installation_key" to "c".repeat(64), "policy_version" to "old", "holder_role" to "representative").forEach { (name, value) ->
            assertFails { validateReceipt(device, receipt(mapOf(name to JsonPrimitive(value))), key, installation, account, "self") }
        }
        assertFails { validateReceipt(device, receipt(mapOf("exp" to JsonPrimitive(999999))), key, installation, account, "self") }
        assertFails { validateReceipt(device, receipt(header = buildJsonObject { put("alg", "none"); put("kid", "test") }), key, installation, account, "self") }
        assertFails { validateReceipt(device, receipt(header = buildJsonObject { put("alg", "EdDSA"); put("kid", "test"); put("jwk", key) }), key, installation, account, "self") }
        assertFails { validateReceipt(device, receipt().dropLast(1) + "A", key, installation, account, "self") }
    }

    @Test fun optionalRegistrationBootstrapIsDurableExactRetry() = runTest {
        val device = Device()
        val identity = IdentitySession(device, backgroundScope)
        runCurrent()
        assertEquals(RegistrationPhase.REQUIRED, identity.view.value.phase)
        device.handler = { _, _, body ->
            assertNotNull(device.secure)
            assertTrue(device.secure!!.decodeToString().contains("bootstrapBody"))
            assertEquals("unknown", jsonObject(requireNotNull(body)).text("preference"))
            throw IdentityDeviceFailure()
        }
        identity.begin("person@example.test", "self")
        runCurrent()
        val body = device.requests.single().third
        identity.begin("person@example.test", "self")
        runCurrent()
        assertEquals(body, device.requests.last().third)
        assertEquals(RegistrationPhase.REQUIRED, identity.view.value.phase)
        assertFalse(device.secure!!.decodeToString().contains("person@example.test"))
    }

    @Test fun guestForegroundDoesNotCreateInstallationOrCollectTelemetry() = runTest {
        val device = Device()
        val identity = IdentitySession(device, backgroundScope)
        runCurrent()
        identity.telemetry.foreground("guest", true)
        identity.telemetry.product("card_select")
        identity.telemetry.utterance("completed", "standard")
        runCurrent()
        assertEquals(RegistrationPhase.REQUIRED, identity.view.value.phase)
        assertTrue(device.requests.isEmpty())
        assertNull(device.secure)
        assertTrue(identity.saved.batches.isEmpty())
    }

    private fun onlineState(): IdentityState = registeredState().copy(accountAccess = "account-token",
        accountExpires = "2028-01-01T00:00:00Z")

    private fun deletionResponse(request: PendingDeletion, status: String, id: String = request.requestId) = buildJsonObject {
        put("contract_version", 1); put("product", IDENTITY_PRODUCT); put("request_id", id); put("status", status)
        put("requested_at", "2026-11-09T00:00:00Z")
        if (status == "completed") put("completed_at", "2026-11-09T00:01:00Z")
    }.toString()

    @Test fun uncertainDeletionPersistsCapabilityBeforeSendAndStatusSurvivesRestart() = runTest {
        val device = Device().apply { secure = identityJson.encodeToString(onlineState()).encodeToByteArray() }
        val identity = IdentitySession(device, backgroundScope)
        runCurrent()
        device.requests.clear()
        var captured: PendingDeletion? = null
        device.handler = { url, bearer, body ->
            if (!url.endsWith("/account-deletions")) throw IdentityDeviceFailure()
            assertEquals("account-token", bearer)
            val stored = identityJson.decodeFromString<IdentityState>(device.secure!!.decodeToString())
            captured = assertNotNull(stored.deletion)
            assertEquals(captured!!.requestId, jsonObject(body!!).text("request_id"))
            assertEquals(captured!!.statusSecret, jsonObject(body).text("status_secret"))
            assertEquals(32, decode64(captured!!.statusSecret).size)
            assertTrue(stored.batches.isEmpty())
            throw IdentityDeviceFailure()
        }
        identity.requestAccountDeletion(); runCurrent()
        assertEquals("unconfirmed", identity.view.value.deletionStatus)
        val pending = assertNotNull(captured)
        val count = device.requests.size
        val restarted = IdentitySession(device, backgroundScope)
        runCurrent()
        assertEquals(count, device.requests.size)
        restarted.begin("must-not-register@example.test", "self"); runCurrent()
        restarted.telemetry.foreground("deleting", true)
        restarted.telemetry.product("card_select"); runCurrent()
        assertEquals(count, device.requests.size)
        device.handler = { url, bearer, body ->
            assertTrue(url.endsWith("/account-deletions/status"))
            assertEquals(pending.statusSecret, bearer)
            assertEquals(pending.requestId, jsonObject(body!!).text("request_id"))
            IdentityHttpResponse(200, deletionResponse(pending, "completed"))
        }
        restarted.checkAccountDeletion(); runCurrent()
        assertEquals("completed", restarted.view.value.deletionStatus)
        assertEquals(RegistrationPhase.REQUIRED, restarted.view.value.phase)
        assertNull(restarted.saved.registrationResponse)
        assertNull(restarted.saved.accountRefresh)
        assertNull(restarted.saved.installationRefresh)
        assertTrue(restarted.saved.commands.isEmpty())
        assertTrue(restarted.saved.batches.isEmpty())
        restarted.dismissAccountDeletion(); runCurrent()
        assertEquals(IdentityState(), restarted.saved)
    }

    @Test fun acceptedDeletionIsNotCompletionAndUnknownStatusCannotBindAnotherRequest() = runTest {
        val device = Device().apply { secure = identityJson.encodeToString(onlineState()).encodeToByteArray() }
        val identity = IdentitySession(device, backgroundScope)
        runCurrent()
        device.handler = { url, _, _ ->
            if (!url.endsWith("/account-deletions")) throw IdentityDeviceFailure()
            IdentityHttpResponse(202, deletionResponse(identity.saved.deletion!!, "requested"))
        }
        identity.requestAccountDeletion(); runCurrent()
        assertEquals("requested", identity.view.value.deletionStatus)
        assertNotNull(identity.saved.registrationResponse)
        val pending = identity.saved.deletion!!
        val requests = device.requests.size
        identity.requestAccountDeletion(); runCurrent()
        assertEquals(requests, device.requests.size)
        device.handler = { _, _, _ -> IdentityHttpResponse(200,
            deletionResponse(pending, "completed", "00000000-0000-4000-8000-000000000001")) }
        identity.checkAccountDeletion(); runCurrent()
        assertEquals("requested", identity.view.value.deletionStatus)
        assertNotNull(identity.saved.registrationResponse)
        assertTrue(identity.view.value.problem)
    }

    @Test fun onlyExplicitRetryAfterNotFoundReusesTheOriginalDeletionCapability() = runTest {
        val pending = PendingDeletion("00000000-0000-4000-8000-000000000000", encode64(ByteArray(32) { 2 }), accountKey = account)
        val device = Device().apply { secure = identityJson.encodeToString(onlineState().copy(deletion = pending)).encodeToByteArray() }
        val identity = IdentitySession(device, backgroundScope)
        runCurrent()
        assertTrue(device.requests.isEmpty())
        device.handler = { _, _, _ -> IdentityHttpResponse(404, "{}") }
        identity.checkAccountDeletion(); runCurrent()
        assertEquals("not_found", identity.view.value.deletionStatus)
        device.handler = { _, bearer, body ->
            assertEquals("account-token", bearer)
            assertEquals(pending.requestId, jsonObject(body!!).text("request_id"))
            assertEquals(pending.statusSecret, jsonObject(body).text("status_secret"))
            IdentityHttpResponse(202, deletionResponse(pending, "requested"))
        }
        identity.requestAccountDeletion(); runCurrent()
        assertEquals("requested", identity.view.value.deletionStatus)
        assertEquals(pending.statusSecret, identity.saved.deletion!!.statusSecret)
    }

    @Test fun deletionProgressCannotRegressAndRejectedRequestDoesNotEraseAccount() = runTest {
        val pending = PendingDeletion("00000000-0000-4000-8000-000000000000", encode64(ByteArray(32) { 2 }),
            "processing", "2026-11-09T00:00:00Z")
        val device = Device().apply { secure = identityJson.encodeToString(onlineState().copy(deletion = pending)).encodeToByteArray() }
        val identity = IdentitySession(device, backgroundScope)
        runCurrent()
        device.handler = { _, _, _ -> IdentityHttpResponse(200, deletionResponse(pending, "requested")) }
        identity.checkAccountDeletion(); runCurrent()
        assertEquals("processing", identity.view.value.deletionStatus)
        device.handler = { _, _, _ -> IdentityHttpResponse(200, deletionResponse(pending, "rejected")) }
        identity.checkAccountDeletion(); runCurrent()
        assertEquals("rejected", identity.view.value.deletionStatus)
        assertNotNull(identity.saved.registrationResponse)
        identity.dismissAccountDeletion(); runCurrent()
        assertNull(identity.saved.deletion)
        assertEquals(RegistrationPhase.REGISTERED, identity.view.value.phase)
    }

    @Test fun deletionRetryReauthenticationCannotSwitchToAnotherAccount() = runTest {
        val pending = PendingChallenge("{}", "self", encode64(ByteArray(32)), "00000000-0000-4000-8000-000000000000", "pending")
        val deletion = PendingDeletion("00000000-0000-4000-8000-000000000001", encode64(ByteArray(32) { 2 }),
            status = "not_found", accountKey = "c".repeat(64))
        val device = Device().apply {
            secure = identityJson.encodeToString(IdentityState(installation = installation,
                installationRefresh = "installation-refresh", pending = pending, deletion = deletion)).encodeToByteArray()
            handler = { url, _, _ -> when {
                url.endsWith("/verify") -> IdentityHttpResponse(200, registration())
                url.endsWith("jwks.json") -> IdentityHttpResponse(200, buildJsonObject { put("keys", JsonArray(listOf(key))) }.toString())
                else -> throw IdentityDeviceFailure()
            } }
        }
        val identity = IdentitySession(device, backgroundScope)
        runCurrent()
        identity.verify("123456"); runCurrent()
        assertFalse(identity.registered)
        assertNull(identity.saved.registrationResponse)
        assertEquals(deletion, identity.saved.deletion)
        assertTrue(identity.view.value.problem)
        assertTrue(device.requests.none { it.first.endsWith("/account-deletions") })
    }

    @Test fun offlineReceiptSurvivesExpiredOnlineSessionAndUncertainRefreshIsNotReplayed() = runTest {
        val device = Device().apply { secure = identityJson.encodeToString(registeredState()).encodeToByteArray() }
        val identity = IdentitySession(device, backgroundScope)
        runCurrent()
        assertEquals(RegistrationPhase.REGISTERED, identity.view.value.phase)
        device.requests.clear()
        val first = async { identity.onlineToken() }
        val second = async { identity.onlineToken() }
        runCurrent()
        assertNull(first.await()); assertNull(second.await())
        assertEquals(1, device.requests.count { it.first.endsWith("/account-sessions/refresh") })
        assertEquals(RegistrationPhase.REGISTERED, identity.view.value.phase)
        assertNull(identity.saved.accountRefresh)
        val restarted = IdentitySession(device, backgroundScope)
        runCurrent()
        assertEquals(RegistrationPhase.REGISTERED, restarted.view.value.phase)
        assertNull(restarted.onlineToken())
        assertEquals(1, device.requests.count { it.first.endsWith("/account-sessions/refresh") })
    }

    @Test fun corruptSecureStateHasNoPlaintextFallbackOrDestructiveReset() = runTest {
        val bytes = "corrupt".encodeToByteArray()
        val device = Device().apply { secure = bytes }
        val identity = IdentitySession(device, backgroundScope)
        runCurrent()
        assertEquals(RegistrationPhase.RECOVERY, identity.view.value.phase)
        assertContentEquals(bytes, device.secure)
        assertTrue(device.requests.isEmpty())
    }

    @Test fun pendingProofPollUsesSameBodyAndQueuedDoesNotOpenGate() = runTest {
        val body = """{"request_id":"00000000-0000-4000-8000-000000000000","email":"private@example.test"}"""
        val device = Device().apply {
            secure = identityJson.encodeToString(IdentityState(installation = installation, installationRefresh = "installation-refresh",
                pending = PendingChallenge(body, "self", encode64(ByteArray(32))))).encodeToByteArray()
            handler = { _, bearer, request ->
                assertEquals("installation-refresh", bearer); assertEquals(body, request)
                IdentityHttpResponse(202, """{"contract_version":1,"challenge_id":"00000000-0000-4000-8000-000000000000","status":"queued","expires_at":"2026-11-10T00:00:00Z","resend_after":"2026-11-09T00:00:00Z"}""")
            }
        }
        val identity = IdentitySession(device, backgroundScope)
        runCurrent()
        identity.begin("", "self", poll = true); runCurrent()
        identity.begin("", "self", poll = true); runCurrent()
        assertEquals(RegistrationPhase.CHALLENGE, identity.view.value.phase)
        assertEquals("queued", identity.view.value.challengeStatus)
        assertEquals(2, device.requests.size)
    }

    @Test fun offlineConsentOffOnPreservesDenialAndOriginalTimestamp() = runTest {
        val device = Device().apply { secure = identityJson.encodeToString(registeredState()).encodeToByteArray() }
        val identity = IdentitySession(device, backgroundScope)
        runCurrent()
        identity.telemetry.setConsent(false)
        identity.telemetry.setConsent(true)
        runCurrent()
        advanceTimeBy(1000); runCurrent()
        assertEquals(listOf("denied", "allowed"), identity.saved.commands.map { it.preference })
        assertEquals(timestamp(device.now), identity.saved.commands.first().recordedAt)
        assertEquals("denied", jsonObject(identity.saved.commands.first().body!!).text("preference"))
        assertTrue(identity.saved.batches.isEmpty())
        val restarted = IdentitySession(device, backgroundScope)
        runCurrent()
        assertEquals(listOf("denied", "allowed"), restarted.saved.commands.map { it.preference })
    }

    private fun preference(epoch: Int, delivery: String, preference: String = "allowed", version: Int = 2) = buildJsonObject {
        put("installation_key", installation); put("product", IDENTITY_PRODUCT); put("preference", preference)
        put("policy_version", INSTALLATION_POLICY); put("recorded_at", "2026-11-08T00:00:00.000Z")
        put("version", version); put("consent_epoch", epoch); put("delivery_state", delivery)
        put("metrics_token", buildJsonObject { put("access_token", "installation-metrics-only"); put("token_type", "Bearer"); put("expires_at", "2028-01-01T00:00:00Z") })
    }.toString()

    @Test fun pendingDeliveryDoesNotCollectAndFrozenBatchRetriesExactBytes() = runTest {
        var delivery = "pending"
        val device = Device().apply {
            secure = identityJson.encodeToString(registeredState().copy(consentDesired = "allowed")).encodeToByteArray()
            handler = { url, bearer, _ ->
                if (url.endsWith("/telemetry-preference")) IdentityHttpResponse(200, preference(5, delivery))
                else {
                    assertEquals("https://metrics.nkolinka.ru/v2/batches", url)
                    assertEquals("installation-metrics-only", bearer)
                    IdentityHttpResponse(503, "{}")
                }
            }
        }
        val identity = IdentitySession(device, backgroundScope)
        runCurrent()
        identity.telemetry.foreground("window1", true)
        identity.telemetry.product("card_select")
        runCurrent()
        assertTrue(identity.saved.batches.isEmpty())
        delivery = "completed"
        advanceTimeBy(1000); runCurrent()
        val first = device.requests.first { it.first.endsWith("/v2/batches") }.third!!
        assertFalse(first.contains(device.locale))
        assertEquals("other", jsonObject(first).getValue("records").jsonArray.first().jsonObject.getValue("app").jsonObject.text("locale"))
        assertEquals("5", jsonObject(first).getValue("scope").jsonObject.getValue("consent_epoch").jsonPrimitive.content)
        identity.telemetry.product("card_select"); runCurrent()
        repeat(40) { identity.telemetry.product("card_select") }; runCurrent()
        assertEquals(16, identity.saved.batches.size)
        device.now += 60000
        advanceTimeBy(60000); runCurrent()
        identity.telemetry.synchronize(); runCurrent()
        assertEquals(first, device.requests.filter { it.first.endsWith("/v2/batches") }[1].third)
        val batchId = jsonObject(first).text("batch_id")
        assertEquals(listOf(batchId, batchId), device.metricsIdempotencyKeys.take(2))
        assertTrue(device.secure!!.size <= 64 * 1024)
        identity.telemetry.setConsent(false); runCurrent()
        assertTrue(identity.saved.batches.isEmpty())
        val sent = device.requests.count { it.first.endsWith("/v2/batches") }
        identity.telemetry.product("card_select"); runCurrent()
        assertEquals(sent, device.requests.count { it.first.endsWith("/v2/batches") })
    }

    @Test fun verifyResponseAndTrustedKeyAreSavedBeforeGateAndStorageFailureFailsClosed() = runTest {
        val pending = PendingChallenge("{}", "self", encode64(ByteArray(32)), "00000000-0000-4000-8000-000000000000", "pending")
        val device = Device().apply {
            secure = identityJson.encodeToString(IdentityState(installation = installation, installationRefresh = "installation-refresh", pending = pending)).encodeToByteArray()
            handler = { url, _, _ -> when {
                url.endsWith("/verify") -> IdentityHttpResponse(200, registration())
                url.endsWith("jwks.json") -> IdentityHttpResponse(200, buildJsonObject { put("keys", JsonArray(listOf(key))) }.toString())
                else -> throw IdentityDeviceFailure()
            } }
        }
        val identity = IdentitySession(device, backgroundScope)
        runCurrent()
        identity.verify("123456"); runCurrent()
        assertEquals(RegistrationPhase.REGISTERED, identity.view.value.phase)
        assertNotNull(identity.saved.trustedKey)
        assertNotNull(identity.saved.registrationResponse)
        assertNull(identity.saved.pending)
        val reopened = IdentitySession(device, backgroundScope)
        runCurrent()
        assertEquals(RegistrationPhase.REGISTERED, reopened.view.value.phase)

        val failing = Device().apply {
            secure = identityJson.encodeToString(IdentityState(installation = installation, installationRefresh = "installation-refresh", pending = pending)).encodeToByteArray()
            handler = { _, _, _ -> failWrite = true; IdentityHttpResponse(200, registration()) }
        }
        val blocked = IdentitySession(failing, backgroundScope)
        runCurrent()
        blocked.verify("123456"); runCurrent()
        assertEquals(RegistrationPhase.RECOVERY, blocked.view.value.phase)
    }

    @Test fun newerEmailInvalidatesLateVerifyResponse() = runTest {
        val pending = PendingChallenge("{}", "self", encode64(ByteArray(32)), "00000000-0000-4000-8000-000000000000", "pending")
        val response = CompletableDeferred<IdentityHttpResponse>()
        val device = Device().apply {
            secure = identityJson.encodeToString(IdentityState(installation = installation, installationRefresh = "installation-refresh", pending = pending)).encodeToByteArray()
            handler = { _, _, _ -> response.await() }
        }
        val identity = IdentitySession(device, backgroundScope)
        runCurrent()
        identity.verify("123456"); runCurrent()
        identity.changeEmail()
        response.complete(IdentityHttpResponse(200, registration()))
        runCurrent()
        assertEquals(RegistrationPhase.REQUIRED, identity.view.value.phase)
        assertNull(identity.saved.registrationResponse)
        assertNull(identity.saved.verifyResponse)
    }

    @Test fun preferenceConflictCannotReenableCollectionAfterRestart() = runTest {
        val device = Device().apply {
            secure = identityJson.encodeToString(registeredState().copy(consentDesired = "allowed")).encodeToByteArray()
            handler = { url, _, body ->
                if (url.endsWith("/telemetry-preference") && body != null) IdentityHttpResponse(409, "{}")
                else IdentityHttpResponse(200, preference(5, "completed"))
            }
        }
        val identity = IdentitySession(device, backgroundScope)
        runCurrent()
        identity.telemetry.setConsent(false); runCurrent()
        assertEquals("denied", identity.view.value.consent)
        assertTrue(identity.saved.consentConflict)
        assertTrue(identity.view.value.consentPending)
        val writes = device.requests.count { it.third != null }
        identity.telemetry.synchronize(); runCurrent()
        assertEquals(writes, device.requests.count { it.third != null })
        val restarted = IdentitySession(device, backgroundScope)
        runCurrent()
        restarted.telemetry.foreground("new-window", true)
        restarted.telemetry.product("card_select"); runCurrent()
        assertEquals("denied", restarted.view.value.consent)
        assertTrue(restarted.view.value.consentConflict)
        assertTrue(restarted.view.value.consentPending)
        assertTrue(restarted.saved.batches.isEmpty())
        assertTrue(device.requests.none { it.first.endsWith("/v2/batches") })
        val previousIntent = jsonObject(device.requests.first { it.third != null }.third!!)
        device.now += 1000
        device.handler = { _, _, _ -> IdentityHttpResponse(200, preference(0, "completed", "denied", 3)) }
        restarted.telemetry.setConsent(false); runCurrent()
        val newIntent = jsonObject(device.requests.last { it.third != null }.third!!)
        assertNotEquals(previousIntent.text("request_id"), newIntent.text("request_id"))
        assertEquals(timestamp(device.now), newIntent.text("recorded_at"))
        assertFalse(restarted.view.value.consentConflict)
        assertFalse(restarted.view.value.consentPending)
        assertEquals("denied", restarted.saved.preference)
    }

    @Test fun rapidOffOnSendsOrderedCasCommandsAndCollectsOnlyNewEpoch() = runTest {
        var serverVersion = 1
        var serverPreference = "allowed"
        var epoch = 7
        val writes = mutableListOf<JsonObject>()
        val device = Device().apply {
            secure = identityJson.encodeToString(registeredState().copy(consentDesired = "allowed")).encodeToByteArray()
            handler = { url, _, body ->
                if (url.endsWith("/v2/batches")) IdentityHttpResponse(202, "{}")
                else {
                    if (body != null) {
                        val command = jsonObject(body)
                        assertEquals(serverVersion.toLong(), command.number("expected_version"))
                        writes += command
                        serverVersion++
                        serverPreference = command.text("preference")
                        epoch = if (serverPreference == "allowed") 8 else 0
                    }
                    IdentityHttpResponse(200, preference(epoch, "completed", serverPreference, serverVersion))
                }
            }
        }
        val identity = IdentitySession(device, backgroundScope)
        runCurrent()
        identity.telemetry.setConsent(false)
        identity.telemetry.setConsent(true)
        runCurrent()
        advanceTimeBy(1000); runCurrent()
        assertEquals(listOf("denied", "allowed"), writes.map { it.text("preference") })
        assertEquals(listOf(1L, 2L), writes.map { it.number("expected_version") })
        assertTrue(identity.saved.commands.isEmpty())
        identity.telemetry.foreground("window", true); runCurrent()
        val sent = device.requests.filter { it.first.endsWith("/v2/batches") }
        assertTrue(sent.isNotEmpty())
        assertTrue(sent.all { jsonObject(it.third!!).getValue("scope").jsonObject.getValue("consent_epoch").jsonPrimitive.content == "8" })
    }

    @Test fun telemetryQueueWriteFailureDoesNotCloseVerifiedOfflineGate() = runTest {
        val device = Device().apply {
            secure = identityJson.encodeToString(registeredState().copy(consentDesired = "allowed")).encodeToByteArray()
            handler = { url, _, _ -> if (url.endsWith("/v2/batches")) IdentityHttpResponse(202, "{}")
                else IdentityHttpResponse(200, preference(5, "completed")) }
        }
        val identity = IdentitySession(device, backgroundScope)
        runCurrent()
        identity.telemetry.foreground("window", true); runCurrent()
        assertTrue(identity.saved.batches.isEmpty())
        device.failWrite = true
        identity.telemetry.product("card_select"); runCurrent()
        assertEquals(RegistrationPhase.REGISTERED, identity.view.value.phase)
        assertTrue(identity.registered)
        assertTrue(identity.saved.batches.isEmpty())
    }

    @Test fun consentQueueReservesCapacityForWithdrawalAndNeverEnablesPastCapacity() = runTest {
        val commands = List(31) { index -> ConsentCommand("00000000-0000-4000-8000-${index.toString().padStart(12, '0')}",
            if (index % 2 == 0) "allowed" else "denied", "2026-11-08T00:00:00.000Z") }
        val device = Device().apply {
            secure = identityJson.encodeToString(registeredState().copy(consentDesired = "allowed", commands = commands)).encodeToByteArray()
        }
        val identity = IdentitySession(device, backgroundScope)
        runCurrent()
        identity.telemetry.setConsent(false); runCurrent()
        identity.telemetry.setConsent(true); runCurrent()
        assertEquals(32, identity.saved.commands.size)
        assertEquals("denied", identity.saved.commands.last().preference)
        assertEquals("denied", identity.view.value.consent)
        assertTrue(identity.saved.batches.isEmpty())
    }

    @Test fun windowsAndConfigurationShareSessionButRealForegroundStartsNewSession() = runTest {
        val device = Device().apply {
            secure = identityJson.encodeToString(registeredState().copy(consentDesired = "allowed")).encodeToByteArray()
            handler = { url, _, _ -> if (url.endsWith("/v2/batches")) IdentityHttpResponse(202, "{}")
                else IdentityHttpResponse(200, preference(5, "completed")) }
        }
        val identity = IdentitySession(device, backgroundScope)
        runCurrent()
        identity.telemetry.foreground("a", true); runCurrent()
        identity.telemetry.foreground("second-window", true); runCurrent()
        identity.telemetry.foreground("second-window", false)
        identity.telemetry.foreground("a", false, configurationChange = true)
        identity.telemetry.foreground("b", true); runCurrent()
        fun records() = device.requests.filter { it.first.endsWith("/v2/batches") }.map {
            jsonObject(it.third!!).getValue("records").jsonArray.single().jsonObject
        }
        assertEquals(1, records().count { it.text("kind") == "app_open" })
        val first = records().first().text("app_session_id")
        identity.telemetry.foreground("b", false)
        identity.telemetry.utterance("cancelled", "standard"); runCurrent()
        assertEquals(first, records().last().text("app_session_id"))
        assertEquals("cancelled", records().last().text("result"))
        identity.telemetry.foreground("c", true); runCurrent()
        val opens = records().filter { it.text("kind") == "app_open" }
        assertEquals(2, opens.size)
        assertNotEquals(first, opens.last().text("app_session_id"))
    }

    @Test fun completedAllowedWithoutMetricsTokenNeverActivatesCollection() = runTest {
        val device = Device().apply {
            secure = identityJson.encodeToString(registeredState().copy(consentDesired = "allowed")).encodeToByteArray()
            handler = { _, _, _ -> IdentityHttpResponse(200, JsonObject(jsonObject(preference(5, "completed")) + ("metrics_token" to JsonNull)).toString()) }
        }
        val identity = IdentitySession(device, backgroundScope)
        runCurrent()
        identity.telemetry.foreground("window", true); runCurrent()
        identity.telemetry.product("card_select")
        identity.telemetry.utterance("completed", "standard"); runCurrent()
        assertTrue(identity.saved.batches.isEmpty())
        assertTrue(device.requests.none { it.first.endsWith("/v2/batches") })
    }

    @Test fun quotaRetryDeadlineSurvivesRestartWithoutEarlySendOrQuotaDrop() = runTest {
        val quotaSeconds = 3600L
        val device = Device().apply {
            secure = identityJson.encodeToString(registeredState().copy(consentDesired = "allowed")).encodeToByteArray()
            handler = { url, _, _ -> if (url.endsWith("/v2/batches")) IdentityHttpResponse(429, "{}", quotaSeconds)
                else IdentityHttpResponse(200, preference(5, "completed")) }
        }
        val identity = IdentitySession(device, backgroundScope)
        runCurrent()
        identity.telemetry.foreground("window", true); runCurrent()
        val original = identity.saved.batches.single()
        assertEquals(device.now + quotaSeconds * 1000, original.nextRetryAt)
        assertEquals(0, original.attempts)
        advanceTimeBy(600000); runCurrent()
        identity.telemetry.synchronize(); runCurrent()
        assertEquals(1, device.requests.count { it.first.endsWith("/v2/batches") })

        val restarted = IdentitySession(device, backgroundScope)
        runCurrent()
        restarted.telemetry.foreground("restarted", true); runCurrent()
        assertEquals(1, device.requests.count { it.first.endsWith("/v2/batches") })
        device.now = original.nextRetryAt - 1
        restarted.telemetry.synchronize(); runCurrent()
        assertEquals(1, device.requests.count { it.first.endsWith("/v2/batches") })
        repeat(5) {
            device.now = restarted.saved.batches.first().nextRetryAt
            restarted.telemetry.synchronize(); runCurrent()
        }
        val sent = device.requests.filter { it.first.endsWith("/v2/batches") }
        assertEquals(6, sent.size)
        assertTrue(sent.all { it.third == original.body })
        assertEquals(0, restarted.saved.batches.first().attempts)
        assertTrue(restarted.saved.batches.first().nextRetryAt > device.now)
        assertEquals(Long.MAX_VALUE, retryDeadline(device.now, Long.MAX_VALUE))
        assertEquals(0L, retryDeadline(-1000, 1))
    }

    @Test fun zeroCardPublicationDoesNotInventOneCardOrReportFailedSaveAsSuccess() = runTest {
        val device = Device().apply {
            secure = identityJson.encodeToString(registeredState().copy(consentDesired = "allowed")).encodeToByteArray()
            handler = { url, _, _ -> if (url.endsWith("/v2/batches")) IdentityHttpResponse(202, "{}")
                else IdentityHttpResponse(200, preference(5, "completed")) }
        }
        val identity = IdentitySession(device, backgroundScope)
        runCurrent()
        identity.telemetry.foreground("window", true); runCurrent()
        device.requests.clear()
        identity.telemetry.setSaved(created = true, count = 0, completed = false); runCurrent()
        assertTrue(device.requests.isEmpty())
        identity.telemetry.setSaved(created = true, count = 0, completed = true); runCurrent()
        val record = jsonObject(device.requests.single().third!!).getValue("records").jsonArray.single().jsonObject
        assertEquals("set_save", record.text("kind"))
        assertFalse("count_bucket" in record)
        assertEquals("product", jsonObject(device.requests.single().third!!).text("stream"))
    }

    @Test fun explicitResendKeepsEmailAndRoleButRotatesProofOnlyAfterServerDeadlines() = runTest {
        val device = Device()
        val requestId = "00000000-0000-4000-8000-000000000000"
        val oldBody = buildJsonObject {
            put("request_id", requestId); put("product_id", IDENTITY_PRODUCT); put("email", "private@example.test")
            put("purpose", "product_registration"); put("holder_role", "representative"); put("client_nonce", encode64(ByteArray(32)))
        }.toString()
        val floor = device.now + 120000
        device.secure = identityJson.encodeToString(IdentityState(installation = installation, installationRefresh = "installation-refresh",
            nextBeginAt = device.now + 60000, pending = PendingChallenge(oldBody, "representative", encode64(ByteArray(32)),
                requestId, "queued", timestamp(device.now + 172800000), timestamp(device.now + 60000), floor))).encodeToByteArray()
        val accepted = CompletableDeferred<Unit>()
        device.handler = { _, _, body ->
            if (jsonObject(body!!).text("request_id") != requestId) accepted.await()
            IdentityHttpResponse(202, buildJsonObject {
                put("contract_version", 1); put("challenge_id", requestId); put("status", "queued")
                put("expires_at", timestamp(device.now + 172800000)); put("resend_after", timestamp(device.now))
            }.toString())
        }
        val identity = IdentitySession(device, backgroundScope)
        runCurrent()
        identity.resend(); runCurrent()
        assertTrue(device.requests.isEmpty())
        assertEquals(floor, identity.view.value.resendAt)
        device.now = floor
        identity.begin("", "self", poll = true); runCurrent()
        assertEquals(oldBody, device.requests.single().third)
        identity.resend(); runCurrent()
        assertTrue(identity.view.value.busy)
        identity.resend(); runCurrent()
        assertEquals(2, device.requests.size)
        val newBody = device.requests.last().third!!
        val newProof = jsonObject(newBody)
        assertEquals("private@example.test", newProof.text("email"))
        assertEquals("representative", newProof.text("holder_role"))
        assertNotEquals(requestId, newProof.text("request_id"))
        assertNotEquals(jsonObject(oldBody).text("client_nonce"), newProof.text("client_nonce"))
        assertEquals(32, decode64(newProof.text("client_nonce")).size)
        accepted.complete(Unit); runCurrent()
        identity.begin("", "self", poll = true); runCurrent()
        assertEquals(newBody, device.requests.last().third)
        assertEquals(RegistrationPhase.CHALLENGE, identity.view.value.phase)
    }

    @Test fun newerEmailDuringFinalSecureWriteCannotPublishOrRetainStaleReceipt() = runTest {
        val releaseWrite = CompletableDeferred<Unit>()
        val pending = PendingChallenge("{}", "self", encode64(ByteArray(32)), "00000000-0000-4000-8000-000000000000", "pending")
        val device = Device().apply {
            secure = identityJson.encodeToString(IdentityState(installation = installation, installationRefresh = "installation-refresh", pending = pending)).encodeToByteArray()
            beforeWrite = { bytes ->
                if (identityJson.decodeFromString<IdentityState>(bytes.decodeToString()).registrationResponse != null) releaseWrite.await()
            }
            handler = { url, _, _ -> if (url.endsWith("/verify")) IdentityHttpResponse(200, registration())
                else IdentityHttpResponse(200, buildJsonObject { put("keys", JsonArray(listOf(key))) }.toString()) }
        }
        val identity = IdentitySession(device, backgroundScope)
        runCurrent()
        identity.verify("123456"); runCurrent()
        assertEquals(RegistrationPhase.CHALLENGE, identity.view.value.phase)
        identity.changeEmail()
        releaseWrite.complete(Unit); runCurrent()
        assertEquals(RegistrationPhase.REQUIRED, identity.view.value.phase)
        assertNull(identity.saved.registrationResponse)
        assertNull(identityJson.decodeFromString<IdentityState>(device.secure!!.decodeToString()).registrationResponse)
    }
}
