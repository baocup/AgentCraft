package baocup.builder;

import java.util.function.Consumer;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import baocup.builder.agent.AgentManager;
import baocup.builder.net.ModPackets;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@Mod(BuilderMod.MODID)
public class BuilderMod {
    public static final String MODID = "agentcraft";
    public static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 由客户端专属类注入。这样公共注册代码无需引用任何客户端类，专用服务器也不会加载它们。
     */
    public static volatile Consumer<ModPackets.S2CState> clientStateHandler = null;
    /** 由客户端专属类注入：转发服务端查询结果，避免公共代码引用客户端类。 */
    public static volatile Consumer<ModPackets.S2CQueryResult> clientQueryHandler = null;

    public BuilderMod(IEventBus modEventBus, ModContainer container) {
        ModItems.ITEMS.register(modEventBus);

        modEventBus.addListener(this::registerPayloads);
        modEventBus.addListener(this::addCreative);
        container.registerConfig(ModConfig.Type.COMMON, BuilderConfig.SPEC);
        NeoForge.EVENT_BUS.register(this);
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(ModPackets.C2SAction.TYPE, ModPackets.C2SAction.STREAM_CODEC,
                (payload, context) -> AgentManager.get().handleAction(context.player(), payload));
        registrar.playToServer(ModPackets.C2SCommit.TYPE, ModPackets.C2SCommit.STREAM_CODEC,
                (payload, context) -> AgentManager.get()
                        .applyClientCommit(context.player(), payload));
        registrar.playToClient(ModPackets.S2CState.TYPE, ModPackets.S2CState.STREAM_CODEC,
                (payload, context) -> {
                    Consumer<ModPackets.S2CState> handler = clientStateHandler;
                    if (handler != null) {
                        handler.accept(payload);
                    }
                });
        registrar.playToServer(ModPackets.C2SQuery.TYPE, ModPackets.C2SQuery.STREAM_CODEC,
                (payload, context) -> AgentManager.get()
                        .applyClientQuery(context.player(), payload));
        registrar.playToClient(ModPackets.S2CQueryResult.TYPE,
                ModPackets.S2CQueryResult.STREAM_CODEC,
                (payload, context) -> {
                    Consumer<ModPackets.S2CQueryResult> handler = clientQueryHandler;
                    if (handler != null) {
                        handler.accept(payload);
                    }
                });
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(ModItems.EXTERNAL_BUILD_WAND);
        }
    }

    /** /agentcraft status：外部 Agent 只通过玩家客户端 MCP 建造。 */
    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("agentcraft")
                .then(Commands.literal("status").executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal(
                            "§b[外部建造] §f请在客户端连接 MCP：http://127.0.0.1:"
                                    + BuilderConfig.MCP_PORT.getAsInt() + "/mcp"), false);
                    return 1;
                })));
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        AgentManager.init(event.getServer());
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        AgentManager.shutdown();
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        AgentManager.get().tick(event.getServer());
    }
}
