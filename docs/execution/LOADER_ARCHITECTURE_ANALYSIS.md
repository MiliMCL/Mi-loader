# Loader Architecture Analysis — Fabric vs Mili Runtime

Date: 2026-10-03
Sources: Fabric Loader JAR 0.16.9 (1,387,357 bytes) + Mili Runtime current JAR (204,847 bytes)

---

## 1. Fabric Loader — 实际 JAR 结构

### 1.1 MANIFEST.MF

```
Manifest-Version: 1.0
Main-Class: net.fabricmc.loader.impl.launch.server.FabricServerLauncher
Fabric-Loom-Remap: false
Automatic-Module-Name: net.fabricmc.loader
Multi-Release: true
```

关键:
- **有 Main-Class** → 支持 `java -jar`
- **Multi-Release: true** → Java 17 专用类在 `META-INF/versions/17/`
- **Fabric-Loom-Remap** → Loom 构建系统的 remap 标记
- **有一个 server launcher + 一个 client launcher**

### 1.2 启动类

Client: `net.fabricmc.loader.impl.launch.knot.KnotClient` (KnotClient extends Knot)
Server: `net.fabricmc.loader.impl.launch.server.FabricServerLauncher`

### 1.3 SPI 服务发现

```
META-INF/services/net.fabricmc.loader.impl.game.GameProvider
  → net.fabricmc.loader.impl.game.minecraft.MinecraftGameProvider

META-INF/services/org.spongepowered.asm.service.IMixinService
META-INF/services/org.spongepowered.asm.service.IMixinServiceBootstrap
META-INF/services/org.spongepowered.asm.service.IGlobalPropertyService
```

**关键: Fabric 不知道 MC 在哪里。GameProvider 通过 SPI 被发现。**
这实现了 Loader 与 Game 的完全解耦。

### 1.4 核心包结构

```
net/fabricmc/loader/
  api/                          ← 公开 API（给 Mod 用）
    ModInitializer.class
    ClientModInitializer.class
    DedicatedServerModInitializer.class
    EntrypointException.class
    FabricLoader.class           ← 单例 API
    LanguageAdapter.class
    MappingResolver.class
    SemanticVersion.class
    ModContainer.class
    ObjectShare.class
    metadata/                    ← Mod 元数据
      ModMetadata.class
      ModDependency.class
      ModEnvironment.class
      ModOrigin.class
      version/
        VersionComparisonOperator.class
        VersionInterval.class
        VersionPredicate.class
    entrypoint/
      EntrypointContainer.class
      PreLaunchEntrypoint.class

  impl/                         ← 内部实现
    FabricLoaderImpl.class       ← 核心单例
    discovery/
      ModCandidateFinder.class   ← SPI: mod 发现器
      ModCandidateImpl.class
      ModDiscoverer.class        ← 扫描 mod 的核心
      DirectoryModCandidateFinder.class
      ClasspathModCandidateFinder.class
      ArgumentModCandidateFinder.class
      ModResolver.class          ← 依赖解析
      ModSolver.class            ← SAT4J 约束求解
      ModPrioSorter.class        ← 拓扑排序
      RuntimeModRemapper.class   ← 运行时 remap
      ModLoadCondition.class
      ResultAnalyzer.class
    entrypoint/
      EntrypointContainerImpl.class
      EntrypointStorage.class    ← 入口点注册表
    game/
      GameProvider.class         ← SPI interface
      GameProviderHelper.class
      GameTransformer.class      ← 字节码变换器
      LibClassifier.class        ← 库分类器
      LoaderLibrary.class
      minecraft/
        MinecraftGameProvider.class   ← MC 专用 provider
        McVersion.class
        McVersionLookup.class   ← ASM 识别版本
        BundlerProcessor.class
        launchwrapper/
          FabricTweaker.class   ← Legacy 启动器集成
        patch/
          EntrypointPatch.class       ← 入口点补丁
          BrandingPatch.class         ← 品牌补丁
          EntrypointPatchFML125.class
          TinyFDPatch.class
      patch/
        GameTransformer.class
        GamePatch.class
    launch/
      FabricLauncher.class
      FabricLauncherBase.class
      FabricMixinBootstrap.class    ← Mixin 初始化
      knot/
        Knot.class              ← 核心启动器
        KnotClassLoader.class   ← 变换 ClassLoader
        KnotClassDelegate.class ← 加载委派
        KnotClassLoaderInterface.class
        DummyClassLoader.class
        FabricGlobalPropertyService.class
    gui/                          ← GUI 工具

  language/                       ← 多语言适配
  impl/lib/                       ← 内嵌库
    asm/                          ← 不依赖外部 ASM
    sat4j/                        ← SAT 求解器
    mappingio/                    ← Mapping 库
```

