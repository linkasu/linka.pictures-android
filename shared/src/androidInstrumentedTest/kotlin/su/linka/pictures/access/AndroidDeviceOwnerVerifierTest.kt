package su.linka.pictures.access

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.OwnerVerificationAvailability
import su.linka.pictures.domain.RequestId

class AndroidDeviceOwnerVerifierTest {
    private val application = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun minimumApiRefusesBeforeConsultingAnySystemSecurityService() = runBlocking {
        var serviceCalls = 0
        val context = object : ContextWrapper(application) {
            override fun getApplicationContext(): Context = this
            override fun getSystemService(name: String): Any? { serviceCalls++; throw IllegalStateException("Test service denial") }
        }
        val verifier = AndroidDeviceOwnerVerifier(context, "Проверка", "Только собственный тест", { _, _, _ ->
            error("No OS prompt is authorized by this test")
        }, {}, { _, _ -> true }, { true }, {})
        if (Build.VERSION.SDK_INT <= 22) {
            assertEquals(OwnerVerificationAvailability.UNAVAILABLE, verifier.availability())
            assertEquals(ProblemCode.OWNER_VERIFICATION_UNAVAILABLE,
                (verifier.verify(RequestId("test"), "owner") as OperationResult.Failed).problem.code)
            assertEquals(0, serviceCalls)
        } else {
            assertEquals(OwnerVerificationAvailability.UNKNOWN, verifier.availability())
            assertEquals(1, serviceCalls)
        }
    }

    @Test fun staleOrBackgroundOwnerNeverStartsSystemUi() = runBlocking {
        var prompts = 0
        var presentationCallbacks = 0
        val foregroundOwners = mutableListOf<String>()
        for ((current, foreground) in listOf(false to true, true to false)) {
            val verifier = AndroidDeviceOwnerVerifier(application, "Проверка", "Только собственный тест", { _, _, _ ->
                prompts++; OperationResult.Completed(Unit)
            }, {}, { _, _ -> current }, { owner -> foregroundOwners += owner; foreground }, { presentationCallbacks++ })
            val result = verifier.verify(RequestId("test"), "owner")
            if (Build.VERSION.SDK_INT <= 22) assertEquals(ProblemCode.OWNER_VERIFICATION_UNAVAILABLE,
                (result as OperationResult.Failed).problem.code)
            else assertEquals(OperationResult.Cancelled, result)
            assertFalse(verifier.hasPendingRequest)
        }
        assertEquals(0, prompts)
        assertEquals(0, presentationCallbacks)
        assertEquals(if (Build.VERSION.SDK_INT <= 22) emptyList<String>() else listOf("owner"), foregroundOwners)
    }
}
