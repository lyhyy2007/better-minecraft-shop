package com.lyhyy2007.bmcs.client;

import com.lyhyy2007.bmcs.BetterMinecraftShop;
import com.lyhyy2007.bmcs.client.ui.ShopRender;
import com.lyhyy2007.bmcs.client.ui.VirtualGrid;
import com.lyhyy2007.bmcs.currency.DeepCoinCurrency;
import com.lyhyy2007.bmcs.network.ShopActionPayload;
import com.lyhyy2007.bmcs.network.ShopDataPayload;
import com.lyhyy2007.bmcs.util.ItemNameUtil;
import dev.ftb.mods.ftblibrary.icon.Color4I;
import dev.ftb.mods.ftblibrary.icon.Icon;
import dev.ftb.mods.ftblibrary.icon.Icons;
import dev.ftb.mods.ftblibrary.icon.ItemIcon;
import dev.ftb.mods.ftblibrary.ui.BaseScreen;
import dev.ftb.mods.ftblibrary.ui.Button;
import dev.ftb.mods.ftblibrary.ui.GuiHelper;
import dev.ftb.mods.ftblibrary.ui.IntTextBox;
import dev.ftb.mods.ftblibrary.ui.Panel;
import dev.ftb.mods.ftblibrary.ui.PanelScrollBar;
import dev.ftb.mods.ftblibrary.ui.ScrollBar;
import dev.ftb.mods.ftblibrary.ui.SimpleTextButton;
import dev.ftb.mods.ftblibrary.ui.TextBox;
import dev.ftb.mods.ftblibrary.ui.Theme;
import dev.ftb.mods.ftblibrary.ui.Widget;
import dev.ftb.mods.ftblibrary.ui.WidgetType;
import dev.ftb.mods.ftblibrary.ui.input.MouseButton;
import dev.ftb.mods.ftblibrary.util.TooltipList;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/**
 * 商店界面。
 *
 * <p>界面框架用 FTB Library 的 {@link BaseScreen} / {@link Panel} / {@link Button} / {@link TextBox}
 * （侧边栏入口与界面框架都走 FTB Library），视觉细节由 {@link ShopRender} 的绘制原语完成。
 *
 * <p>布局：顶部标题 + 余额 + 商品计数 + 搜索框 → 分类页签 → 可滚动商品网格 → 底部数量/买入/卖出/状态栏。
 *
 * <p><b>虚拟滚动</b>：商品网格只为可视窗口内的槽位创建控件
 * （几何计算在 {@link VirtualGrid}，纯整数运算、可单独跑测试），
 * 因此 10000 条商品和 30 条商品的每帧开销几乎一样；滚动时按「首个可见行变化」重建这几十个控件。
 * 数据侧配合 {@link ShopClientData#filtered()} 的过滤缓存，每帧不做全量扫描。
 *
 * <p>这个类只在客户端加载；它不做任何业务判断，价格、库存、数量最终都由服务端复核。
 */
public class ShopScreen extends BaseScreen {
    private static final int PAD = 4;
    private static final int HEADER_H = 34;
    private static final int TABS_H = 22;
    private static final int FOOTER_H = 30;
    private static final int SCROLL_W = 10;
    private static final int TILE_W = 104;
    private static final int TILE_H = 44;
    private static final int TILE_GAP = 4;

    private static final String KEY = "gui." + BetterMinecraftShop.MOD_ID + ".";

    private final TabsPanel tabsPanel;
    private final GridPanel gridPanel;
    private final PanelScrollBar scrollBar;
    private final FooterPanel footerPanel;
    private final TextBox searchBox;
    private final IntTextBox quantityBox;
    private final SimpleTextButton buyButton;
    private final SimpleTextButton sellButton;

