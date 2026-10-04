# Mili Platform Versioning

## 概述

Mili 是一个从零构建的 Minecraft 客户端 Mod 加载器，底层依赖 Mili 自研 Runtime 基础设施，而非对 Fabric 的复刻或模仿。Mili 提供一套原生 API 体系：Scope、Scheduler、EventBus、Capability、ResourceManager。这些组件构成了 Mod 的 API 层，也是整个平台版本管理的核心锚点。

Mili 平台采用严格的版本锁定机制。每一个 Mod 在声明自身时必须明确它所绑定的平台版本、ABI 版本与 Minecraft 版本，三者缺一不可。这种设计的目标是消除隐式兼容性假设，确保加载器在启动阶段就能可靠地拒绝不匹配的 Mod，而非在运行时才暴露难以排查的错误。

本文档描述 Mili 平台版本号的三元组定义、`mod.json` 中的声明格式、加载器的校验规则、错误码含义，以及平台各模块自身的版本策略。

## 三元组：platform / abi / minecraft

Mili 平台通过三个独立的版本字符串来描述运行环境。三者职责不同，演进节奏不同，但必须同时满足才能保证 Mod 正常加载。

**platform** 指 Mili 平台实现本身的版本。它反映的是 `mili-loader`、`mili-runtime` 等模块作为一个整体的行为承诺。当平台的核心模块发生行为变更，或者加载流程、生命周期顺序、服务注册表结构发生变化时，platform 版本必须递增。对于 Mod 而言，platform 版本代表"我编写和测试时所面对的实际实现版本"。

**abi** 指 Mili ABI 的版本，即 `mili-abi` 模块所暴露的纯接口集合的版本。ABI 的稳定性是平台长期可用的基石。只要接口签名、方法语义或协议结构保持不变，abi 版本就不应改变。当且仅当发生破坏性接口变更时（例如移除公开方法、修改方法签名、重命名核心类型），abi 版本才允许递增。Mod 在编译时绑定的是 abi 版本，运行时平台必须提供与之完全匹配的 abi 实现。

**minecraft** 指目标 Minecraft 客户端的版本号。由于 Mili 的 `mili-minecraft-integration` 模块负责与 Minecraft 内部代码桥接，Minecraft 版本的变化往往意味着映射关系、方法签名甚至底层协议的变化，因此 minecraft 版本必须精确匹配，不允许任何漂移。

三个版本合称为"Mili 三元组"。当前常量定义在 `org.loader.api.VersionInfo` 中：`CURRENT_VERSION` 对应 platform，值为 `"0.1.0"`；`ABI_VERSION` 对应 abi，值为 `"1.0"`；`TARGET_MINECRAFT` 对应 minecraft，值为 `"26.2"`。此外该类还声明 `TARGET_JAVA = "25"`，代表构建和运行所需的最小 Java 级别。

## mod.json 中的版本声明

每一个 Mod 通过其根目录下的 `mod.json` 文件声明自身元数据。与传统的 Fabric `fabric.mod.json` 或 Forge `mods.toml` 不同，Mili 在 `mod.json` 中引入了一个可选的顶级 `"mili"` 对象，用于承载三元组声明。当该对象缺失时，Mod 被视为"未绑定"（unbound），加载器允许其加载但会输出警告，以此提供向后兼容，同时引导开发者在迁移时补全声明。

一个完整的 `mod.json` 示例结构如下。`"mili"` 对象内部包含三个字符串字段：`platform`、`abi`、`minecraft`。它们必须与运行环境中 `VersionInfo` 的对应常量完全一致，不允许使用前缀、范围表达式或通配符。

```json
{
  "id": "mymod",
  "name": "My Mod",
  "version": "1.0.0",
  "entrypoint": "com.example.MyMod",
  "mili": {
    "platform": "0.1.0",
    "abi": "1.0",
    "minecraft": "26.2"
  },
  "dependencies": []
}
```

`"mili"` 对象是可选的，但强烈建议所有新 Mod 完整填写。缺失 `"mili"` 对象的旧式 Mod 在加载时会被标记为未绑定状态，加载器会在日志中以警告级别记录该 Mod 的 id 和版本，但不阻断启动流程。这种过渡性策略仅用于兼容迁移期，未来版本可能将其升级为错误。

`"dependencies"` 数组用于声明当前 Mod 对其他 Mod 的依赖关系，其格式与版本校验机制独立于本文档描述的三元组体系，依赖解析器另行校验。

