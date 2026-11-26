---
name: minecraft-builder
description: 通过 MCP 连接正在运行的 Minecraft 客户端，在游戏内建造建筑。当用户要求"在 Minecraft 里建/搭/盖某个东西""用外部建造手杖建个房子""修改游戏里的建筑"或提到 minecraft-builder 时使用。
---

# Minecraft Builder 技能（外部 Agent / MCP）

把本文件夹导入任意本地 Agent（WorkBuddy、DeepSeekHarness、Codex、Claude Code、Cursor，或任何支持 MCP 的客户端），通过 **MCP（Model Context Protocol）** 连接正在运行的 Minecraft，直接在游戏内建造。

**只使用外部 Agent。** 游戏内不再有内部 LLM Agent 物品；所有决策、几何与坐标都由你输出。游戏只负责几何编译、坐标校验、禁建区拦截、逐 tick 写入世界与版本归档。没有 API Key、没有 token 费用、没有模型超时。

## 连接

> 端口开在**玩家客户端**，不是服务器。几何编译、`VoxelWorld` 与拍照渲染都在客户端完成，只有最终方块通过 `C2SCommit` 分批发给服务端写入世界。每个客户端实例有独立端口与独立体素会话。

```
POST http://127.0.0.1:8765/mcp        JSON-RPC 2.0，streamable HTTP
```

- 协议版本 `2025-06-18`；实现 `initialize` / `ping` / `tools/list` / `tools/call`
- 若配置了 `mcpToken`，请求头需带 `Authorization: Bearer <token>`
- 启动日志出现 `[建造] 本地 MCP 已监听 http://127.0.0.1:8765/mcp` 即就绪
- 端口 / 地址 / token 在 `config/builder-common.toml` 的 `[mcp]` 段（开发环境是 `run/config/`）
- 同机多开客户端时，每个实例必须配不同 `mcpPort`

**Claude Code**（项目根 `.mcp.json`）：

```json
{"mcpServers":{"minecraft-builder":{"type":"http","url":"http://127.0.0.1:8765/mcp"}}}
```

**Cursor**（`.cursor/mcp.json`）：与上面相同的 `mcpServers` 结构。

**Codex**（`~/.codex/config.toml`）：

```toml
[mcp_servers.minecraft_builder]
url = "http://127.0.0.1:8765/mcp"
```

## 核心模型：根区、细分区、禁建区

- **根建造区**：玩家手持手杖**默认不选点**（左键只挥手）；需先在 GUI 点「选根区/选子区/选禁建区」武装，再左键两点，第二点落下后**自动退出选点**。也可 GUI 输入坐标，或你调 `request_bounds` 设定。**没有体积上限**，可以覆盖超大工程。
- **细分区**：根区内可递归创建子区域，每个区域有 `id / name / description / parentId / status / progress / bounds`。用 `create_region` 创建、`delete_region` 删除（含后代）、`update_region` 改边界/状态、`focus_region` 聚焦。**建模与恢复只加载当前聚焦区域**，不会把整个根工程灌进客户端。
- **区域与地形完全由你自主管理**：`create_region / delete_region / update_region / focus_region` 与 `read_world` 都不需要玩家操作 GUI，建造过程中随时可调。`read_world` 传 `regionId` 可检测**任意已存在区域**的真实地形，不必先聚焦它；只有 `place_blocks / draw_shape / submit_build` 这类建模动作才受当前聚焦区约束。
- **禁建区**：独立于建造区，可超出根区。用 `create_forbidden` 创建，`delete_forbidden` 删除。建模时过滤，服务端实际放置时再次强制拦截。
- 玩家手持手杖时会看到实时线框与半透明面：根区蓝色、聚焦细分区黄色、禁建区红色。物品 UI 可管理所有区域/禁建区并一键传送。
- **玩家 GUI 选点是「武装式」**：手持默认 `NONE`（左键只挥手、不落点）。在概览点「选根区/选子区/选禁建区」，或在区域行点「子区」（以该行为父级，可嵌套）武装后，左键两点；第二点落下**无论成败都自动回到 `NONE` 并清除待选点**。删除区域也会清除手持待选点，避免旧坐标污染下次选区。这些是玩家侧操作；你（Agent）始终用 `create_region / request_bounds` 等工具设定范围，不需要玩家选点。

