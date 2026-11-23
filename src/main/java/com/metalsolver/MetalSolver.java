package com.metalsolver;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.StringUtils;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MetalSolver {

    // TODO: action bar ka exact text milne par is regex ko badlo.
    private static final Pattern DIST =
            Pattern.compile("TREASURE:\\s*([0-9]+(?:\\.[0-9]+)?)m");

    private static final long SETTLE_MS = 1500;     // itni der ruko, phir aane wali reading lo
    private static final double STILL_EPS = 0.03;   // isse kam hilna = ruka hua
    private static final double MIN_MOVE = 4.0;     // do readings ke beech minimum movement
    private static final double FOUND_RANGE = 3.0;  // chest ke itne paas aane par reset

    private static class Sample {
        final double x, z, r;
        Sample(double x, double z, double r) { this.x = x; this.z = z; this.r = r; }
    }

    private final List<Sample> samples = new ArrayList<Sample>();
    private final List<double[]> candidates = new ArrayList<double[]>();
    private double[] locked = null;
    private boolean enabled = true;

    // movement tracking
    private double lastX, lastZ;
    private boolean hasLast = false;
    private long stillSince = 0;
    private boolean takenThisStop = false;

    // latest action bar reading
    private double latestR = -1;
    private long latestTime = 0;

    @SubscribeEvent
    public void onActionBar(ClientChatReceivedEvent e) {
        if (e.type != 2) return; // 2 = action bar
        String text = StringUtils.stripControlCodes(e.message.getUnformattedText());
        Matcher m = DIST.matcher(text);
        if (!m.find()) return;
        latestR = Double.parseDouble(m.group(1));
        latestTime = System.currentTimeMillis();
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END || !enabled || locked != null) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return;

        double px = mc.thePlayer.posX, pz = mc.thePlayer.posZ;
        long now = System.currentTimeMillis();

        if (!hasLast) {
            lastX = px; lastZ = pz; hasLast = true; stillSince = now;
            return;
        }

        boolean moved = Math.hypot(px - lastX, pz - lastZ) > STILL_EPS;
        lastX = px; lastZ = pz;

        if (moved) {            // chal raha hai: wait reset
            stillSince = now;
            takenThisStop = false;
            return;
        }

        if (takenThisStop) return;

        // ruke hue SETTLE_MS ho gaye aur uske baad ki nayi reading aa gayi?
        if (now - stillSince >= SETTLE_MS && latestTime >= stillSince + SETTLE_MS) {
            if (!samples.isEmpty()) {
                Sample last = samples.get(samples.size() - 1);
                if (Math.hypot(px - last.x, pz - last.z) < MIN_MOVE) {
                    takenThisStop = true;
                    mc.thePlayer.addChatMessage(new ChatComponentText("\u00a7c[Solver] Thoda aur door jao"));
                    return;
                }
            }
            takenThisStop = true;
            samples.add(new Sample(px, pz, latestR));
            mc.thePlayer.addChatMessage(new ChatComponentText(
                    "\u00a7e[Solver] Reading " + samples.size() + ": " + latestR + "m"));
            solve();
        }
    }

    private void solve() {
        candidates.clear();
        if (samples.size() < 2) return;

        if (samples.size() == 2) {
            candidates.addAll(intersect(samples.get(0), samples.get(1)));
            return;
        }

        Sample s0 = samples.get(0);
        double range = s0.r + 10;
        double[] best = bestFit(s0.x - range, s0.x + range, s0.z - range, s0.z + range, 0.5);
        best = bestFit(best[0] - 1, best[0] + 1, best[1] - 1, best[1] + 1, 0.05);

        candidates.add(new double[]{best[0], best[1]});
        double rmse = Math.sqrt(best[2] / samples.size());
        if (rmse < 2.0) locked = new double[]{best[0], best[1]};
    }

    private double[] bestFit(double x0, double x1, double z0, double z1, double step) {
        double bestErr = Double.MAX_VALUE, bx = x0, bz = z0;
        for (double x = x0; x <= x1; x += step) {
            for (double z = z0; z <= z1; z += step) {
                double err = 0;
                for (Sample s : samples) {
                    double d = Math.hypot(x - s.x, z - s.z) - s.r;
                    err += d * d;
                }
                if (err < bestErr) { bestErr = err; bx = x; bz = z; }
            }
        }
        return new double[]{bx, bz, bestErr};
    }

    private static List<double[]> intersect(Sample a, Sample b) {
        List<double[]> out = new ArrayList<double[]>();
        double dx = b.x - a.x, dz = b.z - a.z;
        double d = Math.sqrt(dx * dx + dz * dz);
        double tol = 1.5;
        if (d == 0 || d > a.r + b.r + tol || d < Math.abs(a.r - b.r) - tol) return out;

        double l = (a.r * a.r - b.r * b.r + d * d) / (2 * d);
        double h = Math.sqrt(Math.max(0, a.r * a.r - l * l));
        double mx = a.x + l * dx / d, mz = a.z + l * dz / d;

        out.add(new double[]{mx + h * dz / d, mz - h * dx / d});
        if (h > 0.01) out.add(new double[]{mx - h * dz / d, mz + h * dx / d});
        return out;
    }

    private static void fillBox(AxisAlignedBB b, int r, int g, int bl, int a) {
        Tessellator t = Tessellator.getInstance();
        WorldRenderer w = t.getWorldRenderer();
        w.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        // neeche
        w.pos(b.minX, b.minY, b.minZ).color(r, g, bl, a).endVertex();
        w.pos(b.maxX, b.minY, b.minZ).color(r, g, bl, a).endVertex();
        w.pos(b.maxX, b.minY, b.maxZ).color(r, g, bl, a).endVertex();
        w.pos(b.minX, b.minY, b.maxZ).color(r, g, bl, a).endVertex();
        // upar
        w.pos(b.minX, b.maxY, b.minZ).color(r, g, bl, a).endVertex();
        w.pos(b.minX, b.maxY, b.maxZ).color(r, g, bl, a).endVertex();
        w.pos(b.maxX, b.maxY, b.maxZ).color(r, g, bl, a).endVertex();
        w.pos(b.maxX, b.maxY, b.minZ).color(r, g, bl, a).endVertex();
        // sides
        w.pos(b.minX, b.minY, b.minZ).color(r, g, bl, a).endVertex();
        w.pos(b.minX, b.maxY, b.minZ).color(r, g, bl, a).endVertex();
        w.pos(b.maxX, b.maxY, b.minZ).color(r, g, bl, a).endVertex();
        w.pos(b.maxX, b.minY, b.minZ).color(r, g, bl, a).endVertex();

        w.pos(b.minX, b.minY, b.maxZ).color(r, g, bl, a).endVertex();
        w.pos(b.maxX, b.minY, b.maxZ).color(r, g, bl, a).endVertex();
        w.pos(b.maxX, b.maxY, b.maxZ).color(r, g, bl, a).endVertex();
        w.pos(b.minX, b.maxY, b.maxZ).color(r, g, bl, a).endVertex();

        w.pos(b.minX, b.minY, b.minZ).color(r, g, bl, a).endVertex();
        w.pos(b.minX, b.minY, b.maxZ).color(r, g, bl, a).endVertex();
        w.pos(b.minX, b.maxY, b.maxZ).color(r, g, bl, a).endVertex();
        w.pos(b.minX, b.maxY, b.minZ).color(r, g, bl, a).endVertex();

        w.pos(b.maxX, b.minY, b.minZ).color(r, g, bl, a).endVertex();
        w.pos(b.maxX, b.maxY, b.minZ).color(r, g, bl, a).endVertex();
        w.pos(b.maxX, b.maxY, b.maxZ).color(r, g, bl, a).endVertex();
        w.pos(b.maxX, b.minY, b.maxZ).color(r, g, bl, a).endVertex();
        t.draw();
    }

    @SubscribeEvent
    public void onRender(RenderWorldLastEvent e) {
        if (!enabled || candidates.isEmpty()) return;
        Minecraft mc = Minecraft.getMinecraft();
        Entity view = mc.getRenderViewEntity();
        if (view == null || mc.thePlayer == null) return;

        if (locked != null
                && Math.hypot(mc.thePlayer.posX - locked[0], mc.thePlayer.posZ - locked[1]) < FOUND_RANGE) {
            reset();
            return;
        }

        float pt = e.partialTicks;
        double vx = view.lastTickPosX + (view.posX - view.lastTickPosX) * pt;
        double vy = view.lastTickPosY + (view.posY - view.lastTickPosY) * pt;
        double vz = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * pt;

        GlStateManager.pushMatrix();
        GlStateManager.disableTexture2D();
        GlStateManager.disableDepth();
        GlStateManager.disableLighting();
        GlStateManager.disableCull();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
        GL11.glLineWidth(5f);

        for (double[] c : candidates) {
            AxisAlignedBB box = new AxisAlignedBB(c[0] - 0.5, 0, c[1] - 0.5, c[0] + 0.5, 255, c[1] + 0.5)
                    .offset(-vx, -vy, -vz);
            if (locked != null) {
                fillBox(box, 0, 255, 0, 90);                                  // green fill
                RenderGlobal.drawOutlinedBoundingBox(box, 0, 255, 0, 255);    // green outline
            } else {
                fillBox(box, 255, 255, 0, 90);                                // yellow fill
                RenderGlobal.drawOutlinedBoundingBox(box, 255, 255, 0, 255);  // yellow outline
            }
        }

        GlStateManager.enableCull();
        GlStateManager.enableDepth();
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.popMatrix();
    }

    @SubscribeEvent
    public void onKey(InputEvent.KeyInputEvent e) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return;

        if (MetalSolverMod.resetKey.isPressed()) {
            reset();
            mc.thePlayer.addChatMessage(new ChatComponentText("\u00a7e[Solver] Reset"));
        }
        if (MetalSolverMod.toggleKey.isPressed()) {
            enabled = !enabled;
            if (!enabled) reset();
            mc.thePlayer.addChatMessage(new ChatComponentText("\u00a7e[Solver] " + (enabled ? "ON" : "OFF")));
        }
    }

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load e) {
        reset();
    }

    private void reset() {
        samples.clear();
        candidates.clear();
        locked = null;
        takenThisStop = false;
        hasLast = false;
        latestR = -1;
        latestTime = 0;
    }
}
