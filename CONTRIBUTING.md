# 贡献指南

分支命名使用 `module/<name>`、`fix/<name>`、`refactor/<name>`、`chore/<name>`。禁止 phase 分支和直接推送 main。每个 PR 必须说明范围、非范围、测试、数据库/API/权限影响、截图与回滚方式；默认 squash merge。

提交前执行 README 中的全量质量命令。数据库变化只新增 Flyway 文件。AI 生成代码必须经过同等静态检查、测试和人工审查。
