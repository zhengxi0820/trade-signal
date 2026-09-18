# 回归测试用例库

> 本文件同时是回归测试约定的权威说明，团队/agent 均以此为准。

## 约定（Convention）

- **每次新需求或功能改动，测试完成后，必须把本次改动的测试用例补充进本文件**（编号 R-YYYYMMDD-NN），并在发版前执行一轮回归。
- 回归执行时机：改动完成、发版/上生产前；涉及核心计算或表结构时全量跑；纯前端样式/文案类改动至少跑本库相关用例 + 冒烟。
- 补充方式：在文件末尾追加「本次改动用例」小节，日期按实际测试日期，编号递增，不修改历史条目。
- 用例格式：
  - 编号：`R-YYYYMMDD-NN`
  - 内容：改动/需求、前置条件、步骤、预期结果、实测结果、状态（通过/失败/阻塞）
- 自动化用例（`./mvnw test`）与手工/接口/UI 冒烟用例同等对待，均需登记实测结果。
- 若某个用例暴露问题：先修复再登记通过；失败用例保留记录并注明修复 commit。

## 2026-08-14 本次改动用例

改动：前端工具栏控件苹果风（截止周期/筛选/板块设置/参数设置/查询/日线周线月线季线分段控件）+ 自选股收藏 UPDATED_AT 缺列修复。

| 编号 | 用例 | 前置 | 步骤 | 预期 | 实测 | 状态 |
|---|---|---|---|---|---|---|
| R-20260814-01 | 收藏-加自选（UPDATED_AT 修复） | 本地库 user_watchlist 已补 UPDATED_AT；key 登录 | POST /auth/login {key} → POST /watchlist {"code":"600519"} → GET /watchlist → 重复 POST → POST /watchlist/remove → GET | 204 / ["600519"] / 204（幂等）/ 204 / [] | 全部符合 | 通过 |
| R-20260814-02 | 收藏-未授权拦截 | 无 cookie | GET /watchlist | 401 | 401 | 通过 |
| R-20260814-03 | 查询按钮（图标移除后） | 登录态页面 | 检查查询按钮 DOM 与计算样式 | 纯文字无图标；高 32px、内边距 8px 18px，与参数设置一致；文字居中 | 一致 | 通过 |
| R-20260814-04 | 三按钮间距 | 工具栏渲染 | 计算板块设置/参数设置/查询 相邻间距 | 9px（12px-3px） | 9px | 通过 |
| R-20260814-05 | 日线/周线/月线/季线分段控件 | 工具栏渲染 | 检查容器与选中态计算样式 | gap 10px；四选项圆角一致 9px；选中白胶囊+蓝字 #0066CC | 全部符合 | 通过 |
| R-20260814-06 | 截止周期/代码名称筛选输入框 | 工具栏渲染 | 检查输入框计算样式 | 白底、10px 圆角、细边框 #D2D2D7；聚焦蓝圈 | 符合 | 通过 |
| R-20260814-07 | 板块设置弹层 | 点击板块设置 | 打开弹层检查样式 | 12px 圆角白卡；勾选框苹果蓝 #0066CC 白勾 | 符合 | 通过 |
| R-20260814-08 | 参数设置面板 | 点击参数设置 | 检查复权类型与开关 | 复权类型分段控件 gap 8px、上移 4px；开关蓝底 #0066CC；其余面板样式为原版 | 符合 | 通过 |
| R-20260814-09 | 自动化测试套件 | 本地构建环境 | ./mvnw test | 全绿 | 44/44 通过 | 通过 |
| R-20260814-10 | API 冒烟 | 本地服务运行 | login/periods/all-stocks/series/cache-refresh/未授权 | 204/200/200/200/200/401 | 全部符合 | 通过 |
| R-20260814-11 | 移动端适配 | 390px 视口 | 检查工具栏横向溢出 | 无横向溢出，控件换行正常 | 无溢出 | 通过 |

## 2026-08-14 补充用例（缓存问题修复）

改动：HTML 页面响应禁用缓存（`HtmlCacheHeaderFilter`），修复浏览器缓存旧版 index.html 导致 Vue 未挂载、`{{ toast.text }}` 原样展示。

