package gg.melty.cshudcraft.mdl;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A GoldSrc studio model (.mdl version 10) read from the player's own Counter-Strike install:
 * bones, sequences with their RLE animations and events, attachments, the first model of every
 * body part, and its 8-bit palettised textures. Pure data; MdlPose animates it, ViewModelRenderer draws it.
 */
public final class MdlModel {
    public static final int NF_CHROME = 0x02, NF_ADDITIVE = 0x20, NF_MASKED = 0x40;

    public record Bone(String name, int parent, float[] value, float[] scale) {}
    public record Event(int frame, int event, String options) {}
    public record Sequence(String name, float fps, int numFrames, int animOffset, List<Event> events) {
        public float duration() { return numFrames <= 1 ? 0.1f : (numFrames - 1) / Math.max(1f, fps); }
    }
    public record Attachment(int bone, float[] org) {}
    public record Texture(String name, int flags, int width, int height, int[] argb) {}
    /** Vertices (model space) with their bone; strips/fans flattened to triangles: [v, n, s, t] x 3 per triangle. */
    public record Mesh(int skinRef, int[] tris) {}
    public record Part(float[] verts, int[] vertBone, float[] norms, int[] normBone, List<Mesh> meshes) {}

    public final String name;
    public final ByteBuffer data;
    public final List<Bone> bones = new ArrayList<>();
    public final List<Sequence> sequences = new ArrayList<>();
    public final Map<String, Integer> sequenceIndex = new HashMap<>();
    public final List<Attachment> attachments = new ArrayList<>();
    public final List<Texture> textures = new ArrayList<>();
    public final List<Part> parts = new ArrayList<>();
    public short[] skinRefs = new short[0];

    private MdlModel(String name, ByteBuffer data) {
        this.name = name;
        this.data = data;
    }

    public int sequence(String seqName) {
        Integer i = sequenceIndex.get(seqName);
        return i == null ? -1 : i;
    }

