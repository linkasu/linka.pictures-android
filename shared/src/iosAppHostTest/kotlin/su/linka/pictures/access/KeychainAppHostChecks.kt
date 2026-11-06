package su.linka.pictures.access

/** Synchronous XCTest bridge; the existing runTest methods finish before returning. */
object KeychainAppHostChecks {
    fun bytesReopenUpdateAndRevocation(): String? = check {
        KeychainCredentialStoreTest().actualKeychainBytesReopenUpdateAndRevokedGuardPreserveExistingRecord()
    }

    fun boundsAndServiceValidation(): String? = check {
        KeychainCredentialStoreTest().emptyOversizedAndUnsafeServiceAreRejectedWithoutNativeMutation()
    }

    private fun check(test: () -> Unit): String? = try {
        test()
        null
    } catch (failure: Throwable) {
        // Only synthetic test diagnostics cross the bridge; omit byte dumps and owned service IDs.
        val message = failure.message.orEmpty().take(2048)
            .replace(Regex("\\[[^\\]]*(?:]|$)"), "[redacted]")
            .replace(Regex("su\\.linka\\.pictures\\.test\\.access\\.[A-Za-z0-9-]+"), "<owned-service>")
            .replace(Regex("[\\r\\n\\t]"), " ")
        "${failure::class.simpleName ?: "Throwable"}: $message".take(512)
    }
}
