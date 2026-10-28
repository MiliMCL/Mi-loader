# ClassLoader 契约

> 权威实现：`mili-loader/.../classloader/`
> 当前状态见 [CURRENT.md](CURRENT.md)

---

## 拓扑

```text
AppClassLoader（平台：abi / runtime / loader / integration）
     │
     └─ MinecraftClassLoader            唯一定义 net.minecraft.*
            │
            ├─ ModClassLoader (mod-a)   child-first，parent = gameCL
            ├─ ModClassLoader (mod-b)
            └─ ModClassLoader (mod-c)
```

Mod 之间是**兄弟**，不是父子。

---

## 两条加载路径：只有一条是生产路径

| 路径 | 入口 | 是否创建隔离 ClassLoader | 用途 |
|---|---|---|---|
| **生产** | `LoaderMain` → `ModDiscovery` → `ModClassLoaderManager.create(manifest, source)` | 是（parent = `MinecraftClassLoader`） | 真正启动游戏 |
| **进程内 API** | `org.loader.runtime.mod.ModLoader#load(manifest, parent)` | **否** —— 直接使用调用方给的 ClassLoader | 测试 / Mod 代码已在当前 classpath 上的宿主场景 |

第二条路径曾被误认为"也是加载器"：它的 `createClassLoader` 只有一行
`return parent != null ? parent : getClass().getClassLoader()`，
注释写着 "In a real implementation, this would create a URLClassLoader from mod jars"。
后果是**一个根本没被打进 classpath 的 Mod 也能"加载成功"**，直到某处引用它的类时
才抛 `ClassNotFoundException` —— 报错点离根因很远。

现在的处理：

- `ModLoader.createsIsolatedClassLoaders()` 恒返回 `false`，把这个事实变成可断言的代码；
- `load()` 在清单声明了 `entrypoint` 时，用 `Class.forName(entrypoint, false, loader)`
  做一次可见性检查，不可见就立刻抛 `ModLoadException`，消息直接指向 `ModClassLoader`。

**`ModManager` 属于第二条路径**：`LoaderMain` 不引用它，它也不参与生产启动。
它的 `resolve()` 与生产路径共用同一个
`org.loader.runtime.util.DependencySorter`，因此"依赖环会不会被拦住"不存在两套答案。

### 卸载边界

`ModClassLoader.close()` 关闭 `URLClassLoader`，`ModClassLoaderManager.close()` 逆序关闭，
这让 Mod JAR 的文件句柄被释放（Windows 上才删得掉/换得掉）。

**这不等于类被卸载。** JVM 只在 ClassLoader 与其所有类都不可达时才回收它们；
静态字段、线程栈、缓存里任何一处强引用都会让整棵类树继续存活。
因此文档不声称"彻底卸载"，热替换的正确实现方式仍是重启进程。

---

## 三条硬约束

### 1. Minecraft 类全局唯一

`MinecraftClassLoader` 是全平台**唯一**允许定义 `net.minecraft.*` 的 ClassLoader。

**原因**：Minecraft 内部存在大量跨类强引用与静态状态（注册表、
`SharedConstants`、资源管理器）。若同一 MC 类被两个 ClassLoader 各加载一份，
两份类互不相等 → `ClassCastException`，且静态初始化状态分裂，
导致注册表与游戏状态不一致。

**旧实现**：每个 ModClassLoader 都拿到完整 Minecraft classpath 平铺加载，
且与 gameCL 平行无关联 → Mod 根本无法安全操作 Minecraft 对象。

### 2. ModClassLoader 的 parent 是 MinecraftClassLoader

**绝不是"依赖的 Mod"。**

**原因**：若 A 的 parent 是 B，则 A 卸载会连带影响 B 已链接的类；
且依赖图的 ClassLoader 化会让循环依赖无法处理。

**跨 Mod 访问**通过 `ClassVisibility.export()` 显式声明，不由父子关系隐式获得。

**旧实现**：`ModClassLoaderManager` 恒传 `parent = null`，
`ModClassLoader` 里的依赖链逻辑是死代码。

### 3. 可见性由 ClassVisibility 单点定义

| Resolution | 判定 | 行为 |
|---|---|---|
| `FORBIDDEN` | Loader 内部 / Runtime kernel / Installer / JDK 内部 / Mojang 内部 | 抛 `ClassNotFoundException`，记录 violation |
| `PARENT_FIRST` | `org.loader.api.**`、公开 Runtime 包、`net.minecraft.**` | 委派 parent |
| `SELF_FIRST` | 其余包 | Mod 自己定义（child-first） |

---

## Mod 可见 / 不可见

### 可见（PARENT_FIRST）

