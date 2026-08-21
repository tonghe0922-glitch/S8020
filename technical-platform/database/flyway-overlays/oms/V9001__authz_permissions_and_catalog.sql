-- ADR-006 permissions and initial catalog. Runtime grant SQL is intentionally not switched here.
SET ROLE sjg_owner;

INSERT INTO iam.permission(id,tenant_id,permission_code,permission_name,resource_type,action_code,risk_level,created_at,updated_at,is_deleted)
SELECT gen_random_uuid(),'${sjg_tenant_id}'::uuid,v.code,v.name,'AUTHZ_CONFIG',v.action,v.risk,now(),now(),false
FROM (VALUES
  ('authz.module.read','模块目录查看','READ','NORMAL'),
  ('authz.module.manage','模块定义维护','MANAGE','HIGH'),
  ('authz.org.module.manage','组织模块开关','MANAGE','HIGH'),
  ('authz.position.role.manage','岗位角色配置','MANAGE','HIGH'),
  ('authz.config.preview','权限配置预览','PREVIEW','NORMAL'),
  ('authz.config.manage','权限配置总开关','MANAGE','HIGH')
) AS v(code,name,action,risk)
WHERE NOT EXISTS (SELECT 1 FROM iam.permission p WHERE p.tenant_id='${sjg_tenant_id}'::uuid AND p.permission_code=v.code AND NOT p.is_deleted);

INSERT INTO iam.module(id,tenant_id,module_code,module_name,module_group,process_codes,sort_no,icon,enabled,remark,created_at,updated_at,is_deleted)
SELECT gen_random_uuid(),'${sjg_tenant_id}'::uuid,v.code,v.name,v.group_name,v.process_codes::jsonb,v.sort_no,v.icon,true,'ADR-006 首批模块目录；仅用于配置和预览',now(),now(),false
FROM (VALUES
  ('PLATFORM_SECURITY','身份与权限','平台公共','["P001","P002","P003","P004"]',10,'shield'),
  ('COLLABORATION','通知与会议','平台公共','["P005","P006"]',20,'messages'),
  ('ATTENDANCE','排班考勤','人力资源','["P007","P008","P009"]',30,'calendar'),
  ('LEARNING','学习与资质','人力资源','["P010"]',40,'learning'),
  ('PERFORMANCE','绩效管理','绩效成长福利','["P011"]',50,'chart'),
  ('PROMOTION','晋升任职','绩效成长福利','["P012"]',60,'promotion'),
  ('RECOGNITION','奖励认可','绩效成长福利','["P013"]',70,'award'),
  ('DISCIPLINE','纪律申诉','绩效成长福利','["P014"]',80,'balance'),
  ('GROWTH_POINTS','成长积分','绩效成长福利','["P015"]',90,'points'),
  ('WELFARE','福利关怀','绩效成长福利','["P016"]',100,'heart')
) AS v(code,name,group_name,process_codes,sort_no,icon)
WHERE NOT EXISTS (SELECT 1 FROM iam.module m WHERE m.tenant_id='${sjg_tenant_id}'::uuid AND m.module_code=v.code AND NOT m.is_deleted);

INSERT INTO iam.module_permission(id,tenant_id,module_id,permission_id,capability_type,created_at,updated_at,is_deleted)
SELECT gen_random_uuid(),p.tenant_id,m.id,p.id,
  CASE
    WHEN p.action_code IN ('READ','SELF','PREVIEW','MONITOR') THEN 'VIEW'
    WHEN p.action_code IN ('APPROVE','REVIEW','CALIBRATE','APPEAL','CONFIRM','ACCEPT') THEN 'APPROVE'
    WHEN p.action_code IN ('CONFIG','MANAGE','IMPACT','RECONCILE') AND p.risk_level IN ('HIGH','CRITICAL') THEN 'ADMIN'
    ELSE 'OPERATE'
  END,
  now(),now(),false
FROM iam.permission p
JOIN iam.module m ON m.tenant_id=p.tenant_id AND NOT m.is_deleted
  AND m.module_code=CASE
    WHEN p.permission_code LIKE 'p001.%' OR p.permission_code LIKE 'p002.%' OR p.permission_code LIKE 'p003.%' OR p.permission_code LIKE 'p004.%' OR p.permission_code LIKE 'authz.%' THEN 'PLATFORM_SECURITY'
    WHEN p.permission_code LIKE 'p005.%' OR p.permission_code LIKE 'p006.%' THEN 'COLLABORATION'
    WHEN p.permission_code LIKE 'p007.%' OR p.permission_code LIKE 'p008.%' OR p.permission_code LIKE 'p009.%' THEN 'ATTENDANCE'
    WHEN p.permission_code LIKE 'p010.%' THEN 'LEARNING'
    WHEN p.permission_code LIKE 'p011.%' THEN 'PERFORMANCE'
    WHEN p.permission_code LIKE 'p012.%' THEN 'PROMOTION'
    WHEN p.permission_code LIKE 'p013.%' THEN 'RECOGNITION'
    WHEN p.permission_code LIKE 'p014.%' THEN 'DISCIPLINE'
    WHEN p.permission_code LIKE 'p015.%' THEN 'GROWTH_POINTS'
    WHEN p.permission_code LIKE 'p016.%' THEN 'WELFARE'
    ELSE NULL
  END
WHERE p.tenant_id='${sjg_tenant_id}'::uuid AND NOT p.is_deleted
  AND NOT EXISTS (SELECT 1 FROM iam.module_permission mp WHERE mp.tenant_id=p.tenant_id AND mp.module_id=m.id AND mp.permission_id=p.id AND NOT mp.is_deleted);

-- Deliberately do not auto-grant the new HIGH-risk configuration permissions.
-- Existing P002 permission workflows remain the only bootstrap path for assigning them.
RESET ROLE;
