package com.graphenelab.communication.crypto;

import kotlin.Pair;
import okhttp3.*;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.graphenelab.communication.crypto.Command.getCommandName;
import static com.graphenelab.communication.crypto.ConversionUtils.base64ToBuffer;
import static com.graphenelab.communication.crypto.ConversionUtils.bufferToString;
import static com.graphenelab.communication.crypto.ConversionUtils.int32ToBuffer;
import static com.graphenelab.communication.crypto.ConversionUtils.joinBuffers;
import static com.graphenelab.communication.crypto.CryptoUtils.alertBox;
import static com.graphenelab.communication.crypto.CryptoUtils.splitData;
import static com.graphenelab.communication.crypto.FileUploader.handleServerErrorResponse;
import static com.graphenelab.communication.crypto.FileUploader.handleServerUploadResponse;
import static com.graphenelab.communication.crypto.QrCodeHandler.setClient;
import static com.graphenelab.communication.crypto.encryption.AesEncryption.decryptData;
import static com.graphenelab.communication.crypto.encryption.AesEncryption.encryptData;
import static com.graphenelab.communication.crypto.encryption.RsaEncryption.createRsaPublicKey;
import static com.graphenelab.communication.crypto.encryption.XorEncryption.decryptXorAB;

import com.graphenelab.communication.crypto.encryption.RsaEncryption;

public class RequestManager {

    private static final List<Pair<Integer, byte[]>> spooler = new ArrayList<>();
    private static final AtomicInteger concurrentRequest = new AtomicInteger(0);
    private static final int maxConcurrentRequest = 3;
    private static final boolean DEBUG_CONCURRENCY_LOGS = true;
    private static final OkHttpClient client = new OkHttpClient();
    private static ExecutorService executor = Executors.newCachedThreadPool();
    private static final Set<Call> activeCalls = ConcurrentHashMap.newKeySet();//    public static String proxy = "http://195.20.235.5:5050";
    private static PairingAuthCallback pairingAuthCallback;

    public static String proxy = "http://proxy.tc0.it:5050";

    public static void updateProxyFromEntryPoint(String entryPoint) {
        if (entryPoint == null || entryPoint.trim().isEmpty()) {
            return;
        }
        String trimmed = entryPoint.trim();
        if(trimmed.equals("proxy")){
            trimmed = "proxy.tc0.it";
        }
        boolean hasScheme = trimmed.startsWith("http://") || trimmed.startsWith("https://");
        String normalized = hasScheme ? trimmed : "http://" + trimmed;

        HttpUrl parsed = HttpUrl.parse(normalized);
        if (parsed == null || parsed.host().isEmpty()) {
            return;
        }

        String withoutScheme = hasScheme ? trimmed.substring(trimmed.indexOf("://") + 3) : trimmed;
        String hostPortPart = withoutScheme.split("/", 2)[0];
        boolean portSpecified = hostPortPart.contains(":");

        String scheme = parsed.scheme();
        String host = parsed.host();
        int port = portSpecified ? parsed.port() : 5050;

        proxy = scheme + "://" + host + ":" + port;
        System.out.println("Proxy updated from entry point: " + proxy);
    }

    // Session persistence callback
    public static SessionPersistenceCallback persistenceCallback;

    public static void setSessionPersistenceCallback(SessionPersistenceCallback callback) {
        persistenceCallback = callback;
    }

    public static synchronized void setPairingAuthCallback(PairingAuthCallback callback) {
        pairingAuthCallback = callback;
    }

    public static synchronized void clearPairingAuthCallback() {
        pairingAuthCallback = null;
    }

    private static synchronized PairingAuthCallback consumePairingAuthCallback() {
        PairingAuthCallback callback = pairingAuthCallback;
        pairingAuthCallback = null; // this will be null on the next call since pairing is done only 1 time. One-shot callback (or one-time listener) pattern
        return callback;
    }

    public static synchronized void enqueueRequest(Integer commandId, byte[] data) {
        spooler.add(new Pair<>(commandId, data));
        logConcurrencyState("enqueueRequest", commandId);
        tryStartNext();
    }

