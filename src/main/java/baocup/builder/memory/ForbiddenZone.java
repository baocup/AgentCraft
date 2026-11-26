package baocup.builder.memory;

import baocup.builder.util.Bounds;

/** 独立于建造区域树的禁建区，可位于根建造区之外。 */
public final class ForbiddenZone {
    public String id = "";
    public String name = "禁建区";
    public String description = "";
    public int[] bounds;
    public long createdAt = System.currentTimeMillis();

    public ForbiddenZone() {
    }

    public ForbiddenZone(String id, String name, Bounds bounds) {
        this.id = id;
        this.name = name == null || name.isBlank() ? "禁建区" : name.trim();
        this.bounds = bounds == null ? null : bounds.toArray();
    }

    public Bounds bounds() {
        return Bounds.fromArray(bounds);
    }

    public boolean contains(int x, int y, int z) {
        Bounds box = bounds();
        return box != null && box.contains(x, y, z);
    }

    public ForbiddenZone copy() {
        ForbiddenZone copy = new ForbiddenZone();
        copy.id = id;
        copy.name = name;
        copy.description = description;
        copy.bounds = bounds == null ? null : bounds.clone();
        copy.createdAt = createdAt;
        return copy;
    }
}