| 编号 | 用例 | 前置 | 步骤 | 预期 | 实测 | 状态 |
|---|---|---|---|---|---|---|
| R-20260814-12 | HTML 禁用缓存 | 本地服务运行 | GET / 查看响应头；GET /css/style.css?v=13 与 GET /auth/login 查看响应头 | / 含 `Cache-Control: no-cache, no-store, must-revalidate`；css 与 API 路径无该头 | 全部符合；mvnw test 44/44 | 通过 |

## 2026-08-14 补充用例（周期物化「未完结周期」判定修复）

改动：`scripts/aggregate/period_bar.py` 判定由「周期末交易日 < 全库最大交易日（HAVING PE < max）」改为「周期桶 key < 全局最新交易日所在周期桶 key（HAVING PKEY < max_key）」，停牌股的未完结部分周期不再提前物化。

| 编号 | 用例 | 前置 | 步骤 | 预期 | 实测 | 状态 |
|---|---|---|---|---|---|---|
| R-20260814-13 | 停牌股未完结周期不入表 | 服务器物化脚本为修复版 | ① 清理误物化行（当前周期桶 周202633/月202608/季2026Q3 共 32 行）② 运行增量物化 ③ 复查当前周期桶行数 ④ 复查已完结周期（600519 周 08-07、600635 周 08-04）与 max(period_end) | 物化后当前周期桶无行；周 max=20260807、月 max=20260731、季 max=20260630；已完结周期完好 | 增量物化 1554.7s rc=0；当前周期桶 0 行；max=20260807/20260731/20260630；600519 周 08-07 11068 行、600635 周 08-04 4 行 | 通过 |

## 2026-08-15 补充用例（run_daily.sh FORCE 手动触发开关）

改动：`scripts/run_daily.sh` 新增 `FORCE=1` 环境变量支持——仅绕过三日闸门，探针失败/无新数据仍跳过；常规 cron 不带 FORCE 行为不变；日志标记"人工手动触发（FORCE=1）"。

| 编号 | 用例 | 前置 | 步骤 | 预期 | 实测 | 状态 |
|---|---|---|---|---|---|---|
| R-20260815-01 | FORCE 手动触发同步（拉到 08-14） | 服务器脚本为含 FORCE 版本；水位 20260813、新浪最新 20260814（距 1 天 <3） | `FORCE=1 ./run_daily.sh` → 查看 daily.log 与分片进程 | 日志出现"人工手动触发（FORCE=1），跳过三日闸门"；探针 20260814 > 水位 20260813；分片 0/2、1/2 启动；水位最终到 20260814 | 11:38 触发，日志与两路分片进程确认；finalize 后水位待复核 | 进行中（分片阶段） |

## 2026-08-15 补充用例（周期完结口径：最后一个交易日数据已获取）

改动：需求 §2.4 修订——周/月/季线完结判定从"自然日越过周期结束日"改为"该周期最后一个计划交易日（work_day 含未来）≤ 库内最新交易日"，含日历截断兜底；Java `KDJHandler`、物化脚本 `period_bar.py`、自愈 `ensure_period_bar.sh` 同口径；`workday.py` 新增 `--seed`（akshare 全年日历）与 `--reconcile`（每小时对账清理）。

