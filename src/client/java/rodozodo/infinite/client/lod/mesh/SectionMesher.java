package rodozodo.infinite.client.lod.mesh;

import rodozodo.infinite.Log;
import rodozodo.infinite.client.model.BlockModels;
import rodozodo.infinite.client.model.ModelInfo;
import rodozodo.infinite.thread.WorkerPool;
import rodozodo.infinite.util.NativeBuffer;
import rodozodo.infinite.world.LodPos;
import rodozodo.infinite.world.LodSection;
import rodozodo.infinite.world.LodWorld;
import rodozodo.infinite.world.PackedCells;
import rodozodo.infinite.world.Voxel;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import java.util.concurrent.atomic.LongAdder;

public final class SectionMesher {
    private static final int VOLUME = 32 * 32 * 32;
    private static final int BORDER = VOLUME;
    private static final int CELLS = VOLUME + 6 * 1024;
    private static final int GROUP_LIMIT = 0xFFFF;

    // Bit 3 (width - 1 = 1) marks the 3rd / 4th planes of a '#' plant quad (terrain/quad.glsl)
    private static final int PLANT_SECOND_PAIR = 1 << 3;
    private static final int MAX_QUAD_SIZE = 8;

    // Sentinel AO code (127) indicating per-cell AO and light stored in trailing tables.
    static final int AO_TABLE = 127;
    static final int TABLE_MARK_STEP = 4;
    private static final long AO_BITS = 0x7FL << 57;
    private static final long LIGHT_BITS = 0xFFL << 24;

    // Bit 63: per-corner heights packed into width/height (NW/NE) and bits 57-62 (SW/SE) (terrain/quad.glsl).
    static final long FLUID_CORNERS = 1L << 63;

    // Bit 62: fluid skirt drawn only beside finer neighbors (select/select.comp).
    static final long FLUID_SKIRT = 1L << 62;

    // Matches terrain/quad.glsl height table.
    private static final float[] FLUID_HEIGHTS = {0f, 1f / 6, 1f / 3, 1f / 2, 2f / 3, 7f / 9, 8f / 9, 1f};
    private static final int UNLOADED_STATE = 0;
    private static final int UNLOADED_LIGHT = Voxel.lightOf(15, 0);
    private static final int CAVE_LEVEL = 1;

    // Must match terrain.frag solid-leaf voxel threshold
    public static final int LEAF_SOLID_LEVEL = 2;
    static final int PLANT_LEVEL = 1;

    private static final int OPAQUE = 1, PARTIAL = 2, FLUID = 4, UNKNOWN = 8, PLANT = 16;
    private static final int DRAWN = 31;
    private static final int CASTER = 32;

    private final LodWorld world;
    private final BlockModels models;
    private final boolean cullCaves;

    private final int[] states = new int[CELLS];
    private final byte[] lights = new byte[CELLS];
    private final char[] slots = new char[CELLS];
    private @Nullable PackedCells source;
    private char[] paletteSlots = new char[256];
    private int slotCount;
    private int[] slotKind = new int[256], slotModel = new int[256], slotFluid = new int[256];
    private int[] slotCover = new int[256];
    private long[] slotMeta = new long[256];

    private final int[] opaqueRows = new int[1024];
    private final int[] partialRows = new int[1024];
    private final int[] fluidRows = new int[1024];
    private final int[] usedRows = new int[1024];
    private final int[] casterRows = new int[1024];
    private final int[] plantRows = new int[1024];
    private final int[] coverRows = new int[1024];
    private final int[] caveRows = new int[1024];
    private final int[] litAirRows = new int[1024];
    private final int[] borderLitAir = new int[32];
    private final int[] buriedRows = new int[1024];
    private final int[] coveredColumns = new int[32];
    private final int[][] borderOpaque = new int[6][32];
    private final int[][] borderFluid = new int[6][32];
    private final int[][] borderCaster = new int[6][32];
    private final int[][] borderCave = new int[6][32];
    private final int[] borderUsed = new int[32];

    private final int[] faceRows = new int[1024];
    private final long[] faceCells = new long[VOLUME];
    private final int[] fluidFaceRows = new int[1024];
    private final long[] fluidFaceCells = new long[VOLUME];

    private final long[][] groups = new long[LodMesh.GROUPS][];
    private final int[] groupSizes = new int[LodMesh.GROUPS];
    private final long[][] tabled = new long[LodMesh.GROUPS][];
    private final int[] tabledSizes = new int[LodMesh.GROUPS];
    private int tableCount;
    private int[] tableGroup = new int[256], tableStart = new int[256];
    private short[] tableCodes = new short[4096];
    private int tableCodeCount;
    private int level;
    private boolean animated;
    private boolean anyLeaves;
    private boolean anyPlants;
    private boolean anyDiscard;
    private int discardGroups;
    private int minX, minY, minZ, maxX, maxY, maxZ;

    private final Long2IntOpenHashMap blendCache = new Long2IntOpenHashMap();
    private final boolean[] borderReady = new boolean[6];

    private static final int MEMO_SIZE = 1024, MEMO_MASK = MEMO_SIZE - 1;
    private final int[] memoBlock = new int[MEMO_SIZE];
    private final int[] memoSlot = new int[MEMO_SIZE];

    private static final boolean STAGES = Boolean.getBoolean("infinite.meshStages");
    private static final LongAdder STAGE_CLASSIFY = new LongAdder(), STAGE_CAVES = new LongAdder(),
            STAGE_BORDERS = new LongAdder(), STAGE_BURIED = new LongAdder(), STAGE_FACES = new LongAdder(),
            STAGE_MERGE = new LongAdder(), STAGE_PLANTS = new LongAdder(), STAGE_SECTIONS = new LongAdder();
    private static final LongAdder STAGE_BORDER_LOOKUP = new LongAdder(), STAGE_BORDER_CAVES = new LongAdder();
    private static final LongAdder BORDER_FACES = new LongAdder(), BORDER_PRESENT = new LongAdder();
    private static final LongAdder STAGE_EMPTY = new LongAdder(), STAGE_SOLID = new LongAdder(),
            STAGE_QUADS = new LongAdder();
    private static final LongAdder STAGE_TABLES = new LongAdder(), STAGE_TABLE_CODES = new LongAdder();
    private static final long[] MERGE_SIZES = new long[MAX_QUAD_SIZE * MAX_QUAD_SIZE];
    private static final long[] MERGE_BREAKS = new long[32], MERGE_FACES = new long[2];

    private final IntArrayList missingBlocks = new IntArrayList();
    private final IntOpenHashSet missingSeen = new IntOpenHashSet();
    private boolean warnedOverflow;
    private boolean abandoned;
    private boolean caves;
    private boolean countBreaks;
    private int meshes;

    public SectionMesher(LodWorld world, BlockModels models, boolean cullCaves) {
        this.world = world;
        this.models = models;
        this.cullCaves = cullCaves;
        for (int i = 0; i < LodMesh.GROUPS; i++) {
            this.groups[i] = new long[1024];
        }
        this.blendCache.defaultReturnValue(-1);
        Arrays.fill(this.memoBlock, -1);
    }

    public @Nullable LodMesh mesh(LodSection section, boolean yieldable) {
        this.missingBlocks.clear();
        this.missingSeen.clear();
        this.blendCache.clear();
        Arrays.fill(this.memoBlock, -1);
        this.slotCount = 1;
        Arrays.fill(this.borderReady, false);
        this.abandoned = false;
        this.animated = false;
        this.anyLeaves = false;
        this.anyPlants = false;
        this.anyDiscard = false;
        this.discardGroups = 0;

        this.level = section.level;
        this.countBreaks = STAGES && (this.meshes++ & 63) == 0;
        long at = STAGES ? System.nanoTime() : 0;
        var packed = section.packedCells();
        this.source = packed;
        if (packed != null) {
            this.preparePalette(packed);
        } else {
            var cells = section.cells();
            cells.copyStates(0, this.states, 0, VOLUME);
            cells.copyLight(0, this.lights, 0, VOLUME);
        }
        for (int row = 0; row < 1024; row++) {
            if ((row & 255) == 0 && this.stop(yieldable)) return null;
            if (packed != null) this.classifyPackedRow(row);
            else this.classifyRow(row);
        }
        long classified = STAGES ? System.nanoTime() : 0;
        boolean caves = this.cullCaves && this.level >= CAVE_LEVEL;
        this.caves = caves;
        this.markCaves(caves);
        long caved = STAGES ? System.nanoTime() : 0;
        if (!this.loadBorders(section, yieldable, caves)) return null;
        if (!this.missingBlocks.isEmpty()) {
            return null;
        }
        long bordered = STAGES ? System.nanoTime() : 0;
        this.markBuried();
        long ready = STAGES ? System.nanoTime() : 0;

        Arrays.fill(this.groupSizes, 0);
        Arrays.fill(this.tabledSizes, 0);
        this.tableCount = 0;
        this.tableCodeCount = 0;
        this.minX = this.minY = this.minZ = 32;
        this.maxX = this.maxY = this.maxZ = 0;
        for (int face = 0; face < 6; face++) {
            if (this.stop(yieldable)) return null;
            this.meshFace(face);
        }
        long planted = STAGES ? System.nanoTime() : 0;
        if (this.anyPlants) this.meshPlants();
        if (STAGES) {
            int quads = 0;
            for (int size : this.groupSizes) quads += size;
            for (int size : this.tabledSizes) quads += size;
            STAGE_TABLES.add(this.tableCount);
            STAGE_TABLE_CODES.add(this.tableCodeCount);
            if (quads == 0) STAGE_EMPTY.increment();
            STAGE_QUADS.add(quads);
            boolean solid = true;
            for (int row = 0; row < 1024 && solid; row++) solid = this.opaqueRows[row] == -1;
            if (solid) STAGE_SOLID.increment();
            STAGE_CLASSIFY.add(classified - at);
            STAGE_CAVES.add(caved - classified);
            STAGE_BORDERS.add(bordered - caved);
            STAGE_BURIED.add(ready - bordered);
            STAGE_PLANTS.add(System.nanoTime() - planted);
            STAGE_SECTIONS.increment();
        }
        return this.finish(section.key, section.childMask());
    }

