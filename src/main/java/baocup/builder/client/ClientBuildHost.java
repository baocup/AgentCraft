package baocup.builder.client;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import baocup.builder.BuilderConfig;
import baocup.builder.BuilderMod;
import baocup.builder.agent.AgentTools;
import baocup.builder.agent.LlmClient;
import baocup.builder.agent.McpServer;
import baocup.builder.agent.ToolExecutor;
import baocup.builder.agent.VoxelWorld;
import baocup.builder.item.ExternalBuildWandItem;
import baocup.builder.item.ItemData;
import baocup.builder.net.ModPackets;
import baocup.builder.util.Bounds;
import baocup.builder.util.Json;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 客户端本地建造宿主：外部 Agent 的 MCP 接口、几何编译、VoxelWorld 与拍照渲染都在这里，
 * 服务端只接收最终方块（{@link ModPackets.C2SCommit}）。
 *
 * 这样每个玩家 / 每个客户端都有自己独立的端口与体素世界，多 Agent 并行不会互相覆盖，
 * 也不会占用服务端主线程去渲染图片。
 */
public final class ClientBuildHost {
    public static final ClientBuildHost INSTANCE = new ClientBuildHost();
    /** 单包最多多少格方块，留足余量给 1 MiB 协议上限。 */
    private static final int BATCH_BLOCKS = 4096;
    /**
     * 按 taskId 分槽的串行执行器：同一任务恒定落在同一槽（严格串行，杜绝并发乱序覆盖），
     * 不同任务可并行，线程数固定不随任务数增长。
     */
    private static final int POOL_SLOTS = 4;
    private final ExecutorService[] pools = new ExecutorService[POOL_SLOTS];

