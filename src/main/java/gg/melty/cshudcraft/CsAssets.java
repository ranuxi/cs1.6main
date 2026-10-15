package gg.melty.cshudcraft;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads Counter-Strike 1.6's HUD straight from the player's install: sprites/hud.txt (640 set)
 * and the GoldSrc .spr sheets it names. Lookups go cstrike/ first, then valve/, like the engine does.
 * Nothing is copied to disk; textures live only in Minecraft's texture manager.
 */
public final class CsAssets {
    /** One hud.txt entry: a rectangle on a sprite sheet. */
    public record Region(String sheet, int x, int y, int w, int h) {}

    /** A loaded .spr sheet. additive = drawn the way CS draws HUD sprites (SPR_DrawAdditive). */
    public record Sheet(Identifier id, int width, int height, boolean additive) {}

    private final Map<String, Region> regions = new HashMap<>();
    private final Map<String, Sheet> sheets = new HashMap<>();
    private Path halfLife;
    private String error;

    public boolean loaded() { return error == null && halfLife != null; }
    public String error() { return error; }
    public Path halfLifeDir() { return halfLife; }

    public Region region(String name) { return regions.get(name); }
    public Sheet sheet(String name) { return sheets.get(name); }

    private final Map<String, Region> weaponRegions = new HashMap<>();
    private final Map<String, Boolean> failedSprites = new HashMap<>();

    /**
     * A region from a weapon's own sprites/<hud>.txt (640 set): kind is "weapon", "weapon_s" or "ammo".
     * The sheet it points to is loaded on first use. Null when the file or entry is missing.
     */
    public Region weaponRegion(String hud, String kind) {
        if (!loaded()) return null;
        String key = hud + ":" + kind;
        if (weaponRegions.containsKey(key)) return weaponRegions.get(key);
        Region found = null;
        Path txt = resolve("sprites/" + hud + ".txt");
        if (txt != null) {
            try {
                for (String line : Files.readAllLines(txt)) {
                    String[] t = line.trim().split("\\s+");
                    if (t.length < 7 || !t[1].equals("640")) continue;
                    Region r = new Region(t[2], Integer.parseInt(t[3]), Integer.parseInt(t[4]), Integer.parseInt(t[5]), Integer.parseInt(t[6]));
                    weaponRegions.put(hud + ":" + t[0], r);
                    if (t[0].equals(kind)) found = r;
                }
            } catch (Exception e) {
                CsHudCraft.LOG.warn("[CSHudCraft] Could not read sprites/{}.txt: {}", hud, e.toString());
            }
        }
        weaponRegions.putIfAbsent(key, found);
        if (found != null && spriteSheet(found.sheet()) == null) return null;
        return found;
    }

    /** Any .spr in the player's install by name (no extension), loaded once. */
    public Sheet spriteSheet(String name) {
        if (!loaded()) return null;
        Sheet s = sheets.get(name);
        if (s != null || failedSprites.containsKey(name)) return s;
        try {
            s = loadSheet(name);
            sheets.put(name, s);
            return s;
        } catch (Exception e) {
            failedSprites.put(name, true);
            CsHudCraft.LOG.warn("[CSHudCraft] Could not load sprite {}: {}", name, e.toString());
            return null;
        }
    }

