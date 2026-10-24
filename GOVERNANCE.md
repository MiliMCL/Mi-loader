# Mili Platform 治理（GOVERNANCE.md）

> 本文件定义 Mili Platform 的决策流程、角色与变更规则。它是对 README
> "ABI 优先"承诺的制度化；与 ADR 冲突时，以更晚近的 ADR 为准。

## 1. 角色

| 角色 | 权限 | 产生方式 |
|---|---|---|
| 维护者（Maintainer） | 合并 PR、创建 Release、修改治理文件与保护规则、批准破坏性变更 | 现任维护者邀请 |
| 贡献者（Contributor） | 提交 PR、参与 RFC 讨论 | 开放 |
| AI 工程执行 Agent | 在仓库内自主实现、测试、提交（**不推送**）；其余一切外部动作需维护者显式授权 | 由维护者启动并授权 |

**AI Agent 的硬边界**（摘自执行合同，长期有效）：不推送、不发布 Release、
不上传制品、不批准 PR、不修改保护规则、不操作生产服务、不提交/分发
Minecraft 受限产物、不提交隐私数据、不以"全部完成"等无证据措辞报告。

## 2. 决策流程

### 2.1 三类变更，三种流程

| 变更类型 | 流程 | 示例 |
|---|---|---|
| 内部实现细节 | 直接 PR，测试+文档齐全即可 | 性能优化（不改语义）、bug 修复 |
| 公开 API 变更（非破坏） | PR + ABI 文档同步 + 兼容性测试 | 新增接口方法（带默认实现） |
| 破坏性变更 / 新子系统 / 语义变更 | **RFC → ADR → 实现** | ABI 破坏、权限默认值、新转换机制 |

### 2.2 RFC（征求意见）

- 模板：`docs/decisions/RFC_TEMPLATE.md`
- 任何破坏性变更、新子系统（如动画 ABI、权限模型调整）必须先有 RFC。
- RFC 讨论期由维护者宣布截止；无正式流程工具时，RFC 文件本身即讨论载体。

### 2.3 ADR（架构决策记录）

- 目录：`docs/decisions/`，命名 `NNNN-kebab-case-title.md`
- 模板：`docs/decisions/TEMPLATE.md`
- 状态机：`Proposed（提案）→ Accepted（已接受）→ Superseded by NNNN（被取代）/ Deprecated（弃用）`
- ADR 一经 Accepted **不可修改结论**；推翻它只能写新 ADR 并标记旧的为 Superseded。

## 3. ABI 治理承诺（与 README 同步）

1. 破坏性变更必须经历：提案（RFC）→ 过渡期（旧 API `@Deprecated` + 迁移指引）
   → 移除期（至少一个次版本的过渡后移除）。
2. `mili-abi` 零依赖是硬约束（CI 门禁 `module-boundary-check` 强制）。
3. ASM 是平台内部细节，永不进入 ABI 公开签名（ADR-0011）。

## 4. 质量门禁

每次变更（按适用范围）：格式与静态检查 → 单元测试 → 模块测试 → API 边界
测试 → 真实 MC 符号校验（`verifyTransformationSymbols`）→ 集成启动测试 →
基准对照（性能变更时）→ `git diff --check` 与秘密扫描。

**禁止**以删测试、放宽断言、跳过符号校验或吞异常的方式"修复"CI。

## 5. 发布规则

- Release 需维护者明确授权；候选包、变更日志、兼容矩阵、校验和、许可证检查
  齐备后方可发布。
- 版本锁定三元组（platform/abi/minecraft）的唯一权威来源是 `gradle.properties`。

## 6. 文档真实性

README 与文档只描述**已验证的功能**；实验性功能必须标注；未实现但规划中的
行为必须明确写"未实现/规划项"（参见 README 中对熔断器与权限审计的现状标注）。
发现文档与实现不符时，修复文档或在 ADR 中决定实现方向——不允许保持模糊。
