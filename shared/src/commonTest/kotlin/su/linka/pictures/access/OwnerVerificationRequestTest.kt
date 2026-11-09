package su.linka.pictures.access

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import su.linka.pictures.application.*
import su.linka.pictures.domain.RequestId

class OwnerVerificationRequestTest {
    @Test fun successWaitsForForegroundAndCancellationCannotResurrectIt() = runTest {
        val request = OwnerVerificationRequest(RequestId("request"), "owner")
        val reply = async { request.await() }
        request.proof(OperationResult.Completed(Unit), foreground = false)
        assertFalse(reply.isCompleted)
        request.cancel()
        request.foreground()
        request.proof(OperationResult.Completed(Unit), foreground = true)
        assertEquals(OperationResult.Cancelled, reply.await())
    }

    @Test fun onlyTheFirstProofIsAcceptedAndForegroundConsumesItOnce() = runTest {
        val request = OwnerVerificationRequest(RequestId("request"), "owner")
        request.proof(OperationResult.Completed(Unit), foreground = false)
        request.proof(OperationResult.Failed(Problem(ProblemCode.UNAUTHORIZED)), foreground = true)
        request.foreground()
        assertIs<OperationResult.Completed<Unit>>(request.await())
        assertTrue(request.terminal)
        request.cancel()
        assertIs<OperationResult.Completed<Unit>>(request.await())
    }

    @Test fun failureIsTerminalWithoutForegroundAndUnknownCommitIsNeverAProof() = runTest {
        for (proof in listOf(OperationResult.Cancelled, OperationResult.UnconfirmedCommit,
            OperationResult.Failed(Problem(ProblemCode.OWNER_VERIFICATION_UNAVAILABLE)))) {
            val request = OwnerVerificationRequest(RequestId("request"), "owner")
            request.proof(proof, foreground = false)
            assertTrue(request.terminal)
            val expected = if (proof == OperationResult.UnconfirmedCommit)
                OperationResult.Failed(Problem(ProblemCode.IO_FAILURE)) else proof
            assertEquals(expected, request.await())
        }
    }
}
