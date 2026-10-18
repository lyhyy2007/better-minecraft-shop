package com.lyhyy2007.bmcs.client;

import com.lyhyy2007.bmcs.BetterMinecraftShop;
import com.lyhyy2007.bmcs.client.ui.ShopRender;
import com.lyhyy2007.bmcs.config.ShopItemEntry;
import com.lyhyy2007.bmcs.config.ShopItemWriter;
import com.lyhyy2007.bmcs.config.ShopSide;
import com.lyhyy2007.bmcs.network.OpenShopPayload;
import com.lyhyy2007.bmcs.network.ShopEditorSavePayload;
import com.lyhyy2007.bmcs.util.ItemNameUtil;
import dev.ftb.mods.ftblibrary.icon.Color4I;
import dev.ftb.mods.ftblibrary.icon.Icon;
import dev.ftb.mods.ftblibrary.icon.Icons;
import dev.ftb.mods.ftblibrary.icon.ItemIcon;
import dev.ftb.mods.ftblibrary.ui.BaseScreen;
import dev.ftb.mods.ftblibrary.ui.Button;
import dev.ftb.mods.ftblibrary.ui.IntTextBox;
import dev.ftb.mods.ftblibrary.ui.Panel;
import dev.ftb.mods.ftblibrary.ui.SimpleTextButton;
import dev.ftb.mods.ftblibrary.ui.Theme;
import dev.ftb.mods.ftblibrary.ui.input.MouseButton;
import dev.ftb.mods.ftblibrary.util.TooltipList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 游戏内「新建商品」编辑器（客户端界面）。
 *
 * <p>从商店界面的「新建商品」进入，当前栏位（买入 / 卖出）决定这条商品存到 {@code in/} 还是
 * {@code out/}。物品来源有两种，任选其一：
 * <ol>
 *   <li><b>全部物品</b>：点「从全部物品选择」打开与分类图标选择器相同的可搜索网格（原版 + 模组 +
 *       数据包物品全都在），挑中的物品按默认形态上架；</li>
 *   <li><b>玩家背包</b>：点下方 36 格背包里的一件物品，取 1 个，附魔等组件原样带走。</li>
 * </ol>
 * 然后填价格（买入价 / 回收价），买入方向还可以填库存（{@code -1} = 不限量），保存 —— 只把
 * 「分类 + 物品 id（或背包槽位）+ 价格 + 库存 + 目录版本」发给服务端，由服务端重新校验并写盘
 * （客户端不直接碰文件）。
 *
 * <p>取消 / ESC 会回到商店（玩家本来就是从商店点进来的）；保存成功后服务端重载配置并
 * 重新打开商店界面，列表里立刻能看到刚建的商品。
 */
public class ShopItemEditorScreen extends BaseScreen implements EditorResultSink {
    private static final int DIALOG_W = 342;
    private static final int DIALOG_H = 232;
    private static final int SLOT = 18;
    private static final int COLS = 9;
    private static final int ROWS = 4;
    private static final int INVENTORY_SLOTS = COLS * ROWS;
    private static final int DEFAULT_PRICE = 10;
    private static final String KEY = "gui." + BetterMinecraftShop.MOD_ID + ".";

    private final ShopSide side;
    /** 目标分类：商品会追加到这个分类所在文件里（只能是游戏内新建的分类）。 */
    private final String categoryId;
    private final String categoryName;
    private final SlotButton[] slots = new SlotButton[INVENTORY_SLOTS];
    private final IntTextBox priceBox;
    private final IntTextBox stockBox;
    private final SimpleTextButton pickAllButton;
    private final SimpleTextButton saveButton;
    private final SimpleTextButton cancelButton;

    /** >= 0 表示来自玩家背包槽位；-1 表示从「全部物品」列表里挑的（服务端按 id 构造）。 */
    private int selectedSlot = -1;
    private ItemStack selected = ItemStack.EMPTY;
    private Component status = Component.empty();
    private boolean saving;
    private boolean saved;
    /** 正在切到选择器（不关闭商店流程）：此时 onClosed() 不能发送「打开商店」。 */
    private boolean switchingScreen;

    /** 打开编辑器时的目录版本：切换界面时商店快照可能被释放，这里自己留一份。 */
    private final int revision;

