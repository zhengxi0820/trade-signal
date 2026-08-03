-- trade-signal 数据库结构（MySQL 8.x）
--
-- 与实体类一一对应：
--   stock_quote    ↔ src/main/java/com/xi/orm/entity/StockQuoteDO.java
--   work_day       ↔ src/main/java/com/xi/orm/entity/WorkDayDO.java
--   stock_info     ↔ src/main/java/com/xi/orm/entity/StockInfoDO.java
--   stock_dividend ↔ scripts/adjust（因子反推生成，无对应 Java 实体）
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
    BOARD_TYPE  CHAR(1)       NOT NULL COMMENT '板块：0=沪深主板 1=科创板 2=创业板 3=北交所',
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
    SOURCE      VARCHAR(32)    NOT NULL COMMENT '来源：derive=价格比反推',
    CREATED_AT  DECIMAL(15,0)  DEFAULT NULL COMMENT '创建时间(UNIX秒)',
    UPDATED_AT  DECIMAL(15,0)  DEFAULT NULL COMMENT '更新时间(UNIX秒)',
    PRIMARY KEY (ID),
    UNIQUE KEY uk_code_exdate (CODE, EX_DATE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='除权除息事件';
