package com.vca.orchestrator.merchant;

import java.util.List;
import java.util.Optional;

/** 语音网关分机的存储(数据库是唯一来源, FreeSWITCH 的分机文件由它生成)。 */
public interface GatewayStore {

    GatewayStore NOOP = new GatewayStore() {
        @Override
        public List<GatewayAccount> listAll() {
            return List.of();
        }

        @Override
        public Optional<GatewayAccount> findById(long id) {
            return Optional.empty();
        }

        @Override
        public Optional<GatewayAccount> findByMerchant(long merchantId) {
            return Optional.empty();
        }

        @Override
        public GatewayAccount save(GatewayAccount account) {
            throw new UnsupportedOperationException("没有启用落库, 无法开通网关");
        }

        @Override
        public boolean delete(long id) {
            return false;
        }
    };

    List<GatewayAccount> listAll();

    Optional<GatewayAccount> findById(long id);

    /** 一家店最多一台网关 */
    Optional<GatewayAccount> findByMerchant(long merchantId);

    /** 新建(id 为空)或更新, 返回落库后的记录 */
    GatewayAccount save(GatewayAccount account);

    boolean delete(long id);
}
