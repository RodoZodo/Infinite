package rodozodo.infinite.client.model;

import rodozodo.infinite.util.NativeBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.MipmapStrategy;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;


// Bakes a Minecraft block or fluid state into a LOD model
// Rasterizing its six faces, analyzing per-face flags and occlusion, and detecting crossed-plane plants, wall torches, fires, and live-animated faces.

final class ModelBaker {
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final Direction[] QUAD_SIDES = {Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, null};
    private static final int SOLID = 0, CUTOUT = 1, TRANSLUCENT = 2;

    private static final Map<Block, Block> STANDING_TORCHES = Map.of(
            Blocks.WALL_TORCH, Blocks.TORCH,
            Blocks.REDSTONE_WALL_TORCH, Blocks.REDSTONE_TORCH,
            Blocks.SOUL_WALL_TORCH, Blocks.SOUL_TORCH,
            Blocks.COPPER_WALL_TORCH, Blocks.COPPER_TORCH);
    private static final float WALL_TORCH_RISE = 3.5f / 16;
    private static final float WALL_TORCH_EDGE = 7f / 16;

    private record Drawn(BlockState state, float x, float y, float z) {
        static Drawn inPlace(BlockState state) {
            return new Drawn(state, 0, 0, 0);
        }
    }

    // Model entry flags and face word fields shared with terrain / quad.glsl.
    static final int FLAG_TWO_SIDED = 1, FLAG_BIOME_TINT = 2, FLAG_TRANSLUCENT = 4, FLAG_SHADED = 8, FLAG_LIVE_UV = 16,
            FLAG_PLANT = 32, FLAG_OFFSET = 64, FLAG_FLUID = 128, FLAG_FOUR_PLANES = 256, FLAG_LEAF = 512;
    private static final int FACE_ALPHA_CUTOUT = 1 << 22, FACE_STRETCHED_CUTOUT = 1 << 23, FACE_LIVE = 1 << 26,
            FACE_OPAQUE_MIP_SHIFT = 27;
    private static final int TINT_GREYSCALE = 1, TINT_ALWAYS = 2;

    record Tint(@Nullable List<BlockTintSource> sources, BlockState state, boolean perBiome, int colour) {
        static final Tint NONE = new Tint(null, null, false, -1);

        boolean any() {
            return this.sources != null;
        }
    }

    record Baked(int[] faces, int flags, Tint tint, long info, NativeBuffer texture, ModelKey key, float[] fluidUv,
                 List<TextureAtlasSprite> animated) {}

    record ModelKey(long a, long b, long portable) {}

    private static final float MOST_COVERAGE = 0.15f;
    private static final float LEAST_FLOWER = 0.08f;

    private final FaceRasterizer faces;
    private final int[] atlas;
    private final int atlasWidth;
    private final QuadCollector opaque = new QuadCollector();
    private final QuadCollector translucent = new QuadCollector();
    private final ModelTextures textures = new ModelTextures();
    private final FluidRenderer fluidRenderer;
    private final OneBlockLevel level;
    private final Biome defaultBiome;
    private final boolean cutoutLeaves;
    private final Biome[] tintSamples;
    private final List<BlockStateModelPart> parts = new ArrayList<>();

    private boolean shaded, darkCutout, anyTranslucent, anyDiscard, ambientOcclusion;
    private boolean fluidUvComplete;
    private boolean plant, fourPlanes;
    private final float[] plantPlanes = new float[4 * 4];
    private final float[] plantUvs = new float[4 * 6];
    private final int[] plantQuads = new int[4];
    private final float[] plantBottoms = new float[4], plantTops = new float[4];
    private final float[] iconUvs = new float[6];
    private @Nullable TextureAtlasSprite icon;
    private final List<TextureAtlasSprite> animated = new ArrayList<>();

    // Per face: 6-float affine UV map to the live block atlas (or plant/stick plane data; terrain/quad.glsl).
    private final float[] fluidUv = new float[6 * 6];
    private final float[] imageX = new float[4], imageY = new float[4], texU = new float[4], texV = new float[4];
    private final float[] image = new float[2];
    private static final int[][] CORNER_TRIPLES = {{0, 1, 2}, {0, 1, 3}, {0, 2, 3}, {1, 2, 3}};

    ModelBaker(int[] atlas, int atlasWidth, int atlasHeight, Biome defaultBiome, Biome[] tintSamples) {
        this.faces = new FaceRasterizer(atlas, atlasWidth, atlasHeight);
        this.atlas = atlas;
        this.atlasWidth = atlasWidth;
        this.fluidRenderer = new FluidRenderer(Minecraft.getInstance().getModelManager().getFluidStateModelSet());
        this.level = new OneBlockLevel(defaultBiome);
        this.defaultBiome = defaultBiome;
        this.cutoutLeaves = Minecraft.getInstance().options.cutoutLeaves().get();
        this.tintSamples = tintSamples;
    }

    @Nullable Baked bake(BlockState state, int fluidModel) {
        return this.bake(state, fluidModel, 0);
    }