## 铁律

1. **所有工具都没有 `taskId` 参数。** 工具永远作用在玩家当前手持的外部建造手杖绑定的任务上。玩家放下手杖或换手持物，调用会报错。需要换任务就让玩家换手杖。
2. **坐标是绝对世界整数坐标**，与 `get_context` / `read_state` / `read_world` 返回一致。不要用相对坐标，也不要按“建筑中心”猜。
3. **先聚焦，再建模。** 超大工程必须拆成细分区；每次只在一个区域里建模、校验、提交。跨区混写会被服务端拒绝。
4. **本地世界只装当前区域的方块。** 客户端不会自动把世界里已有建筑读进来；需要已有成果时先 `resume`（按区域分页恢复）。`resume` 优先读 `submit_build` 归档的方块图，无归档时回退扫描真实世界。
5. **建模只在客户端，`submit_build` 才是放进世界。** 每轮工具回执带 `localOnly:true`。提交只发增量变化，本地会话保留，可继续加建。
6. **地面高度不要猜。** 用 `get_context` 的 `groundY / groundSamples`，或 `read_world` 分页检测真实地形。
7. **提交前必须 `validate_build`。** 发现越界、禁建区、包围盒偏差、对称错误、连通分量异常时先修正，不要直接提交。

## 稳定生成方案（必须遵守）

目标：避免表达式/Python 辅助生成仍出现的错位、方向、形状错误。

1. **规划区域树**
   - `get_context` 确认根区与当前聚焦区。
   - `list_regions` 查看已有区域；按工程结构 `create_region` 拆分（例如地基、主体、屋顶、装饰、景观）。
   - 每个区域写清 `name / description`，边界完整位于父区域内。
   - 规划变化时随时 `delete_region` 删掉过时子区（连同后代）、`update_region` 调整边界，再重建；不必让玩家进 GUI 操作。
2. **检测地形**
   - `read_world` 默认读当前聚焦区域；`root=true` 读根区；`regionId` 读任意已存在区域（无需聚焦）；也可传 `region` 局部检测。
   - 动工前、每次切区后、以及怀疑地形与假设不符时，都可以随时再检测一次。
   - 单次 `scanBudget` 只是扫描预算，不是总量上限。`hasMore=true` 时把 `nextCursor` 原样带入下一次调用，直到完成。
   - 贴地建筑先确认 `groundY` 与已有方块，再定 y 坐标。
3. **局部建模**
   - `focus_region` 切到目标区域；必要时 `resume` 恢复该区域已提交成果。
   - 优先用 `draw_shape` 的 `vars / clone / mirror / shell` 表达可复用几何，减少手抄坐标。
   - 大形体分多次调用；单次写入/求值上限仍由配置控制，超出会计入 `rejected`。
   - 方向性方块（楼梯、原木、活塞等）必须显式写方块状态；不要只写方块 id 后猜朝向。
4. **确定性校验**
   - `validate_build` 传 `expectedBounds`、`symmetry`（NONE/X/Z/BOTH）、必要时 `maxComponents`。
   - 检查回执的 `errors / warnings / violationSamples / symmetryErrors / components`。
   - 有错误先 `remove_blocks / place_blocks / draw_shape` 修正，再重新校验。
5. **局部预览**
   - `capture` 渲染当前区域 PNG；回执给 `captureDir / photoPaths` 绝对路径。
   - 能读本地文件就打开 PNG 自检；不能读就用 `read_state` 核对体素。
   - 物品 UI 的“预览当前区域”也走同一局部渲染链路，不加载整个根工程。
   - `submit_build` 完成即归档完整方块图；UI 预览与 `resume` 在该区域无归档时回退扫描真实世界，因此**已经造好的区域**也能预览/恢复。
