-- V9004: complete organization directory remediation.
-- Authorized source: 中心事务-公司架构管理（超级管理端） (8).html
-- Source SHA-256: a6c8194ad36c76d71551cfd6238e733934cc0c6666e7704bb7c32ad1cf15a35b
-- Invariants: 59 S06 organization nodes, 205 unique real employees, 217 active appointments.
-- Unknown hire dates remain NULL; appointment effective date is the controlled import date.
SET ROLE sjg_owner;

CREATE TABLE IF NOT EXISTS org.organization_code_sequence (
    tenant_id uuid NOT NULL PRIMARY KEY,
    next_value integer NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL,
    CONSTRAINT ck_org_organization_code_sequence_next CHECK (next_value > 0)
);
COMMENT ON TABLE org.organization_code_sequence IS '组织编号租户序列｜服务端原子分配 S06-ORG-xxx 编号';

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='fk_org_organization_code_sequence_tenant') THEN
        ALTER TABLE org.organization_code_sequence
            ADD CONSTRAINT fk_org_organization_code_sequence_tenant
            FOREIGN KEY (tenant_id) REFERENCES core.tenant(id) ON DELETE RESTRICT NOT VALID;
        ALTER TABLE org.organization_code_sequence
            VALIDATE CONSTRAINT fk_org_organization_code_sequence_tenant;
    END IF;
END
$$;

ALTER TABLE org.organization_code_sequence ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS p_tenant_org_organization_code_sequence ON org.organization_code_sequence;
CREATE POLICY p_tenant_org_organization_code_sequence ON org.organization_code_sequence
    USING (tenant_id=current_setting('app.tenant_id',true)::uuid)
    WITH CHECK (tenant_id=current_setting('app.tenant_id',true)::uuid);
GRANT SELECT,INSERT,UPDATE ON org.organization_code_sequence TO sjg_api_runtime;

