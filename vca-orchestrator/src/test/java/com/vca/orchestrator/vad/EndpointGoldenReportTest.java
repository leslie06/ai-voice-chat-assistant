package com.vca.orchestrator.vad;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 语义端点判定的<b>离线评测基线</b>。
 *
 * <p>存在的理由: 判停阈值(silence-ms / min / max)与词表是这套系统里最影响体感、又最容易靠"我试了试
 * 感觉快了"来调的一组参数。参数空间是多维的, 而人对 200ms 的差异根本分辨不出来 —— 没有这张表,
 * 每次调参都是在赌。有了它, 改完重跑一次就知道是真的快了, 还是把"没说完"也一起切了。
 *
 * <p>报告分三片打: 全量 / 中间转写<b>带</b>标点 / <b>不带</b>标点。最后一片是关键 ——
 * {@link EndpointPolicy} 判 COMPLETE(缩短等待)几乎只靠句末标点, 而真实 ASR 的中间转写常常不带标点。
 * 如果"不带标点"这一片的 COMPLETE 命中率是 0, 那语义端点在线上就只会<b>加</b>延迟、永远不会减,
 * 此时正确的做法是直接下调 silence-ms, 而不是指望这个功能。
 *
 * <p>断言只守<b>底线</b>(不回归), 具体数字看控制台输出。
 *
 * <p><b>关于这份评测集的可信度, 必须说清楚</b>: {@code src/test/resources/eval/endpoint-golden.tsv}
 * 是实现者手写的<b>种子数据</b>, 不是真实用户语音的转写。它和规则出自同一个人之手, 因此天然存在
 * 过拟合 —— 上面的百分比只能用来<b>对比改动前后</b>, 不能当作线上准确率来读。
 * 要让这些数字有绝对意义, 得从 {@code conversation_recording} 导出真实中间转写、人工标注后覆盖该文件。
 * 在那之前, 线上唯一可信的信号是 {@code vca.turn.endpoint.reason} 这个计数器的实际分布。
 */
class EndpointGoldenReportTest {

    /** 与生产默认一致(application.yml: vca.web.vad.*), 改那边就要同步改这里, 否则测的不是线上那套。 */
    private static final int BASE_MS = 900;
    private static final int MIN_MS = 400;
    private static final int MAX_MS = 1600;

    /** 一条标注样本。 */
    private record Sample(boolean end, String text) {
        /** 中间转写是否带句末标点 —— 报告按这一维分片。 */
        boolean punctuated() {
            String t = text.strip();
            return !t.isEmpty() && "。！？!?；;".indexOf(t.charAt(t.length() - 1)) >= 0;
        }
    }

    /** 一片切片上的统计。 */
    private static final class Slice {
        final String name;
        final Map<EndpointPolicy.Completeness, Integer> endHits = new EnumMap<>(EndpointPolicy.Completeness.class);
        final Map<EndpointPolicy.Completeness, Integer> contHits = new EnumMap<>(EndpointPolicy.Completeness.class);
        long endWaitSum;
        int endCount;
        long contWaitSum;
        int contCount;
        /** 判错的样本(用来照着改规则, 而不是凭空猜哪些句式没覆盖到) */
        final List<String> missedEnd = new ArrayList<>();    // END 却没判出 COMPLETE
        final List<String> falseCuts = new ArrayList<>();    // CONT 却判成了 COMPLETE(危险)
        final List<String> missedCont = new ArrayList<>();   // CONT 却没判出 INCOMPLETE

        Slice(String name) {
            this.name = name;
            for (EndpointPolicy.Completeness c : EndpointPolicy.Completeness.values()) {
                endHits.put(c, 0);
                contHits.put(c, 0);
            }
        }

        void add(Sample s) {
            EndpointPolicy.Completeness c = EndpointPolicy.classify(s.text());
            int wait = EndpointPolicy.requiredSilenceMs(s.text(), BASE_MS, MIN_MS, MAX_MS);
            if (s.end()) {
                endHits.merge(c, 1, Integer::sum);
                endWaitSum += wait;
                endCount++;
                if (c != EndpointPolicy.Completeness.COMPLETE) {
                    missedEnd.add(s.text());
                }
            } else {
                contHits.merge(c, 1, Integer::sum);
                contWaitSum += wait;
                contCount++;
                if (c == EndpointPolicy.Completeness.COMPLETE) {
                    falseCuts.add(s.text());
                } else if (c != EndpointPolicy.Completeness.INCOMPLETE) {
                    missedCont.add(s.text());
                }
            }
        }

        /** END 样本里判 COMPLETE 的比例: 语义端点<b>真正省下延迟</b>的那部分, 越高越好。 */
        double fastEndRate() {
            return rate(endHits.get(EndpointPolicy.Completeness.COMPLETE), endCount);
        }

        /** END 样本里judged INCOMPLETE 的比例: 说完了还硬等 1440ms, 纯亏, 越低越好。 */
        double overWaitRate() {
            return rate(endHits.get(EndpointPolicy.Completeness.INCOMPLETE), endCount);
        }

        /** CONT 样本里判 COMPLETE 的比例: <b>把人话切断</b>, 危害最大的错误, 必须是 0。 */
        double falseCutRate() {
            return rate(contHits.get(EndpointPolicy.Completeness.COMPLETE), contCount);
        }

        /** CONT 样本里判 INCOMPLETE 的比例: 正确地多等一会, 越高越好。 */
        double patienceRate() {
            return rate(contHits.get(EndpointPolicy.Completeness.INCOMPLETE), contCount);
        }

        double avgEndWait() {
            return endCount == 0 ? 0 : (double) endWaitSum / endCount;
        }