    public ShopScreen() {
        super();
        this.tabsPanel = new TabsPanel(this);
        this.gridPanel = new GridPanel(this);
        this.scrollBar = new PanelScrollBar(this, ScrollBar.Plane.VERTICAL, this.gridPanel);
        // 滚动条只在网格上方滚动时响应；canAlwaysScroll 让滚轮在界面任何位置都能滚（FTB 的设计）。
        this.scrollBar.setCanAlwaysScroll(true);
        this.footerPanel = new FooterPanel(this);

        this.searchBox = new TextBox(this) {
            @Override
            public void onTextChanged() {
                ShopScreen.this.applySearch(getText());
            }
        };
        this.searchBox.ghostText = Component.translatable(KEY + "search").getString();
        this.searchBox.setMaxLength(32);

        this.quantityBox = new IntTextBox(this.footerPanel);
        this.quantityBox.setMinMax(1, ShopActionPayload.HARD_LIMIT);
        this.quantityBox.setAmount(ShopClientData.quantity());

        this.buyButton = SimpleTextButton.create(this.footerPanel,
                Component.translatable(KEY + "buy"), Icons.MONEY,
                button -> submit(ShopActionPayload.Action.BUY),
                Component.translatable(KEY + "buy.tip"));
        this.sellButton = SimpleTextButton.create(this.footerPanel,
                Component.translatable(KEY + "sell"), Icons.BIN,
                button -> submit(ShopActionPayload.Action.SELL),
                Component.translatable(KEY + "sell.tip"));
    }

    // ------------------------------------------------------------------
    // FTB Library 生命周期
    // ------------------------------------------------------------------

    @Override
    public boolean onInit() {
        // 全屏铺满：宽高取自窗口的 GUI 缩放尺寸，随窗口大小变化在 initGui() 时重算。
        return setFullscreen();
    }

    @Override
    public void addWidgets() {
        add(tabsPanel);
        add(gridPanel);
        add(scrollBar);
        add(footerPanel);
        add(searchBox);
    }

    @Override
    public void alignWidgets() {
        searchBox.setPosAndSize(width - 124, 10, 116, 16);

        tabsPanel.setPosAndSize(PAD, HEADER_H, Math.max(1, width - PAD * 2), TABS_H);

        int gridY = HEADER_H + TABS_H + PAD;
        int gridH = Math.max(1, height - gridY - FOOTER_H - PAD * 3);
        int gridW = Math.max(1, width - PAD * 2 - SCROLL_W);
        gridPanel.setPosAndSize(PAD, gridY, gridW, gridH);
        scrollBar.setPosAndSize(gridPanel.getPosX() + gridW, gridY, SCROLL_W, gridH);

        footerPanel.setPosAndSize(PAD, height - FOOTER_H - PAD, Math.max(1, width - PAD * 2), FOOTER_H);

        // 子面板的尺寸刚刚才确定，必须在这里再对齐一次（FTB 的 alignWidgets 是自上而下的）。
        tabsPanel.alignWidgets();
        gridPanel.alignWidgets();
        footerPanel.alignWidgets();
    }

    @Override
    public Component getTitle() {
        return Component.translatable(KEY + "title");
    }

    @Override
    public void drawBackground(GuiGraphics graphics, Theme theme, int x, int y, int w, int h) {
        // 整体底板 + 顶部标题栏渐变
        ShopRender.rect(graphics, x, y, w, h, ShopRender.PANEL_BG);
        ShopRender.gradientVertical(graphics, x, y, w, HEADER_H, ShopRender.argb(0xF0, 0x1D, 0x26, 0x38),
                ShopRender.argb(0xC0, 0x14, 0x18, 0x22));
        ShopRender.rect(graphics, x, y + HEADER_H - 1, w, 1, ShopRender.PANEL_BORDER);

        theme.drawString(graphics, Component.translatable(KEY + "title").getString(), x + PAD + 2, y + 6,
                Color4I.rgb(0xFFFFFF), Theme.SHADOW);
        theme.drawString(graphics, DeepCoinCurrency.format(ShopClientData.balance()).getString(),
                x + PAD + 2, y + 18, Color4I.rgb(0xFFC94A), Theme.SHADOW);

        if (ShopClientData.hasData()) {
            Component counter = Component.translatable(KEY + "counter",
                    ShopClientData.entries().size(), ShopClientData.filtered().size());
            theme.drawString(graphics, counter.getString(), x + 150, y + 18, Color4I.rgb(0x9AA6B8), Theme.SHADOW);
        }
    }

