package cn.blockforge.generated.mod541a1b1d;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * 区域强加载：把玩家用 {@code /mfforceload add} 圈定的一整块长方形区块区域纳入强加载。
 *
 * <p>这一块和“被标记生物”的强加载完全独立：票据归属用的是每条区域专属的随机 UUID，
 * 而生物用的是生物自己的 UUID，所以移除区域绝不会误伤被标记的生物，反过来也一样。
 *
 * <p>指令 {@code /mfforceload add <x1> <z1> <x2> <z2>} 里玩家填的是<b>方块坐标</b>，
 * 本类负责换算成覆盖这片方块的<b>区块范围</b>再处理；名册里存的也是区块坐标。
 *
 * <p>铺法：一张区域票据本身就管着“以它为中心的 5x5 个区块”，所以只需要给中心区块挂票。
 * 相邻中心间隔 5 个区块时，前一个的辐射圈正好到后一个的辐射圈前一格收尾，两两相接，
 * 既不重叠也不留缝。因此铺满同样面积所需中心区块数最少，也就是性价比最高的方案，
 * 这也正是“隔 4 个普通区块一个中心”的意思。
 *
 * <p>关键点：<b>只给这些中心区块登记票据</b>。辐射圈里的其他区块由游戏自己的区块调度器
 * 在后台分批加载；如果连它们也一个个去挂票，等于把整片区域的地形当场同步生成一遍，
 * 圈一大片就会把 TPS 打到 0。具体见 {@link ForceloadTickets}。
 */
public final class ForceloadRegionManager {

    /** 相邻两个中心区块之间的间距（区块数）。5 表示两个中心中间正好夹 4 个普通区块。 */
    public static final int CENTER_SPACING = 5;

    /** 每个中心区块向四周辐射的半径。2 表示以它为中心的 5x5 个区块。 */
    public static final int CENTER_RADIUS = 2;

    /** 一个区块的边长（方块数）。指令里填的是方块坐标，换算成区块坐标时用它。 */
    public static final int CHUNK_SIZE = 16;

    /** 一次最多允许铺多少个中心区块，防止手滑输入巨大坐标把服务器拖垮。 */
    public static final int MAX_CENTERS = 16384;

    /**
     * 超过这个区块数就多提醒玩家一句。
     *
     * <p>分批挂票只是让“挂”的过程不卡；挂上之后这些区块每刻都要照常运算，
     * 长期下来照样很吃 CPU。圈个两三千个区块已经开始有感觉了，所以在这里拉条线。
     */
    public static final int WARN_CHUNKS = 2500;

    /**
     * 区域区块是不是要“会跑”的票据。
     *
     * <p>true 表示强加载的同时区块正常刻：范围里的生物照常寻路、攻击、成长，
     * 不会因为远离玩家被冻住。这正是这个功能想要的“5x5 范围内的生物都受强加载影响”。
     */
    private static final boolean TICKING = true;

    private ForceloadRegionManager() {
    }

    // ---------------------------------------------------------------- 数据

    /** 取出区域名册（只读用途）。 */
    public static List<ForceloadRegionData.Region> regions(MinecraftServer server) {
        ForceloadRegionData data = ForceloadRegionData.get(server);
        return Collections.unmodifiableList(data.regions());
    }

    /** 当前一共记着几条区域。 */
    public static int count(MinecraftServer server) {
        return ForceloadRegionData.get(server).regions().size();
    }

    /**
     * 当前已经强加载的区块总数：把名册里每条区域的辐射覆盖数加起来。
     *
     * <p>用来配合配置项 {@code maxForceLoadedChunks} 做总量保护。两条区域范围完全相同时
     * 会被当成重复、不会各记一份；范围部分重叠时这里会把重叠部分各算一次，偏保守，
     * 对“别一次圈太大”这个目的来说正合适。
     */
    public static long totalCoveredChunks(MinecraftServer server) {
        long total = 0;
        for (ForceloadRegionData.Region region : ForceloadRegionData.get(server).regions()) {
            total += coveredChunkCount(region.chunkX1, region.chunkZ1, region.chunkX2, region.chunkZ2);
        }
        return total;
    }

    // ---------------------------------------------------------------- 算法

