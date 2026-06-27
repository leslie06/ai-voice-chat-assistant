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
