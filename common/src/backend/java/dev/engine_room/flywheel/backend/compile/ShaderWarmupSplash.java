package dev.engine_room.flywheel.backend.compile;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
public final class ShaderWarmupSplash {
    private static final long DELAY = Long.getLong("crankshaft.splash.delayMs", 250) * 1_000_000L;
    private static final long INTERVAL = 50_000_000L;
    private static final int WHITE = 0xFFFFFFFF;
    private static boolean active;
    private static long start;
    private static long lastFrame;
    private static int done;
    private static int total;
    static boolean cancelled;

    private ShaderWarmupSplash() {
    }

    public static void run(Runnable warm) {
        RenderSystem.assertOnRenderThread();
        active = true;
        start = System.nanoTime();
        lastFrame = start;
        try {
            warm.run();
        } finally {
            active = false;
            cancelled = false;
        }
    }

    public static void progress(int done, int total, Runnable beforeFrame) {
        if (!active) return;
        ShaderWarmupSplash.done = done;
        ShaderWarmupSplash.total = total;
        long now = System.nanoTime();
        if (now - start < DELAY || now - lastFrame < INTERVAL) return;
        beforeFrame.run();
        RenderSystem.pollEvents();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getWindow().shouldClose()) cancelled = true;
        minecraft.renderFrame(false);
        lastFrame = System.nanoTime();
    }

    public static boolean extract(GuiGraphicsExtractor graphics) {
        if (!active) return false;
        Minecraft minecraft = Minecraft.getInstance();
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        graphics.fill(0, 0, width, height,
                minecraft.options.darkMojangStudiosBackground().get() ? 0xFF000000 : ARGB.color(255, 239, 50, 61));
        int centerX = width / 2;
        double logoHeight = Math.min(width * 0.75, height) * 0.25;
        int logoHeightHalf = (int) (logoHeight * 0.5);
        int logoWidthHalf = (int) (logoHeight * 2.0);
        int logoY = height / 2 - logoHeightHalf;
        graphics.blit(RenderPipelines.MOJANG_LOGO, LoadingOverlay.MOJANG_STUDIOS_LOGO_LOCATION, centerX - logoWidthHalf,
                logoY, -0.0625F, 0.0F, logoWidthHalf, (int) logoHeight, 120, 60, 120, 120, WHITE);
        graphics.blit(RenderPipelines.MOJANG_LOGO, LoadingOverlay.MOJANG_STUDIOS_LOGO_LOCATION, centerX, logoY,
                0.0625F, 60.0F, logoWidthHalf, (int) logoHeight, 120, 60, 120, 120, WHITE);

        int x0 = centerX - logoWidthHalf;
        int x1 = centerX + logoWidthHalf;
        int barY = (int) (height * 0.8325);
        bar(graphics, x0, barY - 5, x1, barY + 5, 1);
        int y0 = barY + 11;
        int y1 = y0 + 10;
        if (total > 0) {
            bar(graphics, x0, y0, x1, y1, Math.min(done, total) / (float) total);
        } else {
            bar(graphics, x0, y0, x1, y1, 0);
            int center = (int) ((System.nanoTime() - start) / INTERVAL % 120) - 10;
            int span = x1 - x0 - 4;
            graphics.fill(x0 + 2 + (int) (span * Mth.clamp((center - 10) / 100F, 0, 1)), y0 + 2,
                    x0 + 2 + (int) (span * Mth.clamp((center + 10) / 100F, 0, 1)), y1 - 2, WHITE);
        }
        Font font = minecraft.font;
        String label = total > 0 ? "CrankShaft shaders " + done + "/" + total : "CrankShaft shaders " + done;
        graphics.text(font, label, centerX - font.width(label) / 2, y1 + 4, WHITE, false);
        return true;
    }

    private static void bar(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, float progress) {
        int fill = Mth.ceil((x1 - x0 - 2) * progress);
        graphics.fill(x0 + 2, y0 + 2, x0 + fill, y1 - 2, WHITE);
        graphics.fill(x0 + 1, y0, x1 - 1, y0 + 1, WHITE);
        graphics.fill(x0 + 1, y1, x1 - 1, y1 - 1, WHITE);
        graphics.fill(x0, y0, x0 + 1, y1, WHITE);
        graphics.fill(x1, y0, x1 - 1, y1, WHITE);
    }

    public static final class CancellationException extends RuntimeException {
        CancellationException() {
            super("Window closed during shader warm-up", null, false, false);
        }
    }
}
