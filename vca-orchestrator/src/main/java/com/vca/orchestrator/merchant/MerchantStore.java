package com.vca.orchestrator.merchant;

import java.util.List;
import java.util.Optional;

/**
 * 商家资料的持久化端口。实现在 vca-store(MyBatis); 电话接入层只认这个接口。
 *
 * <p>为什么放在编排层而不是电话层: 模块依赖是 store → orchestrator, 电话层不被 store 依赖,
 * 端口放这里存储层才能实现它(线索、通话小结都是这么摆的)。
 *
 * <p>读取要快且不能拖垮通话: 每通电话接通时都要按被叫号查一次, 调用方(注册表)自己做缓存,
 * 实现只需保证查询本身走索引。写入后通过 {@link #addChangeListener} 通知调用方作废缓存 —— 诊所在网页上
 * 改完资料, 下一通电话就该用新的, 不能等到重启。
 */
public interface MerchantStore {

    MerchantStore NOOP = new MerchantStore() {
        @Override
        public Optional<MerchantProfile> findByNumber(String number) {
            return Optional.empty();
        }

        @Override
        public Optional<MerchantProfile> findById(long id) {
            return Optional.empty();
        }

        @Override
        public List<MerchantProfile> listByOwner(long ownerId) {
            return List.of();
        }

        @Override
        public List<MerchantProfile> listEnabled() {
            return List.of();
        }

        @Override
        public MerchantProfile save(MerchantProfile profile) {
            return profile;
        }

        @Override
        public boolean delete(long ownerId, long id) {
            return false;
        }
    };

    /** 按接入号找(只找启用的); 没有返回 empty */
    Optional<MerchantProfile> findByNumber(String number);

    Optional<MerchantProfile> findById(long id);

    /** 某账号名下的全部商家(含停用), 按创建顺序 */
    List<MerchantProfile> listByOwner(long ownerId);

    /** 全部启用的商家; 启动时预合成开场白用 */
    List<MerchantProfile> listEnabled();

    /**
     * 新建或更新。id 为空则新建并回填 id; 接入号与别家冲突时抛 {@link IllegalArgumentException}。
     *
     * @return 落库后的资料(带 id 与时间)
     */
    MerchantProfile save(MerchantProfile profile);

    /** 删除自己名下的一家; 不是自己的或不存在返回 false */
    boolean delete(long ownerId, long id);

    /** 资料有任何变动时回调(新建/修改/删除)。默认实现不通知 —— 调用方只能靠缓存过期。 */
    default void addChangeListener(Runnable listener) {
    }
}
