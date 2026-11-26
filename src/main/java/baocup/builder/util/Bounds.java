package baocup.builder.util;

import net.minecraft.core.BlockPos;

/** 选区包围盒。全部使用 public 字段，便于 Gson 直接序列化到记忆文件。 */
public final class Bounds {
    public int x1;
    public int y1;
    public int z1;
    public int x2;
    public int y2;
    public int z2;

    /** Gson 用 */
    public Bounds() {
    }

    public Bounds(int x1, int y1, int z1, int x2, int y2, int z2) {
        this.x1 = x1;
        this.y1 = y1;
        this.z1 = z1;
        this.x2 = x2;
        this.y2 = y2;
        this.z2 = z2;
        normalize();
    }

    public static Bounds of(BlockPos a, BlockPos b) {
        return new Bounds(a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ());
    }

    public static Bounds fromArray(int[] a) {
        if (a == null || a.length != 6) {
            return null;
        }
        return new Bounds(a[0], a[1], a[2], a[3], a[4], a[5]);
    }

    public int[] toArray() {
        return new int[] { x1, y1, z1, x2, y2, z2 };
    }

    public Bounds copy() {
        return new Bounds(x1, y1, z1, x2, y2, z2);
    }

    public void normalize() {
        if (x1 > x2) {
            int t = x1;
            x1 = x2;
            x2 = t;
        }
        if (y1 > y2) {
            int t = y1;
            y1 = y2;
            y2 = t;
        }
        if (z1 > z2) {
            int t = z1;
            z1 = z2;
            z2 = t;
        }
    }

    public int minX() {
        return x1;
    }

    public int minY() {
        return y1;
    }

    public int minZ() {
        return z1;
    }

    public int maxX() {
        return x2;
    }

    public int maxY() {
        return y2;
    }

    public int maxZ() {
        return z2;
    }

    public long sizeXLong() {
        return (long) x2 - x1 + 1;
    }

    public long sizeYLong() {
        return (long) y2 - y1 + 1;
    }

    public long sizeZLong() {
        return (long) z2 - z1 + 1;
    }

    public int sizeX() {
        return saturatedSize(sizeXLong());
    }

    public int sizeY() {
        return saturatedSize(sizeYLong());
    }

    public int sizeZ() {
        return saturatedSize(sizeZLong());
    }

    /** 超大选区只用于空间约束，绝不据此分配数组；溢出时饱和而不是变成负数。 */
    public long volume() {
        long x = sizeXLong();
        long y = sizeYLong();
        long z = sizeZLong();
        if (x <= 0 || y <= 0 || z <= 0) {
            return 0;
        }
        if (x > Long.MAX_VALUE / y) {
            return Long.MAX_VALUE;
        }
        long xy = x * y;
        return xy > Long.MAX_VALUE / z ? Long.MAX_VALUE : xy * z;
    }

    private static int saturatedSize(long size) {
        return size <= 0 ? 0 : size > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) size;
    }

    public boolean contains(int x, int y, int z) {
        return x >= x1 && x <= x2 && y >= y1 && y <= y2 && z >= z1 && z <= z2;
    }

    public boolean contains(Bounds other) {
        return other != null && contains(other.x1, other.y1, other.z1)
                && contains(other.x2, other.y2, other.z2);
    }

    public boolean intersects(Bounds other) {
        return other != null && x1 <= other.x2 && x2 >= other.x1
                && y1 <= other.y2 && y2 >= other.y1 && z1 <= other.z2 && z2 >= other.z1;
    }

    public Bounds intersection(Bounds other) {
        if (!intersects(other)) {
            return null;
        }
        return new Bounds(Math.max(x1, other.x1), Math.max(y1, other.y1), Math.max(z1, other.z1),
                Math.min(x2, other.x2), Math.min(y2, other.y2), Math.min(z2, other.z2));
    }

    /** 把任意长方体裁剪进选区；完全在选区外时返回 null。 */
    public int[] clamp(int ax1, int ay1, int az1, int ax2, int ay2, int az2) {
        int loX = Math.max(x1, Math.min(ax1, ax2));
        int hiX = Math.min(x2, Math.max(ax1, ax2));
        int loY = Math.max(y1, Math.min(ay1, ay2));
        int hiY = Math.min(y2, Math.max(ay1, ay2));
        int loZ = Math.max(z1, Math.min(az1, az2));
        int hiZ = Math.min(z2, Math.max(az1, az2));
        if (loX > hiX || loY > hiY || loZ > hiZ) {
            return null;
        }
        return new int[] { loX, loY, loZ, hiX, hiY, hiZ };
    }

    public BlockPos center() {
        return new BlockPos((int) (((long) x1 + x2) / 2), y1, (int) (((long) z1 + z2) / 2));
    }

    public String display() {
        return "(" + x1 + "," + y1 + "," + z1 + ") -> (" + x2 + "," + y2 + "," + z2 + ")  "
                + sizeXLong() + "x" + sizeYLong() + "x" + sizeZLong();
    }
}
