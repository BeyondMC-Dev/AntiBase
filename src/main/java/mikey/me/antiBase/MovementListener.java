package mikey.me.antiBase;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.event.player.PlayerTeleportEvent;

import org.bukkit.Material;

import java.util.UUID;
import java.util.Set;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MovementListener implements Listener {
    private static final int[][] NEIGHBORS = {{1,0,0}, {-1,0,0}, {0,1,0}, {0,-1,0}, {0,0,1}, {0,0,-1}};
    private static final Set<Material> PASSTHROUGH_BLOCKS = EnumSet.of(
        Material.WATER, Material.LAVA, Material.FIRE, Material.SOUL_FIRE,
        Material.SHORT_GRASS, Material.TALL_GRASS, Material.FERN, Material.LARGE_FERN
    );
    private final AntiBase plugin;
    private final BaseObfuscator obfuscator;
    private final ExecutorService bfsExecutor = Executors.newFixedThreadPool(2);
    private final Map<UUID, Long> lastUpdateTick = new ConcurrentHashMap<>();
    private final Map<UUID, LongHashSet> playerVisibleSections = new ConcurrentHashMap<>();
    private final Map<UUID, BFSContext> bfsContexts = new ConcurrentHashMap<>();
    private final Map<UUID, long[]> lastBFSPosition = new ConcurrentHashMap<>();
    private final Set<UUID> bfsRunning = ConcurrentHashMap.newKeySet();

    public MovementListener(AntiBase plugin, BaseObfuscator obfuscator) {
        this.plugin = plugin;
        this.obfuscator = obfuscator;
    }

    private void update(Location to, Location from, Player player) {
        if (!plugin.isObfuscationEnabled()) return;
        if (from.getBlockX() == to.getBlockX() && from.getBlockZ() == to.getBlockZ() && from.getBlockY() == to.getBlockY()) return;
        if (obfuscator.isWorldBlacklisted(player.getWorld())) return;
        long currentTick = player.getWorld().getFullTime();
        long lastUpdate = lastUpdateTick.getOrDefault(player.getUniqueId(), 0L);
        if (currentTick - lastUpdate < 5) return;
        lastUpdateTick.put(player.getUniqueId(), currentTick);

        int bx = to.getBlockX();
        int by = to.getBlockY();
        int bz = to.getBlockZ();
        long[] lastPos = lastBFSPosition.get(player.getUniqueId());
        boolean needsRestart = lastPos == null;
        if (lastPos != null) {
            long dx = bx - lastPos[0];
            long dy = by - lastPos[1];
            long dz = bz - lastPos[2];
            if (dx * dx + dy * dy + dz * dz >= 4) {
                needsRestart = true;
            }
        }

        if (!needsRestart) return;

        lastBFSPosition.put(player.getUniqueId(), new long[]{bx, by, bz});
        updateVisibility(player);
        updateOthersViewOfPlayer(player);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        update(event.getTo(), event.getFrom(), event.getPlayer());
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        update(event.getTo(), event.getFrom(), event.getPlayer());
    }

    public void updateVisibility(Player player) {
        if (obfuscator.isWorldBlacklisted(player.getWorld())) return;
        int hideBelow = obfuscator.getHideBelowY();
        Location playerLoc = player.getLocation();
        UUID playerId = player.getUniqueId();

        if (playerLoc.getBlockY() > hideBelow + 32) {
            // Clear visible sections when player is above ground so we refresh their chunk when they descend
            playerVisibleSections.remove(playerId);
            updateEntitiesVisibility(player, null);
            return;
        }

        // If player has no visible sections yet, they just entered the underground zone
        boolean justEnteredZone = !playerVisibleSections.containsKey(playerId);

        if (!bfsRunning.add(playerId)) return;

        World world = player.getWorld();
        int minHeight = world.getMinHeight();
        int maxHeight = world.getMaxHeight();
        String worldName = world.getName();

        int startX = playerLoc.getBlockX();
        int startY = Math.max(minHeight, Math.min(maxHeight - 1, playerLoc.getBlockY()));
        int startZ = playerLoc.getBlockZ();

        int yCeiling = hideBelow + 16;

        final boolean skipCurrentChunk = !justEnteredZone;
        bfsExecutor.submit(() -> {
            try {
                BFSContext ctx = bfsContexts.computeIfAbsent(playerId, k -> new BFSContext());
                ctx.reset(startX, startY, startZ);
                ctx.queueX[0] = startX;
                ctx.queueY[0] = startY;
                ctx.queueZ[0] = startZ;
                ctx.tail = 1;
                ctx.size = 1;
                ctx.visitedBlocks.add(plugin.packCoord(startX, startY, startZ));

                int maxDistance = 64;
                int maxDistSq = maxDistance * maxDistance;
                int maxYDistance = 32;

                while (ctx.size > 0) {
                    int x = ctx.queueX[ctx.head];
                    int y = ctx.queueY[ctx.head];
                    int z = ctx.queueZ[ctx.head];
                    ctx.head = (ctx.head + 1) % BFSContext.QUEUE_CAPACITY;
                    ctx.size--;
                    int dx = x - ctx.startX;
                    int dy = y - ctx.startY;
                    int dz = z - ctx.startZ;
                    if (dx * dx + dy * dy + dz * dz > maxDistSq) continue;
                    int absYDist = dy < 0 ? -dy : dy;
                    if (y < hideBelow && absYDist <= maxYDistance) {
                        ctx.newVisibleSections.add(plugin.packSection(x >> 4, y >> 4, z >> 4));
                        ctx.visibleBlocks.add(plugin.packCoord(x, y, z));
                    }
                    for (int[] n : NEIGHBORS) {
                        int nx = x + n[0], ny = y + n[1], nz = z + n[2];
                        if (ny < minHeight || ny >= yCeiling) continue;
                        long key = plugin.packCoord(nx, ny, nz);
                        if (ctx.visitedBlocks.add(key)) {
                            int cx = nx >> 4;
                            int cz = nz >> 4;
                            if (!world.isChunkLoaded(cx, cz)) continue;
                            long chunkKey = ((long)cx << 32) | (cz & 0xFFFFFFFFL);
                            Chunk chunk = ctx.chunkCache.computeIfAbsent(chunkKey, k -> world.getChunkAt(cx, cz));
                            Block block = chunk.getBlock(nx & 15, ny, nz & 15);
                            int nyDist = ny - ctx.startY;
                            int absNYDist = nyDist < 0 ? -nyDist : nyDist;
                            if (ny < hideBelow && absNYDist <= maxYDistance) {
                                ctx.newVisibleSections.add(plugin.packSection(cx, ny >> 4, cz));
                            }
                            if (!block.getType().isOccluding() || block.getLightFromSky() == 15) {
                                if (ctx.size < BFSContext.QUEUE_CAPACITY) {
                                    ctx.queueX[ctx.tail] = nx;
                                    ctx.queueY[ctx.tail] = ny;
                                    ctx.queueZ[ctx.tail] = nz;
                                    ctx.tail = (ctx.tail + 1) % BFSContext.QUEUE_CAPACITY;
                                    ctx.size++;
                                }
                            }
                        }
                    }
                }
                ctx.chunkCache.clear();

                LongHashSet resultVisibleBlocks = new LongHashSet(ctx.visibleBlocks.size());
                ctx.visibleBlocks.forEach(resultVisibleBlocks::add);

                LongHashSet resultSections = new LongHashSet(ctx.newVisibleSections.size());
                ctx.newVisibleSections.forEach(resultSections::add);

                int visitedSize = ctx.visitedBlocks.size();
                int sectionSize = ctx.newVisibleSections.size();

                Player onlinePlayer = plugin.getServer().getPlayer(playerId);
                if (onlinePlayer == null || !onlinePlayer.isOnline()) return;

                onlinePlayer.getScheduler().run(plugin, (task) -> {
                    applyBFSResults(onlinePlayer, resultVisibleBlocks, resultSections, visitedSize, sectionSize, skipCurrentChunk);
                }, null);
            } catch (Exception e) {
                plugin.getLogger().warning("BFS error for " + playerId + ": " + e.getMessage());
            } finally {
                bfsRunning.remove(playerId);
            }
        });
    }

    private void applyBFSResults(Player player, LongHashSet visibleBlocks, LongHashSet newVisibleSections, int visitedSize, int sectionSize, boolean skipCurrentChunk) {
        UUID playerId = player.getUniqueId();
        World world = player.getWorld();

        plugin.setVisibleBlocks(playerId, visibleBlocks);
        updateEntitiesVisibility(player, visibleBlocks);

        int playerChunkX = player.getLocation().getBlockX() >> 4;
        int playerChunkZ = player.getLocation().getBlockZ() >> 4;
        String playerChunkKey = playerChunkX + "," + playerChunkZ;

        Set<String> refreshedChunks = new HashSet<>();
        if (skipCurrentChunk) {
            refreshedChunks.add(playerChunkKey);
        }
        LongHashSet oldVisibleSet = playerVisibleSections.get(playerId);

        newVisibleSections.forEach(sectionKey -> {
            int[] coords = plugin.unpackSection(sectionKey);
            if (!plugin.isSectionVisible(playerId, coords[0], coords[1], coords[2])) {
                plugin.updateSectionVisibility(playerId, coords[0], coords[1], coords[2], true);
                String chunkKeyStr = coords[0] + "," + coords[2];
                if (!refreshedChunks.contains(chunkKeyStr)) {
                    if (world.isChunkLoaded(coords[0], coords[2])) {
                        world.refreshChunk(coords[0], coords[2]);
                        refreshedChunks.add(chunkKeyStr);
                    }
                }
            }
        });

        if (oldVisibleSet != null) {
            oldVisibleSet.forEach(oldKey -> {
                if (!newVisibleSections.contains(oldKey)) {
                    int[] coords = plugin.unpackSection(oldKey);
                    plugin.updateSectionVisibility(playerId, coords[0], coords[1], coords[2], false);
                    String chunkKeyStr = coords[0] + "," + coords[2];
                    if (!refreshedChunks.contains(chunkKeyStr)) {
                        if (world.isChunkLoaded(coords[0], coords[2])) {
                            world.refreshChunk(coords[0], coords[2]);
                            refreshedChunks.add(chunkKeyStr);
                        }
                    }
                }
            });
        }
        playerVisibleSections.put(playerId, newVisibleSections);

        if (plugin.isDebugEnabled(playerId) && !refreshedChunks.isEmpty()) {
            player.sendActionBar(MiniMessage.miniMessage().deserialize(
                "<gray>[<gold>AntiBase</gold>]</gray> <green>Visible:</green> <yellow>" + visitedSize + "</yellow> <gray>|</gray> <green>Sections:</green> <yellow>" + sectionSize + "</yellow>"
            ));
        }
    }

    private void updateEntitiesVisibility(Player player, LongHashSet visibleBlocks) {
        if (obfuscator.isWorldBlacklisted(player.getWorld())) return;
        int hideBelow = obfuscator.getHideBelowY();
        Location pLoc = player.getLocation();
        double px = pLoc.getX();
        double py = pLoc.getY();
        double pz = pLoc.getZ();

        for (Player other : player.getWorld().getPlayers()) {
            if (other.equals(player)) continue;
            double dx = other.getLocation().getX() - px;
            double dy = other.getLocation().getY() - py;
            double dz = other.getLocation().getZ() - pz;
            if (dx * dx + dy * dy + dz * dz > 25600) continue;
            Location otherLoc = other.getLocation();
            int ey = otherLoc.getBlockY();
            if (ey < hideBelow && visibleBlocks != null) {
                Material standingIn = otherLoc.getBlock().getType();
                if (PASSTHROUGH_BLOCKS.contains(standingIn)) {
                    setPlayerVisibility(player, other, true);
                } else {
                    boolean visible = visibleBlocks.contains(plugin.packCoord(
                        otherLoc.getBlockX(), ey, otherLoc.getBlockZ()));
                    setPlayerVisibility(player, other, visible);
                }
            } else {
                setPlayerVisibility(player, other, true);
            }
        }

        for (Entity e : player.getNearbyEntities(48, 48, 48)) {
            if (e instanceof Player) continue;
            Location eLoc = e.getLocation();
            int ey = eLoc.getBlockY();
            if (ey < hideBelow && visibleBlocks != null) {
                Material standingIn = eLoc.getBlock().getType();
                if (PASSTHROUGH_BLOCKS.contains(standingIn)) {
                    setEntityVisibility(player, e, true);
                } else {
                    boolean visible = visibleBlocks.contains(plugin.packCoord(
                        eLoc.getBlockX(), ey, eLoc.getBlockZ()));
                    setEntityVisibility(player, e, visible);
                }
            }
        }
    }

    public void updateOthersViewOfPlayer(Player movingPlayer) {
        if (obfuscator.isWorldBlacklisted(movingPlayer.getWorld())) return;
        int hideBelow = obfuscator.getHideBelowY();
        Location movingLoc = movingPlayer.getLocation();
        int ex = movingLoc.getBlockX();
        int ey = movingLoc.getBlockY();
        int ez = movingLoc.getBlockZ();

        if (ey >= hideBelow || PASSTHROUGH_BLOCKS.contains(movingLoc.getBlock().getType())) {
            for (Player other : movingPlayer.getWorld().getPlayers()) {
                if (other.equals(movingPlayer)) continue;
                double dx = other.getLocation().getX() - movingPlayer.getLocation().getX();
                double dy = other.getLocation().getY() - movingPlayer.getLocation().getY();
                double dz = other.getLocation().getZ() - movingPlayer.getLocation().getZ();
                if (dx * dx + dy * dy + dz * dz > 25600) continue;
                setPlayerVisibility(other, movingPlayer, true);
            }
            return;
        }

        for (Player other : movingPlayer.getWorld().getPlayers()) {
            if (other.equals(movingPlayer)) continue;
            double dx = other.getLocation().getX() - movingPlayer.getLocation().getX();
            double dy = other.getLocation().getY() - movingPlayer.getLocation().getY();
            double dz = other.getLocation().getZ() - movingPlayer.getLocation().getZ();
            if (dx * dx + dy * dy + dz * dz > 25600) continue;

            boolean shouldHide = !plugin.isBlockVisible(other.getUniqueId(), ex, ey, ez);
            setPlayerVisibility(other, movingPlayer, !shouldHide);
        }
    }

    private void setEntityVisibility(Player viewer, Entity target, boolean visible) {
        if (target instanceof Player targetPlayer) {
            setPlayerVisibility(viewer, targetPlayer, visible);
        } else {
            if (visible) {
                viewer.showEntity(plugin, target);
            } else {
                viewer.hideEntity(plugin, target);
            }
        }
    }

    private void setPlayerVisibility(Player viewer, Player target, boolean visible) {
        if (visible) {
            viewer.showPlayer(plugin, target);
            plugin.setHidden(viewer.getUniqueId(), target.getUniqueId(), false);
        } else {
            viewer.hidePlayer(plugin, target);
            plugin.setHidden(viewer.getUniqueId(), target.getUniqueId(), true);
        }
    }

    public void shutdown() {
        bfsExecutor.shutdown();
        try {
            if (!bfsExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                bfsExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            bfsExecutor.shutdownNow();
        }
    }

    public void cleanupPlayer(UUID uuid) {
        bfsContexts.remove(uuid);
        lastBFSPosition.remove(uuid);
        lastUpdateTick.remove(uuid);
        playerVisibleSections.remove(uuid);
        bfsRunning.remove(uuid);
    }
}
