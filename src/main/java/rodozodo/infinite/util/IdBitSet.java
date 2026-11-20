package rodozodo.infinite.util;


public final class IdBitSet {
    private final int capacity;
    private final long[][] levels;
    private int count;
    private int highest = -1;

    public IdBitSet(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
        this.capacity = capacity;
        int depth = 1;
        for (long words = wordsFor(capacity); words > 1; words = wordsFor(words)) depth++;
        this.levels = new long[depth][];
        long bits = capacity;
        for (int level = 0; level < depth; level++) {
            var words = new long[(int) wordsFor(bits)];

            // Mark bits past capacity as used so tree walks need no bounds check
            int tail = (int) (bits & 63);
            if (tail != 0) words[words.length - 1] = -1L << tail;
            this.levels[level] = words;
            bits = words.length;
        }
        for (int level = 0; level + 1 < depth; level++) {
            var words = this.levels[level];
            if (words[words.length - 1] == -1L) this.setBit(level + 1, words.length - 1);
        }
    }

    private static long wordsFor(long bits) {
        return (bits + 63) >>> 6;
    }

    private void setBit(int level, int index) {
        this.levels[level][index >>> 6] |= 1L << index;
    }

    private void take(int id) {
        int index = id;
        for (int level = 0; level < this.levels.length; level++) {
            var words = this.levels[level];
            long word = words[index >>> 6] |= 1L << index;
            if (word != -1L) break;
            index >>>= 6;
        }
        this.count++;
        if (id > this.highest) this.highest = id;
    }

    private int nextFree(int from) {
        if (from >= this.capacity) return -1;
        long bits = ~this.levels[0][from >>> 6] & -1L << from;
        if (bits != 0) return (from & ~63) + Long.numberOfTrailingZeros(bits);
        int word = (from >>> 6) + 1;
        for (int level = 1; level < this.levels.length; level++) {
            var words = this.levels[level];
            if (word >>> 6 >= words.length) return -1;
            bits = ~words[word >>> 6] & -1L << word;
            if (bits != 0) {
                int index = (word & ~63) + Long.numberOfTrailingZeros(bits);
                for (int down = level - 1; down >= 0; down--) {
                    index = (index << 6) + Long.numberOfTrailingZeros(~this.levels[down][index]);
                }
                return index;
            }
            word = (word >>> 6) + 1;
        }
        return -1;
    }

    public int allocate() {
        int id = this.nextFree(0);
        if (id >= 0) this.take(id);
        return id;
    }

    public int allocateRun(int length) {
        if (length < 1 || length > 64) throw new IllegalArgumentException("Run length must be 1 to 64, got " + length);
        if (length == 1) return this.allocate();
        long mask = length == 64 ? -1L : (1L << length) - 1;
        var ids = this.levels[0];
        for (int start = this.nextFree(0); start >= 0; ) {
            if (start + length > this.capacity) return -1;
            int shift = start & 63;
            long window = ids[start >>> 6] >>> shift;
            if (shift + length > 64) window |= ids[(start >>> 6) + 1] << (64 - shift);
            long used = window & mask;
            if (used == 0) {
                for (int id = start; id < start + length; id++) this.take(id);
                return start;
            }
            start = this.nextFree(start + 64 - Long.numberOfLeadingZeros(used));
        }
        return -1;
    }

    public boolean free(int id) {
        if (id < 0 || id >= this.capacity) throw new IndexOutOfBoundsException("Id " + id + " outside 0.." + (this.capacity - 1));
        var ids = this.levels[0];
        long bit = 1L << id;
        if ((ids[id >>> 6] & bit) == 0) return false;
        int index = id;
        for (int level = 0; level < this.levels.length; level++) {
            var words = this.levels[level];
            long word = words[index >>> 6];
            words[index >>> 6] = word & ~(1L << index);
            if (word != -1L) break;
            index >>>= 6;
        }
        this.count--;
        if (id == this.highest) this.highest = this.lastUsedBelow(id);
        return true;
    }

    private int lastUsedBelow(int id) {
        var ids = this.levels[0];
        int word = id >>> 6;
        long bits = ids[word] & ((1L << id) - 1);
        while (true) {
            if (bits != 0) return (word << 6) + 63 - Long.numberOfLeadingZeros(bits);
            if (--word < 0) return -1;
            bits = ids[word];
        }
    }

    public boolean isUsed(int id) {
        return id >= 0 && id < this.capacity && (this.levels[0][id >>> 6] & 1L << id) != 0;
    }

    public int count() {
        return this.count;
    }

    public int capacity() {
        return this.capacity;
    }

    public int highest() {
        return this.highest;
    }
}