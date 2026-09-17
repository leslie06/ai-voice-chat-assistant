package com.vca.telephony.provider.freeswitch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * ESL 客户端(FreeSWITCH 称之为 inbound socket: 我们连进它的 8021)。只用来发起外呼。
 *
 * <p>职责只到<b>协议</b>: 认证、发命令等应答、分发事件。外呼语义在 {@link FreeSwitchTelephonyProvider},
 * 这样协议层能对着一个假 FreeSWITCH 跑真实 TCP 单测。
 *
 * <p><b>应答怎么配对</b>: ESL 的命令应答不带任何关联 id, 靠的是"同一条连接上严格按发送顺序应答"。
 * 所以"写命令"和"排进等待队列"在同一把锁里做, 读线程每收到一条应答就交给队首。事件则穿插着到, 走回调。
 * 外呼结果不看应答(bgapi 立即应答 +OK), 看之后的 BACKGROUND_JOB 事件, 用我们自己指定的 Job-UUID 对上。
 *
 * <p><b>断线自动重连</b>: FreeSWITCH 重启(部署、改配置)是常事, 不能要求跟着重启本进程。
 * 读线程发现断开后按 1s→2s→4s…封顶 30s 退避重连; 断开期间发起的外呼立刻失败, 不排队。
 * 只有<b>首次</b>连接失败才抛异常让启动失败 —— 那通常是配置错了。
 */
