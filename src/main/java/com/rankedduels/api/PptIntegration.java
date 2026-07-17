package com.rankedduels.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Comparator;
import java.util.zip.GZIPInputStream;

/**
 * Optional integration with Matsyir's "PvP Performance Tracker" plugin
 * (https://github.com/Matsyir/pvp-performance-tracker).
 *
 * If the user runs both plugins, we attach PPT's rich fight data (attack
 * counts, off-pray %, expected damage, magic accuracy, per-attack fight log
 * with KO chances) to our duel result report.
 *
 * Plugin Hub plugins cannot depend on each other at compile time, so this
 * uses two read-only strategies:
 *   1. Reflection: find the PPT plugin instance through PluginManager and
 *      serialize its most recent FightPerformance with PPT's own Gson rules
 *      (@Expose fields only -> the same compact JSON as its export feature).
 *   2. File fallback: read the newest chunk in
 *      ~/.runelite/pvp-performance-tracker2/FightHistoryData/*.json.gz.
 *
 * The extracted fight is only attached when it matches our duel:
 * opponent name matches and the fight ended within the match window.
 */
@Slf4j
@Singleton
public class PptIntegration
{
    private static final String PPT_PLUGIN_CLASS = "matsyir.pvpperformancetracker.PvpPerformanceTrackerPlugin";
    private static final String PPT_DATA_FOLDER = "pvp-performance-tracker2";
    private static final String PPT_HISTORY_FOLDER = "FightHistoryData";
    private static final long MATCH_WINDOW_MS = 180_000; // fight must have ended within the last 3 minutes

    @Inject private PluginManager pluginManager;
    @Inject private Gson gson;

    /** True if PvP Performance Tracker is installed and enabled. */
    public boolean isAvailable()
    {
        return findPptPlugin() != null;
    }

    /**
     * Grab PPT's data for the duel we just finished, or null if unavailable
     * or nothing matches. Call this a few seconds AFTER the death, from a
     * background thread - PPT also ends its fight on death, but give it a
     * moment to finalize.
     *
     * @param opponentName our duel opponent's display name
     */
    public JsonObject extractFightData(String opponentName)
    {
        try
        {
            JsonObject fromMemory = extractViaReflection(opponentName);
            if (fromMemory != null)
            {
                return fromMemory;
            }
        }
        catch (Exception e)
        {
            log.debug("PPT reflection extraction failed, trying file fallback", e);
        }

        try
        {
            return extractViaFiles(opponentName);
        }
        catch (Exception e)
        {
            log.debug("PPT file extraction failed", e);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Strategy 1: reflection into the live plugin
    // ------------------------------------------------------------------
    private Plugin findPptPlugin()
    {
        for (Plugin p : pluginManager.getPlugins())
        {
            if (PPT_PLUGIN_CLASS.equals(p.getClass().getName()) && pluginManager.isPluginEnabled(p))
            {
                return p;
            }
        }
        return null;
    }

    private JsonObject extractViaReflection(String opponentName) throws Exception
    {
        Plugin ppt = findPptPlugin();
        if (ppt == null)
        {
            return null;
        }

        // public ArrayDeque<FightPerformance> fightHistory;
        Field historyField = ppt.getClass().getField("fightHistory");
        ArrayDeque<?> history = (ArrayDeque<?>) historyField.get(ppt);
        if (history == null || history.isEmpty())
        {
            return null;
        }

        Object lastFight = history.peekLast();
        // Serialize with PPT's own compact format. PPT builds its GSON with
        // excludeFieldsWithoutExposeAnnotation; replicate that so transient
        // client objects (Player etc.) are skipped.
        Gson exposeGson = gson.newBuilder()
            .excludeFieldsWithoutExposeAnnotation()
            .create();
        JsonObject fight = new JsonParser().parse(exposeGson.toJson(lastFight)).getAsJsonObject();

        return matchesOurDuel(fight, opponentName) ? fight : null;
    }

    // ------------------------------------------------------------------
    // Strategy 2: newest fight-history chunk on disk
    // ------------------------------------------------------------------
    private JsonObject extractViaFiles(String opponentName) throws Exception
    {
        File dir = new File(RuneLite.RUNELITE_DIR, PPT_DATA_FOLDER + File.separator + PPT_HISTORY_FOLDER);
        if (!dir.isDirectory())
        {
            return null;
        }
        File[] chunks = dir.listFiles((d, name) -> name.endsWith(".json.gz"));
        if (chunks == null || chunks.length == 0)
        {
            return null;
        }
        File newest = Arrays.stream(chunks).max(Comparator.comparingLong(File::lastModified)).orElse(null);
        if (newest == null || System.currentTimeMillis() - newest.lastModified() > MATCH_WINDOW_MS * 5)
        {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
            new GZIPInputStream(new FileInputStream(newest)), StandardCharsets.UTF_8)))
        {
            String line;
            while ((line = reader.readLine()) != null)
            {
                sb.append(line);
            }
        }

        // Chunks contain an array of fights; take the newest matching one.
        var parsed = new JsonParser().parse(sb.toString());
        if (parsed.isJsonArray())
        {
            JsonObject best = null;
            for (var el : parsed.getAsJsonArray())
            {
                if (el.isJsonObject() && matchesOurDuel(el.getAsJsonObject(), opponentName))
                {
                    if (best == null || fightTime(el.getAsJsonObject()) > fightTime(best))
                    {
                        best = el.getAsJsonObject();
                    }
                }
            }
            return best;
        }
        if (parsed.isJsonObject() && matchesOurDuel(parsed.getAsJsonObject(), opponentName))
        {
            return parsed.getAsJsonObject();
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Matching: opponent name + recency
    // PPT compact keys: c = competitor Fighter, o = opponent Fighter,
    // t = lastFightTime epoch millis; Fighter.n = name.
    // ------------------------------------------------------------------
    private boolean matchesOurDuel(JsonObject fight, String opponentName)
    {
        if (fight == null || opponentName == null)
        {
            return false;
        }
        long t = fightTime(fight);
        if (t <= 0 || System.currentTimeMillis() - t > MATCH_WINDOW_MS)
        {
            return false;
        }
        try
        {
            String pptOpponent = fight.getAsJsonObject("o").get("n").getAsString();
            return sanitize(opponentName).equalsIgnoreCase(sanitize(pptOpponent));
        }
        catch (Exception e)
        {
            return false;
        }
    }

    private long fightTime(JsonObject fight)
    {
        try
        {
            return fight.get("t").getAsLong();
        }
        catch (Exception e)
        {
            return 0;
        }
    }

    private static String sanitize(String name)
    {
        return name == null ? "" : name.replace('\u00A0', ' ').trim();
    }
}
