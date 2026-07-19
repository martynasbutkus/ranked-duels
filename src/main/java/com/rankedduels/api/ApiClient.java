package com.rankedduels.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rankedduels.RankedDuelsConfig;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.config.ConfigManager;
import okhttp3.*;

import com.rankedduels.RankedDuelsPlugin;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.util.Map;

/**
 * Talks to the WordPress REST API (ranked-duels-api plugin).
 * Every request carries the account hash + bearer token issued during linking.
 * All calls are blocking - always invoke from the plugin's executor, never the client thread.
 */
@Slf4j
@Singleton
public class ApiClient
{
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    @Inject private OkHttpClient http;
    @Inject private Gson gson;
    @Inject private Client client;
    @Inject private RankedDuelsConfig config;
    @Inject private ConfigManager configManager;

    /** Set when the server rejects this plugin version (HTTP 426). */
    private volatile boolean outdated;

    /**
     * Stable account identity, cached from the client thread each tick.
     * Jagex accounts: the numeric account hash. Legacy accounts return -1
     * from getAccountHash(), which would make every legacy player collide
     * into one ladder profile - so those get a SHA-256 of their login name.
     */
    private volatile String accountId;

    /** Called from the plugin on the client thread (GameTick). */
    public void updateIdentity(long accountHash, String loginName)
    {
        if (accountHash != -1L)
        {
            accountId = String.valueOf(accountHash);
        }
        else if (loginName != null && !loginName.isEmpty())
        {
            accountId = "legacy_" + sha256("rankedduels:" + loginName.toLowerCase().trim());
        }
    }

    public void clearIdentity()
    {
        accountId = null;
    }

    private static String sha256(String input)
    {
        try
        {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 12; i++) // 24 hex chars is plenty; column is 32
            {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        }
        catch (Exception e)
        {
            return "legacy_unknown";
        }
    }

    /** Per-account token storage: two accounts on one PC no longer collide. */
    private String tokenKey()
    {
        return "apiToken-" + accountId;
    }

    private String storedToken()
    {
        if (accountId == null)
        {
            return null;
        }
        String token = configManager.getConfiguration("rankedduels", tokenKey());
        if (token == null || token.isEmpty())
        {
            // One-time migration from the old single-token config key.
            String legacy = config.apiToken();
            if (legacy != null && !legacy.isEmpty())
            {
                configManager.setConfiguration("rankedduels", tokenKey(), legacy);
                configManager.setConfiguration("rankedduels", "apiToken", "");
                return legacy;
            }
            return null;
        }
        return token;
    }

    private void storeToken(String token)
    {
        if (accountId != null)
        {
            configManager.setConfiguration("rankedduels", tokenKey(), token);
        }
    }

    public boolean isOutdated()
    {
        return outdated;
    }

    public boolean isLinked()
    {
        String token = storedToken();
        return token != null && !token.isEmpty();
    }

    /**
     * Exchange the one-time link code (shown on the user's website profile)
     * for a long-lived API token bound to this account hash.
     */
    /**
     * Zero-friction onboarding: register this game account on the ladder
     * with no website account. Returns true when a token was issued.
     */
    public boolean autoRegister(String displayName)
    {
        if (accountId == null)
        {
            return false;
        }
        JsonObject body = new JsonObject();
        body.addProperty("account_hash", accountId);
        body.addProperty("display_name", displayName == null ? "" : displayName);

        JsonObject resp = post("/register", body, false);
        if (resp != null && resp.has("token"))
        {
            storeToken(resp.get("token").getAsString());
            return true;
        }
        return false;
    }

    public static final int LINK_OK = 1;
    public static final int LINK_FAILED = 0;
    public static final int LINK_INVALID_CODE = -1;

