package mikey.me.antiBase;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnPlayer;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public class PacketHandler extends PacketListenerAbstract {
    private final BaseObfuscator obfuscator;
    private final Plugin plugin;

    public PacketHandler(Plugin plugin, BaseObfuscator obfuscator) {
        super(PacketListenerPriority.NORMAL);
        this.plugin = plugin;
        this.obfuscator = obfuscator;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        Player player = (Player) event.getPlayer();
        PacketTypeCommon type = event.getPacketType();

        if (type == PacketType.Play.Server.BLOCK_CHANGE) {
            handleBlockChange(event, player);
        } else if (type == PacketType.Play.Server.MULTI_BLOCK_CHANGE) {
            handleMultiBlockChange(event, player);
        } else if (type == PacketType.Play.Server.CHUNK_DATA) {
            handleMapChunk(event, player);
        } else if (type == PacketType.Play.Server.SPAWN_ENTITY || type == PacketType.Play.Server.SPAWN_PLAYER) {
            handleSpawnEntity(event, player);
        }
    }

    private void handleBlockChange(PacketSendEvent event, Player player) {
        WrapperPlayServerBlockChange wrapper = new WrapperPlayServerBlockChange(event);
        Vector3i pos = wrapper.getBlockPosition();
        WrappedBlockState blockState = wrapper.getBlockState();
        String blockName = blockState.getType().getName().toUpperCase().replace("MINECRAFT:", "");
        Material type = Material.getMaterial(blockName);

        if (type != null && obfuscator.shouldObfuscate(type, pos.getX(), pos.getY(), pos.getZ(), player)) {
            wrapper.setBlockState(WrappedBlockState.getByString("minecraft:" + obfuscator.getReplacementBlock().name().toLowerCase()));
        }
    }

    private void handleMultiBlockChange(PacketSendEvent event, Player player) {
    }

    private void handleMapChunk(PacketSendEvent event, Player player) {
        WrapperPlayServerChunkData wrapper = new WrapperPlayServerChunkData(event);
        int cx = wrapper.getColumn().getX();
        int cz = wrapper.getColumn().getZ();

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline())
                return;
            World world = player.getWorld();
            if (!world.isChunkLoaded(cx, cz))
                return;
            Chunk chunk = world.getChunkAt(cx, cz);
            long chunkKey = chunk.getChunkKey();

            // calcuations
            org.bukkit.Location playerLoc = player.getLocation();
            int chunkCenterX = (cx << 4) + 8;
            int chunkCenterZ = (cz << 4) + 8;
            double distSq = Math.pow(playerLoc.getX() - chunkCenterX, 2) + Math.pow(playerLoc.getZ() - chunkCenterZ, 2);
            boolean isFar = distSq > Math.pow(obfuscator.getProximityDistance(), 2);

            if (isFar) {
                // process right now if needed
                obfuscator.setObscured(player.getUniqueId(), chunkKey, true);

                org.bukkit.ChunkSnapshot snapshot = chunk.getChunkSnapshot(false, false, false);
                java.util.concurrent.CompletableFuture.runAsync(() -> {
                    if (!player.isOnline())
                        return;
                    java.util.Map<org.bukkit.Location, org.bukkit.block.data.BlockData> changes = new java.util.HashMap<>();
                    int minY = world.getMinHeight();
                    int maxY = obfuscator.getHideBelowY();

                    for (int by = minY; by <= maxY; by++) {
                        for (int bx = 0; bx < 16; bx++) {
                            for (int bz = 0; bz < 16; bz++) {
                                if (obfuscator.shouldObfuscate(snapshot.getBlockType(bx, by, bz), (cx << 4) + bx, by,
                                        (cz << 4) + bz, player)) {
                                    changes.put(new org.bukkit.Location(world, (cx << 4) + bx, by, (cz << 4) + bz),
                                            obfuscator.getReplacementBlock().createBlockData());
                                }
                            }
                        }
                    }

                    if (!changes.isEmpty()) {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (player.isOnline())
                                player.sendMultiBlockChange(changes);
                        });
                    }
                });
            } else {

                obfuscator.setObscured(player.getUniqueId(), chunkKey, false);
            }
        });
    }

    private void handleSpawnEntity(PacketSendEvent event, Player player) {
        int entityId;
        if (event.getPacketType() == PacketType.Play.Server.SPAWN_ENTITY) {
            WrapperPlayServerSpawnEntity wrapper = new WrapperPlayServerSpawnEntity(event);
            entityId = wrapper.getEntityId();
        } else {
            WrapperPlayServerSpawnPlayer wrapper = new WrapperPlayServerSpawnPlayer(event);
            entityId = wrapper.getEntityId();
        }

        org.bukkit.entity.Entity entity = null;
        for (org.bukkit.entity.Entity e : player.getWorld().getEntities()) {
            if (e.getEntityId() == entityId) {
                entity = e;
                break;
            }
        }

        if (entity != null) {
            if (obfuscator.shouldHideEntity(entity, player)) {
                event.setCancelled(true);
                org.bukkit.entity.Entity finalEntity = entity;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) {
                        player.hideEntity(plugin, finalEntity);
                    }
                });
            }
        }
    }
}