    private static synchronized void tryStartNext() {
        recreateExecutor();

        if (concurrentRequest.get() < maxConcurrentRequest) {
            Pair<Integer, byte[]> nextRequest = null;
            if (!spooler.isEmpty()) {
                nextRequest = spooler.remove(spooler.size() - 1);
            }
            if (nextRequest != null) {
                concurrentRequest.incrementAndGet();
                logConcurrencyState("dispatch", nextRequest.getFirst());
                executeQueuedRequest(nextRequest.getFirst(), nextRequest.getSecond());
            }
        }
    }

    /**
     * Queues a request for dispatch. Keeping this as the only public entry point makes sure the
     * concurrency counter is incremented before the request can complete and decrement it.
     */
    public static void executeRequest(Integer commandId, byte[] data) {
        enqueueRequest(commandId, data);
    }

    private static void executeQueuedRequest(Integer commandId, byte[] data) {
        if (commandId == null) {
            alertBox("Command does not exist");
            requestDone("null-command");
            return;
        }
        byte[] requestData = data != null ? data : new byte[0];
        String proxyUrl = proxy + "/data";
        HttpUrl baseUrl = HttpUrl.parse(proxyUrl);
        if (baseUrl == null) {
            alertBox("Invalid proxy URL");
            requestDone("invalid-proxy-url");
            return;
        }

        var urlBuilder = baseUrl.newBuilder();
        urlBuilder.addQueryParameter("cid", SessionManager.getCurrentSession().getClientId());

        String purpose = null;
        if (commandId == Command.SetClient.getId() || commandId == Command.GetEncryptedQR.getId()) {
            urlBuilder.addQueryParameter("sid", SessionManager.getCurrentSession().getServerId());
            purpose = getCommandName(commandId);
            urlBuilder.addQueryParameter("purpose", purpose);
        }

        HttpUrl url = urlBuilder.build();
        System.out.println("Executing request URL: " + url);
        Request.Builder requestBuilder = new Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36")
                .header("Accept", "*/*")
                .header("Accept-Language", "en-US,en;q=0.9");

        boolean isGet = commandId == Command.GetPushNotifications.getId();
        if (isGet) {
            getRequest(requestBuilder);
            return;
        }

        if (purpose != null) {
            postRequest(requestBuilder, requestData);
            return;
        }

        if (SessionManager.getCurrentSession().getDeviceKey() == null) {
            alertBox("Unregistered user. You need to log in to the server to initialize the encryption.");
            requestDone("missing-device-key");
            return;
        }

        byte[] cmdBuffer = int32ToBuffer(commandId);
        if (requestData.length == 0) {
            requestData = cmdBuffer;
        } else {
            requestData = joinBuffers(cmdBuffer, requestData);
        }

        byte[] finalRequestData = requestData;
        byte[] encrypted;
        try {
            encrypted = encryptData(finalRequestData);
            postRequest(requestBuilder, encrypted);
        } catch (Exception e) {
            requestDone("execute-exception");
            throw new RuntimeException(e);
        }
    }

    private static void getRequest(Request.Builder requestBuilder) {
        getEnqueue(requestBuilder.build());
    }

    private static void postRequest(Request.Builder requestBuilder, byte[] data) {
        MediaType mediaType = MediaType.parse("application/octet-stream");
        RequestBody body = RequestBody.create(data, mediaType);
        Request request = requestBuilder.post(body).build();
        getEnqueue(request);
    }

