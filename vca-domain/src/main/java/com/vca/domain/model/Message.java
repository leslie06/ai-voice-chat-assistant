package com.vca.domain.model;

/**
 * 一条对话消息。LLM 调用时按顺序传入历史(system + 多轮 user/assistant)。
 */
public record Message(Role role, String content) {

    public enum Role {
        SYSTEM, USER, ASSISTANT
    }

    public static Message system(String content) {
        return new Message(Role.SYSTEM, content);
    }

    public static Message user(String content) {
        return new Message(Role.USER, content);
    }

    public static Message assistant(String content) {
        return new Message(Role.ASSISTANT, content);
    }
}