public final class EslClient implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(EslClient.class);

    private final EslConfig cfg;
    private final Deque<CompletableFuture<EslMessage>> awaiting = new ArrayDeque<>();
    private final Object writeLock = new Object();
    private final AtomicBoolean started = new AtomicBoolean();

    /** 重连退避上限(ms) */
    private static final long MAX_RECONNECT_DELAY_MS = 30_000;

    private volatile Socket socket;
    private volatile InputStream in;
    private volatile OutputStream out;
    /** 已认证并订阅完成, 可以发命令 */
    private volatile boolean connected;
    /** 已被 close(), 不再重连 */
    private volatile boolean closed;
    private ExecutorService reader;
    private volatile Consumer<EslMessage> eventHandler = e -> { };

    public EslClient(EslConfig cfg) {
        this.cfg = cfg;
    }

    /** 注册事件回调(收到的是已解析好的事件本体)。只允许一个, 由上层自己分发。 */
    public void onEvent(Consumer<EslMessage> handler) {
        this.eventHandler = handler == null ? e -> { } : handler;
    }

    /**
     * 连接、认证、订阅 BACKGROUND_JOB。失败抛异常 —— 外呼服务连不上 FreeSWITCH 就该启动失败,
     * 而不是静默地拨不出去。
     */
    public void connect() throws IOException {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        try {
            openAndAuth();
        } catch (IOException | RuntimeException e) {
            close();
            throw e instanceof IOException io ? io : new IOException(e);
        }
        reader = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "esl-reader");
            t.setDaemon(true);
            return t;
        });
        reader.submit(this::readLoop);
        log.info("FreeSWITCH ESL 已连接 {}:{}", cfg.host(), cfg.port());
    }

    /** 建 TCP、认证、订阅。成功后 {@link #connected} 置 true; 失败时关掉半开的连接并抛出。 */
    private void openAndAuth() throws IOException {
        Socket s = new Socket();
        try {
            socket = s;
            s.connect(new InetSocketAddress(cfg.host(), cfg.port()), cfg.connectTimeoutMs());
            s.setTcpNoDelay(true);
            s.setKeepAlive(true);
            s.setSoTimeout(cfg.connectTimeoutMs());
            in = new BufferedInputStream(s.getInputStream());
            out = s.getOutputStream();

            // 连上后 FreeSWITCH 先发 auth/request, 在这之前发任何东西都会被忽略
            EslMessage hello = EslMessage.read(in);
            if (hello != null && "text/rude-rejection".equals(hello.contentType())) {
                // 不是密码错, 是地址不在 event_socket.conf 的 apply-inbound-acl 名单里(不配时默认只放行回环)
                throw new IOException("FreeSWITCH 拒绝了本机地址(event_socket.conf 的 apply-inbound-acl 名单不包含它; "
                        + "经 Docker 端口转发连入时源地址是网桥网关): " + hello.body().strip());
            }
            if (hello == null) {
                // 常见于 FreeSWITCH 正在启动: Docker 的端口转发先接下连接, 发现容器里还没监听就立刻关掉
                throw new IOException("连接被立即关闭(FreeSWITCH 可能还没启动完)");
            }
            if (!EslMessage.CT_AUTH_REQUEST.equals(hello.contentType())) {
                throw new IOException("对端不像 FreeSWITCH 事件套接字(没有 auth/request): " + hello);
            }
            EslMessage auth = syncRequest(EslMessage.command("auth " + cfg.password()));
            if (!auth.isOk()) {
                throw new IOException("ESL 认证被拒: " + auth.replyText());
            }
            EslMessage sub = syncRequest(EslMessage.command("event plain BACKGROUND_JOB"));
            if (!sub.isOk()) {
                throw new IOException("ESL 订阅事件失败: " + sub.replyText());
            }
            s.setSoTimeout(0);
            connected = true;
        } catch (IOException | RuntimeException e) {
            closeQuietly(s);
            throw e;
        }
    }

    /** 读线程起来之前(认证阶段)就地读应答 */
    private EslMessage syncRequest(byte[] command) throws IOException {
        synchronized (writeLock) {
            out.write(command);
            out.flush();
        }
        while (true) {
            EslMessage msg;
            try {
                msg = EslMessage.read(in);
            } catch (SocketTimeoutException e) {
                throw new IOException("ESL 认证阶段等应答超时", e);
            }
            if (msg == null) {
                throw new IOException("ESL 连接在认证阶段断开");
            }
            if (EslMessage.CT_REPLY.equals(msg.contentType())) {
                return msg;
            }
        }
    }

    /**
     * 发 {@code bgapi} 并等它的<b>受理</b>应答(不是执行结果)。执行结果以 BACKGROUND_JOB 事件异步到达,
     * 其 {@code Job-UUID} 就是这里传入的 jobUuid。
     *
     * @return 应答(+OK Job-UUID: ... / -ERR ...)
     */
    public EslMessage bgapi(String command, String jobUuid, long timeoutMs) throws IOException {
        byte[] bytes = EslMessage.command("bgapi " + command, "Job-UUID", jobUuid);
        CompletableFuture<EslMessage> reply = new CompletableFuture<>();
        synchronized (writeLock) {
            if (!isConnected()) {
                throw new IOException("FreeSWITCH ESL 未连接(断线重连中或已关闭)");
            }
            awaiting.addLast(reply);   // 与写入在同一把锁里: 队列顺序 = 线上发送顺序
            try {
                out.write(bytes);
                out.flush();
            } catch (IOException e) {
                awaiting.remove(reply);
                throw e;
            }
        }
        try {
            return reply.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new IOException("ESL 命令应答超时: bgapi", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("ESL 命令被中断", e);
        } catch (ExecutionException e) {
            throw new IOException("ESL 命令失败: " + e.getCause().getMessage(), e.getCause());
        }
    }

    /** 读线程: 读到断开就退避重连, 重连成功继续读, 直到 {@link #close()}。 */
    private void readLoop() {
        while (!closed) {
            try {
                EslMessage msg;
                while (!closed && (msg = EslMessage.read(in)) != null) {
                    dispatch(msg);
                }
                if (!closed) {
                    log.error("FreeSWITCH ESL 连接被对端关闭, 外呼暂不可用, 自动重连中");
                }
            } catch (IOException e) {
                if (!closed) {
                    log.error("FreeSWITCH ESL 读中断, 外呼暂不可用, 自动重连中: {}", e.toString());
                }
            }
            synchronized (writeLock) {
                connected = false;
            }
            closeQuietly(socket);
            failAllAwaiting();
            if (!reconnectUntilClosed()) {
                return;
            }
        }
    }

    /** @return true = 已重连; false = 客户端已关闭或线程被中断 */
    private boolean reconnectUntilClosed() {
        long delay = 1_000;
        while (!closed) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (closed) {
                return false;
            }
            try {
                openAndAuth();
                log.info("FreeSWITCH ESL 已重连 {}:{}, 外呼恢复", cfg.host(), cfg.port());
                return true;
            } catch (IOException | RuntimeException e) {
                log.warn("FreeSWITCH ESL 重连失败, {}ms 后重试: {}", Math.min(delay * 2, MAX_RECONNECT_DELAY_MS), e.getMessage());
                delay = Math.min(delay * 2, MAX_RECONNECT_DELAY_MS);
            }
        }
        return false;
    }

    private void dispatch(EslMessage msg) {
        switch (msg.contentType()) {
            case EslMessage.CT_REPLY, EslMessage.CT_API -> {
                CompletableFuture<EslMessage> head;
                synchronized (writeLock) {
                    head = awaiting.pollFirst();
                }
                if (head != null) {
                    head.complete(msg);
                } else {
                    log.debug("ESL 收到无人等待的应答: {}", msg.replyText());
                }
            }
            case EslMessage.CT_EVENT_PLAIN -> {
                try {
                    eventHandler.accept(msg.event());
                } catch (RuntimeException e) {
                    log.warn("ESL 事件处理异常: {}", e.toString());
                }
            }
            case EslMessage.CT_DISCONNECT -> log.warn("FreeSWITCH 通知断开 ESL 连接: {}", msg.body().strip());
            default -> { }
        }
    }

    private void failAllAwaiting() {
        IOException err = new IOException("ESL 连接已断开");
        synchronized (writeLock) {
            awaiting.forEach(f -> f.completeExceptionally(err));
            awaiting.clear();
        }
    }

    public boolean isConnected() {
        return connected && !closed;
    }

    @Override
    public void close() {
        closed = true;
        synchronized (writeLock) {
            connected = false;
        }
        closeQuietly(socket);
        if (reader != null) {
            reader.shutdownNow();   // 打断重连退避的 sleep
        }
        failAllAwaiting();
    }

    private static void closeQuietly(Socket s) {
        if (s == null) {
            return;
        }
        try {
            s.close();
        } catch (IOException e) {
            log.debug("关闭 ESL 连接异常: {}", e.toString());
        }
    }
}
