package com.lyhyy2007.bmcs.client;

import com.lyhyy2007.bmcs.BetterMinecraftShop;
import com.lyhyy2007.bmcs.client.ui.ShopRender;
import com.lyhyy2007.bmcs.util.ItemNameUtil;
import dev.ftb.mods.ftblibrary.icon.Color4I;
import dev.ftb.mods.ftblibrary.icon.Icon;
import dev.ftb.mods.ftblibrary.icon.Icons;
import dev.ftb.mods.ftblibrary.icon.ItemIcon;
import dev.ftb.mods.ftblibrary.ui.BaseScreen;
import dev.ftb.mods.ftblibrary.ui.Button;
import dev.ftb.mods.ftblibrary.ui.Panel;
import dev.ftb.mods.ftblibrary.ui.SimpleTextButton;
import dev.ftb.mods.ftblibrary.ui.TextBox;
import dev.ftb.mods.ftblibrary.ui.Theme;
import dev.ftb.mods.ftblibrary.ui.input.MouseButton;
import dev.ftb.mods.ftblibrary.util.TooltipList;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 分类图标选择器：可搜索的「所有已注册物品」网格（含模组物品）。
 *
 * <p>分类图标就是一个物品 id，所以这里直接把 {@code BuiltInRegistries.ITEM} 摊开 ——
 * 原版 + 模组 + 数据包加的物品全都在，不需要另外维护图标清单。
 */
public class IconPickerScreen extends BaseScreen {
    private static final int COLS = 12;
    private static final int ROWS = 7;
    private static final int CELL = 20;
    private static final int PAGE_SIZE = COLS * ROWS;
    private static final int DIALOG_W = COLS * CELL + 24;
    private static final int DIALOG_H = ROWS * CELL + 74;
    private static final String KEY = "gui." + BetterMinecraftShop.MOD_ID + ".";

    private final Consumer<String> onPick;
    /** true = 为「新建商品」挑物品；false = 为分类挑图标。两者界面一致，只有标题不同。 */
    private final boolean itemMode;
    private final TextBox searchBox;
    private final SimpleTextButton prevButton;
    private final SimpleTextButton nextButton;
    private final SimpleTextButton cancelButton;
    private final CellButton[] cells = new CellButton[PAGE_SIZE];
    private final List<Item> matches = new ArrayList<>();

    private int page;

    public IconPickerScreen(Consumer<String> onPick) {
        this(onPick, false);
    }

    public IconPickerScreen(Consumer<String> onPick, boolean itemMode) {
        super();
        this.onPick = onPick;
        this.itemMode = itemMode;
        this.searchBox = new TextBox(this) {
            @Override
            public void onTextChanged() {
                rebuild();
            }
        };
        this.searchBox.ghostText = Component.translatable(KEY + "icon.search").getString();
        this.searchBox.setMaxLength(48);
        this.prevButton = SimpleTextButton.create(this,
                Component.translatable(KEY + "icon.prev"), Icon.empty(),
                button -> turnPage(-1),
                Component.translatable(KEY + "icon.prev.tip"));
        this.nextButton = SimpleTextButton.create(this,
                Component.translatable(KEY + "icon.next"), Icon.empty(),
                button -> turnPage(1),
                Component.translatable(KEY + "icon.next.tip"));
        this.cancelButton = SimpleTextButton.create(this,
                Component.translatable(KEY + "editor.cancel"), Icons.CANCEL,
                button -> closeGui(true),
                Component.translatable(KEY + "editor.cancel.tip"));
    }

    @Override
    public boolean onInit() {
        return setFullscreen();
    }

    @Override
    public void addWidgets() {
        add(searchBox);
        add(prevButton);
        add(nextButton);
        add(cancelButton);
        for (int i = 0; i < PAGE_SIZE; i++) {
            CellButton button = new CellButton(this, i);
            cells[i] = button;
            add(button);
        }
        rebuild();
    }

    @Override
    public void alignWidgets() {
        int dx = (width - DIALOG_W) / 2;
        int dy = (height - DIALOG_H) / 2;
        searchBox.setPosAndSize(dx + 8, dy + 24, DIALOG_W - 16, 16);
        int gridX = dx + 12;
        int gridY = dy + 48;
        for (int i = 0; i < PAGE_SIZE; i++) {
            int col = i % COLS;
            int row = i / COLS;
            cells[i].setPosAndSize(gridX + col * CELL, gridY + row * CELL, CELL - 2, CELL - 2);
        }
        int buttonY = dy + DIALOG_H - 24;
        prevButton.setPosAndSize(dx + 12, buttonY, 60, 18);
        nextButton.setPosAndSize(dx + 78, buttonY, 60, 18);
        cancelButton.setPosAndSize(dx + DIALOG_W - 88, buttonY, 76, 18);
    }