    @Nullable Baked bake(BlockState state, int fluidModel, int cover) {
        boolean fluid = state.getBlock() instanceof LiquidBlock;
        boolean solidLeaves = state.is(BlockTags.LEAVES) && !this.cutoutLeaves;
        this.faces.clear();
        Arrays.fill(this.fluidUv, 0);
        this.fluidUvComplete = true;
        this.plant = this.fourPlanes = false;
        this.icon = null;
        this.animated.clear();
        this.shaded = this.darkCutout = this.anyTranslucent = this.anyDiscard = this.ambientOcclusion = false;
        if (fluid) {
            this.drawFluid(state);
        } else {
            this.drawBlock(state, solidLeaves);
        }
        if (this.plant) return this.bakePlant(state, fluidModel);
        if (cover != 0) this.coverTop(cover);

        int layer = this.layer(state, solidLeaves);
        var tint = this.anyTinted() ? this.tint(state, fluid) : Tint.NONE;
        long info = (fluid ? ModelInfo.IS_FLUID : 0) | (fluidModel != -1 ? ModelInfo.HAS_FLUID : 0)
                | (layer == TRANSLUCENT ? ModelInfo.TRANSLUCENT : 0) | (tint.perBiome ? ModelInfo.BIOME_TINTED : 0)
                | (cullsSelf(state, solidLeaves) ? ModelInfo.CULLS_SELF : 0) | ModelInfo.light(lightEmission(state))
                | (aoCaster(state) ? ModelInfo.AO_CASTER : 0)
                | (!fluid && this.ambientOcclusion && state.getLightEmission() == 0 ? ModelInfo.USES_AO : 0)
                | (state.is(BlockTags.LEAVES) ? ModelInfo.LEAF : 0);

        float[] depths = new float[6];
        boolean anyFace = false;
        for (int face = 0; face < 6; face++) {
            depths[face] = this.depth(face, layer);
            anyFace |= depths[face] >= 0;
        }
        if (!anyFace && fluidModel == -1) return null;
        var texture = new NativeBuffer(ModelTextures.BYTES);
        this.textures.write(this.faces, this.darkCutout, texture.address);

        int[] words = new int[6];
        boolean opaqueCube = true;
        for (int face = 0; face < 6; face++) {
            if (depths[face] < 0) {
                words[face] = -1;
                opaqueCube = false;
                continue;
            }
            boolean live = !fluid && this.animatedFace(face);
            int opaqueMip = live ? ModelTextures.LEVELS : ModelTextures.opaqueMip(texture.address, face);
            long faceInfo = this.analyseFace(face, layer, depths[face], tint, words, opaqueMip);
            if (live) words[face] |= FACE_LIVE;
            info |= faceInfo;
            opaqueCube &= ModelInfo.occludes(faceInfo, face);
        }
        if (opaqueCube) info |= ModelInfo.OPAQUE_CUBE;
        boolean twoSided = (depths[0] < 0 && depths[1] < 0) || (depths[2] < 0 && depths[3] < 0) || (depths[4] < 0 && depths[5] < 0);
        if (twoSided) info |= ModelInfo.DOUBLE_SIDED;
        if (!this.animated.isEmpty()) info |= ModelInfo.ANIMATED;

        int flags = (twoSided ? FLAG_TWO_SIDED : 0) | (tint.perBiome ? FLAG_BIOME_TINT : 0)
                | (layer == TRANSLUCENT ? FLAG_TRANSLUCENT : 0) | (this.shaded ? FLAG_SHADED : 0)
                | (fluid && this.fluidUvComplete ? FLAG_LIVE_UV : 0) | (fluid ? FLAG_FLUID : 0)
                | (state.is(BlockTags.LEAVES) ? FLAG_LEAF : 0);
        return new Baked(words, flags, tint, info, texture, this.key(words, flags, tint, info, fluidModel, texture),
                this.fluidUv.clone(), List.copyOf(this.animated));
    }

    private void drawBlock(BlockState state, boolean solidLeaves) {
        this.opaque.reset();
        this.translucent.reset();
        if (state.getRenderShape() == RenderShape.INVISIBLE) return;
        this.parts.clear();
        var drawn = drawnFor(state);
        var model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(drawn.state());
        var icon = model.particleMaterial().sprite();
        this.icon = icon;
        this.iconUvs[0] = icon.getU0();
        this.iconUvs[1] = icon.getV1();
        this.iconUvs[2] = icon.getU1();
        this.iconUvs[3] = icon.getV1();
        this.iconUvs[4] = icon.getU0();
        this.iconUvs[5] = icon.getV0();
        model.collectParts(new SingleThreadedRandomSource(42L), this.parts);
        for (var part : this.parts) {
            this.ambientOcclusion |= part.useAmbientOcclusion();
            for (var side : QUAD_SIDES) {
                for (var quad : part.getQuads(side)) {
                    this.addQuad(quad, solidLeaves, drawn.x(), drawn.y(), drawn.z());
                }
            }
        }
        if (this.opaque.isEmpty() && this.translucent.isEmpty()) return;
        if (state.is(BlockTags.FIRE) && this.detectFire()) return;
        if (this.detectPlant() || this.detectStick()) return;
        for (int face = 0; face < 6; face++) {
            this.faces.draw(face, this.opaque, false);
            this.faces.draw(face, this.translucent, true);
        }
    }

    private void coverTop(int cover) {
        int base = Direction.UP.get3DDataValue() * FaceRasterizer.PIXELS;
        for (int i = 0; i < FaceRasterizer.PIXELS; i++) {
            int pixel = base + i;
            if (!this.faces.drawn(pixel)) continue;
            int hash = i * 0x9E3779B1;
            hash ^= hash >>> 15;
            hash *= 0x85EBCA77;
            hash ^= hash >>> 13;
            if ((hash >>> 8 & 0xFFFF) >= (cover >>> 24) * (0x10000 / 255)) continue;
            float light = 0.8f + (hash & 0xFF) / 255f * 0.2f;
            int red = Math.min(255, Math.round((cover & 0xFF) * light));
            int green = Math.min(255, Math.round((cover >> 8 & 0xFF) * light));
            int blue = Math.min(255, Math.round((cover >> 16 & 0xFF) * light));
            this.faces.colour[pixel] = 0xFF000000 | notGrey(red, green, blue);
            this.faces.tinted[pixel] = false;
        }
    }

