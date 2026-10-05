package org.loader.runtime.error;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 结构化错误上下文 —— 挂在 {@link MiliError} 上，供诊断与遥测使用。
 *
 * <p>结构：{@code component_owner_operation_state_cause_retryable}，另附
 * 自由键值对（{@link #details()}）用于承载版本、Mod ID、tick ID 等领域信息。
 *
 * <p>保留 canonical record 构造器以兼容既有 10 个错误子类与测试；
 * 同时提供 {@link #builder(String)} 以便增量附加领域信息。
 */
public record ErrorContext(
        String component,
        String owner,
        String operation,
        String state,
        Throwable cause,
        boolean retryable,
        Map<String, String> details
) {
    public ErrorContext {
        if (component == null || component.isBlank()) {
            throw new IllegalArgumentException("ErrorContext.component must not be blank");
        }
        details = details != null ? Map.copyOf(details) : Map.of();
    }

    /** 兼容旧调用点的紧凑构造器（无 details）。 */
    public ErrorContext(String component, String owner, String operation,
                        String state, Throwable cause, boolean retryable) {
        this(component, owner, operation, state, cause, retryable, Map.of());
    }

    /** 创建构建器。 */
    public static Builder builder(String component) {
        return new Builder(component);
    }

    /** 空上下文（组件未知时使用）。 */
    public static ErrorContext empty() {
        return new ErrorContext("unknown", null, null, null, null, false);
    }

    /** 是否不含额外信息。 */
    public boolean isEmpty() {
        return owner == null && operation == null && state == null
                && cause == null && details.isEmpty();
    }

    /** 附加一个领域信息，返回新上下文（record 不可变）。 */
    public ErrorContext with(String key, Object value) {
        if (key == null || value == null) {
            return this;
        }
        Map<String, String> merged = new LinkedHashMap<>(details);
        merged.put(key, String.valueOf(value));
        return new ErrorContext(component, owner, operation, state, cause, retryable, merged);
    }

    /** 人类可读的多行诊断串。 */
    public String toDiagnosticString() {
        StringBuilder sb = new StringBuilder("  组件: ").append(component);
        if (owner != null) {
            sb.append("\n  所有者: ").append(owner);
        }
        if (operation != null) {
            sb.append("\n  操作: ").append(operation);
        }
        if (state != null) {
            sb.append("\n  状态: ").append(state);
        }
        if (cause != null) {
            sb.append("\n  原因: ").append(cause.getClass().getSimpleName())
                    .append(cause.getMessage() != null ? ": " + cause.getMessage() : "");
        }
        details.forEach((k, v) -> sb.append("\n  ").append(k).append(": ").append(v));
        sb.append("\n  可重试: ").append(retryable);
        return sb.toString();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("ErrorContext[");
        sb.append(component);
        if (owner != null) sb.append(", owner=").append(owner);
        if (operation != null) sb.append(", op=").append(operation);
        if (state != null) sb.append(", state=").append(state);
        if (cause != null) sb.append(", cause=").append(cause.getClass().getSimpleName());
        sb.append(", retryable=").append(retryable);
        if (!details.isEmpty()) sb.append(", details=").append(details);
        sb.append(']');
        return sb.toString();
    }

    /** {@link ErrorContext} 增量构建器。 */
    public static final class Builder {
        private final String component;
        private String owner;
        private String operation;
        private String state;
        private Throwable cause;
        private boolean retryable;
        private final Map<String, String> details = new LinkedHashMap<>();

        private Builder(String component) {
            if (component == null || component.isBlank()) {
                throw new IllegalArgumentException("component 不能为空");
            }
            this.component = component;
        }

        public Builder owner(String v) {
            this.owner = v;
            return this;
        }

        public Builder operation(String v) {
            this.operation = v;
            return this;
        }

        public Builder state(String v) {
            this.state = v;
            return this;
        }

        public Builder cause(Throwable v) {
            this.cause = v;
            return this;
        }

        public Builder retryable(boolean v) {
            this.retryable = v;
            return this;
        }

        public Builder detail(String key, Object value) {
            if (key != null && value != null) {
                this.details.put(key, String.valueOf(value));
            }
            return this;
        }

        public ErrorContext build() {
            return new ErrorContext(component, owner, operation, state, cause,
                    retryable, details);
        }
    }
}