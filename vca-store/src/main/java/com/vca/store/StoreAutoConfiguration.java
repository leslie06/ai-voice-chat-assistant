package com.vca.store;

import com.vca.orchestrator.auth.TokenAuthenticator;
import com.vca.domain.spi.MusicUploadStore;
import com.vca.orchestrator.knowledge.KnowledgeStore;
import com.vca.orchestrator.memory.MemoryStore;
import com.vca.orchestrator.recorder.ConversationRecorder;
import com.vca.orchestrator.recorder.AudioRecordingService;
import com.vca.store.account.AccountRoutes;
import com.vca.store.account.ConversationService;
import com.vca.store.account.EmailSender;
import com.vca.store.account.LogEmailSender;
import com.vca.store.account.PasswordResetService;
import com.vca.store.account.SmtpEmailSender;
import com.vca.store.account.UserService;
import com.vca.store.account.UserTokenAuthenticator;
import com.vca.store.auth.TokenUtil;
import com.vca.store.eval.ConversationEvaluator;
import com.vca.store.eval.EvaluationRoute;
import com.vca.store.mapper.AppUserMapper;
import com.vca.store.mapper.ChatConversationMapper;
import com.vca.store.mapper.ChatMessageMapper;
import com.vca.store.embed.CachingEmbedder;
import com.vca.store.embed.DashScopeEmbedder;
import com.vca.store.embed.Embedder;
import com.vca.store.knowledge.KnowledgeRoutes;
import com.vca.store.knowledge.KnowledgeService;
import com.vca.store.knowledge.MyBatisKnowledgeStore;
import com.vca.store.mapper.ConversationTurnMapper;
import com.vca.store.mapper.ConversationRecordingMapper;
import com.vca.store.mapper.EvaluationMapper;
import com.vca.store.mapper.KnowledgeChunkMapper;
import com.vca.store.mapper.KnowledgeDocMapper;
import com.vca.store.mapper.UserMemoryMapper;
import com.vca.store.mapper.UserMusicPlayMapper;
import com.vca.store.mapper.UserMusicUploadMapper;
import com.vca.store.memory.MyBatisMemoryStore;
import com.vca.store.music.MusicPlayRoutes;
import com.vca.store.music.MusicPlayService;
import com.vca.store.music.MyBatisMusicUploadStore;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.util.StringUtils;

