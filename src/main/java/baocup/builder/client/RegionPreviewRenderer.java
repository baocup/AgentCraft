package baocup.builder.client;

import baocup.builder.item.ExternalBuildWandItem;
import baocup.builder.item.ItemData;
import baocup.builder.util.Bounds;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 世界内选区预览：根区蓝色、禁建区红色、Agent 细分区黄色。 */
public final class RegionPreviewRenderer {
    private static final Map<String, ClientState> STATES = new ConcurrentHashMap<>();

    public static void onState(ClientState state) {
        if (state != null && state.taskId != null && !state.taskId.isBlank()) {
            STATES.put(state.taskId, state);
        }
    }

    public static void clear() {
        STATES.clear();
    }

    @SubscribeEvent
    public void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.options.hideGui) {
            return;
        }
        ItemStack stack = mc.player.getMainHandItem();
        if (!(stack.getItem() instanceof ExternalBuildWandItem)) {
            return;
        }
        ItemData.Data item = ItemData.get(stack);
        if (item.taskId == null || item.taskId.isEmpty()) {
            return;
        }
        ClientState state = STATES.get(item.taskId);
        if (state == null && item.p1 != null) {
            state = new ClientState();
            state.taskId = item.taskId;
        }
        if (state == null) {
            return;
        }

        PoseStack poses = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();
        poses.pushPose();
        poses.translate(-camera.x, -camera.y, -camera.z);
        Matrix4f matrix = poses.last().pose();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();

        java.util.List<Box> boxes = new java.util.ArrayList<>();
        if (state.hasBounds) {
            boxes.add(new Box(state.rootBounds(), 0.18f, 0.52f, 1.0f));
        }
        for (ClientState.RegionItem region : state.safeRegions()) {
            if (!"root".equals(region.id) && region.bounds() != null) {
                boolean active = region.id.equals(state.activeRegionId);
                boxes.add(new Box(region.bounds(), 1.0f, active ? 0.92f : 0.72f, 0.12f));
            }
        }
        for (ClientState.ForbiddenItem zone : state.safeForbiddenZones()) {
            if (zone.bounds() != null) {
                boxes.add(new Box(zone.bounds(), 1.0f, 0.16f, 0.12f));
            }
        }
        SelectionInput.PendingSelection pending = SelectionInput.pending(item.taskId);
        if (pending != null && pending.p1 != null) {
            BlockPos target = hoveredBlock(mc);
            if (target != null) {
                boolean forbidden = pending.selectionMode == ItemData.SelectionMode.FORBIDDEN_ZONE;
                boxes.add(new Box(Bounds.of(pending.p1, target),
                        forbidden ? 1.0f : 0.18f, forbidden ? 0.16f : 0.52f, forbidden ? 0.12f : 1.0f));
            }
        }

        // 1.21.1 的 BufferSource 对共用 shared buffer 的 RenderType，取第二个 buffer 时会
        // endBatch 掉第一个（building=false）。LINES 与 debugFilledBox 都走 shared buffer，
        // 因此必须分两趟：先画完全部线框并 endBatch，再取面缓冲画半透明面。
        VertexConsumer lines = buffers.getBuffer(RenderType.LINES);
        for (Box box : boxes) {
            drawLines(poses, lines, box);
        }
        buffers.endBatch(RenderType.LINES);

        VertexConsumer faces = buffers.getBuffer(RenderType.debugFilledBox());
        for (Box box : boxes) {
            drawFaces(faces, matrix, box);
        }
        buffers.endBatch(RenderType.debugFilledBox());

        poses.popPose();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
    }

    private record Box(Bounds bounds, float r, float g, float b) {
    }

    private static void drawLines(PoseStack poses, VertexConsumer lines, Box box) {
        Bounds b = box.bounds();
        if (b == null) {
            return;
        }
        LevelRenderer.renderLineBox(poses, lines, b.x1, b.y1, b.z1,
                b.x2 + 1.0f, b.y2 + 1.0f, b.z2 + 1.0f, box.r(), box.g(), box.b(), 0.96f);
    }

    private static void drawFaces(VertexConsumer out, Matrix4f matrix, Box box) {
        Bounds b = box.bounds();
        if (b == null) {
            return;
        }
        float x1 = b.x1;
        float y1 = b.y1;
        float z1 = b.z1;
        float x2 = b.x2 + 1.0f;
        float y2 = b.y2 + 1.0f;
        float z2 = b.z2 + 1.0f;
        float r = box.r();
        float g = box.g();
        float bl = box.b();
        addFace(out, matrix, x1, y1, z1, x1, y2, z1, x2, y2, z1, x2, y1, z1, r, g, bl);
        addFace(out, matrix, x1, y1, z2, x2, y1, z2, x2, y2, z2, x1, y2, z2, r, g, bl);
        addFace(out, matrix, x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2, r, g, bl);
        addFace(out, matrix, x1, y2, z1, x1, y2, z2, x2, y2, z2, x2, y2, z1, r, g, bl);
        addFace(out, matrix, x1, y1, z1, x1, y1, z2, x1, y2, z2, x1, y2, z1, r, g, bl);
        addFace(out, matrix, x2, y1, z1, x2, y2, z1, x2, y2, z2, x2, y1, z2, r, g, bl);
    }

    private static BlockPos hoveredBlock(Minecraft mc) {
        HitResult hit = mc.hitResult;
        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
            return blockHit.getBlockPos();
        }
        return null;
    }

    private static void addFace(VertexConsumer out, Matrix4f matrix,
                                float ax, float ay, float az, float bx, float by, float bz,
                                float cx, float cy, float cz, float dx, float dy, float dz,
                                float r, float g, float b) {
        out.addVertex(matrix, ax, ay, az).setColor(r, g, b, 0.105f);
        out.addVertex(matrix, bx, by, bz).setColor(r, g, b, 0.105f);
        out.addVertex(matrix, cx, cy, cz).setColor(r, g, b, 0.105f);
        out.addVertex(matrix, dx, dy, dz).setColor(r, g, b, 0.105f);
    }
}
