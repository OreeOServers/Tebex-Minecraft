package io.tebex.sdk.analytics;

import com.google.gson.JsonObject;
import com.intellectualsites.http.HttpClient;
import com.intellectualsites.http.HttpResponse;
import io.tebex.sdk.analytics.exception.ServerNotFoundException;
import io.tebex.sdk.analytics.exception.ServerNotSetupException;
import io.tebex.sdk.analytics.obj.AnalysePlayer;
import io.tebex.sdk.analytics.obj.Event;
import io.tebex.sdk.analytics.response.PluginInformation;
import io.tebex.sdk.analytics.response.ServerInformation;
import io.tebex.sdk.exception.NotFoundException;
import io.tebex.sdk.exception.RateLimitException;
import io.tebex.sdk.exception.ResponseException;
import io.tebex.sdk.platform.Platform;
import io.tebex.sdk.platform.PlatformType;
import io.tebex.sdk.platform.config.ServerPlatformConfig;
import io.tebex.sdk.util.HttpClientBuilder;
import io.tebex.sdk.util.TrustAllCertificates;

import javax.net.ssl.HttpsURLConnection;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static io.tebex.sdk.util.HttpClientBuilder.GSON;

/**
 * The main SDK class for interacting with the Analytics API.
 */
public class SDK {
    private final HttpClient HTTP_CLIENT;

    private final int API_VERSION = 1;
    private final String SECRET_KEY_HEADER = "X-Secret-Key";

    private final Platform platform;
    private String secretKey;

    /**
     * Constructs a new SDK instance with the specified platform and server token.
     *
     * @param platform    The platform on which the SDK is running.
     * @param secretKey The server token for authentication.
     */
    public SDK(Platform platform, String secretKey) {
        this.platform = platform;
        this.secretKey = secretKey;

        if(System.getProperty("tebex.analytics.url") != null) {
            platform.warning("Setting API URL to " + System.getProperty("tebex.analytics.url"));
        }

        String url = System.getProperty("tebex.analytics.url", String.format("https://analytics.tebex.io/api/v%d", API_VERSION));
        HttpClientBuilder httpClientBuilder = new HttpClientBuilder(url);
        this.HTTP_CLIENT = httpClientBuilder.build();

        // trust all certificates if the url contains ".test"
        if (url.contains(".test")) {
            try {
                TrustAllCertificates.trustAllHttpsCertificates();
                HttpsURLConnection.setDefaultHostnameVerifier(new TrustAllCertificates());
            } catch (Exception e) {
                // ignore
            }
        }
    }

    private void handleResponseErrors(HttpResponse req) {
        if (req.getStatusCode() == 404) {
            throw new CompletionException(new ServerNotFoundException());
        } else if (req.getStatusCode() == 429) {
            throw new CompletionException(new RateLimitException("You are being rate limited."));
        } else if (req.getStatusCode() != 200) {
            JsonObject body = req.getResponseEntity(JsonObject.class);

            if(body.has("message")) {
                throw new CompletionException(new ResponseException("Unexpected status code " + req.getStatusCode() + " (" + body.get("message").getAsString() + ")"));
            }

            if(platform.getPlatformConfig().isVerbose()) {
                platform.warning("Received response: " + new String(req.getRawResponse(), StandardCharsets.UTF_8));
            }

            throw new CompletionException(new ResponseException("Unexpected status code (" + req.getStatusCode() + ")"));
        }
    }

    /**
     * Retrieves the latest plugin information.
     *
     * @param platformType The platform type for which to retrieve the plugin information.
     * @return A CompletableFuture that contains the PluginInformation object.
     */
    public CompletableFuture<PluginInformation> getPluginVersion(PlatformType platformType) {
        return CompletableFuture.supplyAsync(() -> {
            final HttpResponse response = this.HTTP_CLIENT.get("/plugin")
                    .withHeader(SECRET_KEY_HEADER, secretKey)
                    .withHeader("User-Agent", "Tebex-SDK")
                    .withHeader("Content-Type", "application/json")
                    .withHeader("Accept", "application/json")
                    .onStatus(200, req -> {})
                    .onRemaining(this::handleResponseErrors)
                    .execute();

            if(response == null) {
                throw new CompletionException(new IOException("Failed to retrieve plugin information"));
            }

            JsonObject body = response.getResponseEntity(JsonObject.class);
            JsonObject versionData = body.get("version").getAsJsonObject();
            JsonObject assetData = body.get("assets").getAsJsonObject();

            return new PluginInformation(
                    versionData.get("name").getAsString(),
                    versionData.get("incremental").getAsInt(),
                    assetData.get(platformType.name().toLowerCase()).getAsString()
            );
        });
    }

