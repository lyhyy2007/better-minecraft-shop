package com.lyhyy2007.bmcs.client;

import com.lyhyy2007.bmcs.BetterMinecraftShop;
import com.lyhyy2007.bmcs.client.ui.ShopRender;
import com.lyhyy2007.bmcs.config.ShopSide;
import com.lyhyy2007.bmcs.network.ShopCategoryCreatePayload;
import com.lyhyy2007.bmcs.network.ShopCategoryUpdatePayload;
import dev.ftb.mods.ftblibrary.icon.Color4I;
import dev.ftb.mods.ftblibrary.icon.Icon;
import dev.ftb.mods.ftblibrary.icon.Icons;
import dev.ftb.mods.ftblibrary.icon.ItemIcon;
import dev.ftb.mods.ftblibrary.ui.BaseScreen;
import dev.ftb.mods.ftblibrary.ui.SimpleTextButton;
import dev.ftb.mods.ftblibrary.ui.TextBox;
import dev.ftb.mods.ftblibrary.ui.Theme;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 游戏内「新建 / 编辑分类」界面。
 *
 * <p>分类在磁盘上就是一个 JSON 文件，文件名（去掉 .json）就是分类显示名，所以这里改名字
 * 等于改文件名：服务端会把旧文件重命名成新名字。分类名重复会被服务端拒绝（Q11 的约定：
 * 文件名严格等于显示名，不自动加后缀）。
 *
 * <p>图标走 {@link IconPickerScreen}：全量已注册物品里搜索、点一下就选中。
 */
public class ShopCategoryEditorScreen extends BaseScreen implements EditorResultSink {
    private static final int DIALOG_W = 336;
    private static final int DIALOG_H = 206;
    private static final int MAX_NAME = 64;
    private static final String KEY = "gui." + BetterMinecraftShop.MOD_ID + ".";

    private final ShopSide side;
    /** 空串表示「新建」；非空表示改这个分类。 */
    private final String categoryId;
    private final int revision;
    private final Icon categoryIcon;

    private final TextBox nameBox;
    private final SimpleTextButton iconButton;
    private final SimpleTextButton saveButton;
    private final SimpleTextButton cancelButton;

    private String iconId;
    private Component status = Component.empty();
    private boolean saving;
    private boolean saved;

    public ShopCategoryEditorScreen(ShopSide side, String categoryId, String name, String iconId, int revision) {
        super();
        this.side = side == null ? ShopSide.BUY : side;
        this.categoryId = categoryId == null ? "" : categoryId;
        this.iconId = iconId == null ? "" : iconId;
        this.revision = revision;

        this.nameBox = new TextBox(this);
        this.nameBox.setMaxLength(MAX_NAME);
        this.nameBox.setText(name == null ? "" : name);

        this.iconButton = SimpleTextButton.create(this,
                iconLabel(), Icons.SETTINGS,
                button -> openIconPicker(),
                Component.translatable(KEY + "category.icon.tip"));
        this.saveButton = SimpleTextButton.create(this,
                Component.translatable(KEY + "category.save"), Icons.ACCEPT,
                button -> submit(),
                Component.translatable(KEY + "category.save.tip"));
        this.cancelButton = SimpleTextButton.create(this,
                Component.translatable(KEY + "editor.cancel"), Icons.CANCEL,
                button -> closeGui(true),
                Component.translatable(KEY + "editor.cancel.tip"));

        this.categoryIcon = iconIcon();
    }

    private Component iconLabel() {
        return iconId.isBlank()
                ? Component.translatable(KEY + "category.icon.none")
                : Component.literal(iconId);
    }

