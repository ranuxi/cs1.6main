package gg.melty.cshudcraft.mdl;

import java.nio.ByteBuffer;

/**
 * Bone matrices of an MdlModel at a point of a sequence, decoded the way the Half-Life SDK's
 * StudioModel does it (RLE anim values, AngleQuaternion, slerp between frames, parent chain).
 * Matrices are 3x4 row-major in GoldSrc space (X forward, Y left, Z up).
 */
public final class MdlPose {
    private MdlPose() {}

    /** @return float[numBones][12] */
    public static float[][] compute(MdlModel m, int seq, float frame) {
        int n = m.bones.size();
        float[][] out = new float[n][12];
        if (seq < 0 || seq >= m.sequences.size()) {
            for (int i = 0; i < n; i++) identity(out[i]);
            return out;
        }
        MdlModel.Sequence s = m.sequences.get(seq);
        int maxFrame = Math.max(0, s.numFrames() - 1);
        frame = Math.max(0, Math.min(frame, maxFrame));
        int f = (int) frame;
        float t = frame - f;
        if (f >= maxFrame) { f = maxFrame; t = 0; }
        ByteBuffer b = m.data;
        float[] q = new float[4], pos = new float[3], local = new float[12];
        for (int i = 0; i < n; i++) {
            MdlModel.Bone bone = m.bones.get(i);
            int anim = s.animOffset() + i * 12;
            calcRotation(b, anim, bone, f, t, q);
            calcPosition(b, anim, bone, f, t, pos);
            quaternionMatrix(q, local);
            local[3] = pos[0]; local[7] = pos[1]; local[11] = pos[2];
            if (bone.parent() < 0) System.arraycopy(local, 0, out[i], 0, 12);
            else concat(out[bone.parent()], local, out[i]);
        }
        return out;
    }

    private static void calcRotation(ByteBuffer b, int anim, MdlModel.Bone bone, int frame, float s, float[] q) {
        float[] a1 = new float[3], a2 = new float[3];
        for (int j = 0; j < 3; j++) {
            int off = b.getShort(anim + (j + 3) * 2) & 0xFFFF;
            if (off == 0) {
                a1[j] = a2[j] = bone.value()[j + 3];
                continue;
            }
            float[] v = sample(b, anim + off, frame);
            a1[j] = bone.value()[j + 3] + v[0] * bone.scale()[j + 3];
            a2[j] = bone.value()[j + 3] + v[1] * bone.scale()[j + 3];
        }
        if (a1[0] != a2[0] || a1[1] != a2[1] || a1[2] != a2[2]) {
            float[] q1 = new float[4], q2 = new float[4];
            angleQuaternion(a1, q1);
            angleQuaternion(a2, q2);
            slerp(q1, q2, s, q);
        } else {
            angleQuaternion(a1, q);
        }
    }

    private static void calcPosition(ByteBuffer b, int anim, MdlModel.Bone bone, int frame, float s, float[] pos) {
        for (int j = 0; j < 3; j++) {
            pos[j] = bone.value()[j];
            int off = b.getShort(anim + j * 2) & 0xFFFF;
            if (off == 0) continue;
            float[] v = sample(b, anim + off, frame);
            pos[j] += ((1 - s) * v[0] + s * v[1]) * bone.scale()[j];
        }
    }

    /** RLE walk from studio_utils: returns the raw values at frame and frame+1. */
    private static float[] sample(ByteBuffer b, int p, int k) {
        int valid = b.get(p) & 0xFF, total = b.get(p + 1) & 0xFF;
        int guard = 0;
        while (total <= k && guard++ < 4096) {
            k -= total;
            p += (valid + 1) * 2;
            valid = b.get(p) & 0xFF;
            total = b.get(p + 1) & 0xFF;
            if (total == 0) break;
        }
        float v1, v2;
        if (valid > k) {
            v1 = b.getShort(p + (k + 1) * 2);
            if (valid > k + 1) v2 = b.getShort(p + (k + 2) * 2);
            else if (total > k + 1) v2 = v1;
            else v2 = b.getShort(p + (valid + 2) * 2);
        } else {
            v1 = b.getShort(p + valid * 2);
            if (total > k + 1) v2 = v1;
            else v2 = b.getShort(p + (valid + 2) * 2);
        }
        return new float[]{v1, v2};
    }

    static void angleQuaternion(float[] angles, float[] q) {
        double angle = angles[2] * 0.5;
        double sy = Math.sin(angle), cy = Math.cos(angle);
        angle = angles[1] * 0.5;
        double sp = Math.sin(angle), cp = Math.cos(angle);
        angle = angles[0] * 0.5;
        double sr = Math.sin(angle), cr = Math.cos(angle);
        q[0] = (float) (sr * cp * cy - cr * sp * sy);
        q[1] = (float) (cr * sp * cy + sr * cp * sy);
        q[2] = (float) (cr * cp * sy - sr * sp * cy);
        q[3] = (float) (cr * cp * cy + sr * sp * sy);
    }

