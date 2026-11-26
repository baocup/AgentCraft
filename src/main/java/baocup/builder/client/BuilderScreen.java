package baocup.builder.client;

import baocup.builder.net.ModPackets;
import baocup.builder.util.Bounds;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 外部建造手杖控制台：概览 / 区域 / 禁建区 / 日志。 */
public class BuilderScreen extends Screen {
    private enum Tab { OVERVIEW("概览"), REGIONS("区域"), FORBIDDEN("禁建区"), LOGS("日志");
        final String label; Tab(String s) { label = s; } }
    private static final int ROW = 46, LOG = 12;
    private static final String[] FL = {"X1", "Y1", "Z1", "X2", "Y2", "Z2"};
    public final String taskId;
    private ClientState state;
    private Tab tab = Tab.OVERVIEW;
    private final EditBox[] fields = new EditBox[6];
    private int rScroll, fScroll, lScroll;
    private boolean autoLog = true;
    private String delKind = "", delId = "", formMsg = "", previewMsg = "尚未生成局部预览", scanMsg = "";
    private long delUntil;
    private boolean loading;
    private ResourceLocation tex;
    private DynamicTexture dyn;
    private int pw, ph, px, py, pW, pH, cx, cy, cw, ch, ly, lh, fy, by, ty, vy, vh;

    public BuilderScreen(ClientState state) {
        super(Component.literal("外部建造手杖"));
        this.state = state;
        this.taskId = state == null ? "" : state.taskId;
    }

    public void applyState(ClientState s) {
        state = s;
        if (s == null) return;
        Bounds b = s.rootBounds();
        if (b != null) {
            int[] v = b.toArray();
            for (int i = 0; i < 6; i++) if (fields[i] != null && !fields[i].isFocused()) fields[i].setValue(Integer.toString(v[i]));
        }
        if (autoLog) lScroll = Math.max(0, s.safeLogs().size() * LOG - lh);
    }

    @Override protected void init() {
        super.init();
        layout();
        clearWidgets();
        int gap = 4, tw = (cw - gap * 3) / 4;
        for (int i = 0; i < Tab.values().length; i++) {
            Tab t = Tab.values()[i];
            addRenderableWidget(Button.builder(Component.literal((t == tab ? "● " : "") + t.label), b -> switchTab(t))
                    .bounds(cx + i * (tw + gap), py + 28, tw, 20).build());
        }
        int footY = py + pH - 26;
        addRenderableWidget(Button.builder(Component.literal("刷新"), b -> PacketDistributor.sendToServer(new ModPackets.C2SAction(taskId, "sync", "{}"))).bounds(cx, footY, 80, 20).build());
        addRenderableWidget(Button.builder(Component.literal("关闭"), b -> onClose()).bounds(cx + cw - 80, footY, 80, 20).build());
        if (tab == Tab.OVERVIEW) overviewWidgets();
        else if (tab == Tab.FORBIDDEN) addRenderableWidget(Button.builder(Component.literal("两点新增禁建区"), b -> mode("FORBIDDEN_ZONE")).bounds(cx + cw - 120, cy - 2, 120, 18).build());
        else if (tab == Tab.LOGS) {
            addRenderableWidget(Button.builder(Component.literal(autoLog ? "自动滚动：开" : "自动滚动：关"), b -> {
                autoLog = !autoLog; b.setMessage(Component.literal(autoLog ? "自动滚动：开" : "自动滚动：关"));
                if (autoLog && state != null) lScroll = Math.max(0, state.safeLogs().size() * LOG - lh);
            }).bounds(cx + cw - 180, cy - 2, 90, 18).build());
            addRenderableWidget(Button.builder(Component.literal("清空日志"), b -> PacketDistributor.sendToServer(new ModPackets.C2SAction(taskId, "clear_logs", "{}"))).bounds(cx + cw - 84, cy - 2, 84, 18).build());
        }
    }

