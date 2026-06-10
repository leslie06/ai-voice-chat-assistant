package com.vca.orchestrator.statemachine;

import com.vca.domain.enums.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 会话状态机。线程安全(CAS), 只允许合法迁移。
 *
 * <p>合法迁移图:
 * <pre>
 *   IDLE       → LISTENING, CLOSED
 *   LISTENING  → THINKING, INTERRUPTED, IDLE, CLOSED
 *   THINKING   → SPEAKING, INTERRUPTED, IDLE, CLOSED
 *   SPEAKING   → IDLE, LISTENING, INTERRUPTED, CLOSED
 *   INTERRUPTED→ IDLE, LISTENING, CLOSED
 *   CLOSED     → (终态)
 * </pre>
 */
public class ConversationStateMachine {

    private static final Logger log = LoggerFactory.getLogger(ConversationStateMachine.class);

    private static final Map<SessionState, EnumSet<SessionState>> ALLOWED = new EnumMap<>(SessionState.class);

    static {
        ALLOWED.put(SessionState.IDLE, EnumSet.of(SessionState.LISTENING, SessionState.CLOSED));
        ALLOWED.put(SessionState.LISTENING, EnumSet.of(
                SessionState.THINKING, SessionState.INTERRUPTED, SessionState.IDLE, SessionState.CLOSED));
        ALLOWED.put(SessionState.THINKING, EnumSet.of(
                SessionState.SPEAKING, SessionState.INTERRUPTED, SessionState.IDLE, SessionState.CLOSED));
        ALLOWED.put(SessionState.SPEAKING, EnumSet.of(
                SessionState.IDLE, SessionState.LISTENING, SessionState.INTERRUPTED, SessionState.CLOSED));
        ALLOWED.put(SessionState.INTERRUPTED, EnumSet.of(
                SessionState.IDLE, SessionState.LISTENING, SessionState.CLOSED));
        ALLOWED.put(SessionState.CLOSED, EnumSet.noneOf(SessionState.class));
    }

    private final AtomicReference<SessionState> state = new AtomicReference<>(SessionState.IDLE);

    public SessionState current() {
        return state.get();
    }

    public boolean is(SessionState s) {
        return state.get() == s;
    }

    /**
     * 尝试迁移到目标状态。非法迁移返回 false 并记日志, 不抛异常 —— 便于在响应式回调里安全调用。
     */
    public boolean tryTransition(SessionState target) {
        for (; ; ) {
            SessionState cur = state.get();
            if (cur == target) {
                return true; // 幂等
            }
            EnumSet<SessionState> allowed = ALLOWED.get(cur);
            if (allowed == null || !allowed.contains(target)) {
                log.warn("非法状态迁移被拒绝: {} → {}", cur, target);
                return false;
            }
            if (state.compareAndSet(cur, target)) {
                log.debug("状态迁移: {} → {}", cur, target);
                return true;
            }
            // CAS 失败(并发), 重试
        }
    }

    /** 强制置为终态 CLOSED */
    public void close() {
        state.set(SessionState.CLOSED);
    }
}
