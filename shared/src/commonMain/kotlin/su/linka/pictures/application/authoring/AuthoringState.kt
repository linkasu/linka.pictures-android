package su.linka.pictures.application.authoring

import su.linka.pictures.application.Problem
import su.linka.pictures.application.ports.AudioState
import su.linka.pictures.domain.*

enum class AuthoringPhase { IDLE, LOADING, EDITING, PAUSED, APPLYING, APPLIED, CONFLICT, UNCONFIRMED, CLOSED }
enum class DraftSavePhase { UNSAVED, SAVING, SAVED, FAILED, UNCONFIRMED }

data class AuthoringState(
    val ownerId: String? = null,
    val phase: AuthoringPhase = AuthoringPhase.IDLE,
    val draft: EditorDraft? = null,
    val epoch: Long? = null,
    val durableVersion: Long? = null,
    val savePhase: DraftSavePhase = DraftSavePhase.UNSAVED,
    val mediaBusy: Boolean = false,
    val audio: AudioState = AudioState(),
    val published: PublishedBoard? = null,
    val problem: Problem? = null,
)