    /**
     * 把方块坐标换算成它所在的区块坐标。
     *
     * <p>区块边长 16 格，而坐标是负数时普通除法会往 0 取整（-1 / 16 = 0），
     * 那样算出来的区块是错的。所以这里用 {@link Math#floorDiv(int, int)}：
     * -1 属于区块 -1，-16 属于区块 -1，-17 才属于区块 -2，与游戏里的划分一致。
     */
    public static int toChunk(int blockCoord) {
        return Math.floorDiv(blockCoord, CHUNK_SIZE);
    }

    /** 覆盖方块区间 {@code [block1, block2]} 所需的区块区间下限（含）。 */
    public static int minChunkOf(int block1, int block2) {
        return toChunk(Math.min(block1, block2));
    }

    /** 覆盖方块区间 {@code [block1, block2]} 所需的区块区间上限（含）。 */
    public static int maxChunkOf(int block1, int block2) {
        return toChunk(Math.max(block1, block2));
    }

    /**
     * 把方块坐标的矩形换算成区块范围后，预估需要多少个中心区块（用来判断是不是超限）。
     */
    public static long centerCountForBlocks(int blockX1, int blockZ1, int blockX2, int blockZ2) {
        return centerCount(minChunkOf(blockX1, blockX2), minChunkOf(blockZ1, blockZ2),
                maxChunkOf(blockX1, blockX2), maxChunkOf(blockZ1, blockZ2));
    }

    /**
     * 算出铺满区块区间 {@code [min, max]} 需要的中心区块坐标。
     *
     * <p>第一个中心放在 {@code min + 2}，这样它的 5x5 窗口最左边刚好落在 {@code min}；
     * 之后每 5 个区块放一个中心。中心个数 = {@code ceil(宽度 / 5)}，这就是能覆盖整段
     * 区间的最少个数。当宽度不是 5 的整数倍时，最后一个中心的窗口会往右多盖出几格，
     * 那几格属于“顺带强加载”，无害。
     */
    public static int[] centers(int min, int max) {
        int span = max - min + 1;
        if (span < 1) {
            span = 1;
        }
        int count = (span + CENTER_SPACING - 1) / CENTER_SPACING;
        int[] result = new int[count];
        for (int index = 0; index < count; index++) {
            result[index] = min + CENTER_RADIUS + index * CENTER_SPACING;
        }
        return result;
    }

    /** 预估一次添加需要多少个中心区块（用来判断是不是超限）。 */
    public static long centerCount(int chunkX1, int chunkZ1, int chunkX2, int chunkZ2) {
        int minX = Math.min(chunkX1, chunkX2);
        int maxX = Math.max(chunkX1, chunkX2);
        int minZ = Math.min(chunkZ1, chunkZ2);
        int maxZ = Math.max(chunkZ1, chunkZ2);
        return (long) centers(minX, maxX).length * centers(minZ, maxZ).length;
    }

    /**
     * 中心区块的 5x5 辐射圈拼起来最后覆盖了多少个区块。
     *
     * <p>这些辐射圈首尾相接、不重不漏，所以并集就是一块矩形：横向从第一个中心的窗口
     * 左边缘到最后一个中心的窗口右边缘，纵向同理。这个数是“真正被强加载的区块总数”，
     * 用来回显给玩家，和“挂了多少张中心票据”不是一回事。
     */
    public static int coveredChunkCount(int chunkX1, int chunkZ1, int chunkX2, int chunkZ2) {
        int minX = Math.min(chunkX1, chunkX2);
        int maxX = Math.max(chunkX1, chunkX2);
        int minZ = Math.min(chunkZ1, chunkZ2);
        int maxZ = Math.max(chunkZ1, chunkZ2);
        int[] centersX = centers(minX, maxX);
        int[] centersZ = centers(minZ, maxZ);
        int width = centersX[centersX.length - 1] - centersX[0] + 1 + CENTER_RADIUS * 2;
        int depth = centersZ[centersZ.length - 1] - centersZ[0] + 1 + CENTER_RADIUS * 2;
        return width * depth;
    }

    // ---------------------------------------------------------------- 添加

