package cn.blockforge.generated.mod541a1b1d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.world.ForgeChunkManager;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 强加载管理器：维护“哪些生物被标记了”，以及围绕它们强加载哪些区块。
 *
 * <p>区块票据用的是 Forge 的 {@link ForgeChunkManager}，好处是它会把票据写进存档，
 * 服务器重启后自动恢复；而且按“生物 UUID”记账，某只生物消失时可以精确地只释放它自己的区块。
 */
public final class ForceloadManager {

    /** 每隔多少次更新，完整扫一遍世界里的实体做兜底校准。 */
    private static final int SCAN_EVERY_PASSES = 10;

    /** 名册里的生物连续几次没找到之后，就认定它已经消失了。 */
    private static final int MISSING_PASS_LIMIT = 10;

    /** 每只被标记生物的运行时状态（不进存档；重启后第一遍会照名册重建）。 */
    private static final Map<UUID, Applied> APPLIED = new HashMap<>();

    private static int tickCounter;
    private static int scanCounter;

    // 配置缓存：实体每刻都会调用 onLivingTick，不能每次都去读配置文件。
    private static volatile int cachedRadius = 4;
    private static volatile boolean cachedSimulate = true;
    private static volatile int cachedInterval = 20;
    private static volatile boolean cachedGlowing;
    private static volatile int cachedTicketLimit = 32;
    /** 区域强加载合计允许的区块总数上限，默认 1000。 */
    private static volatile int cachedMaxChunks = 1000;

    private ForceloadManager() {
    }

    /** 某只生物当前实际生效的强加载窗口。 */
    private static final class Applied {
        boolean hasWindow;
        int centerX;
        int centerZ;
        int radius;
        boolean simulate;
        int missing;
    }

    // ------------------------------------------------------------------ 标记

    /** 这只生物是不是被本模组标记了。 */
    public static boolean isMarked(Entity entity) {
        return entity.getPersistentData().getBoolean(MobForceload.NBT_MARKED);
    }

    /** 给生物打上标记并登记强加载。 */
    public static void mark(Entity entity, MinecraftServer server) {
        entity.getPersistentData().putBoolean(MobForceload.NBT_MARKED, true);
        entity.addTag(MobForceload.TAG_MARKED);
        keepPersistent(entity);
        if (cachedGlowing) {
            entity.setGlowingTag(true);
        }
        track(entity, server);
    }

    /** 取消标记，并释放它占用的所有强加载区块。 */
    public static boolean unmark(Entity entity, MinecraftServer server) {
        if (!isMarked(entity)) {
            return false;
        }
        clearMark(entity);
        if (server != null) {
            MarkedMobData data = MarkedMobData.get(server);
            UUID uuid = entity.getUUID();
            MarkedMobData.TrackedMob recorded = data.mobs().remove(uuid);
            Applied applied = APPLIED.remove(uuid);
            if (entity.level() instanceof ServerLevel level) {
                if (applied != null && applied.hasWindow) {
                    clearWindow(level, uuid, applied.centerX, applied.centerZ, applied.radius);
                } else {
                    BlockPos pos = recorded != null ? recorded.pos : entity.blockPosition();
                    clearWindow(level, uuid, pos.getX() >> 4, pos.getZ() >> 4, cachedRadius);
                }
            }
            if (recorded != null) {
                data.setDirty();
            }
        }
        return true;
    }

    /** 一次性取消全部标记，连不在加载区块里的也算上。返回处理掉的个数。 */
    public static int unmarkEverything(MinecraftServer server) {
        MarkedMobData data = MarkedMobData.get(server);
        List<UUID> ids = new ArrayList<>(data.mobs().keySet());
        int count = 0;
        for (UUID uuid : ids) {
            MarkedMobData.TrackedMob recorded = data.mobs().get(uuid);
            if (recorded == null) {
                continue;
            }
            ServerLevel level = server.getLevel(recorded.dimension);
            Entity entity = level == null ? null : level.getEntity(uuid);
            if (entity != null) {
                clearMark(entity);
            }
            data.mobs().remove(uuid);
            Applied applied = APPLIED.remove(uuid);
            if (level != null) {
                if (applied != null && applied.hasWindow) {
                    clearWindow(level, uuid, applied.centerX, applied.centerZ, applied.radius);
                } else {
                    clearWindow(level, uuid, recorded.pos.getX() >> 4, recorded.pos.getZ() >> 4, cachedRadius);
                }
            }
            count++;
        }
        if (count > 0) {
            data.setDirty();
        }
        return count;
    }

