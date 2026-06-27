package com.vca.store;

import com.vca.orchestrator.recorder.ConversationRecorder;
import com.vca.store.mapper.ConversationTurnMapper;
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
        return MyBatisSupport.mapper(conversationSqlSessionFactory);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(ConversationRecorder.class)
    MyBatisConversationRecorder conversationRecorder(ConversationTurnMapper conversationTurnMapper,
                                                     StoreProperties props) {
        return new MyBatisConversationRecorder(conversationTurnMapper, props.getQueueCapacity());
    }
}
