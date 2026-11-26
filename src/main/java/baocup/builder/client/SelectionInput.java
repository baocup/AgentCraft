package baocup.builder.client;

import baocup.builder.item.ExternalBuildWandItem;
import baocup.builder.item.ItemData;
import baocup.builder.net.ModPackets;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 建造指令物品的左键选点。
 *
 * Minecraft 的「左键点空」事件只在客户端触发（LeftClickEmpty 明确说明服务端不知情），
 * 所以统一在客户端用攻击键触发事件做边沿检测，再发一个自定义包给服务端，
 * 包里带上准星瞄准的方块坐标（pos），服务端据此记录选点；点空时回退玩家站位。
 *
 * 用 ClientTickEvent.Post 记录按键是否按住，过滤掉「按住左键持续挖掘」时每 tick 的重复事件，
 * 保证一次按下只算一次选点。
 */
public final class SelectionInput {
    private static boolean attackHeld;

    public static final class PendingSelection {
        public final net.minecraft.core.BlockPos p1;
        public final ItemData.SelectionMode selectionMode;

        private PendingSelection(net.minecraft.core.BlockPos p1, ItemData.SelectionMode selectionMode) {
            this.p1 = p1;
            this.selectionMode = selectionMode;
        }
    }

    public static PendingSelection pending(String taskId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || taskId == null) {
            return null;
        }
        for (net.minecraft.world.InteractionHand hand : net.minecraft.world.InteractionHand.values()) {
            ItemStack stack = mc.player.getItemInHand(hand);
            if (!(stack.getItem() instanceof ExternalBuildWandItem)) {
                continue;
            }
            ItemData.Data data = ItemData.get(stack);
            if (taskId.equals(data.taskId) && data.isArmed() && data.selP1 != null) {
                return new PendingSelection(data.selP1, data.selectionMode);
            }
        }
        return null;
    }

    @SubscribeEvent
    public void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        attackHeld = mc.options.keyAttack.isDown();
    }

    @SubscribeEvent
    public void onAttack(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isAttack()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.player == null || mc.getConnection() == null) {
            return;
        }
        Player player = mc.player;
        ItemStack main = player.getItemInHand(event.getHand());
        ItemStack off = player.getItemInHand(net.minecraft.world.InteractionHand.OFF_HAND);
        ItemStack held = main.getItem() instanceof ExternalBuildWandItem ? main
                : (off.getItem() instanceof ExternalBuildWandItem ? off : null);
        if (held == null) {
            return;
        }
        // 无论是否重复都取消原版攻击/挖掘，只保留挥手反馈，避免选点时敲掉方块
        event.setCanceled(true);
        event.setSwingHand(true);
        // 同一次按下里 startAttack 与 continueAttack 会各发一次事件；attackHeld 做边沿检测，
        // 只让第一次真正选点，后续（含按住不放的每 tick 重复）仅取消不发包。
        if (attackHeld) {
            return;
        }
        attackHeld = true;
        // 默认 NONE：手持手杖左键只挥手，不落任何选点；必须先在 UI 点按钮武装。
        if (!ItemData.get(held).isArmed()) {
            return;
        }
        // UUID 由服务端权威分配（客户端不生成），避免创造模式拿到的物品出现两个不同的 taskId
        String taskId = ItemData.taskId(held);
        // 选点必须用准星瞄准的方块，而不是玩家站位；否则"在根区内选子区"会记录成玩家坐标而校验失败。
        com.google.gson.JsonObject data = new com.google.gson.JsonObject();
        net.minecraft.world.phys.HitResult hit = mc.hitResult;
        if (hit instanceof net.minecraft.world.phys.BlockHitResult blockHit
                && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
            net.minecraft.core.BlockPos pos = blockHit.getBlockPos();
            com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
            arr.add(pos.getX());
            arr.add(pos.getY());
            arr.add(pos.getZ());
            data.add("pos", arr);
        }
        PacketDistributor.sendToServer(new ModPackets.C2SAction(
                taskId == null ? "" : taskId, "select_point", data.toString()));
    }
}
