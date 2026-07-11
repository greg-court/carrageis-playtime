package pt.henrique.playtime;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /tophours [page]} — paged playtime leaderboard, gold/silver/bronze
 * for the podium, plus the sender's own rank when it falls off the page.
 * Console (RCON) friendly. Reads only the published {@link PlaytimeSnapshot}.
 */
public final class TophoursCommand implements CommandExecutor, TabCompleter {

    private static final TextColor SILVER = TextColor.color(0xC0C0C0);
    private static final TextColor BRONZE = TextColor.color(0xCD7F32);
    private static final int MIN_PAGE_SIZE = 5;
    private static final int MAX_PAGE_SIZE = 25;

    private final Playtime plugin;
    private final PlaytimeService service;

    public TophoursCommand(Playtime plugin, PlaytimeService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("playtime.top")) {
            sender.sendMessage(plugin.msg(sender, "no-permission"));
            return true;
        }
        PlaytimeSnapshot snapshot = service.snapshot();
        if (!snapshot.isReady()) {
            sender.sendMessage(plugin.msg(sender, "not-ready"));
            return true;
        }
        List<PlaytimeEntry> sorted = snapshot.sorted();
        if (sorted.isEmpty()) {
            sender.sendMessage(plugin.msg(sender, "top.empty"));
            return true;
        }

        int pageSize = Math.max(MIN_PAGE_SIZE, Math.min(MAX_PAGE_SIZE,
                plugin.getConfig().getInt("top.page-size", 10)));
        int pages = (sorted.size() + pageSize - 1) / pageSize;
        int page = 1;
        if (args.length >= 1) {
            try {
                page = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                sender.sendMessage(plugin.msg(sender, "top.invalid-page"));
                return true;
            }
            page = Math.max(1, Math.min(page, pages)); // clamp out-of-range pages
        }

        sender.sendMessage(plugin.msg(sender, "top.header",
                Placeholder.unparsed("total", String.valueOf(sorted.size()))));

        Locale numberLocale = plugin.lang().javaLocale(sender);
        // Own rank by UUID — the name index can point at another account
        // sharing the same visible name.
        PlaytimeEntry own = sender instanceof Player player ? snapshot.byUuid(player.getUniqueId()) : null;
        boolean ownOnPage = false;
        int from = (page - 1) * pageSize;
        int to = Math.min(from + pageSize, sorted.size());
        for (int i = from; i < to; i++) {
            PlaytimeEntry entry = sorted.get(i);
            if (own != null && entry.uuid().equals(own.uuid())) {
                ownOnPage = true;
            }
            sender.sendMessage(plugin.msgBare(sender, "top.entry",
                    Placeholder.component("pos", position(entry.rank())),
                    Placeholder.unparsed("name", entry.name()),
                    Placeholder.unparsed("time", TimeFormat.compact(entry.ticks())),
                    Placeholder.unparsed("hours", TimeFormat.decimalHours(entry.ticks(), numberLocale))));
        }

        sender.sendMessage(plugin.msgBare(sender, "top.footer",
                Placeholder.unparsed("page", String.valueOf(page)),
                Placeholder.unparsed("pages", String.valueOf(pages))));

        if (own != null && !ownOnPage) {
            sender.sendMessage(plugin.msgBare(sender, "top.own-rank",
                    Placeholder.component("pos", position(own.rank())),
                    Placeholder.unparsed("time", TimeFormat.compact(own.ticks()))));
        }
        return true;
    }

    /** {@code #N} colored gold/silver/bronze for the podium, gray otherwise. */
    private static Component position(int rank) {
        TextColor color = switch (rank) {
            case 1 -> NamedTextColor.GOLD;
            case 2 -> SILVER;
            case 3 -> BRONZE;
            default -> NamedTextColor.GRAY;
        };
        return Component.text("#" + rank, color);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        int pageSize = Math.max(MIN_PAGE_SIZE, Math.min(MAX_PAGE_SIZE,
                plugin.getConfig().getInt("top.page-size", 10)));
        int pages = (service.snapshot().sorted().size() + pageSize - 1) / pageSize;
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= Math.min(pages, 10); i++) {
            String suggestion = String.valueOf(i);
            if (suggestion.startsWith(args[0])) {
                out.add(suggestion);
            }
        }
        return out;
    }
}
