package gg.melty.cshudcraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fallback when Melty didn't pass the folder: Half-Life (Counter-Strike 1.6) in the Steam libraries. */
final class SteamFinder {
    private SteamFinder() {}

    static Path find() {
        List<Path> steamRoots = new ArrayList<>();
        for (String env : new String[]{"ProgramFiles(x86)", "ProgramFiles"}) {
            String v = System.getenv(env);
            if (v != null) steamRoots.add(Paths.get(v, "Steam"));
        }
        List<Path> libraries = new ArrayList<>(steamRoots);
        Pattern pathLine = Pattern.compile("\"path\"\\s*\"([^\"]+)\"");
        for (Path root : steamRoots) {
            Path vdf = root.resolve("steamapps/libraryfolders.vdf");
            if (!Files.isRegularFile(vdf)) continue;
            try {
                Matcher m = pathLine.matcher(Files.readString(vdf));
                while (m.find()) libraries.add(Paths.get(m.group(1).replace("\\\\", "\\")));
            } catch (Exception ignored) {
            }
        }
        for (Path lib : libraries) {
            Path hl = lib.resolve("steamapps/common/Half-Life");
            if (Files.isRegularFile(hl.resolve("cstrike/sprites/hud.txt"))) return hl;
        }
        return null;
    }
}
