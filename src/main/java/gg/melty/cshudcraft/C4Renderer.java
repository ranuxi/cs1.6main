package gg.melty.cshudcraft;

import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.RotationAxis;

/** A planted C4 drawn with CS's own w_c4.mdl from the player's install. */
public class C4Renderer extends EntityRenderer<C4Entity, C4Renderer.State> {
    public static class State extends EntityRenderState {
        float yaw;
    }

    public C4Renderer(EntityRendererFactory.Context ctx) {
        super(ctx);
    }

    @Override
    public State createRenderState() { return new State(); }

    @Override
    public void updateRenderState(C4Entity e, State s, float tickProgress) {
        super.updateRenderState(e, s, tickProgress);
        s.yaw = e.getYaw();
    }

    @Override
    public void render(State s, MatrixStack matrices, OrderedRenderCommandQueue queue, CameraRenderState camera) {
        ViewModelRenderer.Loaded l = CsHudCraft.ASSETS.loaded() ? ViewModelRenderer.model(Sheets.EQUIPMENT.get("c4").worldModel()) : null;
        if (l != null) {
            matrices.push();
            matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-s.yaw));
            GrenadeRenderer.draw(l, matrices, queue, s.light);
            matrices.pop();
        }
        super.render(s, matrices, queue, camera);
    }
}
