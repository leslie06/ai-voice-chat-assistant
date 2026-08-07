package com.vca.telephony.provider.ami;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * AMI 客户端: 连 Asterisk、登录、发 Action、收 Response 与 Event。
 *
 * <p>职责边界故意划得很窄 —— 它只管<b>协议</b>, 不知道什么是外呼。外呼语义在
 * {@link AmiTelephonyProvider}。这样协议层能对着一个假 AMI 服务端跑真实 TCP 单测。
 *
 * <p>报文相关(Response 与 Action 的配对)靠 {@code ActionID}。事件没有对应的 Action, 走
 * {@link #onEvent} 回调。
 */
public final class AmiClient implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(AmiClient.class);

    private final AmiConfig cfg;
    private final Map<String, CompletableFuture<AmiPacket>> awaiting = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private final Object writeLock = new Object();

    private Socket socket;
    private BufferedReader in;
    private OutputStream out;
    private ExecutorService reader;
    private volatile Consumer<AmiPacket> eventHandler = p -> { };

    public AmiClient(AmiConfig cfg) {
        this.cfg = cfg;
    }

    /** 注册事件回调。只允许一个, 由上层自己分发。 */
    public void onEvent(Consumer<AmiPacket> handler) {
        this.eventHandler = handler == null ? p -> { } : handler;
    }

    /**
     * 连接并登录。失败抛异常 —— 外呼服务连不上 Asterisk 就该启动失败, 而不是静默地拨不出去。
     */
    public void connect() throws IOException {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        socket = new Socket();
        socket.connect(new InetSocketAddress(cfg.host(), cfg.port()), cfg.connectTimeoutMs());
        socket.setTcpNoDelay(true);
        in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        out = socket.getOutputStream();

        // 欢迎语是<b>单独一行</b>(如 "Asterisk Call Manager/7.0.0"), 不是报文, 后面不跟空行。
        // 不先把它读掉, 第一个报文的解析就会错位。
        socket.setSoTimeout(cfg.connectTimeoutMs());
        String banner = in.readLine();
        log.info("AMI 已连接 {}:{} —— {}", cfg.host(), cfg.port(), banner);

        AmiPacket login = AmiPacket.action("Login",
                "Username", cfg.username(), "Secret", cfg.secret(), "Events", "on");
        String loginId = "login-" + System.nanoTime();
        login = login.with("ActionID", loginId);
        CompletableFuture<AmiPacket> pending = new CompletableFuture<>();
        awaiting.put(loginId, pending);
        writeRaw(login.encode());

        // 登录响应必须同步等到: 没登录成功后面发什么都是白发
        AmiPacket resp = awaitLoginResponse(pending, loginId);
        if (!resp.isSuccess()) {
            close();
            throw new IOException("AMI 登录被拒: " + resp.getOrDefault("Message", "(无消息)"));
        }
        socket.setSoTimeout(0);
        reader = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ami-reader");
            t.setDaemon(true);
            return t;
        });
        reader.submit(this::readLoop);
    }

    /** 登录阶段还没有读线程, 只能就地把报文读出来。 */
    private AmiPacket awaitLoginResponse(CompletableFuture<AmiPacket> pending, String loginId) throws IOException {
        AmiPacket packet;
        while ((packet = readPacket()) != null) {
            if (loginId.equals(packet.actionId()) || packet.isResponse()) {
                awaiting.remove(loginId);
                return packet;
            }
        }
        awaiting.remove(loginId);
        throw new IOException("AMI 登录无响应, 连接已断");
    }

    /**
     * 发一个 Action 并等它的 Response。
     *
     * @param timeoutMs 等待上限
     */
    public AmiPacket send(AmiPacket action, String actionId, long timeoutMs) throws IOException {
        CompletableFuture<AmiPacket> pending = new CompletableFuture<>();
        awaiting.put(actionId, pending);
        try {
            writeRaw(action.encode());
            return pending.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new IOException("AMI Action 超时: " + actionId, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("AMI Action 被中断: " + actionId, e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IOException("AMI Action 失败: " + actionId, e.getCause());
        } finally {
            awaiting.remove(actionId);
        }
    }

    private void writeRaw(String text) throws IOException {
        synchronized (writeLock) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    private void readLoop() {
        try {
            AmiPacket packet;
            while (running.get() && (packet = readPacket()) != null) {
                dispatch(packet);
            }
        } catch (IOException e) {
            if (running.get()) {
                log.warn("AMI 读中断: {}", e.toString());
            }
        } finally {
            failAllAwaiting();
        }
    }

    private void dispatch(AmiPacket packet) {
        String id = packet.actionId();
        if (id != null) {
            CompletableFuture<AmiPacket> pending = awaiting.get(id);
            // Response 才算应答; 带同一 ActionID 的事件(如 OriginateResponse)要继续走事件回调
            if (pending != null && packet.isResponse()) {
                pending.complete(packet);
                return;
            }
        }
        if (packet.event() != null) {
            try {
                eventHandler.accept(packet);
            } catch (RuntimeException e) {
                log.warn("AMI 事件处理异常({}): {}", packet.event(), e.toString());
            }
        }
    }

    /** 读一个报文: 累积到空行为止。流结束返回 null。 */
    private AmiPacket readPacket() throws IOException {
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isEmpty()) {
                if (sb.length() == 0) {
                    continue;   // 连续空行, 跳过
                }
                return AmiPacket.parse(sb.toString());
            }
            sb.append(line).append('\n');
        }
        return null;
    }

    private void failAllAwaiting() {
        IOException err = new IOException("AMI 连接已断开");
        awaiting.values().forEach(f -> f.completeExceptionally(err));
        awaiting.clear();
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException e) {
            log.debug("关闭 AMI socket 异常: {}", e.toString());
        }
        if (reader != null) {
            reader.shutdownNow();
        }
        failAllAwaiting();
        log.info("AMI 连接已关闭");
    }

    public boolean isConnected() {
        return running.get() && socket != null && !socket.isClosed();
    }
}
