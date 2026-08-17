package com.vca.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** 用户的一个会话(对应表 {@code chat_conversation})。按 {@code userId} 隔离。 */
@TableName("chat_conversation")
public class ChatConversation {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String title;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * 逻辑删除标记(0=正常, 1=已删)。
     *
     * <p>{@link TableLogic} 让 MyBatis-Plus 自动改写 SQL: {@code deleteById} 变成
     * {@code UPDATE ... SET deleted = 1}, 而所有 select 自动带上 {@code deleted = 0}。
     * 因此 {@code ConversationService} 里每条访问路径(list/messages/appendMessage 都经过
     * {@code owned()} 的 selectById)<b>不用改一行代码</b>, 删掉的会话对用户即刻不可见,
     * 数据却留在库里 —— 误删可恢复, 也不影响已归档的对话记录。
     */
    @TableLogic
    private Integer deleted;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Integer getDeleted() {
        return deleted;
    }

    public void setDeleted(Integer deleted) {
        this.deleted = deleted;
    }
}
