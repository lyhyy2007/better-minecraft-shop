package com.lyhyy2007.bmcs.shop;

import com.lyhyy2007.bmcs.BetterMinecraftShop;
import com.lyhyy2007.bmcs.config.ShopConfig;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

/**
 * 配置文件热重载：用 {@link WatchService} 监听 {@code config/better-minecraft-shop/} 的变更。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>防抖</b>：编辑器保存一个文件往往触发多个事件（写入 + 重命名），
 *       因此收到事件后等 {@link #DEBOUNCE_NANOS} 静默期再重载，避免解析到写了一半的文件；</li>
 *   <li><b>回到主线程</b>：监听线程只做「发现变化」，真正的解析通过 {@link MinecraftServer#execute}
 *       派回服务端主线程 —— 配置解析要访问注册表（附魔等 DataComponent 的 codec），
 *       而且库存与网络发送本来就必须在主线程；</li>
 *   <li><b>整体重载而不是增量</b>：去重优先级是跨文件的，增量更新反而容易出现
 *       「A 文件删了这条、B 文件的重复项该顶上来了」这类错误，所以一次性整体重扫；
 *       10000 条配置的解析只需要几十毫秒（见 {@code /bmcs reload} 的耗时输出）；</li>
 *   <li><b>健壮</b>：目录被删除 / 改名 / 监视服务溢出时重新注册，监听线程是守护线程，不会阻止关服。</li>
 * </ul>
 */
public final class ShopConfigWatcher implements AutoCloseable {
    /** 事件静默期：1.5 秒内没有新事件才真正重载。 */
    private static final long DEBOUNCE_NANOS = 1_500_000_000L;
    /** 监听轮询间隔（毫秒）。 */
    private static final long POLL_MILLIS = 400L;

    @Nullable
    private static ShopConfigWatcher instance;

    private final MinecraftServer server;
    private final WatchService watchService;
    private final Thread thread;
    private volatile boolean running = true;
    private volatile long lastChange;
    private volatile int reloads;

    private ShopConfigWatcher(MinecraftServer server) throws IOException {
        this.server = server;
        this.watchService = FileSystems.getDefault().newWatchService();
        registerRecursively(ShopConfig.get().configDir());
        this.thread = new Thread(this::loop, "bmcs-config-watcher");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    /** 启动监听（服务端启动、配置加载完成后调用）。重复调用会先停掉旧的。 */
    public static synchronized void start(MinecraftServer server) {
        stop();
        try {
            instance = new ShopConfigWatcher(server);
            BetterMinecraftShop.LOGGER.info("[{}] 已开始监听配置目录变更：{}",
                    BetterMinecraftShop.MOD_NAME, ShopConfig.get().configDir().toAbsolutePath());
        } catch (IOException e) {
            BetterMinecraftShop.LOGGER.warn("[{}] 无法启动配置目录监听，热重载不可用（可用 /bmcs reload 手动重载）",
                    BetterMinecraftShop.MOD_NAME, e);
        }
    }

    /** 停止监听（服务端停止时调用）。 */
    public static synchronized void stop() {
        if (instance != null) {
            instance.close();
            instance = null;
        }
    }

    /** 是否正在监听。 */
    public static synchronized boolean active() {
        return instance != null;
    }

    /** 本次服务端运行期间由热重载触发的重载次数。 */
    public static synchronized int reloadCount() {
        return instance == null ? 0 : instance.reloads;
    }

    private void registerRecursively(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isDirectory).forEach(dir -> {
                try {
                    dir.register(watchService,
                            StandardWatchEventKinds.ENTRY_CREATE,
                            StandardWatchEventKinds.ENTRY_MODIFY,
                            StandardWatchEventKinds.ENTRY_DELETE);
                } catch (IOException e) {
                    BetterMinecraftShop.LOGGER.debug("[{}] 无法监听子目录 {}",
                            BetterMinecraftShop.MOD_NAME, dir, e);
                }
            });
        } catch (IOException e) {
            BetterMinecraftShop.LOGGER.warn("[{}] 遍历配置目录失败，热重载可能不完整：{}",
                    BetterMinecraftShop.MOD_NAME, root, e);
        }
    }

    private void loop() {
        while (running) {
            try {
                WatchKey key = watchService.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
                if (key != null) {
                    boolean changed = false;
                    boolean created = false;
                    for (WatchEvent<?> event : key.pollEvents()) {
                        changed = true;
                        if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                            created = true;
                        }
                    }
                    if (!key.reset()) {
                        // 目录被删除 / 不可访问：重新扫一遍配置目录，稍后若重新出现会再次注册。
                        registerRecursively(ShopConfig.get().configDir());
                    } else if (created) {
                        // 新建了子目录（例如在 in/ 或 out/ 下再分子文件夹），也要纳入监听。
                        registerRecursively(ShopConfig.get().configDir());
                    }
                    if (changed) {
                        lastChange = System.nanoTime();
                    }
                }
                if (lastChange != 0L && System.nanoTime() - lastChange > DEBOUNCE_NANOS) {
                    lastChange = 0L;
                    triggerReload();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ClosedWatchServiceException e) {
                return;
            } catch (RuntimeException e) {
                BetterMinecraftShop.LOGGER.warn("[{}] 配置监听线程出现异常（已继续运行）",
                        BetterMinecraftShop.MOD_NAME, e);
            }
        }
    }

    private void triggerReload() {
        if (!running || !server.isRunning()) {
            return;
        }
        reloads++;
        // 解析必须回到服务端主线程：既要访问注册表，也要改库存、发网络包。
        server.execute(() -> {
            if (!running) {
                return;
            }
            BetterMinecraftShop.LOGGER.info("[{}] 检测到配置文件变化，自动重载（第 {} 次）",
                    BetterMinecraftShop.MOD_NAME, reloads);
            ShopManager.get().reload(server);
            BetterMinecraftShop.LOGGER.info("[{}] 自动重载完成：{}",
                    BetterMinecraftShop.MOD_NAME, ShopConfig.get().statistics());
        });
    }

    @Override
    public void close() {
        running = false;
        try {
            watchService.close();
        } catch (IOException e) {
            BetterMinecraftShop.LOGGER.debug("[{}] 关闭配置监听失败", BetterMinecraftShop.MOD_NAME, e);
        }
        thread.interrupt();
        try {
            thread.join(1_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        BetterMinecraftShop.LOGGER.info("[{}] 已停止监听配置目录", BetterMinecraftShop.MOD_NAME);
    }
}
