package pt.henrique.playtime.i18n;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Reflection-based hook for the PlayerSettings plugin.
 * Provides per-player language preferences without a compile-time dependency.
 */
public class PlayerSettingsHook {

    private Object settingsProvider;
    private Method getLanguageMethod;
    private boolean available;

    public void init(Logger logger) {
        try {
            if (Bukkit.getPluginManager().getPlugin("PlayerSettings") == null) {
                available = false;
                logger.info("[PlayerSettings] Plugin not found. Per-player language disabled.");
                return;
            }
            Class<?> apiClass = Class.forName("pt.henrique.playersettings.api.PlayerSettingsApi");
            var reg = Bukkit.getServicesManager().getRegistration(apiClass);
            if (reg != null && reg.getProvider() != null) {
                settingsProvider = reg.getProvider();
                getLanguageMethod = settingsProvider.getClass().getMethod("getLanguage", UUID.class);
                available = true;
                logger.info("[PlayerSettings] Hook enabled - per-player language active.");
            } else {
                available = false;
                logger.info("[PlayerSettings] API not found in ServicesManager.");
            }
        } catch (ClassNotFoundException e) {
            available = false;
            logger.info("[PlayerSettings] Classes not found on classpath.");
        } catch (Exception e) {
            available = false;
            logger.warning("[PlayerSettings] Hook failed: " + e.getMessage());
        }
    }

    /** @return the locale string (e.g. "en_US", "pt_PT"), or null if unavailable. */
    public String getLanguage(Player player) {
        if (!available || settingsProvider == null) return null;
        try {
            return (String) getLanguageMethod.invoke(settingsProvider, player.getUniqueId());
        } catch (Exception e) {
            return null;
        }
    }

    public boolean isAvailable() {
        return available;
    }
}