    int plantColour(BlockState plant) {
        if (plant.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            plant = plant.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
        }
        this.drawBlock(plant, false);
        Set<TextureAtlasSprite> sprites = Collections.newSetFromMap(new IdentityHashMap<>());
        for (var quads : new QuadCollector[]{this.opaque, this.translucent}) {
            for (int quad = 0, count = quads.quadCount(); quad < count; quad++) {
                var sprite = quads.sprites[quad];
                if (sprite != null && (quads.flags[quad] & QuadCollector.TINTED) == 0) sprites.add(sprite);
            }
        }
        long red = 0, green = 0, blue = 0;
        int flowers = 0, shown = 0, area = 0;
        for (var sprite : sprites) {
            int width = sprite.contents().width(), height = sprite.contents().height();
            area += width * height;
            // Sample animated sprites at frame 0 to match FaceRasterizer.
            var frameZero = sprite.contents().isAnimated() ? sprite.contents().originalImage : null;
            for (int y = 0; y < height; y++) {
                int row = (sprite.getY() + y) * this.atlasWidth + sprite.getX();
                for (int x = 0; x < width; x++) {
                    int colour = frameZero != null ? ARGB.toABGR(frameZero.getPixel(x, y)) : this.atlas[row + x];
                    if (colour >>> 24 < 128) continue;
                    shown++;
                    int r = colour & 0xFF, g = colour >> 8 & 0xFF, b = colour >> 16 & 0xFF;
                    int most = Math.max(r, Math.max(g, b)), least = Math.min(r, Math.min(g, b));
                    boolean leaf = g == most && most - least > 0.25f * most;
                    if (leaf) continue;
                    red += r;
                    green += g;
                    blue += b;
                    flowers++;
                }
            }
        }
        if (flowers == 0 || flowers < LEAST_FLOWER * shown) return 0;
        int coverage = Math.clamp(Math.round(Math.min(MOST_COVERAGE, (float) flowers / area) * 255), 1, 255);
        return coverage << 24 | notGrey((int) (red / flowers), (int) (green / flowers), (int) (blue / flowers));
    }

    // Offset greyscale RGB slightly so terrain.frag does not apply biome tint to white flowers.
    private static int notGrey(int red, int green, int blue) {
        if (Math.abs(red - green) < 8 && Math.abs(green - blue) < 8) blue = blue >= 12 ? blue - 12 : blue + 12;
        return red | green << 8 | blue << 16;
    }

    private Baked bakePlant(BlockState state, int fluidModel) {
        var tint = this.tint(state, false);
        for (int quad = 0, count = this.opaque.quadCount(); quad < count; quad++) {
            if ((this.opaque.flags[quad] & QuadCollector.ANIMATED) != 0) this.addAnimated(this.opaque.sprites[quad]);
        }
        if (this.icon != null && this.icon.contents().isAnimated()) this.addAnimated(this.icon);
        int flags = FLAG_PLANT | FLAG_TWO_SIDED | (state.hasOffsetFunction() ? FLAG_OFFSET : 0)
                | (tint.perBiome ? FLAG_BIOME_TINT : 0) | (this.fourPlanes ? FLAG_FOUR_PLANES : 0);
        long info = ModelInfo.PLANT | (this.fourPlanes ? ModelInfo.FOUR_PLANES : 0) | ModelInfo.light(lightEmission(state))
                | (this.animated.isEmpty() ? 0 : ModelInfo.ANIMATED)
                | (fluidModel != -1 ? ModelInfo.HAS_FLUID : 0)
                | (tint.perBiome ? ModelInfo.BIOME_TINTED : 0);
        int[] words = new int[6];
        var texture = NativeBuffer.zeroed(ModelTextures.BYTES);
        return new Baked(words, flags, tint, info, texture,
                this.key(words, flags, tint, info, fluidModel, texture), this.fluidUv.clone(), List.copyOf(this.animated));
    }

    private void addQuad(BakedQuad quad, boolean solidLeaves, float offsetX, float offsetY, float offsetZ) {
        var material = quad.materialInfo();
        int flags = (material.isTinted() ? QuadCollector.TINTED : 0)
                | (material.sprite().contents().isAnimated() ? QuadCollector.ANIMATED : 0);
        this.shaded |= material.shadeDirectionOverride() == null;
        this.darkCutout |= material.sprite().contents().mipmapStrategy == MipmapStrategy.DARK_CUTOUT;
        if (material.layer() == ChunkSectionLayer.TRANSLUCENT) {
            this.anyTranslucent = true;
            this.translucent.add(quad, flags | QuadCollector.DISCARD, offsetX, offsetY, offsetZ);
        } else {
            if (material.layer() != ChunkSectionLayer.SOLID && !solidLeaves) {
                flags |= QuadCollector.DISCARD;
                this.anyDiscard = true;
            }
            this.opaque.add(quad, flags, offsetX, offsetY, offsetZ);
        }
    }

    private static Drawn drawnFor(BlockState state) {
        Block upright = STANDING_TORCHES.get(state.getBlock());
        if (upright == null) return Drawn.inPlace(state);
        var standing = upright.defaultBlockState();
        if (standing.hasProperty(BlockStateProperties.LIT) && state.hasProperty(BlockStateProperties.LIT)) {
            standing = standing.setValue(BlockStateProperties.LIT, state.getValue(BlockStateProperties.LIT));
        }
        var wall = state.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite();
        return new Drawn(standing, wall.getStepX() * WALL_TORCH_EDGE, WALL_TORCH_RISE,
                wall.getStepZ() * WALL_TORCH_EDGE);
    }

    private void drawFluid(BlockState state) {
        var fluidState = state.getFluidState();
        var fluidModel = Minecraft.getInstance().getModelManager().getFluidStateModelSet().get(fluidState);
        boolean tinted = fluidModel.tintSource() != null;
        var animated = new ArrayList<TextureAtlasSprite>(3);
        for (var material : new Material.Baked[]{fluidModel.stillMaterial(), fluidModel.flowingMaterial(), fluidModel.overlayMaterial()}) {
            if (material != null && material.sprite().contents().isAnimated()) {
                this.addAnimated(material.sprite());
                animated.add(material.sprite());
            }
        }
        this.faces.unspritedFrom(animated.toArray(new TextureAtlasSprite[0]));
        int tint = tinted ? QuadCollector.TINTED : 0;
        this.shaded = true;
        for (int face = 0; face < 6; face++) {
            this.opaque.reset();
            this.translucent.reset();
            this.level.reset(state, this.defaultBiome, DIRECTIONS[face]);
            this.fluidRenderer.tesselate(this.level, BlockPos.ZERO, layer -> {
                if (layer == ChunkSectionLayer.TRANSLUCENT) {
                    this.anyTranslucent = true;
                    this.translucent.streamFlags = tint | QuadCollector.DISCARD;
                    return this.translucent;
                }
                boolean cutout = layer != ChunkSectionLayer.SOLID;
                this.anyDiscard |= cutout;
                this.opaque.streamFlags = tint | (cutout ? QuadCollector.DISCARD : 0);
                return this.opaque;
            }, state, fluidState);
            this.faces.draw(face, this.opaque, false);
            this.faces.draw(face, this.translucent, true);
            this.fluidFaceUv(face, this.translucent.isEmpty() ? this.opaque : this.translucent);
        }
        this.faces.unspritedFrom(new TextureAtlasSprite[0]);
        if (!fluidState.isSource()) this.flowingTop(fluidModel);
    }

