package org.loader.runtime.minecraft.client;

import java.util.logging.Logger;

/**
 * 客户端品牌分发器 —— 生成到 {@code ClientBrandRetriever} 字节码里的
 * 唯一调用目标。
 *
 * <h2>它修的是什么问题</h2>
 * 原版 {@code ClientBrandRetriever#getClientModName()} 恒返回
 * {@code "vanilla"}，于是：
 * <ul>
 *   <li>F3 调试屏显示「原版客户端」，玩家无法分辨自己跑的是 Mili；</li>
 *   <li>{@code ModCheck.identify(...)} 判定客户端为未 modded。</li>
 * </ul>
 * {@code MiliClientBrandTransformer} 在该方法上注入 MODIFY_RETURN，
 * 用本类的返回值替换原值。
 *
 * <h2>为什么由本类持有品牌字符串</h2>
 * 注入的字节码只引用本类（与 {@code TickCallbackDispatch} 同一模式：
 * Minecraft 字节码对 Mod 类与游戏类零额外引用，避免 ClassLoader 泄漏）。
 * 品牌名集中在此，便于审计与测试断言。
 */
public final class ClientBrandDispatch {

    private static final Logger LOG = Logger.getLogger("Mili/ClientBrand");

    /** 平台品牌名 —— F3 调试屏与服务器握手所显示的名称。 */
    public static final String BRAND = "Mili-loader";

    private ClientBrandDispatch() {
    }

    /**
     * 注入回调 —— MODIFY_RETURN 的替换值来源。
     *
     * <p>描述符固定为 {@code ()Ljava/lang/String;}：不消费参数，
     * 返回类型与目标方法一致。原返回值（{@code "vanilla"}）在栈上
     * 位于返回值之下，随 ARETURN 一并丢弃。
     */
    public static String getClientModName() {
        return BRAND;
    }

    static {
        LOG.fine(() -> "Client brand dispatch ready: " + BRAND);
    }
}