    public ShopItemEditorScreen(ShopSide side, String categoryId, String categoryName, int revision) {
        this(side, categoryId, categoryName, revision, ItemStack.EMPTY, -1, DEFAULT_PRICE, ShopItemEntry.UNLIMITED);
    }

    public ShopItemEditorScreen(ShopSide side, String categoryId, String categoryName, int revision,
                                ItemStack preselected, int preselectedSlot, int price, int stock) {
        super();
        this.side = side == null ? ShopSide.BUY : side;
        this.categoryId = categoryId == null ? "" : categoryId;
        this.categoryName = categoryName == null ? "" : categoryName;
        this.revision = revision;
        this.selected = preselected == null ? ItemStack.EMPTY : preselected.copyWithCount(1);
        this.selectedSlot = this.selected.isEmpty() ? -1 : preselectedSlot;

        this.priceBox = new IntTextBox(this);
        this.priceBox.setMinMax(ShopItemWriter.MIN_PRICE, ShopItemWriter.MAX_PRICE);
        this.priceBox.setAmount(Math.max(ShopItemWriter.MIN_PRICE, price));

        this.stockBox = new IntTextBox(this);
        this.stockBox.setMinMax(ShopItemEntry.UNLIMITED, ShopItemWriter.MAX_STOCK);
        this.stockBox.setAmount(stock < ShopItemEntry.UNLIMITED ? ShopItemEntry.UNLIMITED : stock);

        this.pickAllButton = SimpleTextButton.create(this,
                Component.translatable(KEY + "editor.pick_all"), Icon.empty(),
                button -> pickFromAllItems(),
                Component.translatable(KEY + "editor.pick_all.tip"));
        this.saveButton = SimpleTextButton.create(this,
                Component.translatable(KEY + "editor.save"), Icons.ACCEPT,
                button -> submit(),
                Component.translatable(KEY + "editor.save.tip"));
        this.cancelButton = SimpleTextButton.create(this,
                Component.translatable(KEY + "editor.cancel"), Icons.CANCEL,
                button -> closeGui(true),
                Component.translatable(KEY + "editor.cancel.tip"));
    }

    // ------------------------------------------------------------------
    // FTB Library 生命周期
    // ------------------------------------------------------------------

    @Override
    public boolean onInit() {
        return setFullscreen();
    }

    @Override
    public void addWidgets() {
        add(priceBox);
        if (side == ShopSide.BUY) {
            add(stockBox);
        }
        add(pickAllButton);
        for (int i = 0; i < INVENTORY_SLOTS; i++) {
            SlotButton button = new SlotButton(this, i);
            slots[i] = button;
            add(button);
        }
        add(saveButton);
        add(cancelButton);
    }

    @Override
    public void alignWidgets() {
        int dx = (width - DIALOG_W) / 2;
        int dy = (height - DIALOG_H) / 2;
        priceBox.setPosAndSize(dx + 62, dy + 54, 56, 16);
        stockBox.setPosAndSize(dx + 178, dy + 54, 56, 16);
        pickAllButton.setPosAndSize(dx + DIALOG_W - 134, dy + 33, 124, 18);

        int gridX = dx + 10;
        int gridY = dy + 84;
        for (int i = 0; i < INVENTORY_SLOTS; i++) {
            SlotButton button = slots[i];
            if (button == null) {
                continue;
            }
            button.setPosAndSize(gridX + (i % COLS) * SLOT, gridY + (i / COLS) * SLOT, SLOT, SLOT);
        }
        saveButton.setPosAndSize(dx + DIALOG_W - 158, dy + DIALOG_H - 30, 72, 18);
        cancelButton.setPosAndSize(dx + DIALOG_W - 80, dy + DIALOG_H - 30, 70, 18);
    }

    @Override
    public Component getTitle() {
        return Component.translatable(KEY + (side == ShopSide.BUY ? "editor.title.buy" : "editor.title.sell"));
    }

