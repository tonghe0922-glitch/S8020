# 上金谷运营管理平台（S8020）

S8020 是上金谷运营管理平台的新版本唯一代码仓库。系统只有两个运行端口：

- **工作端 `work`**：员工本人操作与中心管理能力，根据当前身份、岗位、组织、权限和数据范围投影。
- **技术端 `tech`**：模块、权限、流程参数、运行监控、审计与补偿；不代表业务超级管理员。

## 技术栈

- Vue 3.5、TypeScript 5.9、Vite 8、Pinia、Vue Router
- Java 21、Spring Boot 3.5、Spring MVC、Spring JDBC
- PostgreSQL 16、Flyway、Redis、Docker

## 从零启动并登录

前置要求：Java 21、Docker Compose、Node.js 22.12+、Python 3、Bash。首次安装前端依赖时还需要 pnpm 10.34.0。

### 1. 准备并校验环境变量

```bash
cp .env.example .env
```

编辑 `.env`，替换**全部** `__SET_LOCAL_*__` 占位符。首个管理员密码只允许以 BCrypt cost=12 哈希写入环境变量，明文密码不得进入 Git：

```bash
python3 -m pip install bcrypt
python3 -c "import bcrypt; print(bcrypt.hashpw(b'<临时密码>', bcrypt.gensalt(rounds=12)).decode())"
```

把输出写入 `SJG_ADMIN_PASSWORD_HASH`，再运行：

```bash
bash scripts/dev/check-env.sh
```

脚本必须显示 `PASS` 才能继续；它只报告变量名，不打印密码或哈希值。

### 2. 启动 PostgreSQL 与 Redis

```bash
docker compose --env-file .env -f docker/compose/docker-compose.dev.yml up -d postgres redis
docker compose --env-file .env -f docker/compose/docker-compose.dev.yml ps
```

确认 `postgres` 与 `redis` 均为 healthy/running。

### 3. 执行真实 Flyway 迁移

```bash
set -a
source .env
set +a
bash scripts/database/migrate.sh
```

成功标志：

```text
PHASE-03 database migration completed for sjg_oms/sjg_audit/sjg_dw
```

该迁移会使用环境变量创建受控的首个管理员完整数据图：组织、岗位、员工、ACTIVE 任职、账号、身份、数据范围、角色与最小会话权限。已执行迁移不得修改；后续结构变化只能新增版本号全局唯一的迁移。

可选核验：

```bash
docker compose --env-file .env -f docker/compose/docker-compose.dev.yml exec -T postgres \
  psql -U "$POSTGRES_USER" -d sjg_oms \
  -c "SELECT login_name,status,mfa_level FROM iam.user_account;"
```

### 4. 启动 API

```bash
bash scripts/dev/api-run.sh
```

该脚本会先重新校验 `.env`，再显式加载 API 数据库、审计库与 Redis 配置。健康检查地址：`http://127.0.0.1:8080/actuator/health`。

### 5. 启动两个前端

在新终端执行：

```bash
cd technical-platform/web
corepack enable
corepack prepare pnpm@10.34.0 --activate
pnpm install --frozen-lockfile
pnpm dev:work
```

再开一个终端：

```bash
cd technical-platform/web
pnpm dev:tech
```

访问：

- 工作端：`http://127.0.0.1:5173/work.html`
- 技术端：`http://127.0.0.1:5175/tech.html`

登录时使用 `.env` 中的 `SJG_TENANT_CODE`、`SJG_ADMIN_LOGIN_NAME`，以及生成哈希时对应的**临时明文密码**。首次登录后立即通过现有个人资料变更流程修改密码。

更完整的部署检查、curl 冒烟、故障码与回滚说明见 [`docs/BOOTSTRAP.md`](docs/BOOTSTRAP.md)。

## 全量质量检查

```bash
./mvnw -B -ntp spotless:check
./mvnw -B -ntp -pl technical-platform/backend/apps/api,technical-platform/backend/apps/worker -am test
cd technical-platform/web
pnpm typecheck
pnpm lint
pnpm quality:duplicates
pnpm quality:deadcode
pnpm quality:ports
pnpm test
pnpm build
pnpm test:e2e
```

## 开发方式

后续按“静态原型 → 模块规格 → 纵向小闭环 → 自动测试 → 浏览器验收”推进。当前模块需求以 `docs/modules/<module>/MODULE_SPEC.md` 为业务事实源，不再按 PHASE 总进度施工。