    private static void clearMark(Entity entity) {
        entity.getPersistentData().remove(MobForceload.NBT_MARKED);
        entity.removeTag(MobForceload.TAG_MARKED);
        entity.setGlowingTag(false);
    }

    // -------------------------------------------------------------- 登记名册

    /** 把生物写进名册，并立刻建立/更新它的强加载窗口。 */
    public static void track(Entity entity, MinecraftServer server) {
        track(entity, server, true);
    }

    /**
     * 把生物写进名册。
     *
     * @param buildWindowNow true 时立刻建立强加载窗口；实体正在从磁盘加载时应当传 false，
     *                       避免在加载区块的过程中又去加载别的区块，交给下一次定时校准补上
     */
    public static void track(Entity entity, MinecraftServer server, boolean buildWindowNow) {
        if (server == null) {
            return;
        }
        MarkedMobData data = MarkedMobData.get(server);
        UUID uuid = entity.getUUID();
        MarkedMobData.TrackedMob recorded = data.mobs().get(uuid);
        boolean fresh = recorded == null;
        if (fresh) {
            recorded = new MarkedMobData.TrackedMob();
            recorded.uuid = uuid;
            data.mobs().put(uuid, recorded);
        }
        ResourceKey<Level> previousDimension = recorded.dimension;
        recorded.dimension = entity.level().dimension();
        recorded.pos = entity.blockPosition();
        recorded.typeId = typeId(entity);
        data.setDirty();

        boolean dimensionChanged = previousDimension != null && !previousDimension.equals(recorded.dimension);
        if (fresh || dimensionChanged) {
            // 换了维度：旧的强加载窗口留在旧维度的世界里，必须在那里撤掉，否则会一直强加载。
            Applied stale = APPLIED.remove(uuid);
            if (stale != null && stale.hasWindow && previousDimension != null) {
                ServerLevel previousLevel = server.getLevel(previousDimension);
                if (previousLevel != null) {
                    clearWindow(previousLevel, uuid, stale.centerX, stale.centerZ, stale.radius);
                }
            }
        }
        if (buildWindowNow && entity.level() instanceof ServerLevel level) {
            Applied applied = APPLIED.computeIfAbsent(uuid, key -> new Applied());
            applyWindow(level, uuid, recorded.pos, cachedRadius, cachedSimulate, applied);
        }
    }

    /**
     * 传送发生之前的“抢跑”登记：把强加载窗口立刻搬到目标位置。
     *
     * <p>原版 {@code /spreadplayers ~ ~ 150 400 false @e} 会把生物甩到很远的地方。
     * 如果目标区块当时没加载，生物传送过去就不落在任何已加载区块里，等于丢了：
     * AI 不会跑，定时校准也可能一时找不到它。所以要在传送真正发生之前
     * （Forge 的传送事件里）先把目标区块强加载起来，再让它落地。
     *
     * @param targetX 生物即将被传送到的坐标（不是它现在的位置）
     */
    public static void retarget(Entity entity, double targetX, double targetY, double targetZ) {
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }
        MinecraftServer server = level.getServer();
        if (server == null) {
            return;
        }
        refreshCache();
        UUID uuid = entity.getUUID();
        MarkedMobData data = MarkedMobData.get(server);
        MarkedMobData.TrackedMob recorded = data.mobs().get(uuid);
        if (recorded == null) {
            // 名册里还没有它（例如刚读档、还没轮到兜底扫描），先补登记再搬窗口。
            track(entity, server, false);
            recorded = data.mobs().get(uuid);
            if (recorded == null) {
                return;
            }
        } else if (!level.dimension().equals(recorded.dimension)) {
            // 跨维度传送：旧维度里那圈强加载要撤掉，否则会一直白扣着。
            releaseStaleWindow(server, uuid, recorded.dimension);
        }

        BlockPos target = BlockPos.containing(targetX, targetY, targetZ);
        recorded.dimension = level.dimension();
        recorded.pos = target;
        recorded.typeId = typeId(entity);
        data.setDirty();

