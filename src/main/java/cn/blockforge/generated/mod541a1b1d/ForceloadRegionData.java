package cn.blockforge.generated.mod541a1b1d;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 存档级别的“用 /mfforceload add 添加的区域强加载”名册。
 *
 * <p>这份名册和 {@link MarkedMobData} 各管各的：这里只记玩家手动圈定的区域，
 * 不会碰任何被标记的生物。每条记录存 4 个区块坐标（范围的两个角）和一个票据
 * 归属用 UUID，重启服务器后照着它就能把区域强加载恢复回来。
 */
public final class ForceloadRegionData extends SavedData {

    private static final String FILE_ID = "mob_forceload_regions";
    private static final String KEY_REGIONS = "regions";
    private static final String KEY_OWNER = "owner";
    private static final String KEY_DIMENSION = "dimension";
    private static final String KEY_MIN = "min";
    private static final String KEY_MAX = "max";
    private static final String KEY_LEGACY_PURGED = "legacyPurged";

    private final List<Region> regions = new ArrayList<>();

    /**
     * 旧版本的遗留票据是否已经清理过。
     *
     * <p>旧版本给每个中心的整块 5x5 都挂了 Forge 票据，这些票会被 Forge 从存档里恢复出来；
     * 新版本只挂中心，所以第一次运行新版本时要把它们清掉。清一次就够了，用一个标记记住，
     * 免得每次开服都白扫一遍。
     */
    private boolean legacyPurged;

    public List<Region> regions() {
        return this.regions;
    }

    public boolean legacyPurged() {
        return this.legacyPurged;
    }

    public void setLegacyPurged(boolean value) {
        this.legacyPurged = value;
    }

    /** 从主世界的数据存储里取出（没有就新建）这份区域名册。 */
    public static ForceloadRegionData get(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(ForceloadRegionData::load, ForceloadRegionData::new, FILE_ID);
    }

    public static ForceloadRegionData load(CompoundTag tag) {
        ForceloadRegionData data = new ForceloadRegionData();
        data.legacyPurged = tag.getBoolean(KEY_LEGACY_PURGED);
        ListTag list = tag.getList(KEY_REGIONS, Tag.TAG_COMPOUND);
        for (int index = 0; index < list.size(); index++) {
            CompoundTag entry = list.getCompound(index);
            if (!entry.hasUUID(KEY_OWNER)) {
                continue;
            }
            ResourceLocation dimensionId = ResourceLocation.tryParse(entry.getString(KEY_DIMENSION));
            if (dimensionId == null) {
                continue;
            }
            long min = entry.getLong(KEY_MIN);
            long max = entry.getLong(KEY_MAX);
            int firstX = (int) (min >> 32);
            int firstZ = (int) min;
            int secondX = (int) (max >> 32);
            int secondZ = (int) max;

            Region region = new Region();
            region.owner = entry.getUUID(KEY_OWNER);
            region.dimension = ResourceKey.create(Registries.DIMENSION, dimensionId);
            region.chunkX1 = Math.min(firstX, secondX);
            region.chunkX2 = Math.max(firstX, secondX);
            region.chunkZ1 = Math.min(firstZ, secondZ);
            region.chunkZ2 = Math.max(firstZ, secondZ);
            data.regions.add(region);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putBoolean(KEY_LEGACY_PURGED, this.legacyPurged);
        ListTag list = new ListTag();
        for (Region region : this.regions) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID(KEY_OWNER, region.owner);
            entry.putString(KEY_DIMENSION, region.dimension.location().toString());
            entry.putLong(KEY_MIN, pack(region.chunkX1, region.chunkZ1));
            entry.putLong(KEY_MAX, pack(region.chunkX2, region.chunkZ2));
            list.add(entry);
        }
        tag.put(KEY_REGIONS, list);
        return tag;
    }

    /** 把两个 int 区块坐标塞进一个 long，省得开两个 NBT 键。 */
    private static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    /** 名册里的一条区域记录，坐标都是区块坐标，且已经保证 x1 <= x2、z1 <= z2。 */
    public static final class Region {
        public UUID owner;
        public ResourceKey<Level> dimension;
        public int chunkX1;
        public int chunkZ1;
        public int chunkX2;
        public int chunkZ2;
    }
}
