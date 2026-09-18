package com.vca.telephony.summary;

import com.vca.domain.enums.VendorType;
import com.vca.domain.model.LlmConfig;
import com.vca.domain.model.Message;
import com.vca.domain.spi.LlmProvider;
import com.vca.orchestrator.call.CallSummary;
import com.vca.orchestrator.call.CallSummaryStore;
import com.vca.telephony.merchant.Merchant;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 通话事后处理: 摘要解析、太短不摘要、推送文案。 */
class CallAftermathTest {

    private static final List<Message> HISTORY = List.of(
            Message.system("你是电话客服助手"),
            Message.user("我想约周六上午做种植牙，我姓王"),
            Message.assistant("王先生，已经帮您登记周六上午的种植牙面诊"));

    private static LlmProvider llmReturning(String reply, AtomicInteger calls) {
        return new LlmProvider() {
            @Override
            public VendorType vendor() {
                return VendorType.QWEN;
            }

            @Override
            public Flux<String> chatStream(List<Message> history, LlmConfig cfg) {
                calls.incrementAndGet();
                // 按 token 切开, 验证聚合
                return Flux.fromArray(reply.split("(?<=\\n)"));
            }
        };
    }

    private static EndedCall call(int durationSec) {
        return new EndedCall("call-7", "13800138000", "01088886666", durationSec, "peer-hangup", HISTORY);
    }

    /** 测试里的商家: 归属账号 11, 推送地址随便填一个(通知器由下面的假实现给, 不真发) */
    private static final Merchant MERCHANT =
            new Merchant("01088886666", "美好口腔", "您好", "", "11", "user/1000", "https://hook", "");

    private static CallAftermath aftermath(LlmProvider llm, CallSummaryStore store, CallNotifier notifier,
                                           int minDurationSec) {
        CallSummarizer summarizer = new CallSummarizer(llm,
                new LlmConfig(VendorType.QWEN, "qwen-flash", CallSummarizer.PROMPT, 0.2, 512));
        return new CallAftermath(summarizer, store, url -> notifier, minDurationSec);
    }

    @Test
    void parsesSummaryIntentAndFollowUp() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        List<CallSummary> saved = new CopyOnWriteArrayList<>();
        List<CallSummary> pushed = new CopyOnWriteArrayList<>();

        aftermath(llmReturning("""
                摘要: 王先生想约周六上午做种植牙，已登记面诊。
                意向: A
                跟进: 周六上午前电话确认时间
                """, calls), s -> saved.add(s), pushed::add, 10)
                .onCallEnded(call(45), MERCHANT);

