package com.vca.store;

import com.vca.store.account.ConversationService;
import com.vca.store.entity.ChatConversation;
import com.vca.store.mapper.ChatConversationMapper;
import com.vca.store.mapper.ChatMessageMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话删除是<b>逻辑删除</b>: 用户侧看起来和真删一样, 但行和消息都还在库里, 误删可恢复。
 */
class ConversationSoftDeleteTest {

    private DriverManagerDataSource ds;
    private ConversationService service;
    private ChatConversationMapper conversations;

    @BeforeEach
    void setUp() throws Exception {
        ds = new DriverManagerDataSource(
                "jdbc:h2:mem:soft-delete-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        ds.setDriverClassName("org.h2.Driver");
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("""
                    CREATE TABLE chat_conversation (
                      id BIGINT AUTO_INCREMENT PRIMARY KEY,
                      user_id BIGINT NOT NULL,
                      title VARCHAR(255),
                      created_at TIMESTAMP NOT NULL,
                      updated_at TIMESTAMP NOT NULL,
                      deleted TINYINT NOT NULL DEFAULT 0
                    )
                    """);
            s.execute("""
                    CREATE TABLE chat_message (
                      id BIGINT AUTO_INCREMENT PRIMARY KEY,
                      conversation_id BIGINT NOT NULL,
                      role VARCHAR(16) NOT NULL,
                      content TEXT,
                      created_at TIMESTAMP NOT NULL
                    )
                    """);
        }
        SqlSessionFactory factory = MyBatisSupport.sqlSessionFactory(ds);
        conversations = MyBatisSupport.mapper(factory, ChatConversationMapper.class);
        ChatMessageMapper messages = MyBatisSupport.mapper(factory, ChatMessageMapper.class);
        service = new ConversationService(conversations, messages);
    }

    /** 用户侧: 删了就该彻底看不见 —— 列表、消息、追加, 一个都不能漏 */
    @Test
    void deletedConversationIsInvisibleEverywhere() {
        ChatConversation c = service.create(1L, "利息怎么算");
        service.appendMessage(1L, c.getId(), "user", "你们年化多少");

        assertThat(service.delete(1L, c.getId())).isTrue();

        assertThat(service.list(1L)).isEmpty();
        assertThat(service.messages(1L, c.getId())).isNull();
        assertThat(service.appendMessage(1L, c.getId(), "user", "再问一句")).isFalse();
        // 重复删除按"不存在"处理, 不该报错也不该返回成功
        assertThat(service.delete(1L, c.getId())).isFalse();
    }

    /** 库侧: 行还在, 只是置了位 —— 这才是"逻辑"删除的意义 */
    @Test
    void rowSurvivesInDatabase() throws Exception {
        ChatConversation c = service.create(1L, "留着的会话");

        service.delete(1L, c.getId());

        try (Connection conn = ds.getConnection(); Statement s = conn.createStatement()) {
            ResultSet rs = s.executeQuery(
                    "SELECT title, deleted FROM chat_conversation WHERE id = " + c.getId());
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("title")).isEqualTo("留着的会话");
            assertThat(rs.getInt("deleted")).isEqualTo(1);
        }
    }

    /**
     * 消息不能跟着删。只删会话行、把消息清空的话, 将来恢复出来的会是个空壳 ——
     * 那等于没有"可恢复"这回事。
     */
    @Test
    void messagesArePreservedForRecovery() throws Exception {
        ChatConversation c = service.create(1L, "带消息的会话");
        service.appendMessage(1L, c.getId(), "user", "第一句");
        service.appendMessage(1L, c.getId(), "bot", "第二句");

        service.delete(1L, c.getId());

        try (Connection conn = ds.getConnection(); Statement s = conn.createStatement()) {
            ResultSet rs = s.executeQuery(
                    "SELECT COUNT(*) AS n FROM chat_message WHERE conversation_id = " + c.getId());
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt("n")).isEqualTo(2);
        }
    }

    /** 恢复: 把标记清回 0, 会话连同消息一起回来 */
    @Test
    void clearingTheFlagRestoresConversationAndMessages() throws Exception {
        ChatConversation c = service.create(1L, "误删的会话");
        service.appendMessage(1L, c.getId(), "user", "别丢了");
        service.delete(1L, c.getId());
        assertThat(service.list(1L)).isEmpty();

        try (Connection conn = ds.getConnection(); Statement s = conn.createStatement()) {
            s.executeUpdate("UPDATE chat_conversation SET deleted = 0 WHERE id = " + c.getId());
        }

        assertThat(service.list(1L)).hasSize(1);
        assertThat(service.messages(1L, c.getId())).hasSize(1);
    }

    /** 越权删除仍然按"不存在"处理, 不能因为改了删除方式就把归属校验漏掉 */
    @Test
    void otherUsersCannotDelete() {
        ChatConversation c = service.create(1L, "我的会话");

        assertThat(service.delete(2L, c.getId())).isFalse();

        assertThat(service.list(1L)).hasSize(1);
        assertThat(conversations.selectById(c.getId())).isNotNull();
    }
}