### 1.5 KnotClassLoader 关键设计

```
KnotClassLoader extends AbstractSecureClassLoader {
    DynamicURLClassLoader urlLoader;       ← URL 加载器(可动态加 URL)
    ClassLoader originalLoader;            ← 原始 app ClassLoader
    KnotClassDelegate delegate;            ← 变换 + 委派
}

加载链:
1. KnotClassLoader.loadClass(name)
2. → delegate.canTransform(name) ? 变换
3. → 查找: urlLoader.findClass(name)
4. → fallback: originalLoader.findClass(name)
```

**关键: 变换发生在 loadClass 阶段。这是 Mixin/EntrypointPatch 的基础。**

### 1.6 MinecraftGameProvider.launch(ClassLoader)

```
provider.launch(classLoader) {
    Class<?> mainClass = Class.forName(getEntrypoint(), false, classLoader);
    Method main = mainClass.getMethod("main", String[].class);
    main.invoke(null, getLaunchArguments());
}
```

Fabric 通过 GameProvider.launch() → 反射调用 MC main
而不是在 LoaderMain 里直接反射。

### 1.7 ModInitializer 接口

```java
public interface ModInitializer {
    void onInitialize();
}

// 入口点发现流程:
EntrypointStorage: 扫描 mod.json 的 "entrypoints" 字段
  → main: [com.example.ModMain]
  → client: [com.example.ClientMod]
  → server: [com.example.ServerMod]
  → preLaunch: [...]

// 调用时机:
PreLaunchEntrypoint.onPreLaunch()  ← MC main 之前
ModInitializer.onInitialize()      ← MC main 之前
ClientModInitializer.onInitializeClient()
DedicatedServerModInitializer.onInitializeServer()
```

---

## 2. 当前 Runtime Loader — 实际结构

### 2.1 MANIFEST.MF

```
Manifest-Version: 1.0
```

**关键: 没有 Main-Class。**
当前 JAR 不能被 `java -jar` 启动。

### 2.2 入口点: LoaderMain.main()

```java
public static void main(String[] args) throws Exception {
    Path gameDir = args.length > 0 ? Path.of(args[0]) : Path.of(".");
    LoaderConfig config = LoaderConfig.load(gameDir);
    LoaderMain loader = new LoaderMain(config);
    loader.launch(args);
}
```

### 2.3 启动链

```
java ... LoaderMain.main(args)
  → MinecraftDiscovery.scan()              // 找 MC jar
  → ModDiscovery.scan()                    // 找 mods (mod.json)
  → modDiscovery.resolveDependencies()     // 简单拓扑排序
  → classLoaderManager.createModClassLoader() × N  // 创建 ModClassLoader
  → createMinecraftClassLoader()           // 单 URLClassLoader(MC+libs+parent=app)
  → entryHook.install()                    // 创建 MinecraftBootstrap
  → invokeMinecraftMain()                  // 反射 net.minecraft.server.Main.main()
```

### 2.4 ModClassLoader — 已创建但未使用

ModClassLoader 是为每个 Mod 创建的独立 ClassLoader，
但 invokeMinecraftMain() 从未引用它们。
**Mod 的 mainClass 从未被加载或调用。**

### 2.5 当前没有

