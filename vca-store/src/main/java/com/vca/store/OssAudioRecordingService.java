package com.vca.store;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.AbortMultipartUploadRequest;
import com.aliyun.oss.model.CompleteMultipartUploadRequest;
import com.aliyun.oss.model.InitiateMultipartUploadRequest;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PartETag;
import com.aliyun.oss.model.PutObjectRequest;
import com.aliyun.oss.model.UploadPartRequest;
import com.vca.orchestrator.recorder.AudioRecordingService;
import com.vca.orchestrator.vad.PcmAudio;
import com.vca.store.entity.ChatConversation;
import com.vca.store.entity.ConversationRecording;
import com.vca.store.mapper.ChatConversationMapper;
import com.vca.store.mapper.ConversationRecordingMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 阿里云 OSS 录音：用户原始音轨、客服原始音轨、按回合排列的完整对话音轨。
 * 音频仅在内存中按分片缓冲，直接上传 OSS，不创建服务器临时文件。
 *
 * <p>WAV 头包含最终数据长度，通话结束前无法确定。因此每条音轨把首个分片留在内存，后续分片边录边传；
 * 结束时生成 WAV 头并上传为 part 1，再按 Part Number 由 OSS 合并为完整 WAV。
 */
public class OssAudioRecordingService implements AudioRecordingService, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OssAudioRecordingService.class);
    private static final int MIN_PART_SIZE = 100 * 1024;

    private final OSS oss;
    private final String bucket;
    private final String prefix;
    private final int partSize;
    private final int queueCapacity;
    private final ConversationRecordingMapper recordings;
    private final ChatConversationMapper conversations;
    private final ConcurrentMap<String, OssSession> active = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(true);

    public OssAudioRecordingService(OSS oss, String bucket, String prefix, int partSize, int queueCapacity,
                                    ConversationRecordingMapper recordings,
                                    ChatConversationMapper conversations) {
        this.oss = oss;
        this.bucket = bucket;
        this.prefix = normalizePrefix(prefix);
        this.partSize = Math.max(MIN_PART_SIZE, partSize);
        this.queueCapacity = Math.max(64, queueCapacity);
        this.recordings = recordings;
        this.conversations = conversations;
    }

    @Override
    public Session start(String userId, String sessionId) {
        if (!running.get() || userId == null || userId.isBlank()) {
            return Session.NOOP;
        }
        try {
            return new OssSession(Long.parseLong(userId), sessionId);
        } catch (Exception e) {
            log.warn("启动 OSS 语音录音失败, session={}: {}", sessionId, e.toString());
            return Session.NOOP;
        }
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        List<OssSession> sessions = new ArrayList<>(active.values());
        sessions.forEach(OssSession::close);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        for (OssSession session : sessions) {
            session.await(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        }
        oss.shutdown();
    }

    private static String normalizePrefix(String value) {
        String p = value == null ? "recordings" : value.trim();
        while (p.startsWith("/")) p = p.substring(1);
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p.isEmpty() ? "recordings" : p;
    }

    private enum Track { USER_RAW, USER_DIALOGUE, ASSISTANT }

    private record AudioPart(Track track, byte[] data, int sampleRate) { }

    private final class OssSession implements Session {
        private final String id = UUID.randomUUID().toString();
        private final long userId;
        private final String wsSessionId;
        private final LocalDateTime startedAt = LocalDateTime.now();
        private final BlockingQueue<AudioPart> queue = new ArrayBlockingQueue<>(queueCapacity);
        private final AtomicBoolean accepting = new AtomicBoolean(true);
        private final AtomicBoolean started = new AtomicBoolean(false);
        private final AtomicLong dropped = new AtomicLong();
        private volatile Long conversationId;
        private Thread worker;

        private OssSession(long userId, String wsSessionId) {
            this.userId = userId;
            this.wsSessionId = wsSessionId;
        }

        private synchronized void ensureStarted() {
            if (!accepting.get() || !started.compareAndSet(false, true)) {
                return;
            }
            active.put(id, this);
            worker = new Thread(this::uploadLoop, "vca-oss-rec-" + id.substring(0, 8));
            worker.setDaemon(true);
            worker.start();
        }

        @Override
        public void setConversationId(Long conversationId) {
            this.conversationId = ownsConversation(conversationId) ? conversationId : null;
        }

        private boolean ownsConversation(Long candidate) {
            if (candidate == null) return false;
            try {
                ChatConversation c = conversations.selectById(candidate);
                return c != null && c.getUserId() != null && c.getUserId() == userId;
            } catch (Exception e) {
                log.warn("校验录音所属会话失败, recording={}: {}", id, e.toString());
                return false;
            }
        }

        @Override
        public void appendUserAudio(byte[] pcm16le, int sampleRate) {
            append(Track.USER_RAW, pcm16le, sampleRate);
        }

        @Override
        public void appendConversationUserAudio(byte[] pcm16le, int sampleRate) {
            append(Track.USER_DIALOGUE, pcm16le, sampleRate);
        }

        @Override
        public void appendAssistantAudio(byte[] pcm16le, int sampleRate) {
            append(Track.ASSISTANT, pcm16le, sampleRate <= 0 ? 24_000 : sampleRate);
        }

        private void append(Track track, byte[] data, int sampleRate) {
            if (!accepting.get() || data == null || data.length == 0 || sampleRate <= 0) return;
            ensureStarted();
            if (!accepting.get() || !started.get()) return;
            if (!queue.offer(new AudioPart(track, Arrays.copyOf(data, data.length), sampleRate))) {
                long n = dropped.incrementAndGet();
                if (n == 1 || n % 100 == 0) {
                    log.warn("OSS 录音队列已满, recording={}, 累计丢弃 {} 个音频块", id, n);
                }
            }
        }

        @Override
        public void close() {
            accepting.set(false);
        }

        private void await(long timeout, TimeUnit unit) {
            Thread t = worker;
            if (t == null) return;
            try {
                long millis = Math.max(1, unit.toMillis(timeout));
                t.join(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void uploadLoop() {
            String day = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
            String baseKey = prefix + "/" + day + "/" + userId + "/" + id + "/";
            OssWavWriter user = new OssWavWriter(oss, bucket, baseKey + "user.wav", partSize, 48_000);
            OssWavWriter assistant = new OssWavWriter(oss, bucket, baseKey + "assistant.wav", partSize, 24_000);
            DialogueWriter dialogue = new DialogueWriter(
                    new OssWavWriter(oss, bucket, baseKey + "conversation.wav", partSize, 24_000));
            String status = "complete";
            insertMetadata(baseKey);
            try {
                while (accepting.get() || !queue.isEmpty()) {
                    AudioPart part = queue.poll(250, TimeUnit.MILLISECONDS);
                    if (part == null) continue;
                    switch (part.track()) {
                        case USER_RAW -> user.write(part.data(), part.sampleRate());
                        case USER_DIALOGUE -> dialogue.writeUser(part.data(), part.sampleRate());
                        case ASSISTANT -> {
                            assistant.write(part.data(), part.sampleRate());
                            dialogue.writeAssistant(part.data(), part.sampleRate());
                        }
                    }
                }
                if (dropped.get() > 0) status = "partial";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                status = "partial";
            } catch (Exception e) {
                status = "error";
                log.warn("上传录音分片失败, recording={}: {}", id, e.toString());
            } finally {
                status = finishWriter(user, status);
                status = finishWriter(assistant, status);
                status = finishWriter(dialogue.writer(), status);
                updateMetadata(baseKey, user, assistant, dialogue.writer(), status);
                active.remove(id);
                log.info("OSS 语音录音结束: recording={}, status={}, oss://{}/{}", id, status, bucket, baseKey);
            }
        }

        private String finishWriter(OssWavWriter writer, String status) {
            try {
                writer.close();
                return status;
            } catch (Exception e) {
                log.warn("完成 OSS WAV 上传失败, recording={}, key={}: {}", id, writer.key(), e.toString());
                return "error";
            }
        }

        private void insertMetadata(String baseKey) {
            ConversationRecording row = baseRow(baseKey);
            row.setStatus("recording");
            row.setStartedAt(startedAt);
            try {
                recordings.insert(row);
            } catch (Exception e) {
                log.warn("创建录音元数据失败, recording={}: {}", id, e.toString());
            }
        }

        private void updateMetadata(String baseKey, OssWavWriter user, OssWavWriter assistant,
                                    OssWavWriter dialogue, String status) {
            ConversationRecording row = baseRow(baseKey);
            row.setConversationId(conversationId);
            row.setUserSampleRate(rateOrNull(user));
            row.setAssistantSampleRate(rateOrNull(assistant));
            row.setUserBytes(user.dataBytes());
            row.setAssistantBytes(assistant.dataBytes());
            row.setConversationBytes(dialogue.dataBytes());
            row.setDurationMs(Math.max(duration(dialogue), Math.max(duration(user), duration(assistant))));
            row.setStatus(status);
            row.setEndedAt(LocalDateTime.now());
            try {
                if (recordings.updateById(row) == 0) {
                    row.setStartedAt(startedAt);
                    recordings.insert(row);
                }
            } catch (Exception e) {
                log.warn("更新录音元数据失败, recording={}: {}", id, e.toString());
            }
        }

        private long duration(OssWavWriter writer) {
            return writer.sampleRate() <= 0 ? 0 : writer.dataBytes() * 1000L / (writer.sampleRate() * 2L);
        }

        private Integer rateOrNull(OssWavWriter writer) {
            return writer.dataBytes() == 0 ? null : writer.sampleRate();
        }

        private ConversationRecording baseRow(String baseKey) {
            ConversationRecording row = new ConversationRecording();
            row.setId(id);
            row.setUserId(userId);
            row.setConversationId(conversationId);
            row.setSessionId(wsSessionId);
            row.setOssBucket(bucket);
            // 兼容第一版本字段名：user_file/assistant_file 现在保存的是 OSS Object Key，不是服务器路径。
            row.setUserFile(baseKey + "user.wav");
            row.setAssistantFile(baseKey + "assistant.wav");
            row.setConversationFile(baseKey + "conversation.wav");
            return row;
        }
    }

    /** 把用户/客服音频按到达顺序串成一条 24kHz 单声道对话，双方切换时插入自然停顿。 */
    static final class DialogueWriter {
        private static final int OUTPUT_RATE = 24_000;
        private enum Side { USER, ASSISTANT }

        private final OssWavWriter writer;
        private Side lastSide;

        DialogueWriter(OssWavWriter writer) {
            this.writer = writer;
        }

        OssWavWriter writer() {
            return writer;
        }

        void writeUser(byte[] pcm16le, int sampleRate) throws IOException {
            write(Side.USER, normalize(pcm16le, sampleRate));
        }

        void writeAssistant(byte[] pcm16le, int sampleRate) throws IOException {
            write(Side.ASSISTANT, normalize(pcm16le, sampleRate));
        }

        private void write(Side side, byte[] pcm24k) throws IOException {
            if (lastSide != null && lastSide != side) {
                int silenceMs = side == Side.ASSISTANT ? 300 : 500;
                writer.write(new byte[OUTPUT_RATE * 2 * silenceMs / 1000], OUTPUT_RATE);
            }
            writer.write(pcm24k, OUTPUT_RATE);
            lastSide = side;
        }

        private byte[] normalize(byte[] pcm16le, int sampleRate) {
            if (sampleRate == OUTPUT_RATE) {
                return pcm16le;
            }
            return PcmAudio.encodeLe(PcmAudio.resample(
                    PcmAudio.decodeLe(pcm16le), sampleRate, OUTPUT_RATE));
        }
    }

    /** 单音轨 WAV 的无磁盘 OSS 分片写入器。仅工作线程调用，无需额外同步。 */
    static final class OssWavWriter implements AutoCloseable {
        private final OSS oss;
        private final String bucket;
        private final String key;
        private final int partSize;
        private final int defaultSampleRate;
        private final ByteArrayOutputStream firstPart;
        private final ByteArrayOutputStream currentPart;
        private final List<PartETag> partETags = new ArrayList<>();
        private int sampleRate;
        private long dataBytes;
        private int nextPartNumber = 2;
        private String uploadId;
        private boolean failed;
        private boolean closed;

        OssWavWriter(OSS oss, String bucket, String key, int partSize, int defaultSampleRate) {
            this.oss = oss;
            this.bucket = bucket;
            this.key = key;
            this.partSize = Math.max(MIN_PART_SIZE, partSize);
            this.defaultSampleRate = defaultSampleRate;
            this.firstPart = new ByteArrayOutputStream(this.partSize);
            this.currentPart = new ByteArrayOutputStream(this.partSize);
        }

        void write(byte[] pcm, int rate) throws IOException {
            if (sampleRate == 0) sampleRate = rate;
            else if (sampleRate != rate) throw new IOException("同一音轨采样率发生变化: " + sampleRate + " -> " + rate);

            int offset = 0;
            int firstCapacity = partSize - 44;
            if (firstPart.size() < firstCapacity) {
                int length = Math.min(firstCapacity - firstPart.size(), pcm.length);
                firstPart.write(pcm, 0, length);
                offset += length;
            }
            while (offset < pcm.length) {
                int length = Math.min(partSize - currentPart.size(), pcm.length - offset);
                currentPart.write(pcm, offset, length);
                offset += length;
                if (currentPart.size() == partSize) uploadCurrentPart();
            }
            dataBytes += pcm.length;
        }

        String key() { return key; }
        int sampleRate() { return sampleRate; }
        long dataBytes() { return dataBytes; }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            int rate = sampleRate == 0 ? defaultSampleRate : sampleRate;
            try {
                if (failed) {
                    abort();
                    throw new IllegalStateException("此前 OSS 分片上传已经失败");
                }
                if (uploadId == null && currentPart.size() == 0) {
                    byte[] object = concat(wavHeader(dataBytes, rate), firstPart.toByteArray());
                    ObjectMetadata metadata = audioMetadata(object.length, rate);
                    PutObjectRequest request = new PutObjectRequest(bucket, key, new ByteArrayInputStream(object));
                    request.setMetadata(metadata);
                    oss.putObject(request);
                    return;
                }
                ensureMultipart(rate);
                if (currentPart.size() > 0) uploadCurrentPart();
                uploadPart(concat(wavHeader(dataBytes, rate), firstPart.toByteArray()), 1);
                partETags.sort(Comparator.comparingInt(PartETag::getPartNumber));
                oss.completeMultipartUpload(new CompleteMultipartUploadRequest(bucket, key, uploadId, partETags));
            } catch (RuntimeException e) {
                failed = true;
                abort();
                throw e;
            }
        }

        private void uploadCurrentPart() {
            ensureMultipart(sampleRate == 0 ? defaultSampleRate : sampleRate);
            byte[] bytes = currentPart.toByteArray();
            currentPart.reset();
            uploadPart(bytes, nextPartNumber++);
        }

        private void ensureMultipart(int rate) {
            if (uploadId != null) return;
            InitiateMultipartUploadRequest request = new InitiateMultipartUploadRequest(bucket, key);
            request.setObjectMetadata(audioMetadata(-1, rate));
            uploadId = oss.initiateMultipartUpload(request).getUploadId();
        }

        private void uploadPart(byte[] bytes, int number) {
            try {
                UploadPartRequest request = new UploadPartRequest();
                request.setBucketName(bucket);
                request.setKey(key);
                request.setUploadId(uploadId);
                request.setInputStream(new ByteArrayInputStream(bytes));
                request.setPartSize(bytes.length);
                request.setPartNumber(number);
                partETags.add(oss.uploadPart(request).getPartETag());
            } catch (RuntimeException e) {
                failed = true;
                throw e;
            }
        }

        private void abort() {
            if (uploadId == null) return;
            try {
                oss.abortMultipartUpload(new AbortMultipartUploadRequest(bucket, key, uploadId));
            } catch (Exception e) {
                log.warn("取消 OSS 分片上传失败, key={}: {}", key, e.toString());
            }
        }

        private static ObjectMetadata audioMetadata(long length, int rate) {
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentType("audio/wav");
            metadata.setContentDisposition("inline");
            metadata.addUserMetadata("sample-rate", String.valueOf(rate));
            metadata.addUserMetadata("channels", "1");
            metadata.addUserMetadata("bits-per-sample", "16");
            if (length >= 0) metadata.setContentLength(length);
            return metadata;
        }
    }

    static byte[] wavHeader(long dataBytes, int sampleRate) {
        if (dataBytes > 0xffff_ffffL - 36) throw new IllegalArgumentException("WAV 超过 4GB");
        byte[] header = new byte[44];
        putAscii(header, 0, "RIFF");
        putLe32(header, 4, 36 + dataBytes);
        putAscii(header, 8, "WAVE");
        putAscii(header, 12, "fmt ");
        putLe32(header, 16, 16);
        putLe16(header, 20, 1);
        putLe16(header, 22, 1);
        putLe32(header, 24, sampleRate);
        putLe32(header, 28, sampleRate * 2L);
        putLe16(header, 32, 2);
        putLe16(header, 34, 16);
        putAscii(header, 36, "data");
        putLe32(header, 40, dataBytes);
        return header;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] value = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, value, a.length, b.length);
        return value;
    }

    private static void putAscii(byte[] target, int offset, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, target, offset, bytes.length);
    }

    private static void putLe16(byte[] target, int offset, int value) {
        target[offset] = (byte) value;
        target[offset + 1] = (byte) (value >>> 8);
    }

    private static void putLe32(byte[] target, int offset, long value) {
        target[offset] = (byte) value;
        target[offset + 1] = (byte) (value >>> 8);
        target[offset + 2] = (byte) (value >>> 16);
        target[offset + 3] = (byte) (value >>> 24);
    }
}
