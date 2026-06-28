package com.vca.store.account;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.vca.store.entity.ChatConversation;
import com.vca.store.entity.ChatMessage;
import com.vca.store.mapper.ChatConversationMapper;
import com.vca.store.mapper.ChatMessageMapper;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户会话与消息的读写。<b>每个方法都按 {@code userId} 校验归属</b> —— 用户只能操作自己的会话,
 * 越权(会话不属于该用户)一律当作不存在返回 null/false, 由路由转成 404/403。
 */
public class ConversationService {

    private final ChatConversationMapper conversations;
    private final ChatMessageMapper messages;

    public ConversationService(ChatConversationMapper conversations, ChatMessageMapper messages) {
        this.conversations = conversations;
        this.messages = messages;
    }

    /** 某用户的全部会话, 最近活跃在前。 */
    public List<ChatConversation> list(long userId) {
        return conversations.selectList(Wrappers.<ChatConversation>query()
                .eq("user_id", userId).orderByDesc("updated_at"));
    }

    /** 新建会话, 返回带 id 的实体。 */
    public ChatConversation create(long userId, String title) {
        ChatConversation c = new ChatConversation();
        c.setUserId(userId);
        c.setTitle(title == null || title.isBlank() ? "新对话" : trim(title, 255));
        LocalDateTime now = LocalDateTime.now();
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        conversations.insert(c);
        return c;
    }

    /** 删除会话(及其消息); 不属于该用户则返回 false。 */
    public boolean delete(long userId, long convId) {
        ChatConversation c = owned(userId, convId);
        if (c == null) {
            return false;
        }
        messages.delete(Wrappers.<ChatMessage>query().eq("conversation_id", convId));
        conversations.deleteById(convId);
        return true;
    }

    /** 会话内消息(按时间正序); 不属于该用户返回 null。 */
    public List<ChatMessage> messages(long userId, long convId) {
        if (owned(userId, convId) == null) {
            return null;
        }
        return messages.selectList(Wrappers.<ChatMessage>query()
                .eq("conversation_id", convId).orderByAsc("id"));
    }

    /**
     * 往会话追加一条消息。会更新会话 {@code updatedAt}; 若标题还是"新对话"且这是用户消息, 用其内容当标题。
     * 不属于该用户则返回 false。
     */
    public boolean appendMessage(long userId, long convId, String role, String content) {
        ChatConversation c = owned(userId, convId);
        if (c == null) {
            return false;
        }
        ChatMessage m = new ChatMessage();
        m.setConversationId(convId);
        m.setRole(role);
        m.setContent(content);
        m.setCreatedAt(LocalDateTime.now());
        messages.insert(m);

        c.setUpdatedAt(LocalDateTime.now());
        if ("user".equals(role) && (c.getTitle() == null || c.getTitle().isBlank()
                || "新对话".equals(c.getTitle())) && content != null && !content.isBlank()) {
            c.setTitle(trim(content.strip(), 40));
        }
        conversations.updateById(c);
        return true;
    }

    /** 取归属该用户的会话, 否则 null(越权/不存在)。 */
    private ChatConversation owned(long userId, long convId) {
        ChatConversation c = conversations.selectById(convId);
        return (c != null && c.getUserId() != null && c.getUserId() == userId) ? c : null;
    }

    private static String trim(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}
