package com.vca.telephony.summary;

import com.vca.orchestrator.call.CallSummary;
import com.vca.orchestrator.call.CallSummaryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.scheduler.Schedulers;

/**
 * 通话事后处理: 生成小结 → 落库 → 推给商家。
 *
 * <p><b>整个过程都在通话之外</b>: 由 {@code CallSession} 收尾时触发, 切到 boundedElastic 上跑。
 * 客户已经挂机了, 这里慢一点、失败一次都不影响任何人 —— 所以每一步都自己兜住异常,
 * 绝不让它冒到收尾路径上去(那会连带影响录音落库、资源释放)。
 *
 * <p>太短的通话不摘要({@code minDurationSec}): 秒挂、拨错、彩铃这类占了呼入的一大半,
 * 每通都调一次大模型纯属烧钱, 而"接通 3 秒挂断"本身已经说明了一切。
 */
public final class CallAftermath {

    private static final Logger log = LoggerFactory.getLogger(CallAftermath.class);

    private final CallSummarizer summarizer;
    private final CallSummaryStore store;
    private final CallNotifier notifier;
    private final String ownerId;
    private final int minDurationSec;

    public CallAftermath(CallSummarizer summarizer, CallSummaryStore store, CallNotifier notifier,
                         String ownerId, int minDurationSec) {
        this.summarizer = summarizer;
        this.store = store == null ? CallSummaryStore.NOOP : store;
        this.notifier = notifier == null ? CallNotifier.NOOP : notifier;
        this.ownerId = ownerId;
        this.minDurationSec = minDurationSec;
    }

    /** 通话结束时调用。<b>立即返回</b>, 真正的活儿在别的线程上。 */
    public void onCallEnded(EndedCall call) {
        if (call == null) {
            return;
        }
        if (call.durationSec() < minDurationSec) {
            log.debug("[{}] 通话仅 {}s, 不生成小结(阈值 {}s)", call.callId(), call.durationSec(), minDurationSec);
            return;
        }
        summarizer.summarize(call, ownerId)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(this::deliver,
                        e -> log.warn("[{}] 通话小结未生成: {}", call.callId(), e.toString()));
    }

    /**
     * 落库与推送<b>各自兜异常</b>: 两件事互不依赖, 而"商家群里弹出那条消息"比留档重要 ——
     * 数据库挂了不能连带把通知也吞掉。
     */
    private void deliver(CallSummary summary) {
        boolean stored = false;
        try {
            stored = store.save(summary);
        } catch (RuntimeException e) {
            log.warn("[{}] 通话小结落库失败(仍会推送): {}", summary.callId(), e.toString());
        }
        log.info("[{}] 通话小结: 意向={}, 已落库={}, 摘要={}",
                summary.callId(), summary.intent(), stored, summary.summary());
        try {
            notifier.notify(summary);
        } catch (RuntimeException e) {
            log.warn("[{}] 通话小结推送失败: {}", summary.callId(), e.toString());
        }
    }
}
