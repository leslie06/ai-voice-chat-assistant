package com.vca.store;

import com.vca.orchestrator.auth.TokenAuthenticator;
import com.vca.orchestrator.recorder.ConversationRecorder;
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
import com.vca.store.mapper.ConversationTurnMapper;
import com.vca.store.mapper.EvaluationMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
        cfg.setMaximumPoolSize(4);   // 落库是低频后台写, 小池足够
        HikariDataSource ds = new HikariDataSource(cfg);

        DatabasePopulatorUtils.execute(
                new ResourceDatabasePopulator(new ClassPathResource("com/vca/store/schema.sql")), ds);
        log.info("对话落库已启用(数据飞轮, MySQL+MyBatis-Plus): url={}", props.getUrl());
        return ds;
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
}
