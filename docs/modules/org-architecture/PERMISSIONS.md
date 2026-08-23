# 组织架构权限矩阵

| 权限码 | 动作 | 工作端 | 技术端 | 数据范围 | 字段范围 | 审计 |
|---|---|---|---|---|---|---|
| `org.architecture.view` | 查看正式组织树 | 可 | 可 | 当前租户 | 非敏感组织字段 | `ORG_TREE_READ` |
| `org.architecture.edit` | 创建、编辑、提交、废弃自己的草稿 | 可 | 否 | 当前租户；草稿创建人约束 | 组织字段 | 草稿前后快照 |
| `org.architecture.review` | 查看待审批、通过或驳回 | 可 | 否 | 当前租户；禁止自审 | 审批意见 | `ORG_DRAFT_REVIEW` |
| `org.architecture.publish` | 发布已审批草稿 | 可 | 否 | 当前租户；基线版本约束 | 正式组织全量快照 | `ORG_DRAFT_PUBLISH` |
| `org.architecture.manage` | 正式节点增改停删、查看和发布兜底 | 可 | 可 | 当前租户 | 正式组织字段 | 节点配置前后快照 |
| 已认证会话 | 查看企业架构与成员通讯录 | 可 | 否 | 当前租户 | 姓名、工号、组织、岗位；无手机号/证件号 | `ORG_DIRECTORY_READ` |

授权委派继续复用 `iam.org_module` 与 `iam.position_role`：模块代码 `ORG_ARCHITECTURE`，组织改名或调级不改变 `org_id`，因此授权不会因名称变化失效。
