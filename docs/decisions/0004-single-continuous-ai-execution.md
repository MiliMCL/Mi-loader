# ADR 0004: Single Continuous AI Execution

## Status
Accepted

## Decision

工程执行不以 Goal Turn 或 Phase 为人工确认点。

AI 应基于完整规范持续：

```text
Implement → Test → Fix → Continue
```

直到全局 Definition of Done 满足。

## Rationale

该项目的目标是让完整规范本身成为可执行工程合同，而不是把实现拆成需要人工逐次推动的对话任务。
