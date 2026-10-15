package gg.melty.cshudcraft;

import gg.melty.cshudcraft.mdl.MdlModel;
import gg.melty.cshudcraft.mdl.MdlPose;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.RotationAxis;

/** A thrown grenade drawn with its CS world model (w_*.mdl) from the player's install, tumbling in flight. */
public class GrenadeRenderer extends EntityRenderer<GrenadeEntity, GrenadeRenderer.State> {
    static final float SCALE = 1f / 36f;

    public static class State extends EntityRenderState {
        String kind = "hegrenade";
        float spin;
        boolean moving;
    }

    public GrenadeRenderer(EntityRendererFactory.Context ctx) {
        super(ctx);
    }

    @Override
    public State createRenderState() { return new State(); }

    @Override
    public void updateRenderState(GrenadeEntity e, State s, float tickProgress) {
        super.updateRenderState(e, s, tickProgress);
        s.kind = e.getKind();
        s.spin = (e.age + tickProgress) * 25f;
        s.moving = e.getVelocity().lengthSquared() > 0.003;
    }

    @Override
    public void render(State s, MatrixStack matrices, OrderedRenderCommandQueue queue, CameraRenderState camera) {
        Sheets.Equipment g = Sheets.EQUIPMENT.get(s.kind);
        ViewModelRenderer.Loaded l = g == null || !CsHudCraft.ASSETS.loaded() ? null : ViewModelRenderer.model(g.worldModel());
        if (l != null) {
            MdlModel m = l.model();
            float[][] bones = MdlPose.compute(m, 0, 0);
            matrices.push();
            if (s.moving) matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(s.spin));
            int light = s.light;
            float[] p = new float[3], n = new float[3];
            for (MdlModel.Part part : m.parts) {
                for (MdlModel.Mesh mesh : part.meshes()) {
                    int skin = mesh.skinRef() < m.skinRefs.length ? m.skinRefs[mesh.skinRef()] : mesh.skinRef();
                    if (skin < 0 || skin >= l.textures().length) continue;
                    MdlModel.Texture tex = m.textures.get(skin);
                    int[] tris = mesh.tris();
                    float[] out = new float[(tris.length / 4) * 8];
                    for (int i = 0, o = 0; i < tris.length; i += 4, o += 8) {
                        int vi = tris[i], ni = tris[i + 1];
                        MdlPose.transform(bones[part.vertBone()[vi]], part.verts()[vi * 3], part.verts()[vi * 3 + 1], part.verts()[vi * 3 + 2], p);
                        // GoldSrc Z up -> Minecraft Y up
                        out[o] = p[0] * SCALE; out[o + 1] = p[2] * SCALE; out[o + 2] = -p[1] * SCALE;
                        if (ni >= 0 && ni * 3 + 2 < part.norms().length) {
                            MdlPose.rotate(bones[part.normBone()[ni]], part.norms()[ni * 3], part.norms()[ni * 3 + 1], part.norms()[ni * 3 + 2], n);
                            out[o + 3] = n[0]; out[o + 4] = n[2]; out[o + 5] = -n[1];
                        } else {
                            out[o + 4] = 1;
                        }
                        out[o + 6] = tris[i + 2] / (float) tex.width();
                        out[o + 7] = tris[i + 3] / (float) tex.height();
                    }
                    queue.submitCustom(matrices, RenderLayers.entityCutoutNoCull(l.textures()[skin]), (entry, vc) -> {
                        for (int t = 0; t + 24 <= out.length; t += 24) {
                            for (int k = 0; k < 4; k++) {
                                int o = t + Math.min(k, 2) * 8;
                                vc.vertex(entry, out[o], out[o + 1], out[o + 2]).color(255, 255, 255, 255).texture(out[o + 6], out[o + 7])
                                        .overlay(OverlayTexture.DEFAULT_UV).light(light).normal(entry, out[o + 3], out[o + 4], out[o + 5]);
                            }
                        }
                    });
                }
            }
            matrices.pop();
        }
        super.render(s, matrices, queue, camera);
    }
}
