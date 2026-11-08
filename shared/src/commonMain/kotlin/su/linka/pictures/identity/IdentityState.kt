package su.linka.pictures.identity

import kotlinx.serialization.Serializable

internal enum class RegistrationPhase { LOADING, REQUIRED, CHALLENGE, REGISTERED, RECOVERY }
internal data class IdentityView(val phase: RegistrationPhase = RegistrationPhase.LOADING,
    val busy: Boolean = false, val challengeStatus: String? = null, val expiresAt: String? = null,
    val resendAfter: String? = null, val retryAt: Long = 0, val problem: Boolean = false,
    val consent: String = "unknown", val consentPending: Boolean = false, val consentConflict: Boolean = false,
    val hasChallenge: Boolean = false, val registrationRevision: Long = 0, val resendAt: Long = 0,
    val hasReceivedProof: Boolean = false)

@Serializable
internal data class PendingChallenge(val body: String, val role: String, val nonce: String,
    val id: String? = null, val status: String? = null, val expiresAt: String? = null,
    val resendAfter: String? = null, val retryAt: Long = 0)

@Serializable
internal data class ConsentCommand(val requestId: String, val preference: String,
    val recordedAt: String, val body: String? = null)

@Serializable
internal data class FrozenBatch(val id: String, val epoch: ULong, val body: String, val attempts: Int = 0,
    val nextRetryAt: Long = 0)

@Serializable
internal data class IdentityState(val version: Int = 1, val bootstrapBody: String? = null,
    val installation: String? = null, val installationRefresh: String? = null,
    val nextBeginAt: Long = 0,
    val pending: PendingChallenge? = null, val verifyResponse: String? = null,
    val registrationResponse: String? = null, val trustedKey: String? = null,
    val accountRefresh: String? = null, val accountAccess: String? = null, val accountExpires: String? = null,
    val preference: String = "unknown", val preferenceVersion: ULong = 1u,
    val consentDesired: String = "unknown", val consentConflict: Boolean = false,
    val consentEpoch: ULong = 0u, val deliveryCompleted: Boolean = false,
    val metricsAccess: String? = null, val metricsExpires: String? = null,
    val commands: List<ConsentCommand> = emptyList(), val batches: List<FrozenBatch> = emptyList())
