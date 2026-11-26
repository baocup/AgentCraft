package baocup.builder.item;

import java.util.UUID;
import java.util.function.Consumer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** 物品上的数据统一放在 CUSTOM_DATA 组件里，避免额外注册 DataComponentType。 */
public final class ItemData {
    public static final String TASK_ID = "builder_task_id";
    /** P1/P2/DIM 是「已确认的根建造区边界」，客户端 MCP 直接读它们，选点流程不得清除。 */
    public static final String P1 = "p1";
    public static final String P2 = "p2";
    public static final String DIM = "dim";
    public static final String LAST_TICK = "last_select_tick";
    public static final String SELECTION_MODE = "selection_mode";
    /** 待选点与已确认边界完全分离：武装后第一角点写 SEL_P1，结束（成功或失败）即清除，避免残留坐标污染下次选区。 */
    public static final String SEL_P1 = "sel_p1";
    public static final String SEL_DIM = "sel_dim";
    public static final String SEL_PARENT = "sel_parent";

    public enum SelectionMode {
        /** 默认：手持不选点，左键只挥手不落点；必须在 UI 点按钮武装后才能选区。 */
        NONE,
        ROOT,
        CHILD_REGION,
        FORBIDDEN_ZONE
    }

    /** 一次读取完整选点状态，供服务端选点、客户端预览和 UI 共用。 */
    public static final class Data {
        public final String taskId;
        /** 已确认根区边界角点（MCP 读取），与待选点无关。 */
        public final BlockPos p1;
        public final BlockPos p2;
        public final String dimension;
        public final SelectionMode selectionMode;
        /** 武装后的待选第一角点；为 null 表示尚未落第一点。 */
        public final BlockPos selP1;
        public final String selDim;
        public final String selParent;

        private Data(String taskId, BlockPos p1, BlockPos p2, String dimension, SelectionMode selectionMode,
                     BlockPos selP1, String selDim, String selParent) {
            this.taskId = taskId;
            this.p1 = p1;
            this.p2 = p2;
            this.dimension = dimension;
            this.selectionMode = selectionMode;
            this.selP1 = selP1;
            this.selDim = selDim;
            this.selParent = selParent;
        }

        public boolean hasSelection() {
            return p1 != null && p2 != null;
        }

        public boolean isArmed() {
            return selectionMode != SelectionMode.NONE;
        }
    }

    private ItemData() {
    }

    public static CompoundTag read(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    }

    public static void update(ItemStack stack, Consumer<CompoundTag> updater) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, updater);
    }

    public static String taskId(ItemStack stack) {
        String id = read(stack).getString(TASK_ID);
        return id == null || id.isEmpty() ? null : id;
    }

    /** 每个物品栈在第一次使用时获得一个全新的 UUID；合成时也会预分配，因此彼此不会串记忆。 */
    public static String ensureTaskId(ItemStack stack) {
        String existing = taskId(stack);
        if (existing != null) {
            return existing;
        }
        String fresh = UUID.randomUUID().toString();
        update(stack, tag -> tag.putString(TASK_ID, fresh));
        return fresh;
    }

    public static BlockPos point(CompoundTag tag, String key) {
        if (!tag.contains(key)) {
            return null;
        }
        int[] p = tag.getIntArray(key);
        return p.length == 3 ? new BlockPos(p[0], p[1], p[2]) : null;
    }

    public static void putPoint(CompoundTag tag, String key, BlockPos pos) {
        tag.putIntArray(key, new int[] { pos.getX(), pos.getY(), pos.getZ() });
    }

    public static Data get(ItemStack stack) {
        CompoundTag tag = read(stack);
        SelectionMode mode;
        try {
            mode = SelectionMode.valueOf(tag.getString(SELECTION_MODE));
        } catch (Exception e) {
            mode = SelectionMode.NONE;
        }
        return new Data(taskId(stack), point(tag, P1), point(tag, P2), tag.getString(DIM), mode,
                point(tag, SEL_P1), tag.getString(SEL_DIM), tag.getString(SEL_PARENT));
    }

    /** 武装选点：设置模式并清空上一次待选点；子区可指定父级 id。不动已确认根区边界。 */
    public static void arm(ItemStack stack, SelectionMode mode, String parentId) {
        update(stack, tag -> {
            tag.putString(SELECTION_MODE, mode.name());
            tag.remove(SEL_P1);
            tag.remove(SEL_DIM);
            if (parentId == null || parentId.isEmpty()) {
                tag.remove(SEL_PARENT);
            } else {
                tag.putString(SEL_PARENT, parentId);
            }
        });
    }

    /** 解除武装：回到 NONE 并清除全部待选点。选区结束（成功或失败）后必须调用。 */
    public static void disarm(ItemStack stack) {
        update(stack, tag -> {
            tag.putString(SELECTION_MODE, SelectionMode.NONE.name());
            tag.remove(SEL_P1);
            tag.remove(SEL_DIM);
            tag.remove(SEL_PARENT);
        });
    }

    /** 记录待选第一角点（不触碰已确认根区边界 P1/P2）。 */
    public static void setPendingStart(ItemStack stack, BlockPos pos, String dimension) {
        update(stack, tag -> {
            putPoint(tag, SEL_P1, pos);
            tag.putString(SEL_DIM, dimension);
        });
    }

    public static void setSelectionMode(ItemStack stack, SelectionMode mode) {
        update(stack, tag -> tag.putString(SELECTION_MODE, mode.name()));
    }

    /** 清除已确认根区边界（仅用于彻底重置任务，选点流程不要用）。 */
    public static void clearPoints(ItemStack stack) {
        update(stack, tag -> {
            tag.remove(P1);
            tag.remove(P2);
            tag.remove(DIM);
            tag.remove(LAST_TICK);
        });
    }
}
