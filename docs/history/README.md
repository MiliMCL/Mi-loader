# ⚠️ history/ —— 历史文档，不代表当前状态

本目录存放**历史分析与设计草案**，保留它们是为了追溯决策脉络。

## 这里的文档不能用来判断项目现状

这些文件描述的是**当时**的状态，其中大量内容已被后续实现推翻。
具体而言，以下论断**已经不再成立**：

| 历史文档中的论断 | 实际情况 |
|---|---|
| `ARCHITECTURE.md` 描述的模块边界 | 部分仍成立，但 ClassLoader 部分已完全重写 |
| `FINAL_REPORT.md` 的完成度自评 | 严重过时 |
| `execution/CURRENT_IMPLEMENTATION_STATE.md` | **完全过时**，该文件名极具误导性 |
| `execution/THREAD_MODEL.md` | 基于已删除的轮询式 tick 设计 |
| `API_REFERENCE.md` 的部分签名 | 签名已变更（如 `ModClassLoader.getClassLoader()` → 继承自 URLClassLoader） |

## 应该读哪个

**当前实现状态只以 [`../architecture/CURRENT.md`](../architecture/CURRENT.md) 为准。**

任何与 `CURRENT.md` 冲突的描述都视为过时。

## 为什么要保留

删除历史会丢失"为什么变成现在这样"的上下文 —— 特别是一些
看似可以简化、但实际踩过坑的决定。这些坑记录在
[`../decisions/`](../decisions/) 的 ADR 里。

## 阅读历史文档时的纪律

1. **先读 `CURRENT.md`**，建立当前认知
2. 历史文档只用于理解**决策动机**
3. 绝不把历史文档当作实现依据
4. 若历史文档与代码冲突 —— **以代码为准**