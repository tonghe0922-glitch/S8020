# S8020 首次部署与登录手册

本文覆盖“全新 clone、空 Docker 卷”场景，目标是让新人只依赖仓库文档完成：环境校验 → 基础设施 → Flyway 迁移 → 首个管理员 → API 登录 → 两端前端。

## 1. 安全边界

1. `.env` 只用于本机或受控部署环境，严禁提交到 Git。
2. 首个管理员的**明文密码不进入仓库、迁移文件、日志或 CI Artifact**。
3. `SJG_ADMIN_PASSWORD_HASH` 必须是 BCrypt cost=12 哈希；后端 `PasswordEncoder` 与此保持一致。
4. `sjg_bootstrap` 仅用于 PostgreSQL 容器初始化，`sjg_migration` 仅用于 Flyway；API 使用 `sjg_api_runtime`，审计写入使用 `sjg_audit_writer`。
5. 生产环境必须设置 `SJG_SECURITY_AUDIT_MODE=fail-closed`；`fail-open` 只用于本地开发或明确批准的降级窗口。
6. 所有已执行 Flyway 迁移只读。任何修复都必须新增迁移并使用大于当前全局最大值的唯一版本号。

## 2. 环境变量分组

| 分组 | 关键变量 | 用途 |
|---|---|---|
| PostgreSQL 启动 | `POSTGRES_*` | 创建本地数据库容器 |
| 迁移 | `SJG_BOOTSTRAP_DB_*`、`SJG_MIGRATION_DB_*`、三个 migration URL | 创建数据库、角色、表、约束与种子 |
| API 运行 | `SJG_API_DB_*` | 最小权限访问 `sjg_oms` |
| 审计 | `SJG_AUDIT_DB_*`、`SJG_SECURITY_AUDIT_MODE` | 安全事件写入与失败策略 |
| 会话 | `REDIS_*` | opaque access/refresh token 会话存储 |
| 租户 | `SJG_TENANT_ID/CODE/NAME` | 受控租户种子 |
| 首个管理员 | `SJG_ADMIN_LOGIN_NAME/PASSWORD_HASH/EMPLOYEE_NO` | 打破无账号、无注册入口的冷启动死锁 |

`SJG_ADMIN_LOGIN_NAME` 默认可保留为 `admin`，`SJG_ADMIN_EMPLOYEE_NO` 默认可保留为 `S8020-E001`；密码哈希必须自行生成。

## 3. 生成首个管理员哈希

```bash
python3 -m pip install bcrypt
python3 -c "import bcrypt; print(bcrypt.hashpw(b'<临时密码>', bcrypt.gensalt(rounds=12)).decode())"
```

将输出完整复制到：

```dotenv
SJG_ADMIN_PASSWORD_HASH='<输出的 BCrypt 哈希>'
```

必须保留单引号，避免 shell 将哈希中的 `$` 当作变量展开。请在密码管理器中临时保存对应明文，完成首次登录与改密后删除临时记录。

## 4. 环境校验

```bash
cp .env.example .env
# 编辑 .env 后：
bash scripts/dev/check-env.sh
```

校验内容包括：

- 所有 `__SET_LOCAL_*__` 占位符是否已替换；
- 必填变量是否缺失或为空；
- tenant ID 是否为 UUID；
- 管理员登录名、员工编号、BCrypt cost 是否合法；
- JDBC URL、端口与审计模式是否合法；
- 本地 bootstrap 密码和审计 writer 密码是否对应；
- 运行角色是否仍遵循最小权限命名。

失败时脚本一次性列出全部变量名并返回非零退出码，不会打印变量值。

## 5. 启动基础设施

仅验证登录闭环时：

```bash
docker compose --env-file .env -f docker/compose/docker-compose.dev.yml up -d postgres redis
```

完整本地依赖：

```bash
docker compose --env-file .env -f docker/compose/docker-compose.dev.yml up -d
```

健康检查：

```bash
docker compose --env-file .env -f docker/compose/docker-compose.dev.yml ps
docker compose --env-file .env -f docker/compose/docker-compose.dev.yml logs --tail=100 postgres redis
```

若使用过旧变量或旧卷，先确认数据是否需要保留。只有在明确允许丢弃本地数据时才执行：

```bash
docker compose --env-file .env -f docker/compose/docker-compose.dev.yml down -v
```

## 6. 执行迁移并核验首个管理员

```bash
set -a
source .env
set +a
bash scripts/database/migrate.sh
```

迁移器会依次：

1. 执行 cluster 迁移，创建数据库与最小权限角色；
2. 对 `sjg_oms`、`sjg_audit`、`sjg_dw` 执行 base + overlay；
3. 执行 `validate()`；
4. 再次执行 `migrate()`，断言没有新增迁移，验证重复执行稳定性。

首个管理员核验：

```bash
docker compose --env-file .env -f docker/compose/docker-compose.dev.yml exec -T postgres \
  psql -U "$POSTGRES_USER" -d sjg_oms \
  -c "SELECT login_name,status,mfa_level FROM iam.user_account WHERE login_name='${SJG_ADMIN_LOGIN_NAME}';"
```

期望：一条 `ACTIVE` 账号，`mfa_level=0`。还可核验 ACTIVE 任职和最小会话权限：

