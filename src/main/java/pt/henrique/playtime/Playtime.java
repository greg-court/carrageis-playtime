package pt.henrique.playtime;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import pt.henrique.playtime.i18n.LangManager;
import pt.henrique.playtime.i18n.PlayerSettingsHook;

import java.util.Objects;

/**
 * Playtime — playtime tracking straight from the vanilla
 * {@code minecraft:play_time} statistic.
 *
 * <p>Design: the plugin keeps NO database of its own. The server already
 * tracks every player's total playtime in ticks — live in memory for online
 * players ({@code Statistic.PLAY_ONE_MINUTE}, the legacy Bukkit name for
 * {@code minecraft:play_time}) and on disk in
 * {@code <mainWorld>/stats/<uuid>.json} for everyone else — so the plugin is
 * born already knowing the full history since the server began.</p>
 *
 * <p>{@link PlaytimeService} rebuilds an immutable {@link PlaytimeSnapshot}
 * on a timer: a cheap sync pass snapshots online players, then an async task
 * scans the stats files and {@code usercache.json}, merges (live wins over
 * files, which lag until autosave) and publishes the result through a single
 * volatile reference. Commands and placeholders read only that snapshot —
 * zero I/O on the command path, zero file I/O on the main thread anywhere.</p>
 */
public final class Playtime extends JavaPlugin {

    private final PlayerSettingsHook settingsHook = new PlayerSettingsHook();
    private LangManager lang;
    private PlaytimeService service;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settingsHook.init(getLogger());
        lang = new LangManager(this, settingsHook);
        service = new PlaytimeService(this);
        service.start();

        PlaytimeCommand playtimeCommand = new PlaytimeCommand(this, service);
        PluginCommand playtime = Objects.requireNonNull(getCommand("playtime"), "playtime missing from plugin.yml");
        playtime.setExecutor(playtimeCommand);
        playtime.setTabCompleter(playtimeCommand);

        TophoursCommand tophoursCommand = new TophoursCommand(this, service);
        PluginCommand tophours = Objects.requireNonNull(getCommand("tophours"), "tophours missing from plugin.yml");
        tophours.setExecutor(tophoursCommand);
        tophours.setTabCompleter(tophoursCommand);

        getServer().getPluginManager().registerEvents(new JoinQuitListener(service), this);

        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new PapiExpansion(this, service).register();
            getLogger().info("[Hook] PlaceholderAPI enabled - %playtime_formatted%, %playtime_hours%, %playtime_rank%.");
        }

        getLogger().info("Playtime enabled - vanilla play_time statistic, refresh every "
                + refreshMinutes() + " min, no database.");
    }

    @Override
    public void onDisable() {
        if (service != null) {
            service.stop();
        }
    }

    /** Refresh interval from config, clamped to at least 1 minute. */
    public int refreshMinutes() {
        return Math.max(1, getConfig().getInt("refresh-minutes", 5));
    }

    public LangManager lang() {
        return lang;
    }

    /** Prefixed message from lang/<locale>.yml in the sender's language. */
    public Component msg(CommandSender sender, String key, TagResolver... resolvers) {
        return lang.msg(sender, key, resolvers);
    }

    /** Message without the prefix (list lines) in the sender's language. */
    public Component msgBare(CommandSender sender, String key, TagResolver... resolvers) {
        return lang.msgBare(sender, key, resolvers);
    }
}
