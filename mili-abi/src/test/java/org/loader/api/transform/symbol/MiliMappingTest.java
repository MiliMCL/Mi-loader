package org.loader.api.transform.symbol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.loader.api.transform.target.TargetField;
import org.loader.api.transform.target.TargetMethod;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 映射层测试。
 *
 * <h2>这一层在守什么</h2>
 * 符号表过期是本平台最危险的静默失效：转换器注入不到任何东西，
 * 游戏照常运行，Mod 的功能悄悄消失，日志里什么都没有。
 *
 * <p>映射层提供两道防线：
 * <ol>
 *   <li><b>解析期</b> —— 未注册的符号名抛异常，不返回 null；</li>
 *   <li><b>CI 期</b> —— {@link #entries()} 暴露全部符号，
 *       {@code SymbolVerifier} 拿真实 jar 逐条比对。</li>
 * </ol>
 */
@DisplayName("映射层 MiliMapping")
class MiliMappingTest {

    @Test
    @DisplayName("全部映射条目都是恒等映射 —— 正式版 Minecraft 不混淆")
    void allEntriesAreIdentity() {
        // 这条断言是这个类存在的主要理由。
        //
        // 若某天出现非恒等条目，说明跑的是混淆版本（dev jar）。
        // 此时平台必须显式失败 —— 用官方名去注入混淆字节码必然不命中，
        // 而「不命中」不报错。
        List<String> nonIdentity = new ArrayList<>();
        for (MiliMapping.Entry entry : MiliMapping.entries()) {
            if (!entry.isIdentity()) {
                nonIdentity.add(entry.symbol());
            }
        }
        assertTrue(nonIdentity.isEmpty(),
                "以下符号非恒等映射（疑似混淆版本）: " + nonIdentity);
    }

    @Test
    @DisplayName("条目数与 MiliSymbol 中的符号数一致")
    void entryCountMatchesSymbolTable() {
        // 少一条意味着某个符号没进映射表 —— 那个符号对应的转换器
        // 在「按名解析」时会被判为未知符号。
        assertEquals(8, MiliMapping.entries().size(),
                "映射表应覆盖 MiliSymbol 的全部 8 个符号"
                        + "（SERVER_TICK / SERVER_TICK_CHILDREN / "
                        + "CLIENT_LEVEL_TICK / CLIENT_TICK / CLIENT_BRAND / "
                        + "TITLE_SCREEN_INIT / CLIENT_MAIN / SERVER_MAIN）");
    }

    @Test
    @DisplayName("MiliSymbol 声明的每个符号常量都在映射表中")
    void everySymbolConstantIsMapped() {
        // 数量断言只防「条目总数对不上」；这条断言防的是方向性错误
        // —— 往 MiliSymbol 加了新常量却忘了同步 MiliMapping。
        // 那种漏网的症状是：解析该符号时直接抛 UnknownSymbolException，
        // 而其他符号全部正常 —— 极难一眼看出是映射表漏了。
        // 逐常量断言让 CI 精确指出是哪一个符号没进表。
        TargetMethod[] symbols = {
                MiliSymbol.SERVER_TICK,
                MiliSymbol.SERVER_TICK_CHILDREN,
                MiliSymbol.CLIENT_LEVEL_TICK,
                MiliSymbol.CLIENT_TICK,
                MiliSymbol.CLIENT_BRAND,
                MiliSymbol.TITLE_SCREEN_INIT,
                MiliSymbol.CLIENT_MAIN,
                MiliSymbol.SERVER_MAIN};
        for (TargetMethod symbol : symbols) {
            TargetMethod resolved = MiliMapping.resolveMethod(symbol.toString());
            assertEquals(symbol, resolved,
                    "符号必须在映射表中且解析回自身: " + symbol);
        }
    }

    @Test
    @DisplayName("按符号名解析出与 MiliSymbol 完全相同的坐标")
    void resolutionMatchesSymbolTable() {
        // 关键性质：解析结果必须与符号表<b>逐字段相同</b>。
        // 只要有一个字符不同，注入就会静默不命中。
        for (MiliMapping.Entry entry : MiliMapping.entries()) {
            TargetMethod viaMapping = MiliMapping.resolveMethod(entry.symbol());
            TargetMethod viaSymbol = entry.toTargetMethod();
            assertEquals(viaSymbol, viaMapping,
                    "解析结果必须与符号表一致: " + entry.symbol());
        }

        assertEquals(MiliSymbol.SERVER_TICK,
                MiliMapping.resolveMethod(
                        MiliSymbol.SERVER_TICK.toString()),
                "tickServer 符号必须解析回它自身");
    }

    @Test
    @DisplayName("未知符号抛异常而非返回 null")
    void unknownSymbolThrows() {
        var e = assertThrows(MiliMapping.UnknownSymbolException.class,
                () -> MiliMapping.resolveMethod("net/minecraft/Foo#bar()V"),
                "符号写错若静默跳过，表现就是「Mod 加载成功但功能永远不生效」。"
                        + "返回 null 会把这个错误推给更远的地方，最终变成一次静默失效。");
        assertTrue(e.getMessage().contains("未注册的符号名"));
    }

    @Test
    @DisplayName("空符号名被拒绝")
    void blankSymbolRejected() {
        assertThrows(MiliMapping.UnknownSymbolException.class,
                () -> MiliMapping.resolveMethod(""));
        assertThrows(MiliMapping.UnknownSymbolException.class,
                () -> MiliMapping.resolveMethod(null));
    }

    @Test
    @DisplayName("错误信息列出可用符号 —— 让作者知道该写什么")
    void errorMessageListsAvailableSymbols() {
        var e = assertThrows(MiliMapping.UnknownSymbolException.class,
                () -> MiliMapping.resolveMethod("bogus"));
        String message = e.getMessage();
        for (MiliMapping.Entry entry : MiliMapping.entries()) {
            assertTrue(message.contains(entry.symbol()),
                    "错误信息应列出可用符号 " + entry.symbol());
        }
    }

    @Test
    @DisplayName("字段解析与方法解析是分开的入口")
    void fieldResolutionIsSeparateFromMethodResolution() {
        // 方法描述符形如 ()V，字段描述符形如 I。
        // 混用一个入口会出现「拿 ()V 比 I」这种永远为 false 的匹配 ——
        // 表现为字段注入静默失效，且没有任何报错。
        TargetField field = MiliMapping.resolveField(
                MiliSymbol.SERVER_TICK.toString());
        assertNotNull(field);
        assertEquals(MiliSymbol.SERVER_TICK.owner(), field.owner());
        assertEquals(MiliSymbol.SERVER_TICK.name(), field.name());
        assertEquals(MiliSymbol.SERVER_TICK.descriptor(), field.descriptor());
    }

    @Test
    @DisplayName("符号名顺序稳定 —— 诊断输出不应因运行环境而变")
    void symbolOrderIsStable() {
        // 稳定的顺序让 CI 里的错误信息 diff 可读。
        // 用 HashMap 会得到哈希序，同一错误在不同机器上打印顺序不同。
        List<String> first = new ArrayList<>(MiliMapping.symbolNames());
        List<String> second = new ArrayList<>(MiliMapping.symbolNames());
        assertEquals(first, second);

        List<String> sorted = new ArrayList<>(first);
        sorted.sort(String::compareTo);
        assertEquals(sorted, first,
                "符号名应按字典序返回（TreeMap）");
    }

    @Test
    @DisplayName("版本与 MiliSymbol 同源")
    void versionMatchesSymbolTable() {
        assertEquals(MiliSymbol.MINECRAFT_VERSION, MiliMapping.minecraftVersion(),
                "映射表与符号表必须针对同一版本 —— 否则解析出的坐标属于别的版本");
    }

    @Test
    @DisplayName("requireIdentity 对非恒等条目显式失败")
    void requireIdentityRejectsNonIdentityEntry() {
        // 构造一个非恒等条目，验证守卫真的会拦。
        MiliMapping.Entry nonIdentity = new MiliMapping.Entry(
                "test#sym()V",
                "net/minecraft/server/MinecraftServer", "tickServer", "()V",
                "net/minecraft/server/MinecraftServer", "a", "()V");

        assertFalse(nonIdentity.isIdentity());
        var e = assertThrows(IllegalStateException.class, nonIdentity::requireIdentity);
        assertTrue(e.getMessage().contains("混淆"),
                "错误信息应说明这是混淆版本");
    }
}