    private static void getEnqueue(Request request) {
        var call = client.newCall(request);
        activeCalls.add(call);
        call.enqueue(new Callback() {

            @Override
            public void onFailure(@NotNull Call call, @NotNull IOException e) {
                activeCalls.remove(call);
                alertBox("HTTP Request error: " + e.getMessage());
                notifyPairingAuthError("Network error during pairing/authentication. Please try again.");
                requestDone("http-failure");
            }

            @Override
            public void onResponse(@NotNull Call call, @NotNull Response response) {
                activeCalls.remove(call);
                requestDone("http-response-" + response.code());
                int code = response.code();
                switch (code) {
                    case 404:
                        alertBox("Status 404: Cloud not found by SID. No cloud with this User ID has registered in the proxy.");
                        notifyPairingAuthError("Cloud connection not found. Please recheck credentials and try again.");
                        break;
                    case 503:
                        alertBox("Status 503: Max request concurrent limit reached.");
                        notifyPairingAuthError("Server is busy. Please try again.");
                        break;
                    case 421:
                        alertBox("Status 421: The cloud is not logged into the proxy. Please restart it.");
                        notifyPairingAuthError("Cloud is offline. Please try again later.");
                        break;
                    case 200:
                        handleSuccessfulResponse(response);
                        break;
                    default:
                        alertBox("HTTP error code: " + code);
                        notifyPairingAuthError("Pairing failed with server error " + code + ".");
                        break;
                }
            }
        });
    }

    private static void handleSuccessfulResponse(Response response) {
        try (ResponseBody body = response.body()) {
            if (body == null) {
                alertBox("Response body is null.");
                return;
            }

            String responseText = body.string();
            if (responseText.isEmpty()) {
                alertBox("Response body is empty.");
                return;
            }

            executor.submit(() -> {
                try {
                    handleResponse(responseText);
                } catch (Exception e) {
                    e.printStackTrace();
                    alertBox("Error processing response: " + e.getMessage());
                }
            });
        } catch (IOException e) {
            e.printStackTrace();
            alertBox("Error reading response body: " + e.getMessage());
        }
    }

    private static void handleResponse(String responseText) {
        try {
            Session session = SessionManager.getCurrentSession();
            if (session == null) {
                alertBox("No active session found.");
                return;
            }

            if (session.getQRkey() != null) {
                getEncryptedQR(responseText);
            } else if (session.getDeviceKey() != null || session.getEncryptionType() != null) {
                var decrypted = decryptData(base64ToBuffer(responseText));
                onResponse(decrypted);
            } else {
                var response = base64ToBuffer(responseText);
                var decrypted = RsaEncryption.decryptData(SessionManager.getCurrentSession().getPrivateKey(), response);
                onResponse(decrypted);
            }

        } catch (Exception e) {
            e.printStackTrace();
            alertBox("Error handling response: " + e.getMessage());
            notifyPairingAuthError("Failed to process pairing response. Please try again.");
        }
    }

    private static synchronized void requestDone() {
        requestDone("unspecified");
    }

    private static synchronized void requestDone(String reason) {
        int current = concurrentRequest.get();
        if (current == 0) {
            System.out.println("[RequestManager][Concurrency] WARNING: request completed without an active queue slot");
        } else {
            current = concurrentRequest.decrementAndGet();
        }
        logConcurrencyState("requestDone:" + reason, null);
        tryStartNext();
    }

    private static synchronized void logConcurrencyState(String event, Integer commandId) {
        if (!DEBUG_CONCURRENCY_LOGS) return;
        String command = commandId == null ? "-" : getCommandName(commandId) + "(" + commandId + ")";
        System.out.println(
                "[RequestManager][Concurrency] " + event +
                        " cmd=" + command +
                        " concurrent=" + concurrentRequest.get() + "/" + maxConcurrentRequest +
                        " queue=" + spooler.size() +
                        " activeCalls=" + activeCalls.size()
        );
    }

    public static void getEncryptedQR(String encryptedDataB64) throws Exception {
        byte[] encryptedData = base64ToBuffer(encryptedDataB64);
        byte[] decryptedData = decryptXorAB(SessionManager.getCurrentSession().getQRkey(), encryptedData);
        SessionManager.getCurrentSession().setQRkey(null);

        int offset = 0;
        int type = decryptedData[offset] & 0xFF;
        if (type != 2) {
            throw new RuntimeException("QR code format not supported!");
        }
        offset += 1;

        int mSize = 2048 / 8;
        byte[] modulus = Arrays.copyOfRange(decryptedData, offset, offset + mSize);
        offset += mSize;
        byte[] exponent = Arrays.copyOfRange(decryptedData, offset, offset + 3);
        PublicKey rsaPubKey = createRsaPublicKey(modulus, exponent);
        setClient(rsaPubKey);
    }

