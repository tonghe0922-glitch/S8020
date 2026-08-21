-- P007 frozen engineering-contract correction after checkpoint review.
SET ROLE sjg_owner;

UPDATE iam.permission
   SET permission_code='p007.schedule.change', permission_name='P007排班/换班变更', action_code='CHANGE', updated_at=now()
 WHERE tenant_id='${sjg_tenant_id}'::uuid AND permission_code='p007.shift.submit' AND NOT is_deleted
   AND NOT EXISTS (SELECT 1 FROM iam.permission p2 WHERE p2.tenant_id='${sjg_tenant_id}'::uuid AND p2.permission_code='p007.schedule.change' AND NOT p2.is_deleted);

UPDATE iam.permission
   SET permission_code='p007.schedule.review', permission_name='P007排班变更审批', action_code='REVIEW', updated_at=now()
 WHERE tenant_id='${sjg_tenant_id}'::uuid AND permission_code='p007.shift.review' AND NOT is_deleted
   AND NOT EXISTS (SELECT 1 FROM iam.permission p2 WHERE p2.tenant_id='${sjg_tenant_id}'::uuid AND p2.permission_code='p007.schedule.review' AND NOT p2.is_deleted);

UPDATE iam.permission SET is_deleted=true,deleted_at=now(),updated_at=now()
 WHERE tenant_id='${sjg_tenant_id}'::uuid AND permission_code IN ('p007.schedule.create','p007.shift.confirm') AND NOT is_deleted;

INSERT INTO iam.permission(id,tenant_id,permission_code,permission_name,resource_type,action_code,risk_level,created_at,updated_at,is_deleted)
SELECT gen_random_uuid(),'${sjg_tenant_id}'::uuid,v.code,v.name,'PROCESS',v.action,'NORMAL',now(),now(),false
FROM (VALUES
  ('p007.schedule.read','P007排班读取','READ'),
  ('p007.schedule.manage','P007排班管理','MANAGE'),
  ('p007.schedule.change','P007排班/换班变更','CHANGE'),
  ('p007.schedule.review','P007排班变更审批','REVIEW'),
  ('p007.schedule.monitor','P007排班运行监控','MONITOR')
) AS v(code,name,action)
WHERE NOT EXISTS (SELECT 1 FROM iam.permission p WHERE p.tenant_id='${sjg_tenant_id}'::uuid AND p.permission_code=v.code AND NOT p.is_deleted);

RESET ROLE;