    /** 搜索框内容变化：更新过滤条件、回到顶部、重建网格。 */
    private void applySearch(String text) {
        ShopClientData.setSearch(text);
        gridPanel.refreshWidgets();
        scrollBar.setValue(0);
        ShopClientData.setStatus(Component.translatable(KEY + "status.filtered",
                ShopClientData.search(), ShopClientData.filtered().size()));
    }

    /** 服务端刷新数据后调用：重建列表但保留滚动、搜索与选中状态。 */
    public void onDataUpdated() {
        gridPanel.refreshWidgets();
        tabsPanel.refreshWidgets();
        footerPanel.refreshWidgets();
        // 目录变小（或过滤结果变少）时把滚动位置夹回合法范围，避免停在内容之外。
        scrollBar.setValue(Math.min(scrollBar.getValue(), scrollBar.getMaxValue()));
    }

    @Override
    public void onClosed() {
        super.onClosed();
        // 关闭界面即丢弃快照：1 万条商品不常驻客户端内存，下次打开重新向服务端请求。
        ShopClientData.clear();
    }

    // ------------------------------------------------------------------
    // 交易请求
    // ------------------------------------------------------------------

    private void submit(ShopActionPayload.Action action) {
        int revision = ShopClientData.revision();
        if (revision < 0) {
            ShopClientData.setStatus(Component.translatable(KEY + "status.no_data"));
            return;
        }
        Optional<ShopDataPayload.EntryData> selected = ShopClientData.selected();
        if (selected.isEmpty()) {
            ShopClientData.setStatus(Component.translatable(KEY + "status.select_first"));
            return;
        }
        ShopDataPayload.EntryData entry = selected.get();
        if (action == ShopActionPayload.Action.SELL && !entry.sellable()) {
            ShopClientData.setStatus(Component.translatable(KEY + "status.not_sellable"));
            return;
        }
        int quantity = Math.max(1, quantityBox.getIntValue());
        ShopClientData.setQuantity(quantity);
        PacketDistributor.sendToServer(new ShopActionPayload(action, entry.id(), quantity, revision));
        ShopClientData.setStatus(Component.translatable(
                action == ShopActionPayload.Action.BUY ? KEY + "status.buy_sent" : KEY + "status.sell_sent",
                plainNameOf(entry), quantity));
    }

    /** 展示名：走游戏内置本地化（客户端语言）。 */
    static Component displayNameOf(ShopDataPayload.EntryData entry) {
        return ItemNameUtil.displayName(entry.template());
    }

    static String plainNameOf(ShopDataPayload.EntryData entry) {
        return displayNameOf(entry).getString();
    }

    // ------------------------------------------------------------------
    // 分类页签
    // ------------------------------------------------------------------

    private class TabsPanel extends Panel {
        private static final int TAB_H = 20;
        private static final int TAB_GAP = 3;

        TabsPanel(Panel parent) {
            super(parent);
        }

        @Override
        public void addWidgets() {
            add(new TabButton(this, "", Component.translatable(KEY + "tab.all"), null));
            for (ShopDataPayload.CategoryData category : ShopClientData.categories()) {
                // 分类名：配置里写了 display_name 就用它（支持翻译键），否则用内置分类翻译键，
                // 再否则退回分类 id —— 名字不再需要服主在 JSON 里维护。
                Component label = ItemNameUtil.categoryName(category.displayName(), category.id());
                add(new TabButton(this, category.id(), label, category.icon().isEmpty() ? null : category.icon()));
            }
        }