    public static void onResponse(byte[] binary) {
        ByteBuffer buffer = ByteBuffer.wrap(binary);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        int commandId = buffer.getInt();
        String command = getCommandName(commandId);

        System.out.println("Command ID: " + commandId);
        System.out.println("Command: " + command);
        if (Command.Authentication.getId() == commandId) {
            System.out.println("Authentication success!");
            notifyPairingAuthSuccess();
            AuthSuccess();
            return;
        }

        byte[] data = Arrays.copyOfRange(binary, 4, binary.length);
        var params = splitData(data);
        System.out.println("Params: " + params);
        if (Command.Error.getId() == commandId) {
            String errorMessage = decodeErrorMessage(params);
            handleServerErrorResponse(errorMessage);
            notifyPairingAuthError(errorMessage);
            alertBox(errorMessage);
            return;
        }

        if(Command.SetFile.getId() == commandId){
            handleServerUploadResponse(params);
        }
        if(Command.Pair.getId() == commandId){
            Pairing.Pair(params);
        }
    }

    private static void notifyPairingAuthSuccess() {
        PairingAuthCallback callback = consumePairingAuthCallback();
        if (callback == null) {
            return;
        }
        try {
            callback.onAuthenticationSuccess();
        } catch (Exception ex) {
            System.err.println("Pairing success callback failed: " + ex.getMessage());
        }
    }

    private static void notifyPairingAuthError(String message) {
        PairingAuthCallback callback = consumePairingAuthCallback();
        if (callback == null) {
            return;
        }
        try {
            callback.onAuthenticationError(message);
        } catch (Exception ex) {
            System.err.println("Pairing error callback failed: " + ex.getMessage());
        }
    }

    private static String decodeErrorMessage(List<byte[]> params) {
        String fallback = "Authentication failed. Please recheck your credentials.";
        if (params == null || params.isEmpty()) {
            return fallback;
        }

        byte[] firstParam = params.get(0);
        if (firstParam == null || firstParam.length == 0) {
            return fallback;
        }

        String decoded = bufferToString(firstParam).trim();
        if (decoded.isEmpty()) {
            return fallback;
        }

        return decoded;
    }

    private static void AuthSuccess() {
        System.out.println("Authentication success!, saving session...");
        saveSessionIfCallbackExists();
    }
    
    private static void saveSessionIfCallbackExists() {
        if (persistenceCallback != null) {
            try {
                persistenceCallback.saveSession(SessionManager.getCurrentSession());
                System.out.println("Session saved successfully!");
            } catch (Exception e) {
                persistenceCallback.onSaveError("Failed to save session: " + e.getMessage());
            }
        }
    }


    public static synchronized void cancelAllPendingRequests() {
        // Clear all pending requests from the spooler
        spooler.clear();
        clearPairingAuthCallback();

        // Interrupt running requests by shutting down and recreating the executor
        try {
            // Attempt graceful shutdown first
            executor.shutdown();

            // Wait up to 2 seconds for current tasks to complete
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                // Force shutdown if tasks don't complete gracefully
                List<Runnable> remainingTasks = executor.shutdownNow();
                System.out.println("Forcibly cancelled " + remainingTasks.size() + " pending upload tasks");

                // Wait a bit more for tasks to respond to interruption
                if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                    System.err.println("Some upload tasks did not terminate gracefully");
                }
            }

            System.out.println("All upload requests cancelled successfully; waiting for active calls to finish cancellation");

        } catch (InterruptedException e) {
            // Current thread was interrupted, re-interrupt and force shutdown
            Thread.currentThread().interrupt();
            executor.shutdownNow();
            System.err.println("Cancellation was interrupted, forced shutdown");
        }

        for (Call call : activeCalls) {
            if (!call.isCanceled()) {
                call.cancel();
            }
        }
    }

    private static synchronized void recreateExecutor() {
        if (executor.isShutdown()) {
            executor = Executors.newCachedThreadPool();
        }
    }


}
