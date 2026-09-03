package com.vca.bootstrap.eval;

import com.vca.domain.enums.VendorType;
import com.vca.domain.model.AsrConfig;
import com.vca.domain.model.AsrEvent;
import com.vca.domain.model.AudioFrame;
import com.vca.orchestrator.eval.ErrorRate;
import com.vca.provider.asr.aliyun.AliyunAsrProperties;
import com.vca.provider.asr.aliyun.AliyunAsrProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * ASR 字错率(CER)回放评测 —— <b>真机、要花钱、默认不跑</b>。
 *
 * <p>解决的是"换 ASR 模型 / 改采样率 / 动 VAD 预滚, 到底变好还是变坏"这个问题。
 * 在有这张表之前只能靠听, 而人对一两个字的差异根本不敏感 —— 直到长会话里累积成"它老是听错"。
 *
 * <h3>怎么跑</h3>
 * <pre>
 * # 1. 准备语料目录: 每条一对同名文件
 * #      utt-001.wav  16k/单声道/16bit PCM 的 WAV
 * #      utt-001.txt  这条音频的正确文本(UTF-8, 人工校对过)
 * # 2. 跑
 * DASHSCOPE_API_KEY=sk-xxx \
 * VCA_EVAL_AUDIO_DIR=/path/to/corpus \
 *   ./mvnw -pl vca-bootstrap test -Dtest=AsrWerReplayTest -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 *
 * <p>语料从哪来: {@code conversation_recording} 表里每通会话都存了用户音轨的 OSS Object Key,
 * 导出后人工校对转写即可 —— 这是<b>你自己线上的真实口音、真实噪声环境</b>,
 * 比任何公开测试集都更能代表这套系统实际要面对的输入。
 *
 * <p>不设断言阈值: 首次跑出来的数就是基线, 记在手边; 之后每次调参对比这个数。
 * 硬编码一个"CER 必须 &lt; x%" 只会在换语料时变成噪声。
 */
@EnabledIfEnvironmentVariable(named = "VCA_EVAL_AUDIO_DIR", matches = ".+")
@EnabledIfEnvironmentVariable(named = "DASHSCOPE_API_KEY", matches = ".+")
class AsrWerReplayTest {

    /** 一次识别的结果。 */
    private record Item(String name, String reference, String hypothesis, ErrorRate.Result result) {
    }