CREATE TEMP TABLE seed_v9004_org (
    org_code text PRIMARY KEY, org_name text NOT NULL, org_type text NOT NULL, parent_code text,
    path text NOT NULL, depth integer NOT NULL, sort_no integer NOT NULL, headcount_plan integer NOT NULL,
    description text, manager_employee_no text
) ON COMMIT DROP;
INSERT INTO seed_v9004_org VALUES
    ('S06-ORG-001','股东会','MANAGEMENT',NULL,'s06_org_001',0,1,0,NULL,NULL),
    ('S06-ORG-002','党支部','MANAGEMENT','S06-ORG-001','s06_org_001.s06_org_002',1,2,0,NULL,NULL),
    ('S06-ORG-003','工会','MANAGEMENT','S06-ORG-002','s06_org_001.s06_org_002.s06_org_003',2,3,0,NULL,'S06-E005'),
    ('S06-ORG-004','党建办公室','MANAGEMENT','S06-ORG-002','s06_org_001.s06_org_002.s06_org_004',2,4,0,NULL,'S06-E004'),
    ('S06-ORG-005','董事会','MANAGEMENT','S06-ORG-001','s06_org_001.s06_org_005',1,5,0,NULL,NULL),
    ('S06-ORG-006','决策委','MANAGEMENT','S06-ORG-005','s06_org_001.s06_org_005.s06_org_006',2,6,0,NULL,NULL),
    ('S06-ORG-007','董办','MANAGEMENT','S06-ORG-005','s06_org_001.s06_org_005.s06_org_007',2,7,0,NULL,'S06-E001'),
    ('S06-ORG-008','董事长直管独立单位','DIRECT','S06-ORG-005','s06_org_001.s06_org_005.s06_org_008',2,8,0,NULL,NULL),
    ('S06-ORG-009','工程建设指挥部','DIRECT','S06-ORG-008','s06_org_001.s06_org_005.s06_org_008.s06_org_009',3,9,0,NULL,NULL),
    ('S06-ORG-010','本部管理岗','DEPARTMENT','S06-ORG-009','s06_org_001.s06_org_005.s06_org_008.s06_org_009.s06_org_010',4,10,2,NULL,'S06-E007'),
    ('S06-ORG-011','技术支持中心','CENTER','S06-ORG-009','s06_org_001.s06_org_005.s06_org_008.s06_org_009.s06_org_011',4,11,4,NULL,'S06-E007'),
    ('S06-ORG-012','生产管理中心','CENTER','S06-ORG-009','s06_org_001.s06_org_005.s06_org_008.s06_org_009.s06_org_012',4,12,6,NULL,'S06-E009'),
    ('S06-ORG-013','维修管理中心','CENTER','S06-ORG-009','s06_org_001.s06_org_005.s06_org_008.s06_org_009.s06_org_013',4,13,7,NULL,'S06-E010'),
    ('S06-ORG-014','宁波善财商贸有限公司','COMPANY','S06-ORG-008','s06_org_001.s06_org_005.s06_org_008.s06_org_014',3,14,0,NULL,'S06-E027'),
    ('S06-ORG-015','财人中心','CENTER','S06-ORG-008','s06_org_001.s06_org_005.s06_org_008.s06_org_015',3,15,3,NULL,'S06-E011'),
    ('S06-ORG-016','财务部','DEPARTMENT','S06-ORG-015','s06_org_001.s06_org_005.s06_org_008.s06_org_015.s06_org_016',4,16,16,NULL,NULL),
    ('S06-ORG-017','档案管理部','DEPARTMENT','S06-ORG-015','s06_org_001.s06_org_005.s06_org_008.s06_org_015.s06_org_017',4,17,1,NULL,'S06-E045'),
    ('S06-ORG-018','采购部','DEPARTMENT','S06-ORG-015','s06_org_001.s06_org_005.s06_org_008.s06_org_015.s06_org_018',4,18,1,NULL,'S06-E046'),
    ('S06-ORG-019','人力资源部','DEPARTMENT','S06-ORG-015','s06_org_001.s06_org_005.s06_org_008.s06_org_015.s06_org_019',4,19,2,NULL,'S06-E012'),
    ('S06-ORG-020','票务服务部','DEPARTMENT','S06-ORG-015','s06_org_001.s06_org_005.s06_org_008.s06_org_015.s06_org_020',4,20,4,NULL,NULL),
    ('S06-ORG-021','社会资源中心','CENTER','S06-ORG-008','s06_org_001.s06_org_005.s06_org_008.s06_org_021',3,21,0,NULL,NULL),
    ('S06-ORG-022','范蠡研究会','DEPARTMENT','S06-ORG-021','s06_org_001.s06_org_005.s06_org_008.s06_org_021.s06_org_022',4,22,0,'暂无人员',NULL),
    ('S06-ORG-023','海外联络中心','CENTER','S06-ORG-008','s06_org_001.s06_org_005.s06_org_008.s06_org_023',3,23,0,'暂无人员',NULL),
    ('S06-ORG-024','市场中心','CENTER','S06-ORG-008','s06_org_001.s06_org_005.s06_org_008.s06_org_024',3,24,0,NULL,NULL),
    ('S06-ORG-025','市场部','DEPARTMENT','S06-ORG-024','s06_org_001.s06_org_005.s06_org_008.s06_org_024.s06_org_025',4,25,0,'暂无人员',NULL),
    ('S06-ORG-026','教育部','DEPARTMENT','S06-ORG-024','s06_org_001.s06_org_005.s06_org_008.s06_org_024.s06_org_026',4,26,7,NULL,'S06-E006'),
    ('S06-ORG-027','文化中心','CENTER','S06-ORG-008','s06_org_001.s06_org_005.s06_org_008.s06_org_027',3,27,2,NULL,'S06-E058'),
    ('S06-ORG-028','商学院','DEPARTMENT','S06-ORG-027','s06_org_001.s06_org_005.s06_org_008.s06_org_027.s06_org_028',4,28,2,NULL,'S06-E058'),
    ('S06-ORG-029','传统文化部','DEPARTMENT','S06-ORG-027','s06_org_001.s06_org_005.s06_org_008.s06_org_027.s06_org_029',4,29,0,'暂无人员',NULL),
    ('S06-ORG-030','总裁线','MANAGEMENT','S06-ORG-005','s06_org_001.s06_org_005.s06_org_030',2,30,0,NULL,NULL),
    ('S06-ORG-031','总裁办','MANAGEMENT','S06-ORG-030','s06_org_001.s06_org_005.s06_org_030.s06_org_031',3,31,0,NULL,'S06-E061'),
    ('S06-ORG-032','行政中心','CENTER','S06-ORG-030','s06_org_001.s06_org_005.s06_org_030.s06_org_032',3,32,1,NULL,'S06-E012'),
    ('S06-ORG-033','办公室','DEPARTMENT','S06-ORG-032','s06_org_001.s06_org_005.s06_org_030.s06_org_032.s06_org_033',4,33,2,NULL,'S06-E006'),
    ('S06-ORG-034','资产部','DEPARTMENT','S06-ORG-032','s06_org_001.s06_org_005.s06_org_030.s06_org_032.s06_org_034',4,34,2,NULL,'S06-E065'),
    ('S06-ORG-035','后勤部','DEPARTMENT','S06-ORG-032','s06_org_001.s06_org_005.s06_org_030.s06_org_032.s06_org_035',4,35,9,NULL,'S06-E067'),
    ('S06-ORG-036','餐饮部','DEPARTMENT','S06-ORG-032','s06_org_001.s06_org_005.s06_org_030.s06_org_032.s06_org_036',4,36,1,NULL,'S06-E076'),
    ('S06-ORG-037','物业中心','CENTER','S06-ORG-030','s06_org_001.s06_org_005.s06_org_030.s06_org_037',3,37,1,NULL,'S06-E077'),
    ('S06-ORG-038','安保部','DEPARTMENT','S06-ORG-037','s06_org_001.s06_org_005.s06_org_030.s06_org_037.s06_org_038',4,38,2,NULL,'S06-E078'),
    ('S06-ORG-039','环卫部','DEPARTMENT','S06-ORG-037','s06_org_001.s06_org_005.s06_org_030.s06_org_037.s06_org_039',4,39,2,NULL,'S06-E080'),
    ('S06-ORG-040','商管部','DEPARTMENT','S06-ORG-037','s06_org_001.s06_org_005.s06_org_030.s06_org_037.s06_org_040',4,40,8,NULL,'S06-E082'),
    ('S06-ORG-041','演艺中心','CENTER','S06-ORG-030','s06_org_001.s06_org_005.s06_org_030.s06_org_041',3,41,2,NULL,'S06-E004'),
    ('S06-ORG-042','表演部','DEPARTMENT','S06-ORG-041','s06_org_001.s06_org_005.s06_org_030.s06_org_041.s06_org_042',4,42,62,NULL,NULL),
    ('S06-ORG-043','服化道','DEPARTMENT','S06-ORG-041','s06_org_001.s06_org_005.s06_org_030.s06_org_041.s06_org_043',4,43,2,NULL,NULL),
    ('S06-ORG-044','无人机飞行部','DEPARTMENT','S06-ORG-041','s06_org_001.s06_org_005.s06_org_030.s06_org_041.s06_org_044',4,44,3,NULL,'S06-E155'),
    ('S06-ORG-045','演艺技术部','DEPARTMENT','S06-ORG-041','s06_org_001.s06_org_005.s06_org_030.s06_org_041.s06_org_045',4,45,10,NULL,'S06-E158'),
    ('S06-ORG-046','网络中心','CENTER','S06-ORG-030','s06_org_001.s06_org_005.s06_org_030.s06_org_046',3,46,1,NULL,'S06-E168'),
    ('S06-ORG-047','网络技术部','DEPARTMENT','S06-ORG-046','s06_org_001.s06_org_005.s06_org_030.s06_org_046.s06_org_047',4,47,4,NULL,NULL),
    ('S06-ORG-048','系统维护部','DEPARTMENT','S06-ORG-046','s06_org_001.s06_org_005.s06_org_030.s06_org_046.s06_org_048',4,48,2,NULL,'S06-E173'),
    ('S06-ORG-049','研发部','DEPARTMENT','S06-ORG-046','s06_org_001.s06_org_005.s06_org_030.s06_org_046.s06_org_049',4,49,1,NULL,'S06-E175'),
    ('S06-ORG-050','企划中心','CENTER','S06-ORG-030','s06_org_001.s06_org_005.s06_org_030.s06_org_050',3,50,2,NULL,'S06-E062'),
    ('S06-ORG-051','企宣部','DEPARTMENT','S06-ORG-050','s06_org_001.s06_org_005.s06_org_030.s06_org_050.s06_org_051',4,51,3,NULL,NULL),
    ('S06-ORG-052','策划部','DEPARTMENT','S06-ORG-050','s06_org_001.s06_org_005.s06_org_030.s06_org_050.s06_org_052',4,52,3,NULL,NULL),
    ('S06-ORG-053','新媒体中心','CENTER','S06-ORG-030','s06_org_001.s06_org_005.s06_org_030.s06_org_053',3,53,1,NULL,'S06-E183'),
    ('S06-ORG-054','OTA部','DEPARTMENT','S06-ORG-053','s06_org_001.s06_org_005.s06_org_030.s06_org_053.s06_org_054',4,54,1,NULL,NULL),
    ('S06-ORG-055','抖音部','DEPARTMENT','S06-ORG-053','s06_org_001.s06_org_005.s06_org_030.s06_org_053.s06_org_055',4,55,1,NULL,NULL),
    ('S06-ORG-056','品牌部','DEPARTMENT','S06-ORG-053','s06_org_001.s06_org_005.s06_org_030.s06_org_053.s06_org_056',4,56,1,NULL,NULL),
    ('S06-ORG-057','运营中心','CENTER','S06-ORG-030','s06_org_001.s06_org_005.s06_org_030.s06_org_057',3,57,3,NULL,'S06-E187'),
    ('S06-ORG-058','接待部','DEPARTMENT','S06-ORG-057','s06_org_001.s06_org_005.s06_org_030.s06_org_057.s06_org_058',4,58,16,NULL,'S06-E190'),
    ('S06-ORG-059','监事会','MANAGEMENT','S06-ORG-001','s06_org_001.s06_org_059',1,59,0,NULL,'S06-E011');