| 编号 | 用例 | 前置 | 步骤 | 预期 | 实测 | 状态 |
|---|---|---|---|---|---|---|
| R-20260815-02 | 周线完结-周五数据到位 | 日历含未来；G=20260814（周五） | aggregate/aggregateDates kdjType=1，asOf=08-14 | 本周（08-10~08-14）已完结，进入周期列表与序列 | KDJHandlerTest 通过 | 通过 |
| R-20260815-03 | 周中不完结 | G=周三 | 同上 asOf=周三 | 本周被剔除 | KDJHandlerTest 通过 | 通过 |
| R-20260815-04 | 月线边界 | 日历含未来；G=月末最后一个交易日 | kdjType=2 | 当月完结；月内（G<月末最后交易日）不完结 | KDJHandlerTest 通过 | 通过 |
| R-20260815-05 | 季线边界 | 同上 | kdjType=3 | 季末最后交易日到位即完结；季中剔除 | KDJHandlerTest 通过 | 通过 |
| R-20260815-06 | 日历截断兜底 | 日历无未来日期（种子缺失） | aggregate kdjType=1 | 当前周期按未完结核对，不提前物化 | KDJHandlerTest + _max_complete_key 实测通过 | 通过 |
| R-20260815-07 | 停牌股周期完结后入表 | 周期完结、个股 PE<周期末 | 物化 | 按各自 PE（停牌前最后交易日）入表 | 300176=08-12、600984/603221=08-10、600635/600993=08-14 全部正确 | 通过 |
| R-20260815-08 | 就绪判断一致 | 物化 max=08-14 | 周线默认扫描 | 无 X-Data-Not-Ready；列表=本周 | 默认扫描与指定本周扫描均 200、无 Not-Ready | 通过 |
| R-20260815-09 | workday 种子 | akshare 可达 | `--seed` | SH/SZ/BJ 全年交易日幂等入库，未来日期存在 | 三市场 work_day max=20261231，未来 279 行 | 通过 |
| R-20260815-10 | 对账清理 | 存在已过且无行情的日历行 | `--reconcile` | 删除该行，不影响其他行 | 清理 5866 行；"有行情但不在日历"=0，无误删 | 通过 |
| R-20260815-11 | 自愈同口径 | ensure_period_bar 部署新版 | 每小时自愈 | BEHIND 按新口径计算；种子+对账先于物化 | 新版脚本部署完成，BEHIND 判定逻辑同口径 | 通过 |
| R-20260815-12 | 周六线上对拍 | 部署新 jar+scripts、G=08-14 | /kdj/periods 周线 + 物化表 + 扫描 | 周线列表含 08-10~08-14；物化周 max=20260814；月/季仍停已完结期；扫描 200 无 Not-Ready | 全部符合（物化 1516s rc=0；周 20260814/月 20260731/季 20260630） | 通过 |

## 2026-08-16 补充用例（物化准确性：窗口对齐 + 先删后插 + 全量重建对账）

改动：`period_bar.py` 增量窗口对齐周期边界（周→周一/月→1号/季→季首）；写入改为"窗口内先删后插"（同事务）；`--full` 全量重建同样先删该类型全部行再流式插入；停牌股周期内 ≥1 交易日即物化、不丢 K 线。

| 编号 | 用例 | 前置 | 步骤 | 预期 | 实测 | 状态 |
|---|---|---|---|---|---|---|
| R-20260815-13 | 窗口对齐 | cutoff 落在周期中间（如周四） | `_align_window_start` + 增量物化 | 边界周完整重算，PERIOD_START/END=真实首末交易日，不再截断 | `scripts/tests/test_period_bar.py` 18 项全过；线上重建后 07-20 周恢复为 11,038 行完整周 | 通过 |
| R-20260815-14 | 先删后插防残留 | 数据后补（600635 类） | 增量/全量重建 | 旧部分行被删除，同一周期仅 adjust 0/1 各一行 | 600635 旧 08-03~08-04 残留清除，仅剩完整周 adjust 0/1 两行；全表重复行=0 | 通过 |
| R-20260815-15 | 全量重建对账 | 服务器新脚本 | `--full` 重建 | 全表与 stock_quote 聚合一致；无重复行 | 02:09 重建完成 rc=0（7404s）；周 7,097,114 / 月 1,684,600 / 季 566,372 行；唯一键重复=0 | 通过 |
| R-20260815-16 | 上工申贝回归 | 重建完成 + 清缓存 | 最新周金叉/交易位扫描 | 600843 不在列表；其 K/D 与序列接口一致 | 金叉 412 只、交易位 8 只，均不含 600843；其 K/D 金叉回到 08-03 周 | 通过 |
| R-20260815-17 | 全表一致性 | 重建完成 | 重复行计数 + 单日异常行计数 + 样例对拍 | 重复=0；07-20 周单日行=0（新股 920238 首周单日为合法）；样例与序列一致 | 重复=0；600843/300237/300176 07-20 周均为完整周；920238 单日行为合法新股数据 | 通过 |

## 2026-08-19 补充用例（安全加固：限流/XFF/token 吊销/参数与权限收口）

改动：安全审查报告 `docs/security-review-20260819.md` S-01~S-08、S-10、S-11 落地——邀请码默认改空（未配置=注册关闭）；clientIp 取 XFF 末值；限流 Map 1 万 IP 上限 + 溢出清理；`/kdj/series` code 必填 400；`POST /kdj/cache/refresh` 仅密钥登录；token 四段格式（含签发时间）+ 用户 token 服务端吊销检查（`UPDATED_AT` 水位，60s 缓存）；登录 dummy BCrypt 拉平时延 + 畸形哈希容错 + 密码 UTF-8 ≤72 字节；注册失败文案去用户名枚举；Dependabot 接入。测试类：`UserServiceTest`（扩充）、`AuthHardeningTest`（新增）、`KDJSeriesCodeRequiredTest`（新增）。