    private void layout() {
        pW = Math.min(width - 16, 760); pH = Math.min(height - 16, 460);
        px = (width - pW) / 2; py = (height - pH) / 2;
        cx = px + 12; cy = py + 56; cw = pW - 24; ch = pH - 92;
        ly = cy + 24; lh = Math.max(40, ch - 24);
        fy = cy + 84; by = fy + 24; ty = by + 26; vy = ty + 24; vh = Math.max(40, cy + ch - vy - 16);
    }

    private void overviewWidgets() {
        int fw = (cw - 30) / 6;
        Bounds root = state == null ? null : state.rootBounds();
        int[] v = root == null ? new int[6] : root.toArray();
        for (int i = 0; i < 6; i++) {
            EditBox e = new EditBox(font, cx + i * (fw + 6), fy, fw, 18, Component.literal(FL[i]));
            e.setMaxLength(12); e.setValue(Integer.toString(v[i])); fields[i] = e; addRenderableWidget(e);
        }
        int bw = (cw - 18) / 4;
        addRenderableWidget(Button.builder(Component.literal("应用坐标"), b -> applyBounds()).bounds(cx, by, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("选根区"), b -> mode("ROOT")).bounds(cx + bw + 6, by, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("选子区"), b -> mode("CHILD_REGION")).bounds(cx + 2 * (bw + 6), by, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("选禁建区"), b -> mode("FORBIDDEN_ZONE")).bounds(cx + 3 * (bw + 6), by, bw, 20).build());
        int tw = (cw - 12) / 3;
        addRenderableWidget(Button.builder(Component.literal("预览当前区域"), b -> preview()).bounds(cx, ty, tw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("检测当前区域"), b -> scan(false)).bounds(cx + tw + 6, ty, tw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("检测根区首页"), b -> scan(true)).bounds(cx + 2 * (tw + 6), ty, tw, 20).build());
    }

    private void switchTab(Tab t) { if (tab != t) { tab = t; delKind = ""; delId = ""; init(); } }
    private void mode(String m) { mode(m, null); }
    private void mode(String m, String parentId) {
        JsonObject d = new JsonObject(); d.addProperty("mode", m);
        if (parentId != null) d.addProperty("parentId", parentId);
        send("selection_mode", d); onClose();
    }
    private void send(String a, JsonObject d) { PacketDistributor.sendToServer(new ModPackets.C2SAction(taskId, a, d.toString())); }
    private void applyBounds() {
        JsonArray arr = new JsonArray();
        try { for (EditBox e : fields) arr.add(Integer.parseInt(e.getValue().trim())); }
        catch (Exception ex) { formMsg = "坐标必须是 6 个整数"; return; }
        JsonObject d = new JsonObject(); d.add("bounds", arr); send("bounds", d); formMsg = "已发送根区坐标更新";
    }

    private void preview() {
        loading = true; previewMsg = "正在生成局部预览…";
        ClientBuildHost.INSTANCE.previewActiveRegion("ortho", r -> Minecraft.getInstance().execute(() -> {
            loading = false;
            if (r.has("error")) { previewMsg = r.get("error").getAsString(); return; }
            JsonArray p = r.has("photoPaths") ? r.getAsJsonArray("photoPaths") : null;
            if (p == null || p.isEmpty()) { previewMsg = "预览已生成，但没有返回图片路径"; return; }
            loadPreview(p.get(0).getAsString());
        }));
    }

    private void loadPreview(String path) {
        closePreview();
        try (InputStream in = Files.newInputStream(Path.of(path))) {
            NativeImage img = NativeImage.read(in);
            pw = img.getWidth(); ph = img.getHeight();
            dyn = new DynamicTexture(img);
            tex = ResourceLocation.fromNamespaceAndPath("builder", "preview/" + san(taskId) + "/" + san(state == null ? "root" : state.activeRegionId));
            Minecraft.getInstance().getTextureManager().register(tex, dyn);
            previewMsg = "预览：" + Path.of(path).getFileName();
        } catch (Exception e) { previewMsg = "预览加载失败：" + e.getMessage(); }
    }