## 严格匹配规则

加载器在发现并解析每一个 Mod 的 `mod.json` 之后，会对其中声明的 `"mili"` 三元组执行严格相等校验。校验逻辑由 `ModManifest.ValidationResult` 承载，规则如下。

对于 platform 字段：Mod 声明的 platform 字符串必须与 `VersionInfo.CURRENT_VERSION` 逐字符相等。任何差异——包括尾部空格、前导零差异、预发布标签差异——均视为不匹配。不允许 SemVer 范围表达式、前缀匹配或通配符。

对于 abi 字段：Mod 声明的 abi 字符串必须与 `VersionInfo.ABI_VERSION` 逐字符相等。由于 ABI 的核心价值在于接口稳定性，abi 的变化代表破坏性更新，因此不存在"兼容"的概念，必须精确一致。

对于 minecraft 字段：Mod 声明的 minecraft 字符串必须与 `VersionInfo.TARGET_MINECRACT` 逐字符相等。Minecraft 版本格式遵循 Mojang 官方的主版本.次版本命名，不携带补丁段或快照标识。

三个字段独立校验，任一不匹配即产生对应的错误码。如果同一 Mod 同时存在多个字段不匹配，加载器应报告所有不匹配项，而非在首个错误处停止，以便开发者一次性修全部问题。

"未绑定"Mod 不参与严格匹配。加载器仅输出警告，跳过三元组校验。这是有意为之的过渡行为，旨在降低从旧式 Mod 体系迁移到 Mili 体系的摩擦。

## 错误码

当校验失败时，`ModManifest.ValidationResult` 携带具体的错误码，供加载器分支处理并向用户展示友好的诊断信息。

**MOD_PLATFORM_MISMATCH** 表示 Mod 声明的 platform 版本与当前运行平台的 `CURRENT_VERSION` 不同。该错误通常意味着 Mod 尚未适配最新平台的实现变更，或者平台本身尚未更新到 Mod 期望的版本。开发者应对比 Mod 所基于的平台源码与当前运行平台的差异，重新编译并更新声明。

**MOD_ABI_MISMATCH** 表示 Mod 声明的 abi 版本与当前平台的 `ABI_VERSION` 不同。这通常发生在平台进行了破坏性接口变更之后，而 Mod 仍在使用旧版 API。解决该错误需要获取与当前 abi 版本匹配的 Mod SDK，重新编译 Mod，并更新 `mod.json` 中的 abi 字段。

**MOD_MINECRAFT_MISMATCH** 表示 Mod 声明的 minecraft 版本与当前平台的 `TARGET_MINECRAFT` 不同。该错误常见于 Minecraft 版本更新后，Mod 尚未适配新的客户端。开发者需要等待或参与 Mod 针对新版 Minecraft 的移植工作。

所有错误码均为校验失败类错误，会阻断该 Mod 的加载。加载器应收集所有 Mod 的校验结果后统一报告，避免逐个弹出错误导致诊断效率低下。

## 模块版本策略

Mili 平台由四个主要模块构成，每个模块在 Maven 坐标中独立版本化，但在 Mili 三元组的管理框架下保持协调演进。

**mili-abi** 是纯接口模块，不包含任何实现代码。它定义了 Mod 能够直接引用的所有公开类型，并承载 `VersionInfo` 常量类。mili-abi 的版本与 ABI 版本号保持同步，当发生破坏性接口变更时升级主版本。该模块的稳定性最高，升级频率最低。

**mili-runtime** 是平台核心实现，包含 Scope、Scheduler、EventBus、Capability 和 ResourceManager 等核心组件，以及面向 Mod 的 SDK。mili-runtime 的版本与 platform 版本号保持同步。该模块不允许引用任何 Minecraft 特定的包，与 `mili-minecraft-integration` 之间的边界由 CI 强制检查，任何跨边界的 import 将导致构建失败。

**mili-loader** 负责 Mod 的发现、类加载、GameProvider SPI 以及版本校验逻辑本身。mili-loader 的版本也与 platform 版本号保持同步。该模块同时持有 `ModManifest` 和 `ValidationResult` 等校验相关类型，并在启动阶段执行三元组校验。

