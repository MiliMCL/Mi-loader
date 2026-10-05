package org.loader.api.transform;

import org.loader.api.transform.symbol.MiliSymbol;
import org.loader.api.transform.target.TargetMethod;

/**
 * 测试专用的符号访问辅助。
 *
 * <p><b>存在的理由</b>：{@link MiliSymbol} 的常量是 {@code public static final}，
 * 原则上直接引用即可。但把符号坐标的断言集中在这里有一个额外好处 ——
 * 当这些坐标需要与真实 Minecraft jar 交叉校验时，只需改动这一个文件。
 *
 * <p>CI 中的符号校验任务正是通过比对这里的值与真实 26.2 class 文件来
 * 防止文档漂移的 —— 本次审计就发现过两处漂移：
 * {@code tickServer()} 与 {@code tickChildren(long)}。
 */
final class MiliSymbolAccess {

    private MiliSymbolAccess() {
    }

    static TargetMethod serverTick() {
        return MiliSymbol.SERVER_TICK;
    }

    static String serverTickOwner() {
        // owner() 返回 JVM 内部名（斜杠分隔）—— 这才是 ASM 与类加载用的形式。
        // ownerDotted() 是给人看的点分名，用在这里会让断言与真实坐标脱钩。
        return MiliSymbol.SERVER_TICK.owner();
    }

    static String serverTickName() {
        return MiliSymbol.SERVER_TICK.name();
    }

    static String serverTickDescriptor() {
        return MiliSymbol.SERVER_TICK.descriptor();
    }

    static TargetMethod serverTickChildren() {
        return MiliSymbol.SERVER_TICK_CHILDREN;
    }

    static TargetMethod clientLevelTick() {
        return MiliSymbol.CLIENT_LEVEL_TICK;
    }

    static TargetMethod clientMain() {
        return MiliSymbol.CLIENT_MAIN;
    }

    static TargetMethod serverMain() {
        return MiliSymbol.SERVER_MAIN;
    }

    static String minecraftVersion() {
        return MiliSymbol.MINECRAFT_VERSION;
    }

    static boolean isClientOnly(TargetMethod symbol) {
        return MiliSymbol.isClientOnly(symbol);
    }
}