package com.vca.store;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 对话落库配置({@code vca.store.*})。默认<b>关</b> —— 不开启时整个 {@code vca-store} 自动装配不生效,
 * 编排层注入到 {@link com.vca.orchestrator.recorder.ConversationRecorder#NOOP}, 对话行为完全不变。
 *
 * <p>用 MySQL + MyBatis-Plus。开启前需先建库(表由启动时的 schema.sql 幂等创建)。
 */
@ConfigurationProperties(prefix = "vca.store")
public class StoreProperties {

    /** 是否落库。默认关。 */
    private boolean enabled = false;

    /** MySQL 连接串。 */
    private String url = "jdbc:mysql://localhost:3306/vca?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai";

    private String username = "root";

    private String password = "";

    /** JDBC 驱动类名。留空让连接池据 url 自行推断(MySQL 8 驱动通常无需显式指定)。 */
    private String driverClassName;

    /** 异步落库队列容量。满了丢最旧, 保护语音热路径。 */
    private int queueCapacity = 1000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getDriverClassName() {
        return driverClassName;
    }

    public void setDriverClassName(String driverClassName) {
        this.driverClassName = driverClassName;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }
}
