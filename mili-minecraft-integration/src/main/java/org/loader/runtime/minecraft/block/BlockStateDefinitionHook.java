package org.loader.runtime.minecraft.block;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把推断出的方块属性接进原版 {@code Block} 构造流程。
 *
 * <h2>为什么需要这个类</h2>
 *
 * <p>原版 {@code Block.<init>} 在<b>父类构造器里</b>虚调用
 * {@code this.createBlockStateDefinition(builder)} 收集属性。生成的方块子类
 * （见 {@link GeneratedBlockFactory}）覆写了这个方法，但方法体只做一件事 ——
 * 把 {@code (this, builder)} 转交给本类的 {@link #addStates}。
 *
 * <p><b>为什么不能把属性存在生成类的实例字段里</b>：回调发生在
 * {@code super(props)} 执行期间，此时子类构造器体还没跑，实例字段全是默认值。
 * 于是按<b>类</b>而非实例登记：注册方在实例化之前
 * {@link #register}（生成类 → 属性数组），构造完成后 {@link #unregister}。
 * {@code getClass()} 在父类构造期间返回的就是生成类，查找成立。
 */
public final class BlockStateDefinitionHook {

    private static final ConcurrentHashMap<Class<?>, Object[]> BY_CLASS =
            new ConcurrentHashMap<>();

    private BlockStateDefinitionHook() {
    }

    /** 实例化前登记。属性列表为空时是 no-op。 */
    public static void register(Class<?> generated, List<Object> properties) {
        if (properties == null || properties.isEmpty()) {
            return;
        }
        BY_CLASS.put(generated, properties.toArray());
    }

    /** 构造完成后解除登记，避免持有生成类的强引用。 */
    public static void unregister(Class<?> generated) {
        BY_CLASS.remove(generated);
    }

    /**
     * 生成类覆写 {@code createBlockStateDefinition} 的转发目标。
     *
     * <p>参数一律 {@code Object} —— 生成代码不认识游戏类型（与
     * {@link BehaviourDispatch} 的转发钩子同一约定）。
     *
     * @param block        正在构造的方块实例（用于按类查找属性）
     * @param stateBuilder 原版 {@code StateDefinition.Builder}
     */
    public static void addStates(Object block, Object stateBuilder) {
        Object[] props = (block == null) ? null : BY_CLASS.get(block.getClass());
        if (props == null || props.length == 0 || stateBuilder == null) {
            return;
        }
        try {
            Method add = null;
            for (Method m : stateBuilder.getClass().getMethods()) {
                // varargs 方法擦除后是 add(Property[])
                if (m.getName().equals("add")
                        && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].isArray()) {
                    add = m;
                    break;
                }
            }
            if (add == null) {
                throw new NoSuchMethodException(
                        "StateDefinition.Builder.add(Property...) not found on "
                                + stateBuilder.getClass().getName());
            }
            // IntegerProperty[] 对 Property[] 赋值兼容（Java 数组协变），
            // 反射 invoke 的可赋值检查同样接受。
            Object array = Array.newInstance(props[0].getClass(), props.length);
            for (int i = 0; i < props.length; i++) {
                Array.set(array, i, props[i]);
            }
            add.invoke(stateBuilder, array);
        } catch (Throwable t) {
            // 在父类构造器里抛出会变成实例化失败 —— 与其让 Mod 作者看到
            // 一坨 InvocationTargetException，不如带上属性清单说清楚。
            throw new IllegalStateException(
                    "Failed to attach inferred blockstate properties "
                            + props.length + " to generated block "
                            + block.getClass().getName()
                            + ". This is a binding-layer bug, not a mod bug.", t);
        }
    }
}
