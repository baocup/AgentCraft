package baocup.builder.memory;

import baocup.builder.util.Bounds;

/** 服务端权威保存的建造区域；子区域通过 parentId 组成任意深度的树。 */
public final class BuildRegion {
    public static final String ROOT_ID = "root";
    public static final String PLANNED = "PLANNED";
    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String DONE = "DONE";

    public String id = ROOT_ID;
    public String parentId = "";
    public String name = "根建造区";
    public String description = "";
    public String status = PLANNED;
    public int progress;
    public long blocksPlaced;
    public int[] bounds;
    public long createdAt = System.currentTimeMillis();
    public long updatedAt = createdAt;

    public BuildRegion() {
    }

    public BuildRegion(String id, String parentId, String name, Bounds bounds) {
        this.id = id;
        this.parentId = parentId == null ? "" : parentId;
        this.name = name == null || name.isBlank() ? id : name.trim();
        this.bounds = bounds == null ? null : bounds.toArray();
    }

    public Bounds bounds() {
        return Bounds.fromArray(bounds);
    }

    public void setBounds(Bounds value) {
        this.bounds = value == null ? null : value.toArray();
        touch();
    }

    public void touch() {
        this.updatedAt = System.currentTimeMillis();
    }

    public BuildRegion copy() {
        BuildRegion copy = new BuildRegion();
        copy.id = id;
        copy.parentId = parentId;
        copy.name = name;
        copy.description = description;
        copy.status = status;
        copy.progress = progress;
        copy.blocksPlaced = blocksPlaced;
        copy.bounds = bounds == null ? null : bounds.clone();
        copy.createdAt = createdAt;
        copy.updatedAt = updatedAt;
        return copy;
    }
}
