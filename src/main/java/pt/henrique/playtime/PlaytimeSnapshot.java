package pt.henrique.playtime;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable view of everyone's playtime at one moment in time: the sorted
 * leaderboard, ticks by UUID (including players whose name could not be
 * resolved — those are kept out of the leaderboard only), a UUID index and a
 * case-insensitive name index. {@link PlaytimeService} publishes instances
 * through a single volatile reference, so readers always see a complete,
 * consistent snapshot — never a half-updated one.
 */
public final class PlaytimeSnapshot {

    /** Pre-first-scan sentinel: {@link #isReady()} is false only here. */
    public static final PlaytimeSnapshot EMPTY =
            new PlaytimeSnapshot(List.of(), Map.of(), Map.of(), Map.of(), null);

    private final List<PlaytimeEntry> sorted;
    private final Map<UUID, Long> ticksByUuid;
    private final Map<UUID, PlaytimeEntry> byUuid;
    private final Map<String, PlaytimeEntry> byLowerName;
    private final Instant generatedAt;

    private PlaytimeSnapshot(List<PlaytimeEntry> sorted,
                             Map<UUID, Long> ticksByUuid,
                             Map<UUID, PlaytimeEntry> byUuid,
                             Map<String, PlaytimeEntry> byLowerName,
                             Instant generatedAt) {
        this.sorted = sorted;
        this.ticksByUuid = ticksByUuid;
        this.byUuid = byUuid;
        this.byLowerName = byLowerName;
        this.generatedAt = generatedAt;
    }

    /**
     * Builds a snapshot from merged tick and name maps. Players without a
     * resolvable name keep their ticks (PAPI lookups by UUID still work) but
     * are skipped from the leaderboard. Sorted by ticks descending, name as
     * a stable tie-break.
     */
    public static PlaytimeSnapshot build(Map<UUID, Long> ticks, Map<UUID, String> names) {
        return build(ticks, names, Instant.now());
    }

    private static PlaytimeSnapshot build(Map<UUID, Long> ticks, Map<UUID, String> names, Instant generatedAt) {
        record Row(UUID uuid, String name, long ticks) {
        }
        List<Row> rows = new ArrayList<>(ticks.size());
        for (Map.Entry<UUID, Long> e : ticks.entrySet()) {
            String name = names.get(e.getKey());
            if (name != null && !name.isBlank()) {
                rows.add(new Row(e.getKey(), name, e.getValue()));
            }
        }
        rows.sort(Comparator.comparingLong(Row::ticks).reversed()
                .thenComparing(Row::name, String.CASE_INSENSITIVE_ORDER));

        List<PlaytimeEntry> sorted = new ArrayList<>(rows.size());
        Map<UUID, PlaytimeEntry> byUuid = new HashMap<>(rows.size() * 2);
        Map<String, PlaytimeEntry> byName = new HashMap<>(rows.size() * 2);
        int rank = 1;
        for (Row row : rows) {
            PlaytimeEntry entry = new PlaytimeEntry(row.uuid(), row.name(), row.ticks(), rank++);
            sorted.add(entry);
            byUuid.put(row.uuid(), entry);
            // Two UUIDs can share one visible name (premium/offline duplicates
            // on ServerAUTH+Geyser servers) — the best-ranked holder wins the
            // name lookup, so /playtime <name> matches what /tophours shows.
            // Lookups for a KNOWN identity must use byUuid(), never this index.
            byName.putIfAbsent(row.name().toLowerCase(Locale.ROOT), entry);
        }
        return new PlaytimeSnapshot(List.copyOf(sorted), Map.copyOf(ticks),
                Map.copyOf(byUuid), Map.copyOf(byName), generatedAt);
    }

    /**
     * Returns a copy of this snapshot with a batch of players' ticks/names
     * replaced (quit patches — the live stat is fresher than the last scan).
     * A no-op before the first scan completes — the scan is seconds away and
     * a one-player "leaderboard" would lie in the meantime.
     */
    public PlaytimeSnapshot withPatched(Map<UUID, Long> patchTicks, Map<UUID, String> patchNames) {
        if (!isReady() || patchTicks.isEmpty()) {
            return this;
        }
        Map<UUID, Long> newTicks = new HashMap<>(ticksByUuid);
        newTicks.putAll(patchTicks);
        Map<UUID, String> names = new HashMap<>(sorted.size() * 2);
        for (PlaytimeEntry entry : sorted) {
            names.put(entry.uuid(), entry.name());
        }
        names.putAll(patchNames);
        return build(newTicks, names, generatedAt);
    }

    /** Leaderboard entries, ticks descending, ranks already assigned. */
    public List<PlaytimeEntry> sorted() {
        return sorted;
    }

    /** Total ticks for a player, 0 when unknown. Includes nameless players. */
    public long ticks(UUID uuid) {
        Long value = ticksByUuid.get(uuid);
        return value == null ? 0L : value;
    }

    /**
     * Leaderboard entry for exactly this UUID; null when unranked. The right
     * lookup whenever the caller already knows WHO it wants (own rank, PAPI)
     * — immune to two accounts sharing one visible name.
     */
    public PlaytimeEntry byUuid(UUID uuid) {
        return byUuid.get(uuid);
    }

    /** Case-insensitive leaderboard lookup; null when the name is unknown. */
    public PlaytimeEntry byName(String name) {
        return byLowerName.get(name.toLowerCase(Locale.ROOT));
    }

    /** Number of ranked (named) players. */
    public int playerCount() {
        return sorted.size();
    }

    /** False only before the first scan has ever published. */
    public boolean isReady() {
        return generatedAt != null;
    }

    /** When the last full scan produced this data; null for {@link #EMPTY}. */
    public Instant generatedAt() {
        return generatedAt;
    }
}