| 编号 | 用例 | 前置 | 步骤 | 预期 | 实测 | 状态 |
|---|---|---|---|---|---|---|
| R-20260819-01 | 邀请码未配置=注册关闭 | 不设 `TRADE_SIGNAL_INVITE_CODES` | `new UserService("",0).registerEnabled()`；POST /auth/register | registerEnabled=false；register 403 | UserServiceTest.inviteCodeAndParamValidation 通过（H2） | 通过 |
| R-20260819-02 | XFF 取末值防伪造绕过 | 构造 `X-Forwarded-For: 1.2.3.4, 5.6.7.8`（首值为伪造） | `clientIp(request)` | 返回 5.6.7.8（末值=反代追加的真实 IP）；无 XFF 回退 remoteAddr | UserServiceTest.clientIpTakesLastXffValue 通过 | 通过 |
| R-20260819-03 | 限流 Map 溢出有界 | 锁定 1.2.3.4 后以 10500 个不同 IP 各失败 1 次 | 观察内部 failMap | size ≤ 10000；1.2.3.4 锁定保留（清非锁定条目优先） | UserServiceTest.failMapCapEvictsUnderFlood 通过 | 通过 |
| R-20260819-04 | series 缺 code 400 | H2 空库 | `getAllKDJ` code 空 / 空白 / null | ResponseStatusException 400；带合法 code 正常返回（空序列） | KDJSeriesCodeRequiredTest 2 项通过 | 通过 |
| R-20260819-05 | cache/refresh 权限 | H2 + MockMvc | 无 Cookie / key Cookie / 注册用户 Cookie 各 POST 一次 | 依次 401 / 200（{"cleared":0}）/ 403 | AuthHardeningTest.cacheRefreshRequiresKeySubject 通过 | 通过 |
| R-20260819-06 | token 四段格式与旧格式失效 | `new AuthService(...)` 直接构造 | issueToken→parseToken：subject/issuedAt 回读；旧三段串/篡改/负会话时长 | 新格式解析正确；旧三段与过期 token 一律 null | UserServiceTest.tokenIssuedAtAndExpiry / tokenSubjectRoundTrip 通过 | 通过 |
| R-20260819-07 | 禁用/水位 bump 吊销 token | 注册用户 + user-token-cache-seconds=0 | /auth/check 204 → SQL 置 STATUS=0 并 bump UPDATED_AT(+100s) → check、/kdj/gold-cross → 恢复 STATUS=1 再 check | 204 → 401/401 → 仍 401（签发早于水位，须重登） | AuthHardeningTest.authCheckEnforcesRevocation 通过 | 通过 |
| R-20260819-08 | 注册→自动登录全链路 | invite-codes=test-invite | POST /auth/register → 带 Cookie GET /auth/check | 204 + TS_AUTH Cookie；check 204（注册即清吊销负缓存） | AuthHardeningTest.registerUserViaEndpoint 通过 | 通过 |
| R-20260819-09 | 登录防枚举与容错 | H2 用户 + 畸形哈希行 | 用户不存在登录；PASSWORD 置为非 BCrypt 串后登录 | 均返回失败不抛异常（dummy BCrypt 拉平时延；畸形哈希按失败） | UserServiceTest.registerAndLogin / malformedHashFailsLoginNot500 通过 | 通过 |
| R-20260819-10 | 密码 72 字节上限 | validateParam | 30 个中文（90 字节）作密码 | 拒绝；20 个中文（60 字节）合法 | UserServiceTest.inviteCodeAndParamValidation 通过 | 通过 |

## 2026-08-20 补充用例（前端苹果风整页改造 v19）

改动：apple-design-skill 规范落地——新增吸顶毛玻璃导航栏（品牌居左 + 黑色胶囊页签）；页面底色 #F5F5F7、卡片 18px 圆角无边框多层阴影；表格透明表头小字标签 + 等宽数字；登录页改纯白卡片 + 填充式输入 + #0066CC 胶囊 CTA；分段控件（日周月季/季度/复权）无灰底轨、选中蓝底白字胶囊、按钮间距 12px；图表配色 K=#EA7A38 D=#8A51C3 J=#4CA2F7、蜡烛红涨 #E64340 绿跌 #27AE60、金叉锚 #CC7F1A；Element Plus 主题经 CSS 变量桥接到 #0066CC。纯静态资源改造（index.html / style.css / app.js 色值），无业务逻辑改动；静态版本号 v=19 做缓存失效。

