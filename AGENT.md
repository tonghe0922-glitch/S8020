# S8020 AI 工程执行规则

## 1. 项目边界

本系统只有 `work` 与 `tech` 两个运行端口。`work` 同时承载员工操作和中心管理能力；`tech` 负责配置、监控、审计与补偿，不自动拥有业务审批权或敏感数据读取权。

## 2. 权威顺序

现行法律与安全底线 > 用户最新明确决定 > 本文件 > 已批准 MODULE_SPEC > ADR > DESIGN > 当前代码与自动测试 > 历史原型。静态 HTML 只表达页面与交互目标，不直接决定权限、数据库、审批人或状态机。

## 3. 开发规则

1. 一次只推进一个模块内的一个可验收闭环。
2. 开工前读取本文件、DESIGN、ARCHITECTURE 和当前模块文档。
3. 必须复用现有认证、权限、审计、通知、文件和工作流能力。
4. 不得创建平行用户、权限、组织或状态事实源。
5. 前端不可提交任意目标状态；状态由服务端命令和状态机改变。
6. 技术端不可绕过业务授权直接把业务状态改为完成。
7. 已进入 `main` 或共享环境执行过的 Flyway 迁移不可修改；结构变化必须新增迁移。
8. 新 Flyway 版本必须大于同一逻辑数据库在 `flyway` 与 `flyway-overlays` 中的当前最大版本，并通过 `scripts/quality/flyway_versions.py`。
9. 禁止提交真实人员、薪资、身份证、健康、令牌、密码或密钥数据。
10. 禁止直接推送 `main`；通过分支、PR 和全部门禁合并。

## 4. 代码质量

- TypeScript 严格模式；ESLint 零警告；不得新增 `any`、`ts-ignore` 或未处理 Promise。
- Java 21；Spotless 使用固定版本 Palantir Java Format 对**全部模块、全部 `src/**/*.java`**执行，不使用 ratchet 跳过存量。
- 所有 Spring `@Component`、`@Service`、`@Repository`、`@Controller`、`@RestController`、`@Configuration` 与 Advice 类不得声明为 `final`；`@Transactional` 方法不得为 `final`，以保证 AOP 代理可创建。
- Controller 保持薄层；P011–P016 的创建、读取投影、动作、幂等与审计必须复用 `Phase11ApiSupport.Endpoint`，不得复制本地 `project/manageAllowed/anyManageData` 模板。
- 新增或修改代码不得提高重复率，不得留下无引用代码；业务状态机差异超过 40% 时保留独立服务，但复用事务、审计、幂等和工作流基础能力。
- SQL 使用参数绑定；权限必须在服务端执行；菜单隐藏不构成安全边界。
- 所有错误应有稳定错误码与 requestId；日志不得包含明文密码、哈希、令牌、连接口令或敏感业务数据。
- 功能修改与全量格式化应尽量分提交；格式化提交必须由 `spotless:apply` 产生，禁止手工形成第二套风格。

## 5. 必跑门禁

```bash
python3 scripts/quality/spring_proxyability.py
python3 scripts/quality/phase11_duplication_guard.py
python3 scripts/quality/flyway_versions.py
python3 scripts/quality/md_linkrot.py
bash ./mvnw -B -ntp spotless:check
bash ./mvnw -B -ntp test
cd technical-platform/web
pnpm typecheck
pnpm lint
pnpm quality:duplicates
pnpm quality:deadcode
pnpm test
pnpm build
```

## 6. 完成定义

页面、API、数据库、权限、审计、测试、构建、文档、真实冷启动和人工体验全部通过，模块闭环才可标记完成。交付必须列出修改文件、测试命令、测试结果、数据库/API/权限影响和回滚方式。
