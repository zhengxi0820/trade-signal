# 安全审查报告 — trade-signal（2026-08-19）

> 审查范围：`src/main`（auth / controller / service / orm / web / 配置）、`static` 前端、`scripts` 凭据处理、`pom.xml` 依赖、部署链路（Caddy 反代）与文档安全口径的对齐情况。
> 结论先行：**整体基线扎实**（SQL 全参数化、白名单校验、BCrypt、常量时间比较、Cookie 三件套、无 actuator/swagger、scripts 凭据无默认值、git 粗扫无泄露密钥），但有 **2 个高危、3 个中危** 值得尽快修：邀请码默认值导致注册默认开放（S-01）、X-Forwarded-For 伪造绕过限流并撑爆内存（S-02）。

## 发现汇总

| 编号 | 等级 | 一句话 | 位置 |
|---|---|---|---|
| S-01 | 高 | `TRADE_SIGNAL_INVITE_CODES` 未设置时默认 `dev-local-only`，生产漏配环境变量 = 注册开放且邀请码可从源码猜到 | `application.yaml:22` |
| S-02 | 高 | `clientIp` 取 XFF 首值，而 Caddy 是追加式转发 → 伪造 XFF 每次换假 IP：绕过 5 次锁定无限爆破 + 限流 Map 无限增长 | `AuthService.java:219-225` |
| S-03 | 中 | 限流 Map（failMap/registerSuccessMap）无容量上限、无过期清理，非锁定条目永驻内存 | `AuthService.java:42-45` |
| S-04 | 中 | `/kdj/series` 未强制 code 必填，缺省会全表读取（3300 万行级）聚合成单序列：无意义结果 + DB/CPU 压力（本机有 OOM 前科） | `KDJServiceImpl.java:633-638` |
| S-05 | 中 | `POST /kdj/cache/refresh` 任何注册用户可无限次调用，反复清空两层缓存触发全市场重算 | `KDJController.java:85-88` |
| S-06 | 低 | 用户登录在"用户不存在"路径不做 BCrypt 比较，响应时延差可枚举用户名 | `UserService.java:95-103` |
| S-07 | 低 | token 无吊销机制：改密/禁用不失效已签发 token（最长 7 天窗口）；登出仅删 Cookie | `AuthService.java:141-175` |
| S-08 | 低 | 注册重名回"用户名已存在"，注册接口可探测已注册用户名（有邀请码门槛，风险有限） | `AuthController.java:60-62` |
| S-09 | 低 | CSP 含 `unsafe-eval`/`unsafe-inline`（已知取舍，预编译模板后应去掉） | 部署 Caddy 配置 |
| S-10 | 低 | 无 CI 依赖扫描（无 .github/Dependabot），SECURITY.md 2.6 的要求未落地 | 仓库根 |
| S-11 | 低 | hutool `BCrypt.checkpw` 对畸形哈希抛异常 → 500；密码上限 64 字符但多字节字符可超 BCrypt 72 字节截断线 | `UserService.java:103` |

> **2026-08-19 修复回填**：当日已修复 S-01（默认改空，未配置=注册关闭）、S-02（clientIp 取 XFF 末值）、S-03（限流 Map 1 万上限 + 溢出清理）、S-04（series code 必填 400）、S-05（cache/refresh 仅密钥登录 403）、S-06（dummy BCrypt 拉平时延）、S-07（token 内嵌签发时间 + `UserService.isTokenActive` 服务端吊销，禁用/改密 bump `UPDATED_AT` 即全量失效，60s 缓存）、S-08（注册失败文案去枚举）、S-10（`.github/dependabot.yml`）、S-11（畸形哈希按失败处理；密码 UTF-8 ≤72 字节）。**S-09（CSP 两个 unsafe）保持跟进**——需前端迁移预编译模板，属独立前端改造。另删除根目录遗留 `ifind_ohlcv_sync.py`（自带偏离权威 schema 的 DDL）。注意：token 格式变更为四段（含签发时间），发版后旧 Cookie 全部失效，用户需重新登录。回归用例见 `docs/regression-test-cases.md` R-20260819-01~10。

## 详述与修法

### S-01 邀请码默认值 = 生产漏配时注册默认开放（高）

`application.yaml:22`：

