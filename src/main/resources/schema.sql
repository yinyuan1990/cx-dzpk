-- ============================================================
-- cx-dzpk 德州子游戏 建表(独立库 cx_dzpk)
-- 架构:每个子游戏独立一个库,同一 MySQL 实例;
--   钻石公用 → 本体在主库 chexuan_game.user.diamond,德州跨库原子扣减,
--   表名走配置 dzpk.diamond-user-table(开发 H2 用下面的最小 user 表)。
-- 全部 IF NOT EXISTS 可反复执行;CREATE INDEX 重复报错由
--   spring.sql.init.continue-on-error 忽略(MySQL 索引不支持 IF NOT EXISTS)。
-- ============================================================

-- 房间档案(创建即写,销毁补 closed_at)
CREATE TABLE IF NOT EXISTS dz_room (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    room_id         BIGINT       NOT NULL,
    name            VARCHAR(64)  NOT NULL DEFAULT '',
    creator_user_id BIGINT       NOT NULL DEFAULT 0,
    sb              BIGINT       NOT NULL DEFAULT 0,
    bb              BIGINT       NOT NULL DEFAULT 0,
    max_players     INT          NOT NULL DEFAULT 9,
    settle_time_mins INT         NOT NULL DEFAULT 0,
    rake_percent    INT          NOT NULL DEFAULT 0,
    diamond_cost    BIGINT       NOT NULL DEFAULT 0,
    created_at      DATETIME     NOT NULL,
    closed_at       DATETIME     NULL
);
CREATE INDEX idx_dz_room_room_id ON dz_room (room_id);

-- 每手战绩明细(一手结束,每个参与者一行;对齐扯旋 game_record 粒度)
CREATE TABLE IF NOT EXISTS dz_hand_record (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    room_id         BIGINT       NOT NULL,
    hand_no         BIGINT       NOT NULL,
    user_id         BIGINT       NOT NULL,
    nickname        VARCHAR(64)  NOT NULL DEFAULT '',
    seat            INT          NOT NULL DEFAULT -1,
    period_seq      INT          NOT NULL DEFAULT 0,
    net_win         BIGINT       NOT NULL DEFAULT 0,
    total_bet       BIGINT       NOT NULL DEFAULT 0,
    stack_after     BIGINT       NOT NULL DEFAULT 0,
    folded          TINYINT      NOT NULL DEFAULT 0,
    showdown        TINYINT      NOT NULL DEFAULT 0,
    hole_cards      VARCHAR(8)   NULL,
    board           VARCHAR(16)  NULL,
    hand_type       INT          NULL,
    hand_name       VARCHAR(16)  NULL,
    created_at      DATETIME     NOT NULL
);
CREATE INDEX idx_dz_hand_user ON dz_hand_record (user_id, created_at);
CREATE INDEX idx_dz_hand_room ON dz_hand_record (room_id, hand_no);

-- 周期/站起结算(战绩权威盈亏,对齐扯旋 room_settle_record)
-- reason: period=周期到点结算 / standup=主动站起 / leave=离房
--         / buyin_timeout=补带入超时
CREATE TABLE IF NOT EXISTS dz_settle_record (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    room_id         BIGINT       NOT NULL,
    room_name       VARCHAR(64)  NOT NULL DEFAULT '',
    user_id         BIGINT       NOT NULL,
    nickname        VARCHAR(64)  NOT NULL DEFAULT '',
    period_seq      INT          NOT NULL DEFAULT 0,
    bring_in        BIGINT       NOT NULL DEFAULT 0,
    final_stack     BIGINT       NOT NULL DEFAULT 0,
    profit          BIGINT       NOT NULL DEFAULT 0,
    rake            BIGINT       NOT NULL DEFAULT 0,
    refund          BIGINT       NOT NULL DEFAULT 0,
    hand_count      INT          NOT NULL DEFAULT 0,
    win_count       INT          NOT NULL DEFAULT 0,
    lose_count      INT          NOT NULL DEFAULT 0,
    played_secs     BIGINT       NOT NULL DEFAULT 0,
    reason          VARCHAR(20)  NOT NULL DEFAULT '',
    created_at      DATETIME     NOT NULL
);
CREATE INDEX idx_dz_settle_user ON dz_settle_record (user_id, created_at);
CREATE INDEX idx_dz_settle_room ON dz_settle_record (room_id);

-- 德州金币钱包(带入货币,按游戏独立;首次访问按 guest-init-balance 初始化)
CREATE TABLE IF NOT EXISTS dz_user_wallet (
    user_id         BIGINT       PRIMARY KEY,
    balance         BIGINT       NOT NULL DEFAULT 0,
    updated_at      DATETIME     NOT NULL
);

-- 钻石流水(钻石本体在主库 user.diamond,这里只记德州侧变动)
CREATE TABLE IF NOT EXISTS dz_diamond_log (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT       NOT NULL,
    amount          BIGINT       NOT NULL,
    balance_after   BIGINT       NOT NULL DEFAULT 0,
    type            VARCHAR(20)  NOT NULL DEFAULT '',
    remark          VARCHAR(128) NOT NULL DEFAULT '',
    created_at      DATETIME     NOT NULL
);
CREATE INDEX idx_dz_diamond_user ON dz_diamond_log (user_id, created_at);

-- 主服用户表最小结构 — 仅开发 H2 生效;
-- 生产 dzpk.diamond-user-table 配成 chexuan_game.user,本表不使用(建了也无妨)
CREATE TABLE IF NOT EXISTS `user` (
    id              BIGINT       PRIMARY KEY,
    diamond         BIGINT       NOT NULL DEFAULT 0
);
