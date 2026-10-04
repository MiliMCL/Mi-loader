# Minecraft 26.x Classloading

明确记录 Bootstrap ClassLoader、Minecraft ClassLoader、Runtime ClassLoader、Mod ClassLoader 的关系。

不要复刻旧 Loader 的统一语义。采用 explicit runtime boundary / explicit mod boundary / explicit dependencies。

出现 ClassCastException、NoSuchMethodError、LinkageError 时必须记录 class name、defining loader、module、dependency、code source。
