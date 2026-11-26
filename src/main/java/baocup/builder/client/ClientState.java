package baocup.builder.client;

import baocup.builder.util.Bounds;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 服务端状态快照的客户端镜像，字段与 AgentManager.stateJson 一一对应。 */
public final class ClientState {
    public String taskId = "";
    public String owner = "";
    public String dimension = "";
    public String status = "IDLE";
    public String stage = "待命";
    /** 仅支持 external = 外部 Agent（MCP）。 */
    public String mode = "external";
    public String userBrief = "";
    public String planText = "";
    public String digest = "";
    public int[] bounds = new int[6];
    public int[] activeBounds = new int[6];
    public String activeRegionId = "root";
    public boolean hasBounds;
    public List<RegionItem> regions = new ArrayList<>();
    public List<ForbiddenItem> forbiddenZones = new ArrayList<>();
    public boolean planMode;
    public boolean planReady;
    public boolean hasBuilt;
    public int modelCalls;
    public int version;
    public long promptTokens;
    public long completionTokens;
    public long blocksPlacedTotal;

    public String factSummary = "";
    public String semanticSummary = "";
    public String crossVerdict = "";
    public int crossFactOnly;
    public int crossSemanticOnly;
    public int crossConflicts;
    public int factIndexSize;
    public int entityCount;
    public String visualReport = "";
    public int cameraCount;
    public List<CaptureItem> captures = new ArrayList<>();
    public List<String> visualIssues = new ArrayList<>();

    public static final class RegionItem {
        public String id = "root";
        public String parentId = "";
        public String name = "根建造区";
        public String description = "";
        public String status = "PLANNED";
        public int progress;
        public long blocksPlaced;
        public int[] bounds;
        public long createdAt;
        public long updatedAt;

        public Bounds bounds() {
            return Bounds.fromArray(bounds);
        }
    }

    public static final class ForbiddenItem {
        public String id = "";
        public String name = "禁建区";
        public String description = "";
        public int[] bounds;
        public long createdAt;

        public Bounds bounds() {
            return Bounds.fromArray(bounds);
        }
    }

    public static final class CaptureItem {
        public String id = "";
        public String name = "";
        public String path = "";
        public int width;
        public int height;
        public int blockPixels;
        public int skyPixels;
        public String materials = "";
        public String pose = "";
        public long ts;
    }

    public List<LogItem> logs = new ArrayList<>();
    public List<ChatItem> chat = new ArrayList<>();
    public List<VersionItem> versions = new ArrayList<>();
    public List<ZoneItem> zones = new ArrayList<>();
    public Map<String, Integer> materials = new LinkedHashMap<>();
    public List<FactLevel> factLevels = new ArrayList<>();
    public List<FactRoom> factRooms = new ArrayList<>();
    public List<FactFeature> factFeatures = new ArrayList<>();
    public List<SemanticRoom> semanticRooms = new ArrayList<>();
    public List<String> crossIssues = new ArrayList<>();

    public static final class FactLevel {
        public int index;
        public int yMin;
        public int yMax;
        public int blocks;
        public String dominantMaterial = "";
    }

    public static final class FactRoom {
        public String id = "";
        public String name = "";
        public String purpose = "";
        public String box = "";
        public int floorCount;
        public int wallCount;
    }

    public static final class FactFeature {
        public String id = "";
        public String kind = "";
        public String name = "";
        public String material = "";
        public String facing = "";
        public String state = "";
        public String box = "";
        public String sourceZone = "";
        public int blocks;
    }

    public static final class SemanticRoom {
        public String roomId = "";
        public String name = "";
        public String purpose = "";
        public String mood = "";
    }

    public static final class LogItem {
        public long ts;
        public String stage = "";
        public String level = "";
        public String text = "";
    }

    public static final class ChatItem {
        public long ts;
        public String role = "";
        public String text = "";
    }

    public static final class VersionItem {
        public int version;
        public long ts;
        public String instruction = "";
        public int blocks;
        public String digest = "";
    }

    public static final class ZoneItem {
        public String id = "";
        public String op = "";
        public String name = "";
        public String shape = "";
        public String box = "";
        public String material = "";
    }

    public Bounds rootBounds() {
        return hasBounds ? Bounds.fromArray(bounds) : null;
    }

    public Bounds activeBounds() {
        if (!hasBounds) {
            return null;
        }
        Bounds active = activeBounds == null || activeBounds.length != 6 ? null : Bounds.fromArray(activeBounds);
        return active == null ? rootBounds() : active;
    }

    public RegionItem activeRegion() {
        for (RegionItem region : safeRegions()) {
            if (region.id.equals(activeRegionId)) {
                return region;
            }
        }
        return null;
    }

    public List<RegionItem> safeRegions() {
        return regions == null ? new ArrayList<>() : regions;
    }

    public List<ForbiddenItem> safeForbiddenZones() {
        return forbiddenZones == null ? new ArrayList<>() : forbiddenZones;
    }

    public String safe(String value) {
        return value == null ? "" : value;
    }

    public List<LogItem> safeLogs() {
        return logs == null ? new ArrayList<>() : logs;
    }

    public List<ChatItem> safeChat() {
        return chat == null ? new ArrayList<>() : chat;
    }

    public List<VersionItem> safeVersions() {
        return versions == null ? new ArrayList<>() : versions;
    }

    public List<ZoneItem> safeZones() {
        return zones == null ? new ArrayList<>() : zones;
    }

    public Map<String, Integer> safeMaterials() {
        return materials == null ? new LinkedHashMap<>() : materials;
    }

    public List<FactLevel> safeFactLevels() {
        return factLevels == null ? new ArrayList<>() : factLevels;
    }

    public List<FactRoom> safeFactRooms() {
        return factRooms == null ? new ArrayList<>() : factRooms;
    }

    public List<FactFeature> safeFactFeatures() {
        return factFeatures == null ? new ArrayList<>() : factFeatures;
    }

    public List<SemanticRoom> safeSemanticRooms() {
        return semanticRooms == null ? new ArrayList<>() : semanticRooms;
    }

    public List<String> safeCrossIssues() {
        return crossIssues == null ? new ArrayList<>() : crossIssues;
    }

    public List<CaptureItem> safeCaptures() {
        return captures == null ? new ArrayList<>() : captures;
    }

    public List<String> safeVisualIssues() {
        return visualIssues == null ? new ArrayList<>() : visualIssues;
    }
}