    /**
     * 把一块区域加进强加载。
     *
     * @return 处理结果，供指令回显；{@link AddResult#duplicate} 为 true 表示这个范围早就加过了
     */
    public static AddResult addRegion(ServerLevel level, int chunkX1, int chunkZ1, int chunkX2, int chunkZ2) {
        int minX = Math.min(chunkX1, chunkX2);
        int maxX = Math.max(chunkX1, chunkX2);
        int minZ = Math.min(chunkZ1, chunkZ2);
        int maxZ = Math.max(chunkZ1, chunkZ2);

        AddResult result = new AddResult();
        result.minX = minX;
        result.maxX = maxX;
        result.minZ = minZ;
        result.maxZ = maxZ;
        int[] centersX = centers(minX, maxX);
        int[] centersZ = centers(minZ, maxZ);
        result.centers = centersX.length * centersZ.length;
        // 相邻中心的 5x5 辐射圈首尾相接、不重不漏，并集正好是一整块矩形，
        // 这块矩形就是真正要强加载的全部区块。
        result.chunks = coveredChunkCount(minX, minZ, maxX, maxZ);

        MinecraftServer server = level.getServer();
        ForceloadRegionData data = ForceloadRegionData.get(server);

        // 同样的维度、同样的范围已经加过就不用再加一遍，免得白占票据。
        for (ForceloadRegionData.Region existing : data.regions()) {
            if (existing.dimension.equals(level.dimension())
                    && existing.chunkX1 == minX && existing.chunkX2 == maxX
                    && existing.chunkZ1 == minZ && existing.chunkZ2 == maxZ) {
                result.duplicate = true;
                return result;
            }
        }

        // 总量保护：这次要覆盖的区块加上已经强加载的，超过配置上限就拒绝，不改动任何东西。
        // 放在去重之后，这样“重复添加同一块区域”仍然按重复提示，而不是被上限拦下。
        long existing = totalCoveredChunks(server);
        int limit = ForceloadManager.maxForceLoadedChunks();
        if (existing + result.chunks > limit) {
            result.overLimit = true;
            result.existingChunks = existing;
            result.chunkLimit = limit;
            return result;
        }

        ForceloadRegionData.Region region = new ForceloadRegionData.Region();
        region.owner = UUID.randomUUID();
        region.dimension = level.dimension();
        region.chunkX1 = minX;
        region.chunkZ1 = minZ;
        region.chunkX2 = maxX;
        region.chunkZ2 = maxZ;
        data.regions().add(region);
        data.setDirty();

        applyRegion(level, region);
        return result;
    }

    /**
     * 按方块坐标把一块区域加进强加载：指令里玩家填的就是这个方块坐标的矩形。
     *
     * <p>先把两个角换算成覆盖它们的区块范围（例如方块 -50~50 落在区块 -4~3），
     * 之后的一切（去重、铺中心、存盘、恢复）都跟以前一样按区块坐标走。
     */
    public static AddResult addRegionBlocks(ServerLevel level, int blockX1, int blockZ1, int blockX2, int blockZ2) {
        int minBlockX = Math.min(blockX1, blockX2);
        int maxBlockX = Math.max(blockX1, blockX2);
        int minBlockZ = Math.min(blockZ1, blockZ2);
        int maxBlockZ = Math.max(blockZ1, blockZ2);

        AddResult result = addRegion(level,
                toChunk(minBlockX), toChunk(minBlockZ), toChunk(maxBlockX), toChunk(maxBlockZ));
        // 顺手把玩家填的方块坐标也带上，方便指令原样回显，让玩家看明白换算结果。
        result.blockX1 = minBlockX;
        result.blockZ1 = minBlockZ;
        result.blockX2 = maxBlockX;
        result.blockZ2 = maxBlockZ;
        return result;
    }

    // ---------------------------------------------------------------- 移除

    /** 移除全部手动添加的区域强加载，返回移除掉的区域条数。生物的强加载不受影响。 */
    public static int removeAll(MinecraftServer server) {
        ForceloadRegionData data = ForceloadRegionData.get(server);
        List<ForceloadRegionData.Region> regions = data.regions();
        if (regions.isEmpty()) {
            return 0;
        }
        int removed = 0;
        for (ForceloadRegionData.Region region : regions) {
            ServerLevel level = server.getLevel(region.dimension);
            if (level != null) {
                releaseRegion(level, region);
            }
            removed++;
        }
        regions.clear();
        data.setDirty();
        return removed;
    }

    // ---------------------------------------------------------------- 恢复