        @Override
        public void alignWidgets() {
            int x = 0;
            for (Widget widget : getWidgets()) {
                if (widget instanceof TabButton tab) {
                    int tabWidth = tab.preferredWidth();
                    tab.setPosAndSize(x, 1, tabWidth, TAB_H);
                    x += tabWidth + TAB_GAP;
                }
            }
        }

        @Override
        public void drawBackground(GuiGraphics graphics, Theme theme, int x, int y, int w, int h) {
            ShopRender.rect(graphics, x, y, w, h, ShopRender.PANEL_BG_ALT);
        }
    }

    private class TabButton extends Button {
        private final String categoryId;

        TabButton(Panel parent, String categoryId, Component label, @Nullable ItemStack iconStack) {
            super(parent, label, iconStack == null ? Icon.empty() : ItemIcon.getItemIcon(iconStack));
            this.categoryId = categoryId;
            setSize(preferredWidth(), 20);
        }

        int preferredWidth() {
            int textWidth = getGui().getTheme().getStringWidth(getTitle().getString());
            return this.icon.isEmpty() ? textWidth + 12 : textWidth + 24;
        }

        private boolean isActive() {
            return ShopClientData.categoryId().equals(categoryId);
        }

        @Override
        public void onClicked(MouseButton button) {
            playClickSound();
            ShopClientData.setCategoryId(categoryId);
            // 立刻重建：页签高亮与商品网格同时刷新。
            ShopScreen.this.tabsPanel.refreshWidgets();
            ShopScreen.this.gridPanel.refreshWidgets();
            ShopScreen.this.scrollBar.setValue(0);
        }

        @Override
        public void draw(GuiGraphics graphics, Theme theme, int x, int y, int w, int h) {
            boolean active = isActive();
            boolean hover = isMouseOver();
            ShopRender.roundedRect(graphics, x, y, w, h, 4, active ? ShopRender.TAB_ACTIVE : ShopRender.TAB_IDLE);
            if (!active && hover) {
                ShopRender.roundedRect(graphics, x, y, w, h, 4, ShopRender.SLOT_HOVER);
            }
            ShopRender.roundedRect(graphics, x, y + h - 2, w, 2, 1, active ? ShopRender.ACCENT : 0x00000000);
            int textX = x + 6;
            if (!this.icon.isEmpty()) {
                this.icon.draw(graphics, x + 4, y + 2, 16, 16);
                textX = x + 22;
            }
            theme.drawString(graphics, theme.trimStringToWidth(getTitle().getString(), w - (textX - x) - 4),
                    textX, y + 6, active ? Color4I.rgb(0xFFFFFF) : Color4I.rgb(0xC0C8D8), Theme.SHADOW);
        }

        @Override
        public void addMouseOverText(TooltipList list) {
            list.add(getTitle());
        }
    }

    // ------------------------------------------------------------------
    // 商品网格（虚拟滚动）
    // ------------------------------------------------------------------

    private class GridPanel extends Panel {
        /** 当前几何；由 {@link #addWidgets()} 按最新尺寸与条目数重建。 */
        private VirtualGrid grid;
        private int windowFrom = -1;
        private int windowTo = -1;

        GridPanel(Panel parent) {
            super(parent);
        }

        /**
         * 覆盖内容高度：FTB 的 {@code PanelScrollBar} 用
         * {@code getContentHeight() - getHeight()} 当滚动上限，而虚拟滚动下
         * 只为窗口建控件、控件范围不再等于内容范围，所以这里直接给出逻辑高度。
         */
        @Override
        public int getContentHeight() {
            VirtualGrid current = grid;
            return current == null ? 0 : current.contentHeight();
        }

        @Override
        public double getScrollStep() {
            VirtualGrid current = grid;
            return current == null ? TILE_H + TILE_GAP : current.scrollStep();
        }

