package com.vca.web.music;

import com.vca.domain.spi.MusicUploadStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 持久化审核队列。任务状态在数据库里，应用重启后会继续处理；任何错误都保持非公开。
 */
public final class MusicModerationCoordinator implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MusicModerationCoordinator.class);

    private final MusicUploadStore store;
    private final AliyunMusicModerationClient moderation;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean draining = new AtomicBoolean();

    public MusicModerationCoordinator(
            MusicUploadStore store, AliyunMusicModerationClient moderation) {
        this.store = store;
        this.moderation = moderation;
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "music-content-moderation");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::drain, 3, 15, TimeUnit.SECONDS);
    }

    public void kick() {
        executor.execute(this::drain);
    }

    private void drain() {
        if (!draining.compareAndSet(false, true)) {
            return;
        }
        try {
            for (MusicUploadStore.Upload upload : store.listForReview(10)) {
                review(upload);
            }
        } catch (Exception error) {
            log.warn("用户歌曲审核队列暂时失败，将自动重试: {}", error.toString());
        } finally {
            draining.set(false);
        }
    }

    private void review(MusicUploadStore.Upload upload) {
        try {
            String taskId = upload.audioTaskId();
            if (taskId == null || taskId.isBlank()) {
                AliyunMusicModerationClient.TextCheck text =
                        moderation.checkText(upload.title(), upload.artist());
                if (text.blocked()) {
                    store.completeReview(upload.id(), "rejected", null, text.labels(),
                            limited(text.reason(), 2048), Instant.now());
                    log.info("用户歌曲文本审核未通过: id={}, labels={}", upload.id(), text.labels());
                    return;
                }
                taskId = moderation.submitAudio(upload.audioObjectKey());
                store.markAudioSubmitted(upload.id(), text.labels(),
                        limited(text.reason(), 1024), taskId);
                log.info("用户歌曲音频审核已提交: id={}, taskId={}", upload.id(), taskId);
            }

            AliyunMusicModerationClient.AudioCheck audio = moderation.queryAudio(taskId);
            if (!audio.completed()) {
                return;
            }
            String status = audio.blocked() ? "rejected" : "approved";
            store.completeReview(upload.id(), status, audio.riskLevel(),
                    limited(audio.details(), 1024), limited(audio.reason(), 2048), Instant.now());
            log.info("用户歌曲审核完成: id={}, status={}, risk={}",
                    upload.id(), status, audio.riskLevel());
        } catch (Exception error) {
            // Fail closed，同时保留任务供下轮重试。不能因审核服务故障把歌曲公开。
            log.warn("用户歌曲审核暂时失败，将自动重试: id={} ({})",
                    upload.id(), error.toString());
        }
    }

    private static String limited(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
