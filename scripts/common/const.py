"""const.py — 口径常量与板块前缀规则

与 docs/trade-signal-schema.sql 一一对应，改动需同步 schema 与本文件。
"""

# ---- stock_quote.ADJUST 枚举（CHAR(1) 字符串，与 Java 侧 kdjType/开关参数同风格）----
ADJUST_NONE = "0"   # 无复权（爬取原始行，只追加）
ADJUST_QFQ = "1"    # 前复权（因子反推自算，可重算覆盖）
ADJUST_HFQ = "2"    # 后复权（预留，当前不使用）

# ---- stock_info.BOARD_TYPE 枚举 ----
BOARD_MAIN = "0"        # 沪深主板
BOARD_STAR = "1"        # 科创板
BOARD_CHINEXT = "2"     # 创业板
BOARD_BSE = "3"         # 北交所

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
# ① 噪声自适应幅度下限：候选跳变幅度必须超过 QUANTUM_NOISE_FACTOR × PRICE_TICK ÷ 股价。
#    依据：factor 由两个各自 0.01 元舍入的价格相除得到，常数平台上逐日 factor 的
#    舍入状态游走最大可达 ~1 个价位/股价（相对值）；601880 实测伪事件全部 ≤1.05 个
#    价位（rel≤0.72%@1.3~1.8 元），真事件全部 ≥2.2 个价位（rel≥1.32%），1.5 个
#    价位在两者之间干净分离。对高价股该项趋近于零（茅台仅 0.0009%），无影响。
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

# 自算 qfq 与爬取 qfq 对拍的最大允许相对偏差
VERIFY_TOLERANCE = 0.001

# factor 阶梯平台段内部允许的最大相对波动（平台稳定性验收线）
PLATEAU_TOLERANCE = 0.001

# 除权事件来源标识（stock_dividend.SOURCE）
SOURCE_DERIVE = "derive"


def board_type_of(code: str) -> str:
    """按代码前缀推导板块（全市场统一规则，不看 code 来自哪个接口）：
    688 → 科创板；300/301/302 → 创业板；4xx/8xx/92x → 北交所；其余 → 主板。
    """
    if code.startswith("688"):
        return BOARD_STAR
    if code[:3] in ("300", "301", "302"):
        return BOARD_CHINEXT
    if code[0] in ("4", "8") or code.startswith("92"):
        return BOARD_BSE
    return BOARD_MAIN
