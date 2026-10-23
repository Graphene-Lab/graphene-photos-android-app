# Access token refresh

Covers issue #2: the app stored an IAM refresh token but never used it, so every request that
arrived after the access token had expired failed and pushed the user back to sign-in.

## What happens now

Every outgoing request still carries the stored access token. If a request comes back
`401 Unauthorized`, the shared authentication code in
`app/src/main/java/com/graphenelab/photosync/data/network/common/AuthInterceptor.kt` asks the
`TokenRefreshCoordinator` for a usable token and, when one is available, resends the original
request exactly once with it. The caller above the network layer sees only the final reply, so a
recovered token is completely transparent to the repositories, the polling controller, and the
screens.

The pieces:

* `ITokenRefreshClient` — the single operation "exchange this refresh token for a new access
  token", kept behind an interface so nothing below it depends on a concrete HTTP client.
* `IamTokenRefreshClient` — the real implementation. It posts the OAuth 2.0 `refresh_token` grant to
  the same token endpoint and client id that `OAuthManager` uses for sign-in, so the issued token
  belongs to the same session. It deliberately runs on its own bare `OkHttpClient` with no
  authentication interceptor attached: sending a refresh through the interceptor that reacts to
  `401` would make a refresh trigger another refresh and recurse.
* `TokenRefreshCoordinator` — decides what to do about a `401` and owns the stored pair.

## The rules it follows

* **Only `401` counts as an expired token.** Every other status code passes through untouched, so
  `409 USER_NOT_FOUND` still means "account no longer exists, please sign in again" and the
  repositories keep their own meaning for it. A refresh is never attempted for a deleted account.
* **One refresh and one resend per request.** The resend carries a private mark, and a marked
  request is never refreshed again, so a bad token cannot produce a loop.
* **A burst costs a single refresh.** The decision runs under one lock. While the first caller is
  refreshing, the other requests that failed with the same expired token wait, then find the
  replacement already stored and simply resend with it instead of each spending a refresh token.
* **Terminal versus retryable failures stay apart.** A rejection that names the credential
  (`invalid_grant`, `invalid_client`, `unauthorized_client`, or HTTP `400`/`401`/`403`) is terminal:
  the stored session is dropped so the user can sign in again. Everything else — unreachable host,
  read timeout, `408`, `429`, `5xx`, a malformed or empty success body — is retryable and discards
  nothing, so the next attempt can still succeed.
* **A cooldown bounds the attempts.** After a failed refresh nothing new is spent until the cooldown
  has passed, so a burst of failures cannot hammer the identity provider, and the session still
  recovers on its own once the provider answers again.
* **A rotated refresh token is kept.** If the response hands out a new refresh token it replaces the
  spent one; if it does not, the spent one stays in storage rather than being dropped.
* **A late result never overwrites a newer session.** Before writing anything back the coordinator
  checks that the token it spent is still the one in storage. If the user signed out, or another
  account took over, while the refresh was in flight, the result is discarded instead of
  resurrecting or overwriting that session.
* **No stale credential is ever sent.** The interceptor replaces any `Authorization` header already
  on a request, and sends no header at all when there is no token.

## Where refresh is deliberately *not*

`CloudSpaceInitializationController` keeps polling and honouring the backend's `nextRetryAt` exactly
as before and does not know anything about refreshing. Recovery lives only in the transport layer,
which is what the issue asked for. The practical difference: a long initialization wait that used to
outlast the access token and end in `SessionExpired` now quietly refreshes and keeps polling, and a
recovery attempt that fails is reported as a request failure rather than as a lost account.

## Tests

`app/src/test/java/com/graphenelab/photosync/data/network/common/` contains:

* `AuthTokenDoubles.kt` — in-memory stand-ins for the token store and the refresh transport; nothing
  here touches `SharedPreferences`, the Android keystore, or a socket.
* `TokenRefreshCoordinatorTest.kt` — recovery with and without a rotation, absent and blank refresh
  tokens, terminal rejection dropping the session, transient failure keeping it, the cooldown blocking
  and then releasing, a burst of concurrent callers costing one single refresh, and late results after
  sign-out and after an account switch.
* `AuthInterceptorTest.kt` — the real interceptor through the real OkHttp call machinery with a stub
  transport. The last interceptor in the chain never proceeds further, so no socket is ever opened.
  It pins down the token actually sent, the single resend, no third attempt after a second failure,
  pass-through for non-`401` codes including `409 USER_NOT_FOUND`, transport failures, and the
  no-client case.

Run the whole unit test task as usual:

```
./gradlew :app:testDebugUnitTest --tests 'com.graphenelab.photosync.data.network.common.*' --console=plain
```

These tests prove the decisions, the counts, and the headers. They do not prove that a real identity
provider accepts the grant for this realm, that rotation behaves as assumed there, or how long a real
recovery takes on a device; that still needs a controlled integration/device run against a token
fixture, as stated in the issue.
