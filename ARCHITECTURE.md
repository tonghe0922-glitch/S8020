# S8020 系统架构

S8020 是前后端分离的模块化单体：

- `technical-platform/web`：Vue 两端应用，运行入口为 `work.html` 和 `tech.html`。
- `technical-platform/backend/apps/api`：同步 REST API。
- `technical-platform/backend/apps/worker`：异步任务。
- `technical-platform/backend/modules`：core、iam、org、authz、workflow、document、notification、audit 等领域模块。
- `technical-platform/database`：PostgreSQL 与 Flyway 基线/增量迁移。

`employee / center / tech` 是业务能力视角；运行时只有 `work / tech`。员工与中心能力共享同一业务事实，通过身份、岗位、组织、权限、字段范围和数据范围形成不同视图。

依赖方向：端口/Controller → Application Service → Domain Port → Infrastructure Adapter。领域模块不得依赖前端，不得跨模块直接访问对方内部实现。

## 认证与基础设施边界

- PostgreSQL `sjg_oms` 是业务与身份事实源；Redis 只承载可撤销会话和 Step-Up 短期状态。
- Redis 不可用时，登录和令牌认证返回稳定的 `503 session_store_unavailable`，不得退化成模糊 500 或绕过会话校验。
- 安全审计支持 `fail-open` 与 `fail-closed`：开发环境可降级并输出结构化 WARN/健康指标；生产默认决策应记录并采用合规要求的模式。
- Spring 运行时依赖 AOP 的组件必须可代理；仓库门禁禁止 Spring 托管类或事务方法使用不兼容的 `final`。

## PHASE-11 复用边界

P011–P016 通过 `Phase11ApiSupport.Endpoint` 统一 API 层的创建、读取面、数据范围投影、监控脱敏、幂等哈希和审计。各流程的权限映射、命令结构、状态机和不可变业务事实继续独立维护；差异显著的领域服务不做强行“大一统”抽象。
