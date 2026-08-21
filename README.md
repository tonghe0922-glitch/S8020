# 上金谷运营管理平台（S8020）

S8020 是上金谷运营管理平台的新版本唯一代码仓库。系统只有两个运行端口：

- **工作端 `work`**：员工本人操作与中心管理能力，根据当前身份、岗位、组织、权限和数据范围投影。
- **技术端 `tech`**：模块、权限、流程参数、运行监控、审计与补偿；不代表业务超级管理员。

## 技术栈

- Vue 3.5、TypeScript 5.9、Vite 8、Pinia、Vue Router
- Java 21、Spring Boot 3.5、Spring MVC、Spring JDBC
- PostgreSQL 16、Flyway、Redis、Docker

## 快速启动

```bash
cp .env.example .env
docker compose --env-file .env -f docker/compose/docker-compose.dev.yml up -d
```

前端开发：

```bash
cd technical-platform/web
npm install --global pnpm@10.34.0
pnpm install --frozen-lockfile
pnpm dev:work
# 另一个终端：pnpm dev:tech
```

## 全量质量检查

```bash
bash ./mvnw -B -ntp -pl technical-platform/backend/apps/api,technical-platform/backend/apps/worker -am test
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