    @Test
    void replayCorpusAndReportCer() throws Exception {
        Path dir = Path.of(System.getenv("VCA_EVAL_AUDIO_DIR"));
        List<Path> wavs;
        try (var s = Files.list(dir)) {
            wavs = s.filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".wav"))
                    .sorted(Comparator.comparing(Path::getFileName))
                    .toList();
        }
        if (wavs.isEmpty()) {
            System.out.println("语料目录里没有 .wav: " + dir);
            return;
        }

        AliyunAsrProperties props = new AliyunAsrProperties();
        props.setApiKey(System.getenv("DASHSCOPE_API_KEY"));
        String model = System.getenv("VCA_EVAL_ASR_MODEL");
        if (model != null && !model.isBlank()) {
            props.setModel(model);
        }
        AliyunAsrProvider asr = new AliyunAsrProvider(props);
        AsrConfig cfg = AsrConfig.defaults(VendorType.ALIYUN);

        List<Item> items = new ArrayList<>();
        for (Path wav : wavs) {
            Path txt = dir.resolve(stripExtension(wav.getFileName().toString()) + ".txt");
            if (!Files.exists(txt)) {
                System.out.println("跳过(没有对应的 .txt 参考文本): " + wav.getFileName());
                continue;
            }
            String reference = Files.readString(txt).strip();
            String hypothesis = transcribe(asr, cfg, wav);
            items.add(new Item(wav.getFileName().toString(), reference, hypothesis,
                    ErrorRate.compare(reference, hypothesis)));
        }
        report(props.getModel(), items);
    }

    /** 把整个 wav 按 20ms 一帧喂进去, 取 final。真实链路是流式的, 这里也按流式喂以贴近线上行为。 */
    private static String transcribe(AliyunAsrProvider asr, AsrConfig cfg, Path wav) throws IOException {
        byte[] pcm = readPcmFromWav(wav);
        int bytesPerFrame = cfg.sampleRate() / 50 * 2;   // 20ms, 16bit 单声道
        List<AudioFrame> frames = new ArrayList<>();
        long seq = 0;
        for (int off = 0; off < pcm.length; off += bytesPerFrame) {
            int len = Math.min(bytesPerFrame, pcm.length - off);
            byte[] chunk = new byte[len];
            System.arraycopy(pcm, off, chunk, 0, len);
            // 时间戳按帧序推算(每帧 20ms), 回放不依赖墙钟, 结果才可复现
            frames.add(AudioFrame.of(chunk, seq, seq * 20));
            seq++;
        }
        frames.add(AudioFrame.endOfSpeech(seq, seq * 20));
        return asr.transcribe(Flux.fromIterable(frames), cfg)
                .filter(AsrEvent::isFinal)
                .next()
                .map(AsrEvent::text)
                .defaultIfEmpty("")
                .block(Duration.ofMinutes(2));
    }

    /**
     * 读 WAV 的 data 块。只认无压缩 PCM —— 评测语料就该是原始 PCM,
     * 经过有损压缩再评测, 测的是编解码器不是识别器。
     */
    private static byte[] readPcmFromWav(Path wav) throws IOException {
        byte[] all = Files.readAllBytes(wav);
        if (all.length < 44 || all[0] != 'R' || all[1] != 'I' || all[2] != 'F' || all[3] != 'F') {
            return all;   // 不是 WAV: 当作裸 PCM
        }
        int pos = 12;   // 跳过 RIFF 头
        while (pos + 8 <= all.length) {
            String id = new String(all, pos, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int size = (all[pos + 4] & 0xFF) | (all[pos + 5] & 0xFF) << 8
                    | (all[pos + 6] & 0xFF) << 16 | (all[pos + 7] & 0xFF) << 24;
            pos += 8;
            if ("data".equals(id)) {
                int len = Math.min(size, all.length - pos);
                byte[] out = new byte[len];
                System.arraycopy(all, pos, out, 0, len);
                return out;
            }
            pos += size + (size & 1);   // 块按偶数字节对齐
        }
        throw new IOException("WAV 里没找到 data 块: " + wav);
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    private static void report(String model, List<Item> items) {
        StringBuilder sb = new StringBuilder("\n============ ASR 字错率(CER)回放报告 ============\n");
        sb.append("模型: ").append(model).append(", 语料 ").append(items.size()).append(" 条\n\n");
        // 错得最狠的排前面 —— 要改进先看这些
        List<Item> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparingDouble((Item i) -> i.result().rate()).reversed());
        for (Item i : sorted) {
            sb.append(String.format("%-24s CER %6.1f%%  (替换%d 删除%d 插入%d / 共%d字)%n",
                    i.name(), i.result().rate() * 100, i.result().substitutions(),
                    i.result().deletions(), i.result().insertions(), i.result().refLength()));
            if (i.result().errors() > 0) {
                sb.append("    参考: ").append(i.reference()).append('\n');
                sb.append("    识别: ").append(i.hypothesis()).append('\n');
            }
        }
        ErrorRate.Result corpus = ErrorRate.aggregate(items.stream().map(Item::result).toList());
        sb.append(String.format("%n---- 语料级 CER: %.2f%% (错 %d 字 / 共 %d 字) ----%n",
                corpus.rate() * 100, corpus.errors(), corpus.refLength()));
        sb.append("(语料级 = 错误数与字数分别求和后相除, 不是逐句错误率取平均)\n");
        sb.append("================================================\n");
        System.out.println(sb);
    }
}
