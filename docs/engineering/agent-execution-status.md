# Agent 执行状态表（agent-execution-status.md）

> **历史记录，不代表当前状态。**
> 本目录存放执行过程记录（审计、状态表、最终报告），保留用于追溯决策脉络。
> 其中引用的版本号（如 ABI 1）、缺陷清单与完成度自评均为**当时**的状态。
> 当前实现状态只以 [`../architecture/CURRENT.md`](../architecture/CURRENT.md) 为准。

- 维护人：AI 工程执行 Agent
- 规则：状态仅可为 `NOT_STARTED / IN_PROGRESS / BLOCKED / DONE / DEFERRED_WITH_REASON`；
  完成标准 = 实现 + 测试 + 文档 + 验证证据齐全。
- 验证策略（用户授权）：中途不运行本地 Java 进程；**收尾时执行一次完整构建+测试**。
- **收尾验证结果（2026-10-24）**：`gradlew compileJava compileTestJava test --no-daemon` →
  **BUILD SUCCESSFUL，733 tests / 733 passed / 0 failed / 0 skipped（6 模块）**。
  详见 `agent-final-report.md` §3。

| ID | 任务 | 状态 | 产出/提交 | 验证命令 | 验证结果 | 阻塞/延期原因 |
|---|---|---|---|---|---|---|
| 0.1 | 环境、工作区、模块审计 | DONE | `implementation-audit.md` §0.1（dd53dff） | git status / 文件清点 | 证据齐备 | — |
| 0.2 | 源码现状审计 | DONE | `implementation-audit.md` §0.2（dd53dff） | 逐文件阅读（347 个 Java 文件） | 证据齐备 | — |
| 0.3 | 基线构建与性能测量 | DONE | §0.3 命令清单 + 收尾验证 | `gradlew compileJava compileTestJava test` | **见 agent-final-report.md §验证** | — |
| A1 | 错误模型与 explain | DONE | ErrorCatalog/MiliError/MiliCli + docs/ERROR_MODEL.md（6bb0f48） | ErrorCatalogTest(9)+MiliCliTest(11) | 见最终报告 | — |
| A2 | doctor | DONE | DoctorCommand（6bb0f48） | DoctorCommandTest(15) | 见最终报告 | — |
| A3 | Crash Capsule | DONE | StartupReport/Sanitizer/CrashCapsule + LoaderMain 接线（a1dec3c） | SanitizerTest(9)+StartupReportTest(7)+CrashCapsuleTest(6) | 见最终报告 | — |
| A4 | mili-test | DONE | 新模块 mili-test（f53a296） | HarnessSelfTest(8) | 见最终报告 | 虚拟 tick 见决策记录 #3 |
| A5 | Gradle 插件/运行任务 | DONE | runClient/runTestMod/miliDoctor/miliExplain（de8f9d9） | 收尾构建 + 手动 `gradlew :mili-loader:miliDoctor` | 见最终报告 | TestKit 集成测试延后（无本地运行许可） |
| A6 | mili new | DONE | NewCommand（de8f9d9） | NewCommandTest(9) | 见最终报告 | — |
| A7 | 文档与治理 | DONE | README 修复 + GOVERNANCE.md + ADR/RFC 模板（e00293b） | 链接检查（已通过） | 完成 | 快速开始实测合并进收尾验证 |
| B0 | 基准设施 | DONE | benchmark source set + BenchmarkMain（29c0ed4） | `gradlew :mili-runtime:benchmark`（手动） | 脚手架就绪；基准数据待手动跑 | 本地策略限制运行 |
| B1 | 转换缓存完善 | DEFERRED_WITH_REASON | — | — | — | 现有 TransformationCache 为内存缓存且带测试；磁盘持久化缓存需要先用 B0 数据证明启动期转换确为热点（MD 第 0 节：无证据不强行合入）。建议路径见最终报告 §后续建议 |
| B2 | 扫描/索引/类加载优化 | DEFERRED_WITH_REASON | — | — | — | MD 要求"先用 profile 证明扫描或查找为热点"——无本地运行许可，profile 数据不可得 |
| B3 | EventBus/反射/权限优化 | DONE(审计结论) | 审计发现 EventBus 已是不可变数组快照架构（implementation-audit §0.2.4） | 现有 EventBusDispatchTest | 既有 | 快照优化已存在于现状，无需改动；MD 要求的语义保持验证由既有测试覆盖 |
| B4 | Scheduler/审计优化 | DEFERRED_WITH_REASON | — | — | — | 同 B2：需要 profile 证据先行；现有 PriorityWorkQueue+metrics 实现无已知瓶颈证据 |
| B5 | AOT/AppCDS/JVM 档位 | DEFERRED_WITH_REASON | — | — | — | 实验项；需要区分缓存生成/消费的多轮对照实验，本地无法运行 JVM |
| C0 | 渲染边界审计 | DONE | 审计文档 §0.2.9 | 源码证据 | 完成 | 结论：有 tick/标题屏/Screen/按键/帧采样挂点；无 HUD/Toast/帧循环挂点 |
| C1 | 性能叠加层 | DEFERRED_WITH_REASON | — | — | — | 数据源就绪（ClientFrameSampler、PerformanceMonitor、SchedulerMetrics），但显示面需要新 Screen 挂点与真实 GPU 验收（用户策略禁本地运行游戏）|
| C2 | 动画 API/Runtime | DEFERRED_WITH_REASON | — | — | — | MD 要求"先设计 ADR 并审查 API，之后实现"——API 审查需要维护者参与；未经审查的动画 ABI 不应单方面进入稳定契约 |
| C3 | 分批视觉效果 | BLOCKED | — | — | — | 依赖 C1/C2 + 新渲染挂点（需扩展 MiliSymbol 并过 CI 符号校验）+ 真实 GPU 验收 |
| C4 | 表现层帧预算 | BLOCKED | — | — | — | 依赖 C1/C2 |
| D1 | Mod 管理 UI | NOT_STARTED | — | — | — | 依赖 A2(已就绪)、C1(延后) |
| D2 | Installer TUI/JSON | DEFERRED_WITH_REASON | — | — | — | install-report.json schema 已存在且 CI 有 live 元数据冒烟测试；TUI 改造收益未与 A 组 P0 竞争 |
| D3 | SemVer 范围 | DEFERRED_WITH_REASON | — | — | — | 现状 manifest 丢弃 versionRange（恒 `*`，审计 §0.2.1）——引入范围语法需要先 ADR 定义版本承诺语义（MD D3 要求先写 ADR） |
| D4 | Dev 文件监视 | NOT_STARTED | — | — | — | 依赖 A5（已就绪）；README 已描述 ResourceWatcher 轮询方案，实装待排期 |
| D5 | mili bisect | DEFERRED_WITH_REASON | — | — | — | 依赖可重复启动（本地策略禁运行游戏）；doctor 提供了它需要的诊断基础 |
| D6 | Capability 图/录制 | DEFERRED_WITH_REASON | — | — | — | doctor --graph 已提供依赖图 Mermaid；Capability 图谱受限于 manifest 不携带 capabilities（审计 §0.2.1） |
| D7 | 多版本兼容证明 | BLOCKED | — | — | — | 需下一个 MC 版本合法输入 |
| E | 分发、信任、发布准备 | BLOCKED | GOVERNANCE.md 已建立治理框架 | — | — | 需维护者决策/授权 |