- 字节码变换 (Mixin, EntrypointPatch, AW)
- GameProvider SPI
- Mapping/Remap 层
- 真正的 ClassLoader 委派链
- MC 入口点补丁
- manifest Main-Class

---

## 3. 启动链对比

### 3.1 Fabric 实际启动链

```
┌─────────────────────────────────────────────────────┐
│  External Launcher (Prism / MC Launcher)            │
│  Loads: MC JAR + libs + fabric-loader.jar           │
│  Calls: KnotClient.launch(args, CLIENT)             │
└────────────────┬────────────────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────────────────┐
│  KnotClassLoader created                            │
│  - originalLoader = app CL (MC JARs + libs)        │
│  - urlLoader = empty DynamicURLClassLoader          │
│  - Mixin environment initialized                    │
└────────────────┬────────────────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────────────────┐
│  GameProvider discovered via SPI                    │
│  → MinecraftGameProvider.locateGame()               │
│  → MinecraftGameProvider.initialize()               │
│  → MinecraftGameProvider.unlockClassPath()          │
│    (adds mod Knot URLs to classpath)                │
└────────────────┬────────────────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────────────────┐
│  ModInitializer.onInitialize() called                │
│  PreLaunchEntrypoint.onPreLaunch() called           │
│  (all before MC main)                               │
└────────────────┬────────────────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────────────────┐
│  GameProvider.launch(classLoader)                   │
│  → Class.forName("net.minecraft.client.main.Main")  │
│  → method.invoke()                                  │
└────────────────┬────────────────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────────────────┐
│  Minecraft runs under KnotClassLoader               │
│  All MC classes can be transformed (Mixin, etc.)   │
│  Mods loaded by same KnotClassLoader              │
└─────────────────────────────────────────────────────┘
```

### 3.2 当前 Runtime 实际启动链

```
java ... LoaderMain.main(args)
  │
  ├── MinecraftDiscovery.scan(config)        // find server.jar
  ├── ModDiscovery.scan(config)              // find mod.json files
  ├── resolveDependencies()                  // topological sort
  ├── classLoaderManager.createModClassLoader()  // ⚠ 创建后从未使用
  │
  ├── createMinecraftClassLoader()           // SINGLE URLClassLoader
  │   new URLClassLoader([mc.jar, libs...], parent=appCL)
  │   → ALL classes loaded by one flat CL
  │   → NO transformation
  │   → NO mod visibility
  │
  ├── entryHook.install()                    // creates MinecraftBootstrap
  │                                          // starts Runtime
  │
  └── entryHook.invokeMinecraftMain()
      → Class.forName("net.minecraft.server.Main")
      → main.invoke()                         // ⚠ 完全反射
                                         // ⚠ RT 不控制 MC 生命周期
                                         // ⚠ MC 运行在 appCL 子集下
```

---

## 4. ClassLoader 层级对比

### 4.1 Fabric 实际 CL 层级

```
Bootstrap CL (java.base, java.lang...)
  ↑ parent delegation
Platform CL (jdk.*)               [Java 9+]
  ↑
Application CL (MC JARs + libraries from profile classpath)
  ↑  originalLoader reference
KnotClassLoader (Fabric Loader classes + Mod classes)
  - urlLoader: empty DynamicURLClassLoader → gets mod URLs
  - Can transform ANY class during load
  - Mixin applies patches at transform time
```

### 4.2 当前 Runtime 实际 CL 层级

```
Bootstrap CL
  ↑
Application CL (our JAR + all deps on java -cp)
  ↑  parent of our URLClassLoader
URLClassLoader ← single flat CL
  - server.jar
  - libraries
  - (nothing isolated)
  ↑
ModClassLoader × N ← ISOLATED BUT NEVER USED
  - Only created, never called to load mod classes
  - Mod main class never invoked
```

---

## 5. 差异分析

### 5.1 正常设计差异 (正常，不是缺陷)

