package com.vca.store;

import com.vca.orchestrator.recorder.ConversationRecorder;
import com.vca.orchestrator.recorder.TurnRecord;
import com.vca.store.entity.ConversationTurn;
import com.vca.store.mapper.ConversationTurnMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 用 MyBatis-Plus 把 {@link TurnRecord} 异步写入 MySQL 的 {@link ConversationRecorder} 实现(数据飞轮第一环)。
 *
 * <p><b>不阻塞语音热路径</b>: {@link #recordTurn} 只把记录入有界队列即返回; 真正的 INSERT 由单独的守护线程
 * 攒批执行(逐条 {@code mapper.insert})。队列满时<b>丢最旧</b>(与上行音频 {@code pending} 缓冲同策略) ——
 * 宁可丢存档也不拖慢对话。写库异常就地吞掉(丢本批), 绝不冒泡到对话链路。
 *
 * <p>{@link #close()} 时停止收新记录并<b>把队列里剩余的尽量写完</b>再退出(优雅收尾, 不靠中断丢数据)。
 */
public class MyBatisConversationRecorder implements ConversationRecorder, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MyBatisConversationRecorder.class);

    /** 一次最多攒多少条一起写, 摊薄调度开销。 */
    private static final int MAX_BATCH = 64;

    private final ConversationTurnMapper mapper;
    private final BlockingQueue<TurnRecord> queue;
    private final Thread worker;
    private final AtomicLong dropped = new AtomicLong();
    private volatile boolean running = true;

    public MyBatisConversationRecorder(ConversationTurnMapper mapper, int queueCapacity) {
        this.mapper = mapper;
        this.queue = new LinkedBlockingQueue<>(Math.max(64, queueCapacity));
        this.worker = new Thread(this::drainLoop, "vca-conv-recorder");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    @Override
    public void recordTurn(TurnRecord record) {
        if (record == null) {
            return;
        }
        if (!queue.offer(record)) {
            queue.poll();            // 满了丢最旧, 给新记录腾位; 保护热路径
            queue.offer(record);
            long n = dropped.incrementAndGet();
            if (n == 1 || n % 100 == 0) {
                log.warn("对话落库队列已满, 累计丢弃 {} 条最旧记录(写库跟不上?)", n);
            }
        }
    }

    /** 守护线程主循环: 攒批 → 写库。running=false 且队列排空后退出。 */
    private void drainLoop() {
        List<TurnRecord> batch = new ArrayList<>(MAX_BATCH);
        while (running || !queue.isEmpty()) {
            try {
                TurnRecord first = queue.poll(500, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;        // 空转一圈, 回头重判 running
                }
                batch.add(first);
                queue.drainTo(batch, MAX_BATCH - 1);
                flush(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("对话落库写入失败, 丢弃本批 {} 条: {}", batch.size(), e.toString());
            } finally {
                batch.clear();
            }
        }
    }

    private void flush(List<TurnRecord> batch) {
        for (TurnRecord r : batch) {
            mapper.insert(toEntity(r));
        }
    }

    private static ConversationTurn toEntity(TurnRecord r) {
        ConversationTurn e = new ConversationTurn();
        e.setSessionId(r.sessionId());
        e.setTurnIndex(r.turnIndex());
        e.setMode(r.mode());
        e.setUserText(r.userText());
        e.setAssistantText(r.assistantText());
        e.setTotalMs(r.totalMs());
        e.setOutcome(r.outcome());
        e.setCreatedAt(LocalDateTime.ofInstant(r.at(), ZoneId.systemDefault()));
        return e;
    }

    @Override
    public void close() {
        running = false;        // 停止收新批; worker 排空队列后自然退出(poll 超时回头重判)
        try {
            worker.join(3000);  // 给最后一批写库的时间, 不中断以免丢数据
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
