package com.vca.telephony.provider.freeswitch;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 扮演 FreeSWITCH 的一路通道: 像拨号计划里的 {@code socket} 应用那样连到被测服务端,
 * 应答握手命令, 并用一个真实 UDP 口收发 unicast 音频。有了它, 接入层能在没装 FreeSWITCH 的机器上跑真实网络验完。
 */
final class FakeFreeSwitchChannel implements Closeable {

    /** 一条收到的命令: 首行 + 头部 */
    record Command(String line, Map<String, String> headers) {
    }

    private final Socket socket;
    private final BufferedReader in;
    private final OutputStream out;
    final DatagramSocket media;
    private volatile int remotePort = -1;

    FakeFreeSwitchChannel(int serverPort) throws IOException {
        socket = new Socket("127.0.0.1", serverPort);
        socket.setSoTimeout(3000);
        in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        out = socket.getOutputStream();
        media = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
        media.setSoTimeout(3000);
    }

    /** 走完一次标准握手, 返回 unicast 命令 */
    Command answerHandshake(String uuid, String caller, String callee) throws IOException {
        expect("connect");
        send("Event-Name: CHANNEL_DATA\nContent-Type: command/reply\nReply-Text: %2BOK%0A\nSocket-Mode: async\n"
                + "Unique-ID: " + uuid + "\nCaller-Caller-ID-Number: " + caller
                + "\nCaller-Destination-Number: " + callee
                + "\nChannel-Read-Codec-Name: PCMA\nChannel-Read-Codec-Rate: 8000"
                + "\nvariable_vca_media_local_ip: 127.0.0.1\nvariable_vca_media_remote_host: 127.0.0.1\n\n");
        expect("myevents");
        reply("+OK Events Enabled");
        Command linger = readCommand();
        if (!linger.line().startsWith("linger")) {
            throw new AssertionError("期望 linger, 实得 " + linger.line());
        }
        reply("+OK will linger");
        Command unicast = expect("sendmsg");
        remotePort = Integer.parseInt(unicast.headers().get("remote-port"));
        reply("+OK");
        return unicast;
    }

    Command expect(String line) throws IOException {
        Command c = readCommand();
        if (!c.line().equals(line)) {
            throw new AssertionError("期望命令 " + line + ", 实得 " + c.line());
        }
        return c;
    }

    Command readCommand() throws IOException {
        String first;
        do {
            first = in.readLine();
            if (first == null) {
                throw new IOException("对端已关闭");
            }
        } while (first.isEmpty());
        Map<String, String> headers = new LinkedHashMap<>();
        String line;
        while ((line = in.readLine()) != null && !line.isEmpty()) {
            int colon = line.indexOf(": ");
            headers.put(line.substring(0, colon), line.substring(colon + 2));
        }
        return new Command(first, headers);
    }

    /** 在 {@code timeoutMs} 内等一条命令; 没等到返回 null(用来断言"什么都没发") */
    Command pollCommand(int timeoutMs) throws IOException {
        int old = socket.getSoTimeout();
        socket.setSoTimeout(timeoutMs);
        try {
            return readCommand();
        } catch (SocketTimeoutException e) {
            return null;
        } finally {
            socket.setSoTimeout(old);
        }
    }

    void reply(String text) throws IOException {
        send("Content-Type: command/reply\nReply-Text: " + text + "\n\n");
    }

    void event(String eventBody) throws IOException {
        byte[] body = eventBody.getBytes(StandardCharsets.UTF_8);
        send("Content-Length: " + body.length + "\nContent-Type: text/event-plain\n\n" + eventBody);
    }

    void send(String raw) throws IOException {
        out.write(raw.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** 像 FreeSWITCH 内核那样往被测进程的媒体口发一包 */
    void sendAudio(byte[] pcm) throws IOException {
        media.send(new DatagramPacket(pcm, pcm.length, new InetSocketAddress("127.0.0.1", remotePort)));
    }

    byte[] receiveAudio() throws IOException {
        byte[] buf = new byte[2048];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        media.receive(p);
        byte[] out = new byte[p.getLength()];
        System.arraycopy(buf, 0, out, 0, p.getLength());
        return out;
    }

    int remotePort() {
        return remotePort;
    }

    /**
     * 连接是否已被对端关闭。读到 EOF 或连接重置都算: 对端关闭时若它的接收缓冲里还有我们刚写的数据没读,
     * 系统会回 RST 而不是 FIN —— 这仍然是"对端断开了"。
     */
    boolean isClosedByPeer() throws IOException {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                // 把挂机指令之类的残留读掉
            }
            return true;
        } catch (SocketTimeoutException e) {
            return false;
        } catch (java.net.SocketException e) {
            return true;
        }
    }

    @Override
    public void close() throws IOException {
        media.close();
        socket.close();
    }
}