    private void closePreview() {
        if (dyn != null) { dyn.close(); dyn = null; }
        if (tex != null) { Minecraft.getInstance().getTextureManager().release(tex); tex = null; }
    }

    private static String san(String s) {
        String l = s == null ? "unknown" : s.toLowerCase(Locale.ROOT);
        StringBuilder o = new StringBuilder();
        for (char c : l.toCharArray()) o.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '/' || c == '.' || c == '_' || c == '-' ? c : '_');
        return o.isEmpty() ? "unknown" : o.toString();
    }

    private void scan(boolean root) {
        scanMsg = root ? "正在检测根区首页…" : "正在检测当前区域…";
        ClientBuildHost.INSTANCE.scanActiveRegion(root).whenComplete((r, e) -> Minecraft.getInstance().execute(() -> {
            if (e != null) scanMsg = "检测失败：" + e.getMessage();
            else if (r.has("error")) scanMsg = r.get("error").getAsString();
            else scanMsg = "返回 " + iv(r, "returned") + " / 扫描 " + iv(r, "scanned") + " / 已加载 " + iv(r, "loaded")
                    + (r.has("hasMore") && r.get("hasMore").getAsBoolean() ? " · 还有更多" : " · 首页完成");
        }));
    }
    private static int iv(JsonObject o, String k) { return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsInt() : 0; }

    @Override public void render(GuiGraphics g, int mx, int my, float pt) {
        // super.render 内部会调 renderBackground（含模糊后处理）再画控件；
        // 这里不能再额外调一次 renderBackground，否则模糊会把先画的文字盖住。
        super.render(g, mx, my, pt);
        g.drawString(font, "外部建造手杖", px + 12, py + 10, 0xFFFFFF);
        String right = shortId() + " · v" + (state == null ? 0 : state.version);
        g.drawString(font, right, px + pW - 12 - font.width(right), py + 10, 0x9AA7B4);
        switch (tab) {
            case OVERVIEW -> overview(g, mx, my);
            case REGIONS -> regions(g, mx, my);
            case FORBIDDEN -> forbidden(g, mx, my);
            case LOGS -> logs(g);
        }
    }

    @Override public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
        super.renderBackground(g, mx, my, pt);
        g.fill(px, py, px + pW, py + pH, 0xF0101418);
        g.fill(px, py, px + pW, py + 1, 0xFF3A4A5A);
        g.fill(px, py + pH - 1, px + pW, py + pH, 0xFF3A4A5A);
        g.fill(px, py, px + 1, py + pH, 0xFF3A4A5A);
        g.fill(px + pW - 1, py, px + pW, py + pH, 0xFF3A4A5A);
    }

    private void overview(GuiGraphics g, int mx, int my) {
        ClientState.RegionItem a = state == null ? null : state.activeRegion();
        Bounds ab = state == null ? null : state.activeBounds();
        g.fill(cx, cy, cx + cw, cy + 54, 0x20182028);
        if (a == null) {
            g.drawString(font, "尚未选择建造区", cx + 8, cy + 8, 0xFFAA00);
            g.drawString(font, fit("默认不选点；点「选根区/选子区/选禁建区」武装后左键两点，完成即自动退出。", cw - 16), cx + 8, cy + 24, 0xB8C4D0);
        } else {
            g.drawString(font, fit(a.name, cw - 160), cx + 8, cy + 6, 0xFFFFFF);
            g.drawString(font, status(a.status) + " · 进度 " + a.progress + "% · 已放置 " + a.blocksPlaced, cx + 8, cy + 20, 0xB8C4D0);
            g.drawString(font, ab == null ? "无边界" : fit(ab.display(), cw - 16), cx + 8, cy + 34, 0x8FA0B0);
            g.drawString(font, fit("聚焦：" + a.id, 140), cx + cw - 148, cy + 6, 0x6FC3FF);
        }
        g.drawString(font, "根建造区坐标（无体积上限）", cx, cy + 62, 0xD0D8E0);
        int fw = (cw - 30) / 6;
        for (int i = 0; i < 6; i++) g.drawString(font, FL[i], cx + i * (fw + 6), fy - 10, 0x8FA0B0);
        if (!formMsg.isEmpty()) g.drawString(font, fit(formMsg, cw), cx, by + 22, 0xFFD080);
        g.fill(cx, vy, cx + cw, vy + vh, 0x20101820);
        if (tex != null && pw > 0 && ph > 0) {
            float s = Math.min((cw - 8) / (float) pw, (vh - 8) / (float) ph);
            int dw = Math.max(1, (int) (pw * s)), dh = Math.max(1, (int) (ph * s));
            g.blit(tex, cx + (cw - dw) / 2, vy + (vh - dh) / 2, 0F, 0F, dw, dh, pw, ph);
        } else g.drawString(font, fit(loading ? "正在渲染…" : "局部预览只渲染当前聚焦区域。", cw - 16), cx + 8, vy + vh / 2 - 4, 0x8FA0B0);
        g.drawString(font, fit(previewMsg, cw), cx, vy + vh + 4, 0x9AA7B4);
        if (!scanMsg.isEmpty()) g.drawString(font, fit(scanMsg, cw), cx, vy + vh + 16, 0x9AA7B4);
    }

    private void regions(GuiGraphics g, int mx, int my) {
        List<Row> rows = flatten();
        g.drawString(font, "全部区域（含层级） · " + rows.size(), cx, cy, 0xD0D8E0);
        g.enableScissor(cx, ly, cx + cw, ly + lh);
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            int y = ly + i * ROW - rScroll;
            if (y + ROW < ly || y > ly + lh) continue;
            boolean active = state != null && r.item.id.equals(state.activeRegionId);
            g.fill(cx, y, cx + cw, y + ROW - 2, active ? 0x30204060 : (i % 2 == 0 ? 0x18182028 : 0x18141C24));
            int tx = cx + 6 + r.depth * 10;
            int textW = cw - 214 - r.depth * 10;
            g.drawString(font, fit(r.item.name, textW), tx, y + 5, 0xFFFFFF);
            g.drawString(font, fit(status(r.item.status) + " " + r.item.progress + "% · " + r.item.blocksPlaced + " 块", textW), tx, y + 17, 0xB8C4D0);
            Bounds b = r.item.bounds();
            g.drawString(font, fit(b == null ? "无边界" : b.display(), textW), tx, y + 29, 0x8FA0B0);
            action(g, cx + cw - 184, y + 6, 40, 16, "子区", mx, my, 0xFF2A6A8A);
            action(g, cx + cw - 138, y + 6, 40, 16, "聚焦", mx, my, 0xFF2A5A8A);
            action(g, cx + cw - 92, y + 6, 40, 16, "传送", mx, my, 0xFF2A7A4A);
            boolean root = "root".equals(r.item.id);
            action(g, cx + cw - 46, y + 6, 40, 16, root ? "根区" : delLabel("region", r.item.id), mx, my, root ? 0xFF3A3A3A : 0xFF7A3A3A);
        }
        g.disableScissor();
        if (rows.isEmpty()) g.drawString(font, "暂无区域；先选择根建造区。", cx + 8, ly + 8, 0x8FA0B0);
    }

    private void forbidden(GuiGraphics g, int mx, int my) {
        List<ClientState.ForbiddenItem> list = state == null ? List.of() : state.safeForbiddenZones();
        g.drawString(font, "禁建区 · " + list.size() + "（可超出根区，写入前强制拦截）", cx, cy, 0xD0D8E0);
        g.enableScissor(cx, ly, cx + cw, ly + lh);
        for (int i = 0; i < list.size(); i++) {
            ClientState.ForbiddenItem z = list.get(i);
            int y = ly + i * ROW - fScroll;
            if (y + ROW < ly || y > ly + lh) continue;
            g.fill(cx, y, cx + cw, y + ROW - 2, i % 2 == 0 ? 0x18281818 : 0x18241414);
            g.drawString(font, fit(z.name, cw - 110), cx + 6, y + 5, 0xFF8080);
            Bounds b = z.bounds();
            g.drawString(font, fit(b == null ? "无边界" : b.display(), cw - 110), cx + 6, y + 17, 0xB8C4D0);
            g.drawString(font, fit(z.description == null || z.description.isEmpty() ? "服务端放置时拦截" : z.description, cw - 110), cx + 6, y + 29, 0x8FA0B0);
            action(g, cx + cw - 92, y + 6, 40, 16, "传送", mx, my, 0xFF2A7A4A);
            action(g, cx + cw - 46, y + 6, 40, 16, delLabel("forbidden", z.id), mx, my, 0xFF7A3A3A);
        }
        g.disableScissor();
        if (list.isEmpty()) g.drawString(font, "暂无禁建区；点击右上角「两点新增禁建区」。", cx + 8, ly + 8, 0x8FA0B0);
    }

    private void logs(GuiGraphics g) {
        List<ClientState.LogItem> list = state == null ? List.of() : state.safeLogs();
        g.drawString(font, "运行日志 · " + list.size(), cx, cy, 0xD0D8E0);
        g.enableScissor(cx, ly, cx + cw, ly + lh);
        for (int i = 0; i < list.size(); i++) {
            ClientState.LogItem l = list.get(i);
            int y = ly + i * LOG - lScroll;
            if (y + LOG < ly || y > ly + lh) continue;
            int color = "ERROR".equals(l.level) ? 0xFF6060 : "WARN".equals(l.level) ? 0xFFC060 : 0xB8C4D0;
            g.drawString(font, fit("[" + l.stage + "] " + l.text, cw - 8), cx + 4, y, color);
        }
        g.disableScissor();
        if (list.isEmpty()) g.drawString(font, "暂无日志。", cx + 8, ly + 8, 0x8FA0B0);
    }

    private void action(GuiGraphics g, int x, int y, int w, int h, String label, int mx, int my, int color) {
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + h;
        g.fill(x, y, x + w, y + h, hover ? color | 0x202020 : color);
        g.drawString(font, label, x + (w - font.width(label)) / 2, y + 4, 0xFFFFFF);
    }

    private String delLabel(String kind, String id) {
        return kind.equals(delKind) && id.equals(delId) && System.currentTimeMillis() < delUntil ? "确认?" : "删除";
    }

    private List<Row> flatten() {
        List<Row> out = new ArrayList<>();
        if (state == null) return out;
        List<ClientState.RegionItem> all = state.safeRegions();
        Set<String> seen = new HashSet<>();
        for (ClientState.RegionItem r : all) if (r.parentId == null || r.parentId.isEmpty() || find(all, r.parentId) == null) add(out, all, r, 0, seen);
        for (ClientState.RegionItem r : all) add(out, all, r, 0, seen);
        return out;
    }
    private void add(List<Row> out, List<ClientState.RegionItem> all, ClientState.RegionItem r, int depth, Set<String> seen) {
        if (r == null || !seen.add(r.id)) return;
        out.add(new Row(r, depth));
        for (ClientState.RegionItem c : all) if (r.id.equals(c.parentId)) add(out, all, c, depth + 1, seen);
    }
    private static ClientState.RegionItem find(List<ClientState.RegionItem> all, String id) {
        for (ClientState.RegionItem r : all) if (r.id.equals(id)) return r;
        return null;
    }
    private static final class Row { final ClientState.RegionItem item; final int depth; Row(ClientState.RegionItem i, int d) { item = i; depth = d; } }

    @Override public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0 && my >= ly && my < ly + lh && mx >= cx && mx < cx + cw) {
            if (tab == Tab.REGIONS) {
                List<Row> rows = flatten();
                int i = (int) ((my - ly + rScroll) / ROW);
                if (i >= 0 && i < rows.size()) {
                    Row r = rows.get(i);
                    int y = ly + i * ROW - rScroll;
                    if (hit(mx, my, cx + cw - 184, y + 6, 40, 16)) { mode("CHILD_REGION", r.item.id); return true; }
                    if (hit(mx, my, cx + cw - 138, y + 6, 40, 16)) { JsonObject d = new JsonObject(); d.addProperty("id", r.item.id); send("focus_region", d); return true; }
                    if (hit(mx, my, cx + cw - 92, y + 6, 40, 16)) { teleport("region", r.item.id); return true; }
                    if (!"root".equals(r.item.id) && hit(mx, my, cx + cw - 46, y + 6, 40, 16)) { delete("region", r.item.id); return true; }
                    JsonObject d = new JsonObject(); d.addProperty("id", r.item.id); send("focus_region", d); return true;
                }
            } else if (tab == Tab.FORBIDDEN && state != null) {
                List<ClientState.ForbiddenItem> list = state.safeForbiddenZones();
                int i = (int) ((my - ly + fScroll) / ROW);
                if (i >= 0 && i < list.size()) {
                    ClientState.ForbiddenItem z = list.get(i);
                    int y = ly + i * ROW - fScroll;
                    if (hit(mx, my, cx + cw - 92, y + 6, 40, 16)) { teleport("forbidden", z.id); return true; }
                    if (hit(mx, my, cx + cw - 46, y + 6, 40, 16)) { delete("forbidden", z.id); return true; }
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }
    private static boolean hit(double mx, double my, int x, int y, int w, int h) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    private void teleport(String kind, String id) { JsonObject d = new JsonObject(); d.addProperty("kind", kind); d.addProperty("id", id); send("teleport", d); onClose(); }
    private void delete(String kind, String id) {
        if (kind.equals(delKind) && id.equals(delId) && System.currentTimeMillis() < delUntil) {
            JsonObject d = new JsonObject(); d.addProperty("id", id);
            send("region".equals(kind) ? "delete_region" : "delete_forbidden", d);
            delKind = ""; delId = "";
        } else { delKind = kind; delId = id; delUntil = System.currentTimeMillis() + 5000; }
    }

    @Override public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        int max;
        if (tab == Tab.REGIONS) { max = Math.max(0, flatten().size() * ROW - lh); rScroll = clamp(rScroll - (int) (sy * 24), max); return true; }
        if (tab == Tab.FORBIDDEN) { max = Math.max(0, (state == null ? 0 : state.safeForbiddenZones().size()) * ROW - lh); fScroll = clamp(fScroll - (int) (sy * 24), max); return true; }
        if (tab == Tab.LOGS) { max = Math.max(0, (state == null ? 0 : state.safeLogs().size()) * LOG - lh); lScroll = clamp(lScroll - (int) (sy * 24), max); autoLog = lScroll >= max; return true; }
        return super.mouseScrolled(mx, my, sx, sy);
    }
    private static int clamp(int v, int max) { return Math.max(0, Math.min(v, max)); }

    private String shortId() { return taskId.length() >= 8 ? taskId.substring(0, 8) : taskId; }
    private static String status(String s) { return "DONE".equals(s) ? "已完成" : "IN_PROGRESS".equals(s) ? "建造中" : "待建造"; }
    private String fit(String s, int w) {
        if (s == null) return "";
        if (font.width(s) <= w) return s;
        while (s.length() > 1 && font.width(s + "..") > w) s = s.substring(0, s.length() - 1);
        return s + "..";
    }

    @Override public void onClose() {
        PacketDistributor.sendToServer(new ModPackets.C2SAction(taskId, "close", "{}"));
        super.onClose();
    }
    @Override public void removed() { closePreview(); super.removed(); }
    @Override public boolean isPauseScreen() { return false; }
}
