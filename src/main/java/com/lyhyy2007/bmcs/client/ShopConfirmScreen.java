package com.lyhyy2007.bmcs.client;

import com.lyhyy2007.bmcs.BetterMinecraftShop;
import com.lyhyy2007.bmcs.client.ui.ShopRender;
import dev.ftb.mods.ftblibrary.icon.Color4I;
import dev.ftb.mods.ftblibrary.icon.Icons;
import dev.ftb.mods.ftblibrary.ui.BaseScreen;
import dev.ftb.mods.ftblibrary.ui.SimpleTextButton;
import dev.ftb.mods.ftblibrary.ui.Theme;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * 通用确认框（删除商品用）。
 *
 * <p>只做一件事：把一句问题显示出来，玩家点「确认」才执行回调。删除是不可逆操作，
 * 所以不去赌玩家手滑 —— 每次删除都要过这一屏。
 */
public class ShopConfirmScreen extends BaseScreen {
    private static final int DIALOG_W = 244;
    private static final int DIALOG_H = 98;
    private static final String KEY = "gui." + BetterMinecraftShop.MOD_ID + ".";

    private final Component question;
    private final Runnable onConfirm;
    private final SimpleTextButton yesButton;
    private final SimpleTextButton noButton;

    public ShopConfirmScreen(Component question, Runnable onConfirm) {
        super();
        this.question = question == null ? Component.empty() : question;
        this.onConfirm = onConfirm;
        this.yesButton = SimpleTextButton.create(this,
                Component.translatable(KEY + "confirm.yes"), Icons.ACCEPT,
                button -> confirm(),
                Component.translatable(KEY + "confirm.yes.tip"));
        this.noButton = SimpleTextButton.create(this,
                Component.translatable(KEY + "confirm.no"), Icons.CANCEL,
                button -> closeGui(true),
                Component.translatable(KEY + "confirm.no.tip"));
    }

    @Override
    public boolean onInit() {
        return setFullscreen();
    }

    @Override
    public void addWidgets() {
        add(yesButton);
        add(noButton);
    }

    @Override
    public void alignWidgets() {
        int dx = (width - DIALOG_W) / 2;
        int dy = (height - DIALOG_H) / 2;
        yesButton.setPosAndSize(dx + DIALOG_W - 160, dy + DIALOG_H - 26, 72, 18);
        noButton.setPosAndSize(dx + DIALOG_W - 82, dy + DIALOG_H - 26, 72, 18);
    }

    private void confirm() {
        closeGui(true);
        if (onConfirm != null) {
            onConfirm.run();
        }
    }

    @Override
    public void drawBackground(GuiGraphics graphics, Theme theme, int x, int y, int w, int h) {
        int dx = (width - DIALOG_W) / 2;
        int dy = (height - DIALOG_H) / 2;
        ShopRender.rect(graphics, x, y, w, h, 0x66000000);
        ShopRender.roundedRect(graphics, dx, dy, DIALOG_W, DIALOG_H, 6, ShopRender.PANEL_BG);
        ShopRender.border(graphics, dx, dy, DIALOG_W, DIALOG_H, 1, ShopRender.PANEL_BORDER);
        theme.drawString(graphics, Component.translatable(KEY + "confirm.title").getString(),
                dx + 10, dy + 8, Color4I.rgb(0xFFFFFF), Theme.SHADOW);

        // 问题文本可能较长（带物品名），按对话框宽度折行后再画。
        var font = Minecraft.getInstance().font;
        List<net.minecraft.util.FormattedCharSequence> lines = font.split(question, DIALOG_W - 20);
        int lineY = dy + 26;
        for (int i = 0; i < Math.min(lines.size(), 3); i++) {
            graphics.drawString(font, lines.get(i), dx + 10, lineY, 0xD8DEE8, true);
            lineY += 10;
        }
    }
}
