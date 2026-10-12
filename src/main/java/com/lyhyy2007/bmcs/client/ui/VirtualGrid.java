package com.lyhyy2007.bmcs.client.ui;

/**
 * 商品网格的虚拟滚动几何计算。
 *
 * <p>为什么单独抽出来：需求要求 10000+ 条目下打开 &lt; 500ms、滚动 60fps。
 * 天真的做法是给每个商品建一个 Widget（FTB 的 {@code Panel} 会遍历所有子控件绘制与命中测试），
 * 10000 个控件每帧的代价是灾难性的。虚拟滚动的做法是：<b>只为可视区域内的槽位建控件</b>，
 * 滚动时按「首个可见行变化」重建这几十个控件。
 *
 * <p>本类是纯整数运算、不引用任何 Minecraft / FTB 类型，因此可以脱离游戏单独跑校验
 * （见 {@code tools/VirtualGridCheck.java}）：越界、空列表、单列、超大列表等边界都在那里验证过。
 */
public final class VirtualGrid {
    /** 可视区域上下各多渲染一行，避免滚动时出现空行闪烁。 */
    public static final int OVERSCAN_ROWS = 1;

    private final int panelWidth;
    private final int panelHeight;
    private final int count;
    private final int tileWidth;
    private final int tileHeight;
    private final int gap;
    private final int pad;
    private final int columns;
    private final int rows;

    private VirtualGrid(int panelWidth, int panelHeight, int count, int tileWidth, int tileHeight, int gap, int pad) {
        this.panelWidth = Math.max(1, panelWidth);
        this.panelHeight = Math.max(1, panelHeight);
        this.count = Math.max(0, count);
        this.tileWidth = Math.max(1, tileWidth);
        this.tileHeight = Math.max(1, tileHeight);
        this.gap = Math.max(0, gap);
        this.pad = Math.max(0, pad);
        int usable = this.panelWidth - this.pad * 2 + this.gap;
        this.columns = Math.max(1, usable / (this.tileWidth + this.gap));
        this.rows = this.count == 0 ? 0 : (this.count + this.columns - 1) / this.columns;
    }

    public static VirtualGrid of(int panelWidth, int panelHeight, int count, int tileWidth, int tileHeight, int gap,
                                 int pad) {
        return new VirtualGrid(panelWidth, panelHeight, count, tileWidth, tileHeight, gap, pad);
    }

    public int columns() {
        return columns;
    }

    public int rows() {
        return rows;
    }

    public int count() {
        return count;
    }

    public int gap() {
        return gap;
    }

    public int pad() {
        return pad;
    }

    /** 行高（含行间距）。 */
    public int rowHeight() {
        return tileHeight + gap;
    }

    /** 一次滚轮滚动的距离：正好一行。 */
    public int scrollStep() {
        return rowHeight();
    }

    /** 内容总高度（供 Panel#getContentHeight 覆盖使用）。 */
    public int contentHeight() {
        if (rows == 0) {
            return pad * 2;
        }
        return pad * 2 + rows * rowHeight() - gap;
    }

    /** 最大滚动量。 */
    public int maxScroll() {
        return Math.max(0, contentHeight() - panelHeight);
    }

    public int rowOf(int index) {
        return columns <= 1 ? index : index / columns;
    }

    public int columnOf(int index) {
        return columns <= 1 ? 0 : index % columns;
    }

    /** 槽位 x（内容坐标）。 */
    public int x(int index) {
        return pad + columnOf(index) * (tileWidth + gap);
    }

    /** 槽位 y（内容坐标，不含滚动偏移 —— 偏移由 FTB 的 Panel 自己处理）。 */
    public int y(int index) {
        return pad + rowOf(index) * rowHeight();
    }

    /** 某个滚动位置下的首个可见行。 */
    public int firstVisibleRow(double scrollY) {
        if (rows == 0) {
            return 0;
        }
        int row = (int) Math.floor((scrollY - pad) / rowHeight());
        return clampRow(row - OVERSCAN_ROWS);
    }

    /** 某个滚动位置下的末个可见行。 */
    public int lastVisibleRow(double scrollY) {
        if (rows == 0) {
            return -1;
        }
        int row = (int) Math.ceil((scrollY + panelHeight - pad) / rowHeight()) - 1;
        return clampRow(row + OVERSCAN_ROWS);
    }

    /** 需要建立控件的起始下标（含）。 */
    public int firstVisibleIndex(double scrollY) {
        if (count == 0) {
            return 0;
        }
        return Math.min(count - 1, firstVisibleRow(scrollY) * columns);
    }

    /** 需要建立控件的结束下标（不含）。 */
    public int lastVisibleIndex(double scrollY) {
        if (count == 0) {
            return 0;
        }
        return Math.min(count, (lastVisibleRow(scrollY) + 1) * columns);
    }

    private int clampRow(int row) {
        return Math.max(0, Math.min(rows - 1, row));
    }
}
