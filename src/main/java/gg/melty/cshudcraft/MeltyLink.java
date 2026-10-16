package gg.melty.cshudcraft;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.world.GameMode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.Locale;
import java.util.Properties;

/**
 * Playing together through Melty. At start the mod empties %LOCALAPPDATA%/CS16Craft/melty.log and
 * reads join= from melty.properties there. With an address it joins that game from the title screen
 * and logs "Melty joined: <address>" (or "Melty join failed: <why>"). Without one this player hosts:
 * the world they load is opened to others, and e4mc (bundled) relays it so friends on other networks
 * can join without forwarding ports; its domain is logged as "Melty join address: <domain>".
 */
final class MeltyLink {
    private static final String RELAY_LINE = "Domain assigned: ";
    /** How long to wait for the relay before handing out the LAN address instead. */
    private static final long RELAY_WAIT_MS = 45_000;

    private static Path log;
    private static String join = "";
    private static boolean joinStarted, joinPending;
    private static boolean opened;
    private static long openedAt;
    private static int lanPort;
    private static volatile String address;
    private static boolean fallbackSaid;

    private MeltyLink() {}

    /** The address this host handed out, or null. */
    static String address() { return address; }

    static boolean hosting() { return join.isEmpty(); }

    static void init() {
        String local = System.getenv("LOCALAPPDATA");
        if (local == null) return;
        Path dir = Paths.get(local, "CS16Craft");
        try {
            Files.createDirectories(dir);
            log = dir.resolve("melty.log");
            Files.writeString(log, "");
        } catch (IOException e) {
            CsHudCraft.LOG.warn("[CSHudCraft] Melty log unavailable: {}", e.toString());
            log = null;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(dir.resolve("melty.properties"))) {
            props.load(in);
        } catch (IOException ignored) {
            // no file: this player hosts
        }
        join = props.getProperty("join", "").trim();
        CsHudCraft.LOG.info("[CSHudCraft] Melty: {}", join.isEmpty() ? "hosting (worlds open to others)" : "joining " + join);
        if (join.isEmpty()) watchRelay();
    }

    private static void say(String line) {
        CsHudCraft.LOG.info("[CSHudCraft] {}", line);
        write(line);
    }

    /** Appends to melty.log only (the relay watcher runs inside logging, so it must not log again). */
    private static void write(String line) {
        if (log == null) return;
        try {
            Files.writeString(log, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }

    // ---------------------------------------------------------------- joining

    static void onScreen(MinecraftClient client, Screen screen) {
        if (!join.isEmpty() && !joinStarted && screen instanceof TitleScreen) {
            joinStarted = true;
            joinPending = true;
            client.execute(() -> ConnectScreen.connect(screen, client, ServerAddress.parse(join),
                    new ServerInfo("Melty", join, ServerInfo.ServerType.OTHER), false, null));
        } else if (joinPending && screen instanceof DisconnectedScreen) {
            joinPending = false;
            say("Melty join failed: " + oneLine(screen.getNarratedTitle().getString()));
        }
    }

    static void onJoinWorld(MinecraftClient client) {
        if (joinPending && client.getServer() == null) {
            joinPending = false;
            say("Melty joined: " + join);
        }
    }

    // ---------------------------------------------------------------- hosting

    /** Every client tick: once a world of this player's own is running, open it to others. */
    static void tick(MinecraftClient client) {
        IntegratedServer server = client.getServer();
        if (server == null || client.player == null) {
            if (server == null && opened) { opened = false; address = null; fallbackSaid = false; }
            return;
        }
        if (!join.isEmpty()) return;
        if (!opened) {
            opened = true;
            openedAt = System.currentTimeMillis();
            if (server.isRemote()) {
                lanPort = server.getServerPort();
            } else {
                lanPort = freePort();
                // test harness only: two copies on one PC with offline names; players always get online mode
                if ("1".equals(System.getenv("CSHUDCRAFT_LAN_OFFLINE"))) server.setOnlineMode(false);
                GameMode mode = client.interactionManager != null ? client.interactionManager.getCurrentGameMode() : GameMode.SURVIVAL;
                if (!server.openToLan(mode, false, lanPort)) {
                    say("Melty join failed: could not open the world to others on port " + lanPort);
                    return;
                }
            }
            CsHudCraft.LOG.info("[CSHudCraft] World open to others on port {}", lanPort);
            if (!FabricLoader.getInstance().isModLoaded("e4mc")) handOutLan();
            return;
        }
        if (address == null && !fallbackSaid && System.currentTimeMillis() - openedAt > RELAY_WAIT_MS) handOutLan();
    }

    /** No relay domain (e4mc missing or unreachable): the LAN address, which Melty's own relay can carry. */
    private static void handOutLan() {
        fallbackSaid = true;
        String a = lanIp() + ":" + lanPort;
        address = a;
        say("Melty join address: " + a);
    }

    /** e4mc logs "Domain assigned: <domain>" once its relay is up. */
    private static void watchRelay() {
        try {
            AbstractAppender appender = new AbstractAppender("CSHudCraftMelty", null, null, true, Property.EMPTY_ARRAY) {
                @Override
                public void append(LogEvent event) {
                    String m = event.getMessage() == null ? null : event.getMessage().getFormattedMessage();
                    if (m == null || !m.startsWith(RELAY_LINE)) return;
                    String domain = m.substring(RELAY_LINE.length()).trim().toLowerCase(Locale.ROOT);
                    if (domain.isEmpty() || domain.equals(address)) return;
                    address = domain;
                    write("Melty join address: " + domain);
                }
            };
            appender.start();
            ((org.apache.logging.log4j.core.Logger) LogManager.getRootLogger()).addAppender(appender);
        } catch (Throwable t) {
            CsHudCraft.LOG.warn("[CSHudCraft] Could not watch for the relay address: {}", t.toString());
        }
    }

    private static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            return 25565;
        }
    }

    private static String lanIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (IOException ignored) {
        }
        return "127.0.0.1";
    }

    private static String oneLine(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }
}
