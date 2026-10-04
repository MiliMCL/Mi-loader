# Minecraft 26.x Reverse Engineering

必须建立真实事实模型：
- main/launcher/bootstrap
- server construction
- lifecycle
- tick entry and ordering
- shutdown
- registry bootstrap/freeze/reload
- world load/unload
- network init/connection/disconnect
- command registration

每个结论记录 class、method、descriptor（若有）、bytecode evidence、mapping source、confidence。

推荐工具：javap、CFR/Vineflower、ASM、Byte Buddy、mapping parser。

工具输出不是最终事实，实际字节码和运行日志才是。
