package com.vca.orchestrator.merchant;

import java.util.Set;

/**
 * 媒体服务器(FreeSWITCH)那一侧的网关操作: 写分机文件、重载、查注册。由电话接入层实现, 运营后台调用 ——
 * 运营在页面上开通网关, 不再上服务器跑脚本。
 *
 * <p>电话模块没启用、或没配分机目录时用 {@link #UNAVAILABLE}: 页面照常能用, 只是开通按钮提示"电话交换未接入"。
 */
public interface GatewayControl {

    GatewayControl UNAVAILABLE = new GatewayControl() {
        @Override
        public boolean available() {
            return false;
        }

        @Override
        public String sipServer() {
            return "";
        }

        @Override
        public boolean userExists(String extension) {
            return false;
        }

        @Override
        public void write(GatewayAccount account) {
            throw new IllegalStateException("电话交换未接入, 无法写分机");
        }

        @Override
        public void remove(GatewayAccount account) {
        }

        @Override
        public void sync(java.util.Collection<GatewayAccount> all) {
        }

        @Override
        public void reload() {
        }

        @Override
        public Status status() {
            return new Status(false, false, Set.of(), "电话交换未接入(VCA 没启用电话模块, 或没配 VCA_FS_GATEWAYS_DIR)");
        }
    };

    /**
     * 此刻媒体服务器的状态。
     *
     * @param reachable  事件套接字连得上
     * @param running    SIP 通道(internal)在运行
     * @param registered 已注册上来的分机号
     * @param message    连不上或异常时的说明; 正常为空
     */
    record Status(boolean reachable, boolean running, Set<String> registered, String message) {
    }

    /** 能不能开通/撤销网关(写得了分机文件、连得上事件套接字) */
    boolean available();

    /** 网关上"SIP Server"要填的地址(服务器公网 IP 或域名), 开通后展示给运营抄到 HT813 上 */
    String sipServer();

    /** 这个分机号在 FreeSWITCH 目录里是否已存在(含配置文件里的老网关、软电话), 分配新分机时避开 */
    boolean userExists(String extension);

    /** 写(或覆盖)这台网关的分机文件; 调用方随后 {@link #reload()} */
    void write(GatewayAccount account);

    /** 删掉这台网关的分机文件 */
    void remove(GatewayAccount account);

    /** 按库里的全部网关重写分机文件, 删掉库里已没有的(只动自己生成的文件) */
    void sync(java.util.Collection<GatewayAccount> all);

    /** 让 FreeSWITCH 重新读目录, 分机即时生效, 不影响在途通话 */
    void reload();

    Status status();
}