        awaitUntil(() -> !saved.isEmpty() && !pushed.isEmpty());
        CallSummary s = saved.get(0);
        assertThat(s.callId()).isEqualTo("call-7");
        assertThat(s.ownerId()).isEqualTo("11");
        assertThat(s.peerNumber()).isEqualTo("13800138000");
        assertThat(s.durationSec()).isEqualTo(45);
        assertThat(s.turns()).isEqualTo(1);          // 一句客户话 = 一轮
        assertThat(s.summary()).isEqualTo("王先生想约周六上午做种植牙，已登记面诊。");
        assertThat(s.intent()).isEqualTo("A");
        assertThat(s.followUp()).isEqualTo("周六上午前电话确认时间");
        assertThat(pushed).containsExactly(s);
    }

    /** 模型没按格式来(加了围栏/写成一段话): 不能丢信息, 原话当摘要、意向退到 C */
    @Test
    void fallsBackWhenTheModelIgnoresTheFormat() throws Exception {
        List<CallSummary> saved = new CopyOnWriteArrayList<>();

        aftermath(llmReturning("客户咨询了洗牙价格，没有留联系方式。", new AtomicInteger()),
                s -> saved.add(s), CallNotifier.NOOP, 10)
                .onCallEnded(call(30), MERCHANT);

        awaitUntil(() -> !saved.isEmpty());
        assertThat(saved.get(0).summary()).contains("洗牙价格");
        assertThat(saved.get(0).intent()).isEqualTo("C");
        assertThat(saved.get(0).followUp()).isNull();
    }

    /** 意向写成 "A(马上要办)" 这种也要认 */
    @Test
    void gradeToleratesExtraWording() throws Exception {
        List<CallSummary> saved = new CopyOnWriteArrayList<>();

        aftermath(llmReturning("摘要: 客户要改预约时间。\n意向: B（有意向待跟进）\n跟进: 无\n", new AtomicInteger()),
                s -> saved.add(s), CallNotifier.NOOP, 10)
                .onCallEnded(call(20), MERCHANT);

        awaitUntil(() -> !saved.isEmpty());
        assertThat(saved.get(0).intent()).isEqualTo("B");
        assertThat(saved.get(0).followUp()).as("跟进写“无”应当归一成 null").isNull();
    }

    /**
     * 回归: 模型把意向那行写成一句中文而不是字母(qwen-flash 实测会这样),
     * 不能因此退回默认的 C —— 明明已经约了面诊, 商家看到 C 就不会跟进了。
     */
    @Test
    void gradeIsInferredWhenTheModelWritesProseInsteadOfALetter() throws Exception {
        List<CallSummary> saved = new CopyOnWriteArrayList<>();

        aftermath(llmReturning("""
                摘要: 客户王女士预约本周六上午做种植牙面诊。
                意向: 明确表达种植牙面诊需求，已约定时间。
                跟进: 24 小时内联系客户确认
                """, new AtomicInteger()), s -> saved.add(s), CallNotifier.NOOP, 10)
                .onCallEnded(call(31), MERCHANT);

        awaitUntil(() -> !saved.isEmpty());
        assertThat(saved.get(0).intent()).isEqualTo("A");
    }

    /** 判不出来的时候才退回 C */
    @Test
    void gradeFallsBackToCWhenNothingCanBeInferred() throws Exception {
        List<CallSummary> saved = new CopyOnWriteArrayList<>();

        aftermath(llmReturning("摘要: 通话内容不明。\n意向: 说不好。\n跟进: 无\n", new AtomicInteger()),
                s -> saved.add(s), CallNotifier.NOOP, 10)
                .onCallEnded(call(31), MERCHANT);

        awaitUntil(() -> !saved.isEmpty());
        assertThat(saved.get(0).intent()).isEqualTo("C");
    }

    /** 秒挂/拨错占呼入一大半, 不能每通都调一次模型 */
    @Test
    void shortCallsAreSkippedEntirely() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        List<CallSummary> saved = new CopyOnWriteArrayList<>();

        aftermath(llmReturning("摘要: x\n意向: D\n跟进: 无\n", calls), s -> saved.add(s), CallNotifier.NOOP, 10)
                .onCallEnded(call(4), MERCHANT);

        Thread.sleep(300);
        assertThat(calls.get()).as("不该调用大模型").isZero();
        assertThat(saved).isEmpty();
    }

    /** 客户一句话没说: 同样不必调模型, 直接判无效 */
    @Test
    void silentCallIsGradedWithoutCallingTheModel() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        List<CallSummary> saved = new CopyOnWriteArrayList<>();
        EndedCall silent = new EndedCall("call-8", null, "01088886666", 25, "peer-hangup",
                List.of(Message.system("你是电话客服助手")));

        aftermath(llmReturning("不该被调用", calls), s -> saved.add(s), CallNotifier.NOOP, 10)
                .onCallEnded(silent, MERCHANT);

        awaitUntil(() -> !saved.isEmpty());
        assertThat(calls.get()).isZero();
        assertThat(saved.get(0).intent()).isEqualTo("D");
        assertThat(saved.get(0).turns()).isZero();
    }

    /** 落库失败不能挡住推送 —— 商家群里那条消息比留档重要 */
    @Test
    void pushStillHappensWhenStoreFails() throws Exception {
        List<CallSummary> pushed = new CopyOnWriteArrayList<>();

        aftermath(llmReturning("摘要: 客户问地址。\n意向: C\n跟进: 无\n", new AtomicInteger()),
                s -> {
                    throw new IllegalStateException("db down");
                }, pushed::add, 10)
                .onCallEnded(call(15), MERCHANT);

        awaitUntil(() -> !pushed.isEmpty());
        assertThat(pushed.get(0).summary()).contains("地址");
    }

    @Test
    void webhookTextIsReadableInAGroupChat() {
        CallSummary s = new CallSummary("call-7", "11", "13800138000", "01088886666", 45, 2,
                "王先生想约周六上午做种植牙。", "A", "周六上午前确认", null);

        String text = WebhookCallNotifier.text(s);

        assertThat(text).contains("意向 A").contains("13800138000").contains("45 秒")
                .contains("王先生想约周六上午做种植牙。").contains("待跟进: 周六上午前确认");
    }

    private static void awaitUntil(java.util.function.BooleanSupplier cond) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("等待条件超时");
    }
}
