package com.vca.store;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.vca.store.mapper.AppUserMapper;
import com.vca.store.mapper.ChatConversationMapper;
import com.vca.store.mapper.ChatMessageMapper;
import com.vca.store.mapper.ConversationTurnMapper;
import com.vca.store.mapper.ConversationRecordingMapper;
import com.vca.store.mapper.EvaluationMapper;
import com.vca.store.mapper.KnowledgeChunkMapper;
import com.vca.store.mapper.KnowledgeDocMapper;
import com.vca.store.mapper.UserMemoryMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;

import javax.sql.DataSource;

/**
 * 手动装配 MyBatis-Plus 的 {@link SqlSessionFactory} 与 Mapper —— 不依赖 spring-boot3-starter 的
 * 自动装配(其面向 Boot 3, 在 Boot 4 上有兼容风险)。auto-config 与单测共用同一构建逻辑, 保证一致。
 *
 * <p>关键: 必须用 MyBatis-Plus 的 {@link MybatisConfiguration}(而非原生 MyBatis Configuration),
 * {@code addMapper} 时才会给 {@code BaseMapper} 注入通用 CRUD 的 SQL。
 */
final class MyBatisSupport {

    private MyBatisSupport() {
    }

    static SqlSessionFactory sqlSessionFactory(DataSource dataSource) {
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);   // sessionId ↔ session_id 自动映射
        factoryBean.setConfiguration(configuration);
        try {
            SqlSessionFactory factory = factoryBean.getObject();
            factory.getConfiguration().addMapper(ConversationTurnMapper.class);
            factory.getConfiguration().addMapper(ConversationRecordingMapper.class);
            factory.getConfiguration().addMapper(EvaluationMapper.class);
            factory.getConfiguration().addMapper(AppUserMapper.class);
            factory.getConfiguration().addMapper(ChatConversationMapper.class);
            factory.getConfiguration().addMapper(ChatMessageMapper.class);
            factory.getConfiguration().addMapper(UserMemoryMapper.class);
            factory.getConfiguration().addMapper(KnowledgeDocMapper.class);
            factory.getConfiguration().addMapper(KnowledgeChunkMapper.class);
            return factory;
        } catch (Exception e) {
            throw new IllegalStateException("构建对话存档 SqlSessionFactory 失败", e);
        }
    }

    /** 由工厂取一个线程安全的 Mapper(底层 {@link SqlSessionTemplate}, 每次操作自管会话)。 */
    static <T> T mapper(SqlSessionFactory factory, Class<T> type) {
        return new SqlSessionTemplate(factory).getMapper(type);
    }
}
