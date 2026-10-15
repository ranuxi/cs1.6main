package gg.melty.cshudcraft;

import net.minecraft.client.MinecraftClient;
import net.minecraft.sound.SoundCategory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineEvent;
import java.io.BufferedInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Plays Counter-Strike's own .wav files from the player's install (paths under sound/, cstrike first then valve),
 * decoded once and kept in memory so automatic fire overlaps like in CS. Volume follows Minecraft's master volume.
 */
public final class CsSounds {
    // sheets/systems.json "sounds"
    public static final String HUD_ON = "common/wpn_hudon.wav";
    public static final String LOCK_LOAD = "radio/locknload.wav";
    public static final String HUD_OFF = "common/wpn_hudoff.wav";
    public static final String ENEMY_DOWN = "radio/enemydown.wav";

    private record Pcm(AudioFormat format, byte[] bytes) {}

    private static final Map<String, Pcm> CACHE = new ConcurrentHashMap<>();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "CSHudCraft sounds");
        t.setDaemon(true);
        return t;
    });

    private CsSounds() {}

    /** Plays several files one after another (e.g. hud on, then "locked and loaded"). */
    public static void play(CsAssets assets, String... rels) {
        POOL.submit(() -> {
            for (String rel : rels) playNow(assets, rel, 1f, true);
        });
    }

    public static void play(CsAssets assets, String rel, float volume) {
        POOL.submit(() -> playNow(assets, rel, volume, false));
    }

    private static void playNow(CsAssets assets, String rel, float volume, boolean wait) {
        if (!assets.loaded()) return;
        float master = MinecraftClient.getInstance().options.getSoundVolume(SoundCategory.MASTER);
        float v = volume * master * 0.8f;
        if (v <= 0.001f) return;
        Pcm pcm = CACHE.computeIfAbsent(rel, k -> decode(assets, k));
        if (pcm == null || pcm.bytes.length == 0) return;
        try {
            Clip clip = AudioSystem.getClip();
            clip.open(pcm.format, pcm.bytes, 0, pcm.bytes.length);
            if (clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                FloatControl gain = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
                float db = (float) (20 * Math.log10(v));
                gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), db)));
            }
            Object done = new Object();
            clip.addLineListener(e -> {
                if (e.getType() == LineEvent.Type.STOP) {
                    clip.close();
                    synchronized (done) { done.notifyAll(); }
                }
            });
            if (wait) {
                synchronized (done) {
                    clip.start();
                    done.wait(5000);
                }
            } else {
                clip.start();
            }
        } catch (Exception e) {
            CsHudCraft.LOG.debug("[CSHudCraft] Could not play {}: {}", rel, e.toString());
        }
    }

    private static Pcm decode(CsAssets assets, String rel) {
        Path p = assets.resolve("sound/" + rel);
        if (p == null) {
            CsHudCraft.LOG.warn("[CSHudCraft] Missing CS sound {}", rel);
            return new Pcm(null, new byte[0]);
        }
        try (AudioInputStream in = AudioSystem.getAudioInputStream(new BufferedInputStream(Files.newInputStream(p)))) {
            AudioFormat f = in.getFormat();
            if (f.getEncoding() != AudioFormat.Encoding.PCM_SIGNED && f.getEncoding() != AudioFormat.Encoding.PCM_UNSIGNED) {
                AudioFormat target = new AudioFormat(f.getSampleRate(), 16, f.getChannels(), true, false);
                try (AudioInputStream conv = AudioSystem.getAudioInputStream(target, in)) {
                    return new Pcm(target, conv.readAllBytes());
                }
            }
            return new Pcm(f, in.readAllBytes());
        } catch (Exception e) {
            CsHudCraft.LOG.warn("[CSHudCraft] Could not read CS sound {}: {}", rel, e.toString());
            return new Pcm(null, new byte[0]);
        }
    }
}
