-- trade-signal 数据库结构（MySQL 8.x）
--
-- 与实体类一一对应：
--   stock_quote    ↔ src/main/java/com/xi/orm/entity/StockQuoteDO.java
--   work_day       ↔ src/main/java/com/xi/orm/entity/WorkDayDO.java
--   stock_info     ↔ src/main/java/com/xi/orm/entity/StockInfoDO.java
--   stock_dividend ↔ scripts/adjust（因子反推生成，无对应 Java 实体）
--   app_user       ↔ src/main/java/com/xi/orm/entity/AppUserDO.java
--   stock_quote_log ↔ 同步中转表（结构与 stock_quote 完全一致，无 Java 实体）
--   stock_period_bar ↔ src/main/java/com/xi/orm/entity/PeriodBarDO.java（周/月/季物化聚合，scripts 物化）
-- 字段要改动时：先改本文件与 DO，再对库执行 ALTER，三者保持一致。
--
-- 约定：
--   TRADE_DATE / EX_DATE 一律 VARCHAR(8)，存 yyyymmdd 字符串（项目三字段日期规则），不要用 DATE 类型。
--   ADJUST / BOARD_TYPE 为 CHAR(1) 字符串枚举（与 kdjType、开关参数同风格）。
--   CREATED_AT / UPDATED_AT 为 DECIMAL(15,0)，存 UNIX_TIMESTAMP 秒级时间戳。
--   导入数据时请保证连接字符集为 utf8mb4，避免中文乱码。

-- 首次部署：建库与账号（应用连接配置见 src/main/resources/application.yaml）
CREATE DATABASE IF NOT EXISTS trade_signal DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE USER IF NOT EXISTS 'trade_signal'@'localhost' IDENTIFIED BY 'trade_signal';
GRANT ALL PRIVILEGES ON trade_signal.* TO 'trade_signal'@'localhost';

USE trade_signal;

