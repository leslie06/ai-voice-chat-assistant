package com.vca.telephony.provider.ami;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 扮演 Asterisk 的假 AMI 服务端。有了它, AMI 协议层(登录握手、ActionID 配对、事件分发)
 * 就能在没装 Asterisk 的机器上跑真实 TCP 验完。
 *
 * <p>行为: 发欢迎语行 → 对任何带 ActionID 的 Action 回 {@code Response: Success} →
 * 把收到的 Action 塞进队列供断言 → 支持主动推事件。
 */
final class FakeAmiServer implements Closeable {

    private final ServerSocket server;
    private final Thread acceptor;
    private final BlockingQueue<AmiPacket> received = new LinkedBlockingQueue<>();
    private volatile OutputStream out;
    private volatile boolean rejectLogin;

    FakeAmiServer() throws IOException {
        this.server = new ServerSocket(0);
        this.acceptor = new Thread(this::serve, "fake-ami");
        this.acceptor.setDaemon(true);
        this.acceptor.start();
    }

    int port() {
        return server.getLocalPort();
    }

    void rejectLogin() {
        this.rejectLogin = true;
    }

    private void serve() {
        try (Socket socket = server.accept()) {
            out = socket.getOutputStream();
            write("Asterisk Call Manager/7.0.0\r\n");   // 欢迎语: 单独一行, 不是报文
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder buf = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) {
                if (!line.isEmpty()) {
                    buf.append(line).append('\n');
                    continue;
                }
                if (buf.length() == 0) {
                    continue;
                }
                AmiPacket packet = AmiPacket.parse(buf.toString());
                buf.setLength(0);
                received.add(packet);
                respond(packet);
            }
        } catch (IOException e) {
            // 测试收尾时关连接会走到这里, 正常
        }
    }

    private void respond(AmiPacket action) throws IOException {
        String id = action.actionId();
        if (id == null) {
            return;
        }
        boolean deny = rejectLogin && "Login".equalsIgnoreCase(action.getOrDefault("Action", ""));
        write("Response: " + (deny ? "Error" : "Success") + "\r\nActionID: " + id
                + "\r\nMessage: " + (deny ? "Authentication failed" : "accepted") + "\r\n\r\n");
    }

    /** 推一个事件(如异步 Originate 的最终结果) */
    void pushEvent(String raw) throws IOException {
        write(raw.endsWith("\r\n\r\n") ? raw : raw + "\r\n\r\n");
    }

    private synchronized void write(String text) throws IOException {
        OutputStream o = out;
        if (o != null) {
            o.write(text.getBytes(StandardCharsets.UTF_8));
            o.flush();
        }
    }

    /** 等一个指定 Action 的报文 */
    AmiPacket awaitAction(String action, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            AmiPacket p = received.poll(200, TimeUnit.MILLISECONDS);
            if (p != null && action.equalsIgnoreCase(p.getOrDefault("Action", ""))) {
                return p;
            }
        }
        throw new AssertionError("没等到 Action: " + action);
    }

    @Override
    public void close() throws IOException {
        server.close();
        acceptor.interrupt();
    }
}
