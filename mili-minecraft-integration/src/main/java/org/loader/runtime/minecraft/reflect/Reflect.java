package org.loader.runtime.minecraft.reflect;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * 反射访问缓存与诊断。
 *
 * <p>绑定层的每个反射点都必须在「游戏类存在时」解析一次并缓存。解析失败时
 * 抛出带完整上下文的 {@link BridgeMismatchException}，而不是让
 * {@code NoSuchMethodError} 泄漏到 Mod 代码里 —— 后者对 Mod 作者毫无信息量。
 *
 * <p>缓存是并发安全的：多个 Mod 可能同时首次触碰同一个游戏成员。
 */
public final class Reflect {

    private static final Map<String, Class<?>> CLASS_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Method> METHOD_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Field> FIELD_CACHE = new ConcurrentHashMap<>();

    /**
     * 游戏类的 ClassLoader，由 Loader 在启动时设置。
     *
     * <p>用 ThreadLocal 而非静态字段：绑定层可能被多个 ClassLoader 各自加载
     * （测试、Mod 隔离场景），静态字段会在它们之间串味。
     */
    private static final ThreadLocal<ClassLoader> GAME_CL = new ThreadLocal<>();

    private Reflect() {
    }

    /** 按全限定名加载游戏类。 */
    public static Class<?> gameClass(String fqn) {
        return CLASS_CACHE.computeIfAbsent(fqn, name -> {
            try {
                return Class.forName(name, false, gameClassLoader());
            } catch (ClassNotFoundException e) {
                throw new BridgeMismatchException(
                        "Minecraft class not found: " + name
                                + "\n  This build targets a different Minecraft version, "
                                + "or the game classes are not on the classpath yet.", e);
            }
        });
    }

    /**
     * 游戏类的 ClassLoader。
     *
     * <p>默认是平台 JAR 的加载器 —— 适用于游戏类与平台在同一个 CL 的情况。
     * 但真实拓扑是 Minecraft 由独立的 {@code MinecraftClassLoader} 加载，
     * 不在平台 CL 上；此时必须通过 {@link #useGameClassLoader} 显式指定，
     * 否则 {@code ClassNotFoundException: net.minecraft.SharedConstants}。
     */
    public static ClassLoader gameClassLoader() {
        ClassLoader explicit = GAME_CL.get();
        return explicit != null ? explicit : Reflect.class.getClassLoader();
    }

    /**
     * 指定游戏类的 ClassLoader，并清空解析缓存。
     *
     * <p>必须与 {@code useGameClassLoader(null)} 配对恢复，否则后续测试会
     * 读到上一个测试留下的 Class 对象（类身份不一致，报错极具误导性）。
     *
     * @param loader 定义 {@code net.minecraft.*} 的加载器；传 null 恢复默认
     */
    public static void useGameClassLoader(ClassLoader loader) {
        GAME_CL.set(loader);
        clearCaches();
    }

    /** 是否存在某个游戏类（不抛异常）。 */
    public static boolean hasGameClass(String fqn) {
        try {
            gameClass(fqn);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 解析方法并缓存。
     *
     * @param owner       声明类全限定名
     * @param name        方法名
     * @param paramTypes  参数类型全限定名
     */
    public static Method method(String owner, String name, String... paramTypes) {
        String key = owner + "#" + name + Arrays.toString(paramTypes);
        return METHOD_CACHE.computeIfAbsent(key, k -> {
            Class<?> cls = gameClass(owner);
            Class<?>[] types = new Class<?>[paramTypes.length];
            for (int i = 0; i < paramTypes.length; i++) {
                types[i] = primitiveOrClass(paramTypes[i]);
            }
            Method m = findAccessible(cls, name, types);
            if (m == null) {
                throw new BridgeMismatchException(
                        "Minecraft method not found: " + key
                                + "\n  Declared in: " + owner
                                + "\n  Available overloads: " + describeOverloads(cls, name));
            }
            m.setAccessible(true);
            return m;
        });
    }

    private static Method findAccessible(Class<?> cls, String name, Class<?>[] types) {
        Class<?> cursor = cls;
        while (cursor != null) {
            try {
                return cursor.getDeclaredMethod(name, types);
            } catch (NoSuchMethodException e) {
                cursor = cursor.getSuperclass();
            }
        }
        return null;
    }

    private static String describeOverloads(Class<?> cls, String name) {
        StringBuilder sb = new StringBuilder();
        Class<?> cursor = cls;
        while (cursor != null && cursor != Object.class) {
            for (Method m : cursor.getDeclaredMethods()) {
                if (m.getName().equals(name) && sb.length() < 2000) {
                    if (sb.length() > 0) {
                        sb.append("\n              ");
                    }
                    sb.append(m.getParameterTypes().length).append(" args (")
                            .append(Arrays.stream(m.getParameterTypes())
                                    .map(Class::getSimpleName)
                                    .reduce((a, b) -> a + ", " + b).orElse(""))
                            .append(')');
                }
            }
            cursor = cursor.getSuperclass();
        }
        return sb.length() == 0 ? "<none>" : sb.toString();
    }

    /** 解析字段并缓存（含父类搜索）。 */
    public static Field field(String owner, String name) {
        String key = owner + "." + name;
        return FIELD_CACHE.computeIfAbsent(key, k -> {
            Class<?> cursor = gameClass(owner);
            while (cursor != null) {
                try {
                    Field f = cursor.getDeclaredField(name);
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException e) {
                    cursor = cursor.getSuperclass();
                }
            }
            throw new BridgeMismatchException(
                    "Minecraft field not found: " + key + " (searched " + owner + " and superclasses)");
        });
    }

    /** 读静态字段。 */
    public static Object staticField(String owner, String name) {
        try {
            return field(owner, name).get(null);
        } catch (IllegalAccessException e) {
            throw new BridgeMismatchException("Cannot read static field " + owner + "." + name, e);
        }
    }

    /** 读实例字段（含父类）。 */
    public static Object instanceField(Object target, String owner, String name) {
        try {
            return field(owner, name).get(target);
        } catch (IllegalAccessException e) {
            throw new BridgeMismatchException(
                    "Cannot read field " + owner + "." + name + " on " + target.getClass().getName(), e);
        }
    }

    /** 解析参数类型：支持 int/boolean 等原语名与全限定类名。 */
    public static Class<?> primitiveOrClass(String name) {
        return switch (name) {
            case "int" -> int.class;
            case "long" -> long.class;
            case "boolean" -> boolean.class;
            case "double" -> double.class;
            case "float" -> float.class;
            case "short" -> short.class;
            case "byte" -> byte.class;
            case "char" -> char.class;
            case "void" -> void.class;
            default -> gameClass(name);
        };
    }

    /** 清空缓存。测试用。 */
    public static void clearCaches() {
        CLASS_CACHE.clear();
        METHOD_CACHE.clear();
        FIELD_CACHE.clear();
    }
}