    public IntList missingBlocks() {
        return this.missingBlocks;
    }

    public boolean abandoned() {
        return this.abandoned;
    }

    public static String describeStages() {
        long sections = STAGE_SECTIONS.sum();
        if (!STAGES || sections == 0) return null;
        double per = sections;
        long borders = STAGE_BORDERS.sum();
        return String.format(java.util.Locale.ROOT,
                "Mesh stages us/section: classify %.0f, caves %.0f, borders %.0f (lookup %.0f, caves %.0f, copy %.0f), "
                        + "buried %.0f, faces %.0f, merge %.0f, plants %.0f (%d sections, %d of %d neighbours there; "
                        + "%d empty, %d solid all through, %.0f quads each, %.0f of them with %.0f occlusion codes)",
                STAGE_CLASSIFY.sum() / per / 1e3, STAGE_CAVES.sum() / per / 1e3, borders / per / 1e3,
                STAGE_BORDER_LOOKUP.sum() / per / 1e3, STAGE_BORDER_CAVES.sum() / per / 1e3,
                (borders - STAGE_BORDER_LOOKUP.sum() - STAGE_BORDER_CAVES.sum()) / per / 1e3,
                STAGE_BURIED.sum() / per / 1e3, STAGE_FACES.sum() / per / 1e3, STAGE_MERGE.sum() / per / 1e3,
                STAGE_PLANTS.sum() / per / 1e3, sections, BORDER_PRESENT.sum(), BORDER_FACES.sum(),
                STAGE_EMPTY.sum(), STAGE_SOLID.sum(), STAGE_QUADS.sum() / per, STAGE_TABLES.sum() / per,
                STAGE_TABLE_CODES.sum() / per);
    }

    public static String describeMergeSizes() {
        if (!STAGES) return null;
        long quads = 0, area = 0, wide = 0, tall = 0, both = 0;
        for (int w = 0; w < MAX_QUAD_SIZE; w++) {
            for (int h = 0; h < MAX_QUAD_SIZE; h++) {
                long count = MERGE_SIZES[w * MAX_QUAD_SIZE + h];
                if (count == 0) continue;
                quads += count;
                area += count * (w + 1L) * (h + 1L);
                if (w + 1 == MAX_QUAD_SIZE) wide += count;
                if (h + 1 == MAX_QUAD_SIZE) tall += count;
                if (w + 1 == MAX_QUAD_SIZE && h + 1 == MAX_QUAD_SIZE) both += count;
            }
        }
        if (quads == 0) return null;
        double total = quads;
        return String.format(java.util.Locale.ROOT,
                "Merge sizes %d quads: mean area %.1f cells, %d wide-%.0f%% tall %.0f%% both %.0f%% (%d)",
                quads, area / total, MAX_QUAD_SIZE, wide * 100.0 / total, tall * 100.0 / total, both * 100.0 / total,
                both);
    }

    public static String describeMergeBreaks() {
        if (!STAGES) return null;
        long pairs = 0;
        for (long count : MERGE_BREAKS) pairs += count;
        long faces = MERGE_FACES[0];
        if (pairs == 0 || faces == 0) return null;
        long unequal = pairs - MERGE_BREAKS[0];
        long single = MERGE_BREAKS[1] + MERGE_BREAKS[2] + MERGE_BREAKS[4] + MERGE_BREAKS[8] + MERGE_BREAKS[9];
        double u = Math.max(1, unequal) / 100.0;
        var rest = new StringBuilder();
        Integer[] combos = new Integer[32];
        for (int i = 0; i < 32; i++) combos[i] = i;
        Arrays.sort(combos, (a, b) -> Long.compare(MERGE_BREAKS[b], MERGE_BREAKS[a]));
        for (int combo : combos) {
            if (combo == 0 || combo == 1 || combo == 2 || combo == 4 || combo == 8 || combo == 9 || combo == 3) continue;
            if (MERGE_BREAKS[combo] / u < 0.5) break;
            rest.append(String.format(java.util.Locale.ROOT, ", %s %.0f%%", breakName(combo), MERGE_BREAKS[combo] / u));
        }
        return String.format(java.util.Locale.ROOT,
                "Merge breaks %d faces, %.0f%% alone; %d neighbouring pairs, %.0f%% equal; of the unequal: light %.0f%%, "
                        + "model %.0f%%, biome %.0f%%, occlusion %.0f%%, light and occlusion %.0f%%, model and light %.0f%%, "
                        + "other %.0f%%",
                faces, MERGE_FACES[1] * 100.0 / faces, pairs, MERGE_BREAKS[0] * 100.0 / pairs,
                MERGE_BREAKS[1] / u, MERGE_BREAKS[2] / u, MERGE_BREAKS[4] / u, MERGE_BREAKS[8] / u, MERGE_BREAKS[9] / u,
                MERGE_BREAKS[3] / u, (unequal - single - MERGE_BREAKS[3]) / u) + (rest.isEmpty() ? "" : " (" + rest.substring(2) + ")");
    }

    private static String breakName(int combo) {
        String[] names = {"light", "model", "biome", "occlusion", "corners"};
        var name = new StringBuilder();
        for (int bit = 0; bit < 5; bit++) {
            if ((combo & 1 << bit) != 0) name.append(name.isEmpty() ? "" : "+").append(names[bit]);
        }
        return name.toString();
    }

    private boolean stop(boolean yieldable) {
        if (!yieldable || !WorkerPool.wantThreadBack()) return false;
        this.abandoned = true;
        return true;
    }

    private void classifyRow(int row) {
        int[] states = this.states;
        int cell = row << 5;
        int state = states[cell];
        int same = 1;
        while (same < 32 && states[cell + same] == state) same++;
        int slot = this.slotOf(state, cell);
        if (same == 32) {
            this.uniformRow(row, slot);
            return;
        }
        int opaque = 0, partial = 0, fluid = 0, used = 0, caster = 0, plant = 0, cover = 0;
        boolean coverLevel = this.level == PLANT_LEVEL;
        for (int x = 0; x < 32; x++, cell++) {
            int next = states[cell];
            if (next != state) {
                state = next;
                slot = this.slotOf(state, cell);
            }
            this.slots[cell] = (char) slot;
            int kind = this.slotKind[slot];
            opaque |= (kind & OPAQUE) << x;
            partial |= (kind >>> 1 & 1) << x;
            fluid |= (kind >>> 2 & 1) << x;
            used |= ((kind & DRAWN) != 0 ? 1 : 0) << x;
            caster |= (kind >>> 5 & 1) << x;
            plant |= (kind >>> 4 & 1) << x;
            if (coverLevel) cover |= (this.slotCover[slot] != 0 ? 1 : 0) << x;
        }
        this.setRowClasses(row, opaque, partial, fluid, used, caster, plant, cover);
    }

    private void uniformRow(int row, int slot) {
        int cell = row << 5;
        Arrays.fill(this.slots, cell, cell + 32, (char) slot);
        int kind = this.slotKind[slot];
        this.setRowClasses(row, (kind & OPAQUE) != 0 ? -1 : 0, (kind & PARTIAL) != 0 ? -1 : 0,
                (kind & FLUID) != 0 ? -1 : 0, (kind & DRAWN) != 0 ? -1 : 0, (kind & CASTER) != 0 ? -1 : 0,
                (kind & PLANT) != 0 ? -1 : 0, this.slotCover[slot] != 0 ? -1 : 0);
    }

    private void preparePalette(PackedCells packed) {
        int size = packed.paletteSize();
        if (size > this.paletteSlots.length) this.paletteSlots = new char[Integer.highestOneBit(size - 1) << 1];
        int[] states = packed.paletteStates();
        for (int i = 0; i < size; i++) this.paletteSlots[i] = (char) this.slotOf(states[i], 0);
    }

