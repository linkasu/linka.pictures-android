package su.linka.pictures.application.communication

import su.linka.pictures.application.Problem
import su.linka.pictures.application.ports.AudioState
import su.linka.pictures.domain.*

enum class MessageSavePhase { LOADING, SAVING, SAVED, FAILED }

data class CommunicationState(
    val message: MessageDraft = MessageDraft(),
    val stamp: MessageStamp = MessageStamp(0, 0),
    val board: PublishedBoard? = null,
    val renderedText: String = "",
    val canUndoClear: Boolean = false,
    val savePhase: MessageSavePhase = MessageSavePhase.LOADING,
    val audio: AudioState = AudioState(),
    val problem: Problem? = null,
)