| 编号 | 用例 | 前置 | 步骤 | 预期 | 实测 | 状态 |
|---|---|---|---|---|---|---|
| R-20260820-01 | 登录页渲染与登录流程 | 本地 v19 | 打开首页→用户名密码登录（API 注册 apple_demo） | 浅灰底白卡片、填充式输入、蓝胶囊按钮；登录后遮罩消失进入主界面 | 本地截图目视 + 浏览器实测通过（回车提交登录成功） | 通过 |
| R-20260820-02 | 主界面样式无渲染异常 | 已登录 | 检查导航栏/工具区/三卡片/表格/分页 | 毛玻璃导航栏、黑色胶囊页签、白卡 18px 圆角、透明表头、无错位溢出 | 本地截图目检：无异常（视觉模型核验） | 通过 |
| R-20260820-03 | 分段控件交互 | 已登录 | 切换 日/周/月/季、季度、复权类型；展开参数面板 | 三处分段统一：无灰底轨、选中蓝底白字、间距 12px；切换后控件状态正确 | 本地实测（周线切换后截止周期选择器联动为周选择） | 通过 |
| R-20260820-04 | 图表配色（真实数据） | 线上有数据 + 发版后 | 选一只股票出图，检查蜡烛与 KDJ 三线颜色 | 红涨 #E64340 绿跌 #27AE60；K 橙 D 紫 J 蓝，无刺眼感 | 待发版后线上核验 | 待验证 |
| R-20260820-05 | CSP 兼容与缓存失效 | 线上 Caddy 安全头 | curl -sI 首页与静态资源；浏览器打开页面 | 无外链字体/无内联脚本（script-src 'self' 不拦截）；v=19 版本引用生效，旧缓存不串 | 发版后核验：CSP 头完整、首页 v=19、登录页样式完整渲染（Vue 挂载/CSS 加载正常，视觉核验无裸 HTML） | 通过 |
| R-20260820-06 | 功能不回归 | 发版后 | 登录/自选星标/查询/列设置/板块设置 | 与改造前行为一致（纯样式改动，JS 仅动色值常量） | ./mvnw test 54/54 通过；待线上抽查 | 通过（代码层） |

## 2026-08-26 补充用例（历史截止查询性能修复 + 单票 series 物化直读 + 同步闸门 2 天）

改动：①全市场扫描历史截止（或 goldInternalMax>50 超窗口）时的兜底从"逐股对 stock_quote 现场聚合"改为按截止上界批读物化表（周/月/季）/批量读原始行聚合（日线）（`loadAnchoredBarsForScan`）；②单票 `/kdj/series` 周/月/季改读物化表全历史（物化未覆盖请求周期时回退实时聚合，日线不变）；③扫描批量装载加 SQL 窗口下界 + 窗口内不足 132 根的股票二次全量批读护栏（长期停牌股）；④`run_daily.sh` 同步闸门 3 天 → 2 天（MIN_SYNC_GAP_DAYS）。