    private void classifyPackedRow(int row) {
        var packed = this.source;
        int cell = row << 5;
        char[] paletteSlots = this.paletteSlots;
        boolean coverLevel = this.level == PLANT_LEVEL;
        switch (packed.indexBytes()) {
            case 0 -> this.uniformRow(row, paletteSlots[0]);
            case 1 -> {
                var indices = packed.narrowIndices();
                long first = indices.get(PackedCells.EIGHT, cell);
                long same = (first & 0xFF) * 0x0101010101010101L;
                if (first == same && indices.get(PackedCells.EIGHT, cell + 8) == same
                        && indices.get(PackedCells.EIGHT, cell + 16) == same
                        && indices.get(PackedCells.EIGHT, cell + 24) == same) {
                    this.uniformRow(row, paletteSlots[(int) (first & 0xFF)]);
                    return;
                }
                int opaque = 0, partial = 0, fluid = 0, used = 0, caster = 0, plant = 0, cover = 0;
                for (int x = 0; x < 32; x++, cell++) {
                    int slot = paletteSlots[indices.get(ValueLayout.JAVA_BYTE, cell) & 0xFF];
                    this.slots[cell] = (char) slot;
                    int kind = this.slotKind[slot];
                    opaque |= (kind & OPAQUE) << x;
                    partial |= (kind >>> 1 & 1) << x;
                    fluid |= (kind >>> 2 & 1) << x;
                    used |= ((kind & DRAWN) != 0 ? 1 : 0) << x;
                    caster |= (kind >>> 5 & 1) << x;
                    plant |= (kind >>> 4 & 1) << x;
                    if (coverLevel) cover |= (this.slotCover[slot] != 0 ? 1 : 0) << x;
                }
                this.setRowClasses(row, opaque, partial, fluid, used, caster, plant, cover);
            }
            default -> {
                var indices = packed.widePage(cell / PackedCells.WIDE_PAGE_CELLS);
                int first = cell % PackedCells.WIDE_PAGE_CELLS - cell;
                int opaque = 0, partial = 0, fluid = 0, used = 0, caster = 0, plant = 0, cover = 0;
                for (int x = 0; x < 32; x++, cell++) {
                    int slot = paletteSlots[indices.getAtIndex(PackedCells.WIDE, first + cell)];
                    this.slots[cell] = (char) slot;
                    int kind = this.slotKind[slot];
                    opaque |= (kind & OPAQUE) << x;
                    partial |= (kind >>> 1 & 1) << x;
                    fluid |= (kind >>> 2 & 1) << x;
                    used |= ((kind & DRAWN) != 0 ? 1 : 0) << x;
                    caster |= (kind >>> 5 & 1) << x;
                    plant |= (kind >>> 4 & 1) << x;
                    if (coverLevel) cover |= (this.slotCover[slot] != 0 ? 1 : 0) << x;
                }
                this.setRowClasses(row, opaque, partial, fluid, used, caster, plant, cover);
            }
        }
    }

    private void setRowClasses(int row, int opaque, int partial, int fluid, int used, int caster, int plant, int cover) {
        this.opaqueRows[row] = opaque;
        this.partialRows[row] = partial;
        this.fluidRows[row] = fluid;
        this.usedRows[row] = used;
        this.casterRows[row] = caster;
        this.plantRows[row] = plant;
        this.coverRows[row] = cover;
    }

    private int slotOf(int state, int cell) {
        if (Voxel.isAir(state)) return 0;
        int block = Voxel.block(state);
        int hash = block & MEMO_MASK;
        if (this.memoBlock[hash] == block) return this.memoSlot[hash];
        int slot = this.resolve(block, cell);
        this.memoBlock[hash] = block;
        this.memoSlot[hash] = slot;
        return slot;
    }

    private int resolve(int block, int cell) {
        int id = this.models.modelOf(block);
        long info = 0;
        int kind = 0, fluid = 0;
        if (id < 0) {
            if (this.missingSeen.add(block)) this.missingBlocks.add(block);
            id = 0;
            kind = UNKNOWN;
        } else if (id != 0) {
            info = this.models.info(id);
            if (ModelInfo.leaf(info)) this.anyLeaves = true;
            boolean animated = this.animates(id);
            if (ModelInfo.isFluid(info)) {
                fluid = id;
                kind = FLUID;
            } else {
                if (ModelInfo.plant(info)) kind = this.level <= PLANT_LEVEL ? PLANT : 0;
                else kind = ModelInfo.opaqueCube(info) ? OPAQUE : PARTIAL;
                if (kind == PLANT && cell < VOLUME) this.anyPlants = true;
                if (ModelInfo.hasFluid(info)) {
                    // Published only after the fluid's own model is ready.
                    fluid = this.models.fluidModel(id);
                    animated |= this.animates(fluid);
                    kind |= FLUID;
                }
            }
            if (animated) this.animated = true;
        }
        if (ModelInfo.aoCaster(info)) kind |= CASTER;
        int cover = 0;
        if (this.level > 0 && this.level <= PLANT_LEVEL && id > 0) {
            int plant = this.world.ids().coverOf(block);
            if (plant != 0) {
                cover = this.models.modelOf(plant);
                if (cover < 0) {
                    if (this.missingSeen.add(plant)) this.missingBlocks.add(plant);
                    cover = 0;
                } else if (cover != 0 && !ModelInfo.plant(this.models.info(cover))) {
                    cover = 0;
                }
                if (cover != 0) this.anyPlants = true;
            }
        }
        int slot = this.slotCount++;
        if (slot == this.slotKind.length) {
            int grown = Math.min(slot * 2, CELLS + 1);
            this.slotKind = Arrays.copyOf(this.slotKind, grown);
            this.slotModel = Arrays.copyOf(this.slotModel, grown);
            this.slotFluid = Arrays.copyOf(this.slotFluid, grown);
            this.slotMeta = Arrays.copyOf(this.slotMeta, grown);
            this.slotCover = Arrays.copyOf(this.slotCover, grown);
        }
        this.slotKind[slot] = kind;
        this.slotModel[slot] = id;
        this.slotFluid[slot] = fluid;
        this.slotMeta[slot] = info;
        this.slotCover[slot] = cover;
        return slot;
    }

    private int model(int cell) {
        return this.slotModel[this.slots[cell]];
    }

    private long meta(int cell) {
        return this.slotMeta[this.slots[cell]];
    }

    private int fluidModel(int cell) {
        return this.slotFluid[this.slots[cell]];
    }

    private void markCaves(boolean caves) {
        Arrays.fill(this.caveRows, 0);
        Arrays.fill(this.coveredColumns, 0);
        if (!caves) return;
        this.markLitAir();
        for (int z = 0; z < 32; z++) {
            int covered = 0;
            for (int y = 31; y >= 0; y--) {
                int row = y * 32 + z;
                this.caveRows[row] = this.darkAir(covered & ~this.usedRows[row] & ~this.litAround(row), row << 5);
                covered |= this.opaqueRows[row];
            }
            this.coveredColumns[z] = covered;
        }
    }

    private void markLitAir() {
        Arrays.fill(this.litAirRows, 0);
        for (int row = 0; row < 1024; row++) {
            int lit = 0;
            for (int air = ~this.usedRows[row]; air != 0; air &= air - 1) {
                int bit = Integer.numberOfTrailingZeros(air);
                if (Voxel.skyLight(this.lightAt(row << 5 | bit)) != 0) lit |= 1 << bit;
            }
            this.litAirRows[row] = lit;
        }
    }

    private int litAround(int row) {
        int lit = this.litAirRows[row];
        int around = lit << 1 | lit >>> 1;
        int z = row & 31;
        if (z > 0) around |= this.litAirRows[row - 1];
        if (z < 31) around |= this.litAirRows[row + 1];
        if (row >= 32) around |= this.litAirRows[row - 32];
        if (row < 1024 - 32) around |= this.litAirRows[row + 32];
        return around;
    }

    private int darkAir(int cells, int first) {
        int dark = 0;
        for (; cells != 0; cells &= cells - 1) {
            int bit = Integer.numberOfTrailingZeros(cells);
            if (Voxel.skyLight(this.lightAt(first + bit)) == 0) dark |= 1 << bit;
        }
        return dark;
    }

    private void markBuried() {
        Arrays.fill(this.buriedRows, 0);
        if (this.level >= LEAF_SOLID_LEVEL || !this.anyLeaves) return;
        for (int y = 0; y < 32; y++) {
            for (int z = 0; z < 32; z++) {
                int row = y * 32 + z;
                int buried = 0;
                for (int x = 0; x < 32; x++) {
                    if (!ModelInfo.leaf(this.meta(row << 5 | x))) continue;
                    int face = 0;
                    while (face < 6 && this.leafAcross(face, x, y, z)) face++;
                    if (face == 6) buried |= 1 << x;
                }
                this.buriedRows[row] = buried;
            }
        }
    }

    private boolean leafAcross(int face, int x, int y, int z) {
        int cell = across(face, x, y, z);
        if (cell >= BORDER && !this.borderReady[(cell - BORDER) >> 10]) return false;
        return ModelInfo.leaf(this.meta(cell));
    }