    public static MdlModel load(Path file, Path textureFile) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN);
        if (b.getInt(0) != 0x54534449) throw new IOException(file.getFileName() + " is not a studio model");
        if (b.getInt(4) != 10) throw new IOException(file.getFileName() + " has version " + b.getInt(4));
        MdlModel m = new MdlModel(file.getFileName().toString(), b);

        int numBones = b.getInt(140), boneIndex = b.getInt(144);
        for (int i = 0; i < numBones; i++) {
            int o = boneIndex + i * 112;
            float[] value = new float[6], scale = new float[6];
            for (int j = 0; j < 6; j++) {
                value[j] = b.getFloat(o + 64 + j * 4);
                scale[j] = b.getFloat(o + 88 + j * 4);
            }
            m.bones.add(new Bone(str(b, o, 32), b.getInt(o + 32), value, scale));
        }

        int numSeq = b.getInt(164), seqIndex = b.getInt(168);
        for (int i = 0; i < numSeq; i++) {
            int o = seqIndex + i * 176;
            List<Event> events = new ArrayList<>();
            int numEv = b.getInt(o + 48), evIndex = b.getInt(o + 52);
            for (int e = 0; e < numEv; e++) {
                int eo = evIndex + e * 76;
                events.add(new Event(b.getInt(eo), b.getInt(eo + 4), str(b, eo + 12, 64)));
            }
            String label = str(b, o, 32);
            m.sequences.add(new Sequence(label, b.getFloat(o + 32), b.getInt(o + 56), b.getInt(o + 124), events));
            m.sequenceIndex.putIfAbsent(label, i);
        }

        int numAtt = b.getInt(212), attIndex = b.getInt(216);
        for (int i = 0; i < numAtt; i++) {
            int o = attIndex + i * 88;
            m.attachments.add(new Attachment(b.getInt(o + 36), new float[]{b.getFloat(o + 40), b.getFloat(o + 44), b.getFloat(o + 48)}));
        }

        // textures live in the model, or in <name>T.mdl when the model has none
        ByteBuffer tb = b;
        if (b.getInt(180) == 0 && textureFile != null && Files.isRegularFile(textureFile)) {
            tb = ByteBuffer.wrap(Files.readAllBytes(textureFile)).order(ByteOrder.LITTLE_ENDIAN);
        }
        int numTex = tb.getInt(180), texIndex = tb.getInt(184);
        for (int i = 0; i < numTex; i++) {
            int o = texIndex + i * 80;
            int flags = tb.getInt(o + 64), w = tb.getInt(o + 68), h = tb.getInt(o + 72), idx = tb.getInt(o + 76);
            int[] argb = new int[w * h];
            int pal = idx + w * h;
            for (int p = 0; p < w * h; p++) {
                int c = tb.get(idx + p) & 0xFF;
                int r = tb.get(pal + c * 3) & 0xFF, g = tb.get(pal + c * 3 + 1) & 0xFF, bl = tb.get(pal + c * 3 + 2) & 0xFF;
                int a = ((flags & NF_MASKED) != 0 && c == 255) ? 0 : 255;
                argb[p] = (a << 24) | (r << 16) | (g << 8) | bl;
            }
            m.textures.add(new Texture(str(tb, o, 64), flags, w, h, argb));
        }
        int numSkinRef = tb.getInt(192), skinIndex = tb.getInt(200);
        m.skinRefs = new short[numSkinRef];
        for (int i = 0; i < numSkinRef; i++) m.skinRefs[i] = tb.getShort(skinIndex + i * 2); // skin family 0

        int numParts = b.getInt(204), partIndex = b.getInt(208);
        for (int p = 0; p < numParts; p++) {
            int po = partIndex + p * 76;
            int numModels = b.getInt(po + 64), modelIndex = b.getInt(po + 72);
            if (numModels <= 0) continue;
            int mo = modelIndex; // body 0 = first model of each part
            int numVerts = b.getInt(mo + 80), vertInfo = b.getInt(mo + 84), vertIdx = b.getInt(mo + 88);
            int numNorms = b.getInt(mo + 92), normInfo = b.getInt(mo + 96), normIdx = b.getInt(mo + 100);
            int numMesh = b.getInt(mo + 72), meshIdx = b.getInt(mo + 76);
            if (numVerts == 0) continue; // blank body part
            float[] verts = new float[numVerts * 3];
            int[] vertBone = new int[numVerts];
            for (int v = 0; v < numVerts; v++) {
                for (int k = 0; k < 3; k++) verts[v * 3 + k] = b.getFloat(vertIdx + v * 12 + k * 4);
                vertBone[v] = b.get(vertInfo + v) & 0xFF;
            }
            float[] norms = new float[numNorms * 3];
            int[] normBone = new int[numNorms];
            for (int v = 0; v < numNorms; v++) {
                for (int k = 0; k < 3; k++) norms[v * 3 + k] = b.getFloat(normIdx + v * 12 + k * 4);
                normBone[v] = b.get(normInfo + v) & 0xFF;
            }
            List<Mesh> meshes = new ArrayList<>();
            for (int me = 0; me < numMesh; me++) {
                int meo = meshIdx + me * 20;
                meshes.add(new Mesh(b.getInt(meo + 8), triangles(b, b.getInt(meo + 4))));
            }
            m.parts.add(new Part(verts, vertBone, norms, normBone, meshes));
        }
        return m;
    }

    /** GoldSrc tri commands: count > 0 strip, < 0 fan, 0 ends; each vertex is (vert, norm, s, t). */
    private static int[] triangles(ByteBuffer b, int offset) {
        List<int[]> out = new ArrayList<>();
        int o = offset;
        while (true) {
            int count = b.getShort(o);
            o += 2;
            if (count == 0) break;
            boolean fan = count < 0;
            count = Math.abs(count);
            int[][] vs = new int[count][4];
            for (int i = 0; i < count; i++) {
                for (int k = 0; k < 4; k++) vs[i][k] = b.getShort(o + k * 2);
                o += 8;
            }
            for (int i = 2; i < count; i++) {
                if (fan) {
                    out.add(vs[0]); out.add(vs[i - 1]); out.add(vs[i]);
                } else if ((i & 1) == 0) {
                    out.add(vs[i - 2]); out.add(vs[i - 1]); out.add(vs[i]);
                } else {
                    out.add(vs[i - 1]); out.add(vs[i - 2]); out.add(vs[i]);
                }
            }
        }
        int[] flat = new int[out.size() * 4];
        for (int i = 0; i < out.size(); i++) System.arraycopy(out.get(i), 0, flat, i * 4, 4);
        return flat;
    }

    static String str(ByteBuffer b, int off, int len) {
        byte[] a = new byte[len];
        b.get(off, a);
        int n = 0;
        while (n < len && a[n] != 0) n++;
        return new String(a, 0, n, StandardCharsets.ISO_8859_1);
    }
}
