package org.loader.runtime.minecraft.client.screen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 生成字节码的<b>调用契约</b>测试 —— 防的是 {@code NoSuchMethodError}。
 *
 * <h2>这个测试为什么必须存在</h2>
 * 两个宿主类（{@code MiliScreenHost} / {@code MiliModListScreen}）是在运行时
 * 生成的，它们对我们自己的分发器发起静态调用。这些调用<b>绕过了 javac 的
 * 签名检查</b> —— 描述符是手写的字符串，写错了编译期一点提示都没有。
 *
 * <p>写错的后果不是「界面显示不对」，而是：
 * <pre>
 *   NoSuchMethodError: 'void ScreenHostDispatch.onInit(Screen)'
 *     at mili-screen-host//MiliScreenHost.init(Unknown Source)
 * </pre>
 * 异常点在生成的类里，堆栈只有一行 {@code Unknown Source}，
 * 而它表现为「按 K 键 → 游戏直接退出」。
 *
 * <p>真实事故：分发器把宿主参数声明为 {@code Object}（它不能引用
 * Minecraft 类型），生成器却按 {@code Screen} 发调用。两者永远不会在
 * 编译期碰面，于是这个错误一路活到玩家手里。
 *
 * <p>本测试把「生成器写出的描述符」与「分发器真实的方法签名」对起来 ——
 * 这是唯一能在构建期发现这类错误的办法。
 */
class ScreenHostContractTest {

    @Test
    @DisplayName("MiliScreenHost 对分发器的每次静态调用都能解析到真实方法")
    void screenHostCallsResolve() {
        assertCallsResolve(MiliScreenHostGenerator.generate(),
                MiliScreenHostGenerator.HOST_BINARY);
    }

    @Test
    @DisplayName("MiliModListScreen 对分发器的每次静态调用都能解析到真实方法")
    void modListHostCallsResolve() {
        assertCallsResolve(MiliModListScreenGenerator.generate(),
                MiliModListScreenGenerator.HOST_BINARY);
    }

    /**
     * 逐条检查 INVOKESTATIC：目标类必须是本模块的类，且同名同描述符的
     * {@code public static} 方法必须真的存在。
     */
    private static void assertCallsResolve(byte[] bytecode, String hostName) {
        ClassNode node = new ClassNode();
        new ClassReader(bytecode).accept(node, 0);

        List<String> problems = new ArrayList<>();
        int checked = 0;

        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn = method.instructions.getFirst();
                 insn != null; insn = insn.getNext()) {
                if (!(insn instanceof MethodInsnNode call)) {
                    continue;
                }
                if (call.getOpcode() != Opcodes.INVOKESTATIC) {
                    continue;
                }
                // 只校验我们自己的类：Screen / Component 等由游戏提供，
                // 这里没有它们的字节码，也不该在这里断言。
                if (!call.owner.startsWith("org/loader/")) {
                    continue;
                }
                checked++;
                String ownerClass = call.owner.replace('/', '.');
                try {
                    Class<?> owner = Class.forName(ownerClass);
                    Method target = findMethod(owner, call.name, call.desc);
                    if (target == null) {
                        problems.add(hostName + "." + method.name + method.desc
                                + " 调用 " + ownerClass + "." + call.name + call.desc
                                + " —— 该方法不存在");
                    } else if (!Modifier.isStatic(target.getModifiers())) {
                        problems.add(hostName + "." + method.name
                                + " 以 INVOKESTATIC 调用了非静态方法 "
                                + ownerClass + "." + call.name + call.desc);
                    }
                } catch (ClassNotFoundException e) {
                    problems.add("找不到目标类 " + ownerClass
                            + "（生成器写错了 owner？）");
                }
            }
        }

        assertTrue(checked > 0,
                hostName + " 没有对平台自身发起任何静态调用 —— 生成器可能已经退化");
        if (!problems.isEmpty()) {
            fail("生成的字节码存在无法解析的调用（运行时必然 NoSuchMethodError）：\n  "
                    + String.join("\n  ", problems));
        }
    }

    /** 按 name + descriptor 精确匹配（不能只按名字 —— 重载会命中错误的那个）。 */
    private static Method findMethod(Class<?> owner, String name, String desc) {
        Type[] argTypes = Type.getArgumentTypes(desc);
        Class<?>[] params = new Class<?>[argTypes.length];
        for (int i = 0; i < argTypes.length; i++) {
            params[i] = toClass(argTypes[i]);
            if (params[i] == null) {
                return null;
            }
        }
        try {
            return owner.getDeclaredMethod(name, params);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    /** 把 ASM 的类型描述映射成 Class；基础类型与 void 都要覆盖。 */
    private static Class<?> toClass(Type type) {
        return switch (type.getSort()) {
            case Type.VOID -> void.class;
            case Type.BOOLEAN -> boolean.class;
            case Type.CHAR -> char.class;
            case Type.BYTE -> byte.class;
            case Type.SHORT -> short.class;
            case Type.INT -> int.class;
            case Type.FLOAT -> float.class;
            case Type.LONG -> long.class;
            case Type.DOUBLE -> double.class;
            case Type.ARRAY -> {
                Class<?> comp = toClass(type.getElementType());
                yield comp == null ? null
                        : java.lang.reflect.Array.newInstance(comp, 0).getClass();
            }
            case Type.OBJECT -> {
                try {
                    yield Class.forName(type.getClassName());
                } catch (ClassNotFoundException e) {
                    // Minecraft 类型在测试 classpath 上不存在 —— 但生成器
                    // 也不该对它们发 INVOKESTATIC（我们只校验自己人）。
                    yield null;
                }
            }
            default -> null;
        };
    }
}
