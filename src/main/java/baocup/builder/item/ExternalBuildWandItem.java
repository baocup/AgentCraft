package baocup.builder.item;

import baocup.builder.agent.AgentManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.List;

/** 外部 Agent 建造手杖：选区、区域/禁建区管理与客户端 MCP 的唯一入口。 */
public class ExternalBuildWandItem extends Item {
    public ExternalBuildWandItem(Properties properties) {
        super(properties);
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, net.minecraft.world.entity.Entity entity,
                              int slot, boolean selected) {
        if (!level.isClientSide) {
            ItemData.ensureTaskId(stack);
        }
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        ItemStack stack = context.getItemInHand();
        if (level.isClientSide || player == null) {
            return InteractionResult.SUCCESS;
        }
        ItemData.ensureTaskId(stack);
        if (player.isCrouching()) {
            openGui(player, stack);
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            return InteractionResultHolder.success(stack);
        }
        ItemData.ensureTaskId(stack);
        openGui(player, stack);
        return InteractionResultHolder.consume(stack);
    }

    private void openGui(Player player, ItemStack stack) {
        if (player instanceof ServerPlayer serverPlayer) {
            AgentManager.get().openGui(serverPlayer, ItemData.taskId(stack), "external");
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal("§7外部 Agent 专用 · 客户端 MCP"));
        tooltip.add(Component.literal("§7右键打开控制台；潜行右键方块也可打开"));
        tooltip.add(Component.literal("§7默认不选点；控制台点按钮武装后左键两点，完成自动退出"));
        tooltip.add(Component.literal("§7手持显示蓝/黄/红线框与半透明面"));
        String taskId = ItemData.taskId(stack);
        if (taskId == null || taskId.isEmpty()) {
            tooltip.add(Component.literal("§8任务 未分配（取出后自动生成）"));
        } else {
            tooltip.add(Component.literal("§8任务 " + (taskId.length() >= 8 ? taskId.substring(0, 8) : taskId)));
        }
    }
}