| 差异 | Fabric | Runtime | 判定 |
|------|--------|---------|------|
| GameProvider SPI | SPI ServiceLoader | 硬编码扫描 | Runtime 目标是 MC-only，可以接受简化 |
| Mapping/Remap | 需要（MC 混淆） | 不需要（Paper/Folia 已反混淆） | ✅ 正常 |
| Mixin 系统 | 核心功能 | 无 | ✅ 可后续添加 |
| 多语言 API | LanguageAdapter | 不需要 | ✅ 正常 |
| MC 版本兼容 | 多版本 | 仅 26.2 | ✅ 正常 |
| 独立 Launcher | 不需要 | 有 DevLauncher | ✅ 正常 |

### 5.2 架构缺陷 (需要修复)

| # | 缺陷 | 严重性 | 证据 |
|---|------|--------|------|
| 1 | **Manifest 无 Main-Class** | HIGH | `Manifest-Version: 1.0` 只有一行 |
| 2 | **Mod ClassLoader 创建后从未使用** | HIGH | LoaderMain 第 73-77 行创建但不调用 |
| 3 | **单一 flat ClassLoader 加载 MC** | HIGH | URLClassLoader 无委派链 |
| 4 | **reflective main 调用** | MEDIUM | 不走 GameProvider.launch() |
| 5 | **MC 入口点未注入回调** | HIGH | 无 EntrypointPatch，无品牌标注 |
| 6 | **Runtime 不能拦截 MC tick/shutdown** | HIGH | MC 生命周期游离在 Scope 外 |
| 7 | **Mod 入口从未调用** | CRITICAL | mod mainClass 从未被 invoke |

---

## 6. 分类判定

当前 Loader 属于:

**B. Minecraft bootstrap wrapper** (非真正的 Fabric Loader)

原因:
- 有独立 main 方法 ✓
- 能发现 MC + mods ✓
- 但 ClassLoader 是 flat 的 ✗
- 但 MC 入口未经变换 ✗
- 但 Mod 类从未加载 ✗
- 但 Runtime 不控制 MC 完整生命周期 ✗

不是 A (独立 Launcher): 因为没有 manifest Main-Class
不是 C/D: 缺少真正的 CL 委派和变换能力

结论: 需要重构。

---

## 7. 重构目标结构

```
目标: Runtime Minecraft Loader

Minecraft 26.2 (loaded by RuntimeModClassLoader)
        ↓
Loader GameProvider (SPI-pluggable, MC-specific impl)
        ↓
RuntimeModClassLoader (transforming loader CL)
        ↓
Runtime Kernel (Scope/Capability/Scheduler)
        ↓
Mod Runtime (Scope-managed mod lifecycle)
```

设计要求:
1. **Manifest Main-Class** → 支持 `java -jar`
2. **GameProvider 接口** → 解耦 game location
3. **单一 Loader ClassLoader** → 加载 loader+runtime 类
4. **Runtime 完整接管 MC 生命周期** → tick/shutdown 注入
5. **Mod ClassLoader 集成** → mod 类由 loader 加载并调用入口
6. **Capability 驱动的权限** → mod permission = Runtime Capability

---

## 8. 保留的不变量

重构时不得破坏:
- Runtime ABI (Scope, Capability, Permission, Scheduler, Resource)
- Lifecycle 状态机 (DISCOVERED → RUNNING → STOPPED/FAILED)
- Ownership/Cleanup 不变量
- Capability lease = scope-bound
- 已有 151 个测试 (可通过)

---

## 9. 执行计划

1. 修复 Manifest → 添加 Main-Class
2. 新增 GameProvider SPI 接口
3. 新增 MinecraftGameProvider (使用 Bukkit/MCLocation 逻辑)
4. 重构 LoaderMain → 使用 GameProvider.launch()
5. 修复 ModClassLoader → 实际用于 mod 加载
6. 注入 MC tick/shutdown 回调 (通过 Runtime Scope)
7. 接入 Capability/Permission → mod 能力控制
8. 测试验证: 保留原有 151 条 + 新增 loader bootstrap 测试
9. 真实验证: `java -jar` 启动 + MC 启动