## 决策记录（依赖关系调整与理由）

1. **A1/A2 的 CLI 挂点**：`LoaderMain.parseArgs` 是唯一现成参数解析，但该文件处于用户未提交修改中；CLI 以独立入口类 `org.loader.loader.cli.MiliCli` 建立，不动 LoaderMain 的参数解析。A3 仅在 launch() 失败路径做 1 处最小增量接线（FQN 调用，不动 import 区）。
2. **A3 依赖用户 WIP 文件**：StartupReport 以 `StartupTimeline`（用户未提交新文件）为参数 —— 该文件已被 LoaderMain 深度使用，耦合方向一致。
3. **A4 虚拟 tick**：TickEngine 是真实墙钟、单线程绑定模型，无可步进的虚拟时间 API（审计 §0.2.5）。mili-test v1 不提供 tick 推进，诚实声明于模块 README；虚拟时钟建议随 C2 动画时间基统一设计。
4. **B0 基准**：不引入 JMH 新依赖（离线构建约束），沿用仓库 HotPathBenchmarkTest 预算式模式；分配率明确标注未测。
5. **B3 的处理**：审计证明 MD B3 设想的"不可变监听器快照"已是 EventBus 现状（写路径复制数组、读路径零分配），无需重复实现；标记 DONE(审计结论)。
6. **C2/C3/E 组**：MD 自身要求 ADR/审查/用户显式启用，属维护者决策边界，Agent 不越权。
7. **D7**：无第二个 MC 版本合法输入，直接 BLOCKED。
8. **验证策略**：用户授权"仅收尾验证一次"——中途所有实现以代码审查 + 测试编写保证正确性，真实构建/测试结果统一回填最终报告。