```yaml
invite-codes: ${TRADE_SIGNAL_INVITE_CODES:dev-local-only}
```

后果链：生产机漏配该环境变量 → `UserService.registerEnabled()` 返回 true → 任何知道 `dev-local-only` 这个字符串的人（源码/仓库可见）都能 `POST /auth/register` 注册成正式用户，获得全部 `/kdj/**` 业务接口权限。这与 SECURITY.md 3.3「安全机制不可因调试方便在代码中默认关闭，**默认值必须安全**」直接冲突，也和 AGENTS.md / api 文档「空=注册关闭」的口径不符（未设置 ≠ 空）。

对照：`TRADE_SIGNAL_ACCESS_KEY` 的默认是空 → 启动随机密钥，这个模式是对的；邀请码没有照做。

**修法**：默认值改为空（`${TRADE_SIGNAL_INVITE_CODES:}`），未配置 = 注册关闭（403）；本机开发在启动命令里显式 `-DTRADE_SIGNAL_INVITE_CODES=dev-local-only` 或 export。`UserServiceTest` 用显式构造参数，不依赖 yaml 默认值，改动无测试负担。改完同步 api 文档 L10 的 403 描述。

### S-02 X-Forwarded-For 可伪造：限流绕过 + 内存放大（高）

`AuthService.clientIp` 取 XFF **第一个**值（`AuthService.java:219-225`）。部署链路是 Caddy 反代（见 `docs/trade-signal-deployment.md`），Caddy v2 对入站 XFF 是**追加**真实 IP，不覆盖。于是客户端带 `X-Forwarded-For: <随机IP>` 请求时，应用看到的首值永远是自己伪造的那个：

1. **限流绕过**：每次失败换一个假 IP，5 次锁定形同虚设 → 可无限爆破用户密码（BCrypt 校验约百毫秒级，慢但可持续；弱密码用户可被打穿）。密钥登录同理可尝试，密钥为随机值时不可枚举。
2. **内存放大**：每个假 IP 在 `failMap`/`registerSuccessMap` 各占一条，永不清理（见 S-03），认证接口可被用来持续撑大堆内存——4C4G 机器有 OOM 前科（2026-08-12）。
3. **审计失真**：日志 `AUTH login fail ip=...` 记录的是伪造 IP，无法对接 fail2ban 二次封禁。

**修法**（任选其一，推荐 a+b 都做）：
a. `clientIp` 改取 XFF **最后一个**值（可信反代追加的真实客户端 IP 在末尾）；
b. Caddy 侧显式覆盖：`reverse_proxy 127.0.0.1:8080 { header_up X-Forwarded-For {remote_host} }`，从源头掐掉伪造链。
注意改完要与服务器实际代理层数核对（单层 Caddy 时 last=真实 IP）。

### S-03 限流内存结构无上限、无清理（中）

`failMap` 只在「锁定期满后再次访问」时清理该条，未达 5 次的失败条目永驻；`registerSuccessMap` 同理。即使修了 S-02，正常公网扫描流量也会让它们单调增长（重启才清零）。

**修法**：给条目加时间戳做惰性过期（如 24h 未再失败即删）；或设总容量上限（如 1 万条，超限驱逐最旧）。与 S-02 一并处理。

### S-04 /kdj/series 缺 code 触发全表聚合（中）

文档（api 文档 L44）写「series 必填」，但 `validateParam` 只做格式校验不做必填校验；`loadDailies` 直接把空 code 传给 `queryAll`（`KDJServiceImpl.java:633-638`），导致读出全市场原始行情行聚合成一个（无意义的）序列。数据面 3300 万行、11GB 索引，认证用户反复调用即重查询压制 DB——2026-08-12 的 OOM 就是「冷缓存 + 并发重查询」叠加的结果。

**修法**：`/kdj/series` 入口对 code 做必填校验（空 → 400），一行改动；文档已按必填描述，改完即对齐。

### S-05 cache/refresh 无权限分级、无冷却（中）

任何注册用户可无限次 `POST /kdj/cache/refresh`，每次清空两层缓存使后续扫描全部重算（秒级到分钟级）。单飞机制防的是「同 key 并发」，防不住「清了再算」的循环。