    static void slerp(float[] p, float[] q, float t, float[] out) {
        float a = 0, b = 0;
        for (int i = 0; i < 4; i++) {
            a += (p[i] - q[i]) * (p[i] - q[i]);
            b += (p[i] + q[i]) * (p[i] + q[i]);
        }
        float[] qq = q.clone();
        if (a > b) for (int i = 0; i < 4; i++) qq[i] = -q[i];
        double cosom = p[0] * qq[0] + p[1] * qq[1] + p[2] * qq[2] + p[3] * qq[3];
        double sclp, sclq;
        if (1.0 + cosom > 1e-6) {
            if (1.0 - cosom > 1e-6) {
                double omega = Math.acos(cosom), sinom = Math.sin(omega);
                sclp = Math.sin((1.0 - t) * omega) / sinom;
                sclq = Math.sin(t * omega) / sinom;
            } else {
                sclp = 1.0 - t;
                sclq = t;
            }
            for (int i = 0; i < 4; i++) out[i] = (float) (sclp * p[i] + sclq * qq[i]);
        } else {
            out[0] = -p[1]; out[1] = p[0]; out[2] = -p[3]; out[3] = p[2];
            sclp = Math.sin((1.0 - t) * 0.5 * Math.PI);
            sclq = Math.sin(t * 0.5 * Math.PI);
            for (int i = 0; i < 3; i++) out[i] = (float) (sclp * p[i] + sclq * out[i]);
        }
    }

    static void quaternionMatrix(float[] q, float[] m) {
        m[0] = 1 - 2 * q[1] * q[1] - 2 * q[2] * q[2];
        m[4] = 2 * q[0] * q[1] + 2 * q[3] * q[2];
        m[8] = 2 * q[0] * q[2] - 2 * q[3] * q[1];
        m[1] = 2 * q[0] * q[1] - 2 * q[3] * q[2];
        m[5] = 1 - 2 * q[0] * q[0] - 2 * q[2] * q[2];
        m[9] = 2 * q[1] * q[2] + 2 * q[3] * q[0];
        m[2] = 2 * q[0] * q[2] + 2 * q[3] * q[1];
        m[6] = 2 * q[1] * q[2] - 2 * q[3] * q[0];
        m[10] = 1 - 2 * q[0] * q[0] - 2 * q[1] * q[1];
    }

    static void concat(float[] a, float[] b, float[] out) {
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                out[r * 4 + c] = a[r * 4] * b[c] + a[r * 4 + 1] * b[4 + c] + a[r * 4 + 2] * b[8 + c];
            }
            out[r * 4 + 3] = a[r * 4] * b[3] + a[r * 4 + 1] * b[7] + a[r * 4 + 2] * b[11] + a[r * 4 + 3];
        }
    }

    static void identity(float[] m) {
        java.util.Arrays.fill(m, 0);
        m[0] = m[5] = m[10] = 1;
    }

    /**
     * Turns one bone and everything hanging off it about that bone's own origin (model space):
     * angles in degrees about X (forward), Y (left), Z (up), then lifted by dz.
     */
    public static void turnSubtree(MdlModel m, float[][] bones, int root, float ax, float ay, float az, float dz) {
        if (root < 0 || root >= bones.length) return;
        float ox = bones[root][3], oy = bones[root][7], oz = bones[root][11];
        double a = Math.toRadians(ax), b = Math.toRadians(ay), c = Math.toRadians(az);
        float ca = (float) Math.cos(a), sa = (float) Math.sin(a), cb = (float) Math.cos(b), sb = (float) Math.sin(b), cc = (float) Math.cos(c), sc = (float) Math.sin(c);
        // R = Rz * Ry * Rx
        float[] r = {
                cc * cb, cc * sb * sa - sc * ca, cc * sb * ca + sc * sa, 0,
                sc * cb, sc * sb * sa + cc * ca, sc * sb * ca - cc * sa, 0,
                -sb, cb * sa, cb * ca, 0};
        float[] tmp = new float[12];
        for (int i = 0; i < bones.length; i++) {
            if (!descends(m, i, root)) continue;
            float[] bm = bones[i];
            float[] shifted = bm.clone();
            shifted[3] -= ox; shifted[7] -= oy; shifted[11] -= oz;
            concat(r, shifted, tmp);
            tmp[3] += ox; tmp[7] += oy; tmp[11] += oz + dz;
            System.arraycopy(tmp, 0, bm, 0, 12);
        }
    }

    private static boolean descends(MdlModel m, int bone, int root) {
        for (int b = bone, guard = 0; b >= 0 && guard < 256; b = m.bones.get(b).parent(), guard++) if (b == root) return true;
        return false;
    }

    /** Index of the first bone whose name contains the text, or -1. */
    public static int findBone(MdlModel m, String text) {
        for (int i = 0; i < m.bones.size(); i++) if (m.bones.get(i).name().toLowerCase().contains(text)) return i;
        return -1;
    }

    /** Transforms a point by a bone matrix. */
    public static void transform(float[] m, float x, float y, float z, float[] out) {
        out[0] = m[0] * x + m[1] * y + m[2] * z + m[3];
        out[1] = m[4] * x + m[5] * y + m[6] * z + m[7];
        out[2] = m[8] * x + m[9] * y + m[10] * z + m[11];
    }

    /** Rotates a direction by a bone matrix. */
    public static void rotate(float[] m, float x, float y, float z, float[] out) {
        out[0] = m[0] * x + m[1] * y + m[2] * z;
        out[1] = m[4] * x + m[5] * y + m[6] * z;
        out[2] = m[8] * x + m[9] * y + m[10] * z;
    }
}