    // Bake flowing top unturned (bands along +x); terrain.vert rotates per quad by flow direction.
    private void flowingTop(FluidModel model) {
        var material = model.flowingMaterial();
        if (material == null) return;
        var sprite = material.sprite();
        this.faces.paint(1, sprite, 0);
        int at = 6;
        this.fluidUv[at] = sprite.getU(0f);
        this.fluidUv[at + 1] = sprite.getV(0f);
        this.fluidUv[at + 2] = 0;
        this.fluidUv[at + 3] = sprite.getV(1f) - sprite.getV(0f);
        this.fluidUv[at + 4] = sprite.getU(1f) - sprite.getU(0f);
        this.fluidUv[at + 5] = 0;
    }

    private void fluidFaceUv(int face, QuadCollector quads) {
        if (quads.isEmpty() || !this.liveUv(face, quads, 0)) this.fluidUvComplete = false;
    }

    private boolean liveUv(int face, QuadCollector quads, int quad) {
        float[] v = quads.vertices;
        for (int i = 0; i < 4; i++) {
            int at = (quad * 4 + i) * QuadCollector.STRIDE;
            FaceRasterizer.imageCoords(face, v[at], v[at + 1], v[at + 2], this.image);
            this.imageX[i] = this.image[0];
            this.imageY[i] = this.image[1];
            this.texU[i] = v[at + 3];
            this.texV[i] = v[at + 4];
        }

        int i0 = -1, i1 = -1, i2 = -1;
        float det = 0, dx1 = 0, dy1 = 0, dx2 = 0, dy2 = 0, du1 = 0, dv1 = 0, du2 = 0, dv2 = 0;
        for (int[] corners : CORNER_TRIPLES) {
            if (det != 0) break;
            i0 = corners[0];
            i1 = corners[1];
            i2 = corners[2];
            dx1 = this.imageX[i1] - this.imageX[i0];
            dy1 = this.imageY[i1] - this.imageY[i0];
            dx2 = this.imageX[i2] - this.imageX[i0];
            dy2 = this.imageY[i2] - this.imageY[i0];
            det = dx1 * dy2 - dx2 * dy1;
            if (Math.abs(det) < 1e-6f) det = 0;
        }
        if (det == 0) return false;
        du1 = this.texU[i1] - this.texU[i0];
        dv1 = this.texV[i1] - this.texV[i0];
        du2 = this.texU[i2] - this.texU[i0];
        dv2 = this.texV[i2] - this.texV[i0];

        float a00 = (du1 * dy2 - du2 * dy1) / det;
        float a01 = (du2 * dx1 - du1 * dx2) / det;
        float a10 = (dv1 * dy2 - dv2 * dy1) / det;
        float a11 = (dv2 * dx1 - dv1 * dx2) / det;
        float cornerU = this.texU[i0] - (a00 * this.imageX[i0] + a01 * this.imageY[i0]);
        float cornerV = this.texV[i0] - (a10 * this.imageX[i0] + a11 * this.imageY[i0]);

        int at = face * 6;
        this.fluidUv[at] = cornerU;
        this.fluidUv[at + 1] = cornerV;
        this.fluidUv[at + 2] = a00 * FaceRasterizer.SIZE;
        this.fluidUv[at + 3] = a10 * FaceRasterizer.SIZE;
        this.fluidUv[at + 4] = a01 * FaceRasterizer.SIZE;
        this.fluidUv[at + 5] = a11 * FaceRasterizer.SIZE;
        return true;
    }

    private boolean animatedFace(int face) {
        QuadCollector found = null;
        int foundQuad = -1;
        for (var quads : new QuadCollector[]{this.opaque, this.translucent}) {
            for (int quad = 0, count = quads.quadCount(); quad < count; quad++) {
                if (!facesOut(quads.vertices, quad * 4 * QuadCollector.STRIDE, face)) continue;
                if (found != null) return false;
                found = quads;
                foundQuad = quad;
            }
        }
        if (found == null || (found.flags[foundQuad] & QuadCollector.ANIMATED) == 0
                || !this.fillsSide(found.vertices, foundQuad * 4 * QuadCollector.STRIDE, face)
                || !this.liveUv(face, found, foundQuad)) return false;
        this.addAnimated(found.sprites[foundQuad]);
        return true;
    }

    private void addAnimated(TextureAtlasSprite sprite) {
        if (!this.animated.contains(sprite)) this.animated.add(sprite);
    }

    private static boolean facesOut(float[] v, int base, int face) {
        int b = base + QuadCollector.STRIDE, c = base + 2 * QuadCollector.STRIDE;
        float abx = v[b] - v[base], aby = v[b + 1] - v[base + 1], abz = v[b + 2] - v[base + 2];
        float acx = v[c] - v[base], acy = v[c + 1] - v[base + 1], acz = v[c + 2] - v[base + 2];
        float outward = switch (face) {
            case 0 -> -(abz * acx - abx * acz);
            case 1 -> abz * acx - abx * acz;
            case 2 -> -(abx * acy - aby * acx);
            case 3 -> abx * acy - aby * acx;
            case 4 -> -(aby * acz - abz * acy);
            default -> aby * acz - abz * acy;
        };
        return outward > 1e-9f;
    }