        Applied applied = APPLIED.computeIfAbsent(uuid, key -> new Applied());
        // 立刻把窗口挪到目标区块；applyWindow 内部会先撤旧窗口再加新窗口。
        applyWindow(level, uuid, target, cachedRadius, cachedSimulate, applied);
    }

    /** 撤掉某个维度里残留的强加载窗口（换维度时用）。 */
    private static void releaseStaleWindow(MinecraftServer server, UUID uuid, ResourceKey<Level> dimension) {
        Applied stale = APPLIED.remove(uuid);
        if (stale != null && stale.hasWindow && dimension != null) {
            ServerLevel previousLevel = server.getLevel(dimension);
            if (previousLevel != null) {
                clearWindow(previousLevel, uuid, stale.centerX, stale.centerZ, stale.radius);
            }
        }
    }

    // ------------------------------------------------------------------ 每刻

    /** 由 ServerTickEvent 每刻调用。 */
    public static void serverTick(MinecraftServer server) {
        if (server == null || server.isStopped()) {
            return;
        }
        refreshCache();
        if (++tickCounter < cachedInterval) {
            return;
        }
        tickCounter = 0;
        reconcile(server);
        if (++scanCounter >= SCAN_EVERY_PASSES) {
            scanCounter = 0;
            scanMarkedEntities(server);
        }
    }

    /** 服务器启动时立刻做一次，免得刚进世界那一秒强加载还没生效。 */
    public static void reconcileNow(MinecraftServer server) {
        if (server == null) {
            return;
        }
        refreshCache();
        reconcile(server);
    }

    /** 单人游戏切换存档时清掉运行时状态。 */
    public static void reset() {
        APPLIED.clear();
        tickCounter = 0;
        scanCounter = 0;
    }

    /**
     * 核心：照名册把每只被标记生物的强加载窗口校准到最新位置。
     *
     * <p>同时负责“认领”那些因为区块卸载、维度切换而暂时找不到的生物，
     * 以及清理确实已经消失（死亡、被清除）的生物留下的区块。
     */
    private static void reconcile(MinecraftServer server) {
        MarkedMobData data = MarkedMobData.get(server);
        int radius = cachedRadius;
        boolean simulate = cachedSimulate;
        boolean dirty = false;

        Iterator<Map.Entry<UUID, MarkedMobData.TrackedMob>> iterator = data.mobs().entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, MarkedMobData.TrackedMob> entry = iterator.next();
            UUID uuid = entry.getKey();
            MarkedMobData.TrackedMob recorded = entry.getValue();
            ServerLevel level = server.getLevel(recorded.dimension);
            if (level == null) {
                APPLIED.remove(uuid);
                iterator.remove();
                dirty = true;
                continue;
            }

            Applied applied = APPLIED.computeIfAbsent(uuid, key -> new Applied());
            Entity entity = level.getEntity(uuid);

            if (entity != null && !entity.isRemoved()) {
                applied.missing = 0;
                if (!isMarked(entity)) {
                    // 标记被别人清掉了，强加载也跟着撤掉。
                    releaseWindow(level, uuid, applied);
                    APPLIED.remove(uuid);
                    iterator.remove();
                    dirty = true;
                    continue;
                }
                BlockPos pos = entity.blockPosition();
                if (!pos.equals(recorded.pos)) {
                    recorded.pos = pos;
                    dirty = true;
                }
                keepPersistent(entity);
            } else {
                applied.missing++;
                boolean positionLoaded = level.getChunkSource()
                        .hasChunk(recorded.pos.getX() >> 4, recorded.pos.getZ() >> 4);
                if (applied.missing > MISSING_PASS_LIMIT && positionLoaded) {
                    // 区块明明加载着却还是找不到它，说明这只生物确实没了。
                    releaseWindow(level, uuid, applied);
                    APPLIED.remove(uuid);
                    iterator.remove();
                    dirty = true;
                    continue;
                }
            }

            applyWindow(level, uuid, recorded.pos, radius, simulate, applied);
        }

        if (dirty) {
            data.setDirty();
        }
    }

    /** 兜底扫描：实体身上带着标记但名册里没有（例如换维度、存档数据缺失）时补登记。 */
    private static void scanMarkedEntities(MinecraftServer server) {
        MarkedMobData data = MarkedMobData.get(server);
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (!isMarked(entity)) {
                    continue;
                }
                UUID uuid = entity.getUUID();
                MarkedMobData.TrackedMob recorded = data.mobs().get(uuid);
                if (recorded == null
                        || !recorded.dimension.equals(level.dimension())
                        || !recorded.pos.equals(entity.blockPosition())) {
                    track(entity, server);
                }
            }
        }
    }

    // -------------------------------------------------------------- 区块票据

    /**
     * 让某个窗口内的区块强加载状态与期望一致。
     *
     * @param simulate true 时用 Forge 的“强制刻”票据，让区块不依赖玩家也能完整运转
     */
    private static void applyWindow(ServerLevel level, UUID uuid, BlockPos pos, int radius, boolean simulate, Applied applied) {
        int centerX = pos.getX() >> 4;
        int centerZ = pos.getZ() >> 4;
        if (applied.hasWindow
                && applied.centerX == centerX
                && applied.centerZ == centerZ
                && applied.radius == radius
                && applied.simulate == simulate) {
            return;
        }
        if (applied.hasWindow) {
            clearWindow(level, uuid, applied.centerX, applied.centerZ, applied.radius);
        }
        addWindow(level, uuid, centerX, centerZ, radius, simulate);
        applied.hasWindow = true;
        applied.centerX = centerX;
        applied.centerZ = centerZ;
        applied.radius = radius;
        applied.simulate = simulate;
    }

    private static void addWindow(ServerLevel level, UUID uuid, int centerX, int centerZ, int radius, boolean simulate) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                // 同一个区块重复加票据是安全的：已经记过就直接返回。
                ForgeChunkManager.forceChunk(level, MobForceload.MOD_ID, uuid, centerX + dx, centerZ + dz, true, simulate);
            }
        }
    }

    private static void clearWindow(ServerLevel level, UUID uuid, int centerX, int centerZ, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                // 两种票据都撤一遍，不管当初是按哪种加的都能清干净。
                ForgeChunkManager.forceChunk(level, MobForceload.MOD_ID, uuid, centerX + dx, centerZ + dz, false, false);
                ForgeChunkManager.forceChunk(level, MobForceload.MOD_ID, uuid, centerX + dx, centerZ + dz, false, true);
            }
        }
    }

    private static void releaseWindow(ServerLevel level, UUID uuid, Applied applied) {
        if (applied.hasWindow) {
            clearWindow(level, uuid, applied.centerX, applied.centerZ, applied.radius);
            applied.hasWindow = false;
        }
    }

    // -------------------------------------------------------------------- 杂项

    /** 被标记的生物不许自然消失（走远了、没玩家在附近也不能刷掉）。 */
    private static void keepPersistent(Entity entity) {
        if (entity instanceof Mob mob && !mob.isPersistenceRequired()) {
            mob.setPersistenceRequired();
        }
    }

    /** 生物类型的字符串 ID，例如 {@code minecraft:zombie}。 */
    public static String typeId(Entity entity) {
        EntityType<?> type = entity.getType();
        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(type);
        return id == null ? "unknown" : id.toString();
    }

    private static void refreshCache() {
        try {
            cachedRadius = MobForceloadConfig.CHUNK_RADIUS.get();
            cachedSimulate = MobForceloadConfig.KEEP_SIMULATION_DISTANCE.get();
            cachedInterval = Math.max(1, MobForceloadConfig.UPDATE_INTERVAL_TICKS.get());
            cachedGlowing = MobForceloadConfig.GLOWING.get();
            cachedTicketLimit = Math.max(1, MobForceloadConfig.FORCELOAD_TICKETS_PER_TICK.get());
            cachedMaxChunks = Math.max(1, MobForceloadConfig.MAX_FORCELOAD_CHUNKS.get());
        } catch (RuntimeException exception) {
            // 配置还没加载好时，先沿用上一次的值。
        }
    }

    public static int radius() {
        return cachedRadius;
    }

    public static boolean simulate() {
        return cachedSimulate;
    }

    /** 每个游戏刻最多挂多少个强加载区块（分批生效用）。 */
    public static int ticketsPerTick() {
        return cachedTicketLimit;
    }

    /** 区域强加载合计最多允许多少个区块（配置项 maxForceLoadedChunks）。 */
    public static int maxForceLoadedChunks() {
        return cachedMaxChunks;
    }
}
