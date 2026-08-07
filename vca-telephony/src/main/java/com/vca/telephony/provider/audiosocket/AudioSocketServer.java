package com.vca.telephony.provider.audiosocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * AudioSocket 的 TCP 服务端。Asterisk 每接通一路就连过来一条 TCP, 一条连接 = 一路
 * {@link AudioSocketCallLeg}。
 *
 * <p><b>建连后的顺序很关键</b>: 先建 leg → 回调 {@code onCall}(上层在这里建 {@code CallSession}
 * 并 {@code attach()}) → 最后才开读泵。这样首帧一定落在订阅之后, 不依赖 sink 的缓冲行为兜底。
 *
 * <p>线程模型是每路通话一个读线程(阻塞读)。20ms 一帧的节奏下这些线程绝大多数时间在阻塞,
 * 几百路并发在现代 JVM 上没有问题; 真要上到数千路再换 Reactor Netty ——
 * <b>那时只需要替换本类</b>, {@link AudioSocketCallLeg} 与上层都不用动。
 */
public final class AudioSocketServer implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(AudioSocketServer.class);

    private final AudioSocketConfig cfg;
    private final Consumer<AudioSocketCallLeg> onCall;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicInteger callSeq = new AtomicInteger();

    private ServerSocket serverSocket;
    private ExecutorService acceptor;
    private ExecutorService workers;

    public AudioSocketServer(AudioSocketConfig cfg, Consumer<AudioSocketCallLeg> onCall) {
        this.cfg = cfg == null ? AudioSocketConfig.defaults() : cfg;
        this.onCall = onCall;
    }

    /** 绑定端口并开始 accept。 */
    public void start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(cfg.port()), cfg.acceptBacklog());
        acceptor = Executors.newSingleThreadExecutor(r -> named(r, "audiosocket-accept"));
        workers = Executors.newCachedThreadPool(r -> named(r, "audiosocket-call-" + callSeq.incrementAndGet()));
        acceptor.submit(this::acceptLoop);
        log.info("AudioSocket 监听 {}, 采样率 {}Hz", port(), cfg.sampleRate());
    }

    /** 实际监听端口(配置为 0 时由系统分配, 单测据此连接)。 */
    public int port() {
        return serverSocket == null ? cfg.port() : serverSocket.getLocalPort();
    }

    private void acceptLoop() {
        while (running.get() && !serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                // 20ms 一帧的小包, 必须关 Nagle, 否则下行会被攒包攒出几十毫秒抖动
                socket.setTcpNoDelay(true);
                workers.submit(() -> serve(socket));
            } catch (IOException e) {
                if (running.get()) {
                    log.warn("accept 失败: {}", e.toString());
                }
                return;
            }
        }
    }

    private void serve(Socket socket) {
        AudioSocketCallLeg leg = new AudioSocketCallLeg(socket, cfg);
        try {
            onCall.accept(leg);   // 上层在此建 CallSession 并订阅
        } catch (RuntimeException e) {
            log.error("建立通话会话失败, 直接挂断: {}", e.toString(), e);
            leg.hangup("session-setup-failed");
            return;
        }
        leg.pump();   // 订阅就位后才开泵
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            log.debug("关闭监听异常: {}", e.toString());
        }
        if (acceptor != null) {
            acceptor.shutdownNow();
        }
        if (workers != null) {
            workers.shutdownNow();
        }
        log.info("AudioSocket 服务端已停止");
    }

    private static Thread named(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }
}
