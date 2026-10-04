# ADR-0009: Real Minecraft Integration as a Separate Adapter

## Decision
Minecraft 26.x 通过独立 Integration Adapter 接入 Runtime。

## Reasons
防止 Minecraft 依赖污染 Kernel；支持未来版本；降低逆向变更影响；保持 ABI 稳定。

## Rejected
把 Minecraft API 直接塞进 Kernel。