    @Override
    public void drawBackground(GuiGraphics graphics, Theme theme, int x, int y, int w, int h) {
        // 半透明遮罩 + 居中对话框：编辑器是弹窗语义，不铺满整屏内容。
        ShopRender.rect(graphics, x, y, w, h, 0x66000000);
        int dx = (width - DIALOG_W) / 2;
        int dy = (height - DIALOG_H) / 2;
        ShopRender.roundedRect(graphics, dx, dy, DIALOG_W, DIALOG_H, 6, 0xF21A2130);
        ShopRender.border(graphics, dx, dy, DIALOG_W, DIALOG_H, 1, ShopRender.PANEL_BORDER);
        ShopRender.rect(graphics, dx, dy, DIALOG_W, 20, ShopRender.TAB_ACTIVE);

        theme.drawString(graphics, getTitle().getString(), dx + 8, dy + 6, Color4I.rgb(0xFFFFFF), Theme.SHADOW);
        theme.drawString(graphics,
                theme.trimStringToWidth(Component.translatable(KEY + "editor.category",
                        categoryName.isEmpty() ? categoryId : categoryName).getString(), DIALOG_W - 16),
                dx + 8, dy + 24, Color4I.rgb(0xFFC94A), Theme.SHADOW);

        // 已选物品预览（右侧留给「从全部物品选择」按钮）
        int previewX = dx + 10;
        int previewY = dy + 34;
        ShopRender.roundedRect(graphics, previewX, previewY, 20, 20, 3, ShopRender.SLOT_BG);
        ShopRender.border(graphics, previewX, previewY, 20, 20, 1, ShopRender.PANEL_BORDER);
        if (selected.isEmpty()) {
            theme.drawString(graphics, Component.translatable(KEY + "editor.preview.none").getString(),
                    previewX + 26, previewY + 6, Color4I.rgb(0x9AA6B8), Theme.SHADOW);
        } else {
            ItemIcon.getItemIcon(selected).draw(graphics, previewX + 2, previewY + 2, 16, 16);
            theme.drawString(graphics,
                    theme.trimStringToWidth(ItemNameUtil.plainText(selected), DIALOG_W - 168),
                    previewX + 26, previewY + 6, Color4I.rgb(0xFFFFFF), Theme.SHADOW);
        }

        theme.drawString(graphics,
                Component.translatable(KEY + (side == ShopSide.BUY ? "editor.price" : "editor.sell_price")).getString(),
                dx + 10, dy + 58, Color4I.rgb(0xC0C8D8), Theme.SHADOW);
        if (side == ShopSide.BUY) {
            theme.drawString(graphics, Component.translatable(KEY + "editor.stock").getString(),
                    dx + 126, dy + 58, Color4I.rgb(0xC0C8D8), Theme.SHADOW);
        }
        theme.drawString(graphics, Component.translatable(KEY + "editor.pick").getString(),
                dx + 10, dy + 74, Color4I.rgb(0x9AA6B8), Theme.SHADOW);

        if (!status.getString().isEmpty()) {
            theme.drawString(graphics, theme.trimStringToWidth(status.getString(), DIALOG_W - 20),
                    dx + 10, dy + 164, Color4I.rgb(0x8FD48F), Theme.SHADOW);
        }
        // 落盘位置：让玩家知道改的是哪个目录（也方便服主备份）。
        String fileHint = "config/" + BetterMinecraftShop.BRAND + "/" + (side.sells() ? "out" : "in") + "/";
        theme.drawString(graphics, theme.trimStringToWidth(fileHint, DIALOG_W - 20),
                dx + 10, dy + DIALOG_H - 26, Color4I.rgb(0x6A7688), Theme.SHADOW);
    }

    @Override
    public void onClosed() {
        super.onClosed();
        if (!saved && !switchingScreen) {
            // 取消 / ESC：回到商店（玩家是从商店的「新建商品」进来的）。
            PacketDistributor.sendToServer(OpenShopPayload.INSTANCE);
        }
    }

    // ------------------------------------------------------------------
    // 选择物品：全部物品 / 背包
    // ------------------------------------------------------------------

    /** 打开与分类图标选择器同款的全物品网格；选中后带着已填的价格库存重新打开编辑器。 */
    private void pickFromAllItems() {
        int price = priceBox.getIntValue();
        int stock = side == ShopSide.BUY ? stockBox.getIntValue() : ShopItemEntry.UNLIMITED;
        switchingScreen = true;
        new IconPickerScreen(picked -> {
            ResourceLocation id = ResourceLocation.tryParse(picked);
            Item item = id == null ? null : BuiltInRegistries.ITEM.get(id);
            if (item == null || item == Items.AIR) {
                new ShopItemEditorScreen(side, categoryId, categoryName, revision, ItemStack.EMPTY, -1, price, stock)
                        .openGui();
                return;
            }
            new ShopItemEditorScreen(side, categoryId, categoryName, revision, new ItemStack(item), -1, price, stock)
                    .openGui();
        }, true).openGui();
    }