    private ExecutorService poolFor(String taskId) {
        int slot = Math.floorMod(taskId == null ? 0 : taskId.hashCode(), POOL_SLOTS);
        synchronized (pools) {
            if (pools[slot] == null) {
                pools[slot] = Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "builder-client-slot-" + slot);
                    thread.setDaemon(true);
                    return thread;
                });
            }
            return pools[slot];
        }
    }

    private record HeldItem(String taskId, Bounds bounds, String dimension) {
    }

    private final Map<String, Session> sessions = new LinkedHashMap<>();
    private final Map<String, ClientState> states = new ConcurrentHashMap<>();
    private final Queue<Runnable> mainQueue = new ConcurrentLinkedQueue<>();
    /** 动作日志按 taskId 分组：不同手杖对应不同任务，日志不能互相串。 */
    private final Map<String, ConcurrentLinkedDeque<ClientState.LogItem>> logsByTask =
            new ConcurrentHashMap<>();
    private McpServer server;
    private int callSeq;
    /** 等待服务端回包的世界查询；key 是 requestId。 */
    private final Map<String, CompletableFuture<JsonObject>> pendingQueries = new ConcurrentHashMap<>();
    private final Map<String, Integer> resumeTotals = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger querySeq =
            new java.util.concurrent.atomic.AtomicInteger();
    /** 外部 Agent 请求设定范围后，等服务端把坐标写回物品 NBT 再回执。 */
    private PendingBounds pendingBounds;

    private ClientBuildHost() {
    }

    public void init() {
        if (!BuilderConfig.mcpEnabled()) {
            BuilderMod.LOGGER.info("[建造] 客户端 MCP 已关闭（mcpEnabled=false）");
            return;
        }
        server = new McpServer(BuilderConfig.mcpToken(), this::tools, this::callTool);
        try {
            server.start(BuilderConfig.mcpHost(), BuilderConfig.mcpPort());
            BuilderMod.LOGGER.info("[建造] 本地 MCP 已监听 http://{}:{}/mcp"
                            + "（客户端建模与渲染，只向服务端提交方块）",
                    BuilderConfig.mcpHost(), BuilderConfig.mcpPort());
        } catch (Exception e) {
            BuilderMod.LOGGER.warn("[建造] 本地 MCP 启动失败（{}:{} 可能被占用，改 mcpPort）：{}",
                    BuilderConfig.mcpHost(), BuilderConfig.mcpPort(), e.toString());
            server = null;
        }
    }

    public void shutdown() {
        if (server != null) {
            server.stop();
            server = null;
        }
        sessions.clear();
        states.clear();
        RegionPreviewRenderer.clear();
        mainQueue.clear();
        logsByTask.clear();
        synchronized (pools) {
            for (int i = 0; i < pools.length; i++) {
                if (pools[i] != null) {
                    pools[i].shutdownNow();
                    pools[i] = null;
                }
            }
        }
    }

    public void onState(ClientState state) {
        if (state == null || state.taskId == null || state.taskId.isBlank()) {
            return;
        }
        states.put(state.taskId, state);
        Session current = sessions.get(state.taskId);
        if (current != null && (!current.regionId.equals(state.activeRegionId)
                || current.forbiddenSignature != forbiddenSignature(state))) {
            sessions.remove(state.taskId);
            logLine(state.taskId, "区域或禁建区已变化，旧局部会话已卸载");
        }
    }

    private static int forbiddenSignature(ClientState state) {
        int hash = 1;
        for (ClientState.ForbiddenItem zone : state.safeForbiddenZones()) {
            hash = 31 * hash + java.util.Arrays.hashCode(zone.bounds);
        }
        return hash;
    }

    /** 客户端每 tick 驱动：主线程队列 + 日志裁剪。 */
    public void tick() {
        Runnable task;
        int guard = 0;
        while ((task = mainQueue.poll()) != null && guard++ < 64) {
            try {
                task.run();
            } catch (Exception e) {
                BuilderMod.LOGGER.warn("[建造] 客户端 MCP 任务异常：{}", e.toString());
            }
        }
        checkPendingBounds();
    }

    /** 服务端应用坐标后会同步物品 NBT；每 tick 检查一次，成功就立刻回执给 Agent。 */
    private void checkPendingBounds() {
        PendingBounds pending = pendingBounds;
        if (pending == null) {
            return;
        }
        if (--pending.remainingTicks <= 0) {
            pendingBounds = null;
            JsonObject out = new JsonObject();
            out.addProperty("status", "pending");
            out.add("requestedBounds", Json.GSON.toJsonTree(pending.target));
            out.addProperty("hint", "已提交给服务端，但没等到物品同步；"
                    + "稍后用 get_context 复查 bounds 是否生效，生效前不要按该范围建模");
            pending.future.complete(out);
            return;
        }
        HeldItem held = held();
        if (held == null) {
            return;
        }
        if (!pending.taskId.equals(held.taskId())) {
            // 手杖被切换：不能拿别的任务的范围当成本任务的确认，直接回 pending 让 Agent 复查
            pendingBounds = null;
            JsonObject out = new JsonObject();
            out.addProperty("status", "pending");
            out.add("requestedBounds", Json.GSON.toJsonTree(pending.target));
            out.addProperty("hint", "手杖已切换到其它任务，无法在本地确认范围；"
                    + "切回该任务的手杖后用 get_context 复查 bounds");
            pending.future.complete(out);
            return;
        }
        if (held.bounds() == null) {
            return;
        }
        if (!sameBounds(held.bounds().toArray(), pending.target)) {
            return;
        }
        pendingBounds = null;
        sessions.remove(pending.taskId);
        JsonObject out = new JsonObject();
        out.addProperty("approved", true);
        out.add("bounds", Json.GSON.toJsonTree(pending.target));
        out.addProperty("volume", Bounds.fromArray(pending.target).volume());
        out.addProperty("note", "范围已生效，本地 VoxelWorld 已重置为新范围");
        pending.future.complete(out);
        logLine(pending.taskId, "范围已生效 " + describe(pending.target)
                + (pending.reason.isBlank() ? "" : "：" + pending.reason));
    }

    /** 只返回该任务自己的动作日志，避免不同 taskId 的日志串在一起。 */
    public List<ClientState.LogItem> snapshotLogs(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return new ArrayList<>();
        }
        ConcurrentLinkedDeque<ClientState.LogItem> queue = logsByTask.get(taskId);
        return queue == null ? new ArrayList<>() : new ArrayList<>(queue);
    }

    private void logLine(String taskId, String text) {
        if (taskId == null || taskId.isBlank()) {
            BuilderMod.LOGGER.info("[建造][本地 MCP] {}", text);
            return;
        }
        ClientState.LogItem item = new ClientState.LogItem();
        item.ts = System.currentTimeMillis();
        item.stage = "外部 Agent";
        item.level = "INFO";
        item.text = text;
        ConcurrentLinkedDeque<ClientState.LogItem> queue =
                logsByTask.computeIfAbsent(taskId, id -> new ConcurrentLinkedDeque<>());
        queue.addLast(item);
        while (queue.size() > 400) {
            queue.pollFirst();
        }
        BuilderMod.LOGGER.info("[建造][本地 MCP][{}] {}", shortId(taskId), text);
    }

    private static String shortId(String taskId) {
        return taskId.length() <= 8 ? taskId : taskId.substring(0, 8);
    }

    // ------------------------------------------------------------------
    // MCP 工具
    // ------------------------------------------------------------------

    private JsonArray tools() {
        JsonArray array = new JsonArray();
        for (JsonElement element : AgentTools.definitions(true)) {
            JsonObject function = element.getAsJsonObject().getAsJsonObject("function");
            String name = Json.str(function, "name", "");
            if (AgentTools.FINISH_BUILD.equals(name)) {
                continue;
            }
            JsonObject tool = new JsonObject();
            tool.addProperty("name", name);
            String description = Json.str(function, "description", "");
            if (AgentTools.CAPTURE.equals(name)) {
                // MCP 只回文本；这里的描述必须按外部链路的实际能力来写
                description = "从独立虚拟相机渲染 PNG 并落盘，用于自检 3D 造型、比例与遮挡。"
                        + "MCP 不回传图片字节：回执给 captureDir 与 photoPaths（可直接读取的绝对路径）、"
                        + "cameras（各机位坐标 / 朝向 / insideSolid）、localReport 统计。"
                        + "环境能读本地文件时请自行打开 PNG，否则用 read_state 核对。"
                        + "机位与朝向完全由你决定（绝对世界坐标，见参数）。";
            }
            tool.addProperty("description", description);
            tool.add("inputSchema", function.getAsJsonObject("parameters"));
            array.add(tool);
        }
        array.add(tool("get_context",
                "读取当前手持「外部建造手杖」绑定的任务 id 与选区，以及本地建模状态。"
                        + "开始建造前先调它；返回 held=false 说明没拿手杖。",
                "{\"type\":\"object\",\"properties\":{}}"));
        array.add(tool("list_regions",
                "列出根建造区与所有递归细分区（名称、描述、parentId、状态、进度、边界），并返回 activeRegionId。",
                "{\"type\":\"object\",\"properties\":{}}"));
        array.add(tool("create_region",
                "在父区域内创建细分区；bounds 必须完整位于父区域。默认聚焦新区域，后续建模只载入它。",
                """
                        {"type":"object","properties":{
                          "parentId":{"type":"string"},"name":{"type":"string"},
                          "description":{"type":"string"},"focus":{"type":"boolean"},
                          "bounds":{"type":"array","items":{"type":"integer"},"minItems":6,"maxItems":6}
                        },"required":["name","bounds"]}"""));
        array.add(tool("update_region",
                "更新区域名称、描述、PLANNED/IN_PROGRESS/DONE 状态、0..100 进度；也可调整非根区域 bounds。",
                """
                        {"type":"object","properties":{
                          "id":{"type":"string"},"name":{"type":"string"},"description":{"type":"string"},
                          "status":{"type":"string","enum":["PLANNED","IN_PROGRESS","DONE"]},
                          "progress":{"type":"integer"},"bounds":{"type":"array","items":{"type":"integer"}}
                        }}"""));
        array.add(tool("focus_region",
                "聚焦一个区域并建立独立局部体素会话；不会加载整个根工程。",
                "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}},\"required\":[\"id\"]}"));
        array.add(tool("delete_region",
                "删除一个非根区域及其全部后代；不会删除已经放入世界的方块。",
                "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}},\"required\":[\"id\"]}"));
        array.add(tool("list_forbidden_zones",
                "列出所有独立禁建区；禁建区可以超出根建造区。",
                "{\"type\":\"object\",\"properties\":{}}"));
        array.add(tool("create_forbidden",
                "创建独立禁建区。建模时过滤，服务端实际放置时再次强制拦截。",
                """
                        {"type":"object","properties":{
                          "name":{"type":"string"},"description":{"type":"string"},
                          "bounds":{"type":"array","items":{"type":"integer"},"minItems":6,"maxItems":6}
                        },"required":["bounds"]}"""));
        array.add(tool("delete_forbidden",
                "按 id 删除禁建区。",
                "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}},\"required\":[\"id\"]}"));
        array.add(tool("validate_build",
                "提交前确定性校验局部体素：区域/禁建区、预期包围盒、X/Z 镜像对称、悬空与连通分量；"
                        + "发现错位或方向问题时先修正，不要直接提交。",
                """
                        {"type":"object","properties":{
                          "expectedBounds":{"type":"array","items":{"type":"integer"},"minItems":6,"maxItems":6},
                          "symmetry":{"type":"string","enum":["NONE","X","Z","BOTH"]},
                          "maxComponents":{"type":"integer","description":"允许的 6 邻接连通分量数，默认不限制"}
                        }}"""));
        array.add(tool("request_bounds",
                "自行设定根建造范围（绝对世界坐标，无选区体积上限）。与玩家在 GUI 里手动输入坐标等效："
                        + "会提交给服务端写回物品并刷新选区，成功返回 approved=true 与生效的 bounds；"
                        + "返回 status=pending 说明还没同步完，稍后用 get_context 复查。"
                        + "范围一旦大于玩家原本的选区，建筑会被截断，所以动工前先确认。",
                """
                        {"type":"object","properties":{
                          "bounds":{"type":"array","items":{"type":"integer"},
                            "description":"[x1,y1,z1,x2,y2,z2] 六个绝对坐标"},
                          "reason":{"type":"string","description":"为什么需要这个范围，仅写进日志"}
                        },"required":["bounds"]}"""));
        array.add(tool("read_world",
                "分页检测真实地形，建造全程可随时自主调用。默认读取当前聚焦区域；root=true 读取根区；"
                        + "regionId 可检测任意已存在区域（无需先聚焦）；region 传局部坐标。"
                        + "hasMore=true 时把 nextCursor 原样带入下一次调用，直到扫描完成。单次扫描预算不等于总量上限。",
                """
                        {"type":"object","properties":{
                          "region":{"type":"array","items":{"type":"integer"},"description":"可选局部 [x1,y1,z1,x2,y2,z2]"},
                          "regionId":{"type":"string"},"root":{"type":"boolean"},
                          "cursor":{"type":"array","items":{"type":"integer"},"description":"上一响应的 nextCursor [x,y,z]"},
                          "limit":{"type":"integer","description":"最多返回多少非空气方块，默认 4096，上限 8192"},
                          "scanBudget":{"type":"integer","description":"单次最多扫描格数，默认 32768，上限 131072"}
                        }}"""));
        array.add(tool("get_build_status",
                "查询服务端该任务的归档版本、状态、实际写入格数与是否仍在写入队列。"
                        + "submit_build 是异步的，用它确认是否真的完成了。",
                "{\"type\":\"object\",\"properties\":{}}"));
        array.add(tool("resume",
                "按区域分页恢复本地 VoxelWorld：客户端重启、切换聚焦或本地状态丢失后调用，载入后即可在已有建筑上加建。"
                        + "submit_build 完成即归档完整方块图；若该区域没有归档（例如直接写进世界的旧建筑），"
                        + "会回退扫描真实世界（仅已加载区块、体积不超过 200 万）作为恢复来源。"
                        + "默认恢复当前聚焦区；regionId 指定任意区域；hasMore=true 时把 nextOffset 带入下一次调用直到恢复完。",
                """
                        {"type":"object","properties":{
                          "regionId":{"type":"string","description":"要恢复的区域 id，默认当前聚焦区"},
                          "offset":{"type":"integer","description":"分页起点，默认 0"},
                          "limit":{"type":"integer","description":"单页方块数，默认 4096，上限 8192"}
                        }}"""));
        array.add(tool("list_versions",
                "列出该任务所有可回滚的归档版本与当前版本，回滚前先用它确认版本号。",
                "{\"type\":\"object\",\"properties\":{}}"));
        array.add(tool("revert",
                "把世界回滚到某个归档版本：方块按 blocksPerTick 排队写回，"
                        + "之后用 get_build_status 确认完成。不填 version 默认回到上一版。",
                """
                        {"type":"object","properties":{
                          "version":{"type":"integer","description":"目标版本号，留空为上一版"}
                        }}"""));
        array.add(tool("submit_build",
                "把本地建模结果提交给服务器写入世界。几何编译与拍照渲染都已在客户端完成，这一步只发送方块。"
                        + "**每完成一个可独立成立的建造部分就调用一次**（地基 / 墙体 / 屋顶 / 室内），"
                        + "不要攒到最后：工具轮用尽或对话中断时本地体素会直接丢失，世界一格都没有。"
                        + "服务端按区块预算写入；提交后保留当前局部会话，可继续增量加建。",
                """
                        {"type":"object","properties":{
                          "summary":{"type":"string","description":"本轮建造说明"}
                        }}"""));
        return array;
    }

    private static JsonObject tool(String name, String description, String schema) {
        JsonObject tool = new JsonObject();
        tool.addProperty("name", name);
        tool.addProperty("description", description);
        JsonObject parsed = Json.GSON.fromJson(schema, JsonObject.class);
        tool.add("inputSchema", parsed == null ? new JsonObject() : parsed);
        return tool;
    }

    private JsonObject callTool(String name, JsonObject args) {
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        mainQueue.add(() -> dispatch(name, args, future));
        boolean query = switch (name) {
            case "read_world", "get_build_status", "resume", "list_versions", "revert",
                    "list_regions", "create_region", "update_region", "delete_region", "focus_region",
                    "list_forbidden_zones", "create_forbidden", "delete_forbidden", "request_bounds" -> true;
            default -> false;
        };
        int timeout = "resume".equals(name) ? Math.max(60, BuilderConfig.mcpToolTimeoutSeconds())
                : query ? 20 : Math.max(30, BuilderConfig.mcpToolTimeoutSeconds());
        try {
            return future.get(timeout, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return error("工具 " + name + " 超时（客户端是否卡住 / 是否在单人存档中？）");
        } catch (Exception e) {
            return error("工具 " + name + " 失败：" + e);
        }
    }

    /** 物品 UI 的局部预览：必要时先按当前区域分页恢复，再离屏渲染，不加载整个根工程。 */
    public void previewActiveRegion(String view, Consumer<JsonObject> callback) {
        Consumer<JsonObject> safe = result -> mainQueue.add(() -> callback.accept(result));
        mainQueue.add(() -> {
            HeldItem held = held();
            if (held == null) {
                safe.accept(error("请手持外部建造手杖再预览"));
                return;
            }
            Session session = sessionFor(held);
            JsonObject captureArgs = new JsonObject();
            captureArgs.addProperty("view", view == null || view.isBlank() ? "ortho" : view);
            captureArgs.addProperty("reason", "物品 UI 局部预览");
            if (session.world.size() > 0) {
                CompletableFuture<JsonObject> capture = new CompletableFuture<>();
                submitTool("capture", captureArgs, capture);
                capture.whenComplete((result, throwable) -> safe.accept(throwable != null
                        ? error(throwable.getMessage()) : result));
                return;
            }
            JsonObject resumeArgs = new JsonObject();
            resumeArgs.addProperty("regionId", session.regionId);
            CompletableFuture<JsonObject> resumeFuture = new CompletableFuture<>();
            sendQuery("resume", resumeArgs, resumeFuture);
            resumeFuture.whenComplete((result, throwable) -> {
                if (throwable != null) {
                    safe.accept(error(throwable.getMessage()));
                    return;
                }
                if (result.has("error")) {
                    safe.accept(result);
                    return;
                }
                if (session.world.size() == 0) {
                    safe.accept(error("当前区域暂无可预览方块；请先由外部 Agent 建模或提交"));
                    return;
                }
                CompletableFuture<JsonObject> capture = new CompletableFuture<>();
                submitTool("capture", captureArgs, capture);
                capture.whenComplete((captured, captureError) -> safe.accept(captureError != null
                        ? error(captureError.getMessage()) : captured));
            });
        });
    }

    /** 物品 UI 的地形检测首页：完整扫描仍由外部 Agent 通过 nextCursor 分页推进。 */
    public CompletableFuture<JsonObject> scanActiveRegion(boolean root) {
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        mainQueue.add(() -> {
            HeldItem held = held();
            if (held == null) {
                future.complete(error("请手持外部建造手杖再检测地形"));
                return;
            }
            JsonObject args = new JsonObject();
            if (root) {
                args.addProperty("root", true);
            } else {
                ClientState snapshot = states.get(held.taskId());
                args.addProperty("regionId", snapshot == null ? "root" : snapshot.activeRegionId);
            }
            args.addProperty("limit", 512);
            args.addProperty("scanBudget", 32768);
            sendQuery("read_world", args, future);
        });
        return future;
    }

    private void dispatch(String name, JsonObject args, CompletableFuture<JsonObject> future) {
        try {
            switch (name) {
                case "get_context" -> future.complete(context());
                case "request_bounds" -> requestBounds(args, future);
                case "submit_build" -> submitBuild(args, future);
                case "read_world", "get_build_status", "resume", "list_versions", "revert",
                        "list_regions", "create_region", "update_region", "delete_region", "focus_region",
                        "list_forbidden_zones", "create_forbidden", "delete_forbidden"
                        -> sendQuery(name, args, future);
                case "validate_build" -> {
                    HeldItem validationHeld = held();
                    String poolKey = validationHeld == null ? "validation" : validationHeld.taskId();
                    Session validationSession = validationHeld == null ? null : sessions.get(validationHeld.taskId());
                    poolFor(poolKey).execute(() -> {
                        try {
                            future.complete(validateBuild(validationSession, args));
                        } catch (Exception e) {
                            future.complete(error("几何校验异常：" + e));
                        }
                    });
                }
                default -> submitTool(name, args, future);
            }
        } catch (Exception e) {
            future.complete(error(e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    private JsonObject context() {
        HeldItem held = held();
        JsonObject out = new JsonObject();
        if (held == null) {
            out.addProperty("held", false);
            out.addProperty("hint", "让玩家手持「外部建造手杖」后再调用；"
                    + "选区可由玩家左键两次、GUI 手动输入，或由你调用 request_bounds 设定");
            return out;
        }
        out.addProperty("held", true);
        out.addProperty("taskId", held.taskId() == null ? "" : held.taskId());
        ClientState state = held.taskId() == null ? null : states.get(held.taskId());
        Bounds bounds = state == null ? held.bounds() : state.activeBounds();
        if (state != null) {
            out.addProperty("activeRegionId", state.activeRegionId);
            out.addProperty("regionCount", state.safeRegions().size());
            out.addProperty("forbiddenZoneCount", state.safeForbiddenZones().size());
            ClientState.RegionItem active = state.activeRegion();
            if (active != null) {
                out.add("activeRegion", Json.GSON.toJsonTree(active));
            }
            if (state.rootBounds() != null) {
                out.add("rootBounds", Json.GSON.toJsonTree(state.rootBounds().toArray()));
            }
        }
        if (bounds != null) {
            out.add("bounds", Json.GSON.toJsonTree(bounds.toArray()));
            out.addProperty("scope", "activeRegion");
            out.addProperty("volume", bounds.volume());
            // 聚焦区域中心与四角的真实地表高度：贴地建筑不要再靠猜
            int[] xs = {(int) (((long) bounds.x1 + bounds.x2) / 2), bounds.x1, bounds.x2};
            int[] zs = {(int) (((long) bounds.z1 + bounds.z2) / 2), bounds.z1, bounds.z2};
            JsonArray samples = new JsonArray();
            int lowest = Integer.MAX_VALUE;
            for (int x : xs) {
                for (int z : zs) {
                    int y = sampleGroundY(x, z);
                    if (y == Integer.MIN_VALUE) {
                        samples.add(com.google.gson.JsonNull.INSTANCE);
                    } else {
                        samples.add(y);
                        lowest = Math.min(lowest, y);
                    }
                }
            }
            out.add("groundSamples", samples);
            if (lowest != Integer.MAX_VALUE) {
                out.addProperty("groundY", lowest);
            }
        } else {
            out.addProperty("hint", "还没有选区：可让玩家左键两次 / GUI 手动输入，"
                    + "或调用 request_bounds 由你设定");
        }
        Session session = held.taskId() == null ? null : sessions.get(held.taskId());
        out.addProperty("localBlocks", session == null ? 0 : session.world.size());
        out.addProperty("localCaptures", session == null ? 0 : session.captures.size());
        out.addProperty("submitCount", session == null ? 0 : session.submitCount);
        out.addProperty("pendingDelta", session == null ? 0
                : Math.max(0, session.world.size() - session.blocksAtLastSubmit));
        out.addProperty("note", "几何编译与渲染都在本地且只针对当前区域；submit_build 只提交相对上次的变化，"
                + "本地删除会以 minecraft:air 传播，提交后可在当前区域继续加建");
        return out;
    }

    /** 向服务端发一次查询，等待 S2CQueryResult 回包；超时由 callTool 统一处理。 */
    private void sendQuery(String query, JsonObject args, CompletableFuture<JsonObject> future) {
        HeldItem held = held();
        if (held == null || held.taskId() == null) {
            future.complete(error("请让玩家手持「外部建造手杖」后再查询"));
            return;
        }
        String requestId = "q" + querySeq.incrementAndGet();
        pendingQueries.put(requestId, future);
        future.whenComplete((result, error) -> pendingQueries.remove(requestId));
        PacketDistributor.sendToServer(new ModPackets.C2SQuery(
                held.taskId(), requestId, query, Json.GSON.toJson(args)));
    }

    /** 服务端查询结果回调（网络线程）：只碰线程安全容器，载入世界投递回主线程。 */
    public void onQueryResult(ModPackets.S2CQueryResult payload) {
        if (payload == null) {
            return;
        }
        JsonObject json;
        try {
            json = Json.GSON.fromJson(payload.json(), JsonObject.class);
        } catch (Exception e) {
            json = new JsonObject();
        }
        if (json == null) {
            json = new JsonObject();
        }
        if ("resume".equals(Json.str(json, "query", ""))) {
            JsonObject result = json;
            mainQueue.add(() -> handleResume(result));
            return;
        }
        CompletableFuture<JsonObject> future = pendingQueries.remove(payload.requestId());
        if (future != null) {
            future.complete(json);
        }
    }

    /** 把归档版本灌回本地 VoxelWorld，并标记为「已提交」避免重复发送。 */
    private void handleResume(JsonObject json) {
        CompletableFuture<JsonObject> future =
                pendingQueries.remove(Json.str(json, "requestId", ""));
        if (future == null) {
            return;
        }
        String taskId = Json.str(json, "taskId", "");
        if (json.has("error")) {
            resumeTotals.remove(taskId);
            future.complete(error(Json.str(json, "error", "恢复失败")));
            return;
        }
        HeldItem held = held();
        if (held == null || taskId.isBlank() || !taskId.equals(held.taskId())) {
            resumeTotals.remove(taskId);
            future.complete(error("手杖与归档任务不一致，无法恢复"));
            return;
        }
        Session session = sessionFor(held);
        String responseRegion = Json.str(json, "regionId", session == null ? "" : session.regionId);
        if (session == null || !session.regionId.equals(responseRegion)) {
            resumeTotals.remove(taskId);
            future.complete(error("聚焦区域已变化，请重新执行 resume_build"));
            return;
        }
        JsonArray blocks = json.getAsJsonArray("blocks");
        int loaded = 0;
        if (blocks != null) {
            for (JsonElement element : blocks) {
                try {
                    JsonArray one = element.getAsJsonArray();
                    String full = one.get(3).getAsString();
                    int bracket = full.indexOf('[');
                    String block = bracket > 0 ? full.substring(0, bracket) : full;
                    String state = bracket > 0 && full.endsWith("]")
                            ? full.substring(bracket + 1, full.length() - 1) : "";
                    session.world.write(new VoxelWorld.WriteResult(),
                            one.get(0).getAsInt(), one.get(1).getAsInt(), one.get(2).getAsInt(),
                            block, state, false);
                    loaded++;
                } catch (Exception ignored) {
                    // 跳过坏条目
                }
            }
        }
        int previous = Json.integer(json, "offset", 0) == 0 ? 0 : resumeTotals.getOrDefault(taskId, 0);
        int totalLoaded = previous + loaded;
        resumeTotals.put(taskId, totalLoaded);
        if (Json.bool(json, "hasMore", false)) {
            JsonObject next = new JsonObject();
            next.addProperty("regionId", session.regionId);
            next.addProperty("offset", Json.integer(json, "nextOffset", 0));
            next.addProperty("limit", 4096);
            sendQuery("resume", next, future);
            return;
        }
        Map<Long, VoxelWorld.Voxel> snapshot = new LinkedHashMap<>();
        for (VoxelWorld.Voxel voxel : session.world.all()) {
            snapshot.put(VoxelWorld.key(voxel.x(), voxel.y(), voxel.z()), voxel);
        }
        session.lastSubmitted.clear();
        session.lastSubmitted.putAll(snapshot);
        session.blocksAtLastSubmit = snapshot.size();
        session.submitCount++;
        resumeTotals.remove(taskId);
        JsonObject out = new JsonObject();
        out.addProperty("resumed", totalLoaded);
        out.addProperty("regionId", session.regionId);
        out.addProperty("version", Json.integer(json, "version", 0));
        out.addProperty("note", "已分页恢复当前区域的局部 VoxelWorld，可继续加建；这批方块不会重复提交");
        future.complete(out);
        logLine(taskId, "从归档 v" + Json.integer(json, "version", 0)
                + " 恢复区域 " + session.regionId + "，共 " + totalLoaded + " 格");
    }

    private Session sessionFor(HeldItem held) {
        if (held == null || held.taskId() == null) {
            return null;
        }
        ClientState state = states.get(held.taskId());
        Bounds bounds = state == null ? held.bounds() : state.activeBounds();
        if (bounds == null) {
            return null;
        }
        String regionId = state == null ? "root" : state.activeRegionId;
        int signature = state == null ? 1 : forbiddenSignature(state);
        Session current = sessions.get(held.taskId());
        if (current != null && (!current.regionId.equals(regionId) || current.forbiddenSignature != signature)) {
            sessions.remove(held.taskId());
            current = null;
        }
        if (current == null) {
            current = new Session(held.taskId(), regionId, bounds, state);
            sessions.put(held.taskId(), current);
            logLine(held.taskId(), "建立局部会话 " + regionId + "，范围 " + bounds.display());
        }
        return current;
    }

    private JsonObject validateBuild(Session session, JsonObject args) {
        if (session == null) {
            return error("当前区域还没有局部建模；请先 draw_shape / place_blocks 或 resume_build");
        }
        JsonObject out = new JsonObject();
        out.addProperty("taskId", session.taskId);
        out.addProperty("regionId", session.regionId);
        JsonArray errors = new JsonArray();
        JsonArray warnings = new JsonArray();
        List<VoxelWorld.Voxel> voxels = session.world.all();
        Map<Long, VoxelWorld.Voxel> byKey = new HashMap<>();
        Bounds actual = Bounds.fromArray(session.world.occupiedBounds());
        int outside = 0;
        int forbidden = 0;
        JsonArray violationSamples = new JsonArray();
        JsonObject facings = new JsonObject();
        JsonObject axes = new JsonObject();
        for (VoxelWorld.Voxel voxel : voxels) {
            byKey.put(VoxelWorld.key(voxel.x(), voxel.y(), voxel.z()), voxel);
            boolean invalid = !session.bounds.contains(voxel.x(), voxel.y(), voxel.z());
            if (!invalid) {
                for (Bounds zone : session.forbiddenZones) {
                    if (zone.contains(voxel.x(), voxel.y(), voxel.z())) {
                        forbidden++;
                        invalid = true;
                        break;
                    }
                }
            } else {
                outside++;
            }
            if (invalid && violationSamples.size() < 32) {
                violationSamples.add(Json.GSON.toJsonTree(new int[]{voxel.x(), voxel.y(), voxel.z()}));
            }
            collectDirectionalProperty(facings, voxel, "facing");
            collectDirectionalProperty(axes, voxel, "axis");
        }
        if (voxels.isEmpty()) {
            errors.add("当前区域没有可提交方块");
        }
        if (outside > 0) {
            errors.add("有 " + outside + " 格超出当前区域");
        }
        if (forbidden > 0) {
            errors.add("有 " + forbidden + " 格落入禁建区");
        }
        Bounds expected = null;
        if (args != null && args.has("expectedBounds")) {
            try {
                expected = Bounds.fromArray(Json.GSON.fromJson(args.get("expectedBounds"), int[].class));
            } catch (Exception e) {
                errors.add("expectedBounds 无效：" + e.getMessage());
            }
            if (expected != null && (actual == null || !java.util.Arrays.equals(expected.toArray(), actual.toArray()))) {
                errors.add("实际包围盒与预期不一致；通常是原点、平移、+1 或镜像方向错误");
            }
        }
        String symmetry = args == null ? "NONE" : Json.str(args, "symmetry", "NONE").toUpperCase(java.util.Locale.ROOT);
        Bounds symmetryReference = expected != null ? expected : actual;
        if (symmetryReference != null && ("X".equals(symmetry) || "BOTH".equals(symmetry))) {
            symmetryErrors(byKey, symmetryReference, true, errors);
        }
        if (symmetryReference != null && ("Z".equals(symmetry) || "BOTH".equals(symmetry))) {
            symmetryErrors(byKey, symmetryReference, false, errors);
        }

        Set<Long> remaining = new HashSet<>(byKey.keySet());
        int components = 0;
        int largestComponent = 0;
        int isolated = 0;
        int[][] neighbors = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!remaining.isEmpty()) {
            long start = remaining.iterator().next();
            remaining.remove(start);
            ArrayDeque<Long> queue = new ArrayDeque<>();
            queue.add(start);
            int size = 0;
            while (!queue.isEmpty()) {
                long key = queue.removeFirst();
                VoxelWorld.Voxel voxel = byKey.get(key);
                if (voxel == null) {
                    continue;
                }
                size++;
                for (int[] delta : neighbors) {
                    long x = (long) voxel.x() + delta[0];
                    long y = (long) voxel.y() + delta[1];
                    long z = (long) voxel.z() + delta[2];
                    if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE
                            || y < Integer.MIN_VALUE || y > Integer.MAX_VALUE
                            || z < Integer.MIN_VALUE || z > Integer.MAX_VALUE) {
                        continue;
                    }
                    long neighbor = VoxelWorld.key((int) x, (int) y, (int) z);
                    if (byKey.containsKey(neighbor) && remaining.remove(neighbor)) {
                        queue.addLast(neighbor);
                    }
                }
            }
            components++;
            largestComponent = Math.max(largestComponent, size);
            if (size == 1) {
                isolated++;
            }
        }
        if (args != null && args.has("maxComponents")
                && components > Math.max(1, Json.integer(args, "maxComponents", 1))) {
            errors.add("连通分量 " + components + " 超过预期上限 " + Json.integer(args, "maxComponents", 1)
                    + "；可能存在断墙、漏块或错位碎片");
        } else if (components > 1) {
            warnings.add("检测到 " + components + " 个连通分量；若应为一个整体，请检查断点");
        }
        if (isolated > 0) {
            warnings.add("检测到 " + isolated + " 个孤立方块");
        }
        out.addProperty("valid", errors.size() == 0);
        out.addProperty("blocks", voxels.size());
        out.add("actualBounds", Json.GSON.toJsonTree(actual == null ? new int[0] : actual.toArray()));
        if (expected != null) {
            out.add("expectedBounds", Json.GSON.toJsonTree(expected.toArray()));
        }
        out.addProperty("components", components);
        out.addProperty("largestComponent", largestComponent);
        out.addProperty("isolatedBlocks", isolated);
        out.add("facings", facings);
        out.add("axes", axes);
        out.add("violationSamples", violationSamples);
        out.add("errors", errors);
        out.add("warnings", warnings);
        out.addProperty("next", errors.size() == 0
                ? "校验通过；拍摄当前区域多角度预览，确认方向与形状后 submit_build"
                : "不要提交；先按 errors、实际/预期包围盒和方向统计修正局部模型");
        return out;
    }

    private static void collectDirectionalProperty(JsonObject out, VoxelWorld.Voxel voxel, String property) {
        String full = voxel.fullState();
        int begin = full.indexOf('[');
        if (begin < 0 || !full.endsWith("]")) {
            return;
        }
        String block = full.substring(0, begin);
        String state = full.substring(begin + 1, full.length() - 1);
        for (String entry : state.split(",")) {
            String[] pair = entry.trim().split("=", 2);
            if (pair.length == 2 && property.equals(pair[0])) {
                JsonObject counts = out.getAsJsonObject(block);
                if (counts == null) {
                    counts = new JsonObject();
                    out.add(block, counts);
                }
                counts.addProperty(pair[1], Json.integer(counts, pair[1], 0) + 1);
            }
        }
    }

    private static void symmetryErrors(Map<Long, VoxelWorld.Voxel> voxels, Bounds reference,
                                       boolean mirrorX, JsonArray errors) {
        int missing = 0;
        JsonArray samples = new JsonArray();
        for (VoxelWorld.Voxel voxel : voxels.values()) {
            long mx = mirrorX ? (long) reference.x1 + reference.x2 - voxel.x() : voxel.x();
            long mz = mirrorX ? voxel.z() : (long) reference.z1 + reference.z2 - voxel.z();
            if (mx < Integer.MIN_VALUE || mx > Integer.MAX_VALUE || mz < Integer.MIN_VALUE || mz > Integer.MAX_VALUE) {
                continue;
            }
            VoxelWorld.Voxel other = voxels.get(VoxelWorld.key((int) mx, voxel.y(), (int) mz));
            if (other == null || !other.block().equals(voxel.block())) {
                missing++;
                if (samples.size() < 16) {
                    samples.add(Json.GSON.toJsonTree(new int[]{(int) mx, voxel.y(), (int) mz}));
                }
            }
        }
        if (missing > 0) {
            JsonObject detail = new JsonObject();
            detail.addProperty("axis", mirrorX ? "X" : "Z");
            detail.addProperty("missing", missing);
            detail.add("expectedSamples", samples);
            errors.add(detail);
        }
    }

    private void submitTool(String name, JsonObject args, CompletableFuture<JsonObject> future) {
        HeldItem held = held();
        if (held == null || held.taskId() == null) {
            future.complete(error("请让玩家手持「外部建造手杖」并选好范围"));
            return;
        }
        Session session = sessionFor(held);
        if (session == null) {
            future.complete(error("当前区域还没有选区：可调用 request_bounds 设定根区，"
                    + "或让玩家左键两次 / 在 GUI 里手动输入坐标"));
            return;
        }
        poolFor(held.taskId()).submit(() -> {
            try {
                ToolExecutor.Outcome outcome = session.executor.execute(
                        new LlmClient.ToolCall("local_" + (++callSeq), name, Json.GSON.toJson(args)));
                session.collect(outcome);
                JsonElement parsed;
                try {
                    parsed = Json.GSON.fromJson(outcome.result, JsonElement.class);
                } catch (Exception e) {
                    parsed = Json.GSON.toJsonTree(outcome.result);
                }
                // 回执统一为扁平结构：placed / rejected / actualBounds 等直接放顶层，
                // 不再套一层 result（否则按文档读 res["placed"] 恒为 0）
                JsonObject out = new JsonObject();
                if (parsed != null && parsed.isJsonObject()) {
                    for (Map.Entry<String, JsonElement> entry
                            : parsed.getAsJsonObject().entrySet()) {
                        out.add(entry.getKey(), entry.getValue());
                    }
                } else {
                    out.add("result", parsed == null ? new JsonObject() : parsed);
                }
                out.addProperty("localOnly", true);
                out.addProperty("submitHint",
                        "以上改动只在客户端本地（拍得到、世界看不到）；这一段确认后就立刻 submit_build，"
                                + "不要攒到最后一次性提交");
                int delta = session.world.size() - session.blocksAtLastSubmit;
                if (delta >= 1500) {
                    out.addProperty("warning", "距上次提交已新增 " + delta
                            + " 格：立刻调用 submit_build 把这段落进世界");
                }
                future.complete(out);
                logLine(held.taskId(), name + " 完成，本地累计 " + session.world.size()
                        + " 格（尚未写入世界）");
            } catch (Exception e) {
                future.complete(error(name + " 执行异常：" + e));
            }
        });
    }

    /**
     * 外部 Agent 自主设定选区：走与 GUI「应用坐标」完全相同的服务端通道
     * （{@code C2SAction("bounds")}），由服务端改任务记忆并把坐标写回物品 NBT，
     * 客户端在 tick 里等到同步完成再回执。
     */
    private void requestBounds(JsonObject args, CompletableFuture<JsonObject> future) {
        HeldItem held = held();
        if (held == null || held.taskId() == null) {
            future.complete(error("请让玩家手持「外部建造手杖」后再设定范围"));
            return;
        }
        if (pendingBounds != null) {
            future.complete(error("任务 " + shortId(pendingBounds.taskId)
                    + " 的范围请求还在等服务端确认，稍后再试"));
            return;
        }
        // 改选区会移除本地 Session，不能静默丢掉已经建好的东西
        Session existing = sessions.get(held.taskId());
        int pending = existing == null ? 0
                : Math.max(0, existing.world.size() - existing.blocksAtLastSubmit);
        if (pending > 0) {
            future.complete(error("还有 " + pending
                    + " 格建模未提交；改选区会重置本地 VoxelWorld，"
                    + "请先调用 submit_build 落盘，再调用 request_bounds"));
            return;
        }
        JsonArray box = args.getAsJsonArray("bounds");
        if (box == null || box.size() != 6) {
            future.complete(error("bounds 必须是 [x1,y1,z1,x2,y2,z2] 六个整数"));
            return;
        }
        int[] values = new int[6];
        try {
            for (int i = 0; i < 6; i++) {
                values[i] = box.get(i).getAsInt();
            }
        } catch (Exception e) {
            future.complete(error("bounds 必须是整数"));
            return;
        }
        Bounds bounds = Bounds.fromArray(values);
        bounds.normalize();
        int[] target = bounds.toArray();
        String reason = Json.str(args, "reason", "").trim();
        JsonObject payload = new JsonObject();
        payload.add("bounds", Json.GSON.toJsonTree(target));
        PacketDistributor.sendToServer(new ModPackets.C2SAction(
                held.taskId(), "bounds", Json.GSON.toJson(payload)));
        // 5 秒（100 tick）足够服务端处理 + 物品同步；超时只回 pending，请求本身仍然生效
        pendingBounds = new PendingBounds(future, target, held.taskId(), reason, 100);
        sessions.remove(held.taskId());
        logLine(held.taskId(), "提交范围 " + describe(target) + "（" + bounds.volume() + " 格）"
                + (reason.isBlank() ? "" : "：" + reason));
    }

    /** 只保留相对上次提交的变化；被删掉的格子以 minecraft:air 返回，让服务端擦除。 */
    static List<VoxelWorld.Voxel> computeChanges(
            Map<Long, VoxelWorld.Voxel> previous, Map<Long, VoxelWorld.Voxel> current) {
        List<VoxelWorld.Voxel> changes = new ArrayList<>();
        for (Map.Entry<Long, VoxelWorld.Voxel> entry : current.entrySet()) {
            VoxelWorld.Voxel old = previous.get(entry.getKey());
            if (old == null || !old.fullState().equals(entry.getValue().fullState())) {
                changes.add(entry.getValue());
            }
        }
        for (Map.Entry<Long, VoxelWorld.Voxel> entry : previous.entrySet()) {
            if (!current.containsKey(entry.getKey())) {
                VoxelWorld.Voxel old = entry.getValue();
                changes.add(new VoxelWorld.Voxel(old.x(), old.y(), old.z(),
                        "minecraft:air", ""));
            }
        }
        return changes;
    }

    /**
     * 增量提交：只发「自上次提交以来变化的格子」，不再每次全量重发整个 VoxelWorld。
     * 本地删掉的格子会以 minecraft:air 发给服务端，让删除真正传播到世界。
     * 每包最多 BATCH_BLOCKS 格，最后一批带 last=true。
     */
    private void submitBuild(JsonObject args, CompletableFuture<JsonObject> future) {
        HeldItem held = held();
        if (held == null || held.taskId() == null) {
            future.complete(error("请让玩家手持「外部建造手杖」并选好坐标"));
            return;
        }
        Session session = sessions.get(held.taskId());
        if (session == null) {
            future.complete(error("本地还没有任何建模；先用 draw_shape / place_blocks"));
            return;
        }
        Map<Long, VoxelWorld.Voxel> current = new LinkedHashMap<>();
        for (VoxelWorld.Voxel voxel : session.world.all()) {
            current.put(VoxelWorld.key(voxel.x(), voxel.y(), voxel.z()), voxel);
        }
        List<VoxelWorld.Voxel> changes = computeChanges(session.lastSubmitted, current);
        int removed = 0;
        for (VoxelWorld.Voxel voxel : changes) {
            if ("minecraft:air".equals(voxel.fullState())) {
                removed++;
            }
        }
        if (changes.isEmpty()) {
            JsonObject out = new JsonObject();
            out.addProperty("submitted", 0);
            out.addProperty("note", "自上次提交以来没有任何变化，无需提交；"
                    + "继续建模或删除后再调用");
            future.complete(out);
            return;
        }
        int total = changes.size();
        String dimension = dimension();
        String summary = Json.str(args, "summary", "");
        int sent = 0;
        int batches = 0;
        int payloadBytes = 0;
        for (int start = 0; start < total; start += BATCH_BLOCKS) {
            int end = Math.min(total, start + BATCH_BLOCKS);
            JsonObject payload = new JsonObject();
            payload.addProperty("regionId", session.regionId);
            if (start == 0) {
                payload.add("bounds", Json.GSON.toJsonTree(session.bounds.toArray()));
                if (!summary.isBlank()) {
                    payload.addProperty("summary", summary);
                }
            }
            JsonArray blocks = new JsonArray();
            for (int i = start; i < end; i++) {
                VoxelWorld.Voxel voxel = changes.get(i);
                JsonArray one = new JsonArray();
                one.add(voxel.x());
                one.add(voxel.y());
                one.add(voxel.z());
                one.add(voxel.fullState());
                blocks.add(one);
            }
            payload.add("blocks", blocks);
            String json = Json.GSON.toJson(payload);
            payloadBytes += json.length();
            batches++;
            PacketDistributor.sendToServer(new ModPackets.C2SCommit(
                    held.taskId(), dimension, json, end >= total));
            sent += end - start;
        }
        session.lastSubmitted.clear();
        session.lastSubmitted.putAll(current);
        session.blocksAtLastSubmit = current.size();
        session.submitCount++;
        logLine(held.taskId(), "增量提交 " + sent + " 格（新增/修改 " + (total - removed)
                + "，删除 " + removed + "；" + batches + " 包，约 "
                + Math.max(1, payloadBytes / 1024) + " KB）"
                + (summary.isBlank() ? "" : "：" + summary));
        JsonObject out = new JsonObject();
        out.addProperty("submitted", sent);
        out.addProperty("changed", total - removed);
        out.addProperty("removed", removed);
        out.addProperty("localBlocks", current.size());
        out.addProperty("batches", batches);
        out.addProperty("payloadKb", Math.max(1, payloadBytes / 1024));
        out.addProperty("note", "只提交了相对上次提交的变化（删除以 minecraft:air 传播）；"
                + "本地 VoxelWorld 保留，服务端按 blocksPerTick 分批覆盖写入并归档");
        future.complete(out);
    }

    // ------------------------------------------------------------------
    // 手持物品与本地区块世界
    // ------------------------------------------------------------------

    private HeldItem held() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return null;
        }
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = mc.player.getItemInHand(hand);
            if (!(stack.getItem() instanceof ExternalBuildWandItem)) {
                continue;
            }
            CompoundTag tag = ItemData.read(stack);
            var p1 = ItemData.point(tag, ItemData.P1);
            var p2 = ItemData.point(tag, ItemData.P2);
            Bounds bounds = p1 != null && p2 != null ? Bounds.of(p1, p2) : null;
            return new HeldItem(ItemData.taskId(stack), bounds, dimension());
        }
        return null;
    }

    /** 从客户端已同步的世界里读某列的最高非空气方块，避免 Agent 猜地面高度。 */
    private static int sampleGroundY(int x, int z) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return Integer.MIN_VALUE;
        }
        int max = Math.min(319, mc.level.getMaxBuildHeight() - 1);
        int min = mc.level.getMinBuildHeight() + 1;
        for (int y = max; y >= min; y--) {
            if (!mc.level.getBlockState(new BlockPos(x, y, z)).isAir()) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    private static String dimension() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? "minecraft:overworld"
                : mc.level.dimension().location().toString();
    }

    private Path captureDir(String taskId) {
        Minecraft mc = Minecraft.getInstance();
        // 用完整 taskId 做目录：只取前 8 位理论上可能碰撞，会让两个任务共用照片目录
        return mc.gameDirectory.toPath().resolve("agentcraft-captures").resolve(taskId);
    }

    private static boolean sameBounds(int[] a, int[] b) {
        if (a == null || b == null || a.length < 6 || b.length < 6) {
            return false;
        }
        for (int i = 0; i < 6; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        return true;
    }

    private static String describe(int[] b) {
        if (b == null || b.length < 6) {
            return "—";
        }
        return "[" + b[0] + "," + b[1] + "," + b[2] + " → " + b[3] + "," + b[4] + "," + b[5] + "]";
    }

    /** 等服务端把新范围写回物品后回执；remainingTicks 用完就只回 pending。 */
    private static final class PendingBounds {
        final CompletableFuture<JsonObject> future;
        final int[] target;
        final String taskId;
        final String reason;
        int remainingTicks;

        PendingBounds(CompletableFuture<JsonObject> future, int[] target, String taskId,
                      String reason, int remainingTicks) {
            this.future = future;
            this.target = target;
            this.taskId = taskId;
            this.reason = reason;
            this.remainingTicks = remainingTicks;
        }
    }

    private static JsonObject error(String message) {
        JsonObject out = new JsonObject();
        out.addProperty("error", message == null ? "未知错误" : message);
        out.addProperty("isError", true);
        return out;
    }

    /** 一个任务当前聚焦区域的局部体素世界；切换区域时卸载，提交后保留以继续增量建造。 */
    private final class Session {
        final String taskId;
        final String regionId;
        final Bounds bounds;
        final int forbiddenSignature;
        final List<Bounds> forbiddenZones;
        final VoxelWorld world;
        final ToolExecutor executor;
        final List<String> captures = new ArrayList<>();
        /** 上次提交时的体素快照：只发增量，并把本地删掉的格子转成 air 发给服务端。 */
        final Map<Long, VoxelWorld.Voxel> lastSubmitted = new java.util.HashMap<>();
        int blocksAtLastSubmit;
        int submitCount;

        Session(String taskId, String regionId, Bounds bounds, ClientState state) {
            this.taskId = taskId;
            this.regionId = regionId;
            this.bounds = bounds.copy();
            this.forbiddenSignature = state == null ? 1 : ClientBuildHost.forbiddenSignature(state);
            List<Bounds> forbidden = new ArrayList<>();
            if (state != null) {
                for (ClientState.ForbiddenItem zone : state.safeForbiddenZones()) {
                    if (zone.bounds() != null) {
                        forbidden.add(zone.bounds());
                    }
                }
            }
            int minY = -64;
            int maxY = 319;
            if (Minecraft.getInstance().level != null) {
                minY = Minecraft.getInstance().level.getMinBuildHeight();
                maxY = Minecraft.getInstance().level.getMaxBuildHeight() - 1;
            }
            this.forbiddenZones = List.copyOf(forbidden);
            this.world = new VoxelWorld(bounds, minY, maxY, forbidden);
            this.executor = new ToolExecutor(taskId, bounds, world, captureDir(taskId + "/" + regionId), 0, true);
        }

        void collect(ToolExecutor.Outcome outcome) {
            for (var line : outcome.captures) {
                if (line != null && line.path != null && !line.path.isBlank()) {
                    captures.add(line.path);
                }
            }
        }
    }
}