    private boolean fillsSide(float[] v, int base, int face) {
        int corners = 0;
        for (int i = 0; i < 4; i++) {
            int at = base + i * QuadCollector.STRIDE;
            FaceRasterizer.imageCoords(face, v[at], v[at + 1], v[at + 2], this.image);
            int u = edgeOfImage(this.image[0]), w = edgeOfImage(this.image[1]);
            if (u < 0 || w < 0) return false;
            corners |= 1 << (u | w << 1);
        }
        return corners == 0b1111;
    }

    private static int edgeOfImage(float coordinate) {
        if (Math.abs(coordinate) < 0.01f) return 0;
        return Math.abs(coordinate - FaceRasterizer.SIZE) < 0.01f ? 1 : -1;
    }

    private boolean detectPlant() {
        if (!this.translucent.isEmpty()) return false;
        int count = this.opaque.quadCount();
        if (count < 2 || count > 8) return false;
        return this.findPlanes(false) || this.findPlanes(true);
    }

    // Stores stick cross planes with footY in fluidUv[21] and top lean in fluidUv[30..31] (terrain.vert).
    private boolean detectStick() {
        if (!this.translucent.isEmpty() || this.opaque.quadCount() != 6) return false;
        float[] v = this.opaque.vertices;
        int up = -1, down = -1, side = -1;
        for (int quad = 0; quad < 6; quad++) {
            int base = quad * 4 * QuadCollector.STRIDE;
            float ax = v[base + QuadCollector.STRIDE] - v[base], ay = v[base + QuadCollector.STRIDE + 1] - v[base + 1];
            float az = v[base + QuadCollector.STRIDE + 2] - v[base + 2];
            int third = base + 2 * QuadCollector.STRIDE;
            float bx = v[third] - v[base], by = v[third + 1] - v[base + 1], bz = v[third + 2] - v[base + 2];
            float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
            float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (length < 1e-6f) return false;
            ny /= length;
            if (ny > 0.7f) {
                if (up >= 0) return false;
                up = base;
            } else if (ny < -0.7f) {
                if (down >= 0) return false;
                down = base;
            } else if (side < 0) {
                side = base;
            }
        }
        if (up < 0 || down < 0 || side < 0) return false;
        float footX = 0, footY = 0, footZ = 0, topX = 0, topY = 0, topZ = 0;
        for (int corner = 0; corner < 4; corner++) {
            int foot = down + corner * QuadCollector.STRIDE, top = up + corner * QuadCollector.STRIDE;
            footX += v[foot] / 4;
            footY += v[foot + 1] / 4;
            footZ += v[foot + 2] / 4;
            topX += v[top] / 4;
            topY += v[top + 1] / 4;
            topZ += v[top + 2] / 4;
        }
        float rise = topY - footY;
        if (rise < 0.25f) return false;

        int shortEdge = -1;
        float width = Float.POSITIVE_INFINITY;
        for (int corner = 1; corner < 4; corner += 2) {
            int at = side + corner * QuadCollector.STRIDE;
            float dx = v[at] - v[side], dy = v[at + 1] - v[side + 1], dz = v[at + 2] - v[side + 2];
            float edge = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (edge < width) {
                width = edge;
                shortEdge = at;
            }
        }
        if (width > 0.3f || width < 1e-4f) return false;
        float du = v[shortEdge + 3] - v[side + 3], dv = v[shortEdge + 4] - v[side + 4];
        if (Math.abs(dv) > Math.abs(du) * 0.01f) return false;
        float uMin = Float.POSITIVE_INFINITY, uMax = Float.NEGATIVE_INFINITY, low = Float.POSITIVE_INFINITY;
        float high = Float.NEGATIVE_INFINITY, vLow = 0, vHigh = 0;
        for (int corner = 0; corner < 4; corner++) {
            int at = side + corner * QuadCollector.STRIDE;
            uMin = Math.min(uMin, v[at + 3]);
            uMax = Math.max(uMax, v[at + 3]);
            if (v[at + 1] < low) {
                low = v[at + 1];
                vLow = v[at + 4];
            }
            if (v[at + 1] > high) {
                high = v[at + 1];
                vHigh = v[at + 4];
            }
        }
        float middle = (uMin + uMax) / 2, across = (uMax - uMin) / width;
        var sprite = this.opaque.sprites[side / (4 * QuadCollector.STRIDE)];
        if (sprite == null) return false;
        float leftEdge = Math.min(sprite.getU0(), sprite.getU1()), rightEdge = Math.max(sprite.getU0(), sprite.getU1());
        float slack = (rightEdge - leftEdge) / 64;
        if (middle - across / 2 < leftEdge - slack || middle + across / 2 > rightEdge + slack) return false;
        for (int plane = 0; plane < 2; plane++) {
            int at = plane * 10;
            this.fluidUv[at] = middle - across / 2;
            this.fluidUv[at + 1] = vLow;
            this.fluidUv[at + 2] = middle + across / 2;
            this.fluidUv[at + 3] = vLow;
            this.fluidUv[at + 4] = middle - across / 2;
            this.fluidUv[at + 5] = vHigh;
            this.fluidUv[at + 6] = footX - (plane == 0 ? 0.5f : 0);
            this.fluidUv[at + 7] = footZ - (plane == 1 ? 0.5f : 0);
            this.fluidUv[at + 8] = footX + (plane == 0 ? 0.5f : 0);
            this.fluidUv[at + 9] = footZ + (plane == 1 ? 0.5f : 0);
        }
        this.fluidUv[20] = rise;
        this.fluidUv[21] = footY;
        this.fluidUv[30] = topX - footX;
        this.fluidUv[31] = topZ - footZ;
        this.plant = true;
        this.fourPlanes = false;
        return true;
    }

