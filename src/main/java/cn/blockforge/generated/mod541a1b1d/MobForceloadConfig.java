package cn.blockforge.generated.mod541a1b1d;

import java.util.List;
import net.minecraftforge.common.ForgeConfigSpec;

/**
 * 配置定义。
 *
 * <p>配置文件生成在 {@code config/mob_forceload-common.toml}，注释为中英双语。
 * The config file is created at {@code config/mob_forceload-common.toml}; comments are bilingual.
 */
public final class MobForceloadConfig {

    public static final ForgeConfigSpec SPEC;

    /** 允许被 /mfsummon 标记的生物 ID 白名单。默认留空，表示不限制生物类型。 */
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> MOB_WHITELIST;

    /** 强加载半径，单位是区块。 */
    public static final ForgeConfigSpec.IntValue CHUNK_RADIUS;

    /** 是否维持模拟距离（让被标记生物远离玩家后依然正常活动）。 */
    public static final ForgeConfigSpec.BooleanValue KEEP_SIMULATION_DISTANCE;

    /** 重新计算强加载范围的间隔，单位是游戏刻。 */
    public static final ForgeConfigSpec.IntValue UPDATE_INTERVAL_TICKS;

    /** 是否让被标记生物发光，便于肉眼确认。 */
    public static final ForgeConfigSpec.BooleanValue GLOWING;

    /**
     * 区域强加载分批登记票据时，每个游戏刻最多登记多少张中心票据。
     */
    public static final ForgeConfigSpec.IntValue FORCELOAD_TICKETS_PER_TICK;

    /**
     * 区域强加载合计最多允许多少个区块。
     *
     * <p>/mfforceload add 建立新区域时，会拿“本次要覆盖的区块数 + 当前已经强加载的区块数”
     * 跟这个值比：超过就拒绝整次操作、不做任何改动。默认 1000。
     */
    public static final ForgeConfigSpec.IntValue MAX_FORCELOAD_CHUNKS;

    /** 默认留空：不写名单就是不限制，任何生物都能被 /mfsummon 标记。 */
    private static final List<String> DEFAULT_WHITELIST = List.of();

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.comment(
                "Mob Forceload configuration.",
                "Changes apply after you re-enter the world (or restart the server).",
                "Mob Forceload 配置。",
                "改完保存文件后，重进一次世界（或重启服务器）就会生效。")
                .push("general");

        MOB_WHITELIST = builder
                .comment(
                        "Whitelist: mob IDs that /mfsummon is allowed to mark. Empty by default ([]), meaning no restriction.",
                        "To allow only certain mobs, put their vanilla IDs here, e.g. minecraft:zombie, minecraft:villager.",
                        "Once a list is set, mobs outside it are rejected by /mfsummon.",
                        "白名单：允许被 /mfsummon 标记的生物 ID。默认留空（[]），表示不限制、什么生物都能标记。",
                        "想只允许特定生物时，把它们的原版 ID 写进来，例如 minecraft:zombie、minecraft:villager。",
                        "写了名单之后，名单之外的生物用 /mfsummon 会被拒绝。")
                .defineListAllowEmpty("mobWhitelist", DEFAULT_WHITELIST, MobForceloadConfig::isValidEntry);

        CHUNK_RADIUS = builder
                .comment(
                        "Force-load radius for a marked mob, in chunks. Default 4.",
                        "4 means 4 chunks in each direction around the mob's chunk, a 9x9 = 81 chunk square.",
                        "0 means only the single chunk the mob stands in.",
                        "Larger values cost the server more; 1-6 is recommended.",
                        "被标记生物的强加载半径，单位是区块。默认 4。",
                        "4 表示以生物所在区块为中心，向四周各扩展 4 个区块，一共 9x9 = 81 个区块。",
                        "0 表示只加载生物自己所在的那 1 个区块。",
                        "数值越大服务器负担越重，建议 1 ~ 6。")
                .defineInRange("chunkRadius", 4, 0, 16);