    private boolean buried(int cell) {
        return cell < VOLUME && (this.buriedRows[cell >> 5] >>> (cell & 31) & 1) != 0;
    }

    private boolean insideCanopy(int voxel, int other, long otherInfo) {
        if (!ModelInfo.leaf(otherInfo)) return false;
        return this.level >= LEAF_SOLID_LEVEL || this.buried(voxel) && this.buried(other);
    }

    private boolean animates(int model) {
        long info = this.models.info(model);
        return ModelInfo.animated(info) && (this.level == 0 || ModelInfo.isFluid(info));
    }

    private boolean loadBorders(LodSection section, boolean yieldable, boolean caves) {
        for (int face = 0; face < 6; face++) {
            if (this.stop(yieldable)) return false;
            Arrays.fill(this.borderOpaque[face], 0);
            Arrays.fill(this.borderFluid[face], 0);
            Arrays.fill(this.borderCaster[face], 0);
            Arrays.fill(this.borderCave[face], 0);
            boolean used = this.sideUsed(face);
            this.borderReady[face] = used;
            if (!used) continue;

            int dx = face == 4 ? -1 : face == 5 ? 1 : 0;
            int dy = face == 0 ? -1 : face == 1 ? 1 : 0;
            int dz = face == 2 ? -1 : face == 3 ? 1 : 0;
            long looked = STAGES ? System.nanoTime() : 0;
            boolean present = this.loadLayer(face,
                    LodPos.pack(section.level, section.x + dx, section.y + dy, section.z + dz));
            if (STAGES) {
                STAGE_BORDER_LOOKUP.add(System.nanoTime() - looked);
                BORDER_FACES.increment();
                if (present) BORDER_PRESENT.increment();
            }
            for (int a = 0; a < 32; a++) {
                int first = BORDER + face * 1024 + a * 32;
                int opaque = 0, fluid = 0, caster = 0, drawn = 0;
                int state = this.states[first];
                int slot = this.slotOf(state, first);
                for (int b = 0; b < 32; b++) {
                    int cell = first + b;
                    int next = this.states[cell];
                    if (next != state) {
                        state = next;
                        slot = this.slotOf(state, cell);
                    }
                    this.slots[cell] = (char) slot;
                    int kind = this.slotKind[slot];
                    opaque |= (kind & OPAQUE) << b;
                    fluid |= (kind >>> 2 & 1) << b;
                    caster |= (kind >>> 5 & 1) << b;
                    drawn |= ((kind & DRAWN) != 0 ? 1 : 0) << b;
                }
                this.borderOpaque[face][a] = opaque;
                this.borderFluid[face][a] = fluid;
                this.borderCaster[face][a] = caster;
                this.borderUsed[a] = drawn;
            }
            long caved = STAGES ? System.nanoTime() : 0;
            if (caves && present) this.markBorderCaves(face);
            if (STAGES) STAGE_BORDER_CAVES.add(System.nanoTime() - caved);
        }
        return true;
    }

    private boolean loadLayer(int face, long position) {
        int first = BORDER + face * 1024;
        var neighbour = this.world.acquireIfStoredForReading(position);
        if (neighbour == null) {
            Arrays.fill(this.states, first, first + 1024, UNLOADED_STATE);
            Arrays.fill(this.lights, first, first + 1024, (byte) UNLOADED_LIGHT);
            return false;
        }
        try {
            int[] adjacent = ADJACENT[face];
            var packed = neighbour.packedCells();
            if (packed != null) {
                int[] paletteStates = packed.paletteStates();
                byte[] paletteLight = packed.paletteLight();
                for (int i = 0; i < 1024; i++) {
                    int index = packed.index(adjacent[i]);
                    this.states[first + i] = paletteStates[index];
                    this.lights[first + i] = paletteLight[index];
                }
                return true;
            }
            var data = neighbour.cells();
            if (face < 4) {
                for (int a = 0; a < 1024; a += 32) {
                    data.copyStates(adjacent[a], this.states, first + a, 32);
                    data.copyLight(adjacent[a], this.lights, first + a, 32);
                }
            } else {
                for (int i = 0; i < 1024; i++) {
                    this.states[first + i] = data.state(adjacent[i]);
                    this.lights[first + i] = (byte) data.light(adjacent[i]);
                }
            }
            return true;
        } finally {
            neighbour.release();
        }
    }

    private void markBorderCaves(int face) {
        int[] opaque = this.borderOpaque[face], cave = this.borderCave[face];
        int first = BORDER + face * 1024;
        for (int row = 0; row < 32; row++) {
            int lit = 0;
            for (int air = ~this.borderUsed[row]; air != 0; air &= air - 1) {
                int bit = Integer.numberOfTrailingZeros(air);
                if (Voxel.skyLight(this.lights[first + row * 32 + bit]) != 0) lit |= 1 << bit;
            }
            this.borderLitAir[row] = lit;
        }
        switch (face) {
            case 0 -> {
                for (int z = 0; z < 32; z++) {
                    cave[z] = this.darkAir(this.coveredColumns[z] & ~this.borderUsed[z] & ~this.borderLitAround(z),
                            first + z * 32);
                }
            }
            case 1 -> {}
            default -> {
                int covered = 0;
                for (int y = 31; y >= 0; y--) {
                    cave[y] = this.darkAir(covered & ~this.borderUsed[y] & ~this.borderLitAround(y), first + y * 32);
                    covered |= opaque[y];
                }
            }
        }
    }

    private int borderLitAround(int row) {
        int lit = this.borderLitAir[row];
        int around = lit << 1 | lit >>> 1;
        if (row > 0) around |= this.borderLitAir[row - 1];
        if (row < 31) around |= this.borderLitAir[row + 1];
        return around;
    }

    private boolean sideUsed(int face) {
        int any = 0;
        switch (face) {
            case 0 -> { for (int z = 0; z < 32; z++) any |= this.usedRows[z]; }
            case 1 -> { for (int z = 0; z < 32; z++) any |= this.usedRows[31 * 32 + z]; }
            case 2 -> { for (int y = 0; y < 32; y++) any |= this.usedRows[y * 32]; }
            case 3 -> { for (int y = 0; y < 32; y++) any |= this.usedRows[y * 32 + 31]; }
            case 4 -> { for (int row = 0; row < 1024; row++) any |= this.usedRows[row] & 1; }
            default -> { for (int row = 0; row < 1024; row++) any |= this.usedRows[row] >>> 31; }
        }
        return any != 0;
    }

    static final int[][] ADJACENT = new int[6][1024];

    static {
        for (int face = 0; face < 6; face++) {
            for (int a = 0; a < 32; a++) {
                for (int b = 0; b < 32; b++) ADJACENT[face][a * 32 + b] = adjacentVoxel(face, a, b);
            }
        }
    }

    private static int adjacentVoxel(int face, int a, int b) {
        return switch (face) {
            case 0 -> 31 << 10 | a << 5 | b;
            case 1 -> a << 5 | b;
            case 2 -> a << 10 | 31 << 5 | b;
            case 3 -> a << 10 | b;
            case 4 -> a << 10 | b << 5 | 31;
            default -> a << 10 | b << 5;
        };
    }

    private static int across(int face, int x, int y, int z) {
        int voxel = y << 10 | z << 5 | x;
        return switch (face) {
            case 0 -> y > 0 ? voxel - 1024 : BORDER + z * 32 + x;
            case 1 -> y < 31 ? voxel + 1024 : BORDER + 1024 + z * 32 + x;
            case 2 -> z > 0 ? voxel - 32 : BORDER + 2 * 1024 + y * 32 + x;
            case 3 -> z < 31 ? voxel + 32 : BORDER + 3 * 1024 + y * 32 + x;
            case 4 -> x > 0 ? voxel - 1 : BORDER + 4 * 1024 + y * 32 + z;
            default -> x < 31 ? voxel + 1 : BORDER + 5 * 1024 + y * 32 + z;
        };
    }

    private static int acrossRow(int[] rows, int[] border, int face, int y, int z) {
        int row = y * 32 + z;
        return switch (face) {
            case 0 -> y > 0 ? rows[row - 32] : border[z];
            case 1 -> y < 31 ? rows[row + 32] : border[z];
            case 2 -> z > 0 ? rows[row - 1] : border[y];
            case 3 -> z < 31 ? rows[row + 1] : border[y];
            case 4 -> rows[row] << 1 | border[y] >>> z & 1;
            default -> rows[row] >>> 1 | (border[y] >>> z & 1) << 31;
        };
    }