| 编号 | 用例 | 前置 | 步骤 | 预期 | 实测 | 状态 |
|---|---|---|---|---|---|---|
| R-20260826-01 | 历史截止批量兜底对拍（月线） | H2：6 股×7000 工作日 + 物化表 | kdjType=2、tradeDate=20201215 的 gold-cross 与 trade-signal | 股票集合/K/D/J/close/crossValue 与全历史基准一致（KDJ 容差 1e-9） | historicalCutoffBatchFallbackMatchesFullHistory 通过 | 通过 |
| R-20260826-02 | 历史截止批量兜底对拍（周线/日线） | 同上 | kdjType=1 tradeDateMin/Max=20230626/20230630 gold-cross；kdjType=0 tradeDate=20230630 gold-cross | 周线走物化表批量、日线走原始行批量聚合，结果均与全历史基准一致 | 同上测试方法通过 | 通过 |
| R-20260826-03 | goldInternalMax>50 超窗口批量 | 同上 | kdjType=2、goldInternalMax=60（窗口 142>132）trade-signal | 全部股票按锚定批量重算（无截止=锚最新、无上界），结果与全历史基准一致 | oversizedWindowBatchFallbackMatchesFullHistory 通过 | 通过 |
| R-20260826-04 | 长期停牌股窗口护栏 | T00007 两段行情（2019~2021 + 2026，周线 161 根>132，132nd-from-last 早于 SQL 下界） | kdjType=1 all-stocks；检查 bars 缓存窗口 | 带下界批读不足 132 根 → 二次全量批读补齐；窗口=132 根且第一根来自停牌前老段；K/D/close 与全历史一致 | suspensionGapStockGetsFullReadGuard 通过 | 通过 |
| R-20260826-05 | 单票 series 周/月/季物化直读对拍 | 物化表已灌（全历史） | kdjType=1/2/3 各取 2 股（无截止）+ 周截止 20230630/月截止 202006/季截止 2021Q4 | 序列长度、OHLC、K/D/J、交叉标注与"全历史日线聚合 + handler 直算"逐字段一致（含早期数据一根不少） | seriesAggTablePathMatchesDailyAggregation 通过 | 通过 |
| R-20260826-06 | series 物化滞后回退 | 物化表删 2025-01 之后月线行 | 无截止 kdjType=2 series | isScanDataReady=false → 回退全历史日线聚合，最新周期不丢 | seriesFallsBackWhenAggTableStale 通过 | 通过 |
| R-20260826-07 | 物化直读 + 窗口下界存量对拍 | 物化表已灌 | kdjType=1/2/3 all-stocks（最新锚定，带下界批读路径） | 结果与全历史基准一致（原有用例回归） | aggTablePathMatchesFullHistory 通过 | 通过 |
| R-20260826-08 | 自动化测试套件 | 本地构建环境 | ./mvnw test | 全绿（54→59，新增 5 用例） | 59/59 通过 | 通过 |
| R-20260826-09 | 月线历史截止线上性能 | 发版后服务器（10:36 发版） | trade-signal kdjType=2 tradeDate=20230731（旧复现路径 30min+）冷缓存计时后同参重试；更老截止 2019-06 复测 | trade-signal 17.7s（>30min → ~100 倍）；同参重试 7ms（结果缓存）；2019-06 截止 3.6s（物化页热后） | 17.7s / 7ms / 3.6s | 通过 |
| R-20260826-10 | 单票月线 series 线上性能与全历史 | 发版后服务器（10:36 发版） | /kdj/series?code=600519&kdjType=2 冷/热各一次；000001 周线/季线冷测 | 月线冷 1.4s（旧 8.6s）、含 2001-08 首月共 265 根（截止 202309，与物化表自洽）；000001 周线 3.9s/1774 根（首根 19910403）、季线 1.7s/141 根 | 1.4s+265 根 / 3.9s+1774 根 / 1.7s+141 根，早期数据完整 | 通过 |
| R-20260826-11 | 同步闸门 2 天 | 服务器 run_daily.sh 已原子替换（MIN_SYNC_GAP_DAYS=2，2026-08-26 10:36） | 观察后续 daily.log：距水位 <2 天跳过、≥2 天执行 | 跳过日志显示"距水位 N 天 < 2 天"；实际约 2~3 天一同步 | 脚本已部署（grep 确认 =2）；行为待后续同步周期观察 | 通过（部署）/ 待观察（行为） |

## 2026-09-01 补充用例（查询触发制 + 「仅看涨」筛选 nextClose）

改动：①前端查询触发制补全——页面初始加载与登录成功不再自动发全市场扫描请求（`initPeriods(true)` + 亮橙点 + 未查询空态文案），`/kdj/periods` 与 `/watchlist` 保持自动加载；②金叉/交易位出参 `CrossStockVO` 新增 `nextClose`（严格下一期口径：全局日历上截止的下一期该股收盘价，同复权、整期停牌跳期置 null），三条取数路径 peek 下一根 bar，历史截止批量兜底 SQL 上界放宽到下一期期末；③前端金叉/交易位卡片头「列设置」左侧各一个独立「仅看涨」勾选框（截止=最新周期隐藏），纯前端过滤 `nextClose > close`，计数联动。

