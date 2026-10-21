package com.graphenelab.photosync.manager

import com.graphenelab.photosync.data.repository.CloudSpaceInitializationException
import com.graphenelab.photosync.data.repository.NetworkException
import com.graphenelab.photosync.data.repository.SessionExpiredException
import com.graphenelab.photosync.data.repository.UnauthorizedException
import com.graphenelab.photosync.domain.model.CloudSpaceInitializationState
import com.graphenelab.photosync.domain.model.CloudSpaceInitializationStatus
import com.graphenelab.photosync.domain.repositroy.ICloudSpaceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

class CloudSpaceInitializationController @Inject constructor(
    private val cloudSpaceRepository: ICloudSpaceRepository
) {
    // Each collection is one cancellable run; callers should keep only one run active.


    suspend fun initializeCloudSpace(): CloudSpaceInitializationState =
        request(cloudSpaceRepository::initializeCloudSpace)

    // after initialization, we need to wait for the credentials to be ready,
    // so this function checks the status and returns the credentials when they are read
    fun waitForCloudSpaceCredentials(
    ): Flow<CloudSpaceInitializationProgress> = flow {
        var state = request(cloudSpaceRepository::getCloudSpaceInitializationState)
        while (true) {
            when (state.status) {
                CloudSpaceInitializationStatus.READY -> {
                    emit(CloudSpaceInitializationProgress.FetchingCredentials)
                    val credentials = try {
                        request(cloudSpaceRepository::getCloudSpaceCredentials)
                        // Storage can become unavailable between the status and credentials calls.
                        // so we get the state from exception body (which returns state in case of error)
                        // and continue polling until it is ready again.
                        // in case of other errors (e.g. network)  we throw the exception and let the caller handle it.
                    } catch (e: CloudSpaceInitializationException) {
                        state = e.state
                        continue
                    }
                    emit(CloudSpaceInitializationProgress.Ready(credentials))
                    return@flow
                }

                CloudSpaceInitializationStatus.PENDING, CloudSpaceInitializationStatus.IN_PROGRESS -> emit(
                    CloudSpaceInitializationProgress.Preparing(state)
                )

                CloudSpaceInitializationStatus.RETRY_SCHEDULED -> emit(
                    CloudSpaceInitializationProgress.RetryScheduled(state)
                )

                CloudSpaceInitializationStatus.FAILED -> {
                    emit(
                        if (state.manualRetryAllowed) CloudSpaceInitializationProgress.RetryLimitReached(
                            state
                        )
                        else CloudSpaceInitializationProgress.SupportRequired(state)
                    )
                    return@flow
                }
            }

            delay(nextPollDelayMillis(state.nextRetryAt).milliseconds)
            state = request(cloudSpaceRepository::getCloudSpaceInitializationState)
        }
    }.handleErrors()

    private fun Flow<CloudSpaceInitializationProgress>.handleErrors(): Flow<CloudSpaceInitializationProgress> =
        catch { exception ->
            when (exception) {
                is CancellationException -> throw exception
                is UnauthorizedException, is SessionExpiredException -> emit(CloudSpaceInitializationProgress.SessionExpired)
                is Exception -> emit(CloudSpaceInitializationProgress.RequestFailed(exception))
                else -> throw exception
            }
        }


    private suspend fun <T : Any> request(operation: suspend () -> Result<T>): T =
        withTimeoutOrNull(REQUEST_TIMEOUT_MS.milliseconds) { operation().getOrThrow() }
            ?: throw NetworkException("Cloud storage request timed out")

    internal fun nextPollDelayMillis(
        nextRetryAt: Instant?, now: Instant = Instant.now()
    ): Long {
        val wait = if (nextRetryAt != null && nextRetryAt.isAfter(now)) {
            Duration.between(now, nextRetryAt)
            // if state is In_PROGRESS or nextRetryAt is in the past, we use the default polling interval
        } else {
            DEFAULT_POLL_DELAY
        }
        // Limit only to the representable millisecond range, not a polling interval.
        return wait.coerceIn(MIN_POLL_DELAY, Duration.ofMillis(Long.MAX_VALUE)).toMillis()
    }

    private companion object {
        const val REQUEST_TIMEOUT_MS = 15_000L
        val DEFAULT_POLL_DELAY: Duration = Duration.ofSeconds(5)
        val MIN_POLL_DELAY: Duration = Duration.ofSeconds(1)
    }
}