    private boolean findPlanes(boolean square) {
        var quads = this.opaque;
        int count = quads.quadCount();
        int planesWanted = square ? 4 : 2;
        float[] v = quads.vertices;
        float height = -1, tallest = -1;
        boolean decorated = false;
        int planeCount = 0;
        for (int quad = 0; quad < count; quad++) {
            int base = quad * 4 * QuadCollector.STRIDE;
            float bottom = Float.POSITIVE_INFINITY, top = Float.NEGATIVE_INFINITY;
            for (int corner = 0; corner < 4; corner++) {
                float y = v[base + corner * QuadCollector.STRIDE + 1];
                bottom = Math.min(bottom, y);
                top = Math.max(top, y);
            }
            tallest = Math.max(tallest, top);

            float x0 = v[base], z0 = v[base + 2], x1 = 0, z1 = 0;
            boolean other = false;
            for (int corner = 1; corner < 4; corner++) {
                int at = base + corner * QuadCollector.STRIDE;
                if (!sameXz(v[at], v[at + 2], x0, z0)) {
                    x1 = v[at];
                    z1 = v[at + 2];
                    other = true;
                    break;
                }
            }
            if (other) {
                for (int corner = 0; corner < 4; corner++) {
                    int at = base + corner * QuadCollector.STRIDE;
                    if (!sameXz(v[at], v[at + 2], x0, z0) && !sameXz(v[at], v[at + 2], x1, z1)) other = false;
                }
            }
            float spanX = Math.abs(x1 - x0), spanZ = Math.abs(z1 - z0);
            boolean shaped = square
                    ? spanX > 0.9f && spanZ < 0.01f || spanZ > 0.9f && spanX < 0.01f
                    : spanX >= 0.4f && spanZ >= 0.4f && Math.abs(spanX - spanZ) <= 0.05f;
            if (!other || Math.abs(bottom) > 0.01f || top < 0.25f || !shaped) {
                decorated = true;
                continue;
            }
            if (height < 0) height = top;
            if (Math.abs(top - height) > 0.01f) return false;

            int plane = -1;
            for (int known = 0; known < planeCount; known++) {
                if (this.samePlane(known, x0, z0, x1, z1)) {
                    plane = known;
                    break;
                }
            }
            if (plane >= 0) continue;
            if (planeCount == planesWanted) return false;
            plane = planeCount++;
            this.plantPlanes[plane * 4] = x0;
            this.plantPlanes[plane * 4 + 1] = z0;
            this.plantPlanes[plane * 4 + 2] = x1;
            this.plantPlanes[plane * 4 + 3] = z1;
            this.plantQuads[plane] = base;
            this.plantBottoms[plane] = bottom;
            this.plantTops[plane] = top;
            if (!this.plantUv(base, bottom, top, x0, z0, x1, z1, plane * 6)) return false;
        }
        if (planeCount != planesWanted) return false;
        if (square) {
            if (decorated || !this.pairPlanes()) return false;
        } else if (decorated) {
            System.arraycopy(this.iconUvs, 0, this.plantUvs, 0, 6);
            System.arraycopy(this.iconUvs, 0, this.plantUvs, 6, 6);
            height = Math.max(height, tallest);
        }
        this.keepPlanes(planeCount, height, square);
        return true;
    }

    private boolean detectFire() {
        if (!this.translucent.isEmpty() || this.icon == null) return false;
        var quads = this.opaque;
        float[] v = quads.vertices;
        float height = 0;
        int alongX = 0, alongZ = 0;
        float[] planes = new float[4 * 4];
        for (int quad = 0, count = quads.quadCount(); quad < count; quad++) {
            int base = quad * 4 * QuadCollector.STRIDE;
            float bottom = Float.POSITIVE_INFINITY, top = Float.NEGATIVE_INFINITY;
            float x0 = v[base], z0 = v[base + 2], x1 = x0, z1 = z0;
            boolean upright = true;
            for (int corner = 0; corner < 4; corner++) {
                int at = base + corner * QuadCollector.STRIDE;
                bottom = Math.min(bottom, v[at + 1]);
                top = Math.max(top, v[at + 1]);
                if (sameXz(v[at], v[at + 2], x0, z0)) continue;
                if (sameXz(x1, z1, x0, z0)) {
                    x1 = v[at];
                    z1 = v[at + 2];
                } else if (!sameXz(v[at], v[at + 2], x1, z1)) {
                    upright = false;
                }
            }
            if (!upright || Math.abs(bottom) > 0.01f || top < 0.25f) continue;
            float spanX = Math.abs(x1 - x0), spanZ = Math.abs(z1 - z0);
            boolean xSide = spanX > 0.9f && spanZ < 0.01f && Math.abs(z0 - 0.5f) > 0.45f;
            boolean zSide = spanZ > 0.9f && spanX < 0.01f && Math.abs(x0 - 0.5f) > 0.45f;
            if (!xSide && !zSide) continue;
            int known = 0;
            for (int i = 0; i < alongX + alongZ; i++) {
                int at = i < alongX ? i * 2 : 1 + (i - alongX) * 2;
                if (this.sameSheet(planes, at, x0, z0, x1, z1)) known++;
            }
            if (known != 0) continue;
            if (xSide ? alongX == 2 : alongZ == 2) return false;
            int plane = xSide ? alongX++ * 2 : 1 + alongZ++ * 2;
            planes[plane * 4] = x0;
            planes[plane * 4 + 1] = z0;
            planes[plane * 4 + 2] = x1;
            planes[plane * 4 + 3] = z1;
            height = Math.max(height, top);
        }
        if (alongX != 2 || alongZ != 2) return false;
        System.arraycopy(planes, 0, this.plantPlanes, 0, planes.length);
        System.arraycopy(this.iconUvs, 0, this.plantUvs, 0, 6);
        System.arraycopy(this.iconUvs, 0, this.plantUvs, 6, 6);
        this.keepPlanes(4, height, true);
        return true;
    }

    private boolean sameSheet(float[] planes, int plane, float x0, float z0, float x1, float z1) {
        int at = plane * 4;
        return sameXz(x0, z0, planes[at], planes[at + 1]) && sameXz(x1, z1, planes[at + 2], planes[at + 3])
                || sameXz(x0, z0, planes[at + 2], planes[at + 3]) && sameXz(x1, z1, planes[at], planes[at + 1]);
    }