    private Icon iconIcon() {
        if (iconId.isBlank()) {
            return Icon.empty();
        }
        ResourceLocation id = ResourceLocation.tryParse(iconId);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            return Icon.empty();
        }
        return ItemIcon.getItemIcon(new ItemStack(BuiltInRegistries.ITEM.get(id)));
    }

    @Override
    public boolean onInit() {
        return setFullscreen();
    }

    @Override
    public void addWidgets() {
        add(nameBox);
        add(iconButton);
        add(saveButton);
        add(cancelButton);
    }

    @Override
    public void alignWidgets() {
        int dx = (width - DIALOG_W) / 2;
        int dy = (height - DIALOG_H) / 2;
        nameBox.setPosAndSize(dx + 76, dy + 34, DIALOG_W - 128, 18);
        iconButton.setPosAndSize(dx + 76, dy + 60, DIALOG_W - 128, 18);
        saveButton.setPosAndSize(dx + DIALOG_W - 172, dy + DIALOG_H - 28, 76, 18);
        cancelButton.setPosAndSize(dx + DIALOG_W - 88, dy + DIALOG_H - 28, 76, 18);
    }

    private void openIconPicker() {
        String currentName = nameBox.getText();
        new IconPickerScreen(picked -> new ShopCategoryEditorScreen(side, categoryId, currentName, picked, revision)
                .openGui()).openGui();
    }

    private void submit() {
        if (saving) {
            return;
        }
        String name = nameBox.getText() == null ? "" : nameBox.getText().trim();
        if (name.isEmpty()) {
            ShopClientData.setStatus(Component.translatable(KEY + "category.status.name_blank"));
            status = Component.translatable(KEY + "category.status.name_blank");
            return;
        }
        if (name.length() > MAX_NAME) {
            status = Component.translatable(KEY + "category.status.name_too_long", MAX_NAME);
            return;
        }
        if (revision < 0) {
            status = Component.translatable(KEY + "editor.status.no_data");
            return;
        }
        saving = true;
        status = Component.translatable(KEY + "editor.status.saving");
        if (categoryId.isBlank()) {
            PacketDistributor.sendToServer(new ShopCategoryCreatePayload(side, name, iconId, revision));
        } else {
            PacketDistributor.sendToServer(new ShopCategoryUpdatePayload(side, categoryId, name, iconId, revision));
        }
    }

    @Override
    public void onEditorResult(boolean success, Component message) {
        saving = false;
        status = message;
        if (success) {
            saved = true;
            // 服务端写盘后会重开商店并自动选中这个分类，这里直接关掉编辑器。
            closeGui(true);
        }
    }

    @Override
    public void drawBackground(GuiGraphics graphics, Theme theme, int x, int y, int w, int h) {
        int dx = (width - DIALOG_W) / 2;
        int dy = (height - DIALOG_H) / 2;
        ShopRender.rect(graphics, x, y, w, h, 0x66000000);
        ShopRender.roundedRect(graphics, dx, dy, DIALOG_W, DIALOG_H, 6, ShopRender.PANEL_BG);
        ShopRender.border(graphics, dx, dy, DIALOG_W, DIALOG_H, 1, ShopRender.PANEL_BORDER);
        ShopRender.gradientVertical(graphics, dx + 1, dy + 1, DIALOG_W - 2, 20, ShopRender.TAB_ACTIVE,
                0xFF1B2130);

        Component title = Component.translatable(KEY + (categoryId.isBlank()
                ? "category.title.new" : "category.title.edit"));
        theme.drawString(graphics, title.getString(), dx + 10, dy + 6, Color4I.rgb(0xFFFFFF), Theme.SHADOW);

        theme.drawString(graphics, Component.translatable(KEY + "category.name").getString(),
                dx + 12, dy + 38, Color4I.rgb(0xC0C8D8), Theme.SHADOW);
        theme.drawString(graphics, Component.translatable(KEY + "category.icon").getString(),
                dx + 12, dy + 64, Color4I.rgb(0xC0C8D8), Theme.SHADOW);
        categoryIcon.draw(graphics, dx + DIALOG_W - 44, dy + 32, 20, 20);

        theme.drawString(graphics, Component.translatable(KEY + "category.hint.name").getString(),
                dx + 12, dy + 90, Color4I.rgb(0x9AA6B8), Theme.SHADOW);
        var font = Minecraft.getInstance().font;
        List<net.minecraft.util.FormattedCharSequence> hints = font.split(
                Component.translatable(KEY + "category.hint.items"), DIALOG_W - 24);
        int hintY = dy + 104;
        for (var line : hints) {
            graphics.drawString(font, line, dx + 12, hintY, 0x9AA6B8, true);
            hintY += 10;
        }

        if (!status.getString().isEmpty()) {
            theme.drawString(graphics, theme.trimStringToWidth(status.getString(), DIALOG_W - 24),
                    dx + 12, dy + DIALOG_H - 44, Color4I.rgb(0xFFD166), Theme.SHADOW);
        }
    }
}
