package cn.blockforge.generated.mod541a1b1d;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.Collection;
import java.util.List;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.CompoundTagArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.commands.SummonCommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

/**
 * 自定义指令。
 *
 * <ul>
 *   <li>{@code /mfsummon <生物> [坐标] [NBT]} —— 生成一只生物并标记它（写法与原版 /summon 一致）</li>
 *   <li>{@code /mfmark <目标>} —— 给已经存在的生物补上标记</li>
 *   <li>{@code /mfunmark <目标>} 或 {@code /mfunmark all} —— 取消标记</li>
 *   <li>{@code /mflist} —— 列出所有被标记的生物</li>
 *   <li>{@code /mfforceload add <x1> <z1> <x2> <z2>} —— 把一块区域手动加入强加载（四个数都是方块坐标，会自动换算成区块）</li>
 *   <li>{@code /mfforceload remove all} —— 移除所有手动添加的区域强加载</li>
 *   <li>{@code /mfforceload list} —— 列出所有手动添加的区域强加载</li>
 *   <li>{@code /mfinfo} —— 查看当前配置生效情况</li>
 * </ul>
 */
public final class MfCommands {

    private MfCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext context) {
        // 指令树故意做成和原版 /summon 一样：坐标和 NBT 都可省略，也允许只写 NBT 不写坐标。
        // NBT 参数用的是原版 CompoundTagArgument，解析的就是标准 NBT（SNBT）语法，
        // 所以 {Tags:[...],PersistentRequired:1b,Attributes:[...]} 这类后续修饰都能原样带进来。
        dispatcher.register(Commands.literal("mfsummon")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("entity", ResourceArgument.resource(context, Registries.ENTITY_TYPE))
                        .executes(command -> summon(command, false, false))
                        .then(Commands.argument("pos", Vec3Argument.vec3())
                                .executes(command -> summon(command, true, false))
                                .then(Commands.argument("nbt", CompoundTagArgument.compoundTag())
                                        .executes(command -> summon(command, true, true))))
                        .then(Commands.argument("nbt", CompoundTagArgument.compoundTag())
                                .executes(command -> summon(command, false, true)))));

        dispatcher.register(Commands.literal("mfmark")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("targets", EntityArgument.entities())
                        .executes(MfCommands::markTargets)));

        dispatcher.register(Commands.literal("mfunmark")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("all").executes(MfCommands::unmarkAll))
                .then(Commands.argument("targets", EntityArgument.entities())
                        .executes(MfCommands::unmarkTargets)));

        dispatcher.register(Commands.literal("mflist")
                .requires(source -> source.hasPermission(2))
                .executes(MfCommands::listMarked));

        // 区域强加载：四个参数都是方块坐标。模组会自动把它们换算成覆盖这片方块的区块范围，
        // 再按“中心点阵”铺满整片区域，所以玩家不用自己算区块，只报矩形的两个角就行。
        dispatcher.register(Commands.literal("mfforceload")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("add")
                        .then(Commands.argument("x1", IntegerArgumentType.integer())
                                .then(Commands.argument("z1", IntegerArgumentType.integer())
                                        .then(Commands.argument("x2", IntegerArgumentType.integer())
                                                .then(Commands.argument("z2", IntegerArgumentType.integer())
                                                        .executes(MfCommands::forceloadAdd))))))
                .then(Commands.literal("remove")
                        .then(Commands.literal("all").executes(MfCommands::forceloadRemoveAll)))
                .then(Commands.literal("list").executes(MfCommands::forceloadList)));

        dispatcher.register(Commands.literal("mfinfo")
                .requires(source -> source.hasPermission(2))
                .executes(MfCommands::info));
    }

    private static int summon(CommandContext<CommandSourceStack> command, boolean hasPosition, boolean hasNbt) throws CommandSyntaxException {
        CommandSourceStack source = command.getSource();
        Holder.Reference<EntityType<?>> holder = ResourceArgument.getSummonableEntityType(command, "entity");
        ServerLevel level = source.getLevel();

        // 把玩家写的 NBT 原样交给原版：CompoundTagArgument 解析出来的就是标准 NBT 结构，
        // 不需要本模组逐项认识 Tags、Attributes、PersistenceRequired 这些键。
        CompoundTag tag = hasNbt ? CompoundTagArgument.getCompoundTag(command, "nbt") : new CompoundTag();
        // 原版 /summon 的规矩：NBT 里没写 id 就按指令里的生物补上；补过 id 才算“随机初始化”。
        boolean randomize = !tag.contains("id", 8);
        if (randomize) {
            tag.putString("id", holder.key().location().toString());
        }

        Vec3 pos = hasPosition ? Vec3Argument.getVec3(command, "pos") : source.getPosition();

        // 复用原版 SummonCommand.createEntity：它会按 NBT 造出生物、套用 NBT、需要时跑一遍
        // finalizeSpawn，完全与原版 /summon 一致，也兼容后续新增的 NBT 结构。
        // 注意它只造生物、不加进世界，所以我们可以先打标记再入世界。
        Entity entity;
        try {
            entity = SummonCommand.createEntity(source, holder, pos, tag, randomize);
        } catch (CommandSyntaxException exception) {
            source.sendFailure(Component.translatable("mob_forceload.msg.create_failed",
                    Component.literal(holder.key().location().toString())));
            return 0;
        }

        String id = ForceloadManager.typeId(entity);
        if (!isWhitelisted(id)) {
            source.sendFailure(Component.translatable("mob_forceload.msg.not_whitelisted", Component.literal(id)));
            return 0;
        }

        MinecraftServer server = source.getServer();
        // 先打标记再加进世界，这样 EntityJoinLevelEvent 一进来就能认出来。
        markRecursively(entity, server);
        if (!level.tryAddFreshEntityWithPassengers(entity)) {
            source.sendFailure(Component.translatable("mob_forceload.msg.create_failed", Component.literal(id)));
            return 0;
        }

        int radius = ForceloadManager.radius();
        source.sendSuccess(() -> Component.translatable("mob_forceload.msg.summoned",
                entity.getDisplayName(), radius), true);
        return 1;
    }

    /** 连同 NBT 里带的乘客（例如骑乘组合）一起标记，整队都跟着强加载。 */
    private static void markRecursively(Entity entity, MinecraftServer server) {
        ForceloadManager.mark(entity, server);
        for (Entity passenger : entity.getPassengers()) {
            markRecursively(passenger, server);
        }
    }

    private static int markTargets(CommandContext<CommandSourceStack> command) throws CommandSyntaxException {
        CommandSourceStack source = command.getSource();
        Collection<? extends Entity> targets = EntityArgument.getEntities(command, "targets");
        MinecraftServer server = source.getServer();
        int count = 0;
        for (Entity entity : targets) {
            ForceloadManager.mark(entity, server);
            count++;
        }
        if (count == 0) {
            source.sendFailure(Component.translatable("mob_forceload.msg.none"));
            return 0;
        }
        int total = count;
        source.sendSuccess(() -> Component.translatable("mob_forceload.msg.marked", total), true);
        return total;
    }

    private static int unmarkTargets(CommandContext<CommandSourceStack> command) throws CommandSyntaxException {
        CommandSourceStack source = command.getSource();
        Collection<? extends Entity> targets = EntityArgument.getEntities(command, "targets");
        MinecraftServer server = source.getServer();
        int count = 0;
        for (Entity entity : targets) {
            if (ForceloadManager.unmark(entity, server)) {
                count++;
            }
        }
        if (count == 0) {
            source.sendFailure(Component.translatable("mob_forceload.msg.none"));
            return 0;
        }
        int total = count;
        source.sendSuccess(() -> Component.translatable("mob_forceload.msg.unmarked", total), true);
        return total;
    }

    private static int unmarkAll(CommandContext<CommandSourceStack> command) {
        CommandSourceStack source = command.getSource();
        int count = ForceloadManager.unmarkEverything(source.getServer());
        if (count == 0) {
            source.sendFailure(Component.translatable("mob_forceload.msg.list.empty"));
            return 0;
        }
        int total = count;
        source.sendSuccess(() -> Component.translatable("mob_forceload.msg.unmarked", total), true);
        return total;
    }

    private static int listMarked(CommandContext<CommandSourceStack> command) {
        CommandSourceStack source = command.getSource();
        MarkedMobData data = MarkedMobData.get(source.getServer());
        if (data.mobs().isEmpty()) {
            source.sendSuccess(() -> Component.translatable("mob_forceload.msg.list.empty"), false);
            return 0;
        }
        int total = data.mobs().size();
        source.sendSuccess(() -> Component.translatable("mob_forceload.msg.list.header", total), false);
        for (MarkedMobData.TrackedMob recorded : data.mobs().values()) {
            source.sendSuccess(() -> Component.translatable("mob_forceload.msg.list.entry",
                    Component.literal(recorded.typeId),
                    Component.literal(recorded.dimension.location().toString()),
                    Component.literal(recorded.pos.toShortString())), false);
        }
        return total;
    }

    // ------------------------------------------------------------ 区域强加载

    /**
     * {@code /mfforceload add <x1> <z1> <x2> <z2>}：把矩形区域加入强加载。
     *
     * <p>四个数字都是<b>方块坐标</b>（和游戏里 F3 看到的 X/Z 一致，不是区块坐标），
     * 分别是大矩形两个角的 X/Z，顺序随便填。模组会先用 floorDiv 把它们换算成覆盖这片
     * 方块的区块范围（例如方块 -50~50 对应区块 -4~3），再交给
     * {@link ForceloadRegionManager} 铺中心区块，所以玩家不需要自己换算区块。
     */
    private static int forceloadAdd(CommandContext<CommandSourceStack> command) {
        CommandSourceStack source = command.getSource();
        // 以下四个都是玩家填的方块坐标。
        int x1 = IntegerArgumentType.getInteger(command, "x1");
        int z1 = IntegerArgumentType.getInteger(command, "z1");
        int x2 = IntegerArgumentType.getInteger(command, "x2");
        int z2 = IntegerArgumentType.getInteger(command, "z2");

        // 超限判断也按方块坐标准确换算成区块后再算，避免用错尺度放行过大的区域。
        long needed = ForceloadRegionManager.centerCountForBlocks(x1, z1, x2, z2);
        if (needed > ForceloadRegionManager.MAX_CENTERS) {
            source.sendFailure(Component.translatable("mob_forceload.msg.forceload.too_large",
                    ForceloadRegionManager.MAX_CENTERS, needed));
            return 0;
        }

        ForceloadRegionManager.AddResult result =
                ForceloadRegionManager.addRegionBlocks(source.getLevel(), x1, z1, x2, z2);
        if (result.duplicate) {
            source.sendFailure(Component.translatable("mob_forceload.msg.forceload.duplicate",
                    result.blockX1, result.blockX2, result.blockZ1, result.blockZ2,
                    result.minX, result.maxX, result.minZ, result.maxZ));
            return 0;
        }
        // 配置里的总区块数保护：这次要覆盖的区块加上已经强加载的，超过上限就整体拒绝，不做任何改动。
        if (result.overLimit) {
            source.sendFailure(Component.translatable("mob_forceload.msg.forceload.limit",
                    result.chunks, result.existingChunks, result.existingChunks + result.chunks,
                    result.chunkLimit));
            return 0;
        }
        int rate = Math.max(1, ForceloadManager.ticketsPerTick());
        int seconds = estimateSeconds(result.centers);
        source.sendSuccess(() -> Component.translatable("mob_forceload.msg.forceload.added",
                result.blockX1, result.blockX2, result.blockZ1, result.blockZ2,
                result.minX, result.maxX, result.minZ, result.maxZ,
                result.centers, result.chunks, rate, seconds), true);
        warnIfHuge(source, result.chunks);
        return 1;
    }

    /**
     * 范围特别大时多提醒一句。
     *
     * <p>分批挂票只解决“挂上去那一刻不卡”，挂好之后这些区块每刻都要运算，
     * 长期还是很吃 CPU，所以圈得太大时得说清楚。
     */
    private static void warnIfHuge(CommandSourceStack source, int chunks) {
        if (chunks > ForceloadRegionManager.WARN_CHUNKS) {
            source.sendSuccess(() -> Component.translatable("mob_forceload.msg.forceload.huge", chunks), false);
        }
    }

    /**
     * 这批中心票据全部登记完大概要多少秒。
     *
     * <p>登记票据本身不生成地形，所以这里只是“把票排完”的时间，实际地形由游戏后台慢慢补，
     * 不需要（也不会）在这一刻一次性生成。
     */
    private static int estimateSeconds(int centers) {
        int rate = Math.max(1, ForceloadManager.ticketsPerTick());
        return (int) Math.ceil(centers / (rate * 20.0));
    }

    /** {@code /mfforceload remove all}：移除全部手动添加的区域强加载，不影响被标记的生物。 */
    private static int forceloadRemoveAll(CommandContext<CommandSourceStack> command) {
        CommandSourceStack source = command.getSource();
        int removed = ForceloadRegionManager.removeAll(source.getServer());
        if (removed == 0) {
            source.sendFailure(Component.translatable("mob_forceload.msg.forceload.none"));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("mob_forceload.msg.forceload.removed", removed), true);
        return removed;
    }

    /** {@code /mfforceload list}：看看现在都圈了哪些区域。 */
    private static int forceloadList(CommandContext<CommandSourceStack> command) {
        CommandSourceStack source = command.getSource();
        List<ForceloadRegionData.Region> regions = ForceloadRegionManager.regions(source.getServer());
        if (regions.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("mob_forceload.msg.forceload.none"), false);
            return 0;
        }
        int total = regions.size();
        source.sendSuccess(() -> Component.translatable("mob_forceload.msg.forceload.list.header", total), false);
        for (ForceloadRegionData.Region region : regions) {
            // 名册里存的是区块坐标，顺手把它们代表的方块范围也列出来，跟 add 时填的方块坐标对得上。
            int blockX1 = region.chunkX1 * ForceloadRegionManager.CHUNK_SIZE;
            int blockZ1 = region.chunkZ1 * ForceloadRegionManager.CHUNK_SIZE;
            int blockX2 = region.chunkX2 * ForceloadRegionManager.CHUNK_SIZE + ForceloadRegionManager.CHUNK_SIZE - 1;
            int blockZ2 = region.chunkZ2 * ForceloadRegionManager.CHUNK_SIZE + ForceloadRegionManager.CHUNK_SIZE - 1;
            source.sendSuccess(() -> Component.translatable("mob_forceload.msg.forceload.list.entry",
                    Component.literal(region.dimension.location().toString()),
                    region.chunkX1, region.chunkX2, region.chunkZ1, region.chunkZ2,
                    blockX1, blockX2, blockZ1, blockZ2), false);
        }
        return total;
    }

    private static int info(CommandContext<CommandSourceStack> command) {
        CommandSourceStack source = command.getSource();
        int radius = ForceloadManager.radius();
        Component simulation = Component.translatable(
                ForceloadManager.simulate() ? "mob_forceload.msg.on" : "mob_forceload.msg.off");
        int marked = MarkedMobData.get(source.getServer()).mobs().size();
        source.sendSuccess(() -> Component.translatable("mob_forceload.msg.info", radius, simulation, marked), false);
        return 1;
    }

    private static boolean isWhitelisted(String id) {
        List<? extends String> whitelist;
        try {
            whitelist = MobForceloadConfig.MOB_WHITELIST.get();
        } catch (RuntimeException exception) {
            // 配置没读出来时不要卡住玩家，按“不限制”处理。
            return true;
        }
        if (whitelist == null || whitelist.isEmpty()) {
            return true;
        }
        for (String entry : whitelist) {
            if (entry != null && entry.equalsIgnoreCase(id)) {
                return true;
            }
        }
        return false;
    }
}