    public int linkAccount(String displayName)
    {
        if (accountId == null)
        {
            return LINK_FAILED;
        }
        JsonObject body = new JsonObject();
        body.addProperty("link_code", config.linkCode());
        body.addProperty("account_hash", accountId);
        body.addProperty("display_name", displayName == null ? "" : displayName);

        Request.Builder rb = new Request.Builder()
            .url(config.apiBaseUrl() + "/link")
            .post(RequestBody.create(JSON, gson.toJson(body)));
        rb.header("X-Plugin-Version", RankedDuelsPlugin.VERSION);
        try (Response response = http.newCall(rb.build()).execute())
        {
            if (response.code() == 404)
            {
                return LINK_INVALID_CODE; // code already used or wrong
            }
            if (!response.isSuccessful() || response.body() == null)
            {
                return LINK_FAILED;
            }
            JsonObject resp = gson.fromJson(response.body().string(), JsonObject.class);
            if (resp != null && resp.has("token"))
            {
                storeToken(resp.get("token").getAsString());
                return LINK_OK;
            }
            return LINK_FAILED;
        }
        catch (Exception e)
        {
            log.debug("link failed", e);
            return LINK_FAILED;
        }
    }

    /**
     * Result of sending a challenge. If the target had already challenged
     * us, the server treats the crossing challenge as an acceptance and
     * returns a live duel id instead ("mutual").
     */
    public static class ChallengeResult
    {
        public long duelId = -1;   // >0 when mutual: the duel is already on
        public double winDelta;
        public double lossDelta;
    }

    public ChallengeResult sendChallenge(String targetName, int world, int worldLocation)
    {
        JsonObject body = new JsonObject();
        body.addProperty("target_name", targetName);
        body.addProperty("world", world);
        body.addProperty("world_location", worldLocation);
        JsonObject resp = post("/challenge", body, true);
        if (resp == null || (!resp.has("challenge_id") && !resp.has("duel_id")))
        {
            return null;
        }
        ChallengeResult result = new ChallengeResult();
        if (resp.has("duel_id"))
        {
            result.duelId = resp.get("duel_id").getAsLong();
        }
        result.winDelta = resp.has("win_delta") ? resp.get("win_delta").getAsDouble() : 0;
        result.lossDelta = resp.has("loss_delta") ? resp.get("loss_delta").getAsDouble() : 0;
        return result;
    }

    /** Accept or decline an incoming challenge. Returns duel id on accept, -1 otherwise. */
    public long respondToChallenge(long challengeId, boolean accept, int world, int worldLocation)
    {
        JsonObject body = new JsonObject();
        body.addProperty("challenge_id", challengeId);
        body.addProperty("accept", accept);
        body.addProperty("world", world);
        body.addProperty("world_location", worldLocation);
        JsonObject resp = post("/challenge/respond", body, true);
        if (accept && resp != null && resp.has("duel_id"))
        {
            return resp.get("duel_id").getAsLong();
        }
        return -1;
    }

    public boolean cancelChallenge()
    {
        return post("/challenge/cancel", new JsonObject(), true) != null;
    }

    public void reportFightStarted(long duelId, int world, Map<String, Integer> myGear, Map<String, Integer> oppGear)
    {
        JsonObject body = new JsonObject();
        body.addProperty("duel_id", duelId);
        body.addProperty("world", world);
        body.add("my_gear", gson.toJsonTree(myGear));
        body.add("opponent_gear", gson.toJsonTree(oppGear));
        post("/duel/started", body, true);
    }

    public void reportFightFinished(long duelId, boolean won, int world,
                                    Map<String, Integer> myGear, Map<String, Integer> oppGear,
                                    int damageDealt, int damageTaken)
    {
        JsonObject body = new JsonObject();
        body.addProperty("duel_id", duelId);
        body.addProperty("won", won);
        body.addProperty("world", world);
        body.add("my_gear", gson.toJsonTree(myGear));
        body.add("opponent_gear", gson.toJsonTree(oppGear));
        body.addProperty("damage_dealt", damageDealt);
        body.addProperty("damage_taken", damageTaken);
        post("/duel/finished", body, true);
    }

    /** Raw PK tracking: type is "kill" or "death". Fire and forget. */
    public void reportPk(String type, int world)
    {
        JsonObject body = new JsonObject();
        body.addProperty("type", type);
        body.addProperty("world", world);
        post("/pk/report", body, true);
    }

