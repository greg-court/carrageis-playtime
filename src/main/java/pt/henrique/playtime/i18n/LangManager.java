package pt.henrique.playtime.i18n;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import pt.henrique.playtime.Playtime;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Per-player language manager (MiniMessage). Loads every lang/*.yml, resolves
 * the locale per player via PlayerSettingsHook and falls back to the config
 * locale. Player-controlled values are inserted through {@link TagResolver}s
 * (Placeholder.unparsed at the call sites), never string-concatenated into
 * the template — a crafted username can't inject MiniMessage tags.
 */
public class LangManager {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String FALLBACK_LOCALE = "pt_PT";

    private final Playtime plugin;
    private final PlayerSettingsHook settingsHook;
    private final Map<String, FileConfiguration> languages = new HashMap<>();
    /** Volatile: PAPI may read the default locale from another thread. */
    private volatile String defaultLocale = FALLBACK_LOCALE;
    private String defaultPrefix = "";

    public LangManager(Playtime plugin, PlayerSettingsHook settingsHook) {
        this.plugin = plugin;
        this.settingsHook = settingsHook;
        load();
    }

    /** (Re)load all language files. Called on enable and on /playtime reload. */
    public void load() {
        languages.clear();
        defaultLocale = plugin.getConfig().getString("locale", FALLBACK_LOCALE);
        saveLangFileIfAbsent("lang/en_US.yml");
        saveLangFileIfAbsent("lang/pt_PT.yml");

        File langDir = new File(plugin.getDataFolder(), "lang");
        if (langDir.isDirectory()) {
            File[] files = langDir.listFiles((dir, name) -> name.endsWith(".yml"));
            if (files != null) {
                for (File file : files) {
                    String locale = file.getName().replace(".yml", "");
                    FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);
                    InputStream jar = plugin.getResource("lang/" + file.getName());
                    if (jar != null) {
                        cfg.setDefaults(YamlConfiguration.loadConfiguration(
                                new InputStreamReader(jar, StandardCharsets.UTF_8)));
                    }
                    languages.put(locale, cfg);
                }
            }
        }

        if (!languages.containsKey(defaultLocale)) {
            plugin.getLogger().warning("Language " + defaultLocale + ".yml not found, falling back to " + FALLBACK_LOCALE + ".");
            InputStream fb = plugin.getResource("lang/" + FALLBACK_LOCALE + ".yml");
            if (fb != null) {
                languages.put(FALLBACK_LOCALE, YamlConfiguration.loadConfiguration(
                        new InputStreamReader(fb, StandardCharsets.UTF_8)));
                defaultLocale = FALLBACK_LOCALE;
            }
        }

        defaultPrefix = messages(defaultLocale).getString("prefix", "");
        plugin.getLogger().info("[Lang] Loaded " + languages.size() + " language(s): " + languages.keySet());
    }

    /** Locale key for this sender: the player's PlayerSettings choice, else the config default. */
    public String localeKey(CommandSender sender) {
        if (sender instanceof Player player && settingsHook != null && settingsHook.isAvailable()) {
            String lang = settingsHook.getLanguage(player);
            if (lang != null && languages.containsKey(lang)) {
                return lang;
            }
        }
        return defaultLocale;
    }

    /** Java locale for number formatting (decimal separator) for this sender. */
    public Locale javaLocale(CommandSender sender) {
        return toJavaLocale(localeKey(sender));
    }

    /** Java locale of the server default language (console, PAPI). */
    public Locale defaultJavaLocale() {
        return toJavaLocale(defaultLocale);
    }

    private static Locale toJavaLocale(String localeKey) {
        return Locale.forLanguageTag(localeKey.replace('_', '-'));
    }

    /** Prefixed message in the sender's language. */
    public Component msg(CommandSender sender, String key, TagResolver... resolvers) {
        String locale = localeKey(sender);
        return MM.deserialize(prefix(locale) + raw(locale, key), resolvers);
    }

    /** Message without the prefix (list lines) in the sender's language. */
    public Component msgBare(CommandSender sender, String key, TagResolver... resolvers) {
        return MM.deserialize(raw(localeKey(sender), key), resolvers);
    }

    private FileConfiguration messages(String locale) {
        FileConfiguration cfg = languages.get(locale);
        if (cfg != null) {
            return cfg;
        }
        cfg = languages.get(defaultLocale);
        return cfg != null ? cfg : new YamlConfiguration();
    }

    private String prefix(String locale) {
        return messages(locale).getString("prefix", defaultPrefix);
    }

    private String raw(String locale, String key) {
        String msg = messages(locale).getString(key);
        if (msg == null) {
            msg = messages(defaultLocale).getString(key, "<red>Missing message: " + key + "</red>");
        }
        return msg;
    }

    private void saveLangFileIfAbsent(String path) {
        File target = new File(plugin.getDataFolder(), path);
        if (!target.exists()) {
            plugin.saveResource(path, false);
        }
    }
}
