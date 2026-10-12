/**
 * 虚拟滚动几何自检：纯整数运算，不依赖 Minecraft / FTB Library。
 *
 * <p>运行方式（编译产物已在 build/classes/java/main 里）：
 * <pre>
 *   java --class-path build/classes/java/main tools/VirtualGridCheck.java
 * </pre>
 * 之所以能这样跑，是因为 {@code VirtualGrid} 刻意不引用任何游戏类型；
 * 于是「10000 条商品下窗口是否只覆盖可视区域」「滚动是否覆盖到每一条」
 * 这些关键行为可以在没有 GPU、没有客户端的情况下被机器验证。
 */
import com.lyhyy2007.bmcs.client.ui.VirtualGrid;
import java.util.TreeSet;

public final class VirtualGridCheck {
    private static int checks;
    private static int failures;

    private VirtualGridCheck() {
    }

    public static void main(String[] args) {
        emptyList();
        singleItem();
        narrowPanel();
        windowSizeTenThousand();
        scrollCoversEveryIndex();
        bottomReachable();
        scrollStepIntegrity();
        System.out.printf("%n共 %d 项断言，失败 %d 项%n", checks, failures);
        if (failures > 0) {
            System.exit(1);
        }
        System.out.println("虚拟滚动几何自检全部通过。");
    }

    // 网格参数与 ShopScreen 保持一致：104x44 的槽位、4px 间距、4px 内边距。
    private static final int TILE_W = 104;
    private static final int TILE_H = 44;
    private static final int GAP = 4;
    private static final int PAD = 4;
    private static final int PANEL_W = 400;
    private static final int PANEL_H = 300;

    private static void emptyList() {
        VirtualGrid grid = VirtualGrid.of(PANEL_W, PANEL_H, 0, TILE_W, TILE_H, GAP, PAD);
        check(grid.rows() == 0, "空列表 rows=0", grid.rows());
        check(grid.firstVisibleIndex(0) == 0, "空列表窗口起点为 0", grid.firstVisibleIndex(0));
        check(grid.lastVisibleIndex(0) == 0, "空列表窗口终点为 0", grid.lastVisibleIndex(0));
        check(grid.maxScroll() == 0, "空列表不可滚动", grid.maxScroll());
    }

    private static void singleItem() {
        VirtualGrid grid = VirtualGrid.of(PANEL_W, PANEL_H, 1, TILE_W, TILE_H, GAP, PAD);
        check(grid.rows() == 1, "单条 rows=1", grid.rows());
        check(grid.columns() == 3, "宽 400 时 3 列", grid.columns());
        check(grid.x(0) == PAD, "首列 x = 内边距", grid.x(0));
        check(grid.y(0) == PAD, "首行 y = 内边距", grid.y(0));
        check(grid.maxScroll() == 0, "单条不可滚动", grid.maxScroll());
        check(grid.firstVisibleIndex(0) == 0 && grid.lastVisibleIndex(0) == 1, "单条位于窗口内");
    }

    private static void narrowPanel() {
        VirtualGrid grid = VirtualGrid.of(50, 120, 10, TILE_W, TILE_H, GAP, PAD);
        check(grid.columns() == 1, "面板比槽位窄时至少 1 列", grid.columns());
        check(grid.rows() == 10, "单列时行数 = 条目数", grid.rows());
    }

    private static void windowSizeTenThousand() {
        VirtualGrid grid = VirtualGrid.of(PANEL_W, PANEL_H, 10000, TILE_W, TILE_H, GAP, PAD);
        check(grid.rows() == 3334, "10000 条 / 3 列 = 3334 行", grid.rows());
        int window = grid.lastVisibleIndex(0) - grid.firstVisibleIndex(0);
        check(window > 0 && window <= 30, "窗口内控件数 20~30 个", window);
        int content = grid.contentHeight();
        check(content == PAD * 2 + grid.rows() * grid.rowHeight() - GAP,
                "内容高度 = 内边距 + 行数 * 行高 - 间距", content);
        check(grid.maxScroll() == content - PANEL_H, "最大滚动量 = 内容高度 - 面板高度", grid.maxScroll());
    }

    /** 关键不变量：把滚动条从顶拖到底，任意一条商品都必须至少出现在某个窗口里。 */
    private static void scrollCoversEveryIndex() {
        VirtualGrid grid = VirtualGrid.of(PANEL_W, PANEL_H, 10000, TILE_W, TILE_H, GAP, PAD);
        TreeSet<Integer> covered = new TreeSet<>();
        for (int scroll = 0; scroll <= grid.maxScroll(); scroll += 2) {
            for (int i = grid.firstVisibleIndex(scroll); i < grid.lastVisibleIndex(scroll); i++) {
                covered.add(i);
            }
        }
        check(covered.size() == 10000, "滚动一遍覆盖全部 10000 条", covered.size());
        check(covered.first() == 0, "覆盖起点是第 0 条", covered.first());
        check(covered.last() == 9999, "覆盖终点是第 9999 条", covered.last());
    }

    private static void bottomReachable() {
        VirtualGrid grid = VirtualGrid.of(PANEL_W, PANEL_H, 10000, TILE_W, TILE_H, GAP, PAD);
        int bottom = grid.maxScroll();
        check(grid.lastVisibleIndex(bottom) == 10000, "滚到底时窗口包含最后一条", grid.lastVisibleIndex(bottom));
        int lastRow = grid.lastVisibleRow(bottom);
        check(grid.rowOf(9999) == lastRow, "最后一条所在行就是末行", grid.rowOf(9999) + "/" + lastRow);
        // 越界滚动不应产生越界下标
        check(grid.lastVisibleIndex(bottom + 100000) <= 10000, "超范围滚动仍被夹取");
        check(grid.firstVisibleIndex(-100000) == 0, "负滚动被夹取到 0");
    }

    private static void scrollStepIntegrity() {
        VirtualGrid grid = VirtualGrid.of(PANEL_W, PANEL_H, 100, TILE_W, TILE_H, GAP, PAD);
        check(grid.scrollStep() == TILE_H + GAP, "一次滚轮滚动正好一行", grid.scrollStep());
        int window = grid.lastVisibleIndex(0) - grid.firstVisibleIndex(0);
        check(window <= grid.rows() * grid.columns(), "窗口不会超过总条目数", window);
        // 单列布局下，第 3 条的 y 必须正好是 3 个行高（行距一致）。
        VirtualGrid single = VirtualGrid.of(50, PANEL_H, 100, TILE_W, TILE_H, GAP, PAD);
        check(single.y(3) - single.y(0) == 3 * single.rowHeight(), "同行行距一致（单列）",
                single.y(3) - single.y(0));
        check(grid.x(3) - grid.x(0) == 0 && grid.x(1) - grid.x(0) == TILE_W + GAP, "同列列距一致");
        check(grid.y(3) - grid.y(0) == grid.rowHeight(), "三列布局下第 4 条换到第二行");
    }

    private static void check(boolean condition, String what) {
        checks++;
        if (condition) {
            System.out.println("  [OK]   " + what);
        } else {
            failures++;
            System.out.println("  [FAIL] " + what);
        }
    }

    private static void check(boolean condition, String what, Object actual) {
        checks++;
        if (condition) {
            System.out.println("  [OK]   " + what);
        } else {
            failures++;
            System.out.println("  [FAIL] " + what + "（实际 " + actual + "）");
        }
    }
}
