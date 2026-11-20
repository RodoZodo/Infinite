package rodozodo.infinite.importers;

import com.mojang.serialization.Codec;
import com.mojang.serialization.Dynamic;
import rodozodo.infinite.Log;
import rodozodo.infinite.thread.WorkerPool;
import rodozodo.infinite.world.LodWorld;
import rodozodo.infinite.world.SectionWriter;
import rodozodo.infinite.world.voxelize.ChunkVoxelizer;
import rodozodo.infinite.world.voxelize.VoxelChunk;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import java.util.function.LongPredicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class RegionImport extends LodImport<RegionImport.Chunk, RegionImport.Context> {
    private static final Pattern REGION_NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    private static final int SECTOR = 4096, CHUNKS_PER_REGION = 1024;

    public interface Source extends AutoCloseable {
        List<Region> regions() throws IOException;

        byte[] read(Region region) throws IOException;

        byte @Nullable [] readOversized(Region region, int chunkX, int chunkZ) throws IOException;

        @Override
        default void close() throws IOException {}
    }

    public record Region(String path, int x, int z) {
        static @Nullable Region parse(String path, String fileName) {
            var match = REGION_NAME.matcher(fileName);
            return match.matches() ? new Region(path, Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2))) : null;
        }
    }

    record Chunk(int regionX, int regionZ, int index, byte[] data, int offset, int length, int compression) {
        @Override
        public String toString() {
            return "chunk " + (this.regionX * 32 + (this.index & 31)) + ", " + (this.regionZ * 32 + (this.index >> 5));
        }
    }

    static final class Context {
        final VoxelChunk section = new VoxelChunk();
    }

    private final Source source;
    private final int centerRegionX, centerRegionZ;
    private final Codec<PalettedContainer<BlockState>> blockCodec;
    private final Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec;
    private final PalettedContainerFactory containers;
    private @Nullable LongPredicate skip;
    private @Nullable LongPredicate within;
    private @Nullable LongConsumer imported;
    private @Nullable Listener observer;

    public RegionImport(String name, Source source, LodWorld world, Level level, int centerChunkX, int centerChunkZ,
                        WorkerPool pool, BooleanSupplier canRun) {
        super(name, world, pool, canRun);
        this.source = source;
        this.centerRegionX = centerChunkX >> 5;
        this.centerRegionZ = centerChunkZ >> 5;
        this.containers = PalettedContainerFactory.create(level.registryAccess());
        this.blockCodec = this.containers.blockStatesContainerCodec();
        this.biomeCodec = this.containers.biomeContainerCodec();
    }

    public RegionImport skipping(LongPredicate skip) {
        this.skip = skip;
        return this;
    }

    public RegionImport within(LongPredicate chunks) {
        this.within = chunks;
        return this;
    }

    public RegionImport imported(LongConsumer chunks) {
        this.imported = chunks;
        return this;
    }

    public RegionImport observer(@Nullable Listener listener) {
        this.observer = listener;
        return this;
    }

    public @Nullable Listener observer() {
        return this.observer;
    }

    public static Source folder(Path folder) {
        return new Source() {
            @Override
            public List<Region> regions() throws IOException {
                try (Stream<Path> files = Files.list(folder)) {
                    var regions = new ArrayList<Region>();
                    for (var file : (Iterable<Path>) files::iterator) {
                        var region = Region.parse(file.toString(), file.getFileName().toString());
                        if (region != null) regions.add(region);
                    }
                    return regions;
                }
            }

            @Override
            public byte[] read(Region region) throws IOException {
                return Files.readAllBytes(Path.of(region.path()));
            }

            @Override
            public byte @Nullable [] readOversized(Region region, int chunkX, int chunkZ) throws IOException {
                var file = Path.of(region.path()).resolveSibling("c." + chunkX + "." + chunkZ + ".mcc");
                return Files.exists(file) ? Files.readAllBytes(file) : null;
            }
        };
    }

    public static @Nullable Path regionsAt(Path given, ResourceKey<Level> dimension) {
        var path = Files.isRegularFile(given.resolve("level.dat"))
                ? DimensionType.getStorageFolder(dimension, given) : given;
        if (Files.isDirectory(path.resolve("region"))) return path.resolve("region");
        return Files.isDirectory(path) ? path : null;
    }

    public static boolean hasRegions(Path folder) {
        try (Stream<Path> files = Files.list(folder)) {
            return files.anyMatch(file -> REGION_NAME.matcher(file.getFileName().toString()).matches());
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    protected void findWork() throws Exception {
        try (this.source) {
            var regions = new ArrayList<>(this.source.regions());
            if (this.within != null) regions.removeIf(region -> !this.wantsAny(region));
            regions.sort(Comparator.comparingLong(r -> {
                long dx = r.x() - this.centerRegionX, dz = r.z() - this.centerRegionZ;
                return dx * dx + dz * dz;
            }));
            long chunksFound = 0;
            for (int read = 0; read < regions.size(); read++) {
                var region = regions.get(read);
                byte[] data;
                try {
                    data = this.source.read(region);
                } catch (IOException e) {
                    Log.error(this.name() + ": could not read " + region.path(), e);
                    continue;
                }
                chunksFound += this.queueChunks(region, data);
                int left = regions.size() - read - 1;
                this.setExpected(chunksFound + (long) left * chunksFound / (read + 1));
            }
            this.setExpected(chunksFound);
        }
    }

    private boolean wantsAny(Region region) {
        var within = this.within;
        if (within == null) return true;
        for (int index = 0; index < CHUNKS_PER_REGION; index++) {
            if (within.test(ChunkPos.pack(region.x() * 32 + (index & 31), region.z() * 32 + (index >> 5)))) return true;
        }
        return false;
    }

    private int queueChunks(Region region, byte[] data) throws InterruptedException, IOException {
        if (data.length < 2 * SECTOR) {
            if (data.length != 0) Log.warn(this.name() + ": ignoring short region file " + region.path());
            return 0;
        }
        var file = ByteBuffer.wrap(data);
        int queued = 0;
        for (int index = 0; index < CHUNKS_PER_REGION; index++) {
            int location = file.getInt(index * 4);
            long start = (long) (location >>> 8) * SECTOR;
            int sectors = location & 0xFF;
            if (location == 0 || sectors == 0) continue;
            if (start + 5 > data.length) {
                Log.warn(this.name() + ": invalid chunk offset " + index + " in " + region.path());
                continue;
            }
            // Stored chunk length includes the 1-byte compression type.
            int length = file.getInt((int) start) - 1;
            int compression = data[(int) start + 4] & 0xFF;
            int chunkX = region.x() * 32 + (index & 31), chunkZ = region.z() * 32 + (index >> 5);
            if (this.within != null && !this.within.test(ChunkPos.pack(chunkX, chunkZ))) continue;
            Chunk chunk;
            if ((compression & 0x80) != 0) {
                byte[] oversized = this.source.readOversized(region, chunkX, chunkZ);
                if (oversized == null) {
                    Log.warn(this.name() + ": missing .mcc file for oversized chunk " + chunkX + ", " + chunkZ);
                    continue;
                }
                chunk = new Chunk(region.x(), region.z(), index, oversized, 0, oversized.length, compression & 0x7F);
            } else {
                if (length <= 0 || start + 5 + length > data.length) {
                    Log.warn(this.name() + ": invalid chunk length " + index + " in " + region.path());
                    continue;
                }
                chunk = new Chunk(region.x(), region.z(), index, data, (int) start + 5, length, compression);
            }
            this.offer(chunk);
            queued++;
        }
        return queued;
    }

    @Override
    protected int chunksIn(Chunk task) {
        return 1;
    }

    @Override
    protected Context newContext() {
        return new Context();
    }

    @Override
    protected int importTask(Chunk task, Context context) throws IOException {
        if (this.skip != null && this.skip.test(ChunkPos.pack(task.regionX() * 32 + (task.index() & 31),
                task.regionZ() * 32 + (task.index() >> 5)))) return 0;
        var version = RegionFileVersion.fromId(task.compression());
        if (version == null) throw new IOException("unknown compression " + task.compression());
        CompoundTag nbt;
        try (var in = new DataInputStream(version.wrap(new ByteArrayInputStream(task.data(), task.offset(), task.length())))) {
            nbt = NbtIo.read(in);
        }
        nbt = upgrade(nbt);
        if (nbt == null || !isFinished(nbt)) return 0;

        int chunkX = nbt.getIntOr("xPos", task.regionX() * 32 + (task.index() & 31));
        int chunkZ = nbt.getIntOr("zPos", task.regionZ() * 32 + (task.index() >> 5));
        var sections = nbt.getList("sections");
        if (sections.isEmpty()) return 0;
        for (var element : sections.get()) {
            if (element instanceof CompoundTag section) this.importSection(context.section, chunkX, chunkZ, section);
        }
        if (this.imported != null) this.imported.accept(ChunkPos.pack(chunkX, chunkZ));
        return 1;
    }

    private static @Nullable CompoundTag upgrade(CompoundTag chunk) {
        var saved = chunk.getInt("DataVersion");
        if (saved.isEmpty()) return null;
        int current = SharedConstants.getCurrentVersion().dataVersion().version();
        if (saved.get() == current) return chunk;
        return DataFixers.getDataFixer().update(References.CHUNK, new Dynamic<>(NbtOps.INSTANCE, chunk), saved.get(), current)
                .getValue().asCompound().orElse(null);
    }

    private static boolean isFinished(CompoundTag chunk) {
        if (!chunk.contains("Status")) return false;
        var status = ChunkStatus.byName(chunk.getStringOr("Status", ""));
        // Upgraded chunks from old worlds can have EMPTY status while still containing terrain.
        return status == ChunkStatus.FULL || status == ChunkStatus.EMPTY;
    }

    private void importSection(VoxelChunk out, int chunkX, int chunkZ, CompoundTag section) {
        var states = section.getCompound("block_states");
        if (states.isEmpty()) return;
        var blocks = this.blockCodec.parse(NbtOps.INSTANCE, states.get()).resultOrPartial().orElse(null);
        if (blocks == null) return;
        PalettedContainerRO<Holder<Biome>> biomes = section.getCompound("biomes")
                .flatMap(tag -> this.biomeCodec.parse(NbtOps.INSTANCE, tag).result())
                .orElseGet(this.containers::createForBiomes);
        var blockLight = section.getByteArray("BlockLight").filter(b -> b.length == 2048).map(DataLayer::new).orElse(null);
        var skyLight = section.getByteArray("SkyLight").filter(b -> b.length == 2048).map(DataLayer::new).orElse(null);
        int sectionY = section.getIntOr("Y", Integer.MIN_VALUE);
        if (sectionY == Integer.MIN_VALUE) return;

        ChunkVoxelizer.voxelize(out.moveTo(chunkX, sectionY, chunkZ), this.world.ids(), blocks, biomes, blockLight, skyLight);
        out.downsample(this.world.ids());
        SectionWriter.write(this.world, out);
    }
}
