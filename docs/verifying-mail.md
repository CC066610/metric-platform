# 验证告警邮件链路

邮件链路分两段，**只有第一段是这个项目负责的**：

```
[1] 平台 → SMTP 服务器          [2] SMTP 服务器 → 收件人邮箱
    这段是你的代码                这段是邮件服务商的事
```

所以验证不需要真实邮箱。`tools/mail_capture.py` 起一个本地 SMTP 服务器，
把收到的邮件原文写成 `.eml` 文件并打印摘要——它能证明平台发出了**格式正确、
信封与正文都符合预期**的邮件。

## 第一步：本地截获（不需要邮箱账号）

开三个终端：

```bash
# 1) 数据库（若未运行）
D:\PostgreSQL\start-pg.bat

# 2) 截获服务器
python tools/mail_capture.py --port 2525 --out tools/captured

# 3) 平台，邮件指向截获服务器
set MAIL_HOST=127.0.0.1
set MAIL_PORT=2525
set ALERT_MAIL_TO=ops@example.com
mvn spring-boot:run
```

`ALERT_MAIL_TO` 的地址不需要真实存在——第一段链路里它只是一个信封字段，
真正的"能不能收到"属于第二段。

然后触发一次告警（用一条临时低阈值规则，避免真把 CPU 压到 85%）：

```bash
python - <<'PY'
import json, urllib.request, time
def call(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else b''
    req = urllib.request.Request('http://127.0.0.1:8080' + path, data=data,
                                 headers={'Content-Type': 'application/json'}, method=method)
    with urllib.request.urlopen(req, timeout=15) as r:
        raw = r.read().decode()
    return json.loads(raw) if raw.strip() else None

rule = call('POST', '/api/alerts/rules', {
    'metricName': 'cpu.usage', 'operator': 'gt', 'threshold': 3,
    'sigmaMultiplier': None, 'windowSeconds': 300,
    'minConsecutive': 1, 'cooldownSeconds': 60})
print('规则', rule['id'])
print(call('POST', f"/api/alerts/rules/{rule['id']}/evaluate"))
time.sleep(5)
call('DELETE', f"/api/alerts/rules/{rule['id']}")
PY
```

截获服务器会打印并落盘一封邮件，形如：

```
captured message -> 20260928-164213-969900.eml
  envelope from : metrics@localhost
  envelope to   : ops@example.com
  Subject       : [FIRING] cpu.usage
  size          : 347 bytes

Date: Mon, 28 Sep 2026 16:42:08 +0800 (CST)
From: metrics@localhost
To: ops@example.com
Subject: [FIRING] cpu.usage
Content-Type: text/plain; charset=UTF-8

cpu.usage is GT 6.400 (bound 3.000)

at 2026-09-28T08:41:53.213994900Z
```

平台侧同时会记录：

```
[alert-notifier] c.example.metrics.service.AlertNotifier : alert delivered to ops@example.com
```

线程名 `alert-notifier` 本身就是证据：投递发生在**后台线程**上，不在评估线程、
也不在 HTTP 请求线程上。

## 第二步：接真实邮箱（可选）

想验证第二段，换成一个真实 SMTP 服务商和它签发的**授权码**（不是登录密码）：

```bash
# 以 Gmail 为例
set MAIL_HOST=smtp.gmail.com
set MAIL_PORT=587
set MAIL_USER=you@gmail.com
set MAIL_PASSWORD=<应用专用密码>
set ALERT_MAIL_TO=you@gmail.com
mvn spring-boot:run
```

国内可选 163 / QQ 邮箱的 SMTP 服务，同样需要先在邮箱设置里开启 SMTP 并获取授权码。

**这一步验证的是邮件服务商，不是这个项目**：能否送达、是否进垃圾箱，都由服务商
和收件方策略决定，与平台代码无关。所以把它当作可选步骤。

## 已验证与未验证

| 项 | 状态 |
| --- | --- |
| 平台打开 SMTP 连接并完成会话 | ✅ 已验证（本地截获） |
| 信封发件人/收件人正确 | ✅ 已验证 |
| 主题格式 `[FIRING] <指标名>` 正确 | ✅ 已验证 |
| 正文含观测值、阈值、时间戳 | ✅ 已验证 |
| 投递在后台线程，不阻塞评估 | ✅ 已验证（日志线程名） |
| 真实邮件服务商送达 | ❌ 未验证，依赖你的账号 |
| TLS / 认证（`MAIL_USER` + `MAIL_PASSWORD`） | ❌ 未验证，本地截获不涉及认证 |
