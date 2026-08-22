# PHASE-03 Flyway 数据库基线与增量迁移

本目录是 S8020 当前有效的数据库迁移事实源，由代码仓库**手工维护、自动校验**，不再依赖仓库外知识库或已删除的生成脚本。

## 目录职责

- `cluster/`：PostgreSQL 集群级角色合同，不提交密码。
- `oms/`：`sjg_oms` 已发布基础迁移。
- `audit/`：`sjg_audit` 已发布基础迁移与不可变审计约束。
- `dw/`：`sjg_dw` 已发布基础迁移与分析读写约束。
- `manifest.json`：保留初始基线的历史来源与哈希证据；其中旧 `source_path` 仅用于追溯，不代表当前仍存在生成输入。
- `../flyway-overlays/<database>/`：已发布基线之后的增量迁移。

## 不可变规则

1. 已进入 `main` 或任何共享环境执行过的 `V*__*.sql` 禁止修改、删除、改名或重新编号。
2. 结构和数据变化只能新增迁移。
3. 每个逻辑数据库的版本号在基础目录与 overlay 目录之间统一排序；新版本必须大于该数据库当前最大版本。
4. 新迁移命名为 `V<版本>__<snake_case>.sql`，同一逻辑数据库不得重复版本。
5. 密码、令牌、真实人员和生产数据不得写入迁移；敏感值只能通过受控环境变量占位符注入。
6. 同一未发布模块优先合并为 1–3 个内聚迁移，不再按“一表一个迁移”机械拆分；已发布历史迁移保持原样。

## 自动门禁

```bash
python3 scripts/quality/flyway_versions.py
# PR 中同时验证历史迁移不可变、新增版本递增：
python3 scripts/quality/flyway_versions.py --base-ref origin/main

bash scripts/database/migrate.sh
```

`flyway_versions.py` 会检查文件名、同库版本唯一性、已发布迁移不可变以及 PR 新增版本必须高于基线最大版本。真实冷启动 CI 还会执行迁移、重复执行、管理员登录、会话读取和错误密码审计验证。

运行时密码和首次管理员明文密码始终属于部署环境，不得进入 Git 或 CI 日志。
