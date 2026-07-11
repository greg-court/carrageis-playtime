package pt.henrique.playtime;

import java.util.UUID;

/**
 * One leaderboard row inside a {@link PlaytimeSnapshot}: a player with a
 * resolvable name, their total playtime in ticks (20/s) and their 1-based
 * position in the ranking. Immutable, like the snapshot that owns it.
 */
public record PlaytimeEntry(UUID uuid, String name, long ticks, int rank) {
}
