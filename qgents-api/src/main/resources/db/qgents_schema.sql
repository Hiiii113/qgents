-- Qgents MVP database initialization schema, MySQL 8.0.16+.
-- 手动执行：mysql -u <user> -p <database> < database/qgents_schema.sql
-- This file initializes a fresh database. Existing deployments require a separate versioned migration.
SET
    NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS
    users (
        id BINARY(16) PRIMARY KEY COMMENT '用户UUIDv7（二进制存储）',
        email VARCHAR(320) NOT NULL COMMENT '归一化后的用户登录邮箱，用于大小写无关唯一校验',
        display_name VARCHAR(120) NOT NULL COMMENT '用户展示名称',
        avatar_url TEXT NULL COMMENT '用户头像URL',
        avatar_object_key VARCHAR(512) NULL COMMENT '当前头像对象键（OSS avatars/{userId}/...），用于替换时定位并删除旧头像',
        password_hash VARCHAR(255) NOT NULL COMMENT '密码单向哈希，禁止存储明文',
        password_algorithm VARCHAR(32) NOT NULL DEFAULT 'ARGON2ID' COMMENT '密码哈希算法枚举：ARGON2ID/BCRYPT',
        status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT '账号状态枚举：ACTIVE/DISABLED',
        created_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
        updated_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
        UNIQUE KEY uk_users_email (email)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户账号与登录凭据';

CREATE TABLE IF NOT EXISTS
    refresh_tokens (
        id BINARY(16) PRIMARY KEY COMMENT '刷新令牌记录UUIDv7',
        user_id BINARY(16) NOT NULL COMMENT '令牌所属用户ID',
        token_hash BINARY(32) NOT NULL COMMENT '刷新令牌SHA-256哈希，禁止存储明文',
        expires_at DATETIME (6) NOT NULL COMMENT '令牌过期时间（UTC）',
        revoked_at DATETIME (6) NULL COMMENT '令牌撤销时间（UTC），为空表示未撤销',
        created_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '签发时间（UTC）',
        UNIQUE KEY uk_refresh_hash (token_hash),
        KEY idx_refresh_user (user_id, expires_at),
        CONSTRAINT fk_refresh_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '登录刷新令牌，仅保存不可逆哈希';

CREATE TABLE IF NOT EXISTS
    password_reset_tokens (
        id BINARY(16) PRIMARY KEY COMMENT '密码重置记录UUIDv7',
        user_id BINARY(16) NOT NULL COMMENT '申请重置的用户ID',
        token_hash BINARY(32) NOT NULL COMMENT '重置令牌SHA-256哈希，禁止存储明文',
        expires_at DATETIME (6) NOT NULL COMMENT '令牌过期时间（UTC）',
        used_at DATETIME (6) NULL COMMENT '令牌使用时间（UTC）',
        created_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '申请时间（UTC）',
        UNIQUE KEY uk_reset_hash (token_hash),
        KEY idx_reset_user (user_id, expires_at),
        CONSTRAINT fk_reset_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '密码重置一次性令牌';

CREATE TABLE IF NOT EXISTS
    email_verification_codes (
        id BINARY(16) PRIMARY KEY COMMENT '验证码记录UUIDv7',
        email VARCHAR(320) NOT NULL COMMENT '归一化邮箱（小写）',
        code_hash BINARY(32) NOT NULL COMMENT '验证码SHA-256哈希，禁止存储明文',
        expires_at DATETIME (6) NOT NULL COMMENT '验证码过期时间（UTC）',
        used_at DATETIME (6) NULL COMMENT '验证码使用时间（UTC），为空表示未使用',
        created_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '发送时间（UTC）',
        UNIQUE KEY uk_verify_hash (code_hash),
        KEY idx_verify_email (email, expires_at)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '注册邮箱验证码一次性记录';

CREATE TABLE IF NOT EXISTS
    teams (
        id BINARY(16) PRIMARY KEY COMMENT '团队UUIDv7',
        owner_user_id BINARY(16) NOT NULL COMMENT '团队所有者用户ID',
        name VARCHAR(255) NOT NULL COMMENT '团队名称',
        description TEXT NULL COMMENT '团队简介',
        avatar_url TEXT NULL COMMENT '团队头像URL（OSS 公共读长期地址，可为空）',
        status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT '团队状态枚举：ACTIVE/ARCHIVED',
        created_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
        updated_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
        KEY idx_team_owner (owner_user_id),
        CONSTRAINT fk_team_owner FOREIGN KEY (owner_user_id) REFERENCES users (id)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '团队协作顶层边界';

CREATE TABLE IF NOT EXISTS
    team_members (
        team_id BINARY(16) NOT NULL COMMENT '团队ID',
        user_id BINARY(16) NOT NULL COMMENT '成员用户ID',
        role VARCHAR(32) NOT NULL COMMENT '团队角色枚举：TEAM_OWNER/TEAM_MEMBER',
        joined_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '加入时间（UTC）',
        PRIMARY KEY (team_id, user_id),
        KEY idx_tm_user (user_id),
        CONSTRAINT fk_tm_team FOREIGN KEY (team_id) REFERENCES teams (id) ON DELETE CASCADE,
        CONSTRAINT fk_tm_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '团队成员及角色关系';

CREATE TABLE IF NOT EXISTS
    team_invitations (
        id BINARY(16) PRIMARY KEY COMMENT '团队邀请UUIDv7',
        team_id BINARY(16) NOT NULL COMMENT '目标团队ID',
        invited_by BINARY(16) NOT NULL COMMENT '邀请发起用户ID',
        email_normalized VARCHAR(320) NOT NULL COMMENT '被邀请邮箱归一化值',
        token_hash BINARY(32) NOT NULL COMMENT '邀请令牌SHA-256哈希',
        status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '邀请状态枚举：PENDING/ACCEPTED/REVOKED/EXPIRED',
        expires_at DATETIME (6) NOT NULL COMMENT '邀请过期时间（UTC）',
        accepted_at DATETIME (6) NULL COMMENT '接受邀请时间（UTC）',
        created_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
        UNIQUE KEY uk_invite_hash (token_hash),
        KEY idx_invite_team (team_id, status),
        KEY idx_invite_user (invited_by),
        CONSTRAINT fk_invite_team FOREIGN KEY (team_id) REFERENCES teams (id) ON DELETE CASCADE,
        CONSTRAINT fk_invite_user FOREIGN KEY (invited_by) REFERENCES users (id)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '团队邮箱邀请';

CREATE TABLE IF NOT EXISTS
    projects (
        id BINARY(16) PRIMARY KEY COMMENT '项目UUIDv7',
        team_id BINARY(16) NOT NULL COMMENT '所属团队ID',
        created_by BINARY(16) NOT NULL COMMENT '项目创建用户ID',
        name VARCHAR(255) NOT NULL COMMENT '项目名称',
        description TEXT NULL COMMENT '项目说明',
        avatar_url TEXT NULL COMMENT '项目头像URL（OSS 公共读长期地址，可为空）',
        settings JSON NULL COMMENT '项目设置：需求群规则开关（allowCreateGroup/autoArchiveGroup/allowAgentTrigger/autoJoinAllGroups）',
        status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT '项目状态枚举：ACTIVE/ARCHIVED',
        created_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
        updated_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
        KEY idx_project_team (team_id, status),
        KEY idx_project_creator (created_by),
        CONSTRAINT fk_project_team FOREIGN KEY (team_id) REFERENCES teams (id),
        CONSTRAINT fk_project_creator FOREIGN KEY (created_by) REFERENCES users (id)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '项目隔离边界';

CREATE TABLE IF NOT EXISTS
    project_members (
        project_id BINARY(16) NOT NULL COMMENT '项目ID',
        user_id BINARY(16) NOT NULL COMMENT '项目成员用户ID',
        role VARCHAR(32) NOT NULL COMMENT '项目角色枚举：PROJECT_ADMIN/PROJECT_MEMBER',
        joined_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '加入项目时间（UTC）',
        PRIMARY KEY (project_id, user_id),
        KEY idx_pm_user (user_id),
        CONSTRAINT fk_pm_project FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE,
        CONSTRAINT fk_pm_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '项目成员及角色关系';

CREATE TABLE IF NOT EXISTS
    github_installations (
        id BINARY(16) PRIMARY KEY COMMENT '安装记录UUIDv7',
        team_id BINARY(16) NOT NULL COMMENT '授权所属团队ID',
        provider_installation_id BIGINT UNSIGNED NOT NULL COMMENT 'GitHub App installation数字ID',
        account_login VARCHAR(255) NOT NULL COMMENT 'GitHub授权账号登录名',
        account_type VARCHAR(32) NOT NULL COMMENT 'GitHub账号类型枚举：USER/ORGANIZATION；USER 建仓使用 OAuth',
        repository_selection VARCHAR(16) NULL COMMENT 'GitHub App 仓库访问范围：ALL/SELECTED；历史记录可为空',
        status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT '安装状态枚举：ACTIVE/SUSPENDED/DELETED',
        created_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '同步创建时间（UTC）',
        updated_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '同步更新时间（UTC）',
        UNIQUE KEY uk_ghi_provider (provider_installation_id),
        KEY idx_ghi_team (team_id, status),
        CONSTRAINT fk_ghi_team FOREIGN KEY (team_id) REFERENCES teams (id)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '团队GitHub App安装授权元数据，不存访问令牌';

CREATE TABLE IF NOT EXISTS
    github_oauth_states (
        id BINARY(16) PRIMARY KEY COMMENT 'OAuth state UUIDv7，同时作为签名 JWT jti',
        state_hash BINARY(32) NOT NULL COMMENT 'state 原文 SHA-256 摘要，不保存 state 原文',
        user_id BINARY(16) NOT NULL COMMENT '发起授权的 Qgents 用户',
        client VARCHAR(16) NOT NULL COMMENT '回跳端：WEB/MOBILE',
        expires_at DATETIME(6) NOT NULL COMMENT 'state 过期时间（UTC）',
        consumed_at DATETIME(6) NULL COMMENT '一次性消费时间（UTC）',
        created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
        UNIQUE KEY uk_ghos_state_hash (state_hash),
        KEY idx_ghos_user (user_id, created_at),
        KEY idx_ghos_expiry (expires_at, consumed_at),
        CONSTRAINT fk_ghos_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
        CONSTRAINT chk_ghos_client CHECK (client IN ('WEB', 'MOBILE'))
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'GitHub OAuth 一次性 state 摘要';

CREATE TABLE IF NOT EXISTS
    github_user_authorizations (
        id BINARY(16) PRIMARY KEY COMMENT '授权记录 UUIDv7',
        user_id BINARY(16) NOT NULL COMMENT 'Qgents 用户',
        provider VARCHAR(32) NOT NULL COMMENT 'OAuth 提供方，当前固定 GITHUB',
        provider_user_id BIGINT UNSIGNED NOT NULL COMMENT 'GitHub /user.id',
        provider_login VARCHAR(255) NOT NULL COMMENT 'GitHub login 展示快照',
        access_token_ciphertext TEXT NULL COMMENT 'AES-GCM 加密后的 OAuth Token，不保存明文',
        scopes VARCHAR(1024) NULL COMMENT '实际授权 scope 快照，逗号分隔',
        status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/REVOKING/REVOKED/EXPIRED/ERROR',
        last_error_code VARCHAR(64) NULL COMMENT '最近一次远程撤销失败的稳定错误码',
        authorized_at DATETIME(6) NOT NULL COMMENT '首次或最近重新授权时间（UTC）',
        last_validated_at DATETIME(6) NULL COMMENT '最近成功调用 GitHub /user 时间（UTC）',
        revoked_at DATETIME(6) NULL COMMENT '本地撤销时间（UTC）',
        created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
        updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
        UNIQUE KEY uk_ghua_user_provider (user_id, provider),
        UNIQUE KEY uk_ghua_provider_user (provider, provider_user_id),
        KEY idx_ghua_user_status (user_id, status),
        KEY idx_ghua_provider_status (provider_user_id, status),
        CONSTRAINT fk_ghua_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
        CONSTRAINT chk_ghua_provider CHECK (provider IN ('GITHUB')),
        CONSTRAINT chk_ghua_status CHECK (status IN ('ACTIVE', 'REVOKING', 'REVOKED', 'EXPIRED', 'ERROR'))
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Qgents 用户 GitHub OAuth 授权密文';

CREATE TABLE IF NOT EXISTS
    github_repositories (
        id BINARY(16) PRIMARY KEY COMMENT '仓库镜像UUIDv7',
        installation_id BINARY(16) NOT NULL COMMENT '所属GitHub安装记录ID',
        provider_repository_id BIGINT UNSIGNED NOT NULL COMMENT 'GitHub仓库数字ID',
        owner_login VARCHAR(255) NOT NULL COMMENT 'GitHub仓库所有者登录名',
        name VARCHAR(255) NOT NULL COMMENT '仓库名称',
        default_branch VARCHAR(512) NULL COMMENT 'GitHub默认分支名；空仓库尚未创建首个提交时为空',
        visibility VARCHAR(32) NOT NULL COMMENT '仓库可见性枚举：PUBLIC/PRIVATE/INTERNAL',
        archived TINYINT (1) NOT NULL DEFAULT 0 COMMENT '是否已在GitHub归档：0否1是',
        authorization_status VARCHAR(32) NOT NULL DEFAULT 'AUTHORIZED' COMMENT '授权状态枚举：AUTHORIZED/REVOKED',
        synced_at DATETIME (6) NOT NULL COMMENT '最近同步时间（UTC）',
        UNIQUE KEY uk_ghr_provider (provider_repository_id),
        KEY idx_ghr_install (installation_id),
        CONSTRAINT fk_ghr_install FOREIGN KEY (installation_id) REFERENCES github_installations (id)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'GitHub仓库元数据镜像';

CREATE TABLE IF NOT EXISTS
    project_repositories (
        id BINARY(16) PRIMARY KEY COMMENT '项目仓库绑定UUIDv7',
        project_id BINARY(16) NOT NULL COMMENT '项目ID',
        repository_id BINARY(16) NOT NULL COMMENT 'GitHub仓库镜像ID',
        default_branch VARCHAR(512) NOT NULL COMMENT '该项目使用的默认分支，可覆盖GitHub仓库默认值',
        display_name VARCHAR(255) NULL COMMENT '仓库在项目内的显示名称',
        bound_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '绑定时间（UTC）',
        status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT '绑定状态：ACTIVE/UNBOUND（软解绑）',
        unbound_at DATETIME (6) NULL COMMENT '软解绑时间（UTC）',
        UNIQUE KEY uk_pr_repo (project_id, repository_id),
        KEY idx_pr_repository (repository_id),
        CONSTRAINT fk_pr_project FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE,
        CONSTRAINT fk_pr_repo FOREIGN KEY (repository_id) REFERENCES github_repositories (id),
        CONSTRAINT chk_pr_status CHECK (status IN ('ACTIVE', 'UNBOUND'))
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '项目与GitHub仓库绑定';

CREATE TABLE IF NOT EXISTS
    requirement_groups (
        id BINARY(16) PRIMARY KEY COMMENT '需求群UUIDv7',
        project_id BINARY(16) NOT NULL COMMENT '所属项目ID',
        created_by BINARY(16) NOT NULL COMMENT '创建用户ID',
        name VARCHAR(255) NOT NULL COMMENT '群聊名称',
        description TEXT NULL COMMENT '群聊目标和需求背景说明',
        group_type VARCHAR(32) NOT NULL DEFAULT 'REQUIREMENT' COMMENT '群类型：PROJECT_MAIN/REQUIREMENT',
        status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT '群状态枚举：ACTIVE/ARCHIVED',
        project_main_project_id BINARY(16)
            GENERATED ALWAYS AS (
                CASE
                    WHEN group_type = 'PROJECT_MAIN' THEN project_id
                    ELSE NULL
                END
            ) STORED COMMENT 'PROJECT_MAIN uniqueness key; NULL for requirement groups',
        last_message_at DATETIME (6) NULL COMMENT '最近消息时间（UTC）',
        created_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
        updated_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
        KEY idx_rg_project (project_id, status, last_message_at),
        KEY idx_rg_creator (created_by),
        UNIQUE KEY uk_rg_project_main (project_main_project_id),
        CONSTRAINT fk_rg_project FOREIGN KEY (project_id) REFERENCES projects (id),
        CONSTRAINT fk_rg_creator FOREIGN KEY (created_by) REFERENCES users (id),
        CONSTRAINT chk_rg_group_type
            CHECK (group_type IN ('PROJECT_MAIN', 'REQUIREMENT')),
        CONSTRAINT chk_rg_status
            CHECK (status IN ('ACTIVE', 'ARCHIVED')),
        CONSTRAINT chk_rg_project_main_active
            CHECK (group_type <> 'PROJECT_MAIN' OR status = 'ACTIVE')
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '项目需求讨论与协作上下文群';

CREATE TABLE IF NOT EXISTS
    requirement_group_repositories (
        requirement_group_id BINARY(16) NOT NULL COMMENT '需求群ID',
        project_repository_id BINARY(16) NOT NULL COMMENT '关联的项目仓库绑定ID',
        PRIMARY KEY (requirement_group_id, project_repository_id),
        KEY idx_rgr_repo (project_repository_id),
        CONSTRAINT fk_rgr_group FOREIGN KEY (requirement_group_id) REFERENCES requirement_groups (id) ON DELETE CASCADE,
        CONSTRAINT fk_rgr_repo FOREIGN KEY (project_repository_id) REFERENCES project_repositories (id)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '需求群与多个项目仓库关联';

CREATE TABLE IF NOT EXISTS
    messages (
        id BINARY(16) PRIMARY KEY COMMENT '消息UUIDv7',
        requirement_group_id BINARY(16) NOT NULL COMMENT '所属需求群ID',
        sequence_no BIGINT UNSIGNED NOT NULL COMMENT '群内单调递增消息序号',
        author_user_id BINARY(16) NULL COMMENT '用户作者ID；Agent/系统消息时为空',
        agent_id BINARY(16) NULL COMMENT 'Agent 作者ID；用户/系统消息时为空',
        client_message_id VARCHAR(128) NULL COMMENT '客户端生成的消息幂等ID',
        message_type VARCHAR(32) NOT NULL DEFAULT 'TEXT' COMMENT '消息类型枚举：TEXT/CODE/IMAGE/FILE/SYSTEM/QUOTE',
        content JSON NOT NULL COMMENT '按消息类型校验的结构化内容JSON',
        mentions JSON NULL COMMENT '提及对象JSON数组，元素含type(USER/AGENT)和id',
        reply_to_message_id BINARY(16) NULL COMMENT '回复或引用的原消息ID',
        created_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '发送时间（UTC）',
        UNIQUE KEY uk_msg_seq (requirement_group_id, sequence_no),
        UNIQUE KEY uk_msg_client (requirement_group_id, client_message_id),
        KEY idx_msg_user (author_user_id),
        KEY idx_msg_agent (agent_id),
        KEY idx_msg_reply (reply_to_message_id),
        CONSTRAINT fk_msg_group FOREIGN KEY (requirement_group_id) REFERENCES requirement_groups (id),
        CONSTRAINT fk_msg_user FOREIGN KEY (author_user_id) REFERENCES users (id),
        CONSTRAINT fk_msg_reply FOREIGN KEY (reply_to_message_id) REFERENCES messages (id),
        CONSTRAINT chk_message_sender CHECK (
            (
                message_type = 'SYSTEM'
                AND author_user_id IS NULL
                AND agent_id IS NULL
            )
            OR (
                (author_user_id IS NOT NULL OR agent_id IS NOT NULL)
                AND NOT (author_user_id IS NOT NULL AND agent_id IS NOT NULL)
            )
        )
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '需求群有序消息，提及在MVP内以JSON保存';

CREATE TABLE IF NOT EXISTS
    attachments (
        id BINARY(16) PRIMARY KEY COMMENT '附件UUIDv7',
        project_id BINARY(16) NOT NULL COMMENT '附件所属项目ID，用于上传前权限隔离',
        message_id BINARY(16) NULL COMMENT '发送消息后绑定的消息ID，上传阶段为空',
        uploaded_by BINARY(16) NOT NULL COMMENT '上传用户ID',
        object_key VARCHAR(512) NOT NULL COMMENT '对象存储内部键，不含临时访问凭证',
        file_name VARCHAR(512) NOT NULL COMMENT '原始文件名',
        media_type VARCHAR(255) NULL COMMENT 'MIME媒体类型',
        size_bytes BIGINT UNSIGNED NULL COMMENT '文件大小，单位字节',
        status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '附件状态枚举：PENDING/READY/FAILED/DELETED',
        metadata JSON NULL COMMENT '附件扩展JSON，如图片宽高和内容哈希',
        created_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '上传记录创建时间（UTC）',
        UNIQUE KEY uk_attachment_object (object_key),
        KEY idx_attachment_message (message_id, created_at),
        KEY idx_attachment_user (uploaded_by),
        CONSTRAINT fk_attachment_project FOREIGN KEY (project_id) REFERENCES projects (id),
        CONSTRAINT fk_attachment_message FOREIGN KEY (message_id) REFERENCES messages (id) ON DELETE SET NULL,
        CONSTRAINT fk_attachment_user FOREIGN KEY (uploaded_by) REFERENCES users (id)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '消息文件、图片和Diff附件元数据';

CREATE TABLE IF NOT EXISTS
    skills (
        id BINARY(16) PRIMARY KEY COMMENT 'Skill UUIDv7',
        project_id BINARY(16) NOT NULL COMMENT '所属项目ID',
        created_by BINARY(16) NOT NULL COMMENT '创建用户ID',
        name VARCHAR(255) NOT NULL COMMENT 'Skill名称',
        content MEDIUMTEXT NOT NULL COMMENT '可复用操作规范正文',
        tags JSON NULL COMMENT '标签JSON字符串数组',
        visibility VARCHAR(32) NOT NULL DEFAULT 'PRIVATE' COMMENT '可见性枚举：PRIVATE/PROJECT_SHARED',
        status VARCHAR(32) NOT NULL DEFAULT 'DRAFT' COMMENT '状态枚举：DRAFT/PENDING_REVIEW/PUBLISHED/REJECTED/ARCHIVED',
        submitted_by BINARY(16) NULL COMMENT '最近提交审核用户ID',
        submitted_at DATETIME (6) NULL COMMENT '最近提交审核时间（UTC）',
        reviewer_id BINARY(16) NULL COMMENT '最近审核用户ID',
        rejection_reason TEXT NULL COMMENT '最近驳回原因',
        reviewed_at DATETIME (6) NULL COMMENT '最近审核时间（UTC）',
        created_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
        updated_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
        KEY idx_skill_project (project_id, status, updated_at),
        KEY idx_skill_creator (created_by),
        KEY idx_skill_reviewer (reviewer_id),
        CONSTRAINT fk_skill_project FOREIGN KEY (project_id) REFERENCES projects (id),
        CONSTRAINT fk_skill_creator FOREIGN KEY (created_by) REFERENCES users (id),
        CONSTRAINT fk_skill_reviewer FOREIGN KEY (reviewer_id) REFERENCES users (id),
        CONSTRAINT fk_skill_submitter FOREIGN KEY (submitted_by) REFERENCES users (id)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '项目Skill，MVP标签内嵌JSON';

CREATE TABLE IF NOT EXISTS
    memories (
        id BINARY(16) PRIMARY KEY COMMENT 'Memory UUIDv7',
        project_id BINARY(16) NOT NULL COMMENT '所属项目ID',
        created_by BINARY(16) NOT NULL COMMENT '创建用户ID',
        title VARCHAR(255) NOT NULL COMMENT '知识标题',
        content MEDIUMTEXT NOT NULL COMMENT '经确认的项目事实正文',
        category VARCHAR(64) NOT NULL COMMENT '知识分类标识',
        tags JSON NULL COMMENT '标签JSON字符串数组',
        status VARCHAR(32) NOT NULL DEFAULT 'DRAFT' COMMENT '状态枚举：DRAFT/PENDING_REVIEW/APPROVED/REJECTED/ARCHIVED',
        submitted_by BINARY(16) NULL COMMENT '最近提交审核用户ID',
        submitted_at DATETIME (6) NULL COMMENT '最近提交审核时间（UTC）',
        reviewer_id BINARY(16) NULL COMMENT '最近审核用户ID',
        rejection_reason TEXT NULL COMMENT '最近驳回原因',
        reviewed_at DATETIME (6) NULL COMMENT '最近审核时间（UTC）',
        created_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
        updated_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
        KEY idx_memory_project (project_id, status, category, updated_at),
        KEY idx_memory_creator (created_by),
        KEY idx_memory_reviewer (reviewer_id),
        CONSTRAINT fk_memory_project FOREIGN KEY (project_id) REFERENCES projects (id),
        CONSTRAINT fk_memory_creator FOREIGN KEY (created_by) REFERENCES users (id),
        CONSTRAINT fk_memory_reviewer FOREIGN KEY (reviewer_id) REFERENCES users (id),
        CONSTRAINT fk_memory_submitter FOREIGN KEY (submitted_by) REFERENCES users (id)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '项目确认知识，MVP标签内嵌JSON';

CREATE TABLE IF NOT EXISTS
    memory_message_sources (
        memory_id BINARY(16) NOT NULL COMMENT 'Memory ID',
        message_id BINARY(16) NOT NULL COMMENT '作为知识依据的消息ID',
        PRIMARY KEY (memory_id, message_id),
        KEY idx_mms_message (message_id),
        CONSTRAINT fk_mms_memory FOREIGN KEY (memory_id) REFERENCES memories (id) ON DELETE CASCADE,
        CONSTRAINT fk_mms_message FOREIGN KEY (message_id) REFERENCES messages (id)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Memory与多条来源消息关系';

CREATE TABLE IF NOT EXISTS
    merge_requests (
        id BINARY(16) PRIMARY KEY COMMENT 'MR镜像UUIDv7',
        project_repository_id BINARY(16) NOT NULL COMMENT '所属项目仓库绑定ID',
        task_id BINARY(16) NULL COMMENT 'Owning Task',
        workspace_id BINARY(16) NULL COMMENT 'Source Workspace',
        provider VARCHAR(32) NOT NULL DEFAULT 'GITHUB' COMMENT '代码托管提供方枚举：GITHUB',
        provider_number BIGINT UNSIGNED NOT NULL COMMENT 'GitHub Pull Request编号',
        source_branch VARCHAR(512) NOT NULL COMMENT '源分支名',
        target_branch VARCHAR(512) NOT NULL COMMENT '目标分支名',
        head_commit VARCHAR(128) NOT NULL COMMENT '当前MR头提交SHA',
        mergeable TINYINT NULL COMMENT 'GitHub是否可合并；null表示GitHub尚未计算完成',
        mergeable_state VARCHAR(32) NULL COMMENT 'GitHub mergeable_state枚举：clean/dirty/blocked/behind/unstable/draft/unknown',
        base_sha VARCHAR(128) NULL COMMENT '合并基线（目标分支）提交SHA',
        title TEXT NULL COMMENT 'MR标题',
        html_url TEXT NULL COMMENT 'GitHub PR 链接',
        author_user_id BINARY(16) NULL COMMENT 'MR 创建用户',
        status VARCHAR(32) NOT NULL COMMENT 'MR状态枚举：OPEN/MERGED/CLOSED',
        provider_updated_at DATETIME (6) NULL COMMENT 'GitHub侧更新时间（UTC）',
        synced_at DATETIME (6) NOT NULL COMMENT '本地最近同步时间（UTC）',
        created_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '镜像创建时间（UTC）',
        UNIQUE KEY uk_mr_provider (project_repository_id, provider, provider_number),
        KEY idx_mr_repo (
            project_repository_id,
            status,
            provider_updated_at
        ),
        KEY idx_mr_repository_status_id (project_repository_id, status, id),
        CONSTRAINT fk_mr_repo FOREIGN KEY (project_repository_id) REFERENCES project_repositories (id)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'GitHub Pull Request业务镜像';

CREATE TABLE IF NOT EXISTS
    idempotency_records (
        id BINARY(16) PRIMARY KEY COMMENT '幂等记录UUIDv7',
        actor_user_id BINARY(16) NULL COMMENT '已认证调用用户ID；匿名请求为空',
        actor_fingerprint BINARY(32) NOT NULL COMMENT '调用者稳定指纹SHA-256',
        scope VARCHAR(255) NOT NULL COMMENT '幂等业务作用域，如HTTP方法与路由模板',
        idempotency_key VARCHAR(255) NOT NULL COMMENT '客户端Idempotency-Key原值',
        request_hash BINARY(32) NOT NULL COMMENT '规范化请求体SHA-256，用于检测同键不同请求',
        response_status SMALLINT UNSIGNED NULL COMMENT '首次请求HTTP响应状态码',
        response_body_redacted JSON NULL COMMENT '确认不含Token或Secret的脱敏响应JSON',
        resource_id BINARY(16) NULL COMMENT '首次请求创建或变更的资源UUID',
        created_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '首次请求时间（UTC）',
        expires_at DATETIME (6) NOT NULL COMMENT '幂等记录失效时间（UTC）',
        UNIQUE KEY uk_idempotency (actor_fingerprint, scope, idempotency_key),
        KEY idx_idem_user (actor_user_id),
        KEY idx_idem_expiry (expires_at),
        CONSTRAINT fk_idem_user FOREIGN KEY (actor_user_id) REFERENCES users (id)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '写接口幂等请求与脱敏响应缓存';

-- ============================================================================
-- ============================================================================

CREATE TABLE IF NOT EXISTS agents (
    id BINARY(16) PRIMARY KEY, team_id BINARY(16) NOT NULL, created_by BINARY(16) NULL,
    name VARCHAR(255) NOT NULL, role VARCHAR(32) NOT NULL,
    avatar TEXT NULL COMMENT 'Agent 头像URL', description TEXT NULL COMMENT 'Agent 用途描述',
    prompt TEXT NULL COMMENT 'Agent 系统提示词',
    review_reason TEXT NULL COMMENT '发布审核拒绝原因（Team Owner 填写；批准为空）',
    reviewed_by BINARY(16) NULL COMMENT '发布审核人（Team Owner）ID',
    reviewed_at DATETIME(6) NULL COMMENT '发布审核时间（UTC）',
    visibility VARCHAR(16) NOT NULL DEFAULT 'TEAM' COMMENT '可见性：PRIVATE（默认，仅创建者）/PENDING（提交发布审核）/TEAM（已批准共享，不可回私有）',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    is_default TINYINT NOT NULL DEFAULT 0 COMMENT '是否团队默认 Agent（每团队每角色至多一条）',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
    default_role VARCHAR(32) GENERATED ALWAYS AS (IF(is_default = 1, role, NULL)) STORED COMMENT '默认Agent角色（唯一约束生成列）',
    UNIQUE KEY uk_agents_team_default_role (team_id, default_role),
    KEY idx_agent_team(team_id,status), CONSTRAINT fk_agent_team FOREIGN KEY(team_id) REFERENCES teams(id),
    CONSTRAINT fk_agent_creator FOREIGN KEY(created_by) REFERENCES users(id),
    CONSTRAINT ck_agent_visibility CHECK(visibility IN ('TEAM','PENDING','PRIVATE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Team-scoped assignable Agent identities';

-- 聊天 Agent 执行记录；普通问答不需要 Task，子 Agent 关联父运行。
CREATE TABLE IF NOT EXISTS agent_runs (
    id BINARY(16) PRIMARY KEY,
    project_id BINARY(16) NOT NULL,
    group_id BINARY(16) NOT NULL,
    trigger_message_id BINARY(16) NULL,
    agent_id BINARY(16) NOT NULL,
    parent_run_id BINARY(16) NULL,
    task_id BINARY(16) NULL,
    created_by BINARY(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    instruction TEXT NOT NULL,
    result MEDIUMTEXT NULL,
    rounds INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    finished_at DATETIME(6) NULL,
    KEY idx_agent_run_group (group_id, created_at),
    KEY idx_agent_run_parent (parent_run_id),
    KEY idx_agent_run_task (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent loop 根运行及 subagent 运行';

CREATE TABLE IF NOT EXISTS group_agents (
    requirement_group_id BINARY(16) NOT NULL,
    agent_id BINARY(16) NOT NULL,
    joined_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (requirement_group_id, agent_id),
    KEY idx_ga_agent(agent_id),
    CONSTRAINT fk_ga_group FOREIGN KEY(requirement_group_id) REFERENCES requirement_groups(id) ON DELETE CASCADE,
    CONSTRAINT fk_ga_agent FOREIGN KEY(agent_id) REFERENCES agents(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='群聊 Agent 参与者（Agent 首次回群时自动加入）';

CREATE TABLE IF NOT EXISTS group_members (
    requirement_group_id BINARY(16) NOT NULL COMMENT '需求群ID',
    user_id BINARY(16) NOT NULL COMMENT '项目成员用户ID',
    joined_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '加入时间（UTC）',
    PRIMARY KEY (requirement_group_id, user_id),
    KEY idx_gm_user (user_id),
    CONSTRAINT fk_gm_group FOREIGN KEY (requirement_group_id) REFERENCES requirement_groups (id) ON DELETE CASCADE,
    CONSTRAINT fk_gm_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='需求群显式成员（用户）；PROJECT_MAIN 主群不写入，成员恒为全部项目成员';

CREATE TABLE IF NOT EXISTS tasks (
    id BINARY(16) PRIMARY KEY, project_id BINARY(16) NOT NULL, requirement_group_id BINARY(16) NOT NULL,
    trigger_message_id BINARY(16) NULL, workspace_id BINARY(16) NOT NULL,
    title VARCHAR(255) NOT NULL, display_code VARCHAR(32) NOT NULL COMMENT '项目内唯一展示编号，如 T-1024，创建后不可变',
    requirement TEXT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/SUCCEEDED/FAILED/CANCELLING/CANCELLED', created_by BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_task_display_code(project_id,display_code),
    KEY idx_task_trigger_message(trigger_message_id),
    KEY idx_task_project(project_id,status), KEY idx_task_project_workspace_updated(project_id,workspace_id,updated_at,id),
    KEY idx_task_group(requirement_group_id), KEY idx_task_workspace(workspace_id),
    CONSTRAINT fk_task_project FOREIGN KEY(project_id) REFERENCES projects(id),
    CONSTRAINT fk_task_group FOREIGN KEY(requirement_group_id) REFERENCES requirement_groups(id),
    CONSTRAINT fk_task_message FOREIGN KEY(trigger_message_id) REFERENCES messages(id),
    CONSTRAINT fk_task_creator FOREIGN KEY(created_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='User-visible requirement execution';

CREATE TABLE IF NOT EXISTS workspaces (
    id BINARY(16) PRIMARY KEY, project_id BINARY(16) NOT NULL,
    storage_key VARCHAR(512) NOT NULL COMMENT 'Opaque storage key, not a host path', status VARCHAR(32) NOT NULL DEFAULT 'PROVISIONING',
    write_lease_task_id BINARY(16) NULL COMMENT 'Current Workspace write lease owner Task',
    write_lease_token VARCHAR(64) NULL COMMENT 'Opaque write lease fencing token',
    write_lease_expires_at DATETIME(6) NULL COMMENT 'UTC write lease expiry',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_workspace_storage(storage_key),
    KEY idx_workspace_project_id (project_id, id),
    KEY idx_workspace_write_lease (write_lease_expires_at),
    CONSTRAINT fk_workspace_project FOREIGN KEY(project_id) REFERENCES projects(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Persistent project development workspace';

ALTER TABLE tasks ADD CONSTRAINT fk_task_workspace FOREIGN KEY(workspace_id) REFERENCES workspaces(id);
ALTER TABLE merge_requests
    ADD CONSTRAINT fk_mr_task FOREIGN KEY(task_id) REFERENCES tasks(id),
    ADD CONSTRAINT fk_mr_workspace FOREIGN KEY(workspace_id) REFERENCES workspaces(id);

CREATE TABLE IF NOT EXISTS workspace_repositories (
    workspace_id BINARY(16) NOT NULL, project_repository_id BINARY(16) NOT NULL, workspace_path VARCHAR(255) NOT NULL,
    base_commit VARCHAR(128) NULL, base_ref VARCHAR(512) NULL, source_branch VARCHAR(512) NOT NULL, head_commit VARCHAR(128) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY(workspace_id,project_repository_id), UNIQUE KEY uk_workspace_repository_path(workspace_id,workspace_path),
    CONSTRAINT fk_workspace_repo_workspace FOREIGN KEY(workspace_id) REFERENCES workspaces(id) ON DELETE CASCADE,
    CONSTRAINT fk_workspace_repo_repository FOREIGN KEY(project_repository_id) REFERENCES project_repositories(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Persistent repository worktrees in one Workspace';

CREATE TABLE IF NOT EXISTS agent_worker_executions (
    execution_id BINARY(16) PRIMARY KEY COMMENT 'Worker 工具执行ID',
    project_id BINARY(16) NOT NULL COMMENT '所属项目ID，用于隔离查询',
    task_id BINARY(16) NULL COMMENT '所属任务ID',
    agent_run_id BINARY(16) NOT NULL COMMENT '所属 Agent 运行ID',
    sandbox_id BINARY(16) NULL COMMENT 'Worker Sandbox ID',
    repository_id BINARY(16) NULL COMMENT '目标项目仓库ID',
    tool_name VARCHAR(64) NULL COMMENT 'Worker 工具名称',
    status VARCHAR(24) NULL COMMENT 'Worker 工具状态',
    exit_code INT NULL COMMENT '进程类工具退出码',
    failure_code VARCHAR(128) NULL,
    failure_reason VARCHAR(512) NULL,
    created_at DATETIME(6) NOT NULL COMMENT 'Worker 接收执行时间（UTC）',
    started_at DATETIME(6) NULL COMMENT 'Worker 开始执行时间（UTC）',
    finished_at DATETIME(6) NULL COMMENT 'Worker 完成时间（UTC）',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    KEY idx_run_worker_execution_run (project_id, agent_run_id, created_at),
    KEY idx_run_worker_execution_task (task_id, created_at),
    CONSTRAINT fk_run_worker_execution_project FOREIGN KEY (project_id) REFERENCES projects (id),
    CONSTRAINT fk_run_worker_execution_task FOREIGN KEY (task_id) REFERENCES tasks (id),
    CONSTRAINT fk_run_worker_execution_run FOREIGN KEY (agent_run_id) REFERENCES agent_runs (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AgentRun 与 Worker 工具执行的脱敏诊断关联';

CREATE TABLE IF NOT EXISTS
    events (
        id BINARY(16) PRIMARY KEY COMMENT '事件UUIDv7',
        project_id BINARY(16) NOT NULL COMMENT '所属项目ID',
        requirement_group_id BINARY(16) NULL COMMENT '可选关联需求群ID',
        sequence_no BIGINT UNSIGNED NOT NULL COMMENT '项目内单调递增事件序号，作为SSE游标',
        event_type VARCHAR(64) NOT NULL COMMENT '事件类型，如task.updated/merge-request.updated',
        resource_id VARCHAR(128) NULL COMMENT '关联资源ID字符串，如agentRunId',
        payload JSON NOT NULL COMMENT '脱敏事件载荷JSON，禁止包含凭证',
        created_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '产生时间（UTC）',
        UNIQUE KEY uk_event_seq (project_id, sequence_no),
        KEY idx_event_type (event_type, created_at),
        CONSTRAINT fk_event_project FOREIGN KEY (project_id) REFERENCES projects (id),
        CONSTRAINT fk_event_group FOREIGN KEY (requirement_group_id) REFERENCES requirement_groups (id)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '项目级实时事件存储，至少保留24小时';

-- 全新整库初始化时上方 merge_requests 建表不包含该列，由本 ALTER 补齐；
-- 已存在该列的库自动跳过，脚本整体可重复执行。
-- 说明：MySQL 不支持 ADD COLUMN IF NOT EXISTS，此处用 information_schema 探测 + PREPARE 动态执行实现幂等。
CREATE TABLE IF NOT EXISTS git_credential_grants (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    grant_id_hash VARCHAR(64) NOT NULL UNIQUE,
    team_id BINARY(16) NOT NULL,
    project_id BINARY(16) NOT NULL,
    installation_id BIGINT UNSIGNED NOT NULL,
    repository_full_name VARCHAR(255) NOT NULL,
    branch_name VARCHAR(255) NOT NULL,
    expected_head_commit VARCHAR(64) NOT NULL,
    purpose VARCHAR(16) NOT NULL DEFAULT 'PUSH',
    expires_at DATETIME(6) NOT NULL,
    is_used TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_grant_hash (grant_id_hash)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Git one-time credential grant';

ALTER TABLE messages ADD CONSTRAINT fk_msg_agent FOREIGN KEY (agent_id) REFERENCES agents (id);

CREATE TABLE IF NOT EXISTS
    notifications (
        id BINARY(16) PRIMARY KEY COMMENT '通知UUIDv7',
        recipient_user_id BINARY(16) NOT NULL COMMENT '接收通知的用户ID',
        project_id BINARY(16) NULL COMMENT '关联项目ID；系统级通知为空',
        requirement_group_id BINARY(16) NULL COMMENT '关联需求群ID；非群通知为空',
        kind VARCHAR(64) NOT NULL COMMENT '通知类型：MESSAGE_MENTION/MR_PENDING/INVITED/TEAM_JOINED/PROJECT_ADDED',
        title VARCHAR(255) NOT NULL COMMENT '一行通知标题',
        description TEXT NULL COMMENT '通知描述正文',
        resource_id VARCHAR(128) NULL COMMENT '关联资源ID字符串，如taskId/mrId',
        dedupe_key VARCHAR(256) NULL COMMENT '通知幂等键；MR_PENDING 为资源ID与状态组合',
        is_read TINYINT (1) NOT NULL DEFAULT 0 COMMENT '是否已读：0未读1已读',
        created_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '产生时间（UTC）',
        read_at DATETIME (6) NULL COMMENT '已读时间（UTC），未读为空',
        KEY idx_notif_user (recipient_user_id, is_read, created_at),
        KEY idx_notif_project (project_id),
        KEY idx_notif_group (requirement_group_id),
        UNIQUE KEY uk_notif_recipient_dedupe (recipient_user_id, dedupe_key),
        CONSTRAINT fk_notif_user FOREIGN KEY (recipient_user_id) REFERENCES users (id) ON DELETE CASCADE,
        CONSTRAINT fk_notif_project FOREIGN KEY (project_id) REFERENCES projects (id),
        CONSTRAINT fk_notif_group FOREIGN KEY (requirement_group_id) REFERENCES requirement_groups (id)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户通知中心';

-- GitHub Webhook 投递幂等记录：以 X-GitHub-Delivery 唯一防重，只存 body 摘要不存 Secret。
CREATE TABLE IF NOT EXISTS
    github_webhook_deliveries (
        id BINARY(16) PRIMARY KEY COMMENT '投递记录UUIDv7',
        provider_delivery_id VARCHAR(64) NOT NULL COMMENT 'X-GitHub-Delivery，GitHub 投递唯一标识',
        event_name VARCHAR(64) NOT NULL COMMENT 'X-GitHub-Event 事件名',
        action VARCHAR(64) NULL COMMENT 'payload 中的 action',
        provider_installation_id BIGINT UNSIGNED NULL COMMENT 'GitHub installation.id，可空',
        provider_repository_id BIGINT UNSIGNED NULL COMMENT 'GitHub repository.id，可空',
        payload_sha256 CHAR(64) NOT NULL COMMENT '原始 body SHA-256 摘要，不保存 Secret',
        status VARCHAR(32) NOT NULL COMMENT 'RECEIVED/PROCESSED/IGNORED/FAILED',
        failure_code VARCHAR(64) NULL COMMENT 'Webhook 最近失败码',
        attempt_count INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '同一 delivery 实际处理次数',
        received_at DATETIME(6) NOT NULL COMMENT '接收时间（UTC）',
        processed_at DATETIME(6) NULL COMMENT '处理完成时间（UTC）',
        updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '最近更新时间（UTC）',
        UNIQUE KEY uk_ghwd_delivery (provider_delivery_id),
        KEY idx_ghwd_status (status, received_at),
        KEY idx_ghwd_install (provider_installation_id, received_at)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'GitHub Webhook 投递幂等记录';

CREATE TABLE IF NOT EXISTS push_devices (
    id BINARY(16) PRIMARY KEY COMMENT '推送设备UUIDv7',
    user_id BINARY(16) NOT NULL COMMENT '设备所属用户ID',
    installation_id VARCHAR(128) NOT NULL COMMENT '客户端安装实例稳定ID',
    platform VARCHAR(16) NOT NULL COMMENT '平台：ANDROID/IOS',
    provider VARCHAR(16) NOT NULL DEFAULT 'FCM' COMMENT '推送提供方：FCM',
    token_hash CHAR(64) NOT NULL COMMENT '设备Token的SHA-256摘要',
    token_ciphertext TEXT NOT NULL COMMENT 'AES-GCM加密后的设备Token',
    active TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否接收离线推送',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_push_device_installation (user_id, installation_id),
    UNIQUE KEY uk_push_device_token (provider, token_hash),
    KEY idx_push_device_user_active (user_id, active),
    CONSTRAINT fk_push_device_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_push_device_platform CHECK (platform IN ('ANDROID', 'IOS')),
    CONSTRAINT chk_push_device_provider CHECK (provider IN ('FCM'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='移动端离线推送设备注册';

CREATE TABLE IF NOT EXISTS push_deliveries (
    id BINARY(16) PRIMARY KEY COMMENT '推送投递UUIDv7',
    notification_id BINARY(16) NOT NULL COMMENT '对应的持久通知ID',
    device_id BINARY(16) NOT NULL COMMENT '目标设备ID',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SENDING/SENT/FAILED',
    attempt_count INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '实际投递尝试次数',
    next_attempt_at DATETIME(6) NOT NULL COMMENT '下次允许重试时间（UTC）',
    provider_message_id VARCHAR(255) NULL COMMENT '提供方返回的真实消息ID',
    last_error_code VARCHAR(64) NULL COMMENT '稳定错误码',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    sent_at DATETIME(6) NULL COMMENT '提供方受理时间（UTC）',
    UNIQUE KEY uk_push_delivery_notification_device (notification_id, device_id),
    KEY idx_push_delivery_due (status, next_attempt_at),
    CONSTRAINT fk_push_delivery_notification FOREIGN KEY (notification_id) REFERENCES notifications(id) ON DELETE CASCADE,
    CONSTRAINT fk_push_delivery_device FOREIGN KEY (device_id) REFERENCES push_devices(id) ON DELETE CASCADE,
    CONSTRAINT chk_push_delivery_status CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='可恢复、幂等的移动端推送投递Outbox';

CREATE TABLE IF NOT EXISTS notification_events (
    id BINARY(16) PRIMARY KEY COMMENT '事件UUIDv7',
    recipient_user_id BINARY(16) NOT NULL COMMENT '接收通知的用户ID',
    sequence_no BIGINT NOT NULL COMMENT '用户内单调递增事件序号（SSE 游标）',
    notification_id BINARY(16) NULL COMMENT '关联通知ID',
    kind VARCHAR(32) NULL COMMENT '通知类型（TASK_COMPLETED/INVITED 等）',
    payload JSON NULL COMMENT '脱敏事件载荷',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '产生时间（UTC）',
    UNIQUE KEY uk_ne_recipient_seq (recipient_user_id, sequence_no),
    CONSTRAINT fk_ne_notification FOREIGN KEY (notification_id) REFERENCES notifications(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通知级 SSE 事件（用户维度游标）';

CREATE TABLE IF NOT EXISTS team_events (
    id BINARY(16) PRIMARY KEY COMMENT '事件UUIDv7',
    team_id BINARY(16) NOT NULL COMMENT '所属团队ID',
    sequence_no BIGINT NOT NULL COMMENT '团队内单调递增事件序号（SSE 游标）',
    event_type VARCHAR(64) NOT NULL COMMENT '事件类型（project.member.added/team.member.updated/activity.created）',
    resource_id VARCHAR(64) NULL COMMENT '关联资源ID（projectId 等）',
    payload JSON NULL COMMENT '脱敏事件载荷',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '产生时间（UTC）',
    UNIQUE KEY uk_te_team_seq (team_id, sequence_no),
    CONSTRAINT fk_te_team FOREIGN KEY (team_id) REFERENCES teams(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='团队级 SSE 事件（团队维度游标）';

-- ============================================================
-- 只反映 Coding 写过程中的累积工作树变更，永不作为已 commit/push/MR；
-- ============================================================

CREATE TABLE IF NOT EXISTS
    group_read_state (
        user_id BINARY(16) NOT NULL COMMENT '用户ID',
        group_id BINARY(16) NOT NULL COMMENT '需求群ID',
        last_read_sequence_no BIGINT UNSIGNED NULL COMMENT '已读游标（群内消息序号，NULL 视为 0）',
        updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
        PRIMARY KEY (user_id, group_id),
        KEY idx_grs_group (group_id),
        CONSTRAINT fk_grs_group FOREIGN KEY (group_id) REFERENCES requirement_groups (id)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '群成员已读游标（未读数=最新sequence-游标，排除本人）';

-- 全新整库初始化时上方 users 建表已含该列，此处仅服务已存在的库；已存在该列的库自动跳过。
CREATE TABLE IF NOT EXISTS
    user_group_preference (
        user_id BINARY(16) NOT NULL COMMENT '用户ID',
        group_id BINARY(16) NOT NULL COMMENT '需求群ID',
        pinned TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否置顶（0/1）',
        updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
        PRIMARY KEY (user_id, group_id),
        KEY idx_ugp_group (group_id)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户×群置顶偏好（跨设备同步）';