    private void meshFace(int face) {
        long started = STAGES ? System.nanoTime() : 0;
        Arrays.fill(this.faceRows, 0);
        Arrays.fill(this.fluidFaceRows, 0);
        int[] borderOpaque = this.borderOpaque[face];
        int[] borderFluid = this.borderFluid[face];
        int[] borderCave = this.borderCave[face];
        boolean any = false, anyFluids = false;
        for (int y = 0; y < 32; y++) {
            for (int z = 0; z < 32; z++) {
                int row = y * 32 + z;
                int opaque = this.opaqueRows[row], partial = this.partialRows[row], fluid = this.fluidRows[row];
                if ((opaque | partial | fluid) == 0) continue;
                int shown = this.caves ? ~acrossRow(this.caveRows, borderCave, face, y, z) : -1;
                int opaqueAcross = acrossRow(this.opaqueRows, borderOpaque, face, y, z);
                for (int candidates = (opaque & ~opaqueAcross | partial) & shown; candidates != 0; candidates &= candidates - 1) {
                    int x = Integer.numberOfTrailingZeros(candidates);
                    int voxel = row << 5 | x;
                    int other = across(face, x, y, z);
                    long cell = this.blockFace(voxel, other, face);
                    if (cell != 0) {
                        this.place(false, face, x, y, z, cell);
                        any = true;
                    }
                }
                int skirtBits = this.level == 0 ? 0 : skirtBits(face, z);
                if (skirtBits != 0) {
                    any |= this.placeSkirts(face, y, z, skirtBits, opaque, fluid, opaqueAcross);
                    anyFluids |= this.placeFluidSkirts(face, y, z, skirtBits, fluid, opaqueAcross);
                }
                if (fluid == 0) continue;
                int fluidAcross = acrossRow(this.fluidRows, borderFluid, face, y, z);
                for (int candidates = (fluid & ~(fluidAcross | opaqueAcross)) & shown; candidates != 0; candidates &= candidates - 1) {
                    int x = Integer.numberOfTrailingZeros(candidates);
                    int voxel = row << 5 | x;
                    int other = across(face, x, y, z);
                    long cell = this.fluidFace(voxel, other, face);
                    if (cell != 0) {
                        this.place(true, face, x, y, z, cell);
                        anyFluids = true;
                    }
                }
            }
        }
        long faced = STAGES ? System.nanoTime() : 0;
        if (any) this.mergeFace(face, false);
        if (anyFluids) this.mergeFace(face, true);
        if (STAGES) {
            STAGE_FACES.add(faced - started);
            STAGE_MERGE.add(System.nanoTime() - faced);
        }
    }

    private long blockFace(int voxel, int other, int face) {
        char slot = this.slots[voxel], otherSlot = this.slots[other];
        long info = this.slotMeta[slot], otherInfo = this.slotMeta[otherSlot];
        int id = this.slotModel[slot];
        if (ModelInfo.leaf(info) && this.insideCanopy(voxel, other, otherInfo)) return 0;
        boolean sameBlock = id == this.slotModel[otherSlot] && ModelInfo.cullsSelf(info);
        if (ModelInfo.opaqueCube(info)) {
            if (sameBlock || ModelInfo.occludes(otherInfo, face ^ 1)) return 0;
            return this.cellValue(id, info, voxel, this.lightAt(other),
                    this.ambientOcclusion(info, face, voxel, true));
        }
        if (!ModelInfo.hasFace(info, face) || sameBlock) return 0;
        if (ModelInfo.hideable(info, face) && ModelInfo.occludes(otherInfo, face ^ 1)) return 0;
        return this.cellValue(id, info, voxel, this.lightAt(ModelInfo.selfLit(info, face) ? voxel : other),
                this.ambientOcclusion(info, face, voxel, ModelInfo.flush(info, face)));
    }

    private long fluidFace(int voxel, int other, int face) {
        if (ModelInfo.occludes(this.meta(other), face ^ 1)) return 0;
        int id = this.fluidModel(voxel);
        if (id != this.model(voxel) && ModelInfo.occludes(this.meta(voxel), face)) return 0;
        long value = this.cellValue(id, this.models.info(id), voxel, this.fluidLight(voxel), this.fluidFlow(id, voxel, face));
        return this.level == 0 && face != 0 ? this.withCorners(value, id, voxel, face) : value;
    }

    private static int skirtBits(int face, int z) {
        return switch (face) {
            case 2 -> z == 0 ? -1 : 0;
            case 3 -> z == 31 ? -1 : 0;
            case 4 -> 1;
            case 5 -> 1 << 31;
            default -> 0;
        };
    }

    private int aboveAcross(int[] rows, int[][] border, int face, int y, int z) {
        return y < 31 ? acrossRow(rows, border[face], face, y + 1, z) : border[1][z];
    }

    private boolean placeSkirts(int face, int y, int z, int skirtBits, int opaque, int fluid, int opaqueAcross) {
        int candidates = opaque & opaqueAcross & ~this.aboveAcross(this.opaqueRows, this.borderOpaque, face, y, z) & skirtBits;
        boolean any = false;
        for (; candidates != 0; candidates &= candidates - 1) {
            int x = Integer.numberOfTrailingZeros(candidates);
            int voxel = (y * 32 + z) << 5 | x;
            int id = this.model(voxel);
            long info = this.meta(voxel);
            if (id == 0 || !ModelInfo.hasFace(info, face)) continue;
            int lit = y < 31 ? across(face, x, y + 1, z) : across(1, x, y, z);
            this.place(false, face, x, y, z, this.cellValue(id, info, voxel, this.lightAt(lit), 0));
            any = true;
        }
        return any;
    }

    private boolean placeFluidSkirts(int face, int y, int z, int skirtBits, int fluid, int opaqueAcross) {
        if (fluid == 0) return false;
        int fluidAcross = acrossRow(this.fluidRows, this.borderFluid[face], face, y, z);
        int above = this.aboveAcross(this.fluidRows, this.borderFluid, face, y, z)
                | this.aboveAcross(this.opaqueRows, this.borderOpaque, face, y, z);
        int candidates = fluid & fluidAcross & ~opaqueAcross & ~above & skirtBits;
        boolean any = false;
        for (; candidates != 0; candidates &= candidates - 1) {
            int x = Integer.numberOfTrailingZeros(candidates);
            int voxel = (y * 32 + z) << 5 | x;
            int id = this.fluidModel(voxel);
            if (id != this.model(voxel) && ModelInfo.occludes(this.meta(voxel), face)) continue;
            long value = this.cellValue(id, this.models.info(id), voxel, this.fluidLight(voxel), 0);
            this.place(true, face, x, y, z, value | FLUID_SKIRT);
            any = true;
        }
        return any;
    }

    private long withCorners(long value, int model, int voxel, int face) {
        int x = voxel & 31, y = voxel >>> 10, z = voxel >>> 5 & 31;
        int kind = this.models.fluidKind(model);
        float own = this.fluidHeight(kind, x, y, z);
        float northWest = 1, northEast = 1, southWest = 1, southEast = 1;
        if (own < 1) {
            float north = this.fluidHeight(kind, x, y, z - 1), south = this.fluidHeight(kind, x, y, z + 1);
            float west = this.fluidHeight(kind, x - 1, y, z), east = this.fluidHeight(kind, x + 1, y, z);
            northWest = this.fluidCorner(kind, own, north, west, x - 1, y, z - 1);
            northEast = this.fluidCorner(kind, own, north, east, x + 1, y, z - 1);
            southWest = this.fluidCorner(kind, own, south, west, x - 1, y, z + 1);
            southEast = this.fluidCorner(kind, own, south, east, x + 1, y, z + 1);
        }
        boolean baked = switch (face) {
            case 1 -> {
                float surface = ownHeight(this.models.flowLevel(model));
                yield same(northWest, surface) && same(northEast, surface) && same(southWest, surface)
                        && same(southEast, surface);
            }
            case 2 -> same(northWest, 1) && same(northEast, 1);
            case 3 -> same(southWest, 1) && same(southEast, 1);
            case 4 -> same(northWest, 1) && same(southWest, 1);
            default -> same(northEast, 1) && same(southEast, 1);
        };
        if (baked) return value;

        // Pack NW / NE corner codes in width / height bits (3-8) and SW / SE in bits 57-62 (terrain/quad.glsl)
        return value & ~(0x7FL << 57) | FLUID_CORNERS
                | (long) heightCode(northWest) << 3 | (long) heightCode(northEast) << 6
                | (long) heightCode(southWest) << 57 | (long) heightCode(southEast) << 60;
    }

    private float fluidHeight(int kind, int x, int y, int z) {
        int cell = this.cellAt(x, y, z);
        if (cell < 0) return -1;
        int fluid = this.fluidModel(cell);
        if (fluid != 0 && this.models.fluidKind(fluid) == kind) {
            int above = this.cellAt(x, y + 1, z);
            if (above >= 0 && this.fluidModel(above) != 0 && this.models.fluidKind(this.fluidModel(above)) == kind) return 1;
            return ownHeight(this.models.flowLevel(fluid));
        }
        int id = this.model(cell);
        return id == 0 || ModelInfo.plant(this.meta(cell)) ? 0 : -1;
    }

    private float fluidCorner(int kind, float own, float sideA, float sideB, int x, int y, int z) {
        if (sideA >= 1 || sideB >= 1) return 1;
        long total = 0;
        if (sideA > 0 || sideB > 0) {
            float diagonal = this.fluidHeight(kind, x, y, z);
            if (diagonal >= 1) return 1;
            total = weigh(total, diagonal);
        }
        total = weigh(weigh(weigh(total, own), sideA), sideB);
        return Float.intBitsToFloat((int) (total >>> 32)) / (int) total;
    }

