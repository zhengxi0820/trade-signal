"""const.py — 口径常量与板块前缀规则

与 docs/trade-signal-schema.sql 一一对应，改动需同步 schema 与本文件。
"""

# ---- stock_quote.ADJUST 枚举（CHAR(1) 字符串，与 Java 侧 kdjType/开关参数同风格）----
ADJUST_NONE = "0"   # 无复权（爬取原始行，只追加）
ADJUST_QFQ = "1"    # 前复权（因子反推自算，可重算覆盖）
ADJUST_HFQ = "2"    # 后复权（预留，当前不使用）

# ---- stock_info.BOARD_TYPE 枚举（2026-08-13 字典变更：主板拆分沪/深，1/2/3 不变）----
BOARD_SH_MAIN = "0"     # 上交所主板（原「沪深主板」沪部分，值不变、语义收窄）
BOARD_STAR = "1"        # 科创板
BOARD_CHINEXT = "2"     # 创业板
BOARD_BSE = "3"         # 北交所
BOARD_SZ_MAIN = "4"     # 深交所主板（新值，从原 0 拆出）

# ---- 市场标识（stock_info.MARKET）----
MARKET_SH = "SH"
MARKET_SZ = "SZ"
MARKET_BJ = "BJ"

# ---- 因子反推阈值（关键设计，勿随意调）----
# 事件判定双阈值（AND 关系）：
#   ① factor 日际相对变化 > FACTOR_REL_THRESHOLD
#   ② 隐含金额变化 = 相对变化 × 当日股价 > IMPLIED_AMOUNT_THRESHOLD（元）
# 单看相对阈值会被低价股的舍入噪声击穿（2 元股最小跳动 0.005 元已是 0.25%），
# 所以必须再加绝对金额下限。
FACTOR_REL_THRESHOLD = 0.001
IMPLIED_AMOUNT_THRESHOLD = 0.005

# 事件因子 k 拟合窗口：跳变点前后各取约 K_WINDOW 个交易日的 factor 中位数相除
K_WINDOW = 10

# ---- 伪事件过滤（低价股舍入噪声对策，2026-08 601880 实测后新增）----
# ① 噪声自适应幅度下限：候选跳变幅度必须超过 QUANTUM_NOISE_FACTOR × PRICE_TICK ÷ min(raw, qfq)。
#    依据：factor 由两个各自 0.01 元舍入的价格相除得到，常数平台上逐日 factor 的
#    舍入状态游走最大可达 ~1 个价位/较小价（相对值）；601880 实测伪事件全部 ≤1.05 个
#    价位（rel≤0.72%@1.3~1.8 元），真事件全部 ≥2.2 个价位（rel≥1.32%），1.5 个
#    价位在两者之间干净分离。对高价股该项趋近于零（茅台仅 0.0009%），无影响。
#    **分母必须取 min(raw, qfq)**（2026-08-04 全量回填事故修复）：等比前复权下早期
#    qfq 被累计因子压缩至分角级（万科 1991 年 qfq 仅 0.04~0.13 元），噪声由较小的
#    qfq 价主导；按 raw 价计算的下限在早期形同虚设，曾致每股上千个伪事件。
# ② 方向过滤（无参数）：等比前复权下除权日 factor 只会向 1 跳升（k=前÷后<1），
#    降向候选（k≥1）物理上不可能是除权，直接丢弃。
# ③ 跳变持续性后验：候选跳变点之后 PERSIST_WINDOW 个交易日的 factor 中位数，
#    必须 ≥ 旧平台 + PERSIST_RATIO ×（新平台 − 旧平台），否则视为振荡噪声丢弃。
#    依据：真除权后 factor 永久稳定在新平台；低价股双序列异步舍入造成的伪跳变
#    会在数日内振荡回原平台（601880 实测 ±0.55% 振荡）。0.5 保持度让真事件
#    （跳变幅度 >> 噪声）有充足余量。②③ 是 2~5 元档（①的价位下限偏弱处）的兜底。
PRICE_TICK = 0.01
QUANTUM_NOISE_FACTOR = 1.5
PERSIST_WINDOW = 5
PERSIST_RATIO = 0.5

# 自算 qfq 与爬取 qfq 对拍的逐日容差：max(VERIFY_TOLERANCE, ADAPTIVE_VERIFY_TICKS 个价位 ÷ min(raw, qfq)价)。
# 早期 qfq 低价段的"偏差"本质是爬取 qfq 自身的两位小数舍入抖动（自算值是段中位数
# 去噪后的平滑序列，反而更接近真值），绝对容差在该段无意义，故按价位数自适应。
VERIFY_TOLERANCE = 0.001
ADAPTIVE_VERIFY_TICKS = 3.0

# factor 阶梯平台段内部允许的最大相对波动（平台稳定性验收线）
PLATEAU_TOLERANCE = 0.001

# 除权事件来源标识（stock_dividend.SOURCE，与 schema.sql 注释一致）
SOURCE_DERIVE = "derive"                    # 纯因子反推（东财公告不可用时的兜底）
SOURCE_ANNOUNCE = "announce"                # 公告日历命中但无反推事件，理论公式算 k
SOURCE_DERIVE_ANNOUNCE = "derive+announce"  # 公告日历 + 反推 k 双轨命中（主口径）

# ---- 双轨合并（公告日历 = 权威事件日历；因子反推 = k 值测量 + 校验 + 兜底）----
# 公告除权日 ±ANNOUNCE_MATCH_DAYS 个交易日内的反推事件视为同一事件
ANNOUNCE_MATCH_DAYS = 3
# 交叉校验：|k_理论/k_反推 - 1| 超过 K_CROSSCHECK_TOL 进人工复核清单（仍用反推 k 落库）
K_CROSSCHECK_TOL = 0.005
# 全量回填保险：单股事件数 > 市龄(年) × MAX_EVENTS_PER_YEAR → 中止该股、标记人工复核
# （000002 事故量级是每年 50~76 个伪事件；真实分红+送转每年至多 2~3 次）
MAX_EVENTS_PER_YEAR = 3


def board_type_of(market: str, code: str) -> str:
    """按市场 + 代码前缀推导板块（全市场统一规则，不看 code 来自哪个接口）：
    688 → 科创板；300/301/302 → 创业板；4xx/8xx/92x → 北交所；
    其余主板按 market 拆沪/深（SH→0、SZ→4）。
    """
    if code.startswith("688"):
        return BOARD_STAR
    if code[:3] in ("300", "301", "302"):
        return BOARD_CHINEXT
    if code[0] in ("4", "8") or code.startswith("92"):
        return BOARD_BSE
    return BOARD_SH_MAIN if market == MARKET_SH else BOARD_SZ_MAIN
