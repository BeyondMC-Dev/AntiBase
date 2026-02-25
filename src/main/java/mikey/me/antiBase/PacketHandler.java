package mikey.me.antiBase;

import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.wrapper.play.server.*;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import org.bukkit.entity.Player;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;

public class PacketHandler extends PacketListenerAbstract {
    private static final int NEAR_CHUNK_THRESHOLD = 1;
    private static final int NEAR_CHUNK_Y_BUFFER = 32;
    private static final int MIN_BLOCK_UPDATE_PROXIMITY = 64;
    private final BaseObfuscator obfuscator;
    private final AntiBase plugin;

    public PacketHandler(AntiBase plugin, BaseObfuscator obfuscator) {
        super(PacketListenerPriority.HIGH);
        this.plugin = plugin;
        this.obfuscator = obfuscator;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        try {
            if (!plugin.isObfuscationEnabled()) return;
            Player player = event.getPlayer();
            if (player == null) return;
            if (obfuscator.isWorldBlacklisted(player.getWorld())) return;
            UUID playerId = player.getUniqueId();
            PacketTypeCommon type = event.getPacketType();

            if (type.getName().equals(PacketType.Play.Server.CHUNK_DATA.getName())) {
                // hide sections below y that player can't see (flood-fill result)
                WrapperPlayServerChunkData chunkData = new WrapperPlayServerChunkData(event);
                Column column = chunkData.getColumn();
                if (column != null) {
                    BaseChunk[] chunks = column.getChunks();
                    if (chunks != null) {
                        boolean modified = false;
                        int hideBelow = obfuscator.getHideBelowY();
                        int minHeight = player.getWorld().getMinHeight();
                        int chunkX = column.getX();
                        int chunkZ = column.getZ();
                        int playerChunkX = player.getLocation().getBlockX() >> 4;
                        int playerChunkZ = player.getLocation().getBlockZ() >> 4;
                        int playerY = player.getLocation().getBlockY();
                        
                        int distX = Math.abs(chunkX - playerChunkX);
                        int distZ = Math.abs(chunkZ - playerChunkZ);
                        boolean isNearChunk = (distX <= NEAR_CHUNK_THRESHOLD && distZ <= NEAR_CHUNK_THRESHOLD);

                        for (int i = 0; i < chunks.length; i++) {
                            BaseChunk section = chunks[i];
                            if (section == null) continue;
                            
                            int sectionBaseY = minHeight + (i * 16);
                            int sectionMaxY = sectionBaseY + 16;

                            if (sectionMaxY <= hideBelow) {
                                boolean isNearY = (playerY >= sectionBaseY - NEAR_CHUNK_Y_BUFFER && playerY <= sectionMaxY + NEAR_CHUNK_Y_BUFFER);
                                
                                if (isNearChunk && isNearY) {
                                    continue; 
                                }

                                if (!plugin.isSectionVisible(playerId, chunkX, sectionBaseY >> 4, chunkZ)) {
                                    clearChunkSection(section);
                                    modified = true;
                                }
                            }
                        }
                        if (modified) {
                            chunkData.setColumn(column);
                            chunkData.write();
                        }
                    }
                }
                return;
            }

            if (type.getName().equals(PacketType.Play.Server.BLOCK_CHANGE.getName())) {
                WrapperPlayServerBlockChange blockChange = new WrapperPlayServerBlockChange(event);
                handleSingleBlockUpdate(player, blockChange.getBlockPosition().getX(), blockChange.getBlockPosition().getY(), blockChange.getBlockPosition().getZ(), blockChange);
                return;
            }

            // Prevent the player from being removed from tab and chat completion

            if (type.getName().equals(PacketType.Play.Server.PLAYER_INFO_REMOVE.getName())) {
                WrapperPlayServerPlayerInfoRemove removeInfo = new WrapperPlayServerPlayerInfoRemove(event);
                List<UUID> uuids = removeInfo.getProfileIds();
                List<UUID> newUUIDs = new ArrayList<>();
                boolean changed = false;
                for (UUID uuid : uuids) {
                    if (plugin.isHidden(playerId, uuid)) {
                        changed = true;
                    } else {
                        newUUIDs.add(uuid);
                    }
                }
                if (changed) {
                    if (newUUIDs.isEmpty()) {
                        event.setCancelled(true);
                    } else {
                        removeInfo.setProfileIds(newUUIDs);
                    }
                }
            }

        } catch (Exception e) {
            plugin.getLogger().severe("Error in PacketHandler: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void handleSingleBlockUpdate(Player player, int bx, int by, int bz, WrapperPlayServerBlockChange packet) {
        int hideBelow = obfuscator.getHideBelowY();
        if (by <= hideBelow) {
            int proximity = obfuscator.getProximityDistance();
            int dx = (int) (player.getLocation().getX() - bx);
            int dy = (int) (player.getLocation().getY() - by);
            int dz = (int) (player.getLocation().getZ() - bz);
            int proximityLimit = Math.max(proximity, MIN_BLOCK_UPDATE_PROXIMITY);
            if (dx * dx + dy * dy + dz * dz > proximityLimit * proximityLimit) {
                packet.setBlockState(BaseObfuscator.getAirBlockState());
            }
        }
    }

    // fill with air so client sees nothing, minimal packet size
    private void clearChunkSection(BaseChunk section) {
        try {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = 0; y < 16; y++) {
                        section.set(x, y, z, BaseObfuscator.getAirBlockStateId());
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Error clearing chunk section: " + e.getMessage());
        }
    }
}