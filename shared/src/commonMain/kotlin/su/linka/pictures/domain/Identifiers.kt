package su.linka.pictures.domain

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import su.linka.pictures.text.isNativeText

@Serializable @JvmInline value class BoardId(val value: String) { init { require(value.isNotBlank() && value.length <= 256 && isNativeText(value)) } }
@Serializable @JvmInline value class PageId(val value: String) { init { require(value.isNotBlank() && value.length <= 256 && isNativeText(value)) } }
@Serializable @JvmInline value class CardId(val value: String) { init { require(value.isNotBlank() && value.length <= 256 && isNativeText(value)) } }
@Serializable @JvmInline value class DraftId(val value: String) { init { require(value.isNotBlank() && value.length <= 256 && isNativeText(value)) } }
@Serializable @JvmInline value class MessagePartId(val value: String) { init { require(value.isNotBlank() && value.length <= 256 && isNativeText(value)) } }
@Serializable @JvmInline value class AssetId(val value: String) {
    init { require(value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }) }
}
@JvmInline value class CommandId(val value: String) { init { require(value.isNotBlank() && isNativeText(value)) } }
@JvmInline value class RequestId(val value: String) { init { require(value.isNotBlank() && isNativeText(value)) } }
@JvmInline value class OperationId(val value: String) { init { require(value.isNotBlank() && isNativeText(value)) } }
@JvmInline value class RecoveryId(val value: String) { init { require(value.isNotBlank() && isNativeText(value)) } }

@Serializable @JvmInline value class FolderId(val value: String) { init { require(value.isNotBlank() && value.length <= 256 && isNativeText(value)) } }
