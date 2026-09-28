package cn.blockforge.generated.mod541a1b1d;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityTeleportEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Forge 事件总线上的入口，只负责把事件转发给管理器。
 */
@Mod.EventBusSubscriber(modid = MobForceload.MOD_ID)
public final class ForgeEventHandler {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        MfCommands.register(event.getDispatcher(), event.getBuildContext());
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        ForceloadManager.reset();
        ForceloadManager.reconcileNow(event.getServer());
        // 手动圈定的区域强加载也照着存档恢复一遍，免得重启后失效。
        ForceloadRegionManager.restoreAll(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        ForceloadManager.reset();
        ForceloadTickets.clear();
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        ForceloadManager.serverTick(event.getServer());
        // 每个游戏刻挂一小批强加载区块。一次挂太多会让服务器当场卡死，
        // 所以区域强加载和缓存预设都走这条通道慢慢挂。
        ForceloadTickets.tick(event.getServer(), ForceloadManager.ticketsPerTick());
    }

    /**
     * 生物带着标记进入世界时（读档、换维度、区块重新加载）登记到名册。
     *
     * <p>这里不立刻建立强加载窗口：实体可能正在被区块加载流程读出来，
     * 此时再去加载别的区块容易层层套娃，交给下一次定时校准补上即可。
     */
    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        Entity entity = event.getEntity();
        if (!ForceloadManager.isMarked(entity)) {
            return;
        }
        MinecraftServer server = entity.getServer();
        if (server != null) {
            ForceloadManager.track(entity, server, false);
        }
    }

    /**
     * 被标记的生物要被原版 {@code /spreadplayers} 甩到别处时，抢先把它落点所在区块强加载起来。
     *
     * <p>Forge 这个事件是在生物真正传送之前触发的，所以我们在这里办事最及时：
     * 目标区块先被加载并挂上强加载票据，生物随后传送过去就落在“有人管”的区块里，
     * 不会因为落点是未加载区块而丢失。
     */
    @SubscribeEvent
    public static void onSpreadPlayers(EntityTeleportEvent.SpreadPlayersCommand event) {
        Entity entity = event.getEntity();
        if (entity.level().isClientSide()) {
            return;
        }
        if (!ForceloadManager.isMarked(entity)) {
            return;
        }
        ForceloadManager.retarget(entity, event.getTargetX(), event.getTargetY(), event.getTargetZ());
    }

    /**
     * 关闭“维持模拟距离”时，把被标记的生物原地冻住。
     *
     * <p>区块本身依然是强加载的，只是这只生物不再寻路、不再攻击、也不会掉进虚空，
     * 相当于把它按了暂停键。
     */
    @SubscribeEvent
    public static void onLivingTick(LivingEvent.LivingTickEvent event) {
        if (ForceloadManager.simulate()) {
            return;
        }
        if (event.getEntity().level().isClientSide()) {
            return;
        }
        if (ForceloadManager.isMarked(event.getEntity())) {
            event.setCanceled(true);
        }
    }
}
