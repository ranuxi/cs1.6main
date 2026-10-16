package gg.melty.cshudcraft;

import gg.melty.cshudcraft.mdl.MdlModel;
import gg.melty.cshudcraft.mdl.MdlPose;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import net.minecraft.client.render.entity.feature.FeatureRendererContext;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Arm;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The CS weapon in a player's hand as others see it (and as you see yourself in third person): CS's own
 * p_*.mdl from the player's install, held in the Minecraft arm. The p_ model is posed once and every
 * vertex is moved into the space of the hand bone it hangs from ("Bip01 R Hand" / "Bip01 L Hand"),
 * so the weapon follows the Minecraft arm the way it followed the CS player's hand.
 */
public class ThirdPersonWeapon extends FeatureRenderer<PlayerEntityRenderState, PlayerEntityModel> {
    /** What the player holds (CsNet.HELD), copied into the render state by PlayerEntityRendererMixin. */
    public static final RenderStateDataKey<String> HELD = RenderStateDataKey.create(() -> "cshudcraft:held");
    /** GoldSrc units to blocks: a 72-unit CS player is 1.8 blocks tall. */
    static final float SCALE = 1f / 40f;

    /** Triangles of one skin in hand space: x,y,z, nx,ny,nz, u,v per vertex, 3 vertices per triangle. */
    record Batch(Identifier texture, float[] verts) {}
    /** A p_ model baked for each hand; a hand's list is empty when nothing hangs from it. */
    record Baked(List<Batch> right, List<Batch> left) {}

    private static final Map<String, Baked> BAKED = new HashMap<>();
    private static int framesDrawn;

    public ThirdPersonWeapon(FeatureRendererContext<PlayerEntityRenderState, PlayerEntityModel> ctx) {
        super(ctx);
    }

    public static int framesDrawn() { return framesDrawn; }

    /** The held id from CsNet.HELD, or "" (nothing CS in hand). */
    public static String heldOf(PlayerEntity p) {
        String h = p.getAttached(CsNet.HELD);
        return h == null ? "" : h;
    }

    /** CS's p_ model for a weapons.json / equipment.json id: v_ak47.mdl -> p_ak47.mdl. */
    static String modelFor(String id) {
        Sheets.Weapon w = Sheets.weapon(id);
        String v = w != null ? w.model() : Sheets.EQUIPMENT.containsKey(id) ? Sheets.EQUIPMENT.get(id).model() : null;
        if (v == null || !v.startsWith("v_")) return null;
        return "p_" + v.substring(2);
    }

    /** Rifles and other two-handed guns are held with both arms forward; pistols with one; knives and grenades at the side. */
    public static BipedEntityModel.ArmPose[] poses(String id) {
        Sheets.Weapon w = Sheets.weapon(id);
        if (w == null || w.isKnife()) return new BipedEntityModel.ArmPose[]{BipedEntityModel.ArmPose.ITEM, BipedEntityModel.ArmPose.EMPTY};
        if (id.equals("elite")) return new BipedEntityModel.ArmPose[]{BipedEntityModel.ArmPose.CROSSBOW_HOLD, BipedEntityModel.ArmPose.CROSSBOW_HOLD};
        if (w.slot() == CsState.SLOT_PISTOL) return new BipedEntityModel.ArmPose[]{BipedEntityModel.ArmPose.CROSSBOW_HOLD, BipedEntityModel.ArmPose.EMPTY};
        return new BipedEntityModel.ArmPose[]{BipedEntityModel.ArmPose.CROSSBOW_HOLD, BipedEntityModel.ArmPose.CROSSBOW_HOLD};
    }

    @Override
    public void render(MatrixStack matrices, OrderedRenderCommandQueue queue, int light, PlayerEntityRenderState state, float limbAngle, float limbDistance) {
        String id = state.getData(HELD);
        if (id == null || id.isEmpty() || state.invisible || !CsHudCraft.ASSETS.loaded()) return;
        Baked b = baked(id);
        if (b == null) return;
        if (!b.right().isEmpty()) drawHand(b.right(), Arm.RIGHT, state, matrices, queue, light);
        if (!b.left().isEmpty()) drawHand(b.left(), Arm.LEFT, state, matrices, queue, light);
        framesDrawn++;
    }