CREATE TEMP TABLE seed_v9004_position (
    position_code text PRIMARY KEY, org_code text NOT NULL, position_name text NOT NULL
) ON COMMIT DROP;
INSERT INTO seed_v9004_position VALUES
    ('S06-POS-001','S06-ORG-003','工会主席'),
    ('S06-POS-002','S06-ORG-003','工会副主席'),
    ('S06-POS-003','S06-ORG-004','书记'),
    ('S06-POS-004','S06-ORG-007','董事长'),
    ('S06-POS-005','S06-ORG-007','董事长助理'),
    ('S06-POS-006','S06-ORG-010','常务副总指挥'),
    ('S06-POS-007','S06-ORG-010','副总指挥'),
    ('S06-POS-008','S06-ORG-011','总经理'),
    ('S06-POS-009','S06-ORG-011','成员'),
    ('S06-POS-010','S06-ORG-012','总经理'),
    ('S06-POS-011','S06-ORG-012','成员'),
    ('S06-POS-012','S06-ORG-013','总经理'),
    ('S06-POS-013','S06-ORG-013','经理'),
    ('S06-POS-014','S06-ORG-013','成员'),
    ('S06-POS-015','S06-ORG-014','总经理'),
    ('S06-POS-016','S06-ORG-015','总经理'),
    ('S06-POS-017','S06-ORG-015','副总经理'),
    ('S06-POS-018','S06-ORG-016','成员'),
    ('S06-POS-019','S06-ORG-017','经理'),
    ('S06-POS-020','S06-ORG-018','经理'),
    ('S06-POS-021','S06-ORG-019','经理'),
    ('S06-POS-022','S06-ORG-019','兼专员'),
    ('S06-POS-023','S06-ORG-020','成员'),
    ('S06-POS-024','S06-ORG-026','经理'),
    ('S06-POS-025','S06-ORG-026','成员'),
    ('S06-POS-026','S06-ORG-027','总经理'),
    ('S06-POS-027','S06-ORG-027','副总经理'),
    ('S06-POS-028','S06-ORG-028','院长'),
    ('S06-POS-029','S06-ORG-028','副院长'),
    ('S06-POS-030','S06-ORG-031','总裁'),
    ('S06-POS-031','S06-ORG-031','常务副总裁'),
    ('S06-POS-032','S06-ORG-031','总裁助理'),
    ('S06-POS-033','S06-ORG-031','司机'),
    ('S06-POS-034','S06-ORG-032','总经理'),
    ('S06-POS-035','S06-ORG-033','主任'),
    ('S06-POS-036','S06-ORG-033','副主任'),
    ('S06-POS-037','S06-ORG-034','经理'),
    ('S06-POS-038','S06-ORG-034','成员'),
    ('S06-POS-039','S06-ORG-035','经理'),
    ('S06-POS-040','S06-ORG-035','副经理'),
    ('S06-POS-041','S06-ORG-035','成员'),
    ('S06-POS-042','S06-ORG-036','经理'),
    ('S06-POS-043','S06-ORG-037','总经理'),
    ('S06-POS-044','S06-ORG-038','经理'),
    ('S06-POS-045','S06-ORG-038','成员'),
    ('S06-POS-046','S06-ORG-039','经理'),
    ('S06-POS-047','S06-ORG-039','成员'),
    ('S06-POS-048','S06-ORG-040','经理'),
    ('S06-POS-049','S06-ORG-040','成员'),
    ('S06-POS-050','S06-ORG-041','总经理'),
    ('S06-POS-051','S06-ORG-041','艺术总监'),
    ('S06-POS-052','S06-ORG-042','成员'),
    ('S06-POS-053','S06-ORG-043','成员'),
    ('S06-POS-054','S06-ORG-044','经理'),
    ('S06-POS-055','S06-ORG-044','成员'),
    ('S06-POS-056','S06-ORG-045','经理'),
    ('S06-POS-057','S06-ORG-045','成员'),
    ('S06-POS-058','S06-ORG-046','副总经理'),
    ('S06-POS-059','S06-ORG-047','成员'),
    ('S06-POS-060','S06-ORG-048','经理'),
    ('S06-POS-061','S06-ORG-048','成员'),
    ('S06-POS-062','S06-ORG-049','经理'),
    ('S06-POS-063','S06-ORG-050','总经理'),
    ('S06-POS-064','S06-ORG-050','副总经理'),
    ('S06-POS-065','S06-ORG-051','成员'),
    ('S06-POS-066','S06-ORG-052','成员'),
    ('S06-POS-067','S06-ORG-053','副总经理'),
    ('S06-POS-068','S06-ORG-054','成员'),
    ('S06-POS-069','S06-ORG-055','成员'),
    ('S06-POS-070','S06-ORG-056','成员'),
    ('S06-POS-071','S06-ORG-057','总经理'),
    ('S06-POS-072','S06-ORG-057','副总经理'),
    ('S06-POS-073','S06-ORG-057','策划总监'),
    ('S06-POS-074','S06-ORG-058','经理'),
    ('S06-POS-075','S06-ORG-058','副经理'),
    ('S06-POS-076','S06-ORG-058','成员'),
    ('S06-POS-077','S06-ORG-059','主席'),
    ('S06-POS-078','S06-ORG-059','监事');