```bash
docker compose --env-file .env -f docker/compose/docker-compose.dev.yml exec -T postgres \
  psql -U "$POSTGRES_USER" -d sjg_oms -c "
    SELECT a.login_name, i.identity_name, ep.status, p.permission_code
      FROM iam.user_account a
      JOIN iam.user_identity i ON i.tenant_id=a.tenant_id AND i.user_id=a.id AND NOT i.is_deleted
      JOIN org.employee_position ep ON ep.tenant_id=i.tenant_id AND ep.employee_id=i.employee_id
       AND ep.org_id=i.org_id AND ep.position_id=i.position_id AND NOT ep.is_deleted
      JOIN iam.user_role ur ON ur.tenant_id=a.tenant_id AND ur.user_id=a.id
       AND (ur.identity_id IS NULL OR ur.identity_id=i.id) AND NOT ur.is_deleted
      JOIN iam.role_permission rp ON rp.tenant_id=ur.tenant_id AND rp.role_id=ur.role_id AND NOT rp.is_deleted
      JOIN iam.permission p ON p.tenant_id=rp.tenant_id AND p.id=rp.permission_id AND NOT p.is_deleted
     WHERE a.login_name='${SJG_ADMIN_LOGIN_NAME}'
     ORDER BY p.permission_code;"
```

## 7. 启动 API

```bash
bash scripts/dev/api-run.sh
```

脚本先调用 `check-env.sh`，随后通过 `set -a; source .env; set +a` 将变量传给 Spring Boot。日志只打印已加载的变量组名称，不打印值。

等待健康检查：

```bash
until curl -fsS http://127.0.0.1:8080/actuator/health; do sleep 2; done
```

## 8. API 登录冒烟

正确密码：

```bash
curl -i -X POST http://127.0.0.1:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d "{\"tenantCode\":\"${SJG_TENANT_CODE}\",\"loginName\":\"${SJG_ADMIN_LOGIN_NAME}\",\"password\":\"<临时密码>\",\"mfaCode\":null}"
```

期望：HTTP 200，并返回 `accessToken` 与 `refreshToken`。

错误密码：

```bash
curl -i -X POST http://127.0.0.1:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d "{\"tenantCode\":\"${SJG_TENANT_CODE}\",\"loginName\":\"${SJG_ADMIN_LOGIN_NAME}\",\"password\":\"definitely-wrong\",\"mfaCode\":null}"
```

期望：HTTP 401，`code=authentication_rejected`，响应包含 `requestId`；审计库应出现 `LOGIN_REJECTED`。

拿到 access token 后：

```bash
curl -i http://127.0.0.1:8080/api/v1/session \
  -H "Authorization: Bearer <accessToken>"
```

期望：HTTP 200，证明 `platform.session.read` 已生效。

## 9. 启动前端

```bash
cd technical-platform/web
corepack enable
corepack prepare pnpm@10.34.0 --activate
pnpm install --frozen-lockfile
pnpm dev:work
```

另一个终端：

```bash
cd technical-platform/web
pnpm dev:tech
```

- 工作端：`http://127.0.0.1:5173/work.html`
- 技术端：`http://127.0.0.1:5175/tech.html`

## 10. 登录失败速查表

| HTTP / code | 用户提示 | 运维检查 |
|---|---|---|
| 401 `authentication_rejected` | 租户、账号、密码、MFA 或 ACTIVE 任职无效 | 核对租户种子、账号状态、身份有效期、任职状态；使用 `requestId` 查日志/审计 |
| 403 | 当前身份没有目标能力 | 核对 `user_role → role_permission → permission` 与数据范围 |
| 503 `session_store_unavailable` | 会话存储不可用 | 检查 Redis 容器、`REDIS_HOST/PORT/PASSWORD` 与网络 |
| 503 `security_audit_unavailable` | 安全审计不可用 | 生产 fail-closed 下检查 `sjg_audit`、writer 账号和连接池 |
| 502/504、前端连接超时 | API 未启动或反向代理不可达 | 检查 `api-run.sh`、8080 端口、Vite proxy、`.env` 是否加载 |
| 500 或其他 code | 未分类服务端故障 | 保留页面显示的 `requestId`，查看 API 日志；不得只截取通用文案 |

## 11. 审计降级模式

### 本地开发：fail-open

```dotenv
SJG_SECURITY_AUDIT_MODE=fail-open
```

审计库不可用时，登录安全事件写入失败会记录 WARN 与兜底日志，登录主链路继续；Redis 仍是会话签发的硬依赖。

### 生产：fail-closed

```dotenv
SJG_SECURITY_AUDIT_MODE=fail-closed
```

审计库不可用时拒绝登录并返回 `security_audit_unavailable`。生产部署必须在 ADR/部署记录中确认该模式。

## 12. 回滚

| 整改项 | 回滚方法 | 数据影响 |
|---|---|---|
| 根目录探测 | 恢复 Java 判定逻辑 | 无数据影响，但会重新造成冷启动失败，不建议 |
| `V9002` 管理员迁移 | 已执行迁移不得删除或修改；通过新增迁移禁用账号、撤销角色/任职 | 保留审计可追溯性 |
| `.env` / 启动脚本 | 回退文档或脚本提交 | 不修改数据库 |
| CI 冷启动门禁 | 回退 workflow 提交 | 不修改运行环境，但会失去防复发能力 |
| 审计模式 | 通过环境变量切换并重启 API | 不改表结构；必须记录生产决策 |

## 13. Definition of Done

一次完整验收至少包括：

```bash
bash scripts/dev/check-env.sh
bash scripts/database/migrate.sh
./mvnw -B -ntp spotless:check
./mvnw -B -ntp -pl technical-platform/backend/apps/api,technical-platform/backend/apps/worker -am test
cd technical-platform/web
pnpm typecheck && pnpm lint && pnpm quality:duplicates && pnpm quality:deadcode && pnpm test && pnpm build
```

并人工确认：正确登录 200、错误密码 401、带 token 的 `/api/v1/session` 200、工作端与技术端均可登录跳转、Redis/审计库故障提示可区分、CI 的 `cold-start-smoke` job 通过。