    /**
     * Retrieves information about the server.
     *
     * @return A CompletableFuture that contains the ServerInformation object.
     */
    public CompletableFuture<ServerInformation> getServerInformation() {
        if (getSecretKey() == null) {
            CompletableFuture<ServerInformation> future = new CompletableFuture<>();
            future.completeExceptionally(new ServerNotSetupException());
            return future;
        }

        return CompletableFuture.supplyAsync(() -> {
            final HttpResponse response = this.HTTP_CLIENT.get("/server")
                    .withHeader(SECRET_KEY_HEADER, secretKey)
                    .withHeader("User-Agent", "Tebex-SDK")
                    .withHeader("Content-Type", "application/json")
                    .withHeader("Accept", "application/json")
                    .onStatus(200, req -> {})
                    .onRemaining(this::handleResponseErrors)
                    .execute();

            if(response == null) {
                throw new CompletionException(new IOException("Failed to retrieve server information"));
            }

            JsonObject body = response.getResponseEntity(JsonObject.class);
            return GSON.fromJson(body.get("data"), ServerInformation.class);
        });
    }

    /**
     * Sends a player session to the Analytics API for tracking.
     *
     * @param player The AnalysePlayer object representing the player to be tracked.
     * @return A CompletableFuture that indicates whether the operation was successful.
     */
    public CompletableFuture<Boolean> trackPlayerSession(AnalysePlayer player) {
        if (getSecretKey() == null) {
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            future.completeExceptionally(new NotFoundException());
            return future;
        }

        if (! platform.isAnalyticsSetup()) {
            platform.debug("Skipped tracking player session for " + player.getName() + " as Analytics isn't setup.");
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            future.complete(false);
            return future;
        }

        if(platform.isPlayerExcluded(player.getUniqueId())) {
            platform.debug("Skipped tracking player session for " + player.getName() + " as they are excluded.");
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            future.complete(false);
            return future;
        }

        player.logout();

        platform.debug("Sending payload: " + GSON.toJson(player));

        platform.debug("Tracking player session for " + player.getName() + "..");
        platform.debug(" - UUID: " + player.getUniqueId());
        platform.debug(" - Type: " + player.getType());
        platform.debug(" - Played for: " + player.getDurationInSeconds() + "s");
        platform.debug(" - IP: " + player.getIpAddress());
        platform.debug(" - Joined at: " + player.getJoinedAt());
        platform.debug(" - First joined at: " + player.getFirstJoinedAt());

        return CompletableFuture.supplyAsync(() -> {
            final HttpResponse response = this.HTTP_CLIENT.get("/server/sessions")
                    .withHeader(SECRET_KEY_HEADER, secretKey)
                    .withHeader("User-Agent", "Tebex-SDK")
                    .withHeader("Content-Type", "application/json")
                    .withHeader("Accept", "application/json")
                    .withInput(() -> GSON.toJson(player))
                    .onStatus(200, req -> {})
                    .onRemaining(this::handleResponseErrors)
                    .execute();

            if(response == null) {
                throw new CompletionException(new IOException("Failed to track player session"));
            }

            JsonObject body = response.getResponseEntity(JsonObject.class);

            return body.get("success").getAsBoolean();
        });
    }

    /**
     * Sends a setup completion request to the Analytics API.
     *
     * @return A CompletableFuture that indicates whether the operation was successful.
     */
    public CompletableFuture<Boolean> completeServerSetup() {
        if (getSecretKey() == null) {
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            future.completeExceptionally(new ServerNotSetupException());
            return future;
        }

        return CompletableFuture.supplyAsync(() -> {
            final HttpResponse response = this.HTTP_CLIENT.get("/server/setup")
                    .withHeader(SECRET_KEY_HEADER, secretKey)
                    .withHeader("User-Agent", "Tebex-SDK")
                    .withHeader("Content-Type", "application/json")
                    .withHeader("Accept", "application/json")
                    .onStatus(200, req -> {})
                    .onRemaining(this::handleResponseErrors)
                    .execute();

            if(response == null) {
                throw new CompletionException(new IOException("Failed to complete server setup"));
            }

            JsonObject body = response.getResponseEntity(JsonObject.class);
            return body.get("success").getAsBoolean();
        });
    }