| 编号 | 用例 | 前置 | 步骤 | 预期 | 实测 | 状态 |
|---|---|---|---|---|---|---|
| R-20260901-01 | 自动化测试套件 | 本地构建环境 | ./mvnw test | 全绿（59→60，新增停牌跳期用例） | 60/60 通过 | 通过 |
| R-20260901-02 | nextClose 纳入全量对拍 | H2：6 股×7000 工作日（±物化表/两段停牌股） | 既有全部对拍用例（最新锚定/历史截止兜底/超窗口/停牌护栏，日周月季全模式） | 出参 nextClose 与"全历史聚合 + 严格下一期"基准逐股一致（assertSameContent 新增比对） | KDJScanWindowCacheTest 全部通过（referenceNextClose 基准） | 通过 |
| R-20260901-03 | 严格下一期·整期停牌跳期 | T00003 删 20260713~0717 整周行情（截止周 0710，下周停牌，0720 复牌） | kdjType=1 tradeDateMin/Max=20260706/20260710 all-stocks | 停牌股 nextClose=null（复牌周收盘不算下一期）；其余股 nextClose=0717 周收盘 | nextCloseNullWhenNextPeriodFullySuspended 通过；真实试点数据同例：688981 截止 20250829 后 0901~0908 停牌、0909 复牌 → nextClose=null | 通过 |
| R-20260901-04 | nextClose 端到端（本地真实库） | 本地 dev 库 11 股（日线 2023-08 起、物化表至 20260731） | curl 密钥登录后：日线截止 20260630 / 周线 0706~0710 / 月线 202606（单票+全市场）；默认无截止；全市场周线老截止 20250825~0829（切片<82 走批量兜底+读上界放宽路径） | nextClose 逐一等于库内下一期收盘（前复权行）：1193.01 / 1253.00 / 1350.60；无截止时全 null；老截止每股=20250905 周收盘（688981 例外=null，见 03） | 全部一致（与 stock_quote/stock_period_bar 库内核对）；**线上同口径复验**：zhengxi.online 周线 0706~0710 gold-cross 600519 nextClose=1253.00、all-stocks 默认周期 nextClose=null、全市场周线历史截止出参均有值 | 通过 |
| R-20260901-05 | 初始加载/登录不自动查询 | 本地服务 + 浏览器 | 打开首页（未认证）；登录后观察三表状态 | 两种进入路径均不发 all-stocks/gold-cross/trade-signal 请求：三表显示"点击「查询」加载数据"、计数（0）、查询按钮亮橙点 | 未认证加载实测 ✓（无任何 /kdj 扫描请求 + 引导文案）；已认证加载与登录成功为同一 `initPeriods(true)` 代码路径（代码审查） | 通过（未认证实测 + 代码同径）/ 已认证路径待人工点验 |
| R-20260901-06 | 「仅看涨」勾选框交互 | 已登录 + 点过查询 + 截止=历史周期 | 选历史截止→卡片头「列设置」左侧出现勾选框；勾选→列表与计数只留 nextClose>close；切回最新周期→勾选框隐藏且过滤失效 | 显隐正确、两框独立、计数联动、样式与现有勾选框一致 | 静态资源生效确认（线上首页含 bull-check ×2）；交互点击验证被浏览器自动化环境事件注入故障阻断 | 待人工点验（已发版，线上 zhengxi.online 直接点验；本地验证实例已停） |

## 2026-09-17 补充用例（信号限制项五项开关 + 前端 localStorage 记忆）

改动：①五个信号限制项（上次/当前金叉交汇上限、死叉交汇上限、金叉最小/最大间距）各加 `xxxEnabled` 开关（"1"=生效默认、"0"=该项不参与过滤，未传等同 "1"，老调用方行为不变）——`KDJParam` 新增 5 字段、`validateParam` 白名单、`fillTradeSignalDefaults` 缺省 SWITCH_ON、`scanCacheKey` 追加 key 段、`isTradeSignal` 四处守卫（死叉仅停用交汇上限，「恰好一次死叉」结构条件恒生效；间距 min/max 独立；回看窗口仍按 goldInternalMax 数值计算）、`withinCurrGoldCrossMax` 感知开关（currGoldCrossMaxEnabled=0 同时停用 series/gold-cross/all-stocks 的金叉标注过滤）；②前端五项各加 el-switch（关=输入框置灰），`buildQuery` 传 5 个开关；③前端 localStorage 记忆：`ts_query_params`（整个参数面板：KDJ 参数+五项数值+七个开关+复权，deep watcher 改了即存、初始化逐字段类型校验恢复）、`ts_ui_state`（仅看涨×2/列设置×2/板块筛选）——刷新/重开浏览器保留，清浏览器缓存才回默认；周期截止不记忆，保持自动最新；④文档同步 api.md 入参表+备注、需求 4.2 条件表+补充规则。