    // ------------------------------------------------------------------
    // 保存
    // ------------------------------------------------------------------

    private void submit() {
        if (saving) {
            return;
        }
        if (selected.isEmpty()) {
            status = Component.translatable(KEY + "editor.status.no_item");
            return;
        }
        int price = priceBox.getIntValue();
        if (price < ShopItemWriter.MIN_PRICE || price > ShopItemWriter.MAX_PRICE) {
            status = Component.translatable(KEY + "editor.status.price_range",
                    ShopItemWriter.MIN_PRICE, ShopItemWriter.MAX_PRICE);
            return;
        }
        int stock = side == ShopSide.BUY ? stockBox.getIntValue() : ShopItemEntry.UNLIMITED;
        if (side == ShopSide.BUY && stock != ShopItemEntry.UNLIMITED
                && (stock < 0 || stock > ShopItemWriter.MAX_STOCK)) {
            status = Component.translatable(KEY + "editor.status.stock_range",
                    ShopItemEntry.UNLIMITED, ShopItemWriter.MAX_STOCK);
            return;
        }
        if (revision < 0) {
            status = Component.translatable(KEY + "editor.status.no_data");
            return;
        }
        if (categoryId.isEmpty()) {
            status = Component.translatable(KEY + "editor.status.need_category");
            return;
        }
        String itemId = String.valueOf(BuiltInRegistries.ITEM.getKey(selected.getItem()));
        PacketDistributor.sendToServer(new ShopEditorSavePayload(side, categoryId, selectedSlot, itemId, price, stock,
                revision));
        saving = true;
        status = Component.translatable(KEY + "editor.status.saving");
    }

    /** 服务端保存结果（由 {@link ClientShopBridge} 转进来）。 */
    public void onEditorResult(boolean success, Component message) {
        saving = false;
        status = message == null ? Component.empty() : message;
        if (success) {
            saved = true;
            closeGui(true);
        }
    }

    // ------------------------------------------------------------------
    // 背包格
    // ------------------------------------------------------------------

    private class SlotButton extends Button {
        private final int index;
        private ItemStack cached = ItemStack.EMPTY;

        SlotButton(Panel parent, int index) {
            super(parent, Component.empty(), Icon.empty());
            this.index = index;
            setSize(SLOT, SLOT);
        }

        private ItemStack stack() {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player == null) {
                return ItemStack.EMPTY;
            }
            return minecraft.player.getInventory().getItem(index);
        }

        private void refreshIcon() {
            ItemStack stack = stack();
            if (!ItemStack.isSameItemSameComponents(cached, stack)) {
                cached = stack.isEmpty() ? ItemStack.EMPTY : stack.copy();
                this.icon = stack.isEmpty() ? Icon.empty() : ItemIcon.getItemIcon(stack);
            }
        }

        @Override
        public void onClicked(MouseButton button) {
            playClickSound();
            ItemStack stack = stack();
            if (stack.isEmpty()) {
                status = Component.translatable(KEY + "editor.slot.empty");
                return;
            }
            selected = stack.copyWithCount(1);
            selectedSlot = index;
            if (priceBox.getIntValue() < ShopItemWriter.MIN_PRICE) {
                priceBox.setAmount(DEFAULT_PRICE);
            }
            status = Component.translatable(KEY + "editor.selected", ItemNameUtil.plainText(selected));
        }

        @Override
        public void draw(GuiGraphics graphics, Theme theme, int x, int y, int w, int h) {
            refreshIcon();
            ShopRender.roundedRect(graphics, x, y, w, h, 3, ShopRender.SLOT_BG);
            if (index == selectedSlot) {
                ShopRender.border(graphics, x, y, w, h, 1, ShopRender.ACCENT);
            } else if (isMouseOver()) {
                ShopRender.roundedRect(graphics, x, y, w, h, 3, ShopRender.SLOT_HOVER);
            }
            if (!this.icon.isEmpty()) {
                this.icon.draw(graphics, x + 1, y + 1, 16, 16);
            }
        }

        @Override
        public void addMouseOverText(TooltipList list) {
            ItemStack stack = stack();
            if (stack.isEmpty()) {
                return;
            }
            list.add(ItemNameUtil.displayName(stack));
            list.add(Component.translatable(KEY + "editor.slot.tip"));
        }
    }
}