    private void drawHand(List<Batch> batches, Arm arm, PlayerEntityRenderState state, MatrixStack matrices, OrderedRenderCommandQueue queue, int light) {
        matrices.push();
        getContextModel().setArmAngle(state, arm, matrices);
        // arm space: +Y runs down the arm to the hand, -Z is the front of the body
        boolean left = arm == Arm.LEFT;
        matrices.translate((left ? 1 : -1) / 16f, 10f / 16f, 0f);
        // hand bone space: +X runs on from the forearm (down the Minecraft arm, the barrel's way), +Z is the top of the gun
        matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(90f));
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(180f));
        if (left) matrices.scale(1f, -1f, 1f); // Biped's left hand bone is the right one mirrored
        matrices.scale(SCALE, SCALE, SCALE);
        for (Batch batch : batches) {
            float[] out = batch.verts();
            queue.submitCustom(matrices, RenderLayers.entityCutoutNoCull(batch.texture()), (entry, vc) -> {
                for (int t = 0; t + 24 <= out.length; t += 24) {
                    for (int k = 0; k < 4; k++) {
                        int o = t + Math.min(k, 2) * 8;
                        vc.vertex(entry, out[o], out[o + 1], out[o + 2]).color(255, 255, 255, 255).texture(out[o + 6], out[o + 7])
                                .overlay(OverlayTexture.DEFAULT_UV).light(light).normal(entry, out[o + 3], out[o + 4], out[o + 5]);
                    }
                }
            });
        }
        matrices.pop();
    }

    /** Poses the p_ model at the start of its only sequence and moves every vertex into the space of its hand. */
    static Baked baked(String id) {
        if (BAKED.containsKey(id)) return BAKED.get(id);
        Baked result = null;
        String file = modelFor(id);
        ViewModelRenderer.Loaded l = file == null ? null : ViewModelRenderer.model(file);
        if (l != null) {
            MdlModel m = l.model();
            float[][] bones = MdlPose.compute(m, 0, 0);
            int rHand = boneIndex(m, "Bip01 R Hand"), lHand = boneIndex(m, "Bip01 L Hand");
            int[] handOf = new int[m.bones.size()];
            for (int i = 0; i < handOf.length; i++) handOf[i] = handAbove(m, i, rHand, lHand);
            Map<Integer, List<Float>> right = new HashMap<>(), left = new HashMap<>();
            float[] p = new float[3], n = new float[3];
            for (MdlModel.Part part : m.parts) {
                for (MdlModel.Mesh mesh : part.meshes()) {
                    int skin = mesh.skinRef() < m.skinRefs.length ? m.skinRefs[mesh.skinRef()] : mesh.skinRef();
                    if (skin < 0 || skin >= l.textures().length) continue;
                    MdlModel.Texture tex = m.textures.get(skin);
                    int[] tris = mesh.tris();
                    for (int i = 0; i + 12 <= tris.length; i += 12) {
                        int hand = handOf[part.vertBone()[tris[i]]];
                        if (hand < 0) continue;
                        List<Float> dst = (hand == lHand ? left : right).computeIfAbsent(skin, s -> new ArrayList<>());
                        float[] h = bones[hand];
                        for (int k = 0; k < 3; k++) {
                            int vi = tris[i + k * 4], ni = tris[i + k * 4 + 1];
                            MdlPose.transform(bones[part.vertBone()[vi]], part.verts()[vi * 3], part.verts()[vi * 3 + 1], part.verts()[vi * 3 + 2], p);
                            toLocal(h, p[0] - h[3], p[1] - h[7], p[2] - h[11], dst);
                            if (ni >= 0 && ni * 3 + 2 < part.norms().length) {
                                MdlPose.rotate(bones[part.normBone()[ni]], part.norms()[ni * 3], part.norms()[ni * 3 + 1], part.norms()[ni * 3 + 2], n);
                                toLocal(h, n[0], n[1], n[2], dst);
                            } else {
                                dst.add(0f); dst.add(0f); dst.add(1f);
                            }
                            dst.add(tris[i + k * 4 + 2] / (float) tex.width());
                            dst.add(tris[i + k * 4 + 3] / (float) tex.height());
                        }
                    }
                }
            }
            result = new Baked(batches(right, l), batches(left, l));
        }
        BAKED.put(id, result);
        return result;
    }

    /** Rotation part of a hand bone's matrix, inverted (it is orthonormal, so transposed). */
    private static void toLocal(float[] h, float x, float y, float z, List<Float> dst) {
        dst.add(h[0] * x + h[4] * y + h[8] * z);
        dst.add(h[1] * x + h[5] * y + h[9] * z);
        dst.add(h[2] * x + h[6] * y + h[10] * z);
    }

    private static List<Batch> batches(Map<Integer, List<Float>> bySkin, ViewModelRenderer.Loaded l) {
        List<Batch> out = new ArrayList<>();
        bySkin.forEach((skin, list) -> {
            float[] a = new float[list.size()];
            for (int i = 0; i < a.length; i++) a[i] = list.get(i);
            out.add(new Batch(l.textures()[skin], a));
        });
        return out;
    }

    private static int boneIndex(MdlModel m, String name) {
        for (int i = 0; i < m.bones.size(); i++) if (m.bones.get(i).name().equals(name)) return i;
        return -1;
    }

    /** The hand bone a bone hangs from (itself included), or -1 for the body. */
    private static int handAbove(MdlModel m, int bone, int rHand, int lHand) {
        for (int b = bone, guard = 0; b >= 0 && guard < 128; b = m.bones.get(b).parent(), guard++) {
            if (b == rHand || b == lHand) return b;
        }
        return -1;
    }
}
