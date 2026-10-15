package gg.melty.cshudcraft;

import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.List;

/** Player state attachment and the two payloads between client and server. */
public final class CsNet {
    private CsNet() {}

    /** Saved with the player, kept through death (the respawn handler resets the loadout), synced to its owner. */
    public static final AttachmentType<CsState> STATE = AttachmentRegistry.create(
            Identifier.of(CsHudCraft.MOD_ID, "state"),
            b -> b.persistent(CsState.CODEC).copyOnDeath().syncWith(CsState.PACKET_CODEC, AttachmentSyncPredicate.targetOnly()));

    /**
     * Client -> server. action: fire, knife, reload_start, reload_finish, select, buy, mode, throw.
     * arg: weapon / item id or extra word. nums: directions (x,y,z per pellet) or other numbers.
     */
    public record Action(String action, String arg, List<Float> nums) implements CustomPayload {
        public static final Id<Action> ID = new Id<>(Identifier.of(CsHudCraft.MOD_ID, "action"));
        public static final PacketCodec<RegistryByteBuf, Action> CODEC = PacketCodec.tuple(
                PacketCodecs.STRING, Action::action,
                PacketCodecs.STRING, Action::arg,
                PacketCodecs.FLOAT.collect(PacketCodecs.toList()), Action::nums,
                Action::new);

        @Override
        public Id<? extends CustomPayload> getId() { return ID; }
    }

    /**
     * Server -> client. kind: sound (arg = CS sound path, nums = x,y,z,volume), flash (nums = strength),
     * award (arg = reason, nums = money, xp), hit (nums = 1 if headshot), denied (arg = reason).
     */
    public record Event(String kind, String arg, List<Float> nums) implements CustomPayload {
        public static final Id<Event> ID = new Id<>(Identifier.of(CsHudCraft.MOD_ID, "event"));
        public static final PacketCodec<RegistryByteBuf, Event> CODEC = PacketCodec.tuple(
                PacketCodecs.STRING, Event::kind,
                PacketCodecs.STRING, Event::arg,
                PacketCodecs.FLOAT.collect(PacketCodecs.toList()), Event::nums,
                Event::new);

        @Override
        public Id<? extends CustomPayload> getId() { return ID; }
    }

    static void register() {
        PayloadTypeRegistry.playC2S().register(Action.ID, Action.CODEC);
        PayloadTypeRegistry.playS2C().register(Event.ID, Event.CODEC);
    }
}