CREATE TEMP TABLE seed_v9004_employee (
    employee_no text PRIMARY KEY, person_name text NOT NULL, hire_date date
) ON COMMIT DROP;
INSERT INTO seed_v9004_employee VALUES
    ('S06-E001','黄水财','2018-06-20'::date),
    ('S06-E002','伍伟佳','2019-03-15'::date),
    ('S06-E003','施璐璐','2019-04-01'::date),
    ('S06-E004','吴全球','2018-08-01'::date),
    ('S06-E005','薛瑞岳','2018-08-01'::date),
    ('S06-E006','王君英','2018-08-01'::date),
    ('S06-E007','王刚','2019-01-01'::date),
    ('S06-E008','张孝辉','2019-01-01'::date),
    ('S06-E009','胡启红','2019-01-01'::date),
    ('S06-E010','叶孝帅','2019-01-01'::date),
    ('S06-E011','张金斌','2018-06-20'::date),
    ('S06-E012','潘晓庭','2018-09-01'::date),
    ('S06-E013','邬军辉',NULL),
    ('S06-E014','刘小军',NULL),
    ('S06-E015','丁剑峰',NULL),
    ('S06-E016','胡小平',NULL),
    ('S06-E017','李茂坤',NULL),
    ('S06-E018','王文娟',NULL),
    ('S06-E019','张斌',NULL),
    ('S06-E020','寇明文',NULL),
    ('S06-E021','马振良',NULL),
    ('S06-E022','王恬恬',NULL),
    ('S06-E023','柴振雁',NULL),
    ('S06-E024','王云云',NULL),
    ('S06-E025','葛云宝',NULL),
    ('S06-E026','娄世炉',NULL),
    ('S06-E027','张忠成',NULL),
    ('S06-E028','兰桂花',NULL),
    ('S06-E029','张可琴',NULL),
    ('S06-E030','马梅玲',NULL),
    ('S06-E031','孙爱莲',NULL),
    ('S06-E032','王寒乐',NULL),
    ('S06-E033','胡柯',NULL),
    ('S06-E034','马金荣',NULL),
    ('S06-E035','翁志忠',NULL),
    ('S06-E036','聂美如',NULL),
    ('S06-E037','黄达凤',NULL),
    ('S06-E038','黄春花',NULL),
    ('S06-E039','陆鹏宇',NULL),
    ('S06-E040','王芊',NULL),
    ('S06-E041','刘莲娇',NULL),
    ('S06-E042','张婷婷',NULL),
    ('S06-E043','胡珊珊',NULL),
    ('S06-E044','沈建芬',NULL),
    ('S06-E045','刘璇',NULL),
    ('S06-E046','陈丹莹',NULL),
    ('S06-E047','叶方珏',NULL),
    ('S06-E048','程华姣',NULL),
    ('S06-E049','熊昕月',NULL),
    ('S06-E050','章莉莎',NULL),
    ('S06-E051','王海欣',NULL),
    ('S06-E052','蔡彩云',NULL),
    ('S06-E053','章静妍',NULL),
    ('S06-E054','王艺倩',NULL),
    ('S06-E055','厉小英',NULL),
    ('S06-E056','陈娴',NULL),
    ('S06-E057','徐佳莹',NULL),
    ('S06-E058','杨柳',NULL),
    ('S06-E059','陆玲芝',NULL),
    ('S06-E060','竺有为',NULL),
    ('S06-E061','王淑姹',NULL),
    ('S06-E062','娄海帆',NULL),
    ('S06-E063','应可伟',NULL),
    ('S06-E064','冯芹芹',NULL),
    ('S06-E065','刘春燕',NULL),
    ('S06-E066','谢芳萍',NULL),
    ('S06-E067','陈伟军',NULL),
    ('S06-E068','梁宏杰',NULL),
    ('S06-E069','吴敬强',NULL),
    ('S06-E070','孙敏光',NULL),
    ('S06-E071','罗垣劭',NULL),
    ('S06-E072','葛恒飞',NULL),
    ('S06-E073','徐华泰',NULL),
    ('S06-E074','江明强',NULL),
    ('S06-E075','江健标',NULL),
    ('S06-E076','张钱军',NULL),
    ('S06-E077','许家明',NULL),
    ('S06-E078','马金发',NULL),
    ('S06-E079','张根钱',NULL),
    ('S06-E080','陈智勇',NULL),
    ('S06-E081','王坚强',NULL),
    ('S06-E082','杨瑞颖',NULL),
    ('S06-E083','葛校魁',NULL),
    ('S06-E084','郑东辉',NULL),
    ('S06-E085','王赛荣',NULL),
    ('S06-E086','王锐兵',NULL),
    ('S06-E087','葛泽松',NULL),
    ('S06-E088','葛瑶瑶',NULL),
    ('S06-E089','葛乔元',NULL),
    ('S06-E090','白雪峰',NULL),
    ('S06-E091','李诗雨',NULL),
    ('S06-E092','周鑫',NULL),
    ('S06-E093','李俊豪',NULL),
    ('S06-E094','李航',NULL),
    ('S06-E095','罗丽萍',NULL),
    ('S06-E096','李欣蔓',NULL),
    ('S06-E097','潘锐',NULL),
    ('S06-E098','刘腾钰',NULL),
    ('S06-E099','高靖薇',NULL),
    ('S06-E100','王黎',NULL),
    ('S06-E101','王晶晶',NULL),
    ('S06-E102','户文涛',NULL),
    ('S06-E103','苏鈜妍',NULL),
    ('S06-E104','陈传文',NULL),
    ('S06-E105','王彦芝',NULL),
    ('S06-E106','张少贤',NULL),
    ('S06-E107','文林学',NULL),
    ('S06-E108','谭梦襄',NULL),
    ('S06-E109','王思涛',NULL),
    ('S06-E110','许东',NULL),
    ('S06-E111','王丞策',NULL),
    ('S06-E112','易曼',NULL),
    ('S06-E113','郭少卿',NULL),
    ('S06-E114','蒋欣瑜',NULL),
    ('S06-E115','陈家驹',NULL),
    ('S06-E116','张凯乐',NULL),
    ('S06-E117','周晓宇',NULL),
    ('S06-E118','刘瑞阳',NULL),
    ('S06-E119','周天行',NULL),
    ('S06-E120','汤灿',NULL),
    ('S06-E121','张佳文',NULL),
    ('S06-E122','周荣锦',NULL),
    ('S06-E123','丁桂蓉',NULL),
    ('S06-E124','卢俊浩',NULL),
    ('S06-E125','吴涵',NULL),
    ('S06-E126','华偌冉',NULL),
    ('S06-E127','武文慧',NULL),
    ('S06-E128','张家苑',NULL),
    ('S06-E129','钟缘',NULL),
    ('S06-E130','徐海洋',NULL),
    ('S06-E131','何晋辉',NULL),
    ('S06-E132','蓝晓雪',NULL),
    ('S06-E133','谭家华',NULL),
    ('S06-E134','肖明堂',NULL),
    ('S06-E135','张子阳',NULL),
    ('S06-E136','蒋浩杰',NULL),
    ('S06-E137','朱长鹏',NULL),
    ('S06-E138','杨松林',NULL),
    ('S06-E139','陈查根',NULL),
    ('S06-E140','侯旭',NULL),
    ('S06-E141','孙锐',NULL),
    ('S06-E142','王薪博',NULL),
    ('S06-E143','黄韦维',NULL),
    ('S06-E144','李翔',NULL),
    ('S06-E145','曾广宇',NULL),
    ('S06-E146','徐学聪',NULL),
    ('S06-E147','王宁',NULL),
    ('S06-E148','陈珊珊',NULL),
    ('S06-E149','闫露露',NULL),
    ('S06-E150','王文卓',NULL),
    ('S06-E151','孙亚德',NULL),
    ('S06-E152','聂小杰',NULL),
    ('S06-E153','王彬彬',NULL),
    ('S06-E154','金丽丽',NULL),
    ('S06-E155','熊莉',NULL),
    ('S06-E156','王奔',NULL),
    ('S06-E157','黄广华',NULL),
    ('S06-E158','王小赞',NULL),
    ('S06-E159','胡铭麟',NULL),
    ('S06-E160','杨永杰',NULL),
    ('S06-E161','曲姜波',NULL),
    ('S06-E162','娄肖华',NULL),
    ('S06-E163','任军',NULL),
    ('S06-E164','陈波',NULL),
    ('S06-E165','彭艳春',NULL),
    ('S06-E166','周佳鑫',NULL),
    ('S06-E167','叶泽宇',NULL),
    ('S06-E168','章峻华',NULL),
    ('S06-E169','陈南威',NULL),
    ('S06-E170','周林云',NULL),
    ('S06-E171','陈雪刚',NULL),
    ('S06-E172','蒋波',NULL),
    ('S06-E173','王运长',NULL),
    ('S06-E174','葛豪杰',NULL),
    ('S06-E175','陈创生',NULL),
    ('S06-E176','孔惠波',NULL),
    ('S06-E177','王强法',NULL),
    ('S06-E178','颜跃峻',NULL),
    ('S06-E179','尤劲超',NULL),
    ('S06-E180','柴盛瀚',NULL),
    ('S06-E181','陶梦繁',NULL),
    ('S06-E182','葛明泽',NULL),
    ('S06-E183','邬红红',NULL),
    ('S06-E184','童昊',NULL),
    ('S06-E185','王浙甬',NULL),
    ('S06-E186','杨堰诚',NULL),
    ('S06-E187','张旭燕',NULL),
    ('S06-E188','柴成杰',NULL),
    ('S06-E189','谢世宁',NULL),
    ('S06-E190','王飞娜',NULL),
    ('S06-E191','童懿丽',NULL),
    ('S06-E192','李爱钰',NULL),
    ('S06-E193','李婕',NULL),
    ('S06-E194','张雁雯',NULL),
    ('S06-E195','葛湘湘',NULL),
    ('S06-E196','娄佳慧',NULL),
    ('S06-E197','孙佳颖',NULL),
    ('S06-E198','楼梦莎',NULL),
    ('S06-E199','杨依',NULL),
    ('S06-E200','陆婷',NULL),
    ('S06-E201','陈美亚',NULL),
    ('S06-E202','杨成一',NULL),
    ('S06-E203','胡佳艺',NULL),
    ('S06-E204','刘巧亚',NULL),
    ('S06-E205','王姣乃',NULL);

