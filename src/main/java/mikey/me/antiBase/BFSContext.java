package mikey.me.antiBase;

import org.bukkit.Chunk;

import java.util.HashMap;

class BFSContext {
    static final int QUEUE_CAPACITY = 100000;
    final int[] queueX = new int[QUEUE_CAPACITY];
    final int[] queueY = new int[QUEUE_CAPACITY];
    final int[] queueZ = new int[QUEUE_CAPACITY];
    final LongHashSet visitedBlocks = new LongHashSet(200000);
    final LongHashSet visibleBlocks = new LongHashSet(50000);
    final LongHashSet newVisibleSections = new LongHashSet(4096);
    final HashMap<Long, Chunk> chunkCache = new HashMap<>();

    int head;
    int tail;
    int size;
    int startX, startY, startZ;

    void reset(int sx, int sy, int sz) {
        visitedBlocks.clear();
        visibleBlocks.clear();
        newVisibleSections.clear();
        chunkCache.clear();
        head = 0;
        tail = 0;
        size = 0;
        startX = sx;
        startY = sy;
        startZ = sz;
    }
}