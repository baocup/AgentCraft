package baocup.builder;

import baocup.builder.item.ExternalBuildWandItem;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(BuilderMod.MODID);

    /** 唯一建造入口：选区、区域管理和外部 Agent（MCP）会话。 */
    public static final DeferredItem<ExternalBuildWandItem> EXTERNAL_BUILD_WAND =
            ITEMS.registerItem("external_build_wand",
                    props -> new ExternalBuildWandItem(props.stacksTo(1)));

    private ModItems() {
    }
}