    public void reportAbort(long duelId, String reason)
    {
        JsonObject body = new JsonObject();
        body.addProperty("duel_id", duelId);
        body.addProperty("reason", reason);
        post("/duel/abort", body, true);
    }

    /** Own ladder stats + recent duels, for the side panel. */
    public JsonObject getMe()
    {
        return get("/me");
    }

    /**
     * Single lightweight poll for anything the server wants to tell this client.
     * Carries the current display name so the server can heal rows that were
     * linked before the player name was available.
     */
    public PollResult poll(String displayName)
    {
        String url = config.apiBaseUrl() + "/poll";
        if (displayName != null && !displayName.isEmpty())
        {
            url += "?name=" + java.net.URLEncoder.encode(displayName, java.nio.charset.StandardCharsets.UTF_8);
        }
        Request.Builder rb = new Request.Builder().url(url).get();
        JsonObject resp = execute(rb, true);
        if (resp == null) return null;

        PollResult result = new PollResult();
        if (resp.has("incoming_challenge") && resp.get("incoming_challenge").isJsonObject())
        {
            JsonObject ch = resp.getAsJsonObject("incoming_challenge");
            result.incomingChallenge = new ChallengeInfo(
                ch.get("challenge_id").getAsLong(),
                ch.get("challenger_name").getAsString(),
                ch.has("challenger_rating") ? ch.get("challenger_rating").getAsInt() : 0,
                ch.has("world") ? ch.get("world").getAsInt() : 0,
                ch.has("win_delta") ? ch.get("win_delta").getAsDouble() : 0,
                ch.has("loss_delta") ? ch.get("loss_delta").getAsDouble() : 0
            );
        }
        result.outdated = resp.has("outdated") && resp.get("outdated").getAsBoolean();
        if (result.outdated)
        {
            outdated = true;
        }
        result.duelAccepted = resp.has("duel_accepted") && resp.get("duel_accepted").getAsBoolean();
        result.duelDeclined = resp.has("duel_declined") && resp.get("duel_declined").getAsBoolean();
        if (resp.has("duel_id"))
        {
            result.duelId = resp.get("duel_id").getAsLong();
        }
        if (resp.has("win_delta"))
        {
            result.winDelta = resp.get("win_delta").getAsDouble();
            result.lossDelta = resp.get("loss_delta").getAsDouble();
        }
        return result;
    }

    // ------------------------------------------------------------------
    private JsonObject post(String path, JsonObject body, boolean authed)
    {
        Request.Builder rb = new Request.Builder()
            .url(config.apiBaseUrl() + path)
            .post(RequestBody.create(JSON, gson.toJson(body)));
        return execute(rb, authed);
    }

    private JsonObject get(String path)
    {
        Request.Builder rb = new Request.Builder().url(config.apiBaseUrl() + path).get();
        return execute(rb, true);
    }

    private JsonObject execute(Request.Builder rb, boolean authed)
    {
        rb.header("X-Plugin-Version", RankedDuelsPlugin.VERSION);
        if (authed)
        {
            String token = storedToken();
            if (token == null || accountId == null)
            {
                return null; // not linked for this account yet
            }
            rb.header("Authorization", "Bearer " + token);
            rb.header("X-Account-Hash", accountId);
        }
        try (Response response = http.newCall(rb.build()).execute())
        {
            if (response.code() == 426)
            {
                outdated = true;
                log.info("Server requires a newer plugin version");
                return null;
            }
            if (!response.isSuccessful() || response.body() == null)
            {
                log.debug("API call failed: {} {}", response.code(), response.message());
                return null;
            }
            // Be lenient: some responses (e.g. empty PHP arrays) arrive as
            // JSON arrays. Treat anything that isn't an object as "no data".
            com.google.gson.JsonElement el = new JsonParser().parse(response.body().string());
            return el != null && el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
        }
        catch (IOException e)
        {
            log.debug("API call error", e);
            return null;
        }
    }

    public static class PollResult
    {
        public ChallengeInfo incomingChallenge;
        public boolean duelAccepted;
        public boolean duelDeclined;
        public long duelId;
        public double winDelta;
        public double lossDelta;
        public boolean outdated;
    }
}
