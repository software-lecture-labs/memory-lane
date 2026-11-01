package com.memorylane.feature.auth.data;

import static org.junit.Assert.*;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.memorylane.core.network.ApiCaller;
import com.memorylane.core.network.ApiClient;
import com.memorylane.core.network.ApiError;
import com.memorylane.core.network.ApiResult;
import com.memorylane.core.network.Cancellable;
import com.memorylane.core.network.ResultCallback;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/** Contract preparation against local HTTP only; all credentials and tokens are synthetic. */
public class RemoteAuthRepositoryTest {
    private static final String EMAIL = " Test@Example.test ";
    private static final String PASSWORD = "  sahte-parola-123-Türkçe  ";
    private static final String USER = "{\"id\":\"11111111-1111-4111-8111-111111111111\","
            + "\"email\":\"test@example.test\"}";
    private static final String TOKEN = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG";
    private final Gson gson = new Gson();
    private MockWebServer server;
    private AuthRepository repository;

    @Before
    public void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        AuthApi api = ApiClient.create(server.url("/").toString(), gson).create(AuthApi.class);
        repository = new RemoteAuthRepository(api, new ApiCaller(gson));
    }

    @After
    public void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    public void registerSendsUnchangedCredentialsAndReads201User() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(201).setBody(USER));

        ApiResult<UserResponse> result = await(callback -> repository.register(request(), callback));

        assertTrue(result.isSuccess());
        assertNotNull(result.getData());
        assertEquals("11111111-1111-4111-8111-111111111111", result.getData().getId());
        assertEquals("test@example.test", result.getData().getEmail());
        assertRequest("/api/v1/auth/register");
    }

    @Test
    public void loginReads200TokenUserAndUtcExpiryWithOptionalFraction() throws Exception {
        for (String expiry : new String[]{"2026-10-29T09:00:00Z", "2026-10-29T09:00:00.123456Z"}) {
            server.enqueue(new MockResponse().setResponseCode(200).setBody(
                    "{\"accessToken\":\"" + TOKEN + "\",\"tokenType\":\"Bearer\","
                            + "\"expiresAt\":\"" + expiry + "\",\"user\":" + USER + "}"));

            ApiResult<LoginResponse> result = login();

            assertTrue(result.isSuccess());
            assertNotNull(result.getData());
            assertEquals(TOKEN, result.getData().getAccessToken());
            assertEquals("Bearer", result.getData().getTokenType());
            assertEquals(expiry, result.getData().getExpiresAt());
            assertNotNull(result.getData().getUser());
            assertEquals("test@example.test", result.getData().getUser().getEmail());
            assertRequest("/api/v1/auth/login");
        }
    }

    @Test
    public void registerPreserves400And409ContractErrors() throws Exception {
        assertHttpError(400, "INVALID_INPUT", false);
        assertHttpError(409, "EMAIL_IN_USE", false);
    }

    @Test
    public void loginPreserves401And429ContractErrors() throws Exception {
        assertHttpError(401, "INVALID_CREDENTIALS", true);
        // ApiCaller currently discards headers: status/code/message survive, Retry-After does not.
        assertHttpError(429, "RATE_LIMITED", true);
    }

    @Test
    public void malformedSuccessBodiesUseSharedParseError() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(201).setBody("<html>not json</html>"));
        ApiResult<UserResponse> registered = await(callback -> repository.register(request(), callback));
        assertFalse(registered.isSuccess());
        assertNotNull(registered.getError());
        assertEquals(ApiError.Kind.PARSE, registered.getError().getKind());

        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"user\":"));
        ApiResult<LoginResponse> loggedIn = login();
        assertFalse(loggedIn.isSuccess());
        assertNotNull(loggedIn.getError());
        assertEquals(ApiError.Kind.PARSE, loggedIn.getError().getKind());
    }

    @Test
    public void malformedErrorBodyStillPreservesHttpStatus() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(400).setBody("<html>bad request</html>"));
        ApiError error = login().getError();
        assertNotNull(error);
        assertEquals(ApiError.Kind.HTTP, error.getKind());
        assertEquals(400, error.getHttpStatus());
        assertNull(error.getCode());
        assertNull(error.getMessage());
    }

    private void assertHttpError(int status, String code, boolean login) throws Exception {
        server.enqueue(new MockResponse().setResponseCode(status).addHeader("Retry-After", "30")
                .setBody("{\"code\":\"" + code + "\",\"message\":\"Sahte test hatası.\"}"));
        ApiResult<?> result = login ? login()
                : this.<UserResponse>await(callback -> repository.register(request(), callback));
        assertFalse(result.isSuccess());
        assertNull(result.getData());
        ApiError error = result.getError();
        assertNotNull(error);
        assertEquals(ApiError.Kind.HTTP, error.getKind());
        assertEquals(status, error.getHttpStatus());
        assertEquals(code, error.getCode());
        assertEquals("Sahte test hatası.", error.getMessage());
        assertRequest(login ? "/api/v1/auth/login" : "/api/v1/auth/register");
    }

    @Test
    public void registerRejectsNullEmptyAndIncompleteUsers() throws Exception {
        for (String body : new String[]{"null", "{}"}) {
            assertInvalidRegister(body);
        }
        for (String field : new String[]{"id", "email"}) {
            for (String value : new String[]{null, "", "   ", "\u2003"}) {
                JsonObject user = gson.fromJson(USER, JsonObject.class);
                user.addProperty(field, value);
                assertInvalidRegister(gson.toJson(user));
            }
            JsonObject user = gson.fromJson(USER, JsonObject.class);
            user.remove(field);
            assertInvalidRegister(gson.toJson(user));
        }
    }

    @Test
    public void loginRejectsNullEmptyAndMissingRequiredFields() throws Exception {
        assertInvalidLogin("null");
        assertInvalidLogin("{}");
        for (String field : new String[]{"accessToken", "tokenType", "expiresAt", "user"}) {
            JsonObject login = validLoginBody();
            login.remove(field);
            assertInvalidLogin(gson.toJson(login));
            login = validLoginBody();
            login.add(field, com.google.gson.JsonNull.INSTANCE);
            assertInvalidLogin(gson.toJson(login));
        }
        for (String field : new String[]{"accessToken", "tokenType", "expiresAt"}) {
            for (String value : new String[]{"", "   ", "\u2003"}) {
                JsonObject login = validLoginBody();
                login.addProperty(field, value);
                assertInvalidLogin(gson.toJson(login));
            }
        }
    }

    @Test
    public void loginRejectsIncompleteNestedUser() throws Exception {
        JsonObject login = validLoginBody();
        login.add("user", new JsonObject());
        assertInvalidLogin(gson.toJson(login));
        for (String field : new String[]{"id", "email"}) {
            for (String value : new String[]{null, "", "   "}) {
                login = validLoginBody();
                login.getAsJsonObject("user").addProperty(field, value);
                assertInvalidLogin(gson.toJson(login));
            }
            login = validLoginBody();
            login.getAsJsonObject("user").remove(field);
            assertInvalidLogin(gson.toJson(login));
        }
    }

    @Test
    public void loginRejectsWrongTokenTypeAndInvalidExpiry() throws Exception {
        for (String type : new String[]{"Basic", "bearer", " Bearer "}) {
            JsonObject login = validLoginBody();
            login.addProperty("tokenType", type);
            assertInvalidLogin(gson.toJson(login));
        }
        for (String expiry : new String[]{"not-a-date", "2026-02-30T09:00:00Z",
                "2026-10-29", "2026-10-29T09:00:00+00:00"}) {
            JsonObject login = validLoginBody();
            login.addProperty("expiresAt", expiry);
            assertInvalidLogin(gson.toJson(login));
        }
    }

    @Test
    public void cancelledAuthCallDoesNotReportBack() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(201).setBody(USER)
                .setBodyDelay(500, TimeUnit.MILLISECONDS));
        CountDownLatch called = new CountDownLatch(1);
        Cancellable pending = repository.register(request(), result -> called.countDown());
        pending.cancel();
        assertFalse(called.await(1, TimeUnit.SECONDS));
    }

    private JsonObject validLoginBody() {
        JsonObject login = new JsonObject();
        login.addProperty("accessToken", TOKEN);
        login.addProperty("tokenType", "Bearer");
        login.addProperty("expiresAt", "2026-10-29T09:00:00Z");
        login.add("user", gson.fromJson(USER, JsonObject.class));
        return login;
    }

    private void assertInvalidRegister(String body) throws Exception {
        server.enqueue(new MockResponse().setResponseCode(201).setBody(body));
        ApiResult<UserResponse> result = await(callback -> repository.register(request(), callback));
        assertParseFailure(result);
    }

    private void assertInvalidLogin(String body) throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody(body));
        assertParseFailure(login());
    }

    private void assertParseFailure(ApiResult<?> result) {
        // Invalid success payloads must not reach callers as success or partial data.
        assertFalse(result.isSuccess());
        assertNull(result.getData());
        assertNotNull(result.getError());
        assertEquals(ApiError.Kind.PARSE, result.getError().getKind());
    }

    private void assertRequest(String path) throws InterruptedException {
        RecordedRequest sent = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(sent);
        assertEquals("POST", sent.getMethod());
        assertEquals(path, sent.getPath());
        assertNotNull(sent.getHeader("Content-Type"));
        assertTrue(sent.getHeader("Content-Type").startsWith("application/json"));
        assertNull(sent.getHeader("Authorization"));
        JsonObject body = gson.fromJson(sent.getBody().readUtf8(), JsonObject.class);
        assertEquals(2, body.size());
        assertEquals(EMAIL, body.get("email").getAsString());
        assertEquals(PASSWORD, body.get("password").getAsString());
    }

    private AuthRequest request() {
        return new AuthRequest(EMAIL, PASSWORD);
    }

    private ApiResult<LoginResponse> login() throws InterruptedException {
        return await(callback -> repository.login(request(), callback));
    }

    private <T> ApiResult<T> await(StartCall<T> start) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<ApiResult<T>> holder = new AtomicReference<>();
        start.enqueue(result -> {
            holder.set(result);
            done.countDown();
        });
        assertTrue("request did not finish", done.await(20, TimeUnit.SECONDS));
        assertNotNull(holder.get());
        return holder.get();
    }

    private interface StartCall<T> {
        Cancellable enqueue(ResultCallback<T> callback);
    }
}
