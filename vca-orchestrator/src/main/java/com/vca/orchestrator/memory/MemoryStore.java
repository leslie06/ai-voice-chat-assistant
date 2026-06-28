package com.vca.orchestrator.memory;

import java.util.List;

/**
 * 长期记忆端口: 跨会话记住某用户的个人信息/偏好(姓名、喜好、习惯、重要事实), 让助手"越用越懂你"。
 * 与 {@link com.vca.orchestrator.recorder.ConversationRecorder} 一样是旁路 SPI, 由 {@code vca-store} 实现;
 * 未注入实现(账号系统未启用)时为 {@link #NOOP}, 记忆功能静默关闭, 不影响对话。
 */
public interface MemoryStore {

    MemoryStore NOOP = new MemoryStore() {
        @Override
        public List<String> recall(String userId) {
            return List.of();
        }

        @Override
        public void remember(String userId, String content) {
        }
    };

    /** 取某用户的长期记忆(较新在前, 实现可截断条数)。 */
    List<String> recall(String userId);

    /** 为某用户新增一条长期记忆。实现自行去重/限量, 失败不抛。 */
    void remember(String userId, String content);
}