        double avgContWait() {
            return contCount == 0 ? 0 : (double) contWaitSum / contCount;
        }

        private static double rate(int hit, int total) {
            return total == 0 ? 0 : (double) hit / total;
        }
    }

    @Test
    void endpointPolicyGoldenReport() throws Exception {
        List<Sample> all = load();
        assertThat(all).hasSizeGreaterThan(50);   // 样本太少的话数字没有意义

        Slice total = new Slice("全量");
        Slice punct = new Slice("带标点");
        Slice plain = new Slice("不带标点");
        for (Sample s : all) {
            total.add(s);
            (s.punctuated() ? punct : plain).add(s);
        }

        print(all, total, punct, plain);

        // ---- 底线断言(守回归, 不锁具体数值) ----

        // 1. 绝不能把"没说完"判成"说完了" —— 这会把人当场切断, 是所有错误里最伤的一种。
        //    宁可全判 NEUTRAL 退回固定阈值, 也不能出现这个。
        assertThat(total.falseCutRate())
                .as("早切率: CONT 被判 COMPLETE(把人话切断)")
                .isZero();

        // 2. "没说完"要有相当比例被识别出来并多等 —— 否则这个功能没在干活。
        assertThat(total.patienceRate())
                .as("CONT 识别率: 判成 INCOMPLETE 而多等一会的比例")
                .isGreaterThan(0.50);

        // 3. <b>整个功能的存在意义</b>: 说完的句子平均等得比固定基线更短。
        //    这条一旦挂掉, 说明语义端点在净亏 —— 正确反应是关掉它直接下调 silence-ms,
        //    而不是继续加词表。
        assertThat(total.avgEndWait())
                .as("END 平均等待 vs 固定基线 " + BASE_MS + "ms")
                .isLessThan(BASE_MS);

        // 4. 反过来, 没说完的要真的等得更久, 否则"多等"只是个说法。
        assertThat(total.avgContWait())
                .as("CONT 平均等待 vs 固定基线 " + BASE_MS + "ms")
                .isGreaterThan(BASE_MS);

        // 5. <b>不带标点这一片单独守</b>, 它才是线上真实形态(ASR 中间转写基本没有标点)。
        //    这一条是从实测里长出来的: 改造前这里是 0% —— 判 COMPLETE 只认句末标点,
        //    于是线上永远提不了速, 语义端点纯粹在加延迟。谁要是把无标点规则删了, 这条会拦住。
        assertThat(plain.endCount).as("不带标点的 END 样本数").isGreaterThan(10);
        assertThat(plain.fastEndRate())
                .as("不带标点时的提速命中率(线上真实形态; 为 0 则本功能对线上只有害处)")
                .isGreaterThan(0.15);
    }

    /** 把报告打到控制台: 调完参跑一次 `mvn -pl vca-orchestrator test -Dtest=EndpointGoldenReportTest` 看这张表。 */
    private static void print(List<Sample> all, Slice... slices) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n============ 语义端点判定 · 离线评测报告 ============\n");
        sb.append(String.format("样本 %d 条 (END %d / CONT %d), 参数 base=%dms min=%dms max=%dms%n",
                all.size(), all.stream().filter(Sample::end).count(),
                all.stream().filter(s -> !s.end()).count(), BASE_MS, MIN_MS, MAX_MS));
        sb.append(String.format("%-10s %6s %6s | %8s %8s %9s | %8s %8s %9s%n",
                "切片", "END", "CONT", "提速命中", "过度等待", "END均等待", "耐心命中", "早切(危)", "CONT均等待"));
        for (Slice s : slices) {
            sb.append(String.format("%-10s %6d %6d | %7.1f%% %7.1f%% %8.0fms | %7.1f%% %7.1f%% %8.0fms%n",
                    s.name, s.endCount, s.contCount,
                    s.fastEndRate() * 100, s.overWaitRate() * 100, s.avgEndWait(),
                    s.patienceRate() * 100, s.falseCutRate() * 100, s.avgContWait()));
        }
        sb.append("""
                ----------------------------------------------------
                提速命中 = END 判 COMPLETE, 省下延迟的那部分, 越高越好
                过度等待 = END 判 INCOMPLETE, 说完了还硬等, 越低越好
                耐心命中 = CONT 判 INCOMPLETE, 正确地多等, 越高越好
                早切(危) = CONT 判 COMPLETE, 把人话切断, 必须为 0
                ====================================================
                """);
        Slice full = slices[0];
        listMisses(sb, "早切(危险, 必须清零)", full.falseCuts);
        listMisses(sb, "该提速却没提速(END 未判 COMPLETE)", full.missedEnd);
        listMisses(sb, "该多等却没多等(CONT 未判 INCOMPLETE)", full.missedCont);
        System.out.println(sb);
    }

    /** 列出判错的样本 —— 改规则时照着这张单子加, 比凭感觉往词表里塞词有效得多。 */
    private static void listMisses(StringBuilder sb, String title, List<String> items) {
        sb.append(String.format("%n-- %s: %d 条 --%n", title, items.size()));
        for (String t : items) {
            sb.append("   ").append(t).append('\n');
        }
    }

    private static List<Sample> load() throws Exception {
        List<Sample> out = new ArrayList<>();
        try (InputStream in = EndpointGoldenReportTest.class
                .getResourceAsStream("/eval/endpoint-golden.tsv");
             BufferedReader r = new BufferedReader(
                     new InputStreamReader(java.util.Objects.requireNonNull(in), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("\t", 2);
                if (parts.length != 2 || parts[1].isBlank()) {
                    continue;
                }
                out.add(new Sample("END".equals(parts[0].strip()), parts[1]));
            }
        }
        return out;
    }
}
