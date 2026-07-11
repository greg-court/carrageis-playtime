package pt.henrique.playtime;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Statistic;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code /playtime [player]} — a player's total playtime, rank and current
 * session, plus the {@code /playtime reload} admin subcommand. Reads come
 * exclusively from the published {@link PlaytimeSnapshot} (and the live
 * statistic when the target is online) — never from disk.
 */
public final class PlaytimeCommand implements CommandExecutor, TabCompleter {

    /** Valid Minecraft account name — checked BEFORE any lookup. */
    private static final Pattern VALID_NAME = Pattern.compile("^[a-zA-Z0-9_]{2,16}$");
    private static final int MAX_SUGGESTIONS = 20;
    /** Hard cap on leaderboard entries examined per tab-complete keystroke. */
    private static final int MAX_SCANNED = 2000;

    private final Playtime plugin;
    private final PlaytimeService service;

    public PlaytimeCommand(Playtime plugin, PlaytimeService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("playtime.admin")) {
                sender.sendMessage(plugin.msg(sender, "no-permission"));
                return true;
            }
            plugin.reloadConfig();
            plugin.lang().load();
            service.restart(); // new refresh-minutes + immediate rescan
            sender.sendMessage(plugin.msg(sender, "reloaded"));
            return true;
        }
        if (!sender.hasPermission("playtime.use")) {
            sender.sendMessage(plugin.msg(sender, "no-permission"));
            return true;
        }
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(plugin.msg(sender, "console-usage"));
                return true;
            }
            show(sender, player.getName());
            return true;
        }
        String target = args[0];
        if (!VALID_NAME.matcher(target).matches()) {
            sender.sendMessage(plugin.msg(sender, "invalid-name"));
            return true;
        }
        show(sender, target);
        return true;
    }

    private void show(CommandSender to, String targetName) {
        PlaytimeSnapshot snapshot = service.snapshot();
        Player online = plugin.getServer().getPlayerExact(targetName); // case-insensitive exact match
        // Online target: rank by UUID (two accounts can share one visible
        // name — the name index would show the other holder's rank).
        PlaytimeEntry entry = online != null
                ? snapshot.byUuid(online.getUniqueId())
                : snapshot.byName(targetName);
        if (online == null && entry == null) {
            to.sendMessage(snapshot.isReady()
                    ? plugin.msg(to, "unknown-player", Placeholder.unparsed("name", targetName))
                    : plugin.msg(to, "not-ready"));
            return;
        }

        String name = online != null ? online.getName() : entry.name();
        long ticks = online != null
                ? online.getStatistic(Statistic.PLAY_ONE_MINUTE)  // live beats the snapshot
                : entry.ticks();

        to.sendMessage(plugin.msg(to, "playtime.total",
                Placeholder.unparsed("name", name),
                Placeholder.unparsed("time", TimeFormat.dhm(ticks)),
                Placeholder.unparsed("hours", TimeFormat.decimalHours(ticks, plugin.lang().javaLocale(to)))));

        if (entry != null) {
            to.sendMessage(plugin.msg(to, "playtime.rank",
                    Placeholder.unparsed("rank", String.valueOf(entry.rank())),
                    Placeholder.unparsed("total", String.valueOf(snapshot.playerCount()))));
        } else {
            to.sendMessage(plugin.msg(to, "playtime.unranked"));
        }

        if (online != null) {
            Instant start = service.sessionStart(online.getUniqueId());
            if (start != null) {
                long sessionTicks = Math.max(0, Duration.between(start, Instant.now()).toSeconds()) * 20L;
                to.sendMessage(plugin.msg(to, "playtime.session",
                        Placeholder.unparsed("session", TimeFormat.dhm(sessionTicks))));
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        Set<String> out = new LinkedHashSet<>();
        if (sender.hasPermission("playtime.admin") && "reload".startsWith(prefix)) {
            out.add("reload");
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (out.size() >= MAX_SUGGESTIONS) {
                break;
            }
            if (player.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                out.add(player.getName());
            }
        }
        // Bounded walk: a spammed no-match prefix must not cost O(all players).
        int scanned = 0;
        for (PlaytimeEntry entry : service.snapshot().sorted()) {
            if (out.size() >= MAX_SUGGESTIONS || ++scanned > MAX_SCANNED) {
                break;
            }
            if (entry.name().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                out.add(entry.name());
            }
        }
        return List.copyOf(out);
    }
}