-- 股票行情（日线粒度，一行 = 某股票某交易日某复权口径）
-- ADJUST='0' 原始行只追加；ADJUST='1' 前复权行由 scripts/adjust 因子反推自算，可重算覆盖
CREATE TABLE IF NOT EXISTS stock_quote (
    ID          VARCHAR(64)   NOT NULL COMMENT 'ID(MD5: code+trade_date+adjust)',
    CODE        VARCHAR(16)   NOT NULL COMMENT '股票代码',
    OPEN        DECIMAL(12,4) NOT NULL COMMENT '开盘价',
    HIGH        DECIMAL(12,4) NOT NULL COMMENT '最高价',
    LOW         DECIMAL(12,4) NOT NULL COMMENT '最低价',
    CLOSE       DECIMAL(12,4) NOT NULL COMMENT '收盘价',
    VOLUME      BIGINT        DEFAULT NULL COMMENT '成交量(股)',
    TRADE_DATE  VARCHAR(8)    NOT NULL COMMENT '交易日期(yyyymmdd)',
    ADJUST      CHAR(1)       NOT NULL COMMENT '复权类型：0=无复权 1=前复权 2=后复权(预留)',
    CREATED_AT  DECIMAL(15,0) DEFAULT NULL COMMENT '创建时间(UNIX秒)',
    UPDATED_AT  DECIMAL(15,0) DEFAULT NULL COMMENT '更新时间(UNIX秒)',
    PRIMARY KEY (ID),
    UNIQUE KEY uk_code_adjust_date (CODE, ADJUST, TRADE_DATE),
    KEY idx_trade_date (TRADE_DATE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='股票行情';

-- 交易日历（/kdj/periods 的周期推导来源，由 stock_quote distinct TRADE_DATE 生成）
CREATE TABLE IF NOT EXISTS work_day (
    ID          VARCHAR(64)   NOT NULL COMMENT 'ID(MD5)',
    MARKET      VARCHAR(8)    NOT NULL COMMENT '市场',
    TRADE_DATE  VARCHAR(8)    NOT NULL COMMENT '交易日期(yyyymmdd)',
    CREATE_DATE VARCHAR(8)    DEFAULT NULL COMMENT '创建日期(yyyymmdd)',
    CREATED_AT  DECIMAL(15,0) DEFAULT NULL COMMENT '创建时间(UNIX秒)',
    UPDATED_AT  DECIMAL(15,0) DEFAULT NULL COMMENT '更新时间(UNIX秒)',
    PRIMARY KEY (ID),
    UNIQUE KEY uk_market_date (MARKET, TRADE_DATE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='交易日历';

-- 股票基础信息（name/market 的唯一来源，stock_quote 不再冗余）
CREATE TABLE IF NOT EXISTS stock_info (
    ID          VARCHAR(64)   NOT NULL COMMENT 'ID(MD5)',
    CODE        VARCHAR(16)   NOT NULL COMMENT '股票代码',
    NAME        VARCHAR(64)   DEFAULT NULL COMMENT '股票名称',
    MARKET      VARCHAR(8)    NOT NULL COMMENT '市场标识(SH/SZ/BJ，预留HK/US)',
    BOARD_TYPE  CHAR(1)       NOT NULL COMMENT '板块：0=上交所主板 1=科创板 2=创业板 3=北交所 4=深交所主板',
    CREATED_AT  DECIMAL(15,0) DEFAULT NULL COMMENT '创建时间(UNIX秒)',
    UPDATED_AT  DECIMAL(15,0) DEFAULT NULL COMMENT '更新时间(UNIX秒)',
    PRIMARY KEY (ID),
    UNIQUE KEY uk_market_code (MARKET, CODE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='股票基础信息';

-- 除权除息事件（因子反推法生成：factor(t)=qfq/raw 阶梯跳变点，只追加）
-- FACTOR 为该事件的综合复权因子（红利/送转/配股合并效果），不存分项明细
CREATE TABLE IF NOT EXISTS stock_dividend (
    ID          VARCHAR(64)    NOT NULL COMMENT 'ID(MD5: code+ex_date)',
    CODE        VARCHAR(16)    NOT NULL COMMENT '股票代码',
    EX_DATE     VARCHAR(8)     NOT NULL COMMENT '除权除息日(yyyymmdd)',
    FACTOR      DECIMAL(20,12) NOT NULL COMMENT '综合复权因子k',
    SOURCE      VARCHAR(32)    NOT NULL COMMENT '来源：derive+announce=公告日历+反推k(主口径) announce=公告日历+理论k derive=纯反推(公告不可用兜底)',
    CREATED_AT  DECIMAL(15,0)  DEFAULT NULL COMMENT '创建时间(UNIX秒)',
    UPDATED_AT  DECIMAL(15,0)  DEFAULT NULL COMMENT '更新时间(UNIX秒)',
    PRIMARY KEY (ID),
    UNIQUE KEY uk_code_exdate (CODE, EX_DATE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='除权除息事件';

-- 注册用户（邀请码注册制，邀请码由环境变量 TRADE_SIGNAL_INVITE_CODES 配置，不落库；未配置=注册关闭）
-- PASSWORD 存 BCrypt 哈希（60 字符），永不存明文；STATUS 是恶意账号禁用开关（非权限体系）
-- UPDATED_AT 兼作用户 token 吊销水位：禁用/改密时同步 bump（UPDATE ... SET UPDATED_AT=UNIX_TIMESTAMP()），
--   晚于 token 签发时间的旧 token 即失效（UserService.isTokenActive，检查带 60s 内存缓存）
-- 唯一索引在 utf8mb4_general_ci 下大小写不敏感：Admin/admin 视为重复（防仿冒）
CREATE TABLE IF NOT EXISTS app_user (
    ID          BIGINT       NOT NULL AUTO_INCREMENT,
    USERNAME    VARCHAR(32)  NOT NULL COMMENT '登录名（^[a-zA-Z0-9_]{3,20}$）',
    PASSWORD    VARCHAR(60)  NOT NULL COMMENT '密码 BCrypt 哈希',
    STATUS      CHAR(1)      NOT NULL DEFAULT '1' COMMENT '状态：1=正常 0=禁用',
    INVITE_CODE VARCHAR(64)  DEFAULT NULL COMMENT '注册时使用的邀请码（滥用追溯）',
    CREATED_AT  DECIMAL(15,0) DEFAULT NULL COMMENT '创建时间(UNIX秒)',
    UPDATED_AT  DECIMAL(15,0) DEFAULT NULL COMMENT '更新时间(UNIX秒)',
    PRIMARY KEY (ID),
    UNIQUE KEY uk_username (USERNAME)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='注册用户';

-- 同步中转表：每次同步先入本表，收尾阶段一次性并入 stock_quote 后备份、TRUNCATE 本表。
-- 分片阶段主表零写入，水位（max TRADE_DATE）只在收尾翻一次，缓存只失效一次。
CREATE TABLE IF NOT EXISTS stock_quote_log (
    ID          VARCHAR(64)   NOT NULL COMMENT 'ID(MD5: code+trade_date+adjust)',
    CODE        VARCHAR(16)   NOT NULL COMMENT '股票代码',
    OPEN        DECIMAL(12,4) NOT NULL COMMENT '开盘价',
    HIGH        DECIMAL(12,4) NOT NULL COMMENT '最高价',
    LOW         DECIMAL(12,4) NOT NULL COMMENT '最低价',
    CLOSE       DECIMAL(12,4) NOT NULL COMMENT '收盘价',
    VOLUME      BIGINT        DEFAULT NULL COMMENT '成交量(股)',
    TRADE_DATE  VARCHAR(8)    NOT NULL COMMENT '交易日期(yyyymmdd)',
    ADJUST      CHAR(1)       NOT NULL COMMENT '复权类型：0=无复权 1=前复权 2=后复权(预留)',
    CREATED_AT  DECIMAL(15,0) DEFAULT NULL COMMENT '创建时间(UNIX秒)',
    UPDATED_AT  DECIMAL(15,0) DEFAULT NULL COMMENT '更新时间(UNIX秒)',
    PRIMARY KEY (ID),
    UNIQUE KEY uk_code_adjust_date (CODE, ADJUST, TRADE_DATE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='行情同步中转';

-- 周期K线物化表（周/月/季；日线原始行即 bar 不入表）。scripts 收尾阶段物化：
-- 常规周每股只 upsert 最新 1-2 个周期；有除权事件的股全周期重算覆盖；首启全量一次。
-- PERIOD_START/END 为周期首/末真实交易日 yyyymmdd（季线出参的 yyyymm 由展示层截取）。
CREATE TABLE IF NOT EXISTS stock_period_bar (
    ID           BIGINT        NOT NULL AUTO_INCREMENT,
    PERIOD_TYPE  CHAR(1)       NOT NULL COMMENT '周期类型：1=周 2=月 3=季',
    CODE         VARCHAR(16)   NOT NULL COMMENT '股票代码',
    ADJUST       CHAR(1)       NOT NULL COMMENT '复权类型',
    PERIOD_START VARCHAR(8)    NOT NULL COMMENT '周期首个交易日(yyyymmdd)',
    PERIOD_END   VARCHAR(8)    NOT NULL COMMENT '周期最后交易日(yyyymmdd)',
    OPEN         DECIMAL(12,4) NOT NULL COMMENT '周期首交易日开盘价',
    HIGH         DECIMAL(12,4) NOT NULL COMMENT '周期内最高价',
    LOW          DECIMAL(12,4) NOT NULL COMMENT '周期内最低价',
    CLOSE        DECIMAL(12,4) NOT NULL COMMENT '周期末交易日收盘价',
    CREATED_AT   DECIMAL(15,0) DEFAULT NULL COMMENT '创建时间(UNIX秒)',
    UPDATED_AT   DECIMAL(15,0) DEFAULT NULL COMMENT '更新时间(UNIX秒)',
    PRIMARY KEY (ID),
    UNIQUE KEY uk_type_code_adjust_end (PERIOD_TYPE, CODE, ADJUST, PERIOD_END)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='周期K线物化（周/月/季）';

-- 用户自选股（挂在注册用户名上；密钥登录的 subject="key" 也可用，独立命名空间不与用户共享）
CREATE TABLE IF NOT EXISTS user_watchlist (
    ID          BIGINT       NOT NULL AUTO_INCREMENT,
    USERNAME    VARCHAR(32)  NOT NULL COMMENT '用户名（token subject）',
    CODE        VARCHAR(16)  NOT NULL COMMENT '股票代码',
    CREATED_AT  DECIMAL(15,0) DEFAULT NULL COMMENT '创建时间(UNIX秒)',
    UPDATED_AT  DECIMAL(15,0) DEFAULT NULL COMMENT '更新时间(UNIX秒)',
    PRIMARY KEY (ID),
    UNIQUE KEY uk_user_code (USERNAME, CODE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户自选股';
