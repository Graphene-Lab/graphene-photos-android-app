package com.graphenelab.photosync.data.repository

import com.google.gson.JsonParseException
import com.graphenelab.photosync.data.network.user.CloudSpaceService
import com.graphenelab.photosync.domain.model.CloudSpaceInitializationStatus
import com.graphenelab.photosync.manager.CloudSpaceInitializationController
import com.graphenelab.photosync.manager.CloudSpaceInitializationProgress
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class CloudSpaceRepositoryTest {
    private data class StubResponse(
        val code: Int,
        val body: String,
        val headers: Headers = Headers.Builder().build(),
        val failure: IOException? = null
    )

    private val responses = ArrayDeque<StubResponse>()
    private val requests = mutableListOf<Request>()
    private lateinit var repository: CloudSpaceRepository

    @BeforeEach
    fun setUp() {
        // All calls end in this interceptor. No socket or live backend is used.
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                requests += chain.request()
                val stub = responses.removeFirst()
                stub.failure?.let { throw it }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(stub.code)
                    .message("Local test response")
                    .headers(stub.headers)
                    .body(stub.body.toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()
        val service = Retrofit.Builder()
            .baseUrl("https://management.invalid/api/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(CloudSpaceService::class.java)
        repository = CloudSpaceRepository(service)
    }

    @ParameterizedTest
    @EnumSource(CloudSpaceInitializationStatus::class)
    fun readsEachBackendStatusWithoutStartingInitialization(status: CloudSpaceInitializationStatus) = runTest {
        responses += StubResponse(
            200,
            """{"status":"${status.name}","attempts":1,"nextRetryAt":null,"errorCode":null,"retryable":false}"""
        )

        val setup = repository.getCloudSpaceInitializationState().getOrThrow()

        assertEquals(status, setup.status)
        assertEquals(1, requests.size)
        assertEquals("GET", requests.single().method)
        assertEquals("/api/me/cloud-space/status", requests.single().url.encodedPath)
    }

    @ParameterizedTest
    @ValueSource(strings = ["7200", "Sun, 18 Oct 2026 14:00:00 GMT"])
    fun preservesScheduledRetryDetailsAndIgnoresRetryHeader(header: String) = runTest {
        responses += StubResponse(
            200,
            """{"status":"RETRY_SCHEDULED","attempts":2,"nextRetryAt":"2026-10-18T12:01:00Z","errorCode":"CLOUD_SPACE_CONNECTION_FAILED","retryable":true}""",
            Headers.headersOf("Retry-After", header)
        )

        val setup = repository.getCloudSpaceInitializationState().getOrThrow()

        assertEquals(2, setup.attempts)
        assertEquals(Instant.parse("2026-10-18T12:01:00Z"), setup.nextRetryAt)
        assertEquals("CLOUD_SPACE_CONNECTION_FAILED", setup.errorCode)
        assertTrue(setup.retryable)
        assertFalse(setup.manualRetryAllowed)
    }

    @Test
    fun explicitRetrySendsOnePostAndAccepts202() = runTest {
        responses += StubResponse(
            202,
            """{"status":"PENDING","attempts":0,"nextRetryAt":null,"errorCode":null,"retryable":true}"""
        )

        val setup = repository.initializeCloudSpace().getOrThrow()

        assertEquals(CloudSpaceInitializationStatus.PENDING, setup.status)
        assertEquals(1, requests.size)
        assertEquals("POST", requests.single().method)
        assertEquals("/api/me/cloud-space/init", requests.single().url.encodedPath)
    }

    @ParameterizedTest
    @ValueSource(strings = ["status", "init", "credentials"])
    fun serviceUnavailablePreservesNestedSetupInsteadOfGenericNetworkError(operation: String) = runTest {
        responses += StubResponse(
            503,
            """{"errorCode":"CLOUD_SPACE_SETUP_RETRY_SCHEDULED","setup":{"status":"RETRY_SCHEDULED","attempts":1,"nextRetryAt":"2026-10-18T12:01:00Z","errorCode":"CLOUD_SPACE_CONNECTION_FAILED","retryable":true}}""",
            Headers.headersOf("Retry-After", "60")
        )

        val failure = assertIs<CloudSpaceInitializationException>(when (operation) {
            "status" -> repository.getCloudSpaceInitializationState().exceptionOrNull()
            "init" -> repository.initializeCloudSpace().exceptionOrNull()
            "credentials" -> repository.getCloudSpaceCredentials().exceptionOrNull()
            else -> error("Unexpected test operation: $operation")
        })

        assertEquals(503, failure.httpStatus)
        assertEquals(503, assertIs<HttpException>(failure.cause).code())
        assertEquals(CloudSpaceInitializationStatus.RETRY_SCHEDULED, failure.state.status)
        assertEquals("CLOUD_SPACE_CONNECTION_FAILED", failure.state.errorCode)
        assertEquals(Instant.parse("2026-10-18T12:01:00Z"), failure.state.nextRetryAt)
        assertTrue(failure.state.retryable)
        assertFalse(failure.state.manualRetryAllowed)
        assertEquals(1, requests.size)
    }

    @Test
    fun exhaustedInitializationAllowsManualRetryEvenWhenRetryableIsFalse() = runTest {
        responses += StubResponse(
            409,
            """{"setup":{"status":"FAILED","attempts":5,"nextRetryAt":null,"errorCode":"CLOUD_SPACE_RETRY_LIMIT_REACHED","retryable":false}}"""
        )

        val failure = assertIs<CloudSpaceInitializationException>(
            repository.getCloudSpaceCredentials().exceptionOrNull()
        )

        assertEquals(409, failure.httpStatus)
        assertFalse(failure.state.retryable)
        assertTrue(failure.state.manualRetryAllowed)
    }

    @ParameterizedTest
    @ValueSource(strings = ["CLOUD_SPACE_INVALID_CONFIGURATION", "CLOUD_SPACE_CREDENTIALS_MISSING"])
    fun rejectedRetryPreservesPermanentFailureAndDoesNotRepeatPost(errorCode: String) = runTest {
        responses += StubResponse(
            409,
            """{"setup":{"status":"FAILED","attempts":1,"nextRetryAt":null,"errorCode":"${errorCode}","retryable":false}}"""
        )

        val failure = assertIs<CloudSpaceInitializationException>(
            repository.initializeCloudSpace().exceptionOrNull()
        )

        assertEquals(errorCode, failure.state.errorCode)
        assertFalse(failure.state.manualRetryAllowed)
        assertEquals(1, requests.size)
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "{}",
        "null",
        "",
        "{",
        """{"status":"UNKNOWN","attempts":0,"retryable":true}""",
        """{"status":"READY","retryable":false}""",
        """{"status":"READY","attempts":-1,"retryable":false}""",
        """{"status":"READY","attempts":1}""",
        """{"status":"RETRY_SCHEDULED","attempts":1,"nextRetryAt":"invalid","retryable":true}"""
    ])
    fun malformedInitializationResponsesAreAnErrorInsteadOfAssumingReadiness(body: String) = runTest {
        responses += StubResponse(200, body)
        responses += StubResponse(202, body)

        assertIs<InvalidCloudSpaceInitializationStatusResponseException>(
            repository.getCloudSpaceInitializationState().exceptionOrNull()
        )
        assertIs<InvalidCloudSpaceInitializationStatusResponseException>(
            repository.initializeCloudSpace().exceptionOrNull()
        )
        assertEquals(2, requests.size)
    }

    @ParameterizedTest
    @ValueSource(ints = [204, 205])
    fun bodylessStatusAndInitializationResponsesAreInvalid(code: Int) = runTest {
        responses += StubResponse(code, "")
        responses += StubResponse(code, "")

        assertIs<InvalidCloudSpaceInitializationStatusResponseException>(
            repository.getCloudSpaceInitializationState().exceptionOrNull()
        )
        assertIs<InvalidCloudSpaceInitializationStatusResponseException>(
            repository.initializeCloudSpace().exceptionOrNull()
        )
        assertEquals(2, requests.size)
    }

    @ParameterizedTest
    @ValueSource(ints = [409, 503])
    fun malformedNestedSetupIsReturnedAsAnInvalidResponse(code: Int) = runTest {
        responses += StubResponse(code, """{"setup":{"status":"FAILED"}}""")

        assertIs<InvalidCloudSpaceInitializationStatusResponseException>(
            repository.initializeCloudSpace().exceptionOrNull()
        )
        assertEquals(1, requests.size)
    }

    @Test
    fun invalidRetryTimePreservesTheParsingCause() = runTest {
        responses += StubResponse(
            200,
            """{"status":"RETRY_SCHEDULED","attempts":1,"nextRetryAt":"invalid","retryable":true}"""
        )

        val failure = assertIs<InvalidCloudSpaceInitializationStatusResponseException>(
            repository.getCloudSpaceInitializationState().exceptionOrNull()
        )

        assertIs<DateTimeParseException>(failure.cause)
    }

    @ParameterizedTest
    @ValueSource(ints = [409, 503])
    fun invalidJsonErrorResponsePreservesTheParsingCause(code: Int) = runTest {
        responses += StubResponse(code, "{")

        val failure = assertIs<InvalidCloudSpaceInitializationStatusResponseException>(
            repository.initializeCloudSpace().exceptionOrNull()
        )

        assertIs<JsonParseException>(failure.cause)
        assertEquals(1, requests.size)
    }

    @Test
    fun unrelatedConflictWithoutSetupKeepsItsHttpError() = runTest {
        responses += StubResponse(409, """{"errorCode":"OTHER_CONFLICT","message":"USER_NOT_FOUND"}""")

        assertIs<NetworkException>(repository.initializeCloudSpace().exceptionOrNull())
        assertEquals(1, requests.size)
    }

    @ParameterizedTest
    @ValueSource(strings = ["status", "init", "credentials"])
    fun missingAccountExpiresSessionForEveryCloudSpaceOperation(operation: String) = runTest {
        responses += StubResponse(409, """{"errorCode":"USER_NOT_FOUND"}""")

        val failure = when (operation) {
            "status" -> repository.getCloudSpaceInitializationState().exceptionOrNull()
            "init" -> repository.initializeCloudSpace().exceptionOrNull()
            "credentials" -> repository.getCloudSpaceCredentials().exceptionOrNull()
            else -> error("Unexpected test operation: $operation")
        }

        assertIs<SessionExpiredException>(failure)
        assertEquals(1, requests.size)
    }

    @ParameterizedTest
    @ValueSource(strings = ["status", "credentials"])
    fun missingAccountStopsWaitingAndEmitsSessionExpired(operation: String) = runTest {
        if (operation == "credentials") {
            responses += StubResponse(200, """{"status":"READY","attempts":1,"retryable":false}""")
        }
        responses += StubResponse(409, """{"errorCode":"USER_NOT_FOUND"}""")

        // Retrofit callbacks use real threads, so the controller timeout must use real time too.
        val progress = CloudSpaceInitializationController(repository)
            .waitForCloudSpaceCredentials().flowOn(Dispatchers.IO).toList()

        val expected = if (operation == "credentials") {
            listOf(CloudSpaceInitializationProgress.FetchingCredentials, CloudSpaceInitializationProgress.SessionExpired)
        } else {
            listOf(CloudSpaceInitializationProgress.SessionExpired)
        }
        assertEquals(expected, progress)
        assertEquals(if (operation == "credentials") 2 else 1, requests.size)
        assertTrue(requests.all { it.method == "GET" })
    }

    @ParameterizedTest
    @ValueSource(ints = [401, 403])
    fun authenticationFailureStaysDistinctFromStorageInitialization(code: Int) = runTest {
        responses += StubResponse(code, "{}")

        assertIs<UnauthorizedException>(repository.getCloudSpaceInitializationState().exceptionOrNull())
    }

    @Test
    fun missingStatusEndpointReturnsNotFoundError() = runTest {
        responses += StubResponse(404, "{}")

        assertIs<NotFoundException>(repository.getCloudSpaceInitializationState().exceptionOrNull())
        assertEquals(1, requests.size)
    }

    @Test
    fun readyCredentialsKeepExistingQrAndNumericPinBehavior() = runTest {
        responses += StubResponse(200, """{"qrEncrypted":"local-test-qr","pin":"123456"}""")

        val credentials = repository.getCloudSpaceCredentials().getOrThrow()

        assertEquals("local-test-qr", credentials.qrEncrypted)
        assertEquals(123456, credentials.pin)
        assertEquals("/api/me/cloud-space/credentials", requests.single().url.encodedPath)
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "{",
        "",
        "{}",
        """{"qrEncrypted":"local-test-qr"}""",
        """{"qrEncrypted":"local-test-qr","pin":null}""",
        """{"pin":123456}""",
        """{"qrEncrypted":null,"pin":123456}""",
        """{"qrEncrypted":" ","pin":123456}""",
        """{"qrEncrypted":"local-test-qr","pin":"invalid"}"""
    ])
    fun malformedCredentialsAreNotClassifiedAsInvalidStatusResponses(body: String) = runTest {
        responses += StubResponse(200, body)

        val failure = assertIs<InvalidCloudSpaceResponseException>(
            repository.getCloudSpaceCredentials().exceptionOrNull()
        )

        assertFalse(failure is InvalidCloudSpaceInitializationStatusResponseException)
    }

    @Test
    fun retryTimeoutIsReturnedWithoutRepeatingThePost() = runTest {
        responses += StubResponse(503, "", failure = SocketTimeoutException("Local test timeout"))

        assertIs<NetworkException>(repository.initializeCloudSpace().exceptionOrNull())
        assertEquals(1, requests.size)
        assertEquals("POST", requests.single().method)
    }

    @Test
    fun cancellationIsRethrownInsteadOfBecomingAnErrorResult() = runTest {
        val service = mockk<CloudSpaceService>()
        coEvery { service.getCloudSpaceInitializationState() } throws CancellationException()
        coEvery { service.initializeCloudSpace() } throws CancellationException()
        coEvery { service.getCloudSpaceCredentials() } throws CancellationException()
        val cancelledRepository = CloudSpaceRepository(service)

        assertFailsWith<CancellationException> { cancelledRepository.getCloudSpaceInitializationState() }
        assertFailsWith<CancellationException> { cancelledRepository.initializeCloudSpace() }
        assertFailsWith<CancellationException> { cancelledRepository.getCloudSpaceCredentials() }
    }
}
