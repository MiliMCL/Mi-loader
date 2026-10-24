# Agent 执行状态表（agent-execution-status.md）

- 维护人：AI 工程执行 Agent
- 规则：状态仅可为 `NOT_STARTED / IN_PROGRESS / BLOCKED / DONE / DEFERRED_WITH_REASON`；
  完成标准 = 实现 + 测试 + 文档 + 验证证据齐全。
- 验证策略（用户授权）：中途不运行本地 Java 进程；**收尾时执行一次完整构建+测试**，真实结果回填本表"验证结果"列。

| ID | 任务 | 状态 | 产出/提交 | 验证命令 | 验证结果 | 阻塞/延期原因 |
|---|---|---|---|---|---|---|
| 0.1 | 环境、工作区、模块审计 | DONE | `docs/engineering/implementation-audit.md` §0.1 | git status / 文件清点 | 证据齐备（构建/测试延后） | — |
| 0.2 | 源码现状审计 | DONE | `docs/engineering/implementation-audit.md` §0.2 | 逐文件阅读 | 证据齐备 | — |
| 0.3 | 基线构建与性能测量 | IN_PROGRESS | §0.3 命令清单 | 收尾：gradlew compileJava/test/shadowJar | 待收尾回填 | 用户策略：仅收尾验证一次 |
| A1 | 错误模型与 explain | IN_PROGRESS | ErrorCatalog + MiliCli | 收尾回填 | 待验证 | — |
| A2 | doctor | IN_PROGRESS | DoctorCommand | 收尾回填 | 待验证 | — |
| A3 | Crash Capsule | NOT_STARTED | | | | |
| A4 | mili-test | NOT_STARTED | | | | |
| A5 | Gradle 插件/运行任务 | NOT_STARTED | | | | |
| A6 | mili new | NOT_STARTED | | | | |
| A7 | 文档与治理 | NOT_STARTED | | | | |
| B0 | 基准设施 | NOT_STARTED | | | | |
| B1 | 转换缓存完善 | NOT_STARTED | | | | |
| B2 | 扫描/索引/类加载优化 | NOT_STARTED | | | | |
| B3 | EventBus/反射/权限优化 | NOT_STARTED | | | | |
| B4 | Scheduler/审计优化 | NOT_STARTED | | | | |
| B5 | AOT/AppCDS/JVM 档位 | NOT_STARTED | | | | |
| C0 | 渲染边界审计 | DONE | 审计文档 §0.2.9 | 源码证据 | 完成 | 无 HUD/Toast/帧循环挂点 |
| C1 | 性能叠加层 | NOT_STARTED | | | | |
| C2 | 动画 API/Runtime | NOT_STARTED | | | | |
| C3 | 分批视觉效果 | NOT_STARTED | | | | |
| C4 | 表现层帧预算 | NOT_STARTED | | | | |
| D1 | Mod 管理 UI | NOT_STARTED | | | | |
| D2 | Installer TUI/JSON | NOT_STARTED | | | | |
| D3 | SemVer 范围 | NOT_STARTED | | | | |
| D4 | Dev 文件监视 | NOT_STARTED | | | | |
| D5 | mili bisect | NOT_STARTED | | | | |
| D6 | Capability 图/录制 | NOT_STARTED | | | | |
| D7 | 多版本兼容证明 | BLOCKED | | | | 需下一个 MC 版本合法输入 |
| E | 分发、信任、发布准备 | BLOCKED | | | | 需维护者决策/授权 |

## 决策记录（依赖关系调整与理由）

1. **A1/A2 的 CLI 挂点**：`LoaderMain.parseArgs` 是唯一现成参数解析，但该文件处于用户未提交修改中，为避免冲突，CLI 以独立入口类 `org.loader.loader.cli.MiliCli` 建立，不动 LoaderMain 的参数解析。
2. **B1 缓存**：现有 `TransformationCache` 仅内存缓存；磁盘持久化缓存是否合入取决于收尾验证后的容量评估，默认倾向 `DEFERRED_WITH_REASON`（无证据证明收益前不强行合入，符合 MD 第 0 节原则）。
3. **B0 基准**：不引入 JMH 新依赖（离线构建约束 + gradle.properties 记录的离线缓存限制），沿用仓库 `HotPathBenchmarkTest` 的纯 Java 预算式基准模式，另建可手动运行的 JavaExec 基准任务。
4. **C2 动画**：纯 JVM 部分可安全实现（ABI 接口 + runtime 实现 + 测试）；渲染线程推进需要 C0 已确认的客户端 tick/Screen 挂点，实机验收列为阻塞项。
5. **D7**：无第二个 MC 版本合法输入，直接 BLOCKED。
6. **E 组**：按 MD 第 7 节仅准备文档，不发布。