    /**
     * Sends the current player count to the Analytics API in the form of a heartbeat.
     *
     * @param playerCount The number of players currently online.
     * @return A CompletableFuture that indicates whether the operation was successful.
     */
    public CompletableFuture<Boolean> trackHeartbeat(int playerCount) {
        if (getSecretKey() == null) {
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            future.completeExceptionally(new ServerNotSetupException());
            return future;
        }

        JsonObject body = new JsonObject();
        body.addProperty("players", playerCount);

        return CompletableFuture.supplyAsync(() -> {
            final HttpResponse response = this.HTTP_CLIENT.post("/server/heartbeat")
                    .withHeader(SECRET_KEY_HEADER, secretKey)
                    .withHeader("User-Agent", "Tebex-SDK")
                    .withHeader("Content-Type", "application/json")
                    .withHeader("Accept", "application/json")
                    .withInput(() -> GSON.toJson(body))
                    .onStatus(200, req -> {})
                    .onRemaining(this::handleResponseErrors)
                    .execute();

            if(response == null) {
                throw new CompletionException(new IOException("Failed to track heartbeat"));
            }

            JsonObject responseBody = response.getResponseEntity(JsonObject.class);

            return responseBody.get("success").getAsBoolean();
        });
    }

    /**
     * Sends the current server telemetry to the Analytics API.
     *
     * @return A CompletableFuture that indicates whether the operation was successful.
     */
    public CompletableFuture<Boolean> sendTelemetry() {
        if (getSecretKey() == null) {
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            future.completeExceptionally(new ServerNotSetupException());
            return future;
        }

        return CompletableFuture.supplyAsync(() -> {
            final HttpResponse response = this.HTTP_CLIENT.post("/server/telemetry")
                    .withHeader(SECRET_KEY_HEADER, secretKey)
                    .withHeader("User-Agent", "Tebex-SDK")
                    .withHeader("Content-Type", "application/json")
                    .withHeader("Accept", "application/json")
                    .withInput(() -> GSON.toJson(platform.getTelemetry()))
                    .onStatus(200, req -> {})
                    .onRemaining(this::handleResponseErrors)
                    .execute();

            if(response == null) {
                throw new CompletionException(new IOException("Failed to send telemetry"));
            }

            JsonObject responseBody = response.getResponseEntity(JsonObject.class);
            return responseBody.get("success").getAsBoolean();
        });
    }

    /**
     * Get the country code of a specific IP address.
     *
     * @param ip The IP address
     * @return CompletableFuture containing the country code
     * @deprecated This method is deprecated and may be removed in future versions
     */
    @Deprecated
    public CompletableFuture<String> getCountryFromIp(String ip) {
        if (getSecretKey() == null) {
            CompletableFuture<String> future = new CompletableFuture<>();
            future.completeExceptionally(new ServerNotSetupException());
            return future;
        }

        return CompletableFuture.supplyAsync(() -> {
            final HttpResponse response = this.HTTP_CLIENT.get("/ip/" + ip)
                    .withHeader(SECRET_KEY_HEADER, secretKey)
                    .withHeader("User-Agent", "Tebex-SDK")
                    .withHeader("Content-Type", "application/json")
                    .withHeader("Accept", "application/json")
                    .onStatus(200, req -> {})
                    .onRemaining(this::handleResponseErrors)
                    .execute();

            if(response == null) {
                throw new CompletionException(new IOException("Failed to retrieve country from IP"));
            }

            JsonObject jsonObject = response.getResponseEntity(JsonObject.class);

            return jsonObject.get("success").getAsBoolean() ? jsonObject.get("country_code").getAsString() : null;
        });
    }

    public CompletableFuture<Boolean> sendEvents(List<Event> events) {
        if (getSecretKey() == null) {
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            future.completeExceptionally(new NotFoundException());
            return future;
        }

        platform.debug("Sending events: " + GSON.toJson(events));

        return CompletableFuture.supplyAsync(() -> {
            final HttpResponse response = this.HTTP_CLIENT.post("/events")
                    .withHeader(SECRET_KEY_HEADER, secretKey)
                    .withHeader("User-Agent", "Tebex-SDK")
                    .withHeader("Content-Type", "application/json")
                    .withHeader("Accept", "application/json")
                    .withInput(() -> GSON.toJson(events))
                    .onStatus(204, req -> {})
                    .onRemaining(this::handleResponseErrors)
                    .execute();

            if (response == null) {
                throw new CompletionException(new IOException("Failed to send events"));
            }

            return true;
        });
    }

    /**
     * Get the server token associated with this SDK instance.
     *
     * @return The server token as a String
     */
    public String getSecretKey() {
        return secretKey;
    }

    /**
     * Set the server token for this SDK instance.
     *
     * @param serverToken The server token as a String
     */
    public void setSecretKey(String serverToken) {
        this.secretKey = serverToken;
    }
}