/**
 * 对话落库自动装配(MySQL + MyBatis-Plus)。仅当 {@code vca.store.enabled=true} 时生效, 自带独立连接池、
 * <b>手动</b>装配 {@link SqlSessionFactory}/Mapper(不用 spring-boot3-starter 的自动装配, 规避其在 Boot 4
 * 上的兼容风险), 并幂等建表 —— 因此<b>不依赖也不干扰</b>宿主的 DataSource 自动装配(本项目其余部分不用数据库)。
 *
 * <p>装配出一个 {@link ConversationRecorder} Bean, 接入层据此注入每路 {@code ConversationSession};
 * 关闭({@code enabled=false}, 默认)时本类不生效, 编排层退回 {@link ConversationRecorder#NOOP}。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "vca.store", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(StoreProperties.class)
public class StoreAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(StoreAutoConfiguration.class);

    /** 专供对话落库的独立连接池; 启动时跑 schema.sql(CREATE TABLE IF NOT EXISTS, 幂等)。 */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(name = "conversationStoreDataSource")
    HikariDataSource conversationStoreDataSource(StoreProperties props) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(props.getUrl());
        cfg.setUsername(props.getUsername());
        cfg.setPassword(props.getPassword());
        if (StringUtils.hasText(props.getDriverClassName())) {
            cfg.setDriverClassName(props.getDriverClassName());
        }
        cfg.setPoolName("vca-store");
        // 池大小可配。别再按"只有后台落库"来估 —— 账号鉴权、会话历史、向量记忆、RAG、音乐目录
        // 都共用这个池, 打满时的报错会出现在离根因很远的地方(例如登录失败), 极难定位。
        cfg.setMaximumPoolSize(props.getMaxPoolSize());
        // 元数据锁等待上限。MySQL 的 lock_wait_timeout 默认 31536000 秒(365 天), 启动时的
        // ALTER TABLE 一旦撞上别人握着的表锁就会无限等待 —— 服务卡在启动中途, 日志停在
        // "Start completed" 之后再无一行, 既不报错也不退出。设成秒级后同样的情况会明确失败。
        if (props.getLockWaitTimeoutSeconds() > 0) {
            cfg.setConnectionInitSql("SET SESSION lock_wait_timeout = " + props.getLockWaitTimeoutSeconds());
        }
        // 泄漏检测<b>刻意不在这里开</b>, 理由见下方 DDL 之后。
        HikariDataSource ds = new HikariDataSource(cfg);

        try {
            DatabasePopulatorUtils.execute(
                    new ResourceDatabasePopulator(new ClassPathResource("com/vca/store/schema.sql")), ds);
            // 轻量迁移: CREATE TABLE IF NOT EXISTS 不会给已存在的表补新列, 故对升级后新增的列做幂等补齐
            // (MySQL 不支持 ADD COLUMN IF NOT EXISTS, 用 information_schema 判存在再 ALTER)。
            migrate(ds);
        } catch (RuntimeException e) {
            ds.close();
            throw new IllegalStateException(
                    "对话落库建表/迁移失败: " + e.getMessage()
                            + " —— 若是 'Lock wait timeout exceeded', 说明有别的连接握着表的元数据锁"
                            + "(常见于旧进程没退干净, 或某个客户端会话开着事务没提交)。"
                            + "用 SHOW FULL PROCESSLIST 找到并 KILL 掉那个连接; "
                            + "临时绕过可设 vca.store.enabled=false 先让服务起来。", e);
        }

        // 建表与补列跑完之后再开泄漏检测。这段 DDL 在 main 线程上一口气占着同一个连接跑完,
        // 本来就可能超过阈值(实测整个启动约 20s), 在建池时就开会让<b>每次启动都刷一条假的
        // "Apparent connection leak"</b> —— 假警报一多, 真出现泄漏时就没人当回事了。
        if (props.getLeakDetectionMs() > 0) {
            // 借出超时未还即打印借用方调用栈 —— 查"连接被谁占着"唯一有效的手段, 只告警不影响业务
            ds.setLeakDetectionThreshold(props.getLeakDetectionMs());
        }
        log.info("对话落库已启用(数据飞轮, MySQL+MyBatis-Plus): url={}, maxPoolSize={}, 泄漏告警阈值={}ms",
                props.getUrl(), props.getMaxPoolSize(), props.getLeakDetectionMs());
        return ds;
    }

    /** 幂等列迁移: 给老库补上升级后新增的列(向量化记忆的 embedding 列)。 */
    private void migrate(HikariDataSource ds) {
        addColumnIfMissing(ds, "user_memory", "embedding", "BLOB NULL");
        // 多步 Agent 指标(P3): 给已存在的 conversation_turn 补列
        addColumnIfMissing(ds, "conversation_turn", "agent_steps", "INT NULL");
        addColumnIfMissing(ds, "conversation_turn", "agent_replans", "INT NULL");
        // 会话逻辑删除: 老库补列。默认 0, 已有会话全部视为未删除。
        addColumnIfMissing(ds, "chat_conversation", "deleted",
                "TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0正常 1已删'");
        addColumnIfMissing(ds, "app_user", "register_ip", "VARCHAR(45) NULL COMMENT '注册 IP'");
        addColumnIfMissing(ds, "app_user", "last_login_at",
                "DATETIME NULL COMMENT '最近一次成功登录时间'");
        addColumnIfMissing(ds, "conversation_recording", "oss_bucket", "VARCHAR(128) NULL COMMENT 'OSS Bucket'");
        addColumnIfMissing(ds, "conversation_recording", "conversation_file",
                "VARCHAR(512) NULL COMMENT '按回合合并的完整对话 OSS Object Key'");
        addColumnIfMissing(ds, "conversation_recording", "conversation_bytes", "BIGINT NOT NULL DEFAULT 0");
        addColumnIfMissing(ds, "user_music_upload", "status",
                "VARCHAR(16) NOT NULL DEFAULT 'pending' COMMENT 'pending/reviewing/approved/rejected/review_error'");
        addColumnIfMissing(ds, "user_music_upload", "text_labels", "VARCHAR(512) NULL");
        addColumnIfMissing(ds, "user_music_upload", "text_reason", "VARCHAR(1024) NULL");
        addColumnIfMissing(ds, "user_music_upload", "audio_task_id", "VARCHAR(128) NULL");
        addColumnIfMissing(ds, "user_music_upload", "audio_risk_level", "VARCHAR(16) NULL");
        addColumnIfMissing(ds, "user_music_upload", "moderation_labels", "VARCHAR(1024) NULL");
        addColumnIfMissing(ds, "user_music_upload", "moderation_reason", "VARCHAR(2048) NULL");
        addColumnIfMissing(ds, "user_music_upload", "reviewed_at", "DATETIME NULL");
    }

    private void addColumnIfMissing(HikariDataSource ds, String table, String column, String ddl) {
        try (java.sql.Connection c = ds.getConnection()) {
            boolean exists;
            try (java.sql.PreparedStatement ps = c.prepareStatement(
                    "SELECT 1 FROM information_schema.COLUMNS "
                            + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?")) {
                ps.setString(1, table);
                ps.setString(2, column);
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    exists = rs.next();
                }
            }
            if (!exists) {
                try (java.sql.Statement st = c.createStatement()) {
                    st.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + ddl);
                }
                log.info("迁移: 给表 {} 补列 {} {}", table, column, ddl);
            }
        } catch (Exception e) {
            log.warn("列迁移失败({}.{}), 可手动执行 ALTER TABLE {} ADD COLUMN {} {}: {}",
                    table, column, table, column, ddl, e.toString());
        }
    }

    @Bean
    @ConditionalOnMissingBean
    SqlSessionFactory conversationSqlSessionFactory(HikariDataSource conversationStoreDataSource) {
        return MyBatisSupport.sqlSessionFactory(conversationStoreDataSource);
    }

    @Bean
    @ConditionalOnMissingBean
    ConversationTurnMapper conversationTurnMapper(SqlSessionFactory conversationSqlSessionFactory) {
        return MyBatisSupport.mapper(conversationSqlSessionFactory, ConversationTurnMapper.class);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(ConversationRecorder.class)
    MyBatisConversationRecorder conversationRecorder(ConversationTurnMapper conversationTurnMapper,
                                                     StoreProperties props) {
        return new MyBatisConversationRecorder(conversationTurnMapper, props.getQueueCapacity());
    }

    @Bean
    @ConditionalOnMissingBean
    ConversationRecordingMapper conversationRecordingMapper(SqlSessionFactory conversationSqlSessionFactory) {
        return MyBatisSupport.mapper(conversationSqlSessionFactory, ConversationRecordingMapper.class);
    }

    /** OSS 原始双轨 + 完整对话 WAV。只有显式开启时才注册，关闭时接入层自动使用 NOOP。 */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(AudioRecordingService.class)
    @ConditionalOnProperty(prefix = "vca.store", name = "audio-recording-enabled", havingValue = "true")
    OssAudioRecordingService audioRecordingService(StoreProperties props,
                                                     ConversationRecordingMapper conversationRecordingMapper,
                                                     ChatConversationMapper chatConversationMapper) {
        requireText(props.getOssEndpoint(), "VCA_OSS_ENDPOINT");
        requireText(props.getOssBucket(), "VCA_OSS_BUCKET");
        requireText(props.getOssAccessKeyId(), "VCA_OSS_ACCESS_KEY_ID");
        requireText(props.getOssAccessKeySecret(), "VCA_OSS_ACCESS_KEY_SECRET");
        com.aliyun.oss.OSS client = new com.aliyun.oss.OSSClientBuilder().build(
                props.getOssEndpoint(), props.getOssAccessKeyId(), props.getOssAccessKeySecret());
        log.info("语音双轨录音已启用: oss://{}/{}", props.getOssBucket(), props.getOssPrefix());
        return new OssAudioRecordingService(client, props.getOssBucket(), props.getOssPrefix(),
                props.getOssPartSizeBytes(), props.getAudioRecordingQueueCapacity(),
                conversationRecordingMapper, chatConversationMapper);
    }

    private static void requireText(String value, String envName) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException("启用 OSS 录音时必须配置 " + envName);
        }
    }

    // ---- 评测查询(P2-A): 只读, 暴露 GET /eval/report ----

    @Bean
    @ConditionalOnMissingBean
    EvaluationMapper evaluationMapper(SqlSessionFactory conversationSqlSessionFactory) {
        return MyBatisSupport.mapper(conversationSqlSessionFactory, EvaluationMapper.class);
    }

    @Bean
    @ConditionalOnMissingBean
    ConversationEvaluator conversationEvaluator(EvaluationMapper evaluationMapper) {
        return new ConversationEvaluator(evaluationMapper);
    }

    /** 把评测报告挂到 GET /eval/report(WebFlux 自动接入所有 RouterFunction Bean)。 */
    @Bean
    org.springframework.web.reactive.function.server.RouterFunction<
            org.springframework.web.reactive.function.server.ServerResponse> evaluationRoute(
            ConversationEvaluator conversationEvaluator) {
        return EvaluationRoute.create(conversationEvaluator);
    }

    // ---- 账号 + 服务端会话(类 ChatGPT, 按用户隔离): 暴露 /api/** ----

    @Bean
    @ConditionalOnMissingBean
    AppUserMapper appUserMapper(SqlSessionFactory conversationSqlSessionFactory) {
        return MyBatisSupport.mapper(conversationSqlSessionFactory, AppUserMapper.class);
    }

    @Bean
    @ConditionalOnMissingBean
    ChatConversationMapper chatConversationMapper(SqlSessionFactory conversationSqlSessionFactory) {
        return MyBatisSupport.mapper(conversationSqlSessionFactory, ChatConversationMapper.class);
    }

    @Bean
    @ConditionalOnMissingBean
    ChatMessageMapper chatMessageMapper(SqlSessionFactory conversationSqlSessionFactory) {
        return MyBatisSupport.mapper(conversationSqlSessionFactory, ChatMessageMapper.class);
    }

    @Bean
    @ConditionalOnMissingBean
    UserMusicPlayMapper userMusicPlayMapper(SqlSessionFactory conversationSqlSessionFactory) {
        return MyBatisSupport.mapper(conversationSqlSessionFactory, UserMusicPlayMapper.class);
    }

    @Bean
    @ConditionalOnMissingBean
    MusicPlayService musicPlayService(UserMusicPlayMapper userMusicPlayMapper) {
        return new MusicPlayService(userMusicPlayMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    UserMusicUploadMapper userMusicUploadMapper(SqlSessionFactory conversationSqlSessionFactory) {
        return MyBatisSupport.mapper(conversationSqlSessionFactory, UserMusicUploadMapper.class);
    }

    @Bean
    @ConditionalOnMissingBean(MusicUploadStore.class)
    MusicUploadStore musicUploadStore(UserMusicUploadMapper mapper) {
        return new MyBatisMusicUploadStore(mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    TokenUtil tokenUtil(StoreProperties props) {
        return new TokenUtil(props.getTokenSecret());
    }

    @Bean
    @ConditionalOnMissingBean
    UserService userService(AppUserMapper appUserMapper, TokenUtil tokenUtil) {
        return new UserService(appUserMapper, tokenUtil);
    }

    /** 用户令牌校验器: 接入层(WS)据此用同一套登录令牌鉴权, 不再需要独立的共享 token。 */
    @Bean
    @ConditionalOnMissingBean(TokenAuthenticator.class)
    TokenAuthenticator tokenAuthenticator(UserService userService) {
        return new UserTokenAuthenticator(userService);
    }

    @Bean
    @ConditionalOnMissingBean
    ConversationService conversationService(ChatConversationMapper chatConversationMapper,
                                            ChatMessageMapper chatMessageMapper) {
        return new ConversationService(chatConversationMapper, chatMessageMapper);
    }

    // ---- Embedding(向量化长期记忆 + RAG 共用): 配了 key 才建 embedder, 否则功能降级 ----

    /** query embedding 缓存条数上限。查询短、复用率高(同一句在记忆与 RAG 两路各用一次), 几百条足够。 */
    private static final int EMBEDDING_CACHE_ENTRIES = 512;

    /**
     * 文本向量化器。仅当 {@code embedding-enabled} 且配了 key 时建; 否则返回 null(不注册 Bean)——
     * 下游经 {@code ObjectProvider} 取不到即降级: 记忆退回关键词级、RAG 检索返回空。
     */
    @Bean
    @ConditionalOnMissingBean(Embedder.class)
    Embedder embedder(StoreProperties props) {
        if (!props.isEmbeddingEnabled() || props.getEmbeddingKey() == null || props.getEmbeddingKey().isBlank()) {
            log.info("Embedding 未启用(无 key), 长期记忆退回关键词级、RAG 检索关闭");
            return null;
        }
        log.info("Embedding 已启用: model={}, dim={}", props.getEmbeddingModel(), props.getEmbeddingDim());
        // 套一层缓存/在途去重: 同一回合里长期记忆召回与 RAG 检索 embed 的是同一句用户输入,
        // 不收口就是同时打两次一模一样的请求(多一份配额、更易吃 429)。见 CachingEmbedder。
        return new CachingEmbedder(
                new DashScopeEmbedder(props.getEmbeddingBaseUrl(), props.getEmbeddingKey(),
                        props.getEmbeddingModel(), props.getEmbeddingDim(), props.getEmbeddingProxy()),
                EMBEDDING_CACHE_ENTRIES);
    }

    // ---- 长期记忆(跨会话个性化): remember 工具写入, 每轮对话回灌上下文(语义召回) ----

    @Bean
    @ConditionalOnMissingBean
    UserMemoryMapper userMemoryMapper(SqlSessionFactory conversationSqlSessionFactory) {
        return MyBatisSupport.mapper(conversationSqlSessionFactory, UserMemoryMapper.class);
    }

    @Bean
    @ConditionalOnMissingBean(MemoryStore.class)
    MemoryStore memoryStore(UserMemoryMapper userMemoryMapper, ObjectProvider<Embedder> embedder) {
        return new MyBatisMemoryStore(userMemoryMapper, embedder.getIfAvailable());
    }

    // ---- RAG 知识库: 文档上传/切块/向量入库(/api/knowledge) + 检索(KnowledgeStore 端口) ----

    @Bean
    @ConditionalOnMissingBean
    KnowledgeDocMapper knowledgeDocMapper(SqlSessionFactory conversationSqlSessionFactory) {
        return MyBatisSupport.mapper(conversationSqlSessionFactory, KnowledgeDocMapper.class);
    }

    @Bean
    @ConditionalOnMissingBean
    KnowledgeChunkMapper knowledgeChunkMapper(SqlSessionFactory conversationSqlSessionFactory) {
        return MyBatisSupport.mapper(conversationSqlSessionFactory, KnowledgeChunkMapper.class);
    }

    @Bean
    @ConditionalOnMissingBean
    KnowledgeService knowledgeService(KnowledgeDocMapper knowledgeDocMapper, KnowledgeChunkMapper knowledgeChunkMapper,
                                      ObjectProvider<Embedder> embedder) {
        return new KnowledgeService(knowledgeDocMapper, knowledgeChunkMapper, embedder.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean(KnowledgeStore.class)
    KnowledgeStore knowledgeStore(KnowledgeChunkMapper knowledgeChunkMapper, ObjectProvider<Embedder> embedder) {
        return new MyBatisKnowledgeStore(knowledgeChunkMapper, embedder.getIfAvailable());
    }

    /** 知识库 REST(/api/knowledge*)挂成 RouterFunction Bean。 */
    @Bean
    org.springframework.web.reactive.function.server.RouterFunction<
            org.springframework.web.reactive.function.server.ServerResponse> knowledgeRoutes(
            UserService userService, KnowledgeService knowledgeService) {
        return KnowledgeRoutes.create(userService, knowledgeService);
    }

    /** 邮件发送器: 配了 SMTP host 用真实发送, 否则回退打日志(配合 mail-dev-echo 联调)。 */
    @Bean
    @ConditionalOnMissingBean(EmailSender.class)
    EmailSender emailSender(StoreProperties props) {
        if (StringUtils.hasText(props.getMailHost())) {
            log.info("邮件: 启用 SMTP(host={}, port={})", props.getMailHost(), props.getMailPort());
            return new SmtpEmailSender(props.getMailHost(), props.getMailPort(), props.getMailUsername(),
                    props.getMailPassword(), props.getMailFrom(), props.isMailSsl(), props.isMailStarttls(),
                    props.getMailProxyHost(), props.getMailProxyPort());
        }
        log.info("邮件: 未配置 SMTP, 使用日志发送器(重置令牌打日志; 配合 vca.store.mail-dev-echo 联调)");
        return new LogEmailSender();
    }

    @Bean
    @ConditionalOnMissingBean
    PasswordResetService passwordResetService(UserService userService, EmailSender emailSender, StoreProperties props) {
        return new PasswordResetService(userService, emailSender, props.getBaseUrl(),
                props.getResetTtlSeconds(), props.isMailDevEcho());
    }

    /** 账号/会话 REST(/api/**)挂成 RouterFunction Bean。 */
    @Bean
    org.springframework.web.reactive.function.server.RouterFunction<
            org.springframework.web.reactive.function.server.ServerResponse> accountRoutes(
            UserService userService, ConversationService conversationService,
            PasswordResetService passwordResetService) {
        return AccountRoutes.create(userService, conversationService, passwordResetService);
    }

    /** 用户听歌统计 REST：实际开始播放后累计该用户、该歌曲的次数。 */
    @Bean
    org.springframework.web.reactive.function.server.RouterFunction<
            org.springframework.web.reactive.function.server.ServerResponse> musicPlayRoutes(
            UserService userService, MusicPlayService musicPlayService) {
        return MusicPlayRoutes.create(userService, musicPlayService);
    }
}