**修法**：限制仅密钥登录（subject="key"，脚本/运维入口）可调；或加每分钟 1 次冷却。前者更简单——注册用户本没有清缓存的需求。

### S-06 登录时序侧信道：用户名枚举（低）

`tryUserLogin` 在用户不存在时直接返回 false，跳过了 BCrypt（约 100ms）；用户存在则做 BCrypt。响应时间差可用于探测用户名是否已注册。

**修法**：用户不存在时也执行一次 dummy `BCrypt.checkpw(password, DUMMY_HASH)` 拉平时延。

### S-07 token 无吊销（低，已知取舍）

token 是纯无状态 HMAC，签发后 7 天内始终有效：用户改密、被禁用（STATUS）都不影响已持有 token——禁用只在登录时校验（`UserService.java:99-101`）。密钥侧若怀疑泄露只能换 `TRADE_SIGNAL_ACCESS_KEY`（会踢掉所有用户，可接受）。

**修法**（如需）：app_user 加 token 纪元字段（如 `UPDATED_AT`），签发时嵌入、校验时比对，改密/禁用即全量失效。当前规模下可先记录为取舍。

### S-08 注册接口用户名探测（低）

重名返回「用户名已存在」。有邀请码门槛 + 重名同样计入失败限流，实际可利用性低；若要收紧，重名与参数错误返回同一文案即可。

### S-09 CSP 的两个 unsafe（低，跟进项）

`unsafe-eval`（Vue 运行时编译模板）与 `unsafe-inline`（Element Plus 内联样式）是 2026-08-04 记录在案的取舍。XSS 面收窄的终局是前端预编译模板（vue 编译为 render 函数）后去掉两者。保持跟进即可，不新增风险。

### S-10 依赖治理未落地（低）

依赖总体克制（无 actuator/swagger/文件上传），但仓库无 CI 与 Dependabot 配置，SECURITY.md 2.6「接入漏洞扫描」停留在纸面。hutool 5.8.25 / reactor-core 3.5.12 当前无已知高危（仅用 hutool BCrypt 与 reactor 的 CompletableFuture 工具），建议：开 GitHub Dependabot（或 `mvn org.owasp:dependency-check-maven` 本地定期跑）。

### S-11 BCrypt 边角（低）

- 库内哈希若损坏/畸形，`BCrypt.checkpw` 抛异常 → 登录 500 而非 401。包一层 try-catch 按失败处理。
- 密码上限 64 字符，但多字节字符（如中文）可超 BCrypt 72 字节输入截断线——截断只影响超长密码的尾部语义，实际风险可忽略，收紧为「≤72 字节 UTF-8」更严谨。

## 做对了的（保持）

- **SQL 注入**：全部 mapper XML 走 `#{}` 参数化，无一处 `${}`；无动态拼接 SQL。
- **入参校验**：`KDJServiceImpl.validateParam` 白名单枚举 + 正则（code/market/日期）+ BigDecimal 正数；watchlist code 同样白名单。业务参数无注入面。
- **前端**：无 `v-html`/`innerHTML`，Vue 文本插值默认转义；localStorage 只存 UI 偏好，无 token/密钥落地。
- **认证实现细节**：密钥比对与 HMAC 校验均 `MessageDigest.isEqual` 常量时间；Cookie `HttpOnly + Secure(默认true) + SameSite=Strict`；用户名白名单字符集天然保证 token 分隔安全。
- **信息收口**：无 actuator/swagger/api-docs；Boot 默认不回堆栈；静态资源无敏感信息；HtmlCacheHeaderFilter 防 HTML 缓存复用。
- **凭据卫生**：Java 侧与 scripts 侧全部环境变量化（`DB_PASSWORD` 无默认值，缺失即报错）；git 跟踪文件粗扫无密钥样字符串；测试用 H2 与生产隔离。
- **部署层**：8080/3306 仅 127.0.0.1、Caddy 安全头 + HSTS + CSP、SSH 仅密钥、fail2ban、备份即时 + 周兜底。

## 修复优先级建议

1. **立即**：S-01（一行配置默认值）、S-02（clientIp 取 XFF 末值 + Caddy 覆盖）——两者都是小改动大收益。
2. **随后**：S-03、S-04、S-05（限流容量、series 必填、refresh 权限）。
3. **择期**：S-06 ~ S-11。