    private void keepPlanes(int planeCount, float height, boolean square) {
        for (int plane = 0; plane < 2; plane++) {
            System.arraycopy(this.plantUvs, plane * 6, this.fluidUv, plane * 10, 6);
            System.arraycopy(this.plantPlanes, plane * 4, this.fluidUv, plane * 10 + 6, 4);
        }
        this.fluidUv[20] = height;

        // Planes 2-3 reuse UVs from planes 0-1, so only endpoints are stored (terrain/quad.glsl).
        for (int plane = 2; plane < planeCount; plane++) {
            System.arraycopy(this.plantPlanes, plane * 4, this.fluidUv, 22 + (plane - 2) * 4, 4);
        }
        this.plant = true;
        this.fourPlanes = square;
    }

    private boolean pairPlanes() {
        int[] order = new int[4];
        int alongX = 0, alongZ = 2;
        for (int plane = 0; plane < 4; plane++) {
            boolean x = Math.abs(this.plantPlanes[plane * 4 + 2] - this.plantPlanes[plane * 4]) > 0.5f;
            if (x ? alongX == 2 : alongZ == 4) return false;
            order[x ? alongX++ : alongZ++] = plane;
        }
        float[] planes = this.plantPlanes.clone(), uvs = this.plantUvs.clone();
        float[] bottoms = this.plantBottoms.clone(), tops = this.plantTops.clone();
        int[] quads = this.plantQuads.clone();
        int[] wanted = {order[0], order[2], order[1], order[3]};
        for (int plane = 0; plane < 4; plane++) {
            int from = wanted[plane];
            System.arraycopy(planes, from * 4, this.plantPlanes, plane * 4, 4);
            System.arraycopy(uvs, from * 6, this.plantUvs, plane * 6, 6);
            this.plantBottoms[plane] = bottoms[from];
            this.plantTops[plane] = tops[from];
            this.plantQuads[plane] = quads[from];
        }
        for (int plane = 2; plane < 4; plane++) {
            if (this.sameUvs(plane, plane - 2)) continue;
            int at = plane * 4;
            float x0 = this.plantPlanes[at], z0 = this.plantPlanes[at + 1];
            this.plantPlanes[at] = this.plantPlanes[at + 2];
            this.plantPlanes[at + 1] = this.plantPlanes[at + 3];
            this.plantPlanes[at + 2] = x0;
            this.plantPlanes[at + 3] = z0;
            if (!this.plantUv(this.plantQuads[plane], this.plantBottoms[plane], this.plantTops[plane], this.plantPlanes[at],
                    this.plantPlanes[at + 1], this.plantPlanes[at + 2], this.plantPlanes[at + 3], plane * 6)) return false;
            if (!this.sameUvs(plane, plane - 2)) return false;
        }
        return true;
    }

    private boolean sameUvs(int plane, int other) {
        for (int i = 0; i < 6; i++) {
            if (Math.abs(this.plantUvs[plane * 6 + i] - this.plantUvs[other * 6 + i]) > 1e-5f) return false;
        }
        return true;
    }

    private boolean plantUv(int base, float bottom, float top, float x0, float z0, float x1, float z1, int out) {
        float[] v = this.opaque.vertices;
        boolean start = false, end = false, up = false;
        for (int corner = 0; corner < 4; corner++) {
            int at = base + corner * QuadCollector.STRIDE;
            boolean low = Math.abs(v[at + 1] - bottom) < 0.01f;
            if (low && !start && sameXz(v[at], v[at + 2], x0, z0)) {
                this.plantUvs[out] = v[at + 3];
                this.plantUvs[out + 1] = v[at + 4];
                start = true;
            } else if (low && !end && sameXz(v[at], v[at + 2], x1, z1)) {
                this.plantUvs[out + 2] = v[at + 3];
                this.plantUvs[out + 3] = v[at + 4];
                end = true;
            } else if (Math.abs(v[at + 1] - top) < 0.01f && !up && sameXz(v[at], v[at + 2], x0, z0)) {
                this.plantUvs[out + 4] = v[at + 3];
                this.plantUvs[out + 5] = v[at + 4];
                up = true;
            }
        }
        return start && end && up;
    }

    private boolean samePlane(int plane, float x0, float z0, float x1, float z1) {
        int at = plane * 4;
        return sameXz(x0, z0, this.plantPlanes[at], this.plantPlanes[at + 1])
                && sameXz(x1, z1, this.plantPlanes[at + 2], this.plantPlanes[at + 3])
                || sameXz(x0, z0, this.plantPlanes[at + 2], this.plantPlanes[at + 3])
                && sameXz(x1, z1, this.plantPlanes[at], this.plantPlanes[at + 1]);
    }

    private static boolean sameXz(float x, float z, float otherX, float otherZ) {
        return Math.abs(x - otherX) < 0.01f && Math.abs(z - otherZ) < 0.01f;
    }

    private int layer(BlockState state, boolean solidLeaves) {
        if (solidLeaves) return SOLID;
        if (this.anyTranslucent) {
            boolean partial = false, holes = false;
            for (int pixel = 0; pixel < 6 * FaceRasterizer.PIXELS; pixel++) {
                if (this.faces.hits[pixel] == 0) continue;
                int alpha = this.faces.colour[pixel] >>> 24;
                partial |= alpha != 0 && alpha != 255;
                holes |= alpha != 255;
            }
            return partial ? TRANSLUCENT : holes ? CUTOUT : SOLID;
        }
        return this.anyDiscard ? CUTOUT : SOLID;
    }

    private boolean shows(int pixel, int layer) {
        return layer == SOLID ? this.faces.hits[pixel] != 0 : this.faces.colour[pixel] >>> 24 > 1;
    }

    private float depth(int face, int layer) {
        int base = face * FaceRasterizer.PIXELS;
        float sum = 0, nearest = Float.POSITIVE_INFINITY;
        int count = 0;
        for (int pixel = base; pixel < base + FaceRasterizer.PIXELS; pixel++) {
            if (!this.shows(pixel, layer) || !this.faces.drawn(pixel)) continue;
            float depth = this.faces.depth[pixel];
            sum += depth;
            nearest = Math.min(nearest, depth);
            count++;
        }
        if (count == 0) return -1;
        return layer == SOLID ? sum / count : nearest;
    }

