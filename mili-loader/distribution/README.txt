Mili Platform — Minecraft 26.2 Mod 加载器
==========================================

快速开始
--------

1. 确认已安装 JDK 25（Minecraft 26.2 要求 Java 25）
2. 运行启动脚本：
     Linux / macOS:  ./bin/mili-loader
     Windows:        bin\mili-loader.bat
3. 首次运行会自动从 Mojang 官方源下载 Minecraft 26.2 并校验（约 600MB，
   请耐心等待）。下载完成后游戏会自动启动。

之后每次运行直接进入游戏，不再重复下载。

目录结构
--------

    bin/                          启动脚本
    core/mili-*.jar               平台 JAR（内含安装器）
    mods/                         把你的 Mod JAR 放这里
    game/                         游戏数据（首次运行后自动创建）
      26.2.jar                    Minecraft 客户端
      libraries/                  依赖库
      natives/                    平台原生库
      assets/                     游戏资源
      mods/                       游戏目录内的 Mod（部分 Mod 需要）
      saves/                      存档
    README.txt                    本文件

安装 Mod
--------

把编译好的 Mod JAR 放进分发包的 `mods/` 目录，然后重启加载器。

Mod 必须在 META-INF/mod.json 中声明版本三元组：

    {
      "id": "your-mod",
      "version": "1.0.0",
      "entrypoint": "com.example.YourMod",
      "mili": {
        "platform": "0.1.0",
        "abi": 1,
        "minecraft": "26.2"
      }
    }

三项必须与平台完全一致，否则加载器会拒绝加载（这是刻意的严格校验，
用于避免运行期出现难以排查的问题）。

关于 Minecraft 文件
------------------

分发包中不包含 Minecraft 本体。首次运行时，加载器内置的安装器会从
Mojang 官方 CDN 下载并逐个校验 SHA-1。这样做有两个原因：

  * 遵守 Mojang 使用条款，游戏本体不能被再分发
  * 避免分发包体积膨胀到 600MB

你也可以手动预先安装：

    java -cp core/mili-*.jar org.loader.installer.InstallerMain \
         --game-dir game --version 26.2

常用参数
--------

    --mili-mods <dir>   指定 Mod 目录（分发包默认已指向 <dist>/mods，一般无需手动传）
    --dry-run        只查询元数据并打印所需体积，不下载
    --skip-assets    跳过资源下载（约省 480MB，但进世界会缺资源）
    --server         以服务端模式启动

故障排查
--------

**游戏能启动，但 Mod 内容一片空白**
Mod 没被发现。启动日志里有一行
`[Mili] Mod 目录: ... (JAR/ZIP=n, 识别=m)` —— 若 `n > 0` 而 `m = 0`，
说明 JAR 放在了平台没扫的目录，或JAR 里缺少 `META-INF/mod.json`。
Mod 必须放在分发包的 `mods/` 目录（不是 `game/mods/`）。

**ClassNotFoundException: net.minecraft.SharedConstants**
平台没能把 MinecraftClassLoader 交给绑定层，通常意味着 core/ 下的
平台 JAR 与 bin/ 下的启动脚本版本不匹配。重新解压一份完整的分发包。

**提示找不到 Minecraft**
运行 `./bin/mili-loader --dry-run` 检查网络能否访问 Mojang CDN。

**提示 Java 版本过低**
Minecraft 26.2 需要 Java 25。设置 JAVA_HOME 指向 JDK 25 后重试。

**资源文件缺失**
说明 assets 未下载完整。删除 game/ 目录后重新运行启动脚本，
下载过程是幂等的，只会补齐缺失部分。
