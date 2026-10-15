package gg.melty.cshudcraft;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * The CS 1.6 buy menu, usable anywhere in the world: categories on the left (keys 1-0), items on the
 * right (keys 1-9), price and money always shown, items you can't afford or haven't unlocked greyed out.
 * Every purchase is a request the server checks against your money.
 */
public final class BuyScreen extends Screen {
    static final int ORANGE = 0xFFFFB400, DIM = 0xFF7A6020, RED = 0xFFE04040, GREEN = 0xFF60D060, PANEL = 0xC8101010, EDGE = 0xFF5A4810;

    private record Item(String id, String name, int price, int level, String detail) {}

    private int category = -1;
    private int hovered = -1;
    private final List<int[]> catBoxes = new ArrayList<>();
    private final List<int[]> itemBoxes = new ArrayList<>();

    public BuyScreen() {
        super(Text.translatable("cshudcraft.buy.title"));
    }

    BuyScreen(int category) {
        this();
        this.category = category;
    }

    @Override
    public boolean shouldPause() { return false; }

    private static CsState st() { return ClientCs.state(); }

    private List<Item> items(String cat) {
        List<Item> out = new ArrayList<>();
        CsState s = st();
        if (cat.equals("ammo")) {
            for (Sheets.Ammo a : Sheets.AMMO.values()) {
                boolean used = s != null && CsServer.usesAmmo(s, a.id());
                int have = s == null ? 0 : s.reserveOf(a.id());
                out.add(new Item("ammo:" + a.id(), a.name() + " x" + a.packSize(), a.packPrice(), 1,
                        (used ? "" : "§8") + have + " / " + a.maxReserve()));
            }
            return out;
        }
        for (Sheets.Weapon w : Sheets.WEAPONS.values()) {
            if (!w.category().equals(cat)) continue;
            String detail = w.isKnife() ? Text.translatable("cshudcraft.buy.knife_detail").getString()
                    : Text.translatable("cshudcraft.buy.gun_detail", Math.round(w.damage()) + (w.pellets() > 1 ? "x" + w.pellets() : ""),
                    w.clip(), Sheets.AMMO.get(w.ammo()).name(), Math.round(60 / w.cycle())).getString();
            out.add(new Item(w.id(), w.name(), w.price(), w.unlockLevel(), detail));
        }
        for (Sheets.Equipment e : Sheets.EQUIPMENT.values()) {
            if (e.category().equals(cat)) out.add(new Item(e.id(), e.name(), e.price(), e.unlockLevel(), e.effect()));
        }
        return out;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, width, height, 0x60000000);
        CsState s = st();
        int x0 = width / 2 - 210, y0 = Math.max(10, height / 2 - 120);
        panel(ctx, x0, y0, 420, 240);
        ctx.drawTextWithShadow(textRenderer, Text.translatable("cshudcraft.buy.title"), x0 + 10, y0 + 8, ORANGE);
        String money = s == null ? "$0" : "$" + s.money;
        ctx.drawTextWithShadow(textRenderer, Text.translatable("cshudcraft.buy.money", money), x0 + 410 - textRenderer.getWidth(Text.translatable("cshudcraft.buy.money", money)), y0 + 8, GREEN);
        if (s != null) {
            String lvl = Text.translatable("cshudcraft.buy.level", s.level, s.xp, Sheets.xpForNextLevel(s.level)).getString();
            ctx.drawTextWithShadow(textRenderer, lvl, x0 + 10, y0 + 226, DIM);
        }
        ctx.drawTextWithShadow(textRenderer, Text.translatable("cshudcraft.buy.hint"), x0 + 410 - textRenderer.getWidth(Text.translatable("cshudcraft.buy.hint")), y0 + 226, DIM);

        catBoxes.clear();
        int cy = y0 + 26;
        for (int i = 0; i < Sheets.CATEGORIES.size(); i++) {
            String cat = Sheets.CATEGORIES.get(i);
            int key = (i + 1) % 10;
            boolean on = i == category;
            boolean hover = in(mouseX, mouseY, x0 + 8, cy - 2, 120, 18);
            if (on || hover) ctx.fill(x0 + 8, cy - 2, x0 + 128, cy + 14, on ? 0x80FFB400 : 0x40FFB400);
            ctx.drawTextWithShadow(textRenderer, key + "  " + Text.translatable("cshudcraft.cat." + cat).getString(), x0 + 12, cy + 2, ORANGE);
            catBoxes.add(new int[]{x0 + 8, cy - 2, 120, 18});
            cy += 19;
        }
        ctx.fill(x0 + 134, y0 + 24, x0 + 135, y0 + 218, EDGE);

