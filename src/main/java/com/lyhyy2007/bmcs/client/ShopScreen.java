package com.lyhyy2007.bmcs.client;

import com.lyhyy2007.bmcs.BetterMinecraftShop;
import com.lyhyy2007.bmcs.client.ui.ShopRender;
import com.lyhyy2007.bmcs.client.ui.VirtualGrid;
import com.lyhyy2007.bmcs.config.ShopSide;
import com.lyhyy2007.bmcs.currency.DeepCoinCurrency;
import com.lyhyy2007.bmcs.network.ShopActionPayload;
import com.lyhyy2007.bmcs.network.ShopDataPayload;
import com.lyhyy2007.bmcs.network.ShopItemDeletePayload;
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
import net.minecraft.core.registries.BuiltInRegistries;
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
    /** 左侧「买入 / 卖出」栏位宽度。 */
    private static final int SIDE_W = 56;
    private static final int TILE_W = 104;
    private static final int TILE_H = 44;
    private static final int TILE_GAP = 4;

    private static final String KEY = "gui." + BetterMinecraftShop.MOD_ID + ".";

    private final SidePanel sidePanel;
    private final TabsPanel tabsPanel;
    private final GridPanel gridPanel;
    private final PanelScrollBar scrollBar;
    private final FooterPanel footerPanel;
    private final TextBox searchBox;
    private final IntTextBox quantityBox;
    private final SimpleTextButton buyButton;
    private final SimpleTextButton sellButton;
    /** 右上角「新建分类」：只在编辑模式下出现。 */
    private final SimpleTextButton createCategoryButton;

    /** 正在从商店界面切到编辑器：此时 onClosed() 不能丢掉刚加载的商店快照。 */
    private boolean openingEditor;

    public ShopScreen() {
        super();
        this.sidePanel = new SidePanel(this);
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

        // 右上角只有「新建分类」一个入口：挂在屏幕本体上（不在 footer），位置紧挨搜索框左侧。
        // 只在服务端下发 can_edit 且处于编辑模式时才 add 到界面，普通玩家完全看不到。
        // 「新建商品」不占顶栏，而是编辑模式下商品网格里的第一格（见 GridPanel）。
        this.createCategoryButton = SimpleTextButton.create(this,
                Component.translatable(KEY + "editor.open"), Icons.ADD,
                button -> openCategoryEditor(),
                Component.translatable(KEY + "editor.open.tip"));
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
        add(sidePanel);
        add(tabsPanel);
        add(gridPanel);
        add(scrollBar);
        add(footerPanel);
        if (ShopClientData.canEdit() && ShopClientData.editMode()) {
            add(createCategoryButton);
        }
        add(searchBox);
    }

    @Override
    public void alignWidgets() {
        // 右上角：搜索框贴边（宽 116），左边是「新建分类」（仅编辑模式存在），间隔 6px。
        int searchX = Math.max(0, width - 124);
        searchBox.setPosAndSize(searchX, 10, 116, 16);
        int buttonWidth = 76;
        createCategoryButton.setPosAndSize(Math.max(PAD, searchX - 6 - buttonWidth), 10, buttonWidth, 16);

        int bodyX = PAD + SIDE_W + PAD;
        int bodyW = Math.max(1, width - bodyX - PAD);
        int bodyH = Math.max(24, height - HEADER_H - FOOTER_H - PAD * 2);
        sidePanel.setPosAndSize(PAD, HEADER_H, SIDE_W, bodyH);

        tabsPanel.setPosAndSize(bodyX, HEADER_H, bodyW, TABS_H);

        // 分类页签按文字实际长度排版：一行放不下就换行，页签栏高度随之变高，商品网格下移。
        int tabsH = Math.max(TABS_H, tabsPanel.requiredHeight(bodyW));
        tabsPanel.setPosAndSize(bodyX, HEADER_H, bodyW, tabsH);

        int gridY = HEADER_H + tabsH + PAD;
        int gridH = Math.max(1, height - gridY - FOOTER_H - PAD * 3);
        int gridW = Math.max(1, bodyW - SCROLL_W);
        gridPanel.setPosAndSize(bodyX, gridY, gridW, gridH);
        scrollBar.setPosAndSize(gridPanel.getPosX() + gridW, gridY, SCROLL_W, gridH);

        footerPanel.setPosAndSize(PAD, height - FOOTER_H - PAD, Math.max(1, width - PAD * 2), FOOTER_H);

        // 子面板的尺寸刚刚才确定，必须在这里再对齐一次（FTB 的 alignWidgets 是自上而下的）。
        sidePanel.alignWidgets();
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
                    ShopClientData.sideEntryCount(), ShopClientData.filtered().size());
            // 顶栏右侧已经被「两个新建按钮 + 搜索框」占掉约 270px，计数文本在窄窗口下要截断，不能压上去。
            int counterWidth = Math.max(40,
                    width - 150 - (ShopClientData.canEdit() && ShopClientData.editMode() ? 300 : 8));
            theme.drawString(graphics, theme.trimStringToWidth(counter.getString(), counterWidth),
                    x + 150, y + 18, Color4I.rgb(0x9AA6B8), Theme.SHADOW);
        }
    }

    /** 右上角「新建」：先建一个分类（文件名 = 分类名），建好会自动选中它。 */
    private void openCategoryEditor() {
        openingEditor = true;
        new ShopCategoryEditorScreen(ShopClientData.side(), "", "", "", ShopClientData.revision()).openGui();
    }

    /**
     * 右上角「新建商品」：只有当前选中的是「游戏内新建的分类」才可用。
     *
     * <p>手写 JSON 里的分类不写 {@code editable:true}，游戏内不会往里加东西
     * （避免把服主手写的文件改乱）；先「新建分类」再往里加商品即可。
     */
    private void openEditor() {
        Optional<ShopDataPayload.CategoryData> category = ShopClientData.editableSelectedCategory();
        if (category.isEmpty()) {
            ShopClientData.setStatus(Component.translatable(KEY + "status.need_category"));
            return;
        }
        openingEditor = true;
        ShopDataPayload.CategoryData data = category.get();
        new ShopItemEditorScreen(ShopClientData.side(), data.id(),
                ItemNameUtil.categoryName(data.displayName(), data.id()).getString(),
                ShopClientData.revision()).openGui();
    }

    /**
     * 编辑模式下、且当前选中的是「游戏内新建的可编辑分类」时，商品网格第一位显示「新建商品」格。
     *
     * <p>没选中可编辑分类（例如停在「全部」页签，或选的是手写 JSON 的分类）时这一格直接不出现，
     * 避免点了才提示。
     */
    private boolean showCreateTile() {
        return ShopClientData.canEdit() && ShopClientData.editMode() && ShopClientData.editableSelectedCategory().isPresent();
    }

    /** 左侧「编辑模式」开关：打开后才能删商品；改分类名/换图标用页签右键。 */
    private void toggleEditMode() {
        if (!ShopClientData.canEdit()) {
            return;
        }
        boolean next = !ShopClientData.editMode();
        ShopClientData.setEditMode(next);
        ShopClientData.setStatus(Component.translatable(KEY + (next ? "edit.on" : "edit.off")));
        // 侧栏按钮的文字要跟着状态立刻换（"编辑模式"/"退出编辑"）。
        sidePanel.updateEditLabel();
        // 「新建分类」按钮与网格里的「新建商品」格子都只在编辑模式出现，必须整屏重建一次。
        refreshWidgets();
        tabsPanel.refreshWidgets();
        gridPanel.refreshWidgets();
    }

    /**
     * 编辑模式下右键分类页签：改显示名 / 换图标（只有游戏内新建的分类可改）。
     */
    private void openCategoryEditorFor(String id) {
        if (!ShopClientData.canEdit()) {
            return;
        }
        if (!ShopClientData.editMode()) {
            ShopClientData.setStatus(Component.translatable(KEY + "status.need_edit_mode"));
            return;
        }
        ShopDataPayload.CategoryData data = null;
        for (ShopDataPayload.CategoryData candidate : ShopClientData.categories()) {
            if (candidate.id().equals(id)) {
                data = candidate;
                break;
            }
        }
        if (data == null) {
            return;
        }
        if (!data.editable()) {
            ShopClientData.setStatus(Component.translatable(KEY + "status.category_locked"));
            return;
        }
        openingEditor = true;
        new ShopCategoryEditorScreen(ShopClientData.side(), data.id(),
                ItemNameUtil.categoryName(data.displayName(), data.id()).getString(),
                iconIdOf(data.icon()), ShopClientData.revision()).openGui();
    }

    private static String iconIdOf(ItemStack icon) {
        if (icon == null || icon.isEmpty()) {
            return "";
        }
        return BuiltInRegistries.ITEM.getKey(icon.getItem()).toString();
    }

    /** 编辑模式下左键商品：先弹确认框，确认后才真正删（删除是不可逆操作）。 */
    private void confirmDelete(ShopDataPayload.EntryData entry) {
        if (!ShopClientData.canEdit()) {
            return;
        }
        boolean editable = false;
        for (ShopDataPayload.CategoryData category : ShopClientData.categories()) {
            if (category.id().equals(entry.categoryId()) && category.editable()) {
                editable = true;
                break;
            }
        }
        if (!editable) {
            ShopClientData.setStatus(Component.translatable(KEY + "status.category_locked"));
            return;
        }
        openingEditor = true;
        new ShopConfirmScreen(
                Component.translatable(KEY + "confirm.delete", plainNameOf(entry)),
                () -> {
                    PacketDistributor.sendToServer(new ShopItemDeletePayload(
                            ShopClientData.side(), entry.categoryId(), entry.id(), ShopClientData.revision()));
                    ShopClientData.setStatus(Component.translatable(KEY + "status.deleting"));
                }).openGui();
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
        sidePanel.refreshWidgets();
        tabsPanel.refreshWidgets();
        footerPanel.refreshWidgets();
        // 目录变小（或过滤结果变少）时把滚动位置夹回合法范围，避免停在内容之外。
        scrollBar.setValue(Math.min(scrollBar.getValue(), scrollBar.getMaxValue()));
    }

    @Override
    public void onClosed() {
        super.onClosed();
        // 切到编辑器时保留快照（编辑器保存需要当前目录版本）；真正关闭界面才丢弃。
        boolean switchingToEditor = openingEditor;
        openingEditor = false;
        if (switchingToEditor) {
            return;
        }
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
    // 左侧「买入 / 卖出」栏位
    // ------------------------------------------------------------------

    /** 左侧竖排的两个栏位：买入、卖出。切换后下方的分类页签与商品网格都只显示对应方向的内容。 */
    private class SidePanel extends Panel {
        private static final int BTN_H = 40;
        private static final int BTN_GAP = 4;
        private final SimpleTextButton editModeButton;

        SidePanel(Panel parent) {
            super(parent);
            this.editModeButton = SimpleTextButton.create(this,
                    Component.translatable(KEY + "edit.button"), Icons.SETTINGS,
                    button -> toggleEditMode(),
                    Component.translatable(KEY + "edit.tip"));
        }

        void updateEditLabel() {
            editModeButton.setTitle(Component.translatable(KEY
                    + (ShopClientData.editMode() ? "edit.done" : "edit.button")));
        }

        @Override
        public void addWidgets() {
            add(new SideButton(this, ShopSide.BUY, Component.translatable(KEY + "side.buy"), Icons.MONEY));
            add(new SideButton(this, ShopSide.SELL, Component.translatable(KEY + "side.sell"), Icons.BIN));
            if (ShopClientData.canEdit()) {
                add(editModeButton);
            }
        }

        @Override
        public void alignWidgets() {
            int y = 4;
            for (Widget widget : getWidgets()) {
                if (widget instanceof SideButton button) {
                    button.setPosAndSize(4, y, Math.max(1, width - 8), BTN_H);
                    y += BTN_H + BTN_GAP;
                }
            }
            editModeButton.setPosAndSize(4, Math.max(y, height - 22), Math.max(1, width - 8), 18);
            updateEditLabel();
        }

        @Override
        public void drawBackground(GuiGraphics graphics, Theme theme, int x, int y, int w, int h) {
            ShopRender.roundedRect(graphics, x, y, w, h, 6, ShopRender.PANEL_BG_ALT);
            ShopRender.border(graphics, x, y, w, h, 1, ShopRender.PANEL_BORDER);
        }
    }

    private class SideButton extends Button {
        private final ShopSide side;

        SideButton(Panel parent, ShopSide side, Component label, Icon icon) {
            super(parent, label, icon);
            this.side = side;
        }

        private boolean isActive() {
            return ShopClientData.side() == side;
        }

        @Override
        public void onClicked(MouseButton button) {
            playClickSound();
            if (isActive()) {
                return;
            }
            // 两个方向的分类、商品、可选操作各不相同：切换栏位时一并清空分类与选中项。
            ShopClientData.setSide(side);
            ShopScreen.this.sidePanel.refreshWidgets();
            ShopScreen.this.tabsPanel.refreshWidgets();
            ShopScreen.this.gridPanel.refreshWidgets();
            ShopScreen.this.footerPanel.refreshWidgets();
            ShopScreen.this.scrollBar.setValue(0);
            ShopClientData.setStatus(Component.translatable(
                    side == ShopSide.BUY ? KEY + "status.side.buy" : KEY + "status.side.sell"));
        }

        @Override
        public void draw(GuiGraphics graphics, Theme theme, int x, int y, int w, int h) {
            boolean active = isActive();
            ShopRender.roundedRect(graphics, x, y, w, h, 4, active ? ShopRender.TAB_ACTIVE : ShopRender.TAB_IDLE);
            if (!active && isMouseOver()) {
                ShopRender.roundedRect(graphics, x, y, w, h, 4, ShopRender.SLOT_HOVER);
            }
            ShopRender.roundedRect(graphics, x, y, 2, h, 1, active ? ShopRender.ACCENT : 0x00000000);

            int iconY = y + 6;
            this.icon.draw(graphics, x + (w - 16) / 2, iconY, 16, 16);
            String label = theme.trimStringToWidth(getTitle().getString(), Math.max(1, w - 4));
            theme.drawString(graphics, label, x + (w - theme.getStringWidth(label)) / 2, y + h - 15,
                    active ? Color4I.rgb(0xFFFFFF) : Color4I.rgb(0xC0C8D8), Theme.SHADOW);
        }

        @Override
        public void addMouseOverText(TooltipList list) {
            list.add(getTitle());
            list.add(Component.translatable(KEY
                    + (side == ShopSide.BUY ? "side.buy.tip" : "side.sell.tip")));
        }
    }

    // ------------------------------------------------------------------
    // 分类页签
    // ------------------------------------------------------------------

    private class TabsPanel extends Panel {
        private static final int TAB_H = 20;
        private static final int TAB_GAP = 3;
        private static final int ROW_GAP = 2;

        TabsPanel(Panel parent) {
            super(parent);
        }

        @Override
        public void addWidgets() {
            add(new TabButton(this, "", Component.translatable(KEY + "tab.all"), null));
            boolean sellSide = ShopClientData.side() == ShopSide.SELL;
            for (ShopDataPayload.CategoryData category : ShopClientData.categories()) {
                // in/ 目录的分类只出现在「买入」栏位，out/ 目录的分类只出现在「卖出」栏位。
                if (category.sellSide() != sellSide) {
                    continue;
                }
                // 分类名：配置里写了 display_name 就用它（支持翻译键），否则用内置分类翻译键，
                // 再否则退回分类 id —— 名字不再需要服主在 JSON 里维护。
                Component label = ItemNameUtil.categoryName(category.displayName(), category.id());
                add(new TabButton(this, category.id(), label, category.icon().isEmpty() ? null : category.icon()));
            }
        }

        @Override
        public void alignWidgets() {
            int avail = Math.max(1, width);
            int x = 0;
            int y = 0;
            for (Widget widget : getWidgets()) {
                if (!(widget instanceof TabButton tab)) {
                    continue;
                }
                int tabWidth = Math.min(tab.preferredWidth(), avail);
                if (x > 0 && x + tabWidth > avail) {
                    x = 0;
                    y += TAB_H + ROW_GAP;
                }
                tab.setPosAndSize(x, 1 + y, tabWidth, TAB_H);
                x += tabWidth + TAB_GAP;
            }
        }

        /** 页签按可用宽度换行后需要的高度（单行时就是 {@code TAB_H}）。 */
        int requiredHeight(int availableWidth) {
            int avail = Math.max(1, availableWidth);
            int x = 0;
            int rows = 1;
            for (Widget widget : getWidgets()) {
                if (!(widget instanceof TabButton tab)) {
                    continue;
                }
                int tabWidth = Math.min(tab.preferredWidth(), avail);
                if (x > 0 && x + tabWidth > avail) {
                    rows++;
                    x = 0;
                }
                x += tabWidth + TAB_GAP;
            }
            // 上下各留 1px：第一行从 y+1 开始画，最后一行要能画完 TAB_H 高度。
            return rows * TAB_H + (rows - 1) * ROW_GAP + 2;
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

        /** 文字左边界相对按钮的左偏移：有图标时先让出 16px 图标 + 间距。 */
        private int textOffset() {
            return this.icon.isEmpty() ? 6 : 24;
        }

        int preferredWidth() {
            int textWidth = getGui().getTheme().getStringWidth(getTitle().getString());
            // 左内边距 + 图标 + 图标与文字的间距 + 文字宽度 + 右内边距：保证整串文字都能画下。
            return this.icon.isEmpty() ? textWidth + 14 : textWidth + 32;
        }

        private boolean isActive() {
            return ShopClientData.categoryId().equals(categoryId);
        }

        @Override
        public void onClicked(MouseButton button) {
            // 编辑模式下的右键：打开分类编辑器（改名 / 换图标）。
            if (button.isRight()) {
                if (!categoryId.isEmpty()) {
                    playClickSound();
                    openCategoryEditorFor(categoryId);
                }
                return;
            }
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
            int textX = x + textOffset();
            if (!this.icon.isEmpty()) {
                this.icon.draw(graphics, x + 4, y + 2, 16, 16);
            }
            theme.drawString(graphics, theme.trimStringToWidth(getTitle().getString(), w - textOffset() - 3),
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
            boolean showCreate = showCreateTile();
            grid = VirtualGrid.of(width, height, visible.size() + (showCreate ? 1 : 0),
                    TILE_W, TILE_H, TILE_GAP, TILE_GAP);
            windowFrom = grid.firstVisibleIndex(getScrollY());
            windowTo = grid.lastVisibleIndex(getScrollY());
            for (int i = windowFrom; i < windowTo; i++) {
                Button button;
                if (showCreate && i == 0) {
                    button = new CreateItemButton(this);
                } else {
                    int itemIndex = i - (showCreate ? 1 : 0);
                    if (itemIndex < 0 || itemIndex >= visible.size()) {
                        continue;
                    }
                    button = new ItemButton(this, visible.get(itemIndex));
                }
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
                if (widget instanceof ItemButton || widget instanceof CreateItemButton) {
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
            // 编辑模式下左键 = 删除这条商品（先弹确认框）。
            if (ShopClientData.editMode()) {
                confirmDelete(entry);
                return;
            }
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

            boolean buySide = !entry.sellSide();
            boolean soldOut = buySide && !entry.unlimited() && entry.remaining() <= 0;
            this.icon.draw(graphics, x + 4, y + 4, 16, 16);

            int textX = x + 24;
            int maxTextWidth = w - 28;
            theme.drawString(graphics, theme.trimStringToWidth(plainNameOf(entry), maxTextWidth),
                    textX, y + 5, soldOut ? Color4I.rgb(0x808A9A) : Color4I.rgb(0xFFFFFF), Theme.SHADOW);

            // 买入条目只显示买入价，卖出条目只显示卖出价：两边价格来自不同目录，互不影响。
            Component priceLine = buySide
                    ? Component.translatable(KEY + "tile.buy", formatMoney(entry.price()))
                    : Component.translatable(KEY + "tile.sell", formatMoney(entry.sellPrice()));
            theme.drawString(graphics, priceLine.getString(), textX, y + 16,
                    buySide ? Color4I.rgb(0xFFC94A) : Color4I.rgb(0x8FD48F), Theme.SHADOW);

            if (buySide) {
                String stockText = entry.unlimited()
                        ? Component.translatable(KEY + "stock.unlimited").getString()
                        : Component.translatable(KEY + "stock.left", entry.remaining()).getString();
                theme.drawString(graphics, stockText, textX, y + 26,
                        soldOut ? Color4I.rgb(0xE05A5A) : Color4I.rgb(0x9AA6B8), Theme.SHADOW);
            }
        }

        @Override
        public void addMouseOverText(TooltipList list) {
            list.add(displayNameOf(entry));
            if (entry.sellSide()) {
                list.add(Component.translatable(KEY + "tip.sell_price", formatMoney(entry.sellPrice())));
            } else {
                list.add(Component.translatable(KEY + "tip.price", formatMoney(entry.price())));
                list.add(entry.unlimited()
                        ? Component.translatable(KEY + "stock.unlimited")
                        : Component.translatable(KEY + "stock.left", entry.remaining()));
            }
            list.add(Component.translatable(KEY + "tip.category", entry.categoryId()));
            list.add(Component.translatable(KEY + "tip.item_id", entry.template().getItem().getDescriptionId()));
            if (entry.customComponents()) {
                // 附魔确实随包同步过来了，这里提示玩家。
                list.add(Component.translatable(KEY + "tip.has_components"));
            }
        }

        @Override
        public WidgetType getWidgetType() {
            return WidgetType.NORMAL;
        }
    }

    // ------------------------------------------------------------------
    // 编辑模式下的「新建商品」格子（固定排第一）
    // ------------------------------------------------------------------

    private class CreateItemButton extends Button {
        CreateItemButton(Panel parent) {
            super(parent, Component.translatable(KEY + "editor.open_item"), Icons.ADD);
            setSize(TILE_W, TILE_H);
        }

        @Override
        public void onClicked(MouseButton button) {
            playClickSound();
            openEditor();
        }

        @Override
        public void draw(GuiGraphics graphics, Theme theme, int x, int y, int w, int h) {
            GuiHelper.setupDrawing();
            ShopRender.roundedRect(graphics, x, y, w, h, 4, ShopRender.SLOT_BG);
            if (isMouseOver()) {
                ShopRender.roundedRect(graphics, x, y, w, h, 4, ShopRender.SLOT_HOVER);
            }
            ShopRender.border(graphics, x, y, w, h, 1, ShopRender.ACCENT);
            this.icon.draw(graphics, x + 4, y + 4, 16, 16);
            theme.drawString(graphics, theme.trimStringToWidth(getTitle().getString(), w - 28),
                    x + 24, y + 5, Color4I.rgb(0xFFFFFF), Theme.SHADOW);
            theme.drawString(graphics, Component.translatable(KEY + "editor.open_item.hint").getString(),
                    x + 24, y + 16, Color4I.rgb(0x8FD48F), Theme.SHADOW);
        }

        @Override
        public void addMouseOverText(TooltipList list) {
            list.add(getTitle());
            list.add(Component.translatable(KEY + "editor.open_item.tip"));
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
            // 买卖分离后底部只显示当前方向对应的那一个操作按钮。
            add(ShopClientData.side() == ShopSide.SELL ? sellButton : buyButton);
        }

        @Override
        public void alignWidgets() {
            quantityBox.setPosAndSize(46, 6, 44, 16);
            buyButton.setPos(98, 4);
            sellButton.setPos(98, 4);
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
