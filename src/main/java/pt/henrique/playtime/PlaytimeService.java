package pt.henrique.playtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the playtime cache and its refresh cycle.
 *
 * <p>Each cycle: a SYNC pass on the main thread snapshots online players'
 * live ticks and names (in-memory reads, cheap), then hands off to an ASYNC
 * task that scans {@code <mainWorld>/stats/*.json} and {@code usercache.json},
 * merges (live values win — stats files lag until the server autosaves),
 * sorts and publishes ONE immutable {@link PlaytimeSnapshot} through a
 * volatile reference. Commands read only the snapshot: no I/O on the command
 * path, no file I/O on the main thread anywhere.</p>
 *
 * <p>On quit the cache entry is patched from the live stat. The quit handler
 * itself only enqueues the values (O(1) on the main thread); one coalescing
 * async task rebuilds the snapshot for the whole pending batch, so a quit
 * storm (restart kick-all, join/quit bots) never sorts the leaderboard on the
 * main thread. A patch and a scan publishing at the same instant is a benign
 * last-writer-wins race — the next cycle self-heals.</p>
 */
public final class PlaytimeService {

    /** Stats JSON path: stats."minecraft:custom"."minecraft:play_time" (ticks). */
    private static final String CUSTOM_SECTION = "minecraft:custom";
    private static final String PLAY_TIME_KEY = "minecraft:play_time";
    /** Pre-1.17 worlds stored the same counter under the old key. */
    private static final String LEGACY_PLAY_TIME_KEY = "minecraft:play_one_minute";
    /** Sentinel for "this UUID has no resolvable name" in the name cache. */
    private static final String UNRESOLVED = "";

    private final Playtime plugin;
    /** The single published state; readers never see a half-built snapshot. */
    private volatile PlaytimeSnapshot snapshot = PlaytimeSnapshot.EMPTY;
    private final Map<UUID, Instant> sessionStarts = new ConcurrentHashMap<>();
    private final AtomicBoolean scanning = new AtomicBoolean(false);
    /**
     * Cross-scan cache of names resolved through the offline-player profile
     * (which may read {@code playerdata/<uuid>.dat}) so that lookup happens
     * at most once per UUID, not once per UUID per cycle.
     */
    private final Map<UUID, String> nameCache = new ConcurrentHashMap<>();
    /** Quit patches waiting to be folded into the snapshot by one async task. */
    private final Map<UUID, PendingPatch> pendingPatches = new ConcurrentHashMap<>();
    private final AtomicBoolean patchFlushQueued = new AtomicBoolean(false);
    private final AtomicBoolean warnedNoStatsDir = new AtomicBoolean(false);
    private BukkitTask refreshTask;

    private record PendingPatch(String name, long ticks) {
    }

    public PlaytimeService(Playtime plugin) {
        this.plugin = plugin;
    }

    /** Starts the refresh timer; seeds sessions for anyone already online. */
    public void start() {
        Instant now = Instant.now();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            sessionStarts.putIfAbsent(player.getUniqueId(), now);
        }
        schedule();
    }

    /** Re-reads {@code refresh-minutes} and forces an immediate rescan (reload). */
    public void restart() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
        schedule();
    }

    /** Cancels the refresh timer. Nothing to flush — vanilla owns the data. */
    public void stop() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
        sessionStarts.clear();
        pendingPatches.clear();
        nameCache.clear();
    }

    private void schedule() {
        long periodTicks = 20L * 60L * plugin.refreshMinutes();
        refreshTask = plugin.getServer().getScheduler()
                .runTaskTimer(plugin, this::refreshNow, 1L, periodTicks);
    }

    /**
     * One refresh cycle. MAIN THREAD ONLY: reads live statistics and resolves
     * the world folders here, then does every file touch asynchronously.
     * Skips the cycle if the previous scan is still running.
     */
    public void refreshNow() {
        List<World> worlds = plugin.getServer().getWorlds();
        if (worlds.isEmpty()) {
            plugin.getLogger().warning("No worlds loaded yet - Playtime refresh postponed.");
            return;
        }
        File statsDir = new File(worlds.get(0).getWorldFolder(), "stats");
        File usercache = new File(plugin.getServer().getWorldContainer(), "usercache.json");

        Map<UUID, Long> liveTicks = new HashMap<>();
        Map<UUID, String> liveNames = new HashMap<>();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            liveTicks.put(player.getUniqueId(), (long) player.getStatistic(Statistic.PLAY_ONE_MINUTE));
            liveNames.put(player.getUniqueId(), player.getName());
        }

        if (!scanning.compareAndSet(false, true)) {
            return; // previous scan still in flight; this cycle's live data will be stale anyway
        }
        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    snapshot = scan(statsDir, usercache, liveTicks, liveNames);
                } catch (Exception e) {
                    plugin.getLogger().severe("Playtime scan failed (previous snapshot kept): " + e);
                } finally {
                    scanning.set(false);
                }
            });
        } catch (Exception e) {
            scanning.set(false); // scheduler rejected the task (shutdown) - don't wedge the flag
        }
    }

    /**
     * Patches one player's entry from the live stat (quit). O(1) on the main
     * thread: the rebuild + sort runs in one coalescing async task, so even a
     * mass quit (restart, bots) costs a single rebuild off-thread.
     */
    public void patchFromLive(UUID uuid, String name, long ticks) {
        pendingPatches.put(uuid, new PendingPatch(name, ticks));
        if (patchFlushQueued.compareAndSet(false, true)) {
            try {
                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, this::flushPatches);
            } catch (Exception e) {
                // Scheduler shutting down: the snapshot dies with the process
                // anyway, so dropping the patch is harmless.
                patchFlushQueued.set(false);
                pendingPatches.clear();
            }
        }
    }

    /**
     * Async: folds every pending quit patch into one new snapshot. Single
     * flight — the queued flag stays set while this runs, so two flushes
     * never race their read-modify-write; patches that arrive mid-flush are
     * caught by the tail re-check (or queue their own flush after the reset).
     */
    private void flushPatches() {
        try {
            Map<UUID, Long> patchTicks = new HashMap<>();
            Map<UUID, String> patchNames = new HashMap<>();
            for (Iterator<Map.Entry<UUID, PendingPatch>> it = pendingPatches.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<UUID, PendingPatch> e = it.next();
                patchTicks.put(e.getKey(), e.getValue().ticks());
                patchNames.put(e.getKey(), e.getValue().name());
                it.remove();
            }
            if (!patchTicks.isEmpty()) {
                snapshot = snapshot.withPatched(patchTicks, patchNames);
            }
        } finally {
            patchFlushQueued.set(false);
            if (!pendingPatches.isEmpty() && patchFlushQueued.compareAndSet(false, true)) {
                try {
                    plugin.getServer().getScheduler().runTaskAsynchronously(plugin, this::flushPatches);
                } catch (Exception e) {
                    patchFlushQueued.set(false); // shutdown — drop, snapshot dies with the process
                }
            }
        }
    }

    public PlaytimeSnapshot snapshot() {
        return snapshot;
    }

    /** Records when this player's current session began. */
    public void beginSession(UUID uuid) {
        sessionStarts.put(uuid, Instant.now());
    }

    public void endSession(UUID uuid) {
        sessionStarts.remove(uuid);
    }

    /** Start of the player's current session, or null if unknown. */
    public Instant sessionStart(UUID uuid) {
        return sessionStarts.get(uuid);
    }

    // ──────────────────────────────────────────────────────────────
    // Async scan (never call on the main thread)
    // ──────────────────────────────────────────────────────────────

    private PlaytimeSnapshot scan(File statsDir, File usercache,
                                  Map<UUID, Long> liveTicks, Map<UUID, String> liveNames) {
        Map<UUID, Long> ticks = readStatsDir(statsDir);
        ticks.putAll(liveTicks);      // live wins — files lag until autosave

        Map<UUID, String> names = readUsercache(usercache);
        names.putAll(liveNames);      // live name wins over a stale cache entry
        for (UUID uuid : ticks.keySet()) {
            if (names.containsKey(uuid)) {
                continue;
            }
            // Offline-profile lookup (UUID overload — never a web request, but
            // it may read playerdata/<uuid>.dat). Cached across scans so each
            // UUID pays for it once, including the "no name" answer.
            String cached = nameCache.computeIfAbsent(uuid, id -> {
                String name = plugin.getServer().getOfflinePlayer(id).getName();
                return name == null ? UNRESOLVED : name;
            });
            if (!UNRESOLVED.equals(cached)) {
                names.put(uuid, cached);
            }
        }
        return PlaytimeSnapshot.build(ticks, names);
    }

    /** Reads every stats file; one corrupt file never kills the scan. */
    private Map<UUID, Long> readStatsDir(File statsDir) {
        Map<UUID, Long> out = new HashMap<>();
        File[] files = statsDir.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) {
            // Normal on a fresh server (no player data saved yet) — say it once, not every cycle.
            if (warnedNoStatsDir.compareAndSet(false, true)) {
                plugin.getLogger().info("Stats folder not found yet (" + statsDir.getAbsolutePath()
                        + ") - the leaderboard will cover online players only until the server saves stats.");
            }
            return out;
        }
        warnedNoStatsDir.set(false);
        for (File file : files) {
            try {
                String base = file.getName().substring(0, file.getName().length() - ".json".length());
                UUID uuid = UUID.fromString(base);
                out.put(uuid, readPlayTimeTicks(file));
            } catch (Exception e) {
                plugin.getLogger().warning("Skipped stats file: " + file.getName() + " (" + e.getMessage() + ")");
            }
        }
        return out;
    }

    private static long readPlayTimeTicks(File file) throws Exception {
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            JsonObject stats = root.getAsJsonObject("stats");
            if (stats == null) {
                return 0L;
            }
            JsonObject custom = stats.getAsJsonObject(CUSTOM_SECTION);
            if (custom == null) {
                return 0L;
            }
            JsonElement playTime = custom.get(PLAY_TIME_KEY);
            if (playTime == null) {
                playTime = custom.get(LEGACY_PLAY_TIME_KEY);
            }
            // Clamp: a hand-edited/corrupt negative value must not poison sorting.
            return playTime == null ? 0L : Math.max(0L, playTime.getAsLong());
        }
    }

    /** usercache.json: array of {"name":..,"uuid":..,"expiresOn":..}. Expired
     *  entries are used on purpose — a stale last-known name beats no name. */
    private Map<UUID, String> readUsercache(File file) {
        Map<UUID, String> out = new HashMap<>();
        if (!file.isFile()) {
            return out;
        }
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            JsonArray entries = JsonParser.parseReader(reader).getAsJsonArray();
            for (JsonElement element : entries) {
                try {
                    JsonObject entry = element.getAsJsonObject();
                    out.put(UUID.fromString(entry.get("uuid").getAsString()),
                            entry.get("name").getAsString());
                } catch (Exception e) {
                    // one malformed entry never kills the name index
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("usercache.json unreadable (falling back to live/profile names): " + e.getMessage());
        }
        return out;
    }
}
