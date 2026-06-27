# 08 · 数据飞轮 P1：对话落库

> "数据 → 评测 → 改进"飞轮的第一环。没有落库，后续所有评测（WER / 误打断率 / 失败率）都无米之炊。

## 设计

三条原则：**旁路端口 + 默认 NOOP + 开关**、**绝不阻塞语音热路径**、**不过度工程**。

```
ConversationSession ──recordTurn()──▶ ConversationRecorder (orchestrator SPI, 默认 NOOP)
                                              ▲
                                              │ vca.store.enabled=true 时注入
                                  MyBatisConversationRecorder (vca-store)
                            有界队列 → 单线程 worker → MyBatis-Plus mapper.insert → MySQL
```

- **端口** `com.vca.orchestrator.recorder.ConversationRecorder`（`TurnRecord` 为载体）与 `TurnListener` 同构：默认空实现，不注入则编排行为完全不变。
- **埋点**在 `ConversationSession` 的三处回合收尾，覆盖全部模式：
  - 三段式 / 文本回合：`respond()` 的 `doFinally`（有 `total_ms`）；
  - 每轮 S2S：`speechToSpeechTurn()` 的 `doOnComplete`；
  - 持久全双工 S2S：`flushAssistant()`（`ResponseDone`=complete / 被打断=interrupted）。
  - S2S 路径里 user/assistant 是分离的异步事件，用 `lastUserText` 暂存配对。
- **落库**（`vca-store`，**MySQL + MyBatis-Plus**）：`recordTurn` 只入队即返回；后台守护线程攒批逐条 `mapper.insert`；**队列满丢最旧**（同上行音频 `pending` 策略）；写库异常就地吞掉。`close()` 优雅排空。
- **技术栈说明**：本项目用 Spring Boot 4（Spring 7），MyBatis-Plus 3.5.14 自带的 `mybatis-spring 2.1.2` 仅适配 Spring 5，故在 `vca-store/pom.xml` 强制覆盖为 `mybatis-spring 4.0.0`（对应 `mybatis 3.5.19`，Spring 7 兼容）。并**手动装配** `SqlSessionFactory`（见 `MyBatisSupport`），不用 `spring-boot3-starter` 的自动装配——其面向 Boot 3，在 Boot 4 上有兼容风险。

## 实体与 Mapper

- 实体 `ConversationTurn`（`@TableName("conversation_turn")`，自增主键 `@TableId(IdType.AUTO)`），字段驼峰 ↔ 列下划线由 `mapUnderscoreToCamelCase` 自动映射。
- Mapper `ConversationTurnMapper extends BaseMapper<ConversationTurn>`，落库只用 `insert`。

## Schema

`conversation_turn`，MySQL DDL（自增主键、`TEXT` 存文本、`utf8mb4`）。见 `vca-store/src/main/resources/com/vca/store/schema.sql`，启动时 `CREATE TABLE IF NOT EXISTS` 幂等建表（**库需先建好**）。

| 列 | 含义 |
|---|---|
| `session_id` / `turn_index` | 会话 + 回合序号（回溯整段对话） |
| `mode` | `pipeline` / `s2s` / `s2s-persistent` |
| `user_text` / `assistant_text` | 用户说了什么 / 机器人答了什么 |
| `total_ms` | 整轮耗时（可空：S2S 路径无逐轮计时） |
| `outcome` | `complete` / `interrupted` / `error` |
| `created_at` | 落库时刻（评测分时段切片） |

> 逐轮延迟 SLO（首 token / 首音频）仍在 Micrometer/Prometheus 聚合，不在此重复存。音频不落库（留作 WER 阶段以对象存储引用补）。

## 开启

默认**关**（`vca.store.enabled=false`），关时零影响。先建库（如 `CREATE DATABASE vca`），再开：

```yaml
vca:
  store:
    enabled: true
    url: jdbc:mysql://localhost:3306/vca?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
    username: root
    password: ***
    queue-capacity: 1000
```

`vca-store` 自带连接池、手动装配 MyBatis-Plus，不走 Boot 的 DataSource 自动装配（已在 `application.yml` 排除 `DataSourceAutoConfiguration`——否则 classpath 上的 MySQL 驱动会让 Boot 尝试自动配 DataSource，未配 url 时启动直接失败）。

> 单测用 H2（MySQL 兼容模式）替身验证实体映射与 `insert`，免起真实 MySQL。

## 验证

启用并跑几轮真实对话后：`SELECT * FROM conversation_turn ORDER BY id DESC;` 应看到逐轮记录。

## 下一步（P2）

落库后即可做**零标注**的自动指标：延迟 p50/p95、回合失败率（`outcome=error`）、误打断率（`interrupted` 但无后续有效 `user_text`）、工具命中。WER 需 golden set（抽样 + 人工参考转写），留作有标注数据后再开。
