package com.vca.telephony.provider.freeswitch;

import com.vca.orchestrator.merchant.GatewayAccount;
import com.vca.orchestrator.merchant.GatewayControl;
import com.vca.orchestrator.merchant.MerchantRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 运营后台开通网关时, FreeSWITCH 这一侧的活: 在挂载进容器的 gateways/ 目录里写分机文件,
 * 再经事件套接字 {@code reloadxml} —— 目录即时生效, 不重启、不影响在途通话。
 *
 * <p>文件与 deploy/freeswitch/add-gateway.sh 生成的一样(LINE 分机绑定接入号与兜底座机), 只是前缀用
 * {@code vca-gw-}: {@link #sync} 只增删自己生成的文件, 脚本开的 {@code gw-*.xml} 一概不碰。
 */
public final class FreeSwitchGatewayControl implements GatewayControl {

    private static final Logger log = LoggerFactory.getLogger(FreeSwitchGatewayControl.class);

    static final String PREFIX = "vca-gw-";
    private static final Pattern REGISTERED = Pattern.compile("(?m)^User:\\s+([^@\\s]+)@");
    private static final Pattern EXTENSION = Pattern.compile("^[0-9]{2,10}$");
    private static final int TIMEOUT_MS = 5000;

    private final Path dir;
    private final String eslHost;
    private final int eslPort;
    private final String eslPassword;
    private final String sipServer;

    public FreeSwitchGatewayControl(Path dir, String eslHost, int eslPort, String eslPassword, String sipServer) {
        this.dir = dir;
        this.eslHost = eslHost;
        this.eslPort = eslPort;
        this.eslPassword = eslPassword == null ? "" : eslPassword;
        this.sipServer = sipServer == null ? "" : sipServer;
    }

    @Override
    public boolean available() {
        return Files.isDirectory(dir) && Files.isWritable(dir);
    }

    @Override
    public String sipServer() {
        return sipServer;
    }

    @Override
    public boolean userExists(String extension) {
        if (extension == null || !EXTENSION.matcher(extension).matches()) {
            return false;
        }
        return "true".equals(api("user_exists id " + extension + " " + MerchantRules.FS_DOMAIN).strip());
    }

    @Override
    public synchronized void write(GatewayAccount a) {
        requireExtension(a.lineUser());
        requireExtension(a.phoneUser());
        Path target = file(a);
        try {
            Path tmp = Files.createTempFile(dir, ".tmp-", ".xml");
            Files.writeString(tmp, render(a), StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("写分机文件失败(" + target + "): " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized void remove(GatewayAccount a) {
        try {
            Files.deleteIfExists(file(a));
        } catch (IOException e) {
            throw new IllegalStateException("删分机文件失败: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized void sync(Collection<GatewayAccount> all) {
        if (!available()) {
            log.warn("网关分机目录 {} 不存在或不可写, 跳过同步", dir);
            return;
        }
        Set<Path> keep = new HashSet<>();
        for (GatewayAccount a : all) {
            write(a);
            keep.add(file(a));
        }
        int removed = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, PREFIX + "*.xml")) {
            for (Path p : ds) {
                if (!keep.contains(p)) {
                    Files.deleteIfExists(p);
                    removed++;
                }
            }
        } catch (IOException e) {
            log.warn("清理多余的分机文件失败: {}", e.toString());
        }
        log.info("网关分机已同步: {} 台{}", all.size(), removed > 0 ? ", 删除库里已没有的 " + removed + " 个文件" : "");
    }

    @Override
    public void reload() {
        String out = api("reloadxml");
        if (!out.contains("+OK")) {
            throw new IllegalStateException("FreeSWITCH 重载目录失败: " + out.strip());
        }
    }

    @Override
    public Status status() {
        try {
            String profiles = api("sofia status");
            boolean running = profiles.lines().anyMatch(l -> l.contains("internal") && l.contains("profile")
                    && l.contains("RUNNING"));
            Set<String> registered = new LinkedHashSet<>();
            Matcher m = REGISTERED.matcher(api("sofia status profile internal reg"));
            while (m.find()) {
                registered.add(m.group(1));
            }
            return new Status(true, running, registered, running ? "" : "SIP 通道没有在运行");
        } catch (IllegalStateException e) {
            return new Status(false, false, Set.of(), e.getMessage());
        }
    }

    private String api(String command) {
        try {
            return EslCommand.api(eslHost, eslPort, eslPassword, command, TIMEOUT_MS);
        } catch (IOException e) {
            throw new IllegalStateException("连不上 FreeSWITCH 事件套接字 " + eslHost + ":" + eslPort + ": " + e.getMessage(), e);
        }
    }

    private Path file(GatewayAccount a) {
        return dir.resolve(PREFIX + a.lineUser() + ".xml");
    }

    private static void requireExtension(String ext) {
        if (ext == null || !EXTENSION.matcher(ext).matches()) {
            throw new IllegalArgumentException("分机号不合法: " + ext);
        }
    }

    /** 与 add-gateway.sh 生成的文件同一格式; 值守脚本按其中的"门店:"注释与 vca_access_number 认网关 */
    static String render(GatewayAccount a) {
        String label = a.label() == null ? "" : a.label().replaceAll("[<>&\"'-]", "").strip();
        return """
                <include>
                  <!-- 门店: %s -->
                  <!-- 由运营后台生成(网关 id %s), 手工改动会在下次同步时被覆盖 -->
                  <!-- LINE 口(FXO, 接电话线): 从这里进来的电话一律按接入号 %s 认领门店 -->
                  <user id="%s">
                    <params>
                      <param name="password" value="%s"/>
                    </params>
                    <variables>
                      <variable name="user_context" value="ai-agent"/>
                      <variable name="effective_caller_id_number" value="%s"/>
                      <variable name="sip-force-contact" value="NDLB-connectile-dysfunction"/>
                      <variable name="vca_access_number" value="%s"/>
                      <!-- AI 接不了时(没起、正在重启、崩了)来电转到这家店的前台座机, 而不是挂断 -->
                      <variable name="vca_fallback_dial" value="%s"/>
                    </variables>
                  </user>
                  <!-- PHONE 口(FXS, 接话机): 这家店的转人工分机 -->
                  <user id="%s">
                    <params>
                      <param name="password" value="%s"/>
                    </params>
                    <variables>
                      <variable name="user_context" value="ai-agent"/>
                      <variable name="effective_caller_id_number" value="%s"/>
                      <variable name="sip-force-contact" value="NDLB-connectile-dysfunction"/>
                    </variables>
                  </user>
                </include>
                """.formatted(label.isEmpty() ? "未命名" : label, a.id(), MerchantRules.number(a.accessNumber()),
                a.lineUser(), hex(a.linePassword()), a.lineUser(), MerchantRules.number(a.accessNumber()), a.phoneDialString(),
                a.phoneUser(), hex(a.phonePassword()), a.phoneUser());
    }

    /** 密码是生成的十六进制串; 从库里读出来的也再校验一遍, 防止被改成能破坏 XML 的内容 */
    private static String hex(String password) {
        if (password == null || !password.matches("^[0-9a-f]{12,64}$")) {
            throw new IllegalArgumentException("分机密码格式不对");
        }
        return password;
    }
}
