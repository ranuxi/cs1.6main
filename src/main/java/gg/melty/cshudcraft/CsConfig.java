package gg.melty.cshudcraft;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/** %LOCALAPPDATA%/CSHudCraft/cshudcraft.properties (sheets/systems.json "settings"). Melty writes cs_dir before every Play. */
public final class CsConfig {
    public static Path folder() {
        String local = System.getenv("LOCALAPPDATA");
        Path base = local != null ? Paths.get(local) : Paths.get(System.getProperty("user.home"), ".local", "share");
        return base.resolve("CSHudCraft");
    }

    private static Path file() { return folder().resolve("cshudcraft.properties"); }

    private final Properties props = new Properties();

    public void load() {
        Path f = file();
        if (!Files.isRegularFile(f)) return;
        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            props.load(r);
        } catch (Exception e) {
            CsHudCraft.LOG.warn("[CSHudCraft] Could not read {}: {}", f, e.toString());
        }
    }

    public void save() {
        try {
            Files.createDirectories(folder());
            try (Writer w = Files.newBufferedWriter(file(), StandardCharsets.UTF_8)) {
                props.store(w, "CS 1.6 HUD Craft");
            }
        } catch (Exception e) {
            CsHudCraft.LOG.warn("[CSHudCraft] Could not save settings: {}", e.toString());
        }
    }

    public String csDir() { return props.getProperty("cs_dir", "").trim(); }
    public boolean enabled() { return !"false".equalsIgnoreCase(props.getProperty("enabled", "true")); }
    public void setEnabled(boolean on) { props.setProperty("enabled", Boolean.toString(on)); save(); }
    /** CS cl_righthand 0: weapon in the left hand. Every start is right-handed; H switches for this session only. */
    private boolean leftHand;
    public boolean leftHanded() { return leftHand; }
    public void setLeftHanded(boolean left) { leftHand = left; }
}
