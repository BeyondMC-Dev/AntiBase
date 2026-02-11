package mikey.me.antiBase;

import org.bukkit.Chunk;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;

public class PlayerConnectionListener implements Listener {
    private final AntiBase plugin;

    public PlayerConnectionListener(AntiBase plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.cleanupPlayer(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        player.getScheduler().runDelayed(plugin, (task) -> {
            plugin.getMovementListener().updateVisibility(event.getPlayer());
            plugin.getMovementListener().updateOthersViewOfPlayer(event.getPlayer());
        }, null, 5L);
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!plugin.isObfuscationEnabled()) return;
        Chunk chunk = event.getChunk();
        int chunkX = chunk.getX();
        int chunkZ = chunk.getZ();

        for (Player player : chunk.getWorld().getPlayers()) {
            int playerChunkX = player.getLocation().getBlockX() >> 4;
            int playerChunkZ = player.getLocation().getBlockZ() >> 4;
            int dx = Math.abs(chunkX - playerChunkX);
            int dz = Math.abs(chunkZ - playerChunkZ);
            if (dx <= 2 && dz <= 2) {
                player.getScheduler().runDelayed(plugin, (task) -> {
                    if (player.isOnline()) {
                        plugin.getMovementListener().updateVisibility(player);
                    }
                }, null, 1L);
            }
        }
    }
}
