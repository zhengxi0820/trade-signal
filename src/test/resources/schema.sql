-- 测试库（H2 内存库）最小表结构，仅覆盖查询涉及的列，与 docs/trade-signal-schema.sql 口径一致
create table stock_quote (
    ID bigint auto_increment primary key,
    CODE varchar(12),
    OPEN decimal(20, 4),
    HIGH decimal(20, 4),
    LOW decimal(20, 4),
    CLOSE decimal(20, 4),
    VOLUME bigint,
    TRADE_DATE varchar(8),
    ADJUST varchar(1),
    CREATED_AT varchar(19),
    UPDATED_AT varchar(19)
);
-- 与生产一致的索引（月/季线 SQL 预聚合的自连接依赖它，缺了 H2 会全表扫描卡死）
create unique index uk_code_adjust_date on stock_quote (CODE, ADJUST, TRADE_DATE);
create index idx_trade_date on stock_quote (TRADE_DATE);

create table stock_info (
    ID bigint auto_increment primary key,
    CODE varchar(12),
    NAME varchar(32),
    MARKET varchar(10),
    BOARD_TYPE char(1),
    CREATED_AT varchar(19),
    UPDATED_AT varchar(19)
);

create table work_day (
    ID bigint auto_increment primary key,
    MARKET varchar(10),
    TRADE_DATE varchar(8),
    CREATED_AT varchar(19),
    UPDATED_AT varchar(19)
);

create table app_user (
    ID bigint auto_increment primary key,
    USERNAME varchar(32) not null,
    PASSWORD varchar(60) not null,
    STATUS char(1) not null default '1',
    INVITE_CODE varchar(64),
    CREATED_AT varchar(19),
    UPDATED_AT varchar(19)
);
create unique index uk_username on app_user (USERNAME);
