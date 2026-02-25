package mikey.me.antiBase;

import org.bukkit.Chunk;

import java.util.HashMap;

// reused per player for flood-fill, ring buffer queue
class BFSContext {
    static final int QUEUE_CAPACITY = 100000;
    static final int VISITED_BLOCKS_INITIAL_CAPACITY = 200000;
    static final int VISIBLE_BLOCKS_INITIAL_CAPACITY = 50000;
    static final int VISIBLE_SECTIONS_INITIAL_CAPACITY = 4096;
    final int[] queueX = new int[QUEUE_CAPACITY];
    final int[] queueY = new int[QUEUE_CAPACITY];
    final int[] queueZ = new int[QUEUE_CAPACITY];
    final LongHashSet visitedBlocks = new LongHashSet(VISITED_BLOCKS_INITIAL_CAPACITY);
    final LongHashSet visibleBlocks = new LongHashSet(VISIBLE_BLOCKS_INITIAL_CAPACITY);
    final LongHashSet newVisibleSections = new LongHashSet(VISIBLE_SECTIONS_INITIAL_CAPACITY);
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