    private static long weigh(long total, float height) {
        if (height < 0) return total;
        int weight = height >= 0.8f ? 10 : 1;
        float sum = Float.intBitsToFloat((int) (total >>> 32)) + height * weight;
        return (long) Float.floatToRawIntBits(sum) << 32 | (int) total + weight;
    }

    private static float ownHeight(int flowLevel) {
        int amount = flowLevel <= 0 || flowLevel >= 8 ? 8 : 8 - flowLevel;
        return amount / 9f;
    }

    private static boolean same(float a, float b) {
        return Math.abs(a - b) < 0.01f;
    }

    private static int heightCode(float height) {
        int best = 0;
        for (int i = 1; i < FLUID_HEIGHTS.length; i++) {
            if (Math.abs(FLUID_HEIGHTS[i] - height) < Math.abs(FLUID_HEIGHTS[best] - height)) best = i;
        }
        return best;
    }

    // Quarter turn flow directions for terrain shader: 0=east, 1=north, 2=west, 3=south
    private static final int[] FLOW_TURN_DX = {1, 0, -1, 0}, FLOW_TURN_DZ = {0, -1, 0, 1};

    private int fluidFlow(int model, int voxel, int face) {
        if (face != 1 || this.level != 0) return 0;
        int level = this.models.flowLevel(model);
        if (level <= 0) return 0;
        int x = voxel & 31, y = voxel >>> 10, z = voxel >>> 5 & 31;
        int downstream = -1, upstream = -1, deepest = level, shallowest = level;
        for (int i = 0; i < 4; i++) {
            int cell = this.cellAt(x + FLOW_TURN_DX[i], y, z + FLOW_TURN_DZ[i]);
            if (cell < 0) continue;
            int neighbour = this.fluidModel(cell);
            if (neighbour == 0) continue;
            int neighbourLevel = this.models.flowLevel(neighbour);
            if (neighbourLevel < 0) continue;
            if (neighbourLevel > deepest) {
                deepest = neighbourLevel;
                downstream = i;
            } else if (neighbourLevel < shallowest) {
                shallowest = neighbourLevel;
                upstream = i;
            }
        }
        if (downstream >= 0) return downstream;
        return upstream >= 0 ? upstream : 0;
    }

    private int cellAt(int x, int y, int z) {
        boolean outX = (x & ~31) != 0, outY = (y & ~31) != 0, outZ = (z & ~31) != 0;
        if (!(outX | outY | outZ)) return y << 10 | z << 5 | x;
        if ((outX ? 1 : 0) + (outY ? 1 : 0) + (outZ ? 1 : 0) > 1) return -1;
        int face = outY ? y < 0 ? 0 : 1 : outZ ? z < 0 ? 2 : 3 : x < 0 ? 4 : 5;
        if (!this.borderReady[face]) return -1;
        return BORDER + face * 1024 + (outY ? z * 32 + x : outZ ? y * 32 + x : y * 32 + z);
    }

    private int fluidLight(int voxel) {
        return Math.max(this.lightAt(voxel), this.lightAt(across(1, voxel & 31, voxel >> 10, voxel >> 5 & 31)));
    }

    private int lightAt(int cell) {
        var source = this.source;
        return cell < VOLUME && source != null ? source.light(cell) : this.lights[cell] & 0xFF;
    }

    private int stateAt(int cell) {
        var source = this.source;
        return cell < VOLUME && source != null ? source.state(cell) : this.states[cell];
    }

    private long cellValue(int id, long info, int voxel, int light, int occlusion) {
        long value = (long) id << 32 | (long) occlusion << 57;
        if (ModelInfo.biomeTinted(info)) {
            value |= (long) this.biomeWord(id, info, voxel) << 48;
        }
        int emitted = ModelInfo.lightEmission(info);
        if (emitted > light >>> 4) {
            light = emitted << 4 | light & 0xF;
        }
        return value | (long) light << 24;
    }

    private int biomeWord(int model, long info, int voxel) {
        int biome = Voxel.biome(this.stateAt(voxel));
        if (this.level != 0 || !ModelInfo.isFluid(info) || biome >= BlockModels.BIOME_LIMIT) return biome;
        int[] row = this.models.blendRow(model);
        if (row == null) return biome;

        int x = voxel & 31, y = voxel >>> 10, z = voxel >>> 5 & 31;
        int red = 0, green = 0, blue = 0;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int colour = row[this.biomeAt(x + dx, y, z + dz, biome)];
                red += colour >> 16 & 0xFF;
                green += colour >> 8 & 0xFF;
                blue += colour & 0xFF;
            }
        }
        int blended = 0xFF000000 | (red / 9) << 16 | (green / 9) << 8 | blue / 9;
        long key = (long) model << 32 | (blended & 0xFFFFFFFFL);
        int slot = this.blendCache.get(key);
        if (slot < 0) {
            slot = this.models.blendSlot(model, blended);
            this.blendCache.put(key, slot);
        }
        return slot;
    }

    private int biomeAt(int x, int y, int z, int fallback) {
        boolean outX = x < 0 || x > 31, outZ = z < 0 || z > 31;
        if (outX && outZ) return fallback;
        int cell;
        if (!outX && !outZ) {
            cell = y << 10 | z << 5 | x;
        } else {
            int face = outX ? x < 0 ? 4 : 5 : z < 0 ? 2 : 3;
            if (!this.borderReady[face]) return fallback;
            cell = BORDER + face * 1024 + y * 32 + (outX ? z : x);
        }
        int state = this.stateAt(cell);
        return Voxel.isAir(state) ? fallback : Voxel.biome(state);
    }

    private int ambientOcclusion(long info, int face, int voxel, boolean inFront) {
        if (!ModelInfo.usesAo(info)) return 0;
        int x = voxel & 31, y = voxel >>> 10, z = voxel >>> 5 & 31;
        if (inFront) {
            switch (face) {
                case 0 -> y--;
                case 1 -> y++;
                case 2 -> z--;
                case 3 -> z++;
                case 4 -> x--;
                default -> x++;
            }
        }
        return AmbientOcclusion.code(switch (face >> 1) {
            case 0 -> this.maskOverZ(x, y, z);
            case 1 -> this.maskOverY(x, y, z);
            default -> this.maskOverX(x, y, z);
        });
    }

    private int maskOverZ(int x, int y, int z) {
        int mask = this.maskAlongX(x, this.casterRow(y, z - 1), this.casterRow(y, z), this.casterRow(y, z + 1));
        if ((y & ~31) == 0 && (x == 0 || x == 31)) {
            mask |= this.edgeBits(this.borderCaster[x == 0 ? 4 : 5][y], z, x == 0 ? 0 : 1);
        }
        return mask;
    }

    private int maskOverY(int x, int y, int z) {
        int mask = this.maskAlongX(x, this.casterRow(y - 1, z), this.casterRow(y, z), this.casterRow(y + 1, z));
        if ((z & ~31) == 0 && (x == 0 || x == 31)) {
            int side = x == 0 ? 4 : 5, bit = x == 0 ? 0 : 1;
            mask |= (this.borderCaster[side][y] >>> z & 1) << bit;
            if (y > 0) mask |= (this.borderCaster[side][y - 1] >>> z & 1) << (bit + 4);
            if (y < 31) mask |= (this.borderCaster[side][y + 1] >>> z & 1) << (bit + 6);
        }
        return mask;
    }

    private int edgeBits(int beside, int z, int bit) {
        int mask = (beside >>> z & 1) << bit;
        if (z > 0) mask |= (beside >>> (z - 1) & 1) << (bit + 4);
        if (z < 31) mask |= (beside >>> (z + 1) & 1) << (bit + 6);
        return mask;
    }

    private int maskAlongX(int x, int below, int middle, int above) {
        return (middle << 1 >>> x & 1) | (middle >>> 1 >>> x & 1) << 1
                | (below >>> x & 1) << 2 | (above >>> x & 1) << 3
                | (below << 1 >>> x & 1) << 4 | (below >>> 1 >>> x & 1) << 5
                | (above << 1 >>> x & 1) << 6 | (above >>> 1 >>> x & 1) << 7;
    }

    private int maskOverX(int x, int y, int z) {
        if ((x & ~31) != 0) return this.borderMask(x < 0 ? 4 : 5, y, z);
        return (this.casterRow(y - 1, z) >>> x & 1) | (this.casterRow(y + 1, z) >>> x & 1) << 1
                | (this.casterRow(y, z - 1) >>> x & 1) << 2 | (this.casterRow(y, z + 1) >>> x & 1) << 3
                | (this.casterRow(y - 1, z - 1) >>> x & 1) << 4 | (this.casterRow(y + 1, z - 1) >>> x & 1) << 5
                | (this.casterRow(y - 1, z + 1) >>> x & 1) << 6 | (this.casterRow(y + 1, z + 1) >>> x & 1) << 7;
    }

    private int borderMask(int side, int y, int z) {
        int[] layer = this.borderCaster[side];
        int mask = 0;
        if (y > 0) {
            if ((layer[y - 1] >>> z & 1) != 0) mask |= 1;
            if (z > 0 && (layer[y - 1] >>> (z - 1) & 1) != 0) mask |= 16;
            if (z < 31 && (layer[y - 1] >>> (z + 1) & 1) != 0) mask |= 64;
        }
        if (y < 31) {
            if ((layer[y + 1] >>> z & 1) != 0) mask |= 2;
            if (z > 0 && (layer[y + 1] >>> (z - 1) & 1) != 0) mask |= 32;
            if (z < 31 && (layer[y + 1] >>> (z + 1) & 1) != 0) mask |= 128;
        }
        if (z > 0 && (layer[y] >>> (z - 1) & 1) != 0) mask |= 4;
        if (z < 31 && (layer[y] >>> (z + 1) & 1) != 0) mask |= 8;
        return mask;
    }

    private int casterRow(int y, int z) {
        boolean outY = (y & ~31) != 0, outZ = (z & ~31) != 0;
        if (!(outY | outZ)) return this.casterRows[y * 32 + z];
        if (outY && outZ) return 0;
        return outY ? this.borderCaster[y < 0 ? 0 : 1][z] : this.borderCaster[z < 0 ? 2 : 3][y];
    }

    private void place(boolean fluid, int face, int x, int y, int z, long cell) {
        int layer, u, v;
        switch (face >> 1) {
            case 0 -> { layer = y; u = x; v = z; }
            case 1 -> { layer = z; u = x; v = y; }
            default -> { layer = x; u = y; v = z; }
        }
        int row = layer * 32 + v;
        int[] rows = fluid ? this.fluidFaceRows : this.faceRows;
        long[] cells = fluid ? this.fluidFaceCells : this.faceCells;
        rows[row] |= 1 << u;
        cells[row << 5 | u] = cell;
    }

    private void mergeFace(int face, boolean fluids) {
        if (this.countBreaks && !fluids) this.countMergeBreaks();
        int[] rows = fluids ? this.fluidFaceRows : this.faceRows;
        long[] cells = fluids ? this.fluidFaceCells : this.faceCells;
        int lastModel = -1;
        long lastSame = -1;
        for (int row = 0; row < 1024; row++) {
            int bits = rows[row];
            int v = row & 31;
            while (bits != 0) {
                int u = Integer.numberOfTrailingZeros(bits);
                long cell = cells[row << 5 | u];
                if ((cell & FLUID_CORNERS) != 0 && ModelInfo.isFluid(this.models.info((int) (cell >>> 32 & 0xFFFF)))) {
                    bits &= ~(1 << u);
                    this.emit(face, row >> 5, u, v, 1, 1, cell);
                    continue;
                }
                int model = (int) (cell >>> 32 & 0xFFFF);
                if (model != lastModel) {
                    lastModel = model;
                    lastSame = fluids || ModelInfo.translucent(this.models.info(model)) ? -1L : ~(AO_BITS | LIGHT_BITS);
                }
                long same = lastSame, key = cell & same;
                long differ = 0;
                int width = 1;
                while (width < MAX_QUAD_SIZE && u + width < 32 && (bits >>> (u + width) & 1) != 0) {
                    long next = cells[row << 5 | (u + width)];
                    if ((next & same) != key) break;
                    differ |= next ^ cell;
                    width++;
                }
                int span = ((1 << width) - 1) << u;
                bits &= ~span;
                int height = 1;
                while (height < MAX_QUAD_SIZE && v + height < 32) {
                    long rowDiffer = this.rowDiffers(rows, cells, row + height, u, width, span, key, same, cell);
                    if (rowDiffer == ROW_ENDS) break;
                    differ |= rowDiffer;
                    rows[row + height] &= ~span;
                    height++;
                }
                if (differ != 0) {
                    this.emitTabled(cells, face, row, u, width, height, cell);
                } else {
                    this.emit(face, row >> 5, u, v, width, height, cell);
                }
            }
        }
    }

    private void countMergeBreaks() {
        for (int row = 0; row < 1024; row++) {
            int bits = this.faceRows[row];
            if (bits == 0) continue;
            int v = row & 31;
            int before = v > 0 ? this.faceRows[row - 1] : 0, after = v < 31 ? this.faceRows[row + 1] : 0;
            int alone = bits & ~(bits << 1) & ~(bits >>> 1) & ~before & ~after;
            MERGE_FACES[0] += Integer.bitCount(bits);
            MERGE_FACES[1] += Integer.bitCount(alone);
            for (int along = bits & bits >>> 1; along != 0; along &= along - 1) {
                int u = Integer.numberOfTrailingZeros(along);
                MERGE_BREAKS[breakFields(this.faceCells[row << 5 | u], this.faceCells[row << 5 | u + 1])]++;
            }
            for (int across = bits & after; across != 0; across &= across - 1) {
                int u = Integer.numberOfTrailingZeros(across);
                MERGE_BREAKS[breakFields(this.faceCells[row << 5 | u], this.faceCells[(row + 1) << 5 | u])]++;
            }
        }
    }

    private static int breakFields(long a, long b) {
        long differ = a ^ b;
        return ((differ & 0xFFL << 24) != 0 ? 1 : 0)
                | ((differ & 0xFFFFL << 32) != 0 ? 2 : 0)
                | ((differ & 0x1FFL << 48) != 0 ? 4 : 0)
                | ((differ & 0x7FL << 57) != 0 ? 8 : 0)
                | ((differ & 0xFFFFFFL) != 0 ? 16 : 0);
    }

    private static final long ROW_ENDS = -1L;

    private long rowDiffers(int[] rows, long[] cells, int row, int u, int width, int span, long key, long same, long first) {
        if ((rows[row] & span) != span) return ROW_ENDS;
        int base = row << 5 | u;
        long differ = 0;
        for (int i = 0; i < width; i++) {
            long cell = cells[base + i];
            if ((cell & same) != key) return ROW_ENDS;
            differ |= cell ^ first;
        }
        return differ;
    }

    private void emitTabled(long[] grid, int face, int row, int u, int width, int height, long cell) {
        int cells = width * height;
        if (this.tableCount == this.tableGroup.length) {
            this.tableGroup = Arrays.copyOf(this.tableGroup, this.tableCount * 2);
            this.tableStart = Arrays.copyOf(this.tableStart, this.tableCount * 2);
        }
        if (this.tableCodeCount + cells > this.tableCodes.length) {
            this.tableCodes = Arrays.copyOf(this.tableCodes, Math.max(this.tableCodes.length * 2, this.tableCodeCount + cells));
        }
        int group = this.emitQuad(face, row >> 5, u, row & 31, width, height, cell & ~(AO_BITS | LIGHT_BITS) | (long) AO_TABLE << 57, true);
        if (group < 0) return;
        this.tableGroup[this.tableCount] = group;
        this.tableStart[this.tableCount++] = this.tableCodeCount;
        // Row-major (v then u) order matches terrain.frag table lookup.
        for (int r = 0; r < height; r++) {
            int base = (row + r) << 5 | u;
            for (int i = 0; i < width; i++) {
                long each = grid[base + i];
                this.tableCodes[this.tableCodeCount++] = (short) ((each >>> 24 & 0xFF) << 8 | each >>> 57);
            }
        }
    }

    private void emit(int face, int layer, int u, int v, int width, int height, long cell) {
        this.emitQuad(face, layer, u, v, width, height, cell, false);
    }

    private int emitQuad(int face, int layer, int u, int v, int width, int height, long cell, boolean tabled) {
        int x, y, z, sizeX, sizeY, sizeZ;
        switch (face >> 1) {
            case 0 -> { x = u; y = layer; z = v; sizeX = width; sizeY = 1; sizeZ = height; }
            case 1 -> { x = u; y = v; z = layer; sizeX = width; sizeY = height; sizeZ = 1; }
            default -> { x = layer; y = u; z = v; sizeX = 1; sizeY = width; sizeZ = height; }
        }
        long info = this.models.info((int) (cell >>> 32 & 0xFFFF));
        int group = ModelInfo.translucent(info) ? LodMesh.GROUP_TRANSLUCENT
                : ModelInfo.doubleSided(info) ? LodMesh.GROUP_DOUBLE_SIDED
                : LodMesh.GROUP_FIRST_FACE + face;
        this.noteDiscard(info, face, width, height, group);
        if (this.groupSizes[group] + this.tabledSizes[group] == GROUP_LIMIT) {
            this.warnOverflow();
            return -1;
        }
        long quad = cell | face | (width - 1) << 3 | (height - 1) << 6 | z << 9 | y << 14 | x << 19;
        if (tabled) {
            int size = this.tabledSizes[group];
            long[] quads = this.tabled[group];
            if (quads == null || size == quads.length) {
                this.tabled[group] = quads = quads == null ? new long[256] : Arrays.copyOf(quads, Math.min(quads.length * 2, GROUP_LIMIT));
            }
            quads[size] = quad;
            this.tabledSizes[group] = size + 1;
        } else {
            int size = this.groupSizes[group];
            long[] quads = this.groups[group];
            if (size == quads.length) {
                this.groups[group] = quads = Arrays.copyOf(quads, Math.min(quads.length * 2, GROUP_LIMIT));
            }
            quads[size] = quad;
            this.groupSizes[group] = size + 1;
        }
        if (STAGES) MERGE_SIZES[(width - 1) * MAX_QUAD_SIZE + height - 1]++;

        this.minX = Math.min(this.minX, x);
        this.minY = Math.min(this.minY, y);
        this.minZ = Math.min(this.minZ, z);
        this.maxX = Math.max(this.maxX, x + sizeX);
        this.maxY = Math.max(this.maxY, y + sizeY);
        this.maxZ = Math.max(this.maxZ, z + sizeZ);
        return group;
    }

    private void meshPlants() {
        boolean coarse = this.level > 0;
        for (int row = 0; row < 1024; row++) {
            int candidates = this.plantRows[row];
            if (coarse) candidates |= row >= 32 ? this.coverRows[row - 32] : this.coverRowBelow(row);
            for (int bits = candidates; bits != 0; bits &= bits - 1) {
                int voxel = row << 5 | Integer.numberOfTrailingZeros(bits);
                long info = this.meta(voxel);
                if (ModelInfo.plant(info)) {
                    long cell = this.cellValue(this.model(voxel), info, voxel, this.lightAt(voxel), 0);
                    if (!coarse) {
                        this.addCross(voxel, cell, info, 0);
                    } else {
                        int above = voxel < VOLUME - 1024 ? voxel + 1024 : this.borderReady[1] ? across(1, voxel & 31, 31, voxel >>> 5 & 31) : -1;
                        boolean stalk = above >= 0 && ModelInfo.plant(this.meta(above));
                        this.addCoarseCrosses(voxel, cell, info, stalk);
                    }
                    continue;
                }
                if (!coarse || (this.slotKind[this.slots[voxel]] & (OPAQUE | PARTIAL | UNKNOWN)) != 0) continue;
                int y = voxel >>> 10;
                int below = y > 0 ? voxel - 1024 : this.borderReady[0] ? across(0, voxel & 31, 0, voxel >>> 5 & 31) : -1;
                if (below < 0) continue;
                int plant = this.slotCover[this.slots[below]];
                if (plant == 0) continue;
                long plantInfo = this.models.info(plant);
                long cell = this.cellValue(plant, plantInfo, below, this.lightAt(voxel), 0);
                this.addCoarseCrosses(voxel, cell, plantInfo, false);
            }
        }
    }

    private int coverRowBelow(int row) {
        if (!this.borderReady[0]) return 0;
        int first = BORDER + (row << 5);
        int mask = 0;
        for (int x = 0; x < 32; x++) {
            if (this.slotCover[this.slots[first + x]] != 0) mask |= 1 << x;
        }
        return mask;
    }

    // Sub-voxel column and stalk bit go into height field (terrain/expand.glsl).
    private void addCoarseCrosses(int voxel, long cell, long info, boolean stalk) {
        int tall = stalk ? 1 : 0;
        this.addCross(voxel, cell, info, tall);
        this.addCross(voxel, cell, info, tall | 1 << 1 | 1 << 2);
    }

    private void addCross(int voxel, long cell, long info, int spot) {
        int height = spot << 6;
        this.addQuad(LodMesh.GROUP_DOUBLE_SIDED, 6 | height, voxel, cell);
        this.addQuad(LodMesh.GROUP_DOUBLE_SIDED, 7 | height, voxel, cell);
        if (ModelInfo.fourPlanes(info)) {
            this.addQuad(LodMesh.GROUP_DOUBLE_SIDED, 6 | PLANT_SECOND_PAIR | height, voxel, cell);
            this.addQuad(LodMesh.GROUP_DOUBLE_SIDED, 7 | PLANT_SECOND_PAIR | height, voxel, cell);
        }
    }

    private void addQuad(int group, int face, int voxel, long cell) {
        int size = this.groupSizes[group];
        if (size + this.tabledSizes[group] == GROUP_LIMIT) {
            this.warnOverflow();
            return;
        }
        long[] quads = this.groups[group];
        if (size == quads.length) {
            this.groups[group] = quads = Arrays.copyOf(quads, Math.min(quads.length * 2, GROUP_LIMIT));
        }
        int x = voxel & 31, y = voxel >>> 10, z = voxel >>> 5 & 31;
        quads[size] = cell | face | z << 9 | y << 14 | x << 19;
        this.groupSizes[group] = size + 1;
        this.minX = Math.min(this.minX, x);
        this.minY = Math.min(this.minY, y);
        this.minZ = Math.min(this.minZ, z);
        this.maxX = Math.max(this.maxX, x + 1);
        this.maxY = Math.max(this.maxY, y + 1);
        this.maxZ = Math.max(this.maxZ, z + 1);
    }

    private void noteDiscard(long info, int face, int width, int height, int group) {
        if (ModelInfo.translucent(info)) return;
        if (ModelInfo.plant(info)) {
            this.discardGroups |= 1 << group;
            this.anyDiscard = true;
            return;
        }
        if (ModelInfo.leaf(info) && this.level >= LEAF_SOLID_LEVEL) return;
        if (ModelInfo.alphaCutout(info, face)
                || (width > 1 || height > 1) && ModelInfo.stretchedCutout(info, face)) {
            this.discardGroups |= 1 << group;
            this.anyDiscard = true;
        }
    }

    private void warnOverflow() {
        if (!this.warnedOverflow) {
            this.warnedOverflow = true;
            Log.warn("LoD section exceeded " + GROUP_LIMIT + " quads in one group; truncating");
        }
    }

    // Trailing table layout (terrain/geometry.glsl aoTableOf): groupStart[8], firstTable[8], tableCount, markOffsets[], 16-bit entries (light<<8|ao).
    private LodMesh finish(long position, byte childMask) {
        int[] sizes = new int[LodMesh.GROUPS];
        int total = 0;
        for (int group = 0; group < LodMesh.GROUPS; group++) {
            sizes[group] = this.groupSizes[group] + this.tabledSizes[group];
            total += sizes[group];
        }
        if (total == 0) {
            return LodMesh.empty(position, childMask);
        }
        int tables = this.tableCount;
        int headWords = 2 * LodMesh.GROUPS + 1 + (tables + TABLE_MARK_STEP - 1) / TABLE_MARK_STEP;
        int words = tables > 0 ? headWords + (this.tableCodeCount + 1) / 2 : 0;
        var buffer = new NativeBuffer((total + (words + 1) / 2) * 8L);
        long at = buffer.address;
        for (int group = 0; group < LodMesh.GROUPS; group++) {
            int tabled = this.tabledSizes[group], size = this.groupSizes[group];
            if (tabled > 0) MemoryUtil.memLongBuffer(at, tabled).put(this.tabled[group], 0, tabled);
            at += tabled * 8L;
            MemoryUtil.memLongBuffer(at, size).put(this.groups[group], 0, size);
            at += size * 8L;
        }
        if (tables > 0) {
            long table = at;
            MemoryUtil.memSet(table, 0, buffer.size - total * 8L);
            MemoryUtil.memPutInt(table + 2L * LodMesh.GROUPS * 4, tables);
            int groupStart = 0, number = 0, entry = headWords * 2;
            for (int group = 0; group < LodMesh.GROUPS; group++) {
                MemoryUtil.memPutInt(table + group * 4L, total - groupStart);
                MemoryUtil.memPutInt(table + (LodMesh.GROUPS + group) * 4L, number);
                long quad = buffer.address + groupStart * 8L;
                for (int t = 0; t < tables; t++) {
                    if (this.tableGroup[t] != group) continue;
                    int length = this.tableLength(t), start = this.tableStart[t];
                    if (number % TABLE_MARK_STEP == 0) {
                        MemoryUtil.memPutInt(table + (2L * LodMesh.GROUPS + 1 + number / TABLE_MARK_STEP) * 4, entry);
                    }
                    MemoryUtil.memPutByte(quad + 3, (byte) entry);
                    for (int i = 0; i < length; i++) MemoryUtil.memPutShort(table + (entry + i) * 2L, this.tableCodes[start + i]);
                    entry += length;
                    number++;
                    quad += 8;
                }
                groupStart += sizes[group];
            }
        }
        int bounds = this.minX | this.minY << 5 | this.minZ << 10
                | (this.maxX - this.minX - 1) << 15 | (this.maxY - this.minY - 1) << 20 | (this.maxZ - this.minZ - 1) << 25
                | (this.animated ? LodMesh.ANIMATED : 0) | (this.anyDiscard ? LodMesh.MAY_DISCARD : 0);
        return new LodMesh(position, childMask, bounds, this.discardGroups, buffer, sizes, tables > 0 ? total : 0);
    }

    private int tableLength(int table) {
        int end = table + 1 < this.tableCount ? this.tableStart[table + 1] : this.tableCodeCount;
        return end - this.tableStart[table];
    }
}
