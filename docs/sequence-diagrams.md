# 时序图：一次上报到一封告警邮件

以下三张图对应仓库中的真实类与方法，方法名可直接在源码中检索。

## 图 1：数据摄取，含双写入路径分岔

```mermaid
sequenceDiagram
    autonumber
    participant A as agent
    participant C as MetricController
    participant S as MetricIngestionService
    participant B as MetricStore
    participant P as CopyMetricStore
    participant DB as PostgreSQL

    A->>C: POST /api/metrics/batch
    Note over C: MetricBatchRequest 校验 NotEmpty 与 Size max 1000
    C->>C: DTO 转 MetricStore.MetricPoint

    C->>S: selectRoute(points.size())
    Note over S: 读配置 copy-min-batch-size 与 force-path 覆盖开关

    alt 少于 100 条
        S-->>C: Route.BATCH
        C->>S: ingest(points)
        S->>B: insertBatch(points)
        Note over B: JdbcTemplate.batchUpdate 每行一条 INSERT
        B->>DB: INSERT INTO metric_point
        DB-->>B: 提交成功
        B-->>S: 行数
    else 达到 100 条
        S-->>C: Route.COPY
        C->>S: ingest(points)
        S->>P: copyIn(points)
        Note over P: 拼文本负载 escape 反斜杠 制表符 换行
        P->>DB: COPY metric_point FROM STDIN
        DB-->>P: 已复制 N 行
        P-->>S: 行数
    end

    S-->>C: accepted
    C-->>A: 202 accepted route
```

`selectRoute` 与 `ingest` 是两次独立调用：先问走哪条，再执行。响应里的 `route`
是决策结果，不是事后推断。

## 图 2：查询与自动降采样

```mermaid
sequenceDiagram
    autonumber
    participant F as 前端
    participant C as MetricController
    participant K as BucketSizes
    participant M as MetricStore
    participant DB as PostgreSQL

    F->>C: GET /api/metrics/query name from to
    Note over C: Instant 参数自带时区 避免 TIMESTAMPTZ 偏移
    C->>C: 校验 to 是否晚于 from

    alt 未指定 bucketSeconds
        C->>K: forSpan(Duration.between from to)
        Note over K: 小于等于 6 小时 60 秒<br/>小于等于 7 天 300 秒<br/>否则 3600 秒
        K-->>C: 300
    end

    C->>M: queryBucketed(name, from, to, 300)
    M->>DB: SELECT floor(epoch/300)*300 AS bucket avg max min count GROUP BY bucket
    Note over DB: 走 idx_metric_point_name_ts
    DB-->>M: 聚合后的桶
    M-->>C: List SeriesPoint
    C->>C: 转 SeriesPointDto
    C-->>F: JSON 数组
```

分桶用 `floor(epoch / 桶宽) * 桶宽` 而不是 `date_trunc`，因为后者只接受分钟、小时
这类固定单位，而前者支持任意桶宽。

## 图 3：定时巡检到邮件送达

