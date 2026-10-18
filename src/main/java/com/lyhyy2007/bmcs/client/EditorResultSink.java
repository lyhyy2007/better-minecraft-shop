package com.lyhyy2007.bmcs.client;

import net.minecraft.network.chat.Component;

/**
 * 能接收「游戏内编辑」保存结果的界面。
 *
 * <p>服务端对所有编辑器类操作（新建分类、改名换图标、新建商品、删除商品）统一回一个
 * {@code shop_editor_result} 包，客户端拿到后转发给当前打开的那个编辑器；普通商店界面
 * 不实现这个接口，也就不会被打扰。
 */
public interface EditorResultSink {
    /**
     * @param success 服务端是否已经写盘成功
     * @param message 给玩家看的一句话（成功或失败原因）
     */
    void onEditorResult(boolean success, Component message);
}