    /** An uncompressed or RLE .tga from the install (CS 1.6's sniper scope arcs), loaded once. */
    public Sheet tga(String rel) {
        if (!loaded()) return null;
        Sheet s = sheets.get(rel);
        if (s != null || failedSprites.containsKey(rel)) return s;
        try {
            Path f = resolve(rel);
            if (f == null) throw new IOException(rel + " not found");
            ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(f)).order(ByteOrder.LITTLE_ENDIAN);
            int idLen = b.get(0) & 0xFF, type = b.get(2) & 0xFF;
            int w = b.getShort(12) & 0xFFFF, h = b.getShort(14) & 0xFFFF, bpp = b.get(16) & 0xFF, desc = b.get(17) & 0xFF;
            int bytes = bpp / 8;
            if ((type != 2 && type != 10 && type != 3 && type != 11) || bytes < 1) throw new IOException("unsupported tga type " + type);
            int[] px = new int[w * h];
            int p = 18 + idLen, i = 0;
            while (i < w * h) {
                int count = 1;
                boolean rle = false;
                if (type >= 9) {
                    int hdr = b.get(p++) & 0xFF;
                    count = (hdr & 0x7F) + 1;
                    rle = (hdr & 0x80) != 0;
                    if (!rle) { for (int k = 0; k < count && i < px.length; k++) { px[i++] = tgaPixel(b, p, bytes); p += bytes; } continue; }
                }
                int c = tgaPixel(b, p, bytes);
                p += bytes;
                for (int k = 0; k < count && i < px.length; k++) px[i++] = c;
            }
            boolean topDown = (desc & 0x20) != 0;
            NativeImage img = new NativeImage(w, h, true);
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) img.setColorArgb(x, topDown ? y : h - 1 - y, px[y * w + x]);
            Identifier id = Identifier.of(CsHudCraft.MOD_ID, "tga/" + rel.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9/._-]", "_"));
            MinecraftClient.getInstance().getTextureManager().registerTexture(id, new NativeImageBackedTexture(() -> "cshudcraft " + rel, img));
            s = new Sheet(id, w, h, false);
            sheets.put(rel, s);
            return s;
        } catch (Exception e) {
            failedSprites.put(rel, true);
            CsHudCraft.LOG.warn("[CSHudCraft] Could not load {}: {}", rel, e.toString());
            return null;
        }
    }

    private static int tgaPixel(ByteBuffer b, int p, int bytes) {
        if (bytes == 1) { int g = b.get(p) & 0xFF; return 0xFF000000 | (g << 16) | (g << 8) | g; }
        int bl = b.get(p) & 0xFF, g = b.get(p + 1) & 0xFF, r = b.get(p + 2) & 0xFF;
        int a = bytes == 4 ? b.get(p + 3) & 0xFF : 255;
        return (a << 24) | (r << 16) | (g << 8) | bl;
    }

    public void load(Path dir) {
        regions.clear();
        sheets.clear();
        weaponRegions.clear();
        failedSprites.clear();
        error = null;
        halfLife = normalize(dir);
        if (halfLife == null) {
            error = "Counter-Strike folder not found (looked for cstrike/sprites/hud.txt)";
            return;
        }
        try {
            for (String line : Files.readAllLines(halfLife.resolve("cstrike/sprites/hud.txt"))) {
                String[] t = line.trim().split("\\s+");
                if (t.length < 7 || !t[1].equals("640")) continue;
                try {
                    regions.put(t[0], new Region(t[2], Integer.parseInt(t[3]), Integer.parseInt(t[4]),
                            Integer.parseInt(t[5]), Integer.parseInt(t[6])));
                } catch (NumberFormatException ignored) {
                }
            }
            for (String name : CsHudCraft.SPRITES_USED) {
                Region r = regions.get(name);
                if (r == null) throw new IOException("hud.txt has no " + name);
                if (!sheets.containsKey(r.sheet())) sheets.put(r.sheet(), loadSheet(r.sheet()));
            }
        } catch (Exception e) {
            error = e.getMessage();
            CsHudCraft.LOG.error("[CSHudCraft] Could not read Counter-Strike's HUD from {}", halfLife, e);
        }
    }

    /** Accepts the Half-Life folder, its cstrike folder, or anything holding them. */
    static Path normalize(Path dir) {
        if (dir == null) return null;
        for (Path p : List.of(dir, dir.resolve("Half-Life"), dir.getParent() == null ? dir : dir.getParent())) {
            if (Files.isRegularFile(p.resolve("cstrike/sprites/hud.txt"))) return p;
        }
        return null;
    }

    /** cstrike/ first, then valve/, as GoldSrc resolves files. */
    public Path resolve(String rel) {
        for (String game : new String[]{"cstrike", "valve"}) {
            Path p = halfLife.resolve(game).resolve(rel);
            if (Files.isRegularFile(p)) return p;
        }
        return null;
    }

    /**
     * An additive .spr (muzzle flash) for drawing in the world: black becomes transparent, the way GoldSrc
     * adds these sprites onto the screen. Loaded once.
     */
    public Sheet glowSheet(String name) {
        if (!loaded()) return null;
        String key = "glow:" + name;
        Sheet s = sheets.get(key);
        if (s != null || failedSprites.containsKey(key)) return s;
        try {
            s = loadSheet(name, true);
            sheets.put(key, s);
            return s;
        } catch (Exception e) {
            failedSprites.put(key, true);
            CsHudCraft.LOG.warn("[CSHudCraft] Could not load sprite {}: {}", name, e.toString());
            return null;
        }
    }

    private Sheet loadSheet(String name) throws IOException {
        return loadSheet(name, false);
    }

    private Sheet loadSheet(String name, boolean glow) throws IOException {
        Path file = resolve("sprites/" + name + ".spr");
        if (file == null) throw new IOException("missing sprites/" + name + ".spr");
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN);
        if (b.getInt() != 0x50534449) throw new IOException(name + ".spr is not a GoldSrc sprite");
        int version = b.getInt();
        if (version != 2) throw new IOException(name + ".spr has version " + version);
        b.getInt(); // type
        int texFormat = b.getInt();
        b.getFloat(); // bounding radius
        b.getInt(); b.getInt(); // max width/height
        b.getInt(); // frames
        b.getFloat(); // beam length
        b.getInt(); // sync type
        int palCount = b.getShort() & 0xFFFF;
        byte[] pal = new byte[palCount * 3];
        b.get(pal);
        int group = b.getInt();
        if (group != 0) throw new IOException(name + ".spr starts with a frame group");
        b.getInt(); b.getInt(); // origin
        int w = b.getInt(), h = b.getInt();
        NativeImage img = new NativeImage(w, h, true);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = b.get() & 0xFF;
                int r, g, bl, a;
                if (texFormat == 2) { // indexalpha: colour is the last palette entry, index is alpha
                    int last = (palCount - 1) * 3;
                    r = pal[last] & 0xFF; g = pal[last + 1] & 0xFF; bl = pal[last + 2] & 0xFF; a = idx;
                } else {
                    r = pal[idx * 3] & 0xFF; g = pal[idx * 3 + 1] & 0xFF; bl = pal[idx * 3 + 2] & 0xFF;
                    a = (texFormat == 3 && idx == 255) ? 0 : 255; // alphatest: index 255 is a hole
                }
                if (glow) { // additive: brightness is coverage; keep the colour at full strength
                    int m = Math.max(r, Math.max(g, bl));
                    a = Math.min(a, m);
                    if (m > 0) { r = r * 255 / m; g = g * 255 / m; bl = bl * 255 / m; }
                }
                img.setColorArgb(x, y, (a << 24) | (r << 16) | (g << 8) | bl);
            }
        }
        Identifier id = Identifier.of(CsHudCraft.MOD_ID, (glow ? "glow/" : "spr/") + name.toLowerCase(Locale.ROOT));
        MinecraftClient.getInstance().getTextureManager().registerTexture(id, new NativeImageBackedTexture(() -> "cshudcraft " + name, img));
        return new Sheet(id, w, h, texFormat == 0 || texFormat == 1);
    }
}