```
org.loader.api.**              Mili ABI，Mod 的稳定编程接口
org.loader.runtime.tick.**     Tick 契约
org.loader.runtime.mod.**      Mod SDK
org.loader.runtime.client.**   客户端能力
org.loader.runtime.minecraft.** 集成桥接
org.loader.runtime.service.**  服务
net.minecraft.**               Minecraft 本体
```

### 不可见（FORBIDDEN）

```
org.loader.loader.**           Loader 实现内部
org.loader.runtime.kernel.**   Runtime 内核实现
org.loader.runtime.error.**
org.loader.runtime.util.**
org.loader.runtime.observability.**
org.loader.runtime.security.**
org.loader.runtime.jvm.**
org.loader.runtime.instance.**
org.loader.installer.**        安装器内部
java.** javax.** jdk.** sun.** com.sun.**
com.mojang.** net.minecraftforge.** org.spongepowered.** org.bouncycastle.**
```

### Mod 私有（SELF_FIRST）

任何未列入上述清单的包。天然跨 Mod 隔离：两个 Mod 各自定义同名类互不可见。

---

## 委托算法（ModClassLoader.loadClass）

```java
1. 已在缓存 → 返回
2. ClassVisibility.resolve(name)
   ├─ FORBIDDEN     → 记 violation，抛 ClassNotFoundException
   ├─ PARENT_FIRST  → parent.loadClass(name)
   └─ SELF_FIRST    → findClass(name)，失败则回退 parent
3. closed → 抛 ClassNotFoundException
```

### MinecraftClassLoader.loadClass

```java
1. 已在缓存 → 返回
2. org.loader.* → 无条件委派 parent
   （MC classpath 绝不允许阴影平台实现）
3. 其他 → self-first（findClass）
4. 未找到 → 回退 parent（JDK / 平台类）
```

---

## 资源隔离

Mod 资源优先从自身 classpath 读取。禁止读取：

```
META-INF/mili/platform*
org/loader/loader/**
org/loader/installer/**
```

拒绝会记录 `AccessViolation`，可通过 `ModClassLoader.violations()` 取回。

**资源不跨 Mod 泄漏**：A 的私有资源 B 读不到（各自 classpath 独立）。

---

## 重复类检测

child-first 策略下两个 Mod 定义同名类是**合法但可疑**的：
互相隔离不会崩溃，但通常意味着重复打包了第三方库。

`ModClassLoaderManager` 提供两级检测：

- `registerClassOwner(class, modId)` — 主动登记，冲突时返回首个持有者
- `detectDuplicateClasses()` — 扫描所有 Mod 已加载的私有类

**仅统计 SELF_FIRST 类**——PARENT_FIRST 共享类天然重复，属正常。

---

## 诊断

```java
ModClassLoaderManager.diagnostics()
// ClassLoader 拓扑:
//   MinecraftClassLoader (net.minecraft.* 唯一定义来源)
//     └─ mod-a  sources=1 loaded=12
//     └─ mod-b  sources=2 loaded=8
//   重复类警告 (1):
//     com.foo.Shared 定义于 [a] 与 [b]
//   被拒绝的访问 (2):
//     [Mod x] 拒绝访问 org.loader.loader.LoaderMain — 访问被 ClassVisibility 契约禁止
```

启动时 `LoaderMain` 会打印该快照。

---

## 泄漏防护

`ClassLoaderLeakTest` 验证：

- 关闭后的 ModClassLoader 可被 GC 回收（WeakReference + 强制 GC）
- 关闭后 Mod JAR 可被删除（无文件句柄占用）
- 关闭 Mod CL **不**连带关闭 MinecraftClassLoader
- Manager 逆序关闭，重复创建幂等（不产生僵尸 CL）

---

## 测试

| 测试 | 验证 |
|---|---|
| `ClassVisibilityTest` | 可见/隐藏/解析策略/跨 Mod 导出登记 |
| `ClassIsolationTest` | MC 类共享同一 Class、Mod 兄弟关系、关闭独立性 |
| `DuplicateClassTest` | 重复类检测、创建幂等、依赖顺序 |
| `ResourceIsolationTest` | 资源隔离（真实 JAR 夹具） |
| `ClassLoaderLeakTest` | GC 回收、文件句柄释放、生命周期隔离 |
| `ModClassLoaderTest` | 基础构造、parent 契约、关闭语义 |

---

## 修改指引

改动本契约时必须同时：

1. 更新本文档
2. 更新 `ClassVisibility` 的 Javadoc
3. 更新 [CURRENT.md](CURRENT.md) 的拓扑图
4. 补/改对应测试
5. 确认 `mili-loader` 之外无模块依赖被破坏

**不要**为了"更干净"引入 Fabric 的 Knot/Mixin/LaunchWrapper 概念。
Mili 有自己的契约。