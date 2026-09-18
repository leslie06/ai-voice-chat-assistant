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
    agent_steps    INT          COMMENT '多步 Agent 执行步数(计划+反思补步); 非 Agent 回合为 NULL',
    agent_replans  INT          COMMENT '多步 Agent 反思补做的额外步数; 非 Agent 回合为 NULL',
    created_at     DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_turn_session (session_id, turn_index),   -- 按会话回溯整段对话
    KEY idx_turn_created (created_at)                -- 按时间做评测切片
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '对话存档(数据飞轮)';

-- 用户账号: username 保存中国大陆手机号；密码用 PBKDF2 加盐哈希；邮箱用于找回/修改密码。
CREATE TABLE IF NOT EXISTS app_user (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    username   VARCHAR(64)  NOT NULL,
    email      VARCHAR(128) NOT NULL,
    register_ip VARCHAR(45)          COMMENT '注册 IP',
    pass_salt  VARCHAR(64)  NOT NULL,
    pass_hash  VARCHAR(128) NOT NULL,
    last_login_at DATETIME           COMMENT '最近一次成功登录时间',
    member_tier VARCHAR(16) NOT NULL DEFAULT 'free' COMMENT '会员等级 free/vip',
    member_expires_at DATETIME       COMMENT '会员到期时间, NULL=不过期',
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
    -- 逻辑删除: 0=正常 1=已删。删除只置位不删行, 误删可恢复(消息也一并保留)。
    deleted    TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_conv_user (user_id, deleted, updated_at)
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

-- 用户听歌统计: 每个用户每首歌一行；每次真正开始一轮播放时累计 play_count。
CREATE TABLE IF NOT EXISTS user_music_play (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    user_id           BIGINT       NOT NULL,
    song_key          CHAR(64)     NOT NULL COMMENT '歌名+歌手归一化后的 SHA-256',
    title             VARCHAR(255) NOT NULL,
    artist            VARCHAR(255) NOT NULL,
    duration_sec      INT          NOT NULL DEFAULT 0,
    play_count        BIGINT       NOT NULL DEFAULT 1,
    first_played_at   DATETIME     NOT NULL,
    last_played_at    DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_music_user_song (user_id, song_key),
    KEY idx_music_user_last (user_id, last_played_at),
    KEY idx_music_popular (play_count, last_played_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '用户听歌次数统计';

-- 用户上传曲库：先审核，审核通过后向所有登录用户公开。
CREATE TABLE IF NOT EXISTS user_music_upload (
    id                VARCHAR(36)  NOT NULL,
    user_id           BIGINT       NOT NULL,
    title             VARCHAR(255) NOT NULL,
    artist            VARCHAR(255) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    audio_object_key  VARCHAR(512) NOT NULL,
    lyrics_object_key VARCHAR(512),
    audio_bytes       BIGINT       NOT NULL,
    lyrics_bytes      BIGINT       NOT NULL DEFAULT 0,
    rights_confirmed  TINYINT(1)   NOT NULL DEFAULT 1,
    status            VARCHAR(16)  NOT NULL DEFAULT 'pending',
    text_labels       VARCHAR(512),
    text_reason       VARCHAR(1024),
    audio_task_id     VARCHAR(128),
    audio_risk_level  VARCHAR(16),
    moderation_labels VARCHAR(1024),
    moderation_reason VARCHAR(2048),
    reviewed_at       DATETIME,
    created_at        DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_music_upload_user (user_id, created_at),
    KEY idx_music_upload_review (status, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '用户上传歌曲';

-- 用户的声音复刻音色。音色本体在厂商侧(阿里云百炼), 这里只存归属与元数据:
-- 厂商的音色表是账号级的、不分用户, 不落库就拦不住 A 拿 B 的 voice_id(它在前端是明文);
-- 另外厂商会自动删除"过去 1 年未用于任何合成"的音色, last_used_at 就是为提前提醒留的。
CREATE TABLE IF NOT EXISTS user_voice_clone (
    voice_id         VARCHAR(160) NOT NULL COMMENT '厂商音色 id, 形如 qwen-audio-3.0-tts-flash-u1a-<32位hex>',
    user_id          BIGINT       NOT NULL,
    name             VARCHAR(64)  NOT NULL COMMENT '用户起的显示名',
    target_model     VARCHAR(64)  NOT NULL COMMENT '创建时绑定的合成模型, 合成必须用同一个',
    sample_seconds   INT          NOT NULL DEFAULT 0,
    rights_confirmed TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '用户确认是本人声音',
    status           VARCHAR(16)  NOT NULL DEFAULT 'active' COMMENT 'active / invalid',
    created_at       DATETIME     NOT NULL,
    last_used_at     DATETIME,
    PRIMARY KEY (voice_id),
    KEY idx_voice_clone_user (user_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '用户声音复刻音色';

-- 一次 WebSocket 语音通话的原始双轨 + 完整对话录音。音频直接上传 OSS。
CREATE TABLE IF NOT EXISTS conversation_recording (
    id                    VARCHAR(36)  NOT NULL,
    user_id               BIGINT       NOT NULL,
    conversation_id       BIGINT,
    session_id            VARCHAR(128) NOT NULL,
    oss_bucket            VARCHAR(128) NOT NULL,
    user_file             VARCHAR(512) NOT NULL COMMENT '用户音轨 OSS Object Key',
    assistant_file        VARCHAR(512) NOT NULL COMMENT '客服音轨 OSS Object Key',
    conversation_file     VARCHAR(512) COMMENT '按回合合并的完整对话 OSS Object Key',
    user_sample_rate      INT,
    assistant_sample_rate INT,
    user_bytes            BIGINT       NOT NULL DEFAULT 0,
    assistant_bytes       BIGINT       NOT NULL DEFAULT 0,
    conversation_bytes    BIGINT       NOT NULL DEFAULT 0,
    duration_ms           BIGINT       NOT NULL DEFAULT 0,
    status                VARCHAR(16)  NOT NULL COMMENT 'recording | complete | partial | error',
    started_at            DATETIME     NOT NULL,
    ended_at              DATETIME,
    PRIMARY KEY (id),
    KEY idx_recording_user (user_id, started_at),
    KEY idx_recording_conv (conversation_id, started_at),
    KEY idx_recording_session (session_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '语音通话录音元数据';

-- 用户长期记忆(跨会话个性化): 模型经 remember 工具写入, 每次对话作为上下文回灌。
CREATE TABLE IF NOT EXISTS user_memory (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    user_id    BIGINT       NOT NULL,
    content    VARCHAR(512) NOT NULL,
    embedding  BLOB         COMMENT '内容向量(小端 float32); 旧行/未启用 embedding 时为 NULL',
    created_at DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_mem_user (user_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '用户长期记忆';

-- RAG 知识库: 用户上传的文档(一条/文件), 按 user_id 隔离。
CREATE TABLE IF NOT EXISTS knowledge_doc (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    user_id    BIGINT       NOT NULL,
    title      VARCHAR(255) NOT NULL,
    created_at DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_doc_user (user_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT 'RAG 文档';

-- 文档切块 + 向量, RAG 检索的最小单位。
CREATE TABLE IF NOT EXISTS knowledge_chunk (
    id         BIGINT   NOT NULL AUTO_INCREMENT,
    user_id    BIGINT   NOT NULL,
    doc_id     BIGINT   NOT NULL,
    ordinal    INT      NOT NULL COMMENT '在文档内的序号',
    content    TEXT     NOT NULL,
    embedding  BLOB     COMMENT '片段向量(小端 float32)',
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    KEY idx_chunk_user (user_id, id),
    KEY idx_chunk_doc (doc_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT 'RAG 文档切块';

-- 电话线索: 客户在通话里留下的预约/留资信息。按 owner_id(商家账号)归属, 与知识库同一个账号体系。
-- call_id 与 conversation_turn.session_id、录音文件名一致, 凭它能回听这通电话。
CREATE TABLE IF NOT EXISTS phone_lead (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    call_id        VARCHAR(64)  NOT NULL,
    owner_id       BIGINT       NOT NULL COMMENT '商家账号 id',
    peer_number    VARCHAR(32)  COMMENT '来电号码; 线路没送号时为空',
    called_number  VARCHAR(32)  COMMENT '客户拨打的号码(商家接入号)',
    name           VARCHAR(64)  COMMENT '客户称呼',
    phone          VARCHAR(32)  COMMENT '客户留的回电号码',
    intent         VARCHAR(255) COMMENT '意向/想做的项目',
    preferred_time VARCHAR(128) COMMENT '期望到店或回电时间(客户原话)',
    note           VARCHAR(512) COMMENT '备注',
    created_at     DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_lead_owner (owner_id, id),
    KEY idx_lead_call (call_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '电话线索';
