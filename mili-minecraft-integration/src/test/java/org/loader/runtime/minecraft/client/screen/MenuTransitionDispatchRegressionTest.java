package org.loader.runtime.minecraft.client.screen;

import net.minecraft.client.gui.components.Button;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.loader.runtime.minecraft.reflect.Reflect;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 主菜单转场分发器的两个真机回归缺陷（2026-10-28 自测证实）：
 *
 * <h2>1. hidden class 访问 —— 玩家被困主菜单</h2>
 * 原版 OnPress 是 LambdaMetafactory 生成的 hidden class；对它
 * {@code getClass().getMethod(...)} 得到的 Method 声明类是 hidden class
 * 本身，invoke 一律 {@code IllegalAccessException}（连 public 也如此）。
 * 症状：转场动画播完，原版换屏从未执行，点单人/多人都停在主菜单。
 * 修复后从公开接口 {@code Button$OnPress} 解析。
 *
 * <h2>2. 遍历中修改 children —— CME</h2>
 * takeoverButtons 边遍历 children 边 removeWidget，第一个按钮替换完
 * 迭代器立即 CME，第二个按钮永远接管不上。修复后快照遍历
 * （{@link MenuTransitionDispatch#forEachSnapshot}）。
 */
class MenuTransitionDispatchRegressionTest {

    @Test
    @DisplayName("invokeOriginal 能调用 lambda（hidden class）实现的 OnPress")
    void invokesLambdaOnPress() {
        useTestClasspathLoader();
        try {
            AtomicInteger called = new AtomicInteger();
            // lambda → invokedynamic → LambdaMetafactory hidden class，
            // 与生产中 TitleScreen 单人/多人按钮的 OnPress 形态完全一致。
            // 旧实现（getClass().getMethod）在这里必然 IllegalAccessException。
            Button.OnPress original = button -> called.incrementAndGet();

            MenuTransitionDispatch.invokeOriginal(original, null);

            assertEquals(1, called.get(), "原版回调必须被真实执行");
        } finally {
            Reflect.useGameClassLoader(null);
        }
    }

    @Test
    @DisplayName("invokeOriginal 对具名实现类同样有效")
    void invokesNamedOnPress() {
        useTestClasspathLoader();
        try {
            AtomicInteger called = new AtomicInteger();
            Button.OnPress original = new Button.OnPress() {
                @Override
                public void onPress(Button button) {
                    called.incrementAndGet();
                }
            };

            MenuTransitionDispatch.invokeOriginal(original, null);

            assertEquals(1, called.get(), "原版回调必须被真实执行");
        } finally {
            Reflect.useGameClassLoader(null);
        }
    }

    /**
     * 把游戏 CL 钉回测试类路径。其他测试（GameTestClassLoader）会留下自定义
     * 的游戏 CL 且不恢复 —— 那种 CL 里也有一个假 Button$OnPress，类身份与本
     * 测试类路径上的接口不同，回调会因「receiver 不是实例」而失败。
     */
    private static void useTestClasspathLoader() {
        Reflect.useGameClassLoader(MenuTransitionDispatchRegressionTest.class.getClassLoader());
    }

    @Test
    @DisplayName("forEachSnapshot：遍历中增删原列表不 CME，且每个元素都被访问")
    void snapshotIterationToleratesMutation() {
        List<Object> children = new ArrayList<>(List.of("singleplayer", "multiplayer", "options"));
        List<Object> visited = new ArrayList<>();

        // 模拟 replaceButton 的行为：访问后立刻把元素从 children 摘掉
        MenuTransitionDispatch.forEachSnapshot(children, widget -> {
            visited.add(widget);
            children.remove(widget);
        });

        assertEquals(List.of("singleplayer", "multiplayer", "options"),
                visited, "三个按钮都必须被访问到");
        assertEquals(List.of(), children, "原列表的删除应已生效");
    }
}
