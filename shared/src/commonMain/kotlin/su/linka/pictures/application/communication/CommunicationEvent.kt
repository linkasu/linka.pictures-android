package su.linka.pictures.application.communication

import su.linka.pictures.application.ports.AudioPhase
import su.linka.pictures.domain.InputStyle
import su.linka.pictures.domain.InteractionMode

/** Safe operational facts, without content or identifiers. */
sealed interface CommunicationEvent {
    data object BoardOpened : CommunicationEvent
    data object CardSelected : CommunicationEvent
    data class UtteranceFinished(val phase: AudioPhase, val mode: InteractionMode, val style: InputStyle) : CommunicationEvent
}
