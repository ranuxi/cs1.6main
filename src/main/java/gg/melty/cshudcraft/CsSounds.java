package gg.melty.cshudcraft;

import net.minecraft.client.MinecraftClient;
import net.minecraft.sound.SoundCategory;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL10;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.BufferedInputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Plays Counter-Strike's own .wav files from the player's install (paths under sound/, cstrike first then
 * valve) through OpenAL, the same audio engine Minecraft uses: each file is decoded once into an AL buffer
 * and played on a small pool of sources, so rapid fire and the C4's fast beeps mix without stalls or cuts.
 * Volume follows Minecraft's master volume. All AL calls run on the client thread.
 */
public final class CsSounds {
    // sheets/systems.json "sounds"
    public static final String HUD_ON = "common/wpn_hudon.wav";
    public static final String LOCK_LOAD = "radio/locknload.wav";
    public static final String HUD_OFF = "common/wpn_hudoff.wav";
    public static final String ENEMY_DOWN = "radio/enemydown.wav";

    /** Few sources, so Minecraft's own sound engine keeps plenty of the device's limit. */
    static final int POOL = 16;

    private record Clip(int buffer, long millis) {}

    private static final Map<String, Clip> BUFFERS = new HashMap<>();
    private static final int[] SOURCES = new int[POOL];
    private static final long[] STARTED = new long[POOL];
    private static boolean ready;
    private static final ScheduledExecutorService LATER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "CSHudCraft sound queue");
        t.setDaemon(true);
        return t;
    });

    private CsSounds() {}

    /** Plays several files one after another (e.g. hud on, then "locked and loaded"). */
    public static void play(CsAssets assets, String... rels) {
        onClient(() -> {
            if (!alReady()) return;
            long delay = 0;
            for (String rel : rels) {
                long d = delay;
                if (d == 0) playNow(assets, rel, 1f, null);
                else LATER.schedule(() -> onClient(() -> playNow(assets, rel, 1f, null)), d, TimeUnit.MILLISECONDS);
                Clip c = clip(assets, rel);
                delay += c == null ? 0 : c.millis();
            }
        });
    }

    public static void play(CsAssets assets, String rel, float volume) {
        onClient(() -> playNow(assets, rel, volume, null));
    }

    /**
     * Plays on a named channel, like GoldSrc entity channels: a new sound on the same channel cuts the
     * previous one. The C4 uses one channel, so its 1.5 s beep files never pile up when it beeps fast.
     */
    public static void playOn(CsAssets assets, String channel, String rel, float volume) {
        onClient(() -> playNow(assets, rel, volume, channel));
    }

    private static void onClient(Runnable r) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.isOnThread()) r.run();
        else mc.execute(r);
    }

    private static final Map<String, Integer> CHANNELS = new HashMap<>();

    private static void playNow(CsAssets assets, String rel, float volume, String channel) {
        if (!assets.loaded()) return;
        float master = MinecraftClient.getInstance().options.getSoundVolume(SoundCategory.MASTER);
        float v = volume * master * 0.8f;
        if (v <= 0.001f) return;
        if (!alReady()) return; // Minecraft's sound engine isn't up yet (or was shut down): touching AL now would break it
        try {
            if (!ensureSources()) return;
            Clip c = clip(assets, rel);
            if (c == null) return;
            int slot = -1;
            if (channel != null) {
                Integer s = CHANNELS.get(channel);
                if (s != null) slot = s; // same channel: reuse its source, which stops the old sound
            }
            if (slot < 0) slot = freeSource();
            for (Map.Entry<String, Integer> e : CHANNELS.entrySet()) {
                if (e.getValue() == slot && !e.getKey().equals(channel)) { CHANNELS.remove(e.getKey()); break; }
            }
            if (channel != null) CHANNELS.put(channel, slot);
            int src = SOURCES[slot];
            AL10.alSourceStop(src);
            AL10.alSourcei(src, AL10.AL_BUFFER, c.buffer());
            AL10.alSourcef(src, AL10.AL_GAIN, Math.min(1f, v));
            AL10.alSourcei(src, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
            AL10.alSource3f(src, AL10.AL_POSITION, 0f, 0f, 0f);
            AL10.alSourcePlay(src);
            STARTED[slot] = System.nanoTime();
            if (AL10.alGetError() != AL10.AL_NO_ERROR) reset(); // the sound engine was restarted: rebuild next time
        } catch (Throwable e) {
            CsHudCraft.LOG.debug("[CSHudCraft] Could not play {}: {}", rel, e.toString());
            reset();
        }
    }

    /** True once Minecraft has created its OpenAL context; checked without touching any AL function. */
    private static boolean alReady() {
        try {
            return org.lwjgl.openal.AL.getCapabilities() != null;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private static boolean ensureSources() {
        if (ready && AL10.alIsSource(SOURCES[0])) return true;
        BUFFERS.clear();
        CHANNELS.clear();
        AL10.alGetError();
        for (int i = 0; i < POOL; i++) {
            SOURCES[i] = AL10.alGenSources();
            AL10.alSourcef(SOURCES[i], AL10.AL_ROLLOFF_FACTOR, 0f);
        }
        ready = AL10.alGetError() == AL10.AL_NO_ERROR;
        CsHudCraft.LOG.info("[CSHudCraft] OpenAL sound pool {}", ready ? "ready (" + POOL + " sources)" : "failed");
        return ready;
    }

    private static void reset() {
        ready = false;
        BUFFERS.clear();
        CHANNELS.clear();
    }

    /** A source that's not playing, or the one that started longest ago. */
    private static int freeSource() {
        int oldest = 0;
        for (int i = 0; i < POOL; i++) {
            if (AL10.alGetSourcei(SOURCES[i], AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) return i;
            if (STARTED[i] < STARTED[oldest]) oldest = i;
        }
        return oldest;
    }

    private static Clip clip(CsAssets assets, String rel) {
        if (!alReady()) return null;
        if (BUFFERS.containsKey(rel)) return BUFFERS.get(rel);
        Clip c = decode(assets, rel);
        BUFFERS.put(rel, c);
        return c;
    }

    private static Clip decode(CsAssets assets, String rel) {
        Path p = assets.resolve("sound/" + rel);
        if (p == null) {
            CsHudCraft.LOG.warn("[CSHudCraft] Missing CS sound {}", rel);
            return null;
        }
        try (AudioInputStream in = AudioSystem.getAudioInputStream(new BufferedInputStream(Files.newInputStream(p)))) {
            AudioFormat f = in.getFormat();
            AudioInputStream pcm = in;
            boolean eight = f.getSampleSizeInBits() == 8 && f.getEncoding() == AudioFormat.Encoding.PCM_UNSIGNED;
            boolean sixteen = f.getSampleSizeInBits() == 16 && f.getEncoding() == AudioFormat.Encoding.PCM_SIGNED && !f.isBigEndian();
            if (!eight && !sixteen) { // anything else becomes 16-bit signed little-endian
                AudioFormat target = new AudioFormat(f.getSampleRate(), 16, f.getChannels(), true, false);
                pcm = AudioSystem.getAudioInputStream(target, in);
                f = target;
                eight = false;
            }
            byte[] bytes = pcm.readAllBytes();
            int channels = f.getChannels();
            int format = eight ? (channels == 2 ? AL10.AL_FORMAT_STEREO8 : AL10.AL_FORMAT_MONO8)
                    : (channels == 2 ? AL10.AL_FORMAT_STEREO16 : AL10.AL_FORMAT_MONO16);
            ByteBuffer buf = BufferUtils.createByteBuffer(bytes.length);
            buf.put(bytes).flip();
            int id = AL10.alGenBuffers();
            AL10.alBufferData(id, format, buf, (int) f.getSampleRate());
            long millis = (long) (bytes.length / (double) (channels * (eight ? 1 : 2)) / f.getSampleRate() * 1000);
            return new Clip(id, millis);
        } catch (Exception e) {
            CsHudCraft.LOG.warn("[CSHudCraft] Could not read CS sound {}: {}", rel, e.toString());
            return null;
        }
    }
}