CREATE TEMP TABLE seed_v9004_appointment (
    appointment_code text PRIMARY KEY, employee_no text NOT NULL, position_code text NOT NULL,
    org_code text NOT NULL, is_primary boolean NOT NULL
) ON COMMIT DROP;
INSERT INTO seed_v9004_appointment VALUES
    ('S06-APPT-001','S06-E005','S06-POS-001','S06-ORG-003',true),
    ('S06-APPT-002','S06-E006','S06-POS-002','S06-ORG-003',true),
    ('S06-APPT-003','S06-E004','S06-POS-003','S06-ORG-004',true),
    ('S06-APPT-004','S06-E001','S06-POS-004','S06-ORG-007',true),
    ('S06-APPT-005','S06-E002','S06-POS-005','S06-ORG-007',true),
    ('S06-APPT-006','S06-E003','S06-POS-005','S06-ORG-007',true),
    ('S06-APPT-007','S06-E007','S06-POS-006','S06-ORG-010',true),
    ('S06-APPT-008','S06-E008','S06-POS-007','S06-ORG-010',true),
    ('S06-APPT-009','S06-E007','S06-POS-008','S06-ORG-011',false),
    ('S06-APPT-010','S06-E013','S06-POS-009','S06-ORG-011',true),
    ('S06-APPT-011','S06-E014','S06-POS-009','S06-ORG-011',true),
    ('S06-APPT-012','S06-E015','S06-POS-009','S06-ORG-011',true),
    ('S06-APPT-013','S06-E009','S06-POS-010','S06-ORG-012',true),
    ('S06-APPT-014','S06-E016','S06-POS-011','S06-ORG-012',true),
    ('S06-APPT-015','S06-E017','S06-POS-011','S06-ORG-012',true),
    ('S06-APPT-016','S06-E018','S06-POS-011','S06-ORG-012',true),
    ('S06-APPT-017','S06-E019','S06-POS-011','S06-ORG-012',true),
    ('S06-APPT-018','S06-E020','S06-POS-011','S06-ORG-012',true),
    ('S06-APPT-019','S06-E010','S06-POS-012','S06-ORG-013',true),
    ('S06-APPT-020','S06-E021','S06-POS-013','S06-ORG-013',true),
    ('S06-APPT-021','S06-E022','S06-POS-014','S06-ORG-013',true),
    ('S06-APPT-022','S06-E023','S06-POS-014','S06-ORG-013',true),
    ('S06-APPT-023','S06-E024','S06-POS-014','S06-ORG-013',true),
    ('S06-APPT-024','S06-E025','S06-POS-014','S06-ORG-013',true),
    ('S06-APPT-025','S06-E026','S06-POS-014','S06-ORG-013',true),
    ('S06-APPT-026','S06-E027','S06-POS-015','S06-ORG-014',true),
    ('S06-APPT-027','S06-E011','S06-POS-016','S06-ORG-015',true),
    ('S06-APPT-028','S06-E028','S06-POS-017','S06-ORG-015',true),
    ('S06-APPT-029','S06-E012','S06-POS-017','S06-ORG-015',true),
    ('S06-APPT-030','S06-E029','S06-POS-018','S06-ORG-016',true),
    ('S06-APPT-031','S06-E030','S06-POS-018','S06-ORG-016',true),
    ('S06-APPT-032','S06-E031','S06-POS-018','S06-ORG-016',true),
    ('S06-APPT-033','S06-E032','S06-POS-018','S06-ORG-016',true),
    ('S06-APPT-034','S06-E033','S06-POS-018','S06-ORG-016',true),
    ('S06-APPT-035','S06-E034','S06-POS-018','S06-ORG-016',true),
    ('S06-APPT-036','S06-E035','S06-POS-018','S06-ORG-016',true),
    ('S06-APPT-037','S06-E036','S06-POS-018','S06-ORG-016',true),
    ('S06-APPT-038','S06-E037','S06-POS-018','S06-ORG-016',true),
    ('S06-APPT-039','S06-E038','S06-POS-018','S06-ORG-016',true),
    ('S06-APPT-040','S06-E039','S06-POS-018','S06-ORG-016',true),
    ('S06-APPT-041','S06-E040','S06-POS-018','S06-ORG-016',true),
    ('S06-APPT-042','S06-E041','S06-POS-018','S06-ORG-016',true),
    ('S06-APPT-043','S06-E042','S06-POS-018','S06-ORG-016',true),
    ('S06-APPT-044','S06-E043','S06-POS-018','S06-ORG-016',true),
    ('S06-APPT-045','S06-E044','S06-POS-018','S06-ORG-016',true),
    ('S06-APPT-046','S06-E045','S06-POS-019','S06-ORG-017',true),
    ('S06-APPT-047','S06-E046','S06-POS-020','S06-ORG-018',true),
    ('S06-APPT-048','S06-E012','S06-POS-021','S06-ORG-019',false),
    ('S06-APPT-049','S06-E047','S06-POS-022','S06-ORG-019',true),
    ('S06-APPT-050','S06-E048','S06-POS-023','S06-ORG-020',true),
    ('S06-APPT-051','S06-E049','S06-POS-023','S06-ORG-020',true),
    ('S06-APPT-052','S06-E050','S06-POS-023','S06-ORG-020',true),
    ('S06-APPT-053','S06-E051','S06-POS-023','S06-ORG-020',true),
    ('S06-APPT-054','S06-E006','S06-POS-024','S06-ORG-026',false),
    ('S06-APPT-055','S06-E052','S06-POS-024','S06-ORG-026',true),
    ('S06-APPT-056','S06-E053','S06-POS-025','S06-ORG-026',true),
    ('S06-APPT-057','S06-E054','S06-POS-025','S06-ORG-026',true),
    ('S06-APPT-058','S06-E055','S06-POS-025','S06-ORG-026',true),
    ('S06-APPT-059','S06-E056','S06-POS-025','S06-ORG-026',true),
    ('S06-APPT-060','S06-E057','S06-POS-025','S06-ORG-026',true),
    ('S06-APPT-061','S06-E058','S06-POS-026','S06-ORG-027',true),
    ('S06-APPT-062','S06-E059','S06-POS-027','S06-ORG-027',true),
    ('S06-APPT-063','S06-E058','S06-POS-028','S06-ORG-028',false),
    ('S06-APPT-064','S06-E060','S06-POS-029','S06-ORG-028',true),
    ('S06-APPT-065','S06-E061','S06-POS-030','S06-ORG-031',true),
    ('S06-APPT-066','S06-E062','S06-POS-031','S06-ORG-031',true),
    ('S06-APPT-067','S06-E047','S06-POS-032','S06-ORG-031',false),
    ('S06-APPT-068','S06-E063','S06-POS-033','S06-ORG-031',true),
    ('S06-APPT-069','S06-E012','S06-POS-034','S06-ORG-032',false),
    ('S06-APPT-070','S06-E006','S06-POS-035','S06-ORG-033',false),
    ('S06-APPT-071','S06-E064','S06-POS-036','S06-ORG-033',true),
    ('S06-APPT-072','S06-E065','S06-POS-037','S06-ORG-034',true),
    ('S06-APPT-073','S06-E066','S06-POS-038','S06-ORG-034',true),
    ('S06-APPT-074','S06-E067','S06-POS-039','S06-ORG-035',true),
    ('S06-APPT-075','S06-E068','S06-POS-040','S06-ORG-035',true),
    ('S06-APPT-076','S06-E069','S06-POS-041','S06-ORG-035',true),
    ('S06-APPT-077','S06-E070','S06-POS-041','S06-ORG-035',true),
    ('S06-APPT-078','S06-E071','S06-POS-041','S06-ORG-035',true),
    ('S06-APPT-079','S06-E072','S06-POS-041','S06-ORG-035',true),
    ('S06-APPT-080','S06-E073','S06-POS-041','S06-ORG-035',true),
    ('S06-APPT-081','S06-E074','S06-POS-041','S06-ORG-035',true),
    ('S06-APPT-082','S06-E075','S06-POS-041','S06-ORG-035',true),
    ('S06-APPT-083','S06-E076','S06-POS-042','S06-ORG-036',true),
    ('S06-APPT-084','S06-E077','S06-POS-043','S06-ORG-037',true),
    ('S06-APPT-085','S06-E078','S06-POS-044','S06-ORG-038',true),
    ('S06-APPT-086','S06-E079','S06-POS-045','S06-ORG-038',true),
    ('S06-APPT-087','S06-E080','S06-POS-046','S06-ORG-039',true),
    ('S06-APPT-088','S06-E081','S06-POS-047','S06-ORG-039',true),
    ('S06-APPT-089','S06-E082','S06-POS-048','S06-ORG-040',true),
    ('S06-APPT-090','S06-E083','S06-POS-049','S06-ORG-040',true),
    ('S06-APPT-091','S06-E084','S06-POS-049','S06-ORG-040',true),
    ('S06-APPT-092','S06-E085','S06-POS-049','S06-ORG-040',true),
    ('S06-APPT-093','S06-E086','S06-POS-049','S06-ORG-040',true),
    ('S06-APPT-094','S06-E087','S06-POS-049','S06-ORG-040',true),
    ('S06-APPT-095','S06-E088','S06-POS-049','S06-ORG-040',true),
    ('S06-APPT-096','S06-E089','S06-POS-049','S06-ORG-040',true),
    ('S06-APPT-097','S06-E004','S06-POS-050','S06-ORG-041',false),
    ('S06-APPT-098','S06-E090','S06-POS-051','S06-ORG-041',true),
    ('S06-APPT-099','S06-E091','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-100','S06-E092','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-101','S06-E093','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-102','S06-E094','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-103','S06-E095','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-104','S06-E096','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-105','S06-E097','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-106','S06-E098','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-107','S06-E099','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-108','S06-E100','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-109','S06-E101','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-110','S06-E102','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-111','S06-E103','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-112','S06-E104','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-113','S06-E105','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-114','S06-E106','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-115','S06-E107','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-116','S06-E108','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-117','S06-E109','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-118','S06-E110','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-119','S06-E111','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-120','S06-E112','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-121','S06-E113','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-122','S06-E114','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-123','S06-E115','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-124','S06-E116','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-125','S06-E117','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-126','S06-E118','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-127','S06-E119','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-128','S06-E120','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-129','S06-E121','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-130','S06-E122','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-131','S06-E123','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-132','S06-E124','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-133','S06-E125','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-134','S06-E126','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-135','S06-E127','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-136','S06-E128','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-137','S06-E129','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-138','S06-E130','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-139','S06-E131','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-140','S06-E132','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-141','S06-E133','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-142','S06-E134','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-143','S06-E135','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-144','S06-E136','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-145','S06-E137','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-146','S06-E138','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-147','S06-E139','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-148','S06-E140','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-149','S06-E141','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-150','S06-E142','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-151','S06-E143','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-152','S06-E144','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-153','S06-E145','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-154','S06-E146','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-155','S06-E147','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-156','S06-E148','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-157','S06-E149','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-158','S06-E150','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-159','S06-E151','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-160','S06-E152','S06-POS-052','S06-ORG-042',true),
    ('S06-APPT-161','S06-E153','S06-POS-053','S06-ORG-043',true),
    ('S06-APPT-162','S06-E154','S06-POS-053','S06-ORG-043',true),
    ('S06-APPT-163','S06-E155','S06-POS-054','S06-ORG-044',true),
    ('S06-APPT-164','S06-E156','S06-POS-055','S06-ORG-044',true),
    ('S06-APPT-165','S06-E157','S06-POS-055','S06-ORG-044',true),
    ('S06-APPT-166','S06-E158','S06-POS-056','S06-ORG-045',true),
    ('S06-APPT-167','S06-E159','S06-POS-057','S06-ORG-045',true),
    ('S06-APPT-168','S06-E160','S06-POS-057','S06-ORG-045',true),
    ('S06-APPT-169','S06-E161','S06-POS-057','S06-ORG-045',true),
    ('S06-APPT-170','S06-E162','S06-POS-057','S06-ORG-045',true),
    ('S06-APPT-171','S06-E163','S06-POS-057','S06-ORG-045',true),
    ('S06-APPT-172','S06-E164','S06-POS-057','S06-ORG-045',true),
    ('S06-APPT-173','S06-E165','S06-POS-057','S06-ORG-045',true),
    ('S06-APPT-174','S06-E166','S06-POS-057','S06-ORG-045',true),
    ('S06-APPT-175','S06-E167','S06-POS-057','S06-ORG-045',true),
    ('S06-APPT-176','S06-E168','S06-POS-058','S06-ORG-046',true),
    ('S06-APPT-177','S06-E169','S06-POS-059','S06-ORG-047',true),
    ('S06-APPT-178','S06-E170','S06-POS-059','S06-ORG-047',true),
    ('S06-APPT-179','S06-E171','S06-POS-059','S06-ORG-047',true),
    ('S06-APPT-180','S06-E172','S06-POS-059','S06-ORG-047',true),
    ('S06-APPT-181','S06-E173','S06-POS-060','S06-ORG-048',true),
    ('S06-APPT-182','S06-E174','S06-POS-061','S06-ORG-048',true),
    ('S06-APPT-183','S06-E175','S06-POS-062','S06-ORG-049',true),
    ('S06-APPT-184','S06-E062','S06-POS-063','S06-ORG-050',false),
    ('S06-APPT-185','S06-E176','S06-POS-064','S06-ORG-050',true),
    ('S06-APPT-186','S06-E177','S06-POS-065','S06-ORG-051',true),
    ('S06-APPT-187','S06-E178','S06-POS-065','S06-ORG-051',true),
    ('S06-APPT-188','S06-E179','S06-POS-065','S06-ORG-051',true),
    ('S06-APPT-189','S06-E180','S06-POS-066','S06-ORG-052',true),
    ('S06-APPT-190','S06-E181','S06-POS-066','S06-ORG-052',true),
    ('S06-APPT-191','S06-E182','S06-POS-066','S06-ORG-052',true),
    ('S06-APPT-192','S06-E183','S06-POS-067','S06-ORG-053',true),
    ('S06-APPT-193','S06-E184','S06-POS-068','S06-ORG-054',true),
    ('S06-APPT-194','S06-E185','S06-POS-069','S06-ORG-055',true),
    ('S06-APPT-195','S06-E186','S06-POS-070','S06-ORG-056',true),
    ('S06-APPT-196','S06-E187','S06-POS-071','S06-ORG-057',true),
    ('S06-APPT-197','S06-E188','S06-POS-072','S06-ORG-057',true),
    ('S06-APPT-198','S06-E189','S06-POS-073','S06-ORG-057',true),
    ('S06-APPT-199','S06-E190','S06-POS-074','S06-ORG-058',true),
    ('S06-APPT-200','S06-E191','S06-POS-075','S06-ORG-058',true),
    ('S06-APPT-201','S06-E192','S06-POS-076','S06-ORG-058',true),
    ('S06-APPT-202','S06-E193','S06-POS-076','S06-ORG-058',true),
    ('S06-APPT-203','S06-E194','S06-POS-076','S06-ORG-058',true),
    ('S06-APPT-204','S06-E195','S06-POS-076','S06-ORG-058',true),
    ('S06-APPT-205','S06-E196','S06-POS-076','S06-ORG-058',true),
    ('S06-APPT-206','S06-E197','S06-POS-076','S06-ORG-058',true),
    ('S06-APPT-207','S06-E198','S06-POS-076','S06-ORG-058',true),
    ('S06-APPT-208','S06-E199','S06-POS-076','S06-ORG-058',true),
    ('S06-APPT-209','S06-E200','S06-POS-076','S06-ORG-058',true),
    ('S06-APPT-210','S06-E201','S06-POS-076','S06-ORG-058',true),
    ('S06-APPT-211','S06-E202','S06-POS-076','S06-ORG-058',true),
    ('S06-APPT-212','S06-E203','S06-POS-076','S06-ORG-058',true),
    ('S06-APPT-213','S06-E204','S06-POS-076','S06-ORG-058',true),
    ('S06-APPT-214','S06-E205','S06-POS-076','S06-ORG-058',true),
    ('S06-APPT-215','S06-E011','S06-POS-077','S06-ORG-059',false),
    ('S06-APPT-216','S06-E062','S06-POS-078','S06-ORG-059',false),
    ('S06-APPT-217','S06-E004','S06-POS-078','S06-ORG-059',false);

