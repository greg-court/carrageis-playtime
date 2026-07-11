package pt.henrique.playtime;

import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Session bookkeeping: join records when the current session began (for the
 * "Sessão atual" line); quit patches the cache from the live statistic — a
 * cheap in-memory read on the main thread, never a file read — so the
 * leaderboard stays fresh between scans.
 */
public final class JoinQuitListener implements Listener {

    private final PlaytimeService service;

    public JoinQuitListener(PlaytimeService service) {
        this.service = service;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        service.beginSession(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        service.patchFromLive(player.getUniqueId(), player.getName(),
                player.getStatistic(Statistic.PLAY_ONE_MINUTE));
        service.endSession(player.getUniqueId());
    }
}