```mermaid
sequenceDiagram
    autonumber
    participant T as Spring 调度器
    participant E as AlertEvaluator
    participant AS as AlertStore
    participant M as MetricStore
    participant SM as AlertStateMachine
    participant N as AlertNotifier
    participant DB as PostgreSQL
    participant MB as 后台线程
    participant MAIL as SMTP 服务器

    Note over T: 每 30 秒触发一次
    T->>E: evaluateAll()
    E->>AS: enabledRules()
    AS->>DB: SELECT FROM alert_rule WHERE enabled
    DB-->>AS: 启用的规则
    AS-->>E: List AlertRule

    loop 每条启用规则
        E->>M: latest(metric_name)
        M->>DB: ORDER BY ts DESC id DESC LIMIT 1
        DB-->>M: 最新点
        M-->>E: MetricPoint

        alt 数据陈旧
            Note over E: 超过 max-point-age-seconds 则跳过<br/>防止 agent 断连后反复评估旧值
        else 数据新鲜
            E->>E: resolveBound(rule)
            alt 规则使用统计阈值
                E->>M: baseline(name, window)
                M->>DB: SELECT avg stddev_pop count
                DB-->>M: 均值与标准差
                M-->>E: Baseline
                Note over E: 上限 等于 均值 加 倍数乘标准差
            else 规则使用固定阈值
                Note over E: 上限 等于 rule.threshold
            end

            E->>AS: loadState(rule_id)
            AS->>DB: SELECT FROM alert_state
            DB-->>AS: 状态行
            AS-->>E: AlertState

            E->>SM: evaluate(rule, state, bound, value, now)
            Note over SM: 纯函数 不碰数据库与框架
            SM->>SM: comparison.breachedBy(value, bound)
            SM->>SM: 连续计数加一或归零
            SM->>SM: withinCooldown(lastFiredAt, cooldown, now)
            SM-->>E: AlertEvaluation 新状态加可选事件

            E->>AS: saveState(新状态)
            AS->>DB: INSERT ON CONFLICT UPDATE

            alt 状态跨越通知边界
                E->>AS: recordEvent(FIRING 或 RESOLVED)
                AS->>DB: INSERT INTO alert_event
                E->>AS: isQuietNow(now)
                Note over AS: 检查 quiet_window 含跨午夜窗口
                alt 处于静默时段
                    Note over E: 记录但不出声
                else 非静默
                    E->>N: dispatch(event)
                    N->>MB: executor.submit
                    Note over N: 立刻返回 不阻塞评估
                    MB->>MAIL: 发送邮件 超时 5 秒
                    MAIL-->>MB: 结果
                    Note over MB: 失败只记日志 不重试
                end
            else 无需通知
                Note over E: 只落库 不出声
            end
        end
    end
```

## 时间轴

```
t=0s        agent 上报，图 1 完成，数据落库
t=0-30s     无事发生，数据静置在 metric_point
t=30s       调度器唤醒，图 3 开始
t=30s       取最新值、算基准、状态机判定
t=30s       若跨越边界：写 alert_event 并投递到后台线程
t=30s       evaluateAll 返回，主流程结束，不等邮件
t=31s       后台线程发完邮件
```

`dispatch` 只把任务投入队列便返回，所以评估总耗时不受 SMTP 影响。这是图中
`N->>MB` 与 `MB->>MAIL` 分属两条生命线的原因。

## 抖动与真故障的分道扬镳

同样从 `AlertStateMachine.evaluate` 开始，取值序列不同则结果完全不同：

| 步骤 | 抖动场景 | 真故障场景 |
| --- | --- | --- |
| 第 1 次评估 | 95 大于 85，连续计数 0 变 1 | 同左 |
| 判定 | 1 小于 minConsecutive 2，进入 pending | 同左 |
| 落库 | 写 alert_state，不写 event | 同左 |
| 第 2 次评估 | 40 小于 85，回到 ok | 96 大于 85，连续计数 1 变 2 |
| 判定 | 状态非 firing，静默归零 | 达到阈值且状态非 firing，进入 firing |
| 通知 | 无 | 发出 FIRING 邮件 |
| 第 3 次评估 | 无 | 97，仍在冷却窗口内，只更新计数不发送 |

左列全程没有发出任何通知，这就是 `min_consecutive` 的全部意义。

## 调用链速查

| 层 | 类 | 关键方法 | 职责 |
| --- | --- | --- | --- |
| 入口 | `MetricController` | `ingest` `query` | 收请求、校验、转换表示 |
| 调度 | `MetricIngestionService` | `selectRoute` `ingest` | 选择写入路径 |
| 业务 | `AlertEvaluator` | `evaluateAll` `evaluateOnce` | 定时巡检与编排 |
| 核心 | `AlertStateMachine` | `evaluate` `withinCooldown` | 纯决策：状态与数值进，新状态出 |
| 业务 | `AlertNotifier` | `dispatch` | 通知离开请求路径 |
| 业务 | `RetentionJob` | `purgeExpired` | 分批删除过期数据 |
| 存储 | `MetricStore` | `insertBatch` `queryBucketed` `latest` `baseline` | 指标读写 |
| 存储 | `CopyMetricStore` | `copyIn` | COPY 流式写入 |
| 存储 | `AlertStore` | `loadState` `saveState` `recordEvent` `isQuietNow` | 规则与状态读写 |
| 工具 | `BucketSizes` | `forSpan` | 按跨度选择桶宽 |