        KEEP_SIMULATION_DISTANCE = builder
                .comment(
                        "Keep simulation distance running for marked mobs. Default true.",
                        "true: a marked mob keeps pathfinding, attacking and growing even when it leaves every player's simulation distance.",
                        "false: the chunks stay force-loaded, but the marked mob stops moving where it is.",
                        "Note: force-loading itself is always on and is not affected by this option.",
                        "是否维持模拟距离，默认开启。",
                        "开启：被标记的生物即使跑出所有玩家的模拟距离，也照样正常寻路、攻击、生长，不会被冻结。",
                        "关闭：区块依然是强加载的，但被标记的生物会停在原地不再活动。",
                        "注意：强加载本身永远开启，不受这一项影响。")
                .define("keepSimulationDistance", true);

        UPDATE_INTERVAL_TICKS = builder
                .comment(
                        "How often the force-load window is recalculated, in game ticks (20 ticks = 1 second). Default 20.",
                        "Smaller reacts faster but costs more; larger is cheaper.",
                        "多久重新计算一次强加载范围，单位是游戏刻（20 刻 = 1 秒）。默认 20。",
                        "调小反应更灵敏但更费性能，调大更省性能。")
                .defineInRange("updateIntervalTicks", 20, 1, 200);

        GLOWING = builder
                .comment(
                        "Whether marked mobs glow, so you can see at a glance who is marked. Default false.",
                        "Visual only; it does not affect the force-loading itself.",
                        "是否让被标记的生物发光，方便一眼看出谁被标记了。默认关闭。",
                        "只是视觉效果，不影响功能。")
                .define("glowing", false);

        FORCELOAD_TICKETS_PER_TICK = builder
                .comment(
                        "How many centre tickets the region force-loader may register per game tick. Default 32.",
                        "A region only registers tickets for its centre chunks; one ticket already radiates over 5x5 chunks,",
                        "and the rest of the area is loaded by the game in the background. Registering a ticket does not generate terrain.",
                        "Batching still matters: registering tens of thousands of tickets at once would make the game process",
                        "tens of thousands of chunk-level updates in a single tick. Smaller is smoother but the whole region takes longer;",
                        "larger is faster but heavier per tick. 1-4096. A typical machine is fine with 8-32; a strong server can try 64-256.",
                        "区域强加载分批登记票据的速度：每个游戏刻最多登记多少张中心票据。默认 32。",
                        "一片区域只会给“中心区块”登记票据，一张票自带 5x5 个区块的辐射，",
                        "辐射圈里的其他区块由游戏后台分批加载，登记票据这一步本身不生成地形。",
                        "之所以还要分批：一次登记几万张票会让游戏在同一刻处理几万条区块等级更新，",
                        "分成每刻一小批更平滑。调小更平滑但全部登记更慢，调大更快但每刻更吃力。1 ~ 4096。",
                        "机器一般建议 8 ~ 32；配置好的服务器可以试 64 ~ 256。")
                .defineInRange("forceloadTicketsPerTick", 32, 1, 4096);

        MAX_FORCELOAD_CHUNKS = builder
                .comment(
                        "Safety cap on the total number of force-loaded chunks used by /mfforceload add. Default 1000.",
                        "When a new region is added, the chunks it would cover plus the chunks already force-loaded must not",
                        "exceed this number; otherwise the command is rejected and nothing is changed.",
                        "This stops a single typo from force-loading an enormous area and driving the server's TPS to zero.",
                        "Raise it only if you really need a larger permanent force-loaded area and your server can afford the CPU.",
                        "/mfforceload add 能强加载的区块总数上限，默认 1000。",
                        "添加新区域时，“本次要覆盖的区块数 + 当前已经强加载的区块数”不能超过这个值，",
                        "否则整条指令会被拒绝，且不会产生任何改动。",
                        "这是为了防止手滑输入一个巨大的区域，一次性挂上太多区块把服务器 TPS 打到 0。",
                        "只有确实需要长期强加载更大范围、并且服务器扛得住时，才建议调大。")
                .defineInRange("maxForceLoadedChunks", 1000, 1, Integer.MAX_VALUE);

        builder.pop();
        SPEC = builder.build();
    }

    private MobForceloadConfig() {
    }

    private static boolean isValidEntry(Object value) {
        return value instanceof String;
    }
}
