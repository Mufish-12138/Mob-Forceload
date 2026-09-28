package cn.blockforge.generated.mod541a1b1d;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.UUID;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.world.ForgeChunkManager;

/**
 * 区域强加载的“票据通道”。
 *
 * <p>先说清楚一个关键事实：Forge 的 {@code ForgeChunkManager.forceChunk(..., 挂上, ...)}
 * 内部会调用 {@code ServerLevel.getChunk(x, z)}，也就是<b>当场把这块地的地形生成出来</b>。
 * 所以如果逐个区块去挂强加载，代价就是同步生成整片区域的地形，服务器必然卡死。
 *
 * <p>但没有必要逐个区块挂：一张“距离 2”的区域票据，游戏会自动把票据所在区块定为
 * “实体刻”那一级，再往外一圈降一级，一共把以它为中心的 <b>5x5</b> 个区块都加载起来。
 * 换句话说，<b>一张票据就管 5x5</b>。给一片区域挂强加载，只要按 5 个区块的间距挑出
 * 中心区块、给这些中心各挂一张票据即可；辐射圈里的其他区块交给游戏自己的区块调度器
 * 在后台分批加载，不占主线程、更不会当场生成地形。
 *
 * <p>这里调用的是 {@link net.minecraft.server.level.ServerChunkCache#addRegionTicket}，
 * 也就是 Forge 内部最终真正调用的那一步，但<b>跳过了它前面那次 {@code getChunk}</b>：
 * 只登记票据、不生成地形，什么时候加载由游戏自己安排。于是“圈一大片区域”不再等于
 * “一口气生成一大片地形”。
 *
 * <p>登记票据本身很便宜，但一次登记几万张会让游戏在同一刻处理几万条区块等级更新。
 * 所以这里仍然排进队列、每个游戏刻处理一小批（数量由配置项
 * {@code forceloadTicketsPerTick} 控制），把这点开销也摊平。
 */
public final class ForceloadTickets {

    /**
     * 本模组自己的区域票据类型。
     *
     * <p>用自定义类型而不是原版 {@code TicketType.FORCED}，是为了把“票据归属”写进票据本身：
     * 票据的值就是这张票的主人（区域或预设的 UUID）。这样两个区域即便中心位置重叠，
     * 也是各挂各的票、各撤各的票，不会互相误伤。
     */
    public static final TicketType<UUID> REGION_TICKET =
            TicketType.create("mob_forceload_region", Comparator.<UUID>naturalOrder());

    /**
     * 区域票据的辐射距离（区块）。
     *
     * <p>游戏把票据所在区块定为“完整区块等级 − 距离”，也就是“实体刻”那一级，
     * 再每往外一圈降一级。距离 2 正好让最外圈落在“已完成”等级上，覆盖 5x5 个区块。
     * 这个取值和 Forge 自己挂票时用的完全一致。
     */
    public static final int RADIUS = 2;

    /** 待处理的登记任务，先进先出。 */
    private static final Deque<Op> QUEUE = new ArrayDeque<>();

    private ForceloadTickets() {
    }

    /** 一条“给某个中心区块登记区域票据”的任务。 */
    private static final class Op {
        private final ResourceKey<Level> dimension;
        private final UUID owner;
        private final int chunkX;
        private final int chunkZ;
        private final boolean ticking;

        private Op(ResourceKey<Level> dimension, UUID owner, int chunkX, int chunkZ, boolean ticking) {
            this.dimension = dimension;
            this.owner = owner;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.ticking = ticking;
        }
    }

    /** 排入一个中心区块的登记任务。 */
    public static void scheduleChunk(ResourceKey<Level> dimension, UUID owner, int chunkX, int chunkZ, boolean ticking) {
        QUEUE.addLast(new Op(dimension, owner, chunkX, chunkZ, ticking));
    }

    /**
     * 把某个归属下还没执行的登记任务全部取消。
     *
     * <p>用在“区域被移除 / 预设被禁用”的时候：这些票本来就还没登记，直接丢掉即可，
     * 免得取消之后它们又迟到一步把区块挂上。
     */
    public static void cancelPending(UUID owner) {
        QUEUE.removeIf(op -> op.owner.equals(owner));
    }

    /** 每个游戏刻调用：最多处理 {@code limit} 张票据，返回这次实际登记了多少张。 */
    public static int tick(MinecraftServer server, int limit) {
        if (server == null) {
            return 0;
        }
        int done = 0;
        while (done < limit && !QUEUE.isEmpty()) {
            Op op = QUEUE.pollFirst();
            ServerLevel level = server.getLevel(op.dimension);
            if (level == null) {
                // 维度不存在（例如相关模组被移除），跳过这条，不占这一刻的额度。
                continue;
            }
            add(level, op.owner, op.chunkX, op.chunkZ, op.ticking);
            done++;
        }
        return done;
    }

    /**
     * 给一个中心区块登记区域票据，<b>不</b>同步生成地形。
     *
     * <p>票据一登记，游戏就会把它辐射到的 5x5 区块排进正常的区块加载流程，
     * 由区块调度器在后台按自己的节奏加载，所以这里不会阻塞主线程。
     *
     * @param ticking true 表示用电票据，让辐射范围内的区块尽量维持正常运转
     */
    public static void add(ServerLevel level, UUID owner, int chunkX, int chunkZ, boolean ticking) {
        level.getChunkSource().addRegionTicket(REGION_TICKET, new ChunkPos(chunkX, chunkZ), RADIUS, owner, ticking);
    }

    /** 撤掉某个中心区块上的区域票据（挂票和撤票成对，才会彻底清干净）。 */
    public static void remove(ServerLevel level, UUID owner, int chunkX, int chunkZ) {
        ChunkPos pos = new ChunkPos(chunkX, chunkZ);
        // 当初可能是按“维持/不维持模拟距离”两种票据之一挂的，这里两种都撤一遍，确保撤干净。
        level.getChunkSource().removeRegionTicket(REGION_TICKET, pos, RADIUS, owner, true);
        level.getChunkSource().removeRegionTicket(REGION_TICKET, pos, RADIUS, owner, false);
    }

    /**
     * 清掉旧版本遗留在中心区块周围的 5x5 票据。
     *
     * <p>旧版本是给每个中心的 25 个区块各挂一张 Forge 票据的，这些票会写进存档、
     * 重启时被 Forge 自动恢复。新版本只挂中心，所以这里把内圈 5x5 全部撤一遍，
     * 让从旧版本升级上来的存档也收敛成“只有中心有票”。撤不存在的票是空操作，很便宜。
     */
    public static void purgeLegacyWindow(ServerLevel level, UUID owner, int centerX, int centerZ) {
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                ForgeChunkManager.forceChunk(level, MobForceload.MOD_ID, owner, centerX + dx, centerZ + dz, false, false);
                ForgeChunkManager.forceChunk(level, MobForceload.MOD_ID, owner, centerX + dx, centerZ + dz, false, true);
            }
        }
    }

    /** 队列里还排着多少条任务。 */
    public static int pending() {
        return QUEUE.size();
    }

    /** 清空队列（服务器停止时用，下次启动会照名册重新排）。 */
    public static void clear() {
        QUEUE.clear();
    }
}