**mili-minecraft-integration** 是 Mili 与 Minecraft 26.2 客户端之间的桥接层。它的版本同样与 platform 版本号保持同步，但它内部可以自由引用 Minecraft 相关包。该模块是平台中唯一被允许直接耦合 Minecraft 内部实现的模块，其他模块如需访问 Minecraft 功能需要通过 `mili-runtime` 提供的抽象间接进行。

四个模块尽管在 Maven 中各自拥有独立版本号，但在每次平台发布时统一递增。这种"统一发布、协调版本"的策略避免了模块间版本组合爆炸，确保每个平台版本对应一组经过联合测试的模块集合。开发者只需关心 platform 版本，无需单独追踪每个模块的版本号。

## 升级

当 Mili 平台发布新版本时，Mod 开发者需要遵循以下步骤使其 Mod 适配新版平台。

首先，确认目标平台版本的 `VersionInfo` 三元组，特别是 platform 和 abi 是否发生变化。如果 abi 版本未变而仅 platform 变化，通常意味着内部实现重构但对外接口不变，Mod 可能无需代码修改，仅需在 `mod.json` 中更新 platform 字段并重新测试。

如果 abi 版本发生变化，则存在破坏性接口变更。开发者需要获取新版本 `mili-abi` 的变更日志，定位 Mod 中受影响的 API 调用，逐一修复编译错误，然后更新 `mod.json` 中的 platform 和 abi 字段。测试阶段应覆盖 Mod 的全部核心功能，因为 ABI 变更可能引入微妙的行为差异。

如果 minecraft 版本发生变化，这代表 Minecraft 客户端本身发生了升级。除了更新 `mod.json` 中的 minecraft 字段外，Mod 可能还需要适配 Minecraft 内部代码的变动，这通常需要参考 `mili-minecraft-integration` 的变更日志以及相关映射更新。

对于"未绑定"Mod，升级路径的第一步是在 `mod.json` 中补充 `"mili"` 声明，将其纳入正式的版本管理体系。之后按照上述流程进行版本适配。

平台升级时建议开发者关注 Mili 官方发布的迁移指南。该平台承诺在 ABI 破坏性变更时提供详尽的迁移文档，列举所有移除、重命名或语义变更的 API，以及推荐的替代方案。

## 常见问题

**为什么不用 SemVer 范围表达式？**

SemVer 范围表达式会引入歧义和不确定性。例如 `^0.1.0` 究竟是否包含 `0.2.0` 在不同工具链中有不同解释。Mili 选择严格相等来消除这种歧义，保证每一个 Mod 的运行环境完全可预测。平台稳定性通过 ABI 的精心维护来实现，而非通过版本范围来投机性兼容。

**abi 和 platform 有什么本质区别？**

platform 描述的是实现，abi 描述的是契约。实现可以频繁重构、优化、内部重组，只要最终对外表现的行为不变，platform 版本就无需变化。而 abi 只要发生变化，就意味着有 Mod 需要修改代码才能继续工作。将二者分离，使得平台内部重构无需强制全生态升级。

**如果我只是想快速测试一个 Mod，可以不填 `"mili"` 对象吗？**

短期可以，但强烈不建议。未绑定 Mod 以警告模式运行，未来可能随平台演进而受到更多限制。在生产环境或发布版本中，未绑定状态将不被允许。

**Java 版本 25 是必须的吗？**

是的。Mili 平台构建和运行均要求 Java 25 或更高版本。`VersionInfo.TARGET_JAVA` 声明了这一最低要求。低于该版本的 JVM 无法加载 Mili 平台。

**未绑定 Mod 会不会在未来变成硬错误？**

有可能。当前允许未绑定状态是为了兼容迁移期。随着生态成熟，平台可能在未来版本中将未绑定 Mod 的警告升级为加载阻断错误。建议开发者尽早为所有 Mod 补充 `"mili"` 声明。

**多 Minecraft 版本共存时如何处理？**

Mili 平台一次只针对一个 Minecraft 版本运行。`TARGET_MINECRAFT` 在构建时确定，不可变。如果需要在不同 Minecraft 版本之间切换，必须使用对应版本的 Mili 平台构建产物。Mod 也是如此，每个 Minecraft 版本需要独立的 Mod 构建。

**CI 如何保证模块边界？**

CI 在每次提交时使用静态分析工具扫描 `mili-runtime` 和 `mili-abi` 的源代码，禁止其中出现任何 `net.minecraft` 或 `com.mojang` 等 Minecraft 特定包的 import 语句。任何违反此规则的提交将被拒绝合并。
