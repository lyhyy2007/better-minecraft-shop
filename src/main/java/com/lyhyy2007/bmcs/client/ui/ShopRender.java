package com.lyhyy2007.bmcs.client.ui;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 商店界面的绘制原语（自研实现）。
 *
 * <p>视觉风格参考 SDM-UI-Lib 的渲染原语清单（圆角矩形、渐变、三角、圆、描边、RGBA 颜色模型），
 * 但这里是按 Minecraft 1.21.1 的 {@link GuiGraphics#fill(int, int, int, int, int)} 重新实现的，
 * 没有复制 SDM-UI-Lib 的任何代码，也不引入它的依赖。
 *
 * <p>全部方法都是纯静态、无状态、无 GL 直调，因此不会踩到「GUI 状态没恢复」这类渲染坑。
 */
public final class ShopRender {
    // ---- 配色（ARGB）----
    public static final int PANEL_BG = 0xE610141C;
    public static final int PANEL_BG_ALT = 0xD81B2130;
    public static final int PANEL_BORDER = 0xFF3A4459;
    public static final int SLOT_BG = 0x80202836;
    public static final int SLOT_HOVER = 0x60FFFFFF;
    public static final int SLOT_SELECTED = 0x8048D07A;
    public static final int TEXT_PRIMARY = 0xFFE8ECF4;
    public static final int TEXT_SECONDARY = 0xFF9AA6B8;
    public static final int MONEY = 0xFFFFC94A;
    public static final int DANGER = 0xFFE05A5A;
    public static final int ACCENT = 0xFF4AA3FF;
    public static final int TAB_ACTIVE = 0xFF2E4A6B;
    public static final int TAB_IDLE = 0xFF1B2130;

    private ShopRender() {
    }

    public static int argb(int alpha, int red, int green, int blue) {
        return (clamp(alpha) << 24) | (clamp(red) << 16) | (clamp(green) << 8) | clamp(blue);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    /** 纯色矩形。 */
    public static void rect(GuiGraphics graphics, int x, int y, int width, int height, int argb) {
        if (width <= 0 || height <= 0) {
            return;
        }
        graphics.fill(x, y, x + width, y + height, argb);
    }

    /** 圆角矩形：中间两条矩形 + 四个角的四分之一圆扫描线。 */
    public static void roundedRect(GuiGraphics graphics, int x, int y, int width, int height, int radius, int argb) {
        if (width <= 0 || height <= 0) {
            return;
        }
        int r = Math.max(0, Math.min(radius, Math.min(width, height) / 2));
        if (r == 0) {
            rect(graphics, x, y, width, height, argb);
            return;
        }
        rect(graphics, x + r, y, width - 2 * r, height, argb);
        rect(graphics, x, y + r, r, height - 2 * r, argb);
        rect(graphics, x + width - r, y + r, r, height - 2 * r, argb);
        for (int row = 0; row < r; row++) {
            int dy = r - row;
            int dx = (int) Math.round(Math.sqrt((double) r * r - (double) dy * dy));
            int inset = r - dx;
            if (inset <= 0) {
                continue;
            }
            rect(graphics, x + inset, y + row, r - inset, 1, argb);
            rect(graphics, x + width - r, y + row, r - inset, 1, argb);
            rect(graphics, x + inset, y + height - row - 1, r - inset, 1, argb);
            rect(graphics, x + width - r, y + height - row - 1, r - inset, 1, argb);
        }
    }

    /** 描边（内部镂空）。 */
    public static void border(GuiGraphics graphics, int x, int y, int width, int height, int thickness, int argb) {
        int t = Math.max(1, thickness);
        rect(graphics, x, y, width, t, argb);
        rect(graphics, x, y + height - t, width, t, argb);
        rect(graphics, x, y + t, t, height - 2 * t, argb);
        rect(graphics, x + width - t, y + t, t, height - 2 * t, argb);
    }

    /** 垂直渐变（逐行插值）。 */
    public static void gradientVertical(GuiGraphics graphics, int x, int y, int width, int height,
                                        int topArgb, int bottomArgb) {
        if (width <= 0 || height <= 0) {
            return;
        }
        for (int row = 0; row < height; row++) {
            float t = height == 1 ? 0.0F : (float) row / (float) (height - 1);
            rect(graphics, x, y + row, width, 1, lerp(topArgb, bottomArgb, t));
        }
    }

    /** 实心圆（扫描线）。 */
    public static void circle(GuiGraphics graphics, int centerX, int centerY, int radius, int argb) {
        if (radius <= 0) {
            return;
        }
        for (int dy = -radius; dy <= radius; dy++) {
            int dx = (int) Math.round(Math.sqrt((double) radius * radius - (double) dy * dy));
            rect(graphics, centerX - dx, centerY + dy, 2 * dx + 1, 1, argb);
        }
    }

    /** 实心三角（按 y 扫描，用边函数求交）。 */
    public static void triangle(GuiGraphics graphics, int x1, int y1, int x2, int y2, int x3, int y3, int argb) {
        int minY = Math.min(y1, Math.min(y2, y3));
        int maxY = Math.max(y1, Math.max(y2, y3));
        for (int y = minY; y <= maxY; y++) {
            int left = Integer.MAX_VALUE;
            int right = Integer.MIN_VALUE;
            int[][] edges = {{x1, y1, x2, y2}, {x2, y2, x3, y3}, {x3, y3, x1, y1}};
            for (int[] edge : edges) {
                int ax = edge[0];
                int ay = edge[1];
                int bx = edge[2];
                int by = edge[3];
                if (ay == by) {
                    if (y == ay) {
                        left = Math.min(left, Math.min(ax, bx));
                        right = Math.max(right, Math.max(ax, bx));
                    }
                    continue;
                }
                if (y < Math.min(ay, by) || y > Math.max(ay, by)) {
                    continue;
                }
                double t = (double) (y - ay) / (double) (by - ay);
                int x = (int) Math.round(ax + t * (bx - ax));
                left = Math.min(left, x);
                right = Math.max(right, x);
            }
            if (left <= right) {
                rect(graphics, left, y, right - left + 1, 1, argb);
            }
        }
    }

    /** 颜色线性插值。 */
    public static int lerp(int fromArgb, int toArgb, float t) {
        float clamped = Math.max(0.0F, Math.min(1.0F, t));
        int a = (int) (alpha(fromArgb) + (alpha(toArgb) - alpha(fromArgb)) * clamped);
        int r = (int) (red(fromArgb) + (red(toArgb) - red(fromArgb)) * clamped);
        int g = (int) (green(fromArgb) + (green(toArgb) - green(fromArgb)) * clamped);
        int b = (int) (blue(fromArgb) + (blue(toArgb) - blue(fromArgb)) * clamped);
        return argb(a, r, g, b);
    }

    public static int alpha(int argb) {
        return argb >>> 24 & 0xFF;
    }

    public static int red(int argb) {
        return argb >> 16 & 0xFF;
    }

    public static int green(int argb) {
        return argb >> 8 & 0xFF;
    }

    public static int blue(int argb) {
        return argb & 0xFF;
    }
}
