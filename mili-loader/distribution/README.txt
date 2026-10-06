Mili Platform — Minecraft 26.2 Mod 加载器
==========================================

快速开始
--------

1. 确认已安装 JDK 25（Minecraft 26.2 要求 Java 25）
2. 运行启动脚本：
     Linux / macOS:  ./bin/mili-loader
     Windows:        bin\mili-loader.bat
3. Windows 版首次运行会问你两件事，问一次就记住了：
     * 游戏名        —— 游戏内显示的名字，同时用于派生离线 UUID
     * 访问令牌      —— 只有联机正版服务器/Realms 才需要，直接回车跳过即
                     以离线模式运行
   回答结果保存在 bin\mili-loader.cfg，之后每次启动不再询问。
4. 首次运行会自动从 Mojang 官方源下载 Minecraft 26.2 并校验（约 600MB，
   请耐心等待）。下载完成后游戏会自动启动。

之后每次运行直接进入游戏，不再重复下载。

不需要手动传任何参数。Minecraft 版本由脚本从平台 JAR 内
META-INF/mili/platform.json 读取（读不到时退回解析 JAR 文件名里的
-mcX.Y），因此永远与平台版本一致。

如需改游戏名或清除保存的令牌，删除 bin\mili-loader.cfg 后重跑即可。

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

平台启动脚本（bin\mili-loader）只识别两个参数：

    <gameDir>            第 1 个位置参数，游戏数据目录；缺省为当前目录
    --mili-mods <dir>    Mod 目录；缺省为 <gameDir>/mods

除这两个以外的参数会原样转发给 Minecraft。因此不要给启动脚本传
--dry-run / --skip-assets / --server —— 它们属于下面的安装器，传给
Minecraft 会被 joptsimple 以 UnrecognizedOptionException 拒绝启动。

只想预装游戏、不启动客户端时，直接调安装器（注意是连字符 --game-dir）：

    java -cp core/mili-*.jar org.loader.installer.InstallerMain \
         --game-dir game --version 26.2 [--dry-run] [--skip-assets]

服务端/客户端由平台根据 JAR 内容自动判定（检测到客户端入口走客户端，
否则走服务端），无需命令行开关。

账号
----

平台自身不做任何认证，也不会伪造凭据。脚本默认以离线模式启动：按你的
游戏名派生一个离线 UUID（与原版 OfflinePlayer:<名字> 方案一致），
不传 --accessToken。此时无法联机正版服务器，也无法使用 Realms。

要联机，把 accessToken 填进 bin\mili-loader.cfg 的 accessToken 一行，
启动脚本会原样传给 Minecraft。日志里出现 401 / Realms 认证失败属于
凭据无效的正常反馈，不影响离线游玩。

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
先看启动脚本打印的 `[Mili] MC version:` 那一行是否正确；不对说明平台
JAR 读取出了问题，重新解压一份完整的分发包。若版本正确，则是网络问题，
用安装器单独探测（注意 --dry-run 属于安装器，不属于启动脚本）：

    java -cp core/mili-*.jar org.loader.installer.InstallerMain \
         --game-dir game --version 26.2 --dry-run

**提示 Java 版本过低**
Minecraft 26.2 需要 Java 25。设置 JAVA_HOME 指向 JDK 25 后重试。

**资源文件缺失**
说明 assets 未下载完整。删除 game/ 目录后重新运行启动脚本，
下载过程是幂等的，只会补齐缺失部分。
