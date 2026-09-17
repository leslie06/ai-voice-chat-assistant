package com.vca.telephony.provider.freeswitch;

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
 * 接 FreeSWITCH 拨号计划里 {@code socket} 应用连过来的 TCP(FreeSWITCH 称之为 outbound socket:
 * 从它的角度是往外连)。<b>一条连接 = 一路通话</b>, 呼入和外呼都从这里进来。
 *
 * <p>每条连接的顺序固定:
 * <ol>
 *   <li>{@link FreeSwitchCallLeg#handshake()} —— 拿到 callId 与号码, 下发 unicast;</li>
 *   <li>回调 {@code onCall} —— 上层建 {@code CallSession} 并订阅;</li>
 *   <li>最后才开两个泵(信令、媒体)—— 保证 ANSWERED 与首个音频包都落在订阅之后。</li>
 * </ol>
 *
 * <p>线程模型: 每路通话两个阻塞读线程(信令、媒体)。20ms 一包的节奏下它们绝大多数时间在阻塞,
 * 几百路并发没有问题; 真到数千路再换 NIO —— 那时只需要替换本类和 leg 的两个泵, 上层不动。
 */
public final class FreeSwitchSocketServer implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(FreeSwitchSocketServer.class);

    private final FreeSwitchConfig cfg;
    private final Consumer<FreeSwitchCallLeg> onCall;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicInteger threadSeq = new AtomicInteger();

    private ServerSocket serverSocket;
    private ExecutorService acceptor;
    private ExecutorService workers;

    public FreeSwitchSocketServer(FreeSwitchConfig cfg, Consumer<FreeSwitchCallLeg> onCall) {
        this.cfg = cfg == null ? FreeSwitchConfig.defaults() : cfg;
        this.onCall = onCall;
    }

    public void start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(cfg.listenAddress(), cfg.port()), cfg.acceptBacklog());
        acceptor = Executors.newSingleThreadExecutor(r -> named(r, "fs-socket-accept"));
        workers = Executors.newCachedThreadPool(r -> named(r, "fs-call-" + threadSeq.incrementAndGet()));
        acceptor.submit(this::acceptLoop);
        log.info("FreeSWITCH socket 监听 {}:{}, 线路 {}Hz, 媒体绑定 {}",
                cfg.listenAddress(), port(), cfg.sampleRate(), cfg.mediaBindAddress());
    }

    /** 实际监听端口(配置为 0 时由系统分配, 单测据此连接) */
    public int port() {
        return serverSocket == null ? cfg.port() : serverSocket.getLocalPort();
    }

    private void acceptLoop() {
        while (running.get() && !serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                socket.setKeepAlive(true);
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
        FreeSwitchCallLeg leg;
        try {
            leg = new FreeSwitchCallLeg(socket, cfg);
            leg.handshake();
        } catch (IOException e) {
            log.warn("FreeSWITCH 通话握手失败, 放弃这路(来自 {}): {}", socket.getRemoteSocketAddress(), e.getMessage());
            closeQuietly(socket);
            return;
        }
        try {
            onCall.accept(leg);   // 上层在此建 CallSession 并订阅
        } catch (RuntimeException e) {
            log.error("[{}] 建立通话会话失败, 直接挂断: {}", leg.callId(), e.toString(), e);
            leg.hangup("session-setup-failed");
            leg.pumpSignaling();  // 把挂机走完, 顺带收掉连接
            return;
        }
        workers.submit(leg::pumpMedia);
        leg.pumpSignaling();
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // 已在错误路径上
        }
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
        log.info("FreeSWITCH socket 服务端已停止");
    }

    private static Thread named(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }
}
