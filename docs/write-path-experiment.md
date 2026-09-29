# batchUpdate 与 COPY FROM STDIN 写入路径对比

同表、同数据、同机器、同驱动，**唯一变化的是写入路径**。

## 被测对象

| 路径 | 实现 | 协议行为 |
| --- | --- | --- |
| `batchUpdate` | `MetricStore.insertBatch`（`JdbcTemplate.batchUpdate`） | 每行一条 `INSERT`；服务端逐行 解析 → 规划 → 执行 |
| `COPY FROM STDIN` | `CopyMetricStore.copyIn`（`PGConnection.getCopyAPI()`） | 一次 `COPY` 命令 + 文本格式元组流；服务端跳过解析器/规划器/执行器 |

两条路径都通过 JDBC 驱动写入同一张 `metric_point` 表，因此对比的是**服务端写入路径**，不是客户端语言或网络栈的差异。

## 为什么会有差距

`batchUpdate` 无论批多大，发出的仍是独立的 `INSERT` 语句集合。服务端对每一行都要：

1. **Parser** — 把 SQL 文本变成语法树
2. **Planner** — 生成执行计划（即使有 prepared statement 缓存，仍有 executor 的逐行启动成本）
3. **Executor** — 逐行走一遍执行器节点

`COPY` 把这三层整体跳过：元组直接从协议层组装成 heap tuple 落盘。这是 PostgreSQL 为批量装载专门设计的路径，代价是放弃逐行级的约束检查、`ON CONFLICT` 与 `RETURNING`。

## 实验设计

- 数据：固定种子的随机游走，模拟真实指标形状（多个 metric name、多个 host 标签、时间递增）
- 规模：每个配置 100,000 行
- 变量：每批行数 `1 / 100 / 1000 / 10000`
- 次数：每个配置 1 次预热 + 3 次测量，取**中位数**
- 计时范围：只包含 JDBC 调用本身；数据生成在计时窗口之外
- 每次测量前 `TRUNCATE`，保证表状态一致

## 运行

```bash
# 需先启动 PostgreSQL 并建好 metrics 库
mvn -q compile
mvn -q exec:java \
  -Dexec.mainClass=com.example.metrics.bench.WritePathBenchmark \
  -Dexec.args="100000 3"
```

参数：`<总行数> <每配置测量次数>`。

## 结果

本机：PostgreSQL 17.11（Windows）、驱动 `postgresql-42.7.7`、HikariCP 连接池上限 4、
10 万行、每配置 1 次预热 + 7 次测量取中位数。

| 每批行数 | batchUpdate (rows/s) | COPY (rows/s) | COPY 相对倍数 |
| ---: | ---: | ---: | ---: |
| 1 | 7,605 | 5,102 | **0.67x** |
| 100 | 63,246 | 92,199 | 1.46x |
| 1,000 | 72,371 | 106,700 | 1.47x |
| 10,000 | 71,481 | **126,392** | 1.77x |

中位数耗时：batchUpdate 最优 1.382 s（batch=1000），COPY 最优 0.791 s（batch=10000）。

> 需要说明的测量边界：COPY 侧客户端先拼好整段文本负载再交给驱动，因此它包含了
> 字符串构造开销；batchUpdate 侧没有对应开销。这一步**不利于** COPY，也就是
> 表里的 COPY 数字是偏保守的下界。若要测 COPY 的纯协议上限，应改为边读边流式
> 发送而不是先构造完整负载——本项目先取保守值。

### 三个必须注意的读数

**1. COPY 在小批量下更慢（0.67x）。** 每次 `copyIn` 都要解析 `COPY` 语句、建立流式协议、
消耗一次连接借出成本。在 batch=1 时这些固定开销**每行**都要付一次，因此 COPY 反而落后。
因此存在一个**交叉点**：本机测得在 batch≈100 附近 COPY 开始领先。

**2. batchUpdate 的吞吐在 batch=1000 之后不再增长（72,371 → 71,481）。** 这说明批量化之后，
瓶颈已经从"每行的解析/规划/执行"转移到了**服务端的 heap 插入与 WAL 写入**——这是两条路径
都要付的公共成本，也是 COPY 只能拿到约 1.7 倍而拿不到数量级的原因。

**3. 1.7 倍是"localhost 下限"，不是通用倍数。** 被排除在外的最大变量是**网络往返**：
本机 127.0.0.1 的往返延迟接近 0。batchUpdate 每批至少一次往返，COPY 整个数据集一次往返；
跨机房部署（RTT 数毫秒）时 COPY 的优势会显著大于 1.7 倍。**引用这个数字时必须说明是本机环境。**

### 对照实验：排除驱动 INSERT 重写的干扰

PostgreSQL JDBC 驱动有一个 `reWriteBatchedInserts` 参数，开启后会把批量 `INSERT` 重写成
多值 `VALUES`，那会削弱对比的公平性。因此做了对照：

| 配置 | batchUpdate 最优 (rows/s) | COPY 最优 (rows/s) | 倍数 |
| --- | ---: | ---: | ---: |
| 驱动默认 | ~63,000 | ~110,000 | 1.8x |
| `reWriteBatchedInserts=false` | ~75,000 | ~121,000 | 1.6x |

两者量级一致，说明**默认设置下驱动并未启用重写**，即上表测到的确实是两种写入路径本身的差异，
而不是驱动优化的差异。两次独立运行的结果差异约 10–20%，这是本机（非独占、有后台进程）的
正常波动，所以文档中只保留一次 7 次取中位数的定稿结果。

## 面试要点

**结论不是"COPY 更好"**，而是按路径分流：

| 需求 | 应该走 |
| --- | --- |
| 高频指标摄取，允许整批失败重试 | `COPY` |
| 需要 `ON CONFLICT` 幂等写入 | `batchUpdate` |
| 需要触发器/外键副作用或 `RETURNING` 取回生成值 | `batchUpdate` |
| 批量回填历史数据 | `COPY` |

代价必须说清楚：`COPY` **放弃**逐行约束检查、upsert、`RETURNING`，且**整批失败**——错误定位到文本位置而非具体行。
