package com.vca.store;

import com.vca.orchestrator.merchant.MerchantProfile;
import com.vca.store.merchant.MyBatisMerchantStore;
import com.vca.store.mapper.PhoneMerchantMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 商家资料落库: 接入号唯一、按账号隔离、改动必须通知(注册表靠它作废缓存)。H2 替身, 免起真实 MySQL。 */
class MyBatisMerchantStoreTest {

    private static DataSource h2(String name) throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:" + name + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        ds.setDriverClassName("org.h2.Driver");
        try (var c = ds.getConnection(); var st = c.createStatement()) {
            st.execute("""
                    CREATE TABLE phone_merchant (
                      id BIGINT AUTO_INCREMENT PRIMARY KEY,
                      owner_id BIGINT NOT NULL,
                      number VARCHAR(32) NOT NULL,
                      name VARCHAR(128) NOT NULL DEFAULT '',
                      enabled TINYINT NOT NULL DEFAULT 1,
                      industry VARCHAR(32) NOT NULL DEFAULT 'generic',
                      greeting VARCHAR(512) NOT NULL DEFAULT '',
                      system_prompt TEXT,
                      transfer_dial_string VARCHAR(128) NOT NULL DEFAULT '',
                      summary_webhook VARCHAR(512) NOT NULL DEFAULT '',
                      tts_voice VARCHAR(64) NOT NULL DEFAULT '',
                      asr_vocabulary_id VARCHAR(128) NOT NULL DEFAULT '',
                      address VARCHAR(512) NOT NULL DEFAULT '',
                      business_hours VARCHAR(512) NOT NULL DEFAULT '',
                      phone VARCHAR(64) NOT NULL DEFAULT '',
                      transport VARCHAR(512) NOT NULL DEFAULT '',
                      services TEXT, staff TEXT, booking_rules TEXT, notes TEXT,
                      created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL,
                      UNIQUE (number)
                    )""");
        }
        return ds;
    }

    private static MyBatisMerchantStore store(String db) throws Exception {
        var factory = MyBatisSupport.sqlSessionFactory(h2(db));
        return new MyBatisMerchantStore(MyBatisSupport.mapper(factory, PhoneMerchantMapper.class));
    }

    private static MerchantProfile profile(Long id, long owner, String number, String name) {
        return new MerchantProfile(id, owner, number, name, true, "dental", "您好，这里是" + name, "", "user/8002@vca.local",
                "", "", "", "东城区", "9:00-20:00", "", "", "洗牙 200-400", "张伟 种植科", "", "", null, null);
    }

    @Test
    void savesAndFindsByNumberAndOwner() throws Exception {
        MyBatisMerchantStore s = store("m1");
        MerchantProfile saved = s.save(profile(null, 11, "5000", "美好口腔"));

        assertThat(saved.id()).isNotNull();
        assertThat(saved.createdAt()).isNotNull();
        assertThat(s.findByNumber("5000")).get().extracting(MerchantProfile::name).isEqualTo("美好口腔");
        assertThat(s.findByNumber("5000")).get().as("行业与人员列也要原样存取")
                .extracting(MerchantProfile::industry, MerchantProfile::staff).containsExactly("dental", "张伟 种植科");
        assertThat(s.findByNumber(" 5000 ")).as("号码两边的空白不该影响命中").isPresent();
        assertThat(s.listByOwner(11)).hasSize(1);
        assertThat(s.listByOwner(12)).isEmpty();
    }

    @Test
    void numberMustBeGloballyUnique() throws Exception {
        MyBatisMerchantStore s = store("m2");
        s.save(profile(null, 11, "5000", "美好口腔"));

        assertThatThrownBy(() -> s.save(profile(null, 12, "5000", "别家")))
                .as("两家店配同一个接入号, 来电只能落到一家, 另一家永远接不到还不知道为什么")
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("5000");
    }

    @Test
    void updateKeepsIdAndOwnerIsolationHolds() throws Exception {
        MyBatisMerchantStore s = store("m3");
        MerchantProfile a = s.save(profile(null, 11, "5000", "美好口腔"));

        MerchantProfile renamed = s.save(profile(a.id(), 11, "5000", "美好口腔东城店"));
        assertThat(renamed.id()).isEqualTo(a.id());
        assertThat(s.findByNumber("5000")).get().extracting(MerchantProfile::name).isEqualTo("美好口腔东城店");

        assertThatThrownBy(() -> s.save(profile(a.id(), 12, "5000", "冒充")))
                .as("别的账号不能改我的店").isInstanceOf(IllegalArgumentException.class);
        assertThat(s.delete(12, a.id())).as("别的账号也删不掉").isFalse();
        assertThat(s.delete(11, a.id())).isTrue();
        assertThat(s.findByNumber("5000")).isEmpty();
    }

    @Test
    void disabledMerchantIsNotResolvedByNumber() throws Exception {
        MyBatisMerchantStore s = store("m4");
        MerchantProfile p = profile(null, 11, "5000", "美好口腔");
        MerchantProfile off = new MerchantProfile(null, 11, p.number(), p.name(), false, "", p.greeting(), "", "",
                "", "", "", "", "", "", "", "", "", "", "", null, null);
        s.save(off);
        assertThat(s.findByNumber("5000")).as("停用的店来电按默认商家处理").isEmpty();
        assertThat(s.listByOwner(11)).as("但自己名下还看得到").hasSize(1);
        assertThat(s.listEnabled()).isEmpty();
    }

    @Test
    void notifiesListenersOnEveryChange() throws Exception {
        MyBatisMerchantStore s = store("m5");
        AtomicInteger changes = new AtomicInteger();
        s.addChangeListener(changes::incrementAndGet);

        MerchantProfile a = s.save(profile(null, 11, "5000", "美好口腔"));   // 新建
        s.save(profile(a.id(), 11, "5000", "改名"));                         // 修改
        s.delete(11, a.id());                                               // 删除
        s.delete(11, 99999);                                                // 删不存在的: 不该通知

        assertThat(changes.get()).as("新建/修改/删除各通知一次, 无效删除不通知").isEqualTo(3);
    }
}
