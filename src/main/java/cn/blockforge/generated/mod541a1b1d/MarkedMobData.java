package cn.blockforge.generated.mod541a1b1d;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
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
 * 存档级别的“被标记生物名册”。
 *
 * <p>之所以不能只靠扫描世界里的实体，是因为生物可能远在未加载的区块里，
 * 扫描不到它就发不出强加载票据。这里记下每只被标记生物所在的维度、
 * 上次已知坐标和种类，重启服务器后也能照着这份名册把强加载恢复起来。
 */
public final class MarkedMobData extends SavedData {

    private static final String FILE_ID = "mob_forceload_marked";
    private static final String KEY_MOBS = "mobs";
    private static final String KEY_UUID = "uuid";
    private static final String KEY_DIMENSION = "dimension";
    private static final String KEY_POS = "pos";
    private static final String KEY_TYPE = "type";

    private final Map<UUID, TrackedMob> mobs = new LinkedHashMap<>();

    public Map<UUID, TrackedMob> mobs() {
        return this.mobs;
    }

    /** 从主世界的数据存储里取出（没有就新建）这份名册。 */
    public static MarkedMobData get(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(MarkedMobData::load, MarkedMobData::new, FILE_ID);
    }

    public static MarkedMobData load(CompoundTag tag) {
        MarkedMobData data = new MarkedMobData();
        ListTag list = tag.getList(KEY_MOBS, Tag.TAG_COMPOUND);
        for (int index = 0; index < list.size(); index++) {
            CompoundTag entry = list.getCompound(index);
            if (!entry.hasUUID(KEY_UUID)) {
                continue;
            }
            ResourceLocation dimensionId = ResourceLocation.tryParse(entry.getString(KEY_DIMENSION));
            if (dimensionId == null) {
                continue;
            }
            TrackedMob mob = new TrackedMob();
            mob.uuid = entry.getUUID(KEY_UUID);
            mob.dimension = ResourceKey.create(Registries.DIMENSION, dimensionId);
            mob.pos = BlockPos.of(entry.getLong(KEY_POS));
            mob.typeId = entry.getString(KEY_TYPE);
            data.mobs.put(mob.uuid, mob);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (TrackedMob mob : this.mobs.values()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID(KEY_UUID, mob.uuid);
            entry.putString(KEY_DIMENSION, mob.dimension.location().toString());
            entry.putLong(KEY_POS, mob.pos.asLong());
            entry.putString(KEY_TYPE, mob.typeId == null ? "" : mob.typeId);
            list.add(entry);
        }
        tag.put(KEY_MOBS, list);
        return tag;
    }

    /** 名册里的一条记录。 */
    public static final class TrackedMob {
        public UUID uuid;
        public ResourceKey<Level> dimension;
        public BlockPos pos = BlockPos.ZERO;
        public String typeId = "";
    }
}
