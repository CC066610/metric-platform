# agent —— 主机指标采集器

把本机的 CPU、内存、磁盘、网络指标上报到平台。这是让看板从"模拟数据"变成"真实主机视图"的那个组件。

## 快速开始

```bash
pip install psutil

# 先看一眼会采集到什么（不发送）
python agent.py --once

# 正式采集：每 10 秒一次，上报到本机平台
python agent.py

# 指定平台地址、间隔、主机标签
python agent.py --url http://10.0.0.5:8080 --interval 5 --host web-01
```

启动前确保：
1. 平台已运行（`mvn spring-boot:run`，监听 8080）
2. 告警规则已建好：`psql -U metrics -h 127.0.0.1 -d metrics -f rules.sql`

## 采集的指标

| 指标 | 单位 | 标签 | 说明 |
|---|---|---|---|
| `cpu.usage` | % | host | 全核平均使用率 |
| `cpu.load.1m_per_core` | — | host | 1 分钟负载 ÷ 逻辑核数 |
| `mem.usage` | % | host | 内存使用率 |
| `mem.available.bytes` | 字节 | host | 可用内存 |
| `disk.used.percent` | % | host, mount | 每个挂载点一条 |
| `disk.free.bytes` | 字节 | host, mount | 每个挂载点一条 |
| `disk.read.bytes_per_sec` | B/s | host | 所有设备合计读速率 |
| `disk.write.bytes_per_sec` | B/s | host | 所有设备合计写速率 |
| `disk.busy.percent` | % | host | 平均设备繁忙度 |
| `net.sent.bytes_per_sec` | B/s | host | 发送速率 |
| `net.recv.bytes_per_sec` | B/s | host | 接收速率 |

**每个指标都对应 `rules.sql` 里的一条规则**，所以全新安装后数据是被真正判定的，而不只是存着。

## 三个设计决定

**① 采集和上报是两条独立线程。** 采集线程按固定节奏往一个有界队列里放样本；上报线程从队列里批量取走。这样平台变慢或挂掉**不会改变采集节奏**，而队列上界把一次故障变成"丢弃部分样本"而不是"内存无限增长"。

**② 所有速率用真实时间差算，不用标称间隔。** `time.sleep(10)` 实际会睡 10.02 秒或更长。如果除数是 10 而是 10.02，每个速率都会有系统性偏差。所以所有速率都基于 `time.monotonic()` 的差值计算。

**③ 发送失败就丢弃并计数，不重试。** 重试队列需要持久化、幂等键、死信处理——对一个每 10 秒发一批的采集器，这个成本换不来什么。失败被计数、被记录日志，仅此而已。

## 已知局限

- **单机采集。** 没有服务发现、没有配置下发、没有中心化的 agent 管理。多台机器就多跑几个进程。
- **没有认证。** 平台 API 目前不校验身份，agent 和平台的通信假定在可信网络内。
- **`disk.busy.percent` 在部分平台为空。** Windows 上 `psutil.disk_io_counters` 有时不可用，此时该指标**不发送**而不是发 0——发 0 会被读成"磁盘空闲"，掩盖真实问题。
- **主机标签依赖系统名。** 若系统名不可用（过短或为空），会退化为 `unknown-<6位哈希>`，哈希由机器标识和网卡 MAC 派生，重启后稳定、多机不冲突。

## 开机自启

**Windows**：把 `start-agent.bat` 的快捷方式放进 `shell:startup`。

**Linux**：写一个 systemd unit：

```ini
[Unit]
Description=Metric platform agent
After=network-online.target

[Service]
ExecStart=/usr/bin/python3 /opt/metric-agent/agent.py --url http://127.0.0.1:8080
Restart=always
RestartSec=10
User=metrics

[Install]
WantedBy=multi-user.target
```

`Restart=always` 配合 agent 自身的队列丢弃策略，就是这套单机方案的可用性兜底。
