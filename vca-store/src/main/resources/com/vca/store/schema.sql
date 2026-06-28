-- 对话存档表(数据飞轮 P1)。MySQL DDL, 启动时 CREATE TABLE IF NOT EXISTS 幂等建表。
-- 工具参数等结构化字段一律 TEXT 存 JSON 字符串(不依赖 MySQL JSON 类型, 便于迁移/检索)。
CREATE TABLE IF NOT EXISTS conversation_turn (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    session_id     VARCHAR(128) NOT NULL,
    turn_index     INT          NOT NULL,
    mode           VARCHAR(32)  NOT NULL COMMENT 'pipeline | s2s | s2s-persistent',
    user_text      TEXT         COMMENT '本轮用户说了什么',
    assistant_text TEXT         COMMENT '机器人本轮回复',
    total_ms       BIGINT       COMMENT '整轮耗时(ms), 可空: S2S 路径无逐轮计时',
    outcome        VARCHAR(16)  COMMENT 'complete | interrupted | error',
    created_at     DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_turn_session (session_id, turn_index),   -- 按会话回溯整段对话
    KEY idx_turn_created (created_at)                -- 按时间做评测切片
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '对话存档(数据飞轮)';

-- 用户账号: 密码用 PBKDF2 加盐哈希(不存明文); 邮箱用于找回/修改密码。
CREATE TABLE IF NOT EXISTS app_user (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    username   VARCHAR(64)  NOT NULL,
    email      VARCHAR(128) NOT NULL,
    pass_salt  VARCHAR(64)  NOT NULL,
    pass_hash  VARCHAR(128) NOT NULL,
    created_at DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_name (username),
    UNIQUE KEY uk_user_email (email)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '用户账号';

-- 每个用户的会话(类 ChatGPT 左侧列表)。按 user_id 隔离。
CREATE TABLE IF NOT EXISTS chat_conversation (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    user_id    BIGINT       NOT NULL,
    title      VARCHAR(255),
    created_at DATETIME     NOT NULL,
    updated_at DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_conv_user (user_id, updated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '用户会话';

-- 会话内的消息(展示用)。role: user | bot | music。
CREATE TABLE IF NOT EXISTS chat_message (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    conversation_id BIGINT      NOT NULL,
    role            VARCHAR(16) NOT NULL COMMENT 'user | bot | music',
    content         TEXT,
    created_at      DATETIME    NOT NULL,
    PRIMARY KEY (id),
    KEY idx_msg_conv (conversation_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '会话消息';