        itemBoxes.clear();
        hovered = -1;
        if (category < 0) {
            ctx.drawTextWithShadow(textRenderer, Text.translatable("cshudcraft.buy.pick"), x0 + 144, y0 + 30, DIM);
            super.render(ctx, mouseX, mouseY, delta);
            return;
        }
        List<Item> list = items(Sheets.CATEGORIES.get(category));
        int iy = y0 + 26;
        for (int i = 0; i < list.size() && i < 9; i++) {
            Item it = list.get(i);
            boolean locked = s == null || s.level < it.level;
            boolean poor = s == null || s.money < it.price;
            boolean hover = in(mouseX, mouseY, x0 + 140, iy - 2, 272, 20);
            if (hover) { ctx.fill(x0 + 140, iy - 2, x0 + 412, iy + 18, 0x40FFB400); hovered = i; }
            int col = locked || poor ? DIM : ORANGE;
            ctx.drawTextWithShadow(textRenderer, (i + 1) + "  " + it.name, x0 + 144, iy, col);
            String right = locked ? Text.translatable("cshudcraft.buy.locked", it.level).getString() : "$" + it.price;
            ctx.drawTextWithShadow(textRenderer, right, x0 + 408 - textRenderer.getWidth(right), iy, locked ? RED : poor ? RED : GREEN);
            ctx.drawText(textRenderer, it.detail, x0 + 156, iy + 10, 0xFF9A8A60, false);
            itemBoxes.add(new int[]{x0 + 140, iy - 2, 272, 20});
            iy += 21;
        }
        if (Sheets.CATEGORIES.get(category).equals("ammo")) {
            ctx.drawTextWithShadow(textRenderer, Text.translatable("cshudcraft.buy.quick_ammo"), x0 + 144, y0 + 212, DIM);
        }
        super.render(ctx, mouseX, mouseY, delta);
    }

    private static void panel(DrawContext ctx, int x, int y, int w, int h) {
        ctx.fill(x, y, x + w, y + h, PANEL);
        ctx.fill(x, y, x + w, y + 1, EDGE);
        ctx.fill(x, y + h - 1, x + w, y + h, EDGE);
        ctx.fill(x, y, x + 1, y + h, EDGE);
        ctx.fill(x + w - 1, y, x + w, y + h, EDGE);
        ctx.fill(x + 1, y + 20, x + w - 1, y + 21, EDGE);
    }

    private static boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private void buy(int index) {
        if (category < 0) return;
        List<Item> list = items(Sheets.CATEGORIES.get(category));
        if (index < 0 || index >= list.size()) return;
        ClientCs.send("buy", list.get(index).id);
        CsSounds.play(CsHudCraft.ASSETS, "common/wpn_select.wav", 0.6f);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int key = input.key();
        if (key == GLFW.GLFW_KEY_ESCAPE || CsHudCraft.BUY.matchesKey(input)) {
            if (category >= 0 && key != GLFW.GLFW_KEY_ESCAPE) { category = -1; return true; }
            close();
            return true;
        }
        if (key == GLFW.GLFW_KEY_BACKSPACE) { category = -1; return true; }
        if (key >= GLFW.GLFW_KEY_0 && key <= GLFW.GLFW_KEY_9) {
            int n = key - GLFW.GLFW_KEY_0;
            if (category < 0) {
                int c = n == 0 ? 9 : n - 1;
                if (c < Sheets.CATEGORIES.size()) {
                    category = c;
                    CsSounds.play(CsHudCraft.ASSETS, "common/wpn_moveselect.wav", 0.5f);
                }
            } else if (n == 0) {
                category = -1;
            } else {
                buy(n - 1);
            }
            return true;
        }
        if (CsHudCraft.AMMO_PRIMARY.matchesKey(input)) { ClientCs.send("buy", "ammo_primary"); return true; }
        if (CsHudCraft.AMMO_SECONDARY.matchesKey(input)) { ClientCs.send("buy", "ammo_secondary"); return true; }
        return super.keyPressed(input);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        for (int i = 0; i < catBoxes.size(); i++) {
            int[] b = catBoxes.get(i);
            if (in(click.x(), click.y(), b[0], b[1], b[2], b[3])) {
                category = i;
                CsSounds.play(CsHudCraft.ASSETS, "common/wpn_moveselect.wav", 0.5f);
                return true;
            }
        }
        for (int i = 0; i < itemBoxes.size(); i++) {
            int[] b = itemBoxes.get(i);
            if (in(click.x(), click.y(), b[0], b[1], b[2], b[3])) { buy(i); return true; }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public void close() {
        MinecraftClient.getInstance().setScreen(null);
    }
}
