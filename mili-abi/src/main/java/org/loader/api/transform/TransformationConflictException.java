package org.loader.api.transform;

import java.util.List;

/**
 * 转换冲突 —— 多个转换器在同一位置做了互斥的修改。
 *
 * <p><b>冲突绝不能静默覆盖。</b>两个 Mod 都想重定向同一个调用时，
 * 若平台默默让后者胜出，结果取决于 Mod 的加载顺序 —— 表现为
 * 「装了 B 之后 A 的功能就坏了」，且没有任何线索指向真正的原因。
 * 加载顺序依赖目录扫描，因此这类 bug 还具有不可复现性。
 *
 * <h2>什么算冲突，什么不算</h2>
 * 可组合（<b>不</b>算冲突）：
 * <ul>
 *   <li>多个 {@link InjectionPoint#HEAD} —— 都执行，按顺序</li>
 *   <li>多个 {@link InjectionPoint#RETURN} —— 同上</li>
 *   <li>不同 {@code ordinal} 的 {@link InjectionPoint#BEFORE_INVOKE}</li>
 * </ul>
 *
 * 互斥（算冲突）：
 * <ul>
 *   <li>同一目标的两个 {@link InjectionPoint#REDIRECT}</li>
 *   <li>同一目标的两个 {@link InjectionPoint#REPLACE_FIELD_ACCESS}</li>
 *   <li>同一目标的两个 {@link InjectionPoint#OVERWRITE}</li>
 *   <li>同 ordinal 的两个 {@link InjectionPoint#BEFORE_INVOKE}</li>
 *   <li>Mod 阶段覆盖 {@link TransformationPhase#CORE} 已占用的位置</li>
 * </ul>
 */
public final class TransformationConflictException extends TransformationException {

    private static final long serialVersionUID = 1L;

    private final String className;
    private final String memberName;
    private final List<String> transformerIds;
    private final String conflictReason;

    public TransformationConflictException(
            String className,
            String memberName,
            List<String> transformerIds,
            String conflictReason) {
        super(buildMessage(className, memberName, transformerIds, conflictReason),
                transformerIds.isEmpty() ? null : transformerIds.get(0), null);
        this.className = className;
        this.memberName = memberName;
        this.transformerIds = List.copyOf(transformerIds);
        this.conflictReason = conflictReason;
    }

    private static String buildMessage(String cls, String member,
                                      List<String> ids, String reason) {
        return "转换冲突:\n"
                + "  Class       : " + cls + "\n"
                + "  Method      : " + member + "\n"
                + "  Transformers: " + String.join(", ", ids) + "\n"
                + "  Conflict    : " + reason + "\n"
                + "多个转换器对同一目标做了互斥修改。平台不会静默覆盖任何一个。\n"
                + "  * 若二者本就该共存 → 改用可组合注入点 (HEAD / RETURN / 不同 ordinal)\n"
                + "  * 若确需互斥 → 由平台仲裁，而非依赖 Mod 加载顺序";
    }

    public String className() {
        return className;
    }

    public String memberName() {
        return memberName;
    }

    /** 参与冲突的转换器 id（按注册顺序）。 */
    public List<String> transformerIds() {
        return transformerIds;
    }

    public String conflictReason() {
        return conflictReason;
    }
}