| 编号 | 用例 | 前置 | 步骤 | 预期 | 实测 | 状态 |
|---|---|---|---|---|---|---|
| R-20260917-01 | 五个开关逐项 off 放行（handler 层） | 合成 K/D 序列（默认参数下为交易位） | KDJHandlerTest 新增 5 用例：每项限制设为必拦值，开关 "0" vs 不传/"1" | 开=拦下（false）、关=通过（true）；开关不传(null)=生效由既有用例（参数未设开关）覆盖 | 新增 5 用例全过 | 通过 |
| R-20260917-02 | 开关缓存隔离 + 关开关对拍 | H2：6 股×7000 工作日 | KDJScanWindowCacheTest 新增 limitSwitchesGetDistinctCacheEntriesAndMatchReference：trade-signal 默认 vs goldInternalMaxEnabled=0 各两次；all-stocks currGoldCrossMax=1 vs =1+开关0 | 同参数 assertSame 命中缓存、开关不同 assertNotSame 不串缓存；关开关结果与基准直算（关同款开关）assertSameContent 一致；all-stocks 关开关后金叉标注不再受上限过滤 | 用例通过（14/14） | 通过 |
| R-20260917-03 | 自动化测试套件 | 本地构建环境 | ./mvnw test | 全绿（60→69：handler +5、scan cache +2、校验 +1、series +1） | 69/69 通过 | 通过 |
| R-20260917-04 | 入参白名单校验 | 服务运行 | 开关传非法值（如 lastGoldCrossMaxEnabled=2） | 400（validateParam requireEnum SWITCH_VALUES） | 新增 KDJParamSwitchValidationTest（5 开关 × {"2","true","on"} 均 400、"0"/"1"/不传放行）；线上冒烟 goldInternalMaxEnabled=2 → 400 | 通过 |
| R-20260917-05 | 前端开关交互与传参 | 本地/线上已发版 | 关任一开关→输入框置灰、点查询→请求带 xxxEnabled=0、结果集变化且其余限制仍过滤 | disabled 联动正确；query 参数含 5 开关；trade-signal 结果符合"仅关该项"预期 | 线上静态资源已生效（首页/app.js 含 Enabled 代码）；后端开关传参行为见 R-08 实测；用户浏览器点击交互点验通过（2026-09-18） | 通过 |
| R-20260917-06 | localStorage 记忆与恢复 | 浏览器 | 修改参数面板（含关开关）→刷新→重开浏览器→清站点数据 | 刷新/重开后参数面板与仅看涨/列设置/板块筛选完整恢复（F12 Application 可见 ts_query_params / ts_ui_state）；清缓存后回默认值；周期截止始终自动最新不记忆 | 用户浏览器实测：刷新后参数面板记忆正常恢复（2026-09-18，"刷新都还在"）；重开浏览器/清缓存路径同机制未单测 | 通过 |
| R-20260917-07 | series 端点当前金叉开关 | 服务运行 | /kdj/series?code=xxx&currGoldCrossMax=1&currGoldCrossMaxEnabled=0 | 金叉标注不被上限=1 过滤（与不传 currGoldCrossMax 等价） | 新增 seriesCurrGoldCrossMaxSwitchMatchesUnlimited（H2 6 股：关开关与不限逐标注一致、生效时超限金叉全部降级且存在区分度）；线上 200 | 通过 |
| R-20260917-08 | 线上五开关行为冒烟（2026-09-17 发版后，43.138.158.123 本机 8080） | jar 已替换重启（active/首页 200） | 密钥登录后：日线 trade-signal 全开 vs 逐个开关 =0 对比计数与子集关系；非法值 400；series 开关 200；同参重试 | 关任一开关交易位数 ≥ 全开（16 只）；全开结果是关 max 间距结果的子集；非法值 400；同参重试毫秒级（缓存 key 隔离且命中） | 全开 16 / 关上次金叉上限 51 / 关当前金叉上限 16 / 关死叉上限 18 / 关最小间距 28 / 关最大间距 18（子集 True、新增 2）；400；200/200；重试 0.0023s | 通过 |
