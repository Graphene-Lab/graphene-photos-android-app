package com.graphenelab.photosync.manager

import com.graphenelab.photosync.data.repository.CloudSpaceInitializationException
import com.graphenelab.photosync.data.repository.InvalidCloudSpaceInitializationStatusResponseException
import com.graphenelab.photosync.data.repository.InvalidCloudSpaceResponseException
import com.graphenelab.photosync.data.repository.NetworkException
import com.graphenelab.photosync.data.repository.NotFoundException
import com.graphenelab.photosync.data.repository.SessionExpiredException
import com.graphenelab.photosync.data.repository.UnauthorizedException
import com.graphenelab.photosync.domain.model.CloudSpaceCredentials
import com.graphenelab.photosync.domain.model.CloudSpaceInitializationState
import com.graphenelab.photosync.domain.model.CloudSpaceInitializationStatus
import com.graphenelab.photosync.domain.model.CloudSpaceInitializationStatus.*
import com.graphenelab.photosync.domain.repositroy.ICloudSpaceRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifySequence
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class CloudSpaceInitializationControllerTest {
    private val repository = mockk<ICloudSpaceRepository>()
    private val controller = CloudSpaceInitializationController(repository)
    private val credentials = CloudSpaceCredentials("local-test-qr", 123456)
    private val exhausted = setup(FAILED, "CLOUD_SPACE_RETRY_LIMIT_REACHED").copy(attempts = 5)

    @Test
    fun readyFetchesCredentialsOnceAndCompletes() = runTest {
        coEvery { repository.getCloudSpaceInitializationState() } returns Result.success(setup(READY))
        coEvery { repository.getCloudSpaceCredentials() } returns Result.success(credentials)

        val states = controller.waitForCloudSpaceCredentials().toList()

        assertEquals(
            listOf(
                CloudSpaceInitializationProgress.FetchingCredentials,
                CloudSpaceInitializationProgress.Ready(credentials)
            ),
            states
        )
        coVerifySequence {
            repository.getCloudSpaceInitializationState()
            repository.getCloudSpaceCredentials()
        }
    }

    @Test
    fun pendingAndRunningPollStatusWithoutStartingInitialization() = runTest {
        coEvery { repository.getCloudSpaceInitializationState() } returnsMany listOf(
            Result.success(setup(PENDING)),
            Result.success(setup(IN_PROGRESS)),
            Result.success(setup(READY))
        )
        coEvery { repository.getCloudSpaceCredentials() } returns Result.success(credentials)

        val states = controller.waitForCloudSpaceCredentials().toList()

        assertEquals(
            listOf(PENDING, IN_PROGRESS),
            states.filterIsInstance<CloudSpaceInitializationProgress.Preparing>().map { it.state.status }
        )
        assertEquals(10_000L, testScheduler.currentTime)
        assertEquals(1, states.filterIsInstance<CloudSpaceInitializationProgress.Ready>().size)
        coVerify(exactly = 3) { repository.getCloudSpaceInitializationState() }
        coVerify(exactly = 0) { repository.initializeCloudSpace() }
    }

    @Test
    fun scheduledRetryWithoutBackendTimingUsesFallbackInterval() = runTest {
        val scheduled = setup(RETRY_SCHEDULED, "CLOUD_SPACE_CONNECTION_FAILED")
        coEvery { repository.getCloudSpaceInitializationState() } returnsMany listOf(
            Result.success(scheduled),
            Result.success(setup(READY))
        )
        coEvery { repository.getCloudSpaceCredentials() } returns Result.success(credentials)

        val states = controller.waitForCloudSpaceCredentials().toList()

        assertEquals(CloudSpaceInitializationProgress.RetryScheduled(scheduled), states.first())
        assertEquals(5_000L, testScheduler.currentTime)
        assertIs<CloudSpaceInitializationProgress.Ready>(states.last())
        coVerify(exactly = 0) { repository.initializeCloudSpace() }
    }

    @Test
    fun scheduledRetryDoesNotPollBeforeBackendTime() = runTest {
        val scheduled = setup(RETRY_SCHEDULED).copy(nextRetryAt = Instant.now().plusSeconds(600))
        coEvery { repository.getCloudSpaceInitializationState() } returns Result.success(scheduled)
        val states = mutableListOf<CloudSpaceInitializationProgress>()
        val run = backgroundScope.launch { controller.waitForCloudSpaceCredentials().toList(states) }
        runCurrent()

        advanceTimeBy(30_000)
        runCurrent()

        assertEquals(listOf(CloudSpaceInitializationProgress.RetryScheduled(scheduled)), states)
        coVerify(exactly = 1) { repository.getCloudSpaceInitializationState() }
        coVerify(exactly = 0) { repository.initializeCloudSpace() }
        coVerify(exactly = 0) { repository.getCloudSpaceCredentials() }
        run.cancel()
        run.join()
    }

    @Test
    fun exhaustedAttemptsStopCheckingAndOfferExplicitRetry() = runTest {
        coEvery { repository.getCloudSpaceInitializationState() } returns Result.success(exhausted)

        val states = controller.waitForCloudSpaceCredentials().toList()
        advanceTimeBy(3_600_000)

        assertEquals(CloudSpaceInitializationProgress.RetryLimitReached(exhausted), states.last())
        coVerify(exactly = 1) { repository.getCloudSpaceInitializationState() }
        coVerify(exactly = 0) { repository.initializeCloudSpace() }
        coVerify(exactly = 0) { repository.getCloudSpaceCredentials() }
    }

    @Test
    fun permanentFailureRequiresSupportAndStopsChecking() = runTest {
        val failed = setup(FAILED, "CLOUD_SPACE_CREDENTIALS_MISSING")
        coEvery { repository.getCloudSpaceInitializationState() } returns Result.success(failed)

        val states = controller.waitForCloudSpaceCredentials().toList()

        assertEquals(CloudSpaceInitializationProgress.SupportRequired(failed), states.last())
        coVerify(exactly = 1) { repository.getCloudSpaceInitializationState() }
        coVerify(exactly = 0) { repository.initializeCloudSpace() }
    }

    @Test
    fun explicitInitializationPostsOnceAndReturnsState() = runTest {
        val pending = setup(PENDING)
        coEvery { repository.initializeCloudSpace() } returns Result.success(pending)

        assertEquals(pending, controller.initializeCloudSpace())
        assertEquals(0L, testScheduler.currentTime)
        coVerifySequence {
            repository.initializeCloudSpace()
        }
    }

    @Test
    fun initializationReturnsReadyStateWithoutFetchingCredentials() = runTest {
        val ready = setup(READY)
        coEvery { repository.initializeCloudSpace() } returns Result.success(ready)

        assertEquals(ready, controller.initializeCloudSpace())
        coVerifySequence {
            repository.initializeCloudSpace()
        }
    }

    @Test
    fun initializationPropagatesExpiredSession() = runTest {
        coEvery { repository.initializeCloudSpace() } returns Result.failure(UnauthorizedException("Expired"))

        assertFailsWith<UnauthorizedException> { controller.initializeCloudSpace() }
        coVerifySequence { repository.initializeCloudSpace() }
    }

    @Test
    fun backendInitializationRefusalPropagatesItsState() = runTest {
        val failed = setup(FAILED, "CLOUD_SPACE_CREDENTIALS_MISSING")
        coEvery { repository.initializeCloudSpace() } returns Result.failure(
            CloudSpaceInitializationException(failed, 409)
        )

        val exception = assertFailsWith<CloudSpaceInitializationException> { controller.initializeCloudSpace() }

        assertEquals(failed, exception.state)
        coVerify(exactly = 1) { repository.initializeCloudSpace() }
        coVerify(exactly = 0) { repository.getCloudSpaceInitializationState() }
    }

    @Test
    fun retryTimeoutStopsAndRecoveryCanCheckStatusWithoutRepeatingPost() = runTest {
        coEvery { repository.initializeCloudSpace() } coAnswers { awaitCancellation() }

        assertFailsWith<NetworkException> { controller.initializeCloudSpace() }
        assertEquals(15_000L, testScheduler.currentTime)
        coEvery { repository.getCloudSpaceInitializationState() } returns Result.success(setup(READY))
        coEvery { repository.getCloudSpaceCredentials() } returns Result.success(credentials)

        assertIs<CloudSpaceInitializationProgress.Ready>(controller.waitForCloudSpaceCredentials().toList().last())
        coVerify(exactly = 1) { repository.initializeCloudSpace() }
        coVerify(exactly = 1) { repository.getCloudSpaceInitializationState() }
    }

    @Test
    fun networkFailureIsReturnedAndANewCheckCanRecover() = runTest {
        val failure = NetworkException("Local test connection failure")
        coEvery { repository.getCloudSpaceInitializationState() } returnsMany listOf(
            Result.failure(failure),
            Result.success(setup(READY))
        )
        coEvery { repository.getCloudSpaceCredentials() } returns Result.success(credentials)

        val failed = assertIs<CloudSpaceInitializationProgress.RequestFailed>(controller.waitForCloudSpaceCredentials().toList().last())
        assertEquals(failure.message, assertIs<NetworkException>(failed.exception).message)
        assertIs<CloudSpaceInitializationProgress.Ready>(controller.waitForCloudSpaceCredentials().toList().last())
        coVerify(exactly = 0) { repository.initializeCloudSpace() }
    }

    @Test
    fun credentials503ReturnsToInitializationBeforeFetchingCredentialsAgain() = runTest {
        val scheduled = setup(RETRY_SCHEDULED, "CLOUD_SPACE_CONNECTION_FAILED")
        coEvery { repository.getCloudSpaceInitializationState() } returns Result.success(setup(READY))
        coEvery { repository.getCloudSpaceCredentials() } returnsMany listOf(
            Result.failure(CloudSpaceInitializationException(scheduled, 503)),
            Result.success(credentials)
        )

        val states = controller.waitForCloudSpaceCredentials().toList()

        assertEquals(1, states.filterIsInstance<CloudSpaceInitializationProgress.RetryScheduled>().size)
        assertEquals(1, states.filterIsInstance<CloudSpaceInitializationProgress.Ready>().size)
        assertEquals(5_000L, testScheduler.currentTime)
        coVerify(exactly = 2) { repository.getCloudSpaceInitializationState() }
        coVerify(exactly = 2) { repository.getCloudSpaceCredentials() }
        coVerify(exactly = 0) { repository.initializeCloudSpace() }
    }

    @Test
    fun credentialsFailureRequiringSupportStopsWithoutAnotherStatusCheck() = runTest {
        val failed = setup(FAILED, "CLOUD_SPACE_CREDENTIALS_MISSING")
        coEvery { repository.getCloudSpaceInitializationState() } returns Result.success(setup(READY))
        coEvery { repository.getCloudSpaceCredentials() } returns Result.failure(
            CloudSpaceInitializationException(failed, 409)
        )

        val states = controller.waitForCloudSpaceCredentials().toList()

        assertEquals(CloudSpaceInitializationProgress.SupportRequired(failed), states.last())
        assertEquals(0L, testScheduler.currentTime)
        coVerify(exactly = 1) { repository.getCloudSpaceInitializationState() }
        coVerify(exactly = 1) { repository.getCloudSpaceCredentials() }
        coVerify(exactly = 0) { repository.initializeCloudSpace() }
    }

    @Test
    fun unexpectedInitializationErrorFromStatusStopsTheRun() = runTest {
        coEvery { repository.getCloudSpaceInitializationState() } returns Result.failure(
            CloudSpaceInitializationException(setup(PENDING), 503)
        )

        val failed = assertIs<CloudSpaceInitializationProgress.RequestFailed>(controller.waitForCloudSpaceCredentials().toList().last())

        assertIs<CloudSpaceInitializationException>(failed.exception)
        coVerify(exactly = 1) { repository.getCloudSpaceInitializationState() }
        coVerify(exactly = 0) { repository.getCloudSpaceCredentials() }
        coVerify(exactly = 0) { repository.initializeCloudSpace() }
    }

    @Test
    fun missingStatusEndpointStopsWithoutFetchingCredentials() = runTest {
        coEvery { repository.getCloudSpaceInitializationState() } returns Result.failure(NotFoundException("No status API"))

        val failed = assertIs<CloudSpaceInitializationProgress.RequestFailed>(controller.waitForCloudSpaceCredentials().toList().last())

        assertIs<NotFoundException>(failed.exception)
        coVerify(exactly = 1) { repository.getCloudSpaceInitializationState() }
        coVerify(exactly = 0) { repository.getCloudSpaceCredentials() }
        coVerify(exactly = 0) { repository.initializeCloudSpace() }
    }

    @Test
    fun missingCredentialsAfterReadyFailsInsteadOfAssumingReadiness() = runTest {
        coEvery { repository.getCloudSpaceInitializationState() } returns Result.success(setup(READY))
        coEvery { repository.getCloudSpaceCredentials() } returns Result.failure(NotFoundException("No storage"))

        val failed = assertIs<CloudSpaceInitializationProgress.RequestFailed>(controller.waitForCloudSpaceCredentials().toList().last())

        assertIs<NotFoundException>(failed.exception)
        coVerify(exactly = 1) { repository.getCloudSpaceCredentials() }
        coVerify(exactly = 0) { repository.initializeCloudSpace() }
    }

    @Test
    fun missingInitializationEndpointDoesNotFallBackToCredentials() = runTest {
        coEvery { repository.initializeCloudSpace() } returns Result.failure(NotFoundException("No initialization API"))

        assertFailsWith<NotFoundException> { controller.initializeCloudSpace() }
        coVerify(exactly = 0) { repository.getCloudSpaceCredentials() }
    }

    @Test
    fun rejectedSessionStopsWithoutRetryingInitialization() = runTest {
        coEvery { repository.getCloudSpaceInitializationState() } returns Result.failure(UnauthorizedException("Expired"))

        assertEquals(CloudSpaceInitializationProgress.SessionExpired, controller.waitForCloudSpaceCredentials().toList().last())
        coVerify(exactly = 1) { repository.getCloudSpaceInitializationState() }
        coVerify(exactly = 0) { repository.initializeCloudSpace() }
    }

    @Test
    fun sessionCanAlsoExpireWhileFetchingCredentials() = runTest {
        coEvery { repository.getCloudSpaceInitializationState() } returns Result.success(setup(READY))
        coEvery { repository.getCloudSpaceCredentials() } returns Result.failure(SessionExpiredException("Expired"))

        assertEquals(CloudSpaceInitializationProgress.SessionExpired, controller.waitForCloudSpaceCredentials().toList().last())
    }

    @Test
    fun invalidResponseKeepsItsSpecificException() = runTest {
        val failure = InvalidCloudSpaceInitializationStatusResponseException("Missing attempts")
        coEvery { repository.getCloudSpaceInitializationState() } returns Result.failure(failure)

        val failed = assertIs<CloudSpaceInitializationProgress.RequestFailed>(controller.waitForCloudSpaceCredentials().toList().last())

        assertEquals(failure.message, assertIs<InvalidCloudSpaceInitializationStatusResponseException>(failed.exception).message)
        coVerify(exactly = 0) { repository.getCloudSpaceCredentials() }
    }

    @Test
    fun invalidInitializationResponsePropagatesWithoutFetchingCredentials() = runTest {
        coEvery { repository.initializeCloudSpace() } returns Result.failure(
            InvalidCloudSpaceInitializationStatusResponseException("Invalid initialization response")
        )

        assertFailsWith<InvalidCloudSpaceInitializationStatusResponseException> { controller.initializeCloudSpace() }
        coVerify(exactly = 0) { repository.getCloudSpaceCredentials() }
    }

    @Test
    fun invalidPairingCredentialsCannotProduceReady() = runTest {
        val failure = InvalidCloudSpaceResponseException("Missing cloud storage pairing PIN")
        coEvery { repository.getCloudSpaceInitializationState() } returns Result.success(setup(READY))
        coEvery { repository.getCloudSpaceCredentials() } returns Result.failure(failure)

        val failed = assertIs<CloudSpaceInitializationProgress.RequestFailed>(controller.waitForCloudSpaceCredentials().toList().last())

        assertEquals(failure.message, assertIs<InvalidCloudSpaceResponseException>(failed.exception).message)
        assertFalse(failed.exception is InvalidCloudSpaceInitializationStatusResponseException)
    }

    @Test
    fun statusRequestTimeoutIsBounded() = runTest {
        coEvery { repository.getCloudSpaceInitializationState() } coAnswers { awaitCancellation() }

        val failed = assertIs<CloudSpaceInitializationProgress.RequestFailed>(controller.waitForCloudSpaceCredentials().toList().last())

        assertIs<NetworkException>(failed.exception)
        assertEquals(15_000L, testScheduler.currentTime)
        coVerify(exactly = 1) { repository.getCloudSpaceInitializationState() }
    }

    @Test
    fun cancellingTheRunStopsPollingWithoutShowingAnError() = runTest {
        coEvery { repository.getCloudSpaceInitializationState() } returns Result.success(setup(PENDING))
        val states = mutableListOf<CloudSpaceInitializationProgress>()
        val run = backgroundScope.launch { controller.waitForCloudSpaceCredentials().toList(states) }
        runCurrent()

        run.cancel()
        run.join()
        advanceTimeBy(60_000)
        runCurrent()

        coVerify(exactly = 1) { repository.getCloudSpaceInitializationState() }
        assertFalse(states.any { it is CloudSpaceInitializationProgress.RequestFailed })
        coVerify(exactly = 0) { repository.getCloudSpaceCredentials() }
    }

    @Test
    fun lateResponseFromACancelledRunCannotFetchCredentials() = runTest {
        coEvery { repository.getCloudSpaceInitializationState() } coAnswers {
            withContext(NonCancellable) {
                delay(1_000)
                Result.success(setup(READY))
            }
        }
        val states = mutableListOf<CloudSpaceInitializationProgress>()
        val run = backgroundScope.launch { controller.waitForCloudSpaceCredentials().toList(states) }
        runCurrent()

        run.cancel()
        advanceTimeBy(1_000)
        runCurrent()
        run.join()

        assertFalse(states.any { it is CloudSpaceInitializationProgress.Ready })
        assertFalse(states.any { it is CloudSpaceInitializationProgress.RequestFailed })
        coVerify(exactly = 0) { repository.getCloudSpaceCredentials() }
    }

    @Test
    fun collectorFailureIsNotReinterpretedAsARequestError() = runTest {
        coEvery { repository.getCloudSpaceInitializationState() } returns Result.success(setup(READY))
        coEvery { repository.getCloudSpaceCredentials() } returns Result.success(credentials)
        val failure = IllegalStateException("Local collector failure")

        val thrown = assertFailsWith<IllegalStateException> {
            controller.waitForCloudSpaceCredentials().collect {
                if (it is CloudSpaceInitializationProgress.Ready) throw failure
            }
        }

        assertSame(failure, thrown)
    }

    @Test
    fun pollTimingUsesNextRetryAtOrFallback() {
        val now = Instant.parse("2026-10-18T12:00:00Z")

        assertEquals(5_000L, controller.nextPollDelayMillis(null, now))
        assertEquals(2_000L, controller.nextPollDelayMillis(now.plusSeconds(2), now))
        assertEquals(600_000L, controller.nextPollDelayMillis(now.plusSeconds(600), now))
        assertEquals(5_000L, controller.nextPollDelayMillis(now, now))
        assertEquals(5_000L, controller.nextPollDelayMillis(now.minusSeconds(1), now))
        assertEquals(1_000L, controller.nextPollDelayMillis(now.plusMillis(200), now))
        assertEquals(Long.MAX_VALUE, controller.nextPollDelayMillis(Instant.MAX, now))
    }

    private fun setup(status: CloudSpaceInitializationStatus, errorCode: String? = null) =
        CloudSpaceInitializationState(
            status = status,
            attempts = 1,
            nextRetryAt = null,
            errorCode = errorCode,
            retryable = status != FAILED && status != READY
        )
}