    /**
     * 服务器启动时按名册把区域票据重新挂一遍。
     *
     * <p>新版本的区域票据是直接登记在游戏的区块调度器上的，不会写进存档，所以每次启动
     * 都要照名册重挂一次；重挂是幂等的（同一个归属对同一个区块重复登记会被自动忽略）。
     *
     * <p>另外还要做一次升级清理：旧版本是给每个中心的整块 5x5 都挂 Forge 票据的，
     * 那些票被写进了存档、会被 Forge 在读档时恢复。这里按名册把它们清一遍，
     * 之后只保留“中心有票”的新铺法。清理只做一次，结果记在名册里。
     */
    public static void restoreAll(MinecraftServer server) {
        ForceloadRegionData data = ForceloadRegionData.get(server);
        if (!data.legacyPurged()) {
            for (ForceloadRegionData.Region region : data.regions()) {
                ServerLevel level = server.getLevel(region.dimension);
                if (level == null) {
                    continue;
                }
                int[] centersX = centers(region.chunkX1, region.chunkX2);
                int[] centersZ = centers(region.chunkZ1, region.chunkZ2);
                for (int centerX : centersX) {
                    for (int centerZ : centersZ) {
                        ForceloadTickets.purgeLegacyWindow(level, region.owner, centerX, centerZ);
                    }
                }
            }
            data.setLegacyPurged(true);
            data.setDirty();
        }
        for (ForceloadRegionData.Region region : data.regions()) {
            ServerLevel level = server.getLevel(region.dimension);
            if (level != null) {
                applyRegion(level, region);
            }
        }
    }

    // -------------------------------------------------------------- 票据操作

    /**
     * 给这块区域登记强加载票据。
     *
     * <p>只给中心区块登记：一张区域票据本身就管它周围 5x5 个区块，相邻中心间隔 5，
     * 辐射圈正好拼满整片区域。辐射圈里的其他区块由游戏后台加载，这里绝不再逐个挂票，
     * 否则等于把整片区域的地形当场同步生成一遍（见 {@link ForceloadTickets}）。
     *
     * <p>登记本身很便宜，但仍然排进分批通道，把“一次登记几万张票”的开销也摊到很多刻里。
     */
    private static void applyRegion(ServerLevel level, ForceloadRegionData.Region region) {
        int[] centersX = centers(region.chunkX1, region.chunkX2);
        int[] centersZ = centers(region.chunkZ1, region.chunkZ2);
        for (int centerX : centersX) {
            for (int centerZ : centersZ) {
                ForceloadTickets.scheduleChunk(region.dimension, region.owner, centerX, centerZ, TICKING);
            }
        }
    }

    private static void releaseRegion(ServerLevel level, ForceloadRegionData.Region region) {
        // 先丢掉还没执行的登记任务，免得撤销之后它们又迟到一步把区块挂上。
        ForceloadTickets.cancelPending(region.owner);
        int[] centersX = centers(region.chunkX1, region.chunkX2);
        int[] centersZ = centers(region.chunkZ1, region.chunkZ2);
        for (int centerX : centersX) {
            for (int centerZ : centersZ) {
                ForceloadTickets.remove(level, region.owner, centerX, centerZ);
                // 顺手清掉旧版本遗留在中心周围的 5x5 票据。
                ForceloadTickets.purgeLegacyWindow(level, region.owner, centerX, centerZ);
            }
        }
    }

    /** 一次添加的处理结果。 */
    public static final class AddResult {
        public boolean duplicate;
        /** 是否因为“本次 + 已有”超过配置的区块总数上限而被拒绝。 */
        public boolean overLimit;
        /** 被拒绝时，当前已经强加载的区块总数。 */
        public long existingChunks;
        /** 被拒绝时，配置里的区块总数上限。 */
        public int chunkLimit;
        /** 玩家在指令里填的方块坐标矩形（已经保证 x1<=x2、z1<=z2）。 */
        public int blockX1;
        public int blockX2;
        public int blockZ1;
        public int blockZ2;
        /** 换算后、真正用于铺中心区块的区块范围。 */
        public int minX;
        public int maxX;
        public int minZ;
        public int maxZ;
        /** 需要的中心区块个数。 */
        public int centers;
        /** 展开后真正要强加载的区块总数。 */
        public int chunks;
    }
}