        @Override
        public void setScrollY(double scroll) {
            double before = getScrollY();
            super.setScrollY(scroll);
            if (before != scroll && windowChanged()) {
                // 只有「首个可见行」真的变了才重建控件：一屏 20~40 个按钮，滚动时毫无压力。
                refreshWidgets();
            }
        }

        /** 重新计算可视窗口；返回是否需要重建控件。 */
        private boolean windowChanged() {
            VirtualGrid current = grid;
            if (current == null) {
                return true;
            }
            int from = current.firstVisibleIndex(getScrollY());
            int to = current.lastVisibleIndex(getScrollY());
            if (from == windowFrom && to == windowTo) {
                return false;
            }
            windowFrom = from;
            windowTo = to;
            return true;
        }

        @Override
        public void addWidgets() {
            List<ShopDataPayload.EntryData> visible = ShopClientData.filtered();
            grid = VirtualGrid.of(width, height, visible.size(), TILE_W, TILE_H, TILE_GAP, TILE_GAP);
            windowFrom = grid.firstVisibleIndex(getScrollY());
            windowTo = grid.lastVisibleIndex(getScrollY());
            for (int i = windowFrom; i < windowTo && i < visible.size(); i++) {
                ItemButton button = new ItemButton(this, visible.get(i));
                button.setPosAndSize(grid.x(i), grid.y(i), TILE_W, TILE_H);
                add(button);
            }
        }

        @Override
        public void alignWidgets() {
            VirtualGrid current = grid;
            if (current == null) {
                return;
            }
            int index = windowFrom;
            for (Widget widget : getWidgets()) {
                if (widget instanceof ItemButton) {
                    widget.setPosAndSize(current.x(index), current.y(index), TILE_W, TILE_H);
                    index++;
                }
            }
        }

        @Override
        public void drawBackground(GuiGraphics graphics, Theme theme, int x, int y, int w, int h) {
            ShopRender.rect(graphics, x, y, w, h, ShopRender.PANEL_BG_ALT);
            if (ShopClientData.filtered().isEmpty()) {
                Component empty = ShopClientData.hasData()
                        ? Component.translatable(KEY + "empty.filter")
                        : Component.translatable(KEY + "empty.no_data");
                theme.drawString(graphics, empty.getString(), x + 8, y + 8, Color4I.rgb(0x9AA6B8), Theme.SHADOW);
            }
        }
    }

    private class ItemButton extends Button {
        private final ShopDataPayload.EntryData entry;

        ItemButton(Panel parent, ShopDataPayload.EntryData entry) {
            super(parent, ItemNameUtil.displayName(entry.template()), ItemIcon.getItemIcon(entry.template()));
            this.entry = entry;
            setSize(TILE_W, TILE_H);
        }

        private boolean isSelected() {
            return entry.id().equals(ShopClientData.selectedId());
        }

        @Override
        public void onClicked(MouseButton button) {
            playClickSound();
            ShopClientData.select(entry.id());
            ShopClientData.setStatus(Component.translatable(KEY + "status.selected", plainNameOf(entry)));
        }

