package com.vca.telephony.provider.freeswitch;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;

/**
 * 连一次事件套接字、执行一条 {@code api} 命令、断开。运营后台开通网关、查注册状态用。
 *
 * <p>为什么不复用外呼的 {@link EslClient}: 那是常驻连接, 带断线重连和事件订阅, 且只在开了外呼时才建;
 * 运营操作一天几次, 用完即断最简单, 也不受外呼开关影响。
 */
public final class EslCommand {

    private EslCommand() {
    }

    /**
     * @return api 命令的输出正文
     * @throws IOException 连不上、认证失败、超时
     */
    public static String api(String host, int port, String password, String command, int timeoutMs) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            s.setSoTimeout(timeoutMs);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();

            EslMessage hello = EslMessage.read(in);
            if (hello == null) {
                throw new IOException("连接被立即关闭(FreeSWITCH 可能还没启动完)");
            }
            if ("text/rude-rejection".equals(hello.contentType())) {
                throw new IOException("FreeSWITCH 拒绝了本机地址(event_socket.conf 的 apply-inbound-acl)");
            }
            if (!EslMessage.CT_AUTH_REQUEST.equals(hello.contentType())) {
                throw new IOException("对端不像 FreeSWITCH 事件套接字");
            }
            out.write(EslMessage.command("auth " + password));
            out.flush();
            EslMessage auth = until(in, EslMessage.CT_REPLY);
            if (!auth.isOk()) {
                throw new IOException("事件套接字认证被拒(密码不对?): " + auth.replyText());
            }
            out.write(EslMessage.command("api " + command));
            out.flush();
            return until(in, EslMessage.CT_API).body();
        } catch (SocketTimeoutException e) {
            throw new IOException("等 FreeSWITCH 应答超时: " + command, e);
        }
    }

    private static EslMessage until(InputStream in, String contentType) throws IOException {
        while (true) {
            EslMessage msg = EslMessage.read(in);
            if (msg == null) {
                throw new IOException("FreeSWITCH 断开了连接");
            }
            if (contentType.equals(msg.contentType())) {
                return msg;
            }
        }
    }
}
