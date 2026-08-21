# S8020 系统架构

S8020 是前后端分离的模块化单体：

- `technical-platform/web`：Vue 两端应用，运行入口为 `work.html` 和 `tech.html`。
- `technical-platform/backend/apps/api`：同步 REST API。
- `technical-platform/backend/apps/worker`：异步任务。
- `technical-platform/backend/modules`：core、iam、org、authz、workflow、document、notification、audit 等领域模块。
- `technical-platform/database`：PostgreSQL 与 Flyway 迁移。

`employee / center / tech` 是业务能力视角；运行时只有 `work / tech`。员工与中心能力共享同一业务事实，通过身份、岗位、组织、权限、字段范围和数据范围形成不同视图。

依赖方向：端口/Controller → Application Service → Domain Port → Infrastructure Adapter。领域模块不得依赖前端，不得跨模块直接访问对方内部实现。