    private long analyseFace(int face, int layer, float depth, Tint tint, int[] words, int opaqueMip) {
        int base = face * FaceRasterizer.PIXELS;
        int minU = 15, maxU = 0, minV = 15, maxV = 0, shown = 0;
        int tintedPixels = 0, colouredPixels = 0;
        for (int i = 0; i < FaceRasterizer.PIXELS; i++) {
            int pixel = base + i;
            if (!this.shows(pixel, layer)) continue;
            int u = i & 15, v = i >> 4;
            minU = Math.min(minU, u);
            maxU = Math.max(maxU, u);
            minV = Math.min(minV, v);
            maxV = Math.max(maxV, v);
            shown++;
            int colour = this.faces.colour[pixel];
            if ((colour & 0xFFFFFF) != 0 && colour >>> 24 != 0) {
                colouredPixels++;
                if (this.faces.tinted[pixel]) tintedPixels++;
            }
        }
        boolean wholeSide = minU == 0 && minV == 0 && maxU == 15 && maxV == 15;
        boolean translucent = layer == TRANSLUCENT;

        int word = minU | maxU << 4 | minV << 8 | maxV << 12;
        int inset = Math.round(depth * 64);
        word |= (inset >= 64 ? 63 : Math.min(inset, 62)) << 16;
        int extent = (maxU - minU + 1) * (maxV - minV + 1);
        if (!translucent && (layer != SOLID && opaqueMip != 0 || shown < 0.9f * extent)) word |= FACE_ALPHA_CUTOUT;
        if (!translucent && !wholeSide) word |= FACE_STRETCHED_CUTOUT;
        if ((word & (FACE_ALPHA_CUTOUT | FACE_STRETCHED_CUTOUT)) != 0 && opaqueMip > 0 && opaqueMip < ModelTextures.LEVELS) {
            word |= opaqueMip << FACE_OPAQUE_MIP_SHIFT;
        }
        if (tint.any() && tintedPixels != 0) {
            word |= (tintedPixels == colouredPixels ? TINT_ALWAYS : TINT_GREYSCALE) << 24;
        }
        words[face] = word;

        boolean occludes = !translucent && wholeSide && depth < 0.1f && shown > 0.9f * FaceRasterizer.PIXELS;
        boolean hideable = depth < 0.3f;
        boolean selfLit = depth > 0.01f;
        boolean flush = depth <= 0.01f;
        boolean cutout = (word & FACE_ALPHA_CUTOUT) != 0;
        boolean stretchedCutout = (word & FACE_STRETCHED_CUTOUT) != 0;
        return ModelInfo.face(face, occludes, hideable, selfLit, flush, cutout, stretchedCutout);
    }

    private boolean anyTinted() {
        for (boolean tinted : this.faces.tinted) {
            if (tinted) return true;
        }
        return false;
    }

    private Tint tint(BlockState state, boolean fluid) {
        List<BlockTintSource> sources;
        if (fluid) {
            var source = Minecraft.getInstance().getModelManager().getFluidStateModelSet().get(state.getFluidState()).tintSource();
            sources = source == null ? null : List.of(source);
        } else {
            sources = Minecraft.getInstance().getBlockColors().getTintSources(state);
            if (sources.isEmpty()) sources = null;
        }
        if (sources == null) return Tint.NONE;
        this.level.reset(state, this.defaultBiome, null);
        int colour = colourOf(sources, state, this.level);
        return new Tint(sources, state, this.level.askedForBiome, colour);
    }

    static int colourOf(List<BlockTintSource> sources, BlockState state, OneBlockLevel level) {
        for (var source : sources) {
            if (source == null) continue;
            int colour = source.colorInWorld(state, level, BlockPos.ZERO);
            if (colour != -1) return colour | 0xFF000000;
        }
        return -1;
    }

    private static boolean cullsSelf(BlockState state, boolean solidLeaves) {
        if (state.is(BlockTags.LEAVES)) return solidLeaves;
        for (var direction : DIRECTIONS) {
            if (!state.skipRendering(state, direction)) return false;
        }
        return true;
    }

    private static boolean aoCaster(BlockState state) {
        try {
            return state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static int lightEmission(BlockState state) {
        return state.emissiveRendering() ? 15 : Math.clamp(state.getLightEmission(), 0, 15);
    }

    private ModelKey key(int[] words, int flags, Tint tint, long info, int fluidModel, NativeBuffer texture) {
        var hash = new Hash128();
        for (int word : words) hash.add(word);
        hash.add(flags);
        hash.add(info);
        hash.add(tint.colour);
        if (tint.perBiome) {
            for (var biome : this.tintSamples) {
                this.level.reset(tint.state, biome, null);
                hash.add(colourOf(tint.sources, tint.state, this.level));
            }
        }
        for (float uv : this.fluidUv) {
            hash.add(Float.floatToRawIntBits(uv));
        }
        for (long at = 0; at < texture.size; at += 8) {
            hash.add(MemoryUtil.memGetLong(texture.address + at));
        }

        // Portable hash omits session local fluidModel id.
        // BlockModels folds in the fluids own hash.
        var portable = hash.copy();
        hash.add(fluidModel);
        return new ModelKey(hash.a, hash.b, portable.a ^ Long.rotateLeft(portable.b, 17));
    }

    private static final class Hash128 {
        long a = 0x243F6A8885A308D3L, b = 0x13198A2E03707344L;

        Hash128 copy() {
            var copy = new Hash128();
            copy.a = this.a;
            copy.b = this.b;
            return copy;
        }

        void add(long value) {
            this.a = Long.rotateLeft(this.a ^ mix(value), 31) * 0x9E3779B97F4A7C15L;
            this.b = Long.rotateLeft(this.b + mix(value ^ 0xD6E8FEB86659FD93L), 29) * 0xC2B2AE3D27D4EB4FL + 0x165667B19E3779F9L;
        }

        private static long mix(long z) {
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            return z ^ (z >>> 31);
        }
    }
}