        @Override
        public void draw(GuiGraphics graphics, Theme theme, int x, int y, int w, int h) {
            GuiHelper.setupDrawing();
            ShopRender.roundedRect(graphics, x, y, w, h, 4, ShopRender.SLOT_BG);
            if (isSelected()) {
                ShopRender.roundedRect(graphics, x, y, w, h, 4, ShopRender.SLOT_SELECTED);
                ShopRender.border(graphics, x, y, w, h, 1, ShopRender.ACCENT);
            } else if (isMouseOver()) {
                ShopRender.roundedRect(graphics, x, y, w, h, 4, ShopRender.SLOT_HOVER);
            }

            boolean soldOut = !entry.unlimited() && entry.remaining() <= 0;
            this.icon.draw(graphics, x + 4, y + 4, 16, 16);

            int textX = x + 24;
            int maxTextWidth = w - 28;
            theme.drawString(graphics, theme.trimStringToWidth(plainNameOf(entry), maxTextWidth),
                    textX, y + 5, soldOut ? Color4I.rgb(0x808A9A) : Color4I.rgb(0xFFFFFF), Theme.SHADOW);

            theme.drawString(graphics,
                    Component.translatable(KEY + "tile.buy", formatMoney(entry.price())).getString(),
                    textX, y + 16, Color4I.rgb(0xFFC94A), Theme.SHADOW);

            String stockText = entry.unlimited()
                    ? Component.translatable(KEY + "stock.unlimited").getString()
                    : Component.translatable(KEY + "stock.left", entry.remaining()).getString();
            theme.drawString(graphics, stockText, textX, y + 26,
                    soldOut ? Color4I.rgb(0xE05A5A) : Color4I.rgb(0x9AA6B8), Theme.SHADOW);

            if (entry.sellable()) {
                String sellText = Component.translatable(KEY + "tile.sell", formatMoney(entry.sellPrice())).getString();
                theme.drawString(graphics, sellText, x + w - theme.getStringWidth(sellText) - 5, y + h - 11,
                        Color4I.rgb(0x8FD48F), Theme.SHADOW);
            }
        }

        @Override
        public void addMouseOverText(TooltipList list) {
            list.add(displayNameOf(entry));
            list.add(Component.translatable(KEY + "tip.price", formatMoney(entry.price())));
            if (entry.sellable()) {
                list.add(Component.translatable(KEY + "tip.sell_price", formatMoney(entry.sellPrice())));
            }
            list.add(entry.unlimited()
                    ? Component.translatable(KEY + "stock.unlimited")
                    : Component.translatable(KEY + "stock.left", entry.remaining()));
            list.add(Component.translatable(KEY + "tip.max_trade", entry.maxTrade()));
            list.add(Component.translatable(KEY + "tip.category", entry.categoryId()));
            list.add(Component.translatable(KEY + "tip.item_id", entry.template().getItem().getDescriptionId()));
            if (entry.customComponents()) {
                // 附魔 / 改名等组件确实随包同步过来了，这里提示玩家。
                list.add(Component.translatable(KEY + "tip.has_components"));
            }
        }

        @Override
        public WidgetType getWidgetType() {
            return WidgetType.NORMAL;
        }
    }

    private static String formatMoney(long value) {
        return DeepCoinCurrency.format(value).getString();
    }

    // ------------------------------------------------------------------
    // 底部操作栏
    // ------------------------------------------------------------------

    private class FooterPanel extends Panel {
        FooterPanel(Panel parent) {
            super(parent);
        }

        @Override
        public void addWidgets() {
            add(quantityBox);
            add(buyButton);
            add(sellButton);
        }

        @Override
        public void alignWidgets() {
            quantityBox.setPosAndSize(46, 6, 44, 16);
            buyButton.setPos(98, 4);
            sellButton.setPos(98 + buyButton.getWidth() + 6, 4);
        }

        @Override
        public void drawBackground(GuiGraphics graphics, Theme theme, int x, int y, int w, int h) {
            ShopRender.rect(graphics, x, y, w, h, ShopRender.PANEL_BG_ALT);
            ShopRender.rect(graphics, x, y, w, 1, ShopRender.PANEL_BORDER);
            theme.drawString(graphics, Component.translatable(KEY + "quantity").getString(), x + 6, y + 10,
                    Color4I.rgb(0xC0C8D8), Theme.SHADOW);

            Component status = ShopClientData.status();
            if (!status.getString().isEmpty()) {
                theme.drawString(graphics, theme.trimStringToWidth(status.getString(), Math.max(40,
                                w - 210)), x + 190, y + 10, Color4I.rgb(0x8FD48F), Theme.SHADOW);
            }
        }
    }

    /** 便捷方法：关闭界面（供外部调用）。 */
    public void requestClose() {
        closeGui(true);
    }
}
