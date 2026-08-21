-- PHASE-06 C3 minimal technical overlay for explicit platform file metadata.
-- Source: PHASE-06 SOURCE_CONTRACT requires file sensitivity + version while approved V16 DDL lacks columns.
-- P1_INTERNAL is a technical token mapping the approved file_object column comments "敏感级别:P1-内部".

ALTER TABLE document.file_object
    ADD COLUMN sensitive_level varchar(32) DEFAULT 'P1_INTERNAL' NOT NULL,
    ADD COLUMN version_no integer DEFAULT 1 NOT NULL;

ALTER TABLE document.file_object
    ADD CONSTRAINT ck_document_file_object_version_no CHECK (version_no > 0);

COMMENT ON COLUMN document.file_object.sensitive_level IS
    '文件敏感等级｜PHASE-06平台技术元数据｜默认P1_INTERNAL映射批准DDL注释P1-内部；具体业务分级由后续权限策略解释';
COMMENT ON COLUMN document.file_object.version_no IS
    '文件版本号｜PHASE-06平台技术元数据｜正整数；不替代业务对象自身version_no';