6. **分段提交与进度**
   - 校验通过后立刻 `submit_build`，不要攒到最后。
   - `get_build_status` 确认 `done / written / pendingWrites`。
   - `update_region` 更新 `status / progress`；完成一个区域再聚焦下一个。
7. **断点续建**
   - 客户端重启后本地体素丢失。重新手持手杖，`focus_region` 到目标区域，再 `resume` 分页恢复，然后继续加建。

## 工具

| 工具 | 说明 |
| --- | --- |
| `get_context` | 入口：任务、根区、当前聚焦区、地表采样、本地累计与待提交增量 |
| `request_bounds` | 设定根建造范围，无体积上限；有未提交增量时会拒绝 |
| `list_regions` | 列出根区与所有递归细分区，返回 `activeRegionId` |
| `create_region` | 在父区域内创建细分区；`bounds` 必须完整位于父区域 |
| `update_region` | 更新名称、描述、状态、进度；也可调整非根区域边界 |
| `focus_region` | 聚焦区域并建立独立局部体素会话 |
| `delete_region` | 删除非根区域及后代；不删除已放入世界的方块 |
| `list_forbidden_zones` | 列出所有禁建区 |
| `create_forbidden` | 创建禁建区；可超出根区 |
| `delete_forbidden` | 按 id 删除禁建区 |
| `validate_build` | 提交前确定性校验：区域/禁建区、预期包围盒、镜像对称、连通分量 |
| `read_state` | 读本地真实方块；`detail=summary` 带全量 layers |
| `read_world` | 分页检测服务端真实地形；支持 `regionId / root / region / cursor / limit / scanBudget` |
| `get_build_status` | 服务端归档版本、状态、实际写入与队列 |
| `resume` | 按区域分页恢复本地 VoxelWorld；优先读提交归档，无归档时回退扫描真实世界；支持 `regionId / offset / limit` |
| `list_versions` | 列出可回滚版本 |
| `revert` | 回滚到指定版本 |
| `list_materials` | 方块检索与物理/生物群系着色标注 |
| `place_blocks` | 放单个/一批方块；写 `minecraft:air` 即删除 |
| `draw_shape` | 区域谓词/内置形状；支持 `vars / clone / mirror / shell` |
| `remove_blocks` | 坐标列表、区域谓词或按类型删除 |
| `capture` | 离屏渲染 PNG；回执给绝对路径 |
| `submit_build` | 只提交相对上次的变化，可选 `summary` |

`tools/list` 返回权威参数 schema，以它为准。所有工具回执都是扁平 JSON，没有 `{"result": ...}` 包装。

## read_world 分页

```json
{"name":"read_world","arguments":{"root":true,"limit":4096,"scanBudget":32768}}
```

回执包含 `blocks / returned / scanned / loaded / truncated / hasMore / nextCursor / region / volume`。`hasMore=true` 时：

```json
{"name":"read_world","arguments":{"root":true,"cursor":[x,y,z],"limit":4096,"scanBudget":32768}}
```

不要试图一次读完超大根区；按游标推进，或先 `create_region` 缩小检测范围。

## validate_build

```json
{"name":"validate_build","arguments":{
  "expectedBounds":[x1,y1,z1,x2,y2,z2],
  "symmetry":"BOTH",
  "maxComponents":1
}}
```

关注：
- `errors`：越界、禁建区、包围盒不符、对称错误等，必须修正。
- `warnings`：悬空、孤立块、方向属性异常等，按设计判断。
- `violationSamples`：前若干问题坐标，直接用于定位修正。
- `components / largestComponent`：6 邻接连通分量；主体建筑通常应为 1。

## 形状语法

`draw_shape` 的 `where` 是任意数学谓词，逐体素求值，为真的格子被填充。