    /** 按搜索词过滤并回到第一页。控件不重建 —— 格子每帧都按 {@link #page} 现取物品。 */
    private void rebuild() {
        String query = searchBox == null ? "" : searchBox.getText().trim().toLowerCase(Locale.ROOT);
        matches.clear();
        for (ResourceLocation id : BuiltInRegistries.ITEM.keySet()) {
            Item item = BuiltInRegistries.ITEM.get(id);
            if (item == null || item == Items.AIR) {
                continue;
            }
            if (!query.isEmpty() && !matchesQuery(item, id, query)) {
                continue;
            }
            matches.add(item);
        }
        matches.sort(Comparator.comparing(item -> BuiltInRegistries.ITEM.getKey(item).toString()));
        page = 0;
    }

    private static boolean matchesQuery(Item item, ResourceLocation id, String query) {
        return id.toString().toLowerCase(Locale.ROOT).contains(query)
                || ItemNameUtil.plainText(new ItemStack(item)).toLowerCase(Locale.ROOT).contains(query);
    }

    private int pageCount() {
        return Math.max(1, (matches.size() + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    private void turnPage(int delta) {
        int next = Math.max(0, Math.min(pageCount() - 1, page + delta));
        if (next != page) {
            page = next;
            // 格子每帧都按 page 现取物品，改 page 即可，无需重建控件。
            playClickSound();
        }
    }

    private void pick(Item item) {
        String id = BuiltInRegistries.ITEM.getKey(item).toString();
        closeGui(true);
        if (onPick != null) {
            onPick.accept(id);
        }
    }

    @Override
    public void drawBackground(GuiGraphics graphics, Theme theme, int x, int y, int w, int h) {
        int dx = (width - DIALOG_W) / 2;
        int dy = (height - DIALOG_H) / 2;
        ShopRender.rect(graphics, x, y, w, h, 0x66000000);
        ShopRender.roundedRect(graphics, dx, dy, DIALOG_W, DIALOG_H, 6, ShopRender.PANEL_BG);
        ShopRender.border(graphics, dx, dy, DIALOG_W, DIALOG_H, 1, ShopRender.PANEL_BORDER);
        theme.drawString(graphics,
                Component.translatable(KEY + (itemMode ? "item.pick.title" : "icon.title")).getString(),
                dx + 10, dy + 8, Color4I.rgb(0xFFFFFF), Theme.SHADOW);

        if (matches.isEmpty()) {
            theme.drawString(graphics, Component.translatable(KEY + "icon.empty").getString(),
                    dx + 12, dy + 52, Color4I.rgb(0x9AA6B8), Theme.SHADOW);
            return;
        }
        int pages = pageCount();
        theme.drawString(graphics, (page + 1) + " / " + pages, dx + DIALOG_W - 62, dy + 8,
                Color4I.rgb(0x9AA6B8), Theme.SHADOW);
    }

    /** 网格里的一个物品格子。 */
    private class CellButton extends Button {
        private final int index;

        CellButton(Panel parent, int index) {
            super(parent);
            this.index = index;
        }

        private Item item() {
            int at = page * PAGE_SIZE + index;
            return at < matches.size() ? matches.get(at) : null;
        }

        @Override
        public void onClicked(MouseButton button) {
            Item item = item();
            if (item != null) {
                playClickSound();
                pick(item);
            }
        }

        @Override
        public void draw(GuiGraphics graphics, Theme theme, int x, int y, int w, int h) {
            Item item = item();
            if (item == null) {
                return;
            }
            ShopRender.roundedRect(graphics, x, y, w, h, 3, ShopRender.SLOT_BG);
            if (isMouseOver()) {
                ShopRender.roundedRect(graphics, x, y, w, h, 3, ShopRender.SLOT_HOVER);
            }
            Icon icon = ItemIcon.getItemIcon(new ItemStack(item));
            icon.draw(graphics, x + 1, y + 1, 16, 16);
        }

        @Override
        public void addMouseOverText(TooltipList list) {
            Item item = item();
            if (item != null) {
                ItemStack stack = new ItemStack(item);
                list.add(ItemNameUtil.displayName(stack));
                ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
                list.add(Component.literal(id == null ? "?" : id.toString())
                        .withStyle(style -> style.withColor(0x9AA6B8)));
            }
        }
    }
}
