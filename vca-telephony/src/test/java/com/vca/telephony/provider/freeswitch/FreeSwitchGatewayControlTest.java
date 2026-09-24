package com.vca.telephony.provider.freeswitch;

import com.vca.orchestrator.merchant.GatewayAccount;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 运营后台开通网关时写的 FreeSWITCH 分机文件(事件套接字那部分不在这里测)。 */
class FreeSwitchGatewayControlTest {

    @TempDir
    Path dir;

    private FreeSwitchGatewayControl control() {
        return new FreeSwitchGatewayControl(dir, "127.0.0.1", 1, "x", "47.95.248.104");
    }

    private static GatewayAccount account(long id, String line, String phone, String number, String label) {
        return new GatewayAccount(id, 7, number, label, line, "0123456789abcdef01234567", phone,
                "fedcba9876543210fedcba98", null);
    }

    @Test
    void writesLineBoundToAccessNumberWithFallbackToThePhonePort() throws Exception {
        control().write(account(3, "8011", "8012", "5002", "阳光<口腔>"));

        String xml = Files.readString(dir.resolve("vca-gw-8011.xml"));
        assertThat(xml).contains("<user id=\"8011\">").contains("<user id=\"8012\">")
                .contains("name=\"vca_access_number\" value=\"5002\"")
                .contains("name=\"vca_fallback_dial\" value=\"user/8012@vca.local\"")
                .contains("<!-- 门店: 阳光口腔 -->");   // 店名里的尖括号去掉, 不能破坏 XML
    }

    /** 只增删自己生成的 vca-gw-*.xml; add-gateway.sh 开的 gw-*.xml 和占位文件不碰 */
    @Test
    void syncRewritesOwnFilesAndLeavesScriptFilesAlone() throws Exception {
        Files.writeString(dir.resolve("gw-8091.xml"), "<include/>");
        Files.writeString(dir.resolve("00-empty.xml"), "<include></include>");
        Files.writeString(dir.resolve("vca-gw-8031.xml"), "<include/>");   // 库里已经没有的

        control().sync(List.of(account(1, "8011", "8012", "5000", "美好口腔")));

        assertThat(dir.resolve("vca-gw-8011.xml")).exists();
        assertThat(dir.resolve("vca-gw-8031.xml")).doesNotExist();
        assertThat(dir.resolve("gw-8091.xml")).exists();
        assertThat(dir.resolve("00-empty.xml")).exists();
    }

    @Test
    void refusesValuesThatWouldBreakTheXml() {
        FreeSwitchGatewayControl c = control();
        assertThatThrownBy(() -> c.write(new GatewayAccount(1L, 7, "5000", "x", "80\"11", "0123456789abcdef01234567",
                "8012", "0123456789abcdef01234567", null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c.write(new GatewayAccount(1L, 7, "5000", "x", "8011", "pw\"/><user id=\"1",
                "8012", "0123456789abcdef01234567", null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c.write(new GatewayAccount(1L, 7, "50\"00", "x", "8011", "0123456789abcdef01234567",
                "8012", "0123456789abcdef01234567", null))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void removeDeletesTheFile() {
        FreeSwitchGatewayControl c = control();
        GatewayAccount a = account(1, "8011", "8012", "5000", "美好口腔");
        c.write(a);
        c.remove(a);
        assertThat(dir.resolve("vca-gw-8011.xml")).doesNotExist();
    }

    @Test
    void unreachableEventSocketIsReportedNotThrown() {
        var s = control().status();
        assertThat(s.reachable()).isFalse();
        assertThat(s.message()).contains("连不上");
    }
}
