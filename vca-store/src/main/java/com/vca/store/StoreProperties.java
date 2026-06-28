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

    /** 登录令牌 HMAC 签名密钥。生产务必经 env 设一个随机串; 改了它会让已签发令牌全部失效。 */
    private String tokenSecret = "vca-default-secret-change-me";

    /** 应用对外基址(如 https://host:8443), 用于拼重置密码链接。留空则邮件只给令牌。 */
    private String baseUrl = "";

    /** 重置令牌有效期(秒)。 */
    private int resetTtlSeconds = 1800;

    /**
     * 邮件开发回显: 为 true 时 {@code /api/password/*} 把重置令牌回给前端(无真实邮件通道也能联调)。
     * <b>生产务必设 false</b>, 并配好 SMTP(下面)。
     */
    private boolean mailDevEcho = true;

    // ---- SMTP(可选): 配了 host 才真实发邮件, 否则回退打日志的 LogEmailSender ----
    private String mailHost;
    private int mailPort = 465;
    private String mailUsername;
    private String mailPassword;
    /** 发件人地址; 留空用 mailUsername。 */
    private String mailFrom;
    /** 用 SSL(465 端口常用); 与 starttls 二选一。 */
    private boolean mailSsl = true;
    private boolean mailStarttls = false;
    /** 经 HTTP 代理 CONNECT 隧道发信(可选); 某些机器 JVM 直连 SMTP 被重置时填本地代理如 127.0.0.1:7890。 */
    private String mailProxyHost;
    private int mailProxyPort;

    // ---- Embedding(向量化长期记忆 + RAG 知识库): 配了 key 才启用; 否则记忆退回关键词级、RAG 检索为空 ----
    private boolean embeddingEnabled = true;
    private String embeddingBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    /** embedding API key; 复用 DASHSCOPE_API_KEY。空则不建 embedder, 功能降级。 */
    private String embeddingKey;
    private String embeddingModel = "text-embedding-v4";
    private int embeddingDim = 1024;
    /** 可选 HTTP 代理(host:port 形式的 URL, 如 http://127.0.0.1:7890); 本机开代理直连超时时用。 */
    private String embeddingProxy;

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

    public String getTokenSecret() {
        return tokenSecret;
    }

    public void setTokenSecret(String tokenSecret) {
        this.tokenSecret = tokenSecret;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public int getResetTtlSeconds() {
        return resetTtlSeconds;
    }

    public void setResetTtlSeconds(int resetTtlSeconds) {
        this.resetTtlSeconds = resetTtlSeconds;
    }

    public boolean isMailDevEcho() {
        return mailDevEcho;
    }

    public void setMailDevEcho(boolean mailDevEcho) {
        this.mailDevEcho = mailDevEcho;
    }

    public String getMailHost() {
        return mailHost;
    }

    public void setMailHost(String mailHost) {
        this.mailHost = mailHost;
    }

    public int getMailPort() {
        return mailPort;
    }

    public void setMailPort(int mailPort) {
        this.mailPort = mailPort;
    }

    public String getMailUsername() {
        return mailUsername;
    }

    public void setMailUsername(String mailUsername) {
        this.mailUsername = mailUsername;
    }

    public String getMailPassword() {
        return mailPassword;
    }

    public void setMailPassword(String mailPassword) {
        this.mailPassword = mailPassword;
    }

    public String getMailFrom() {
        return mailFrom;
    }

    public void setMailFrom(String mailFrom) {
        this.mailFrom = mailFrom;
    }

    public boolean isMailSsl() {
        return mailSsl;
    }

    public void setMailSsl(boolean mailSsl) {
        this.mailSsl = mailSsl;
    }

    public boolean isMailStarttls() {
        return mailStarttls;
    }

    public void setMailStarttls(boolean mailStarttls) {
        this.mailStarttls = mailStarttls;
    }

    public String getMailProxyHost() {
        return mailProxyHost;
    }

    public void setMailProxyHost(String mailProxyHost) {
        this.mailProxyHost = mailProxyHost;
    }

    public int getMailProxyPort() {
        return mailProxyPort;
    }

    public void setMailProxyPort(int mailProxyPort) {
        this.mailProxyPort = mailProxyPort;
    }

    public boolean isEmbeddingEnabled() {
        return embeddingEnabled;
    }

    public void setEmbeddingEnabled(boolean embeddingEnabled) {
        this.embeddingEnabled = embeddingEnabled;
    }

    public String getEmbeddingBaseUrl() {
        return embeddingBaseUrl;
    }

    public void setEmbeddingBaseUrl(String embeddingBaseUrl) {
        this.embeddingBaseUrl = embeddingBaseUrl;
    }

    public String getEmbeddingKey() {
        return embeddingKey;
    }

    public void setEmbeddingKey(String embeddingKey) {
        this.embeddingKey = embeddingKey;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public void setEmbeddingModel(String embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public int getEmbeddingDim() {
        return embeddingDim;
    }

    public void setEmbeddingDim(int embeddingDim) {
        this.embeddingDim = embeddingDim;
    }

    public String getEmbeddingProxy() {
        return embeddingProxy;
    }

    public void setEmbeddingProxy(String embeddingProxy) {
        this.embeddingProxy = embeddingProxy;
    }
}