DO $v9004$
DECLARE
    v_tenant_id uuid := '${sjg_tenant_id}'::uuid;
    v_admin_employee_no text := nullif(btrim(current_setting('sjg.bootstrap.admin_employee_no', true)), '');
    v_actor_employee_id uuid;
    v_root_org_id uuid;
    v_admin_position_id uuid;
    v_row record;
    v_parent_id uuid;
    v_org_id uuid;
    v_position_id uuid;
    v_employee_id uuid;
BEGIN
    PERFORM set_config('app.tenant_id', v_tenant_id::text, true);

    IF v_admin_employee_no IS NULL THEN
        IF session_user IN ('postgres','sjg_bootstrap') THEN
            RAISE NOTICE 'V9004 real organization directory seed skipped for direct superuser test migration';
            RETURN;
        END IF;
        RAISE EXCEPTION 'V9004 requires the controlled bootstrap administrator settings';
    END IF;

    SELECT id INTO v_actor_employee_id
      FROM org.employee
     WHERE tenant_id=v_tenant_id AND employee_no=v_admin_employee_no AND NOT is_deleted
     ORDER BY created_at,id LIMIT 1;
    IF v_actor_employee_id IS NULL THEN
        RAISE EXCEPTION 'V9004 could not resolve the controlled bootstrap administrator employee';
    END IF;

    FOR v_row IN SELECT * FROM seed_v9004_org ORDER BY depth,sort_no,org_code LOOP
        IF v_row.parent_code IS NULL THEN
            v_parent_id := NULL;
        ELSE
            SELECT id INTO v_parent_id FROM org.organization
             WHERE tenant_id=v_tenant_id AND org_code=v_row.parent_code AND NOT is_deleted LIMIT 1;
            IF v_parent_id IS NULL THEN
                RAISE EXCEPTION 'V9004 parent organization missing: %', v_row.parent_code;
            END IF;
        END IF;

        INSERT INTO org.organization(
            id,tenant_id,created_by,created_at,updated_by,updated_at,is_deleted,deleted_at,
            org_code,org_name,org_type,parent_id,path,manager_employee_id,status,
            description,headcount_plan,sort_no,version_no)
        VALUES (
            md5(v_tenant_id::text || ':v9004:org:' || v_row.org_code)::uuid,
            v_tenant_id,v_actor_employee_id,now(),v_actor_employee_id,now(),false,NULL,
            v_row.org_code,v_row.org_name,v_row.org_type,v_parent_id,v_row.path::ltree,NULL,'ACTIVE',
            v_row.description,v_row.headcount_plan,v_row.sort_no,1)
        ON CONFLICT (tenant_id,org_code) DO UPDATE SET
            org_name=EXCLUDED.org_name,org_type=EXCLUDED.org_type,parent_id=EXCLUDED.parent_id,
            path=EXCLUDED.path,status='ACTIVE',description=EXCLUDED.description,
            headcount_plan=EXCLUDED.headcount_plan,sort_no=EXCLUDED.sort_no,
            updated_by=v_actor_employee_id,updated_at=now(),is_deleted=false,deleted_at=NULL;
    END LOOP;

    FOR v_row IN SELECT * FROM seed_v9004_position ORDER BY position_code LOOP
        SELECT id INTO v_org_id FROM org.organization
         WHERE tenant_id=v_tenant_id AND org_code=v_row.org_code AND NOT is_deleted LIMIT 1;
        INSERT INTO org.position(
            id,tenant_id,created_by,created_at,updated_by,updated_at,is_deleted,deleted_at,
            position_code,position_name,org_id,status)
        VALUES (md5(v_tenant_id::text || ':v9004:position:' || v_row.position_code)::uuid,
                v_tenant_id,v_actor_employee_id,now(),v_actor_employee_id,now(),
                false,NULL,v_row.position_code,v_row.position_name,v_org_id,'ACTIVE')
        ON CONFLICT (tenant_id,position_code) DO UPDATE SET
            position_name=EXCLUDED.position_name,org_id=EXCLUDED.org_id,status='ACTIVE',
            updated_by=v_actor_employee_id,updated_at=now(),is_deleted=false,deleted_at=NULL;
    END LOOP;

    FOR v_row IN SELECT * FROM seed_v9004_employee ORDER BY employee_no LOOP
        INSERT INTO org.employee AS employee(
            id,tenant_id,created_by,created_at,updated_by,updated_at,is_deleted,deleted_at,
            employee_no,person_name,employment_status,hire_date,leave_date)
        VALUES (md5(v_tenant_id::text || ':v9004:employee:' || v_row.employee_no)::uuid,
                v_tenant_id,v_actor_employee_id,now(),v_actor_employee_id,now(),
                false,NULL,v_row.employee_no,v_row.person_name,'ACTIVE',v_row.hire_date,NULL)
        ON CONFLICT (tenant_id,employee_no) DO UPDATE SET
            person_name=EXCLUDED.person_name,employment_status='ACTIVE',
            hire_date=COALESCE(employee.hire_date,EXCLUDED.hire_date),leave_date=NULL,
            updated_by=v_actor_employee_id,updated_at=now(),is_deleted=false,deleted_at=NULL;
    END LOOP;

    FOR v_row IN SELECT * FROM seed_v9004_appointment ORDER BY appointment_code LOOP
        SELECT id INTO v_employee_id FROM org.employee
         WHERE tenant_id=v_tenant_id AND employee_no=v_row.employee_no AND NOT is_deleted LIMIT 1;
        SELECT id INTO v_position_id FROM org.position
         WHERE tenant_id=v_tenant_id AND position_code=v_row.position_code AND NOT is_deleted LIMIT 1;
        SELECT id INTO v_org_id FROM org.organization
         WHERE tenant_id=v_tenant_id AND org_code=v_row.org_code AND NOT is_deleted LIMIT 1;

        INSERT INTO org.employee_position(
            id,tenant_id,created_by,created_at,updated_by,updated_at,is_deleted,deleted_at,
            employee_id,position_id,org_id,is_primary,effective_start_date,effective_end_date,status)
        VALUES (md5(v_tenant_id::text || ':v9004:appointment:' || v_row.appointment_code)::uuid,
                v_tenant_id,v_actor_employee_id,now(),v_actor_employee_id,now(),
                false,NULL,v_employee_id,v_position_id,v_org_id,v_row.is_primary,DATE '2026-08-23',NULL,'ACTIVE')
        ON CONFLICT (id) DO UPDATE SET
            employee_id=EXCLUDED.employee_id,position_id=EXCLUDED.position_id,org_id=EXCLUDED.org_id,
            is_primary=EXCLUDED.is_primary,effective_start_date=EXCLUDED.effective_start_date,
            effective_end_date=NULL,status='ACTIVE',updated_by=v_actor_employee_id,updated_at=now(),
            is_deleted=false,deleted_at=NULL;
    END LOOP;

    UPDATE org.employee employee
       SET primary_org_id=organization.id,primary_position_id=position.id,
           updated_by=v_actor_employee_id,updated_at=now()
      FROM seed_v9004_appointment seed
      JOIN org.organization organization
        ON organization.tenant_id=v_tenant_id AND organization.org_code=seed.org_code AND NOT organization.is_deleted
      JOIN org.position position
        ON position.tenant_id=v_tenant_id AND position.position_code=seed.position_code AND NOT position.is_deleted
     WHERE seed.is_primary
       AND employee.tenant_id=v_tenant_id AND employee.employee_no=seed.employee_no AND NOT employee.is_deleted;

    UPDATE org.organization organization
       SET manager_employee_id=employee.id,updated_by=v_actor_employee_id,updated_at=now()
      FROM seed_v9004_org seed
      JOIN org.employee employee
        ON employee.tenant_id=v_tenant_id AND employee.employee_no=seed.manager_employee_no AND NOT employee.is_deleted
     WHERE organization.tenant_id=v_tenant_id AND organization.org_code=seed.org_code
       AND seed.manager_employee_no IS NOT NULL AND NOT organization.is_deleted;

    SELECT id INTO v_root_org_id FROM org.organization
     WHERE tenant_id=v_tenant_id AND org_code='S06-ORG-001' AND NOT is_deleted;
    SELECT id INTO v_admin_position_id FROM org.position
     WHERE tenant_id=v_tenant_id AND position_code='S8020-ADMIN' AND NOT is_deleted;
    UPDATE org.position SET org_id=v_root_org_id,updated_by=v_actor_employee_id,updated_at=now()
     WHERE tenant_id=v_tenant_id AND position_code='S8020-ADMIN' AND NOT is_deleted;
    UPDATE org.employee SET primary_org_id=v_root_org_id,primary_position_id=v_admin_position_id,
           updated_by=v_actor_employee_id,updated_at=now()
     WHERE tenant_id=v_tenant_id AND id=v_actor_employee_id;
    UPDATE org.employee_position SET org_id=v_root_org_id,position_id=v_admin_position_id,
           updated_by=v_actor_employee_id,updated_at=now()
     WHERE tenant_id=v_tenant_id AND employee_id=v_actor_employee_id AND status='ACTIVE' AND NOT is_deleted;
    UPDATE iam.user_identity SET org_id=v_root_org_id,position_id=v_admin_position_id,updated_at=now()
     WHERE tenant_id=v_tenant_id AND employee_id=v_actor_employee_id AND NOT is_deleted;
    UPDATE org.organization SET status='INACTIVE',is_deleted=true,deleted_at=now(),
           updated_by=v_actor_employee_id,updated_at=now()
     WHERE tenant_id=v_tenant_id AND org_code='S8020-PLATFORM' AND NOT is_deleted;

    UPDATE iam.role_permission role_permission
       SET is_deleted=false,updated_at=now()
      FROM iam.role role,iam.permission permission
     WHERE role_permission.tenant_id=v_tenant_id
       AND role_permission.role_id=role.id AND role_permission.permission_id=permission.id
       AND role.tenant_id=v_tenant_id AND role.role_code='S8020_BOOTSTRAP_ADMIN'
       AND permission.tenant_id=v_tenant_id AND role.enabled AND NOT role.is_deleted AND NOT permission.is_deleted;

    INSERT INTO iam.role_permission(id,tenant_id,role_id,permission_id,created_at,updated_at,is_deleted)
    SELECT gen_random_uuid(),v_tenant_id,role.id,permission.id,now(),now(),false
      FROM iam.role role
      CROSS JOIN iam.permission permission
     WHERE role.tenant_id=v_tenant_id AND role.role_code='S8020_BOOTSTRAP_ADMIN'
       AND permission.tenant_id=v_tenant_id AND role.enabled AND NOT role.is_deleted AND NOT permission.is_deleted
       AND NOT EXISTS (SELECT 1 FROM iam.role_permission existing
                        WHERE existing.tenant_id=v_tenant_id AND existing.role_id=role.id
                          AND existing.permission_id=permission.id AND NOT existing.is_deleted);

    INSERT INTO org.architecture_version(
        tenant_id,version_no,source_draft_id,snapshot,change_summary,published_by,published_at)
    SELECT v_tenant_id,
           COALESCE((SELECT max(version_no) FROM org.architecture_version WHERE tenant_id=v_tenant_id),0)+1,
           NULL,
           COALESCE(jsonb_agg(jsonb_build_object(
               'id',organization.id,'orgCode',organization.org_code,'orgName',organization.org_name,
               'orgType',organization.org_type,'parentId',organization.parent_id,'path',organization.path::text,
               'status',organization.status,'managerEmployeeId',organization.manager_employee_id,
               'memberCount',(SELECT count(*) FROM org.employee_position appointment
                    WHERE appointment.tenant_id=organization.tenant_id AND appointment.org_id=organization.id
                      AND appointment.status='ACTIVE' AND appointment.effective_start_date<=current_date
                      AND (appointment.effective_end_date IS NULL OR appointment.effective_end_date>=current_date)
                      AND NOT appointment.is_deleted),
               'headcountPlan',organization.headcount_plan,'sortNo',organization.sort_no,
               'versionNo',organization.version_no,'description',organization.description
           ) ORDER BY organization.path,organization.sort_no,organization.org_code),'[]'::jsonb),
           '[{"kind":"REAL_DIRECTORY_SEED_V9004","summary":"导入59个真实组织节点、205位员工和217条任职，并为超级管理员授予全量权限"}]'::jsonb,
           NULL,now()
      FROM org.organization organization
     WHERE organization.tenant_id=v_tenant_id AND organization.org_code~'^S06-ORG-[0-9]{3}$'
       AND NOT organization.is_deleted
       AND NOT EXISTS (
           SELECT 1 FROM org.architecture_version version
            WHERE version.tenant_id=v_tenant_id
              AND version.change_summary @> '[{"kind":"REAL_DIRECTORY_SEED_V9004"}]'::jsonb);

    IF (SELECT count(*) FROM seed_v9004_org seed
         JOIN org.organization organization ON organization.tenant_id=v_tenant_id
          AND organization.org_code=seed.org_code AND NOT organization.is_deleted) <> 59 THEN
        RAISE EXCEPTION 'V9004 organization count assertion failed';
    END IF;
    IF (SELECT count(*) FROM seed_v9004_employee seed
         JOIN org.employee employee ON employee.tenant_id=v_tenant_id
          AND employee.employee_no=seed.employee_no AND NOT employee.is_deleted) <> 205 THEN
        RAISE EXCEPTION 'V9004 employee count assertion failed';
    END IF;
    IF (SELECT count(*) FROM seed_v9004_appointment seed
         JOIN org.employee_position appointment
           ON appointment.id=md5(v_tenant_id::text || ':v9004:appointment:' || seed.appointment_code)::uuid
          AND appointment.tenant_id=v_tenant_id AND appointment.status='ACTIVE' AND NOT appointment.is_deleted) <> 217 THEN
        RAISE EXCEPTION 'V9004 appointment count assertion failed';
    END IF;
    IF (SELECT count(DISTINCT role_permission.permission_id)
          FROM iam.role role
          JOIN iam.role_permission role_permission ON role_permission.tenant_id=role.tenant_id
               AND role_permission.role_id=role.id AND NOT role_permission.is_deleted
          JOIN iam.permission permission ON permission.tenant_id=role_permission.tenant_id
               AND permission.id=role_permission.permission_id AND NOT permission.is_deleted
         WHERE role.tenant_id=v_tenant_id AND role.role_code='S8020_BOOTSTRAP_ADMIN'
           AND role.enabled AND NOT role.is_deleted)
       <> (SELECT count(*) FROM iam.permission WHERE tenant_id=v_tenant_id AND NOT is_deleted) THEN
        RAISE EXCEPTION 'V9004 bootstrap administrator permission assertion failed';
    END IF;
END
$v9004$;

INSERT INTO org.organization_code_sequence(tenant_id,next_value,updated_at)
SELECT '${sjg_tenant_id}'::uuid,
       COALESCE(max(cast(substring(org_code from 'S06-ORG-([0-9]+)') as integer)),0)+1,
       now()
  FROM org.organization
 WHERE tenant_id='${sjg_tenant_id}'::uuid AND org_code~'^S06-ORG-[0-9]+$' AND NOT is_deleted
ON CONFLICT (tenant_id) DO UPDATE SET
    next_value=GREATEST(org.organization_code_sequence.next_value,EXCLUDED.next_value),
    updated_at=now();

RESET ROLE;
