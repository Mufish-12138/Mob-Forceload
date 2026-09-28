package cn.blockforge.generated.mod541a1b1d;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.world.ForgeChunkManager;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * Mob Forceload 主类。
 *
 * <p>用法：用 {@code /mfsummon <生物> [坐标] [NBT]} 生成生物，它会被本模组打上标记。
 * 写法与原版 {@code /summon} 一致，后面的 NBT 修饰（Tags、Attributes、PersistenceRequired 等）
 * 会原样套用到生成的生物身上。
 * 被标记的生物会永久维持自己周围一定范围（默认 4 个区块）的区块强加载，
 * 并且（可配置、默认开启）让它即使在远离所有玩家之后也继续正常活动，
 * 不会被冻结、也不会因为走远而消失。
 */
@Mod(MobForceload.MOD_ID)
public final class MobForceload {

    /** 模组 ID，同时也是配置文件名和资源命名空间。 */
    public static final String MOD_ID = "mob_forceload";

    public static final Logger LOGGER = LogUtils.getLogger();

    /** 打在生物自身 NBT 里的标记键，跟着生物一起存进存档。 */
    public static final String NBT_MARKED = "MobForceloadMarked";

    /** 附加在生物身上的标签，方便用选择器 {@code @e[tag=mf_marked]} 找到被标记的生物。 */
    public static final String TAG_MARKED = "mf_marked";

    public MobForceload(FMLJavaModLoadingContext context) {
        context.registerConfig(ModConfig.Type.COMMON, MobForceloadConfig.SPEC);

        // 交给 Forge 在存档加载时恢复本模组保存的强加载记录。
        // 这里留一个空回调表示“存档里记着的都保留”，具体的增删由 ForceloadManager 每刻校准。
        ForgeChunkManager.setForcedChunkLoadingCallback(MOD_ID, (level, helper) -> {
        });

        LOGGER.info("Mob Forceload 已加载：/mfsummon 生成的生物会被标记并维持区块强加载。");
    }
}
