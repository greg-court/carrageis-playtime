package pt.henrique.playtime;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * PlaceholderAPI expansion — {@code %playtime_formatted%},
 * {@code %playtime_hours%} and {@code %playtime_rank%} for the requesting
 * player. Resolves on the CALLING thread, so it reads only the published
 * {@link PlaytimeSnapshot}: no I/O, no blocking, scoreboard-safe. Numbers
 * use the server's default locale (scoreboards have no per-viewer text).
 */
public final class PapiExpansion extends PlaceholderExpansion {

    private final Playtime plugin;
    private final PlaytimeService service;

    public PapiExpansion(Playtime plugin, PlaytimeService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "playtime";
    }

    @Override
    public @NotNull String getAuthor() {
        return "henrique";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    /** Bundled inside the plugin — must persist or /papi reload wipes it. */
    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) {
            return "";
        }
        PlaytimeSnapshot snapshot = service.snapshot();
        return switch (params.toLowerCase(Locale.ROOT)) {
            case "formatted" -> TimeFormat.dhm(snapshot.ticks(player.getUniqueId()));
            case "hours" -> TimeFormat.decimalHours(snapshot.ticks(player.getUniqueId()),
                    plugin.lang().defaultJavaLocale());
            case "rank" -> {
                // UUID lookup: no OfflinePlayer#getName() (can hit disk on the
                // calling thread) and immune to two accounts sharing a name.
                PlaytimeEntry entry = snapshot.byUuid(player.getUniqueId());
                yield entry == null ? "—" : String.valueOf(entry.rank());
            }
            default -> null; // unknown placeholder → PAPI leaves %playtime_x% visible
        };
    }
}