```text
变量  : x y z, cx cy cz, rx ry rz, x0 y0 z0 x1 y1 z1, t, i, n, pi, e
运算符: + - * / % ^, == != > < >= <=, && || !, 括号, 逗号
函数  : abs min max floor ceil round sqrt pow sin cos tan atan2 hypot
        clamp step smoothstep mod sign lerp fract dist dist2 length mix
        grid(v,n) hash3(x,y,z) rand(x,y,z) noise3(x,y,z)
```

- `^` 是幂运算，不是异或。
- `!` 是逻辑取反；`&& || !` 组合可做并集/交集/差集。
- `grid(v,n)` 返回 v 到最近 n 倍数的距离，适合柱网/窗格。
- `hash3 / rand` 是确定性伪随机；`noise3` 是平滑 value noise。

复用示例：

```json
{"region":[0,64,0,15,80,15],"block":"minecraft:stone_bricks",
 "vars":{"H":"-52+grid(x,9)*3"},
 "where":"y<=H && y>=H-4",
 "clone":{"offset":[20,0,0],"count":4},
 "mirror":{"axis":"z","at":7.5}}
```

`shell:true` 只留外表面；`mode` 可选 `set/add/erase/replace`；也可用 `shape + params` 走内置快捷形状。

## 拍照局限

`capture` 在 MCP 下只返回文本，不把图片字节发给你。回执给 `photos / cameras / localReport / captureDir / photoPaths`。照片落盘在：

```
<客户端游戏目录>/builder-captures/<taskId>/<regionId>/...
```

能读本地文件就打开 PNG；不能读就用 `read_state` 核对体素。相机给绝对坐标，`lookAt` 最直观；`view:"plan"` 或 `orthographic:true` 适合大范围俯视。照片只渲染当前区域方块，背景地面不代表真实地形。

## 分段提交

每完成一个能独立成立的建造部分，就立刻 `submit_build`：

- 地基 → 校验 → 提交 → 墙体 → 校验 → 提交 → 屋顶 → 校验 → 提交 → 装饰。
- 服务端覆盖写入，分多次提交不会破坏已有成果。
- 攒着不提交会在上下文超限/中断时丢失本地体素，世界一格都没有。
- 提交后本地会话保留，可增量续建；重复提交同一状态返回 `submitted:0`。
- 想彻底重来请换一根手杖（新 taskId）。

## 会“自己消失”的方块

服务端写入已处理：
- 树叶自动 `persistent=true`。
- 重力方块从下往上写入，支撑先到位；设计上真悬空的仍会被原版规则拉下。
- 需要支撑的方块写入不触发邻居更新，但玩家之后手动更新仍可能掉落。
- 随机刻变化方块归档前校验并补写。

别用悬空重力方块做设计；需要悬空装饰时选不受影响的方块。

## 排错

| 现象 | 原因与处理 |
| --- | --- |
| 连不上 | 游戏没开、`mcpEnabled=false`、端口被占；看日志，冲突改 `mcpPort` |
| 401 | 配了 `mcpToken` 但没带 Bearer |
| 请手持外部建造手杖 | 玩家没拿手杖 / 换手了 |
| 物品还没有选区 | GUI 点「选根区」武装后左键两点 / GUI 输入坐标 / `request_bounds` |
| 区域外拦截 | 建模超出当前聚焦区；`focus_region` 或调整区域边界 |
| 禁建区拦截 | 坐标落入禁建区；修改设计或 `delete_forbidden` |
| `rejected` 很大 | 越界、非法 id、禁建区；看 `rejectionSamples` |
| `read_state` 看不到已有建筑 | 先 `resume` 恢复当前区域 |
| UI 预览提示「暂无可预览方块」 | 该区域既无本地体素也无归档、且世界扫描为空；先 `resume`，或确认区域边界正确、所在区块已加载 |
| 工具超时 | 客户端卡住或不在存档里；检查 `mcpToolTimeoutSeconds` |
