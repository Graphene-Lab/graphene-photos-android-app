package com.graphenelab.photosync.data.repository

import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.graphenelab.photosync.data.network.payment.SubscriptionPlan
import com.graphenelab.photosync.data.network.user.CloudSpaceInitializationErrorResponse
import com.graphenelab.photosync.data.network.user.CloudSpaceInitializationResponse
import com.graphenelab.photosync.data.network.user.CloudSpaceService
import com.graphenelab.photosync.domain.model.CloudSpaceCredentials
import com.graphenelab.photosync.domain.model.CloudSpaceInitializationState
import com.graphenelab.photosync.domain.model.CloudSpaceInitializationStatus
import com.graphenelab.photosync.domain.repositroy.DeleteAccountResult
import com.graphenelab.photosync.domain.repositroy.ICloudSpaceRepository
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import retrofit2.Response
import java.io.EOFException
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeParseException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CloudSpaceRepository @Inject constructor(
    private val cloudSpaceService: CloudSpaceService
) : ICloudSpaceRepository {
    private val gson = Gson()

    override suspend fun getCloudSpaceInitializationState(): Result<CloudSpaceInitializationState> =
        cloudSpaceRequest(invalidResponse = ::InvalidCloudSpaceInitializationStatusResponseException) {
            cloudSpaceService.getCloudSpaceInitializationState().toInitialization()
        }

    override suspend fun initializeCloudSpace(): Result<CloudSpaceInitializationState> =
        cloudSpaceRequest(invalidResponse = ::InvalidCloudSpaceInitializationStatusResponseException) {
            cloudSpaceService.initializeCloudSpace().toInitialization()
        }

    override suspend fun getCloudSpaceCredentials(): Result<CloudSpaceCredentials> =
        cloudSpaceRequest {
            val response = cloudSpaceService.getCloudSpaceCredentials()
            val parsedQrEncrypted = response.qrEncrypted?.takeIf { it.isNotBlank() }
                ?: throw InvalidCloudSpaceResponseException("Missing cloud storage pairing credentials")
            val parsedPin = response.pin
                ?: throw InvalidCloudSpaceResponseException("Missing cloud storage pairing PIN")
            CloudSpaceCredentials(
                qrEncrypted = parsedQrEncrypted,
                pin = parsedPin
            )
        }

    private fun Response<CloudSpaceInitializationResponse>.toInitialization(): CloudSpaceInitializationState {
        if (!isSuccessful) throw HttpException(this)
        return body().toDomain()
    }

    private fun CloudSpaceInitializationResponse?.toDomain(): CloudSpaceInitializationState {
        if (this == null) throw InvalidCloudSpaceInitializationStatusResponseException(
            "Missing cloud storage initialization response"
        )
        val parsedStatus = CloudSpaceInitializationStatus.entries.firstOrNull { it.name == status }
            ?: throw InvalidCloudSpaceInitializationStatusResponseException(
                "Unknown cloud storage initialization status"
            )
        val parsedAttempts = attempts
            ?: throw InvalidCloudSpaceInitializationStatusResponseException(
                "Missing cloud storage initialization attempts"
            )

        if (parsedAttempts < 0) {
            throw InvalidCloudSpaceInitializationStatusResponseException(
                "Invalid cloud storage initialization attempts"
            )
        }
        val parsedRetryable = retryable
            ?: throw InvalidCloudSpaceInitializationStatusResponseException(
                "Missing cloud storage initialization retry flag"
            )
        val parsedNextRetryAt = nextRetryAt?.let {
            try {
                Instant.parse(it)
            } catch (e: DateTimeParseException) {
                throw InvalidCloudSpaceInitializationStatusResponseException(
                    "Invalid cloud storage initialization next retry time",
                    e
                )
            }
        }
        return CloudSpaceInitializationState(
            status = parsedStatus,
            attempts = parsedAttempts,
            nextRetryAt = parsedNextRetryAt,
            errorCode = errorCode,
            retryable = parsedRetryable
        )
    }

    private suspend fun <T> cloudSpaceRequest(
        invalidResponse: (String, Throwable?) -> InvalidCloudSpaceResponseException = ::InvalidCloudSpaceResponseException,
        request: suspend () -> T
    ): Result<T> {
        return try {
            Result.success(request())
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            Result.failure(mapCloudSpaceError(e))
        } catch (e: JsonParseException) {
            Result.failure(invalidResponse("Invalid cloud storage response", e))
        } catch (e: EOFException) {
            Result.failure(invalidResponse("Incomplete cloud storage response", e))
        } catch (e: IOException) {
            Result.failure(NetworkException("Network error: ${e.message}"))
        } catch (e: InvalidCloudSpaceResponseException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(NetworkException("Unexpected error: ${e.message}"))
        }
    }

    private fun mapCloudSpaceError(exception: HttpException): Exception {
        val response = exception.response()
        if (exception.code() == 409 || exception.code() == 503) {
            val state = try {
                val error = gson.fromJson(
                    response?.errorBody()?.string(),
                    CloudSpaceInitializationErrorResponse::class.java
                )
                if (exception.code() == 409 && error?.errorCode == "USER_NOT_FOUND") {
                    return SessionExpiredException("Account no longer exists. Please sign in again.")
                }
                error?.state?.toDomain()
            } catch (e: InvalidCloudSpaceResponseException) {
                return e
            } catch (e: JsonParseException) {
                return InvalidCloudSpaceInitializationStatusResponseException(
                    "Invalid cloud storage initialization error response",
                    e
                )
            } catch (e: IOException) {
                return NetworkException("Network error: ${e.message}")
            }
            if (state != null) {
                return CloudSpaceInitializationException(state, exception.code(), exception)
            }
        }
        return when (exception.code()) {
            401, 403 -> UnauthorizedException("User not authenticated")
            404 -> NotFoundException("Cloud space endpoint or credentials not found")
            500 -> ServerErrorException("Server error occurred")
            else -> NetworkException("HTTP error: ${exception.code()}")
        }
    }

    override suspend fun getCurrentSubscriptionPlan(): Result<SubscriptionPlan> {
        return try {
            val plan = cloudSpaceService.getCurrentSubscriptionPlan()
            Result.success(plan)
        } catch (e: HttpException) {
            Result.failure(
                when (e.code()) {
                    401 -> UnauthorizedException("User not authenticated")
                    404 -> NotFoundException("Subscription plan not found")
                    else -> NetworkException("HTTP error: ${e.code()}")
                }
            )
        } catch (e: IOException) {
            Result.failure(NetworkException("Network error: ${e.message}"))
        }
    }

    override suspend fun deleteCurrentUserAccount(): Result<DeleteAccountResult> {
        return try {
            val response = cloudSpaceService.deleteCurrentUserAccount()
            when {
                response.code() == 204 || response.isSuccessful -> {
                    Result.success(DeleteAccountResult.Deleted)
                }

                response.code() == 401 || response.code() == 403 -> {
                    Result.failure(SessionExpiredException("Session expired"))
                }

                response.code() == 409 && isUserNotFoundConflict(
                    response.errorBody()?.string()
                ) -> {
                    Result.success(DeleteAccountResult.UserNotFound)
                }

                else -> {
                    Result.failure(
                        RetryableOperationException("Unable to delete account. Please try again.")
                    )
                }
            }
        } catch (e: IOException) {
            Result.failure(RetryableOperationException("Unable to delete account. Please try again."))
        } catch (e: Exception) {
            Result.failure(RetryableOperationException("Unable to delete account. Please try again."))
        }
    }

    private fun isUserNotFoundConflict(errorBody: String?): Boolean {
        if (errorBody.isNullOrBlank()) return false
        return errorBody.contains("USER_NOT_FOUND", ignoreCase = true)
    }
}

// Custom exception classes
class NetworkException(message: String) : Exception(message)
class UnauthorizedException(message: String) : Exception(message)
class NotFoundException(message: String) : Exception(message)
class ServerErrorException(message: String) : Exception(message)
class SessionExpiredException(message: String) : Exception(message)
class RetryableOperationException(message: String) : Exception(message)

class CloudSpaceInitializationException(
    val state: CloudSpaceInitializationState,
    val httpStatus: Int,
    cause: Throwable? = null
) : Exception(
    "Cloud storage initialization unavailable " +
            "(HTTP $httpStatus, state=${state.status}, code=${state.errorCode})",
    cause
)

open class InvalidCloudSpaceResponseException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

class InvalidCloudSpaceInitializationStatusResponseException(
    message: String,
    cause: Throwable? = null
) : InvalidCloudSpaceResponseException(message, cause)
