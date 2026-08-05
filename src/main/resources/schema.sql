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
    club_id         BIGINT       NOT NULL DEFAULT 0,
    creator_user_id BIGINT       NOT NULL DEFAULT 0,
    sb              BIGINT       NOT NULL DEFAULT 0,
    bb              BIGINT       NOT NULL DEFAULT 0,
    max_players     INT          NOT NULL DEFAULT 9,
    settle_time_mins INT         NOT NULL DEFAULT 0,
    rake_percent    INT          NOT NULL DEFAULT 0,
    diamond_cost    BIGINT       NOT NULL DEFAULT 0,
    rules_json      VARCHAR(2048) NOT NULL DEFAULT '',
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
    club_id         BIGINT       NOT NULL DEFAULT 0,
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

-- 系统参数(对齐扯旋 system_config):启动播种默认值,管理后台在线调整,改了立即生效
CREATE TABLE IF NOT EXISTS dz_system_config (
    cfg_key     VARCHAR(64)   NOT NULL PRIMARY KEY,
    cfg_value   VARCHAR(4096) NOT NULL DEFAULT '',
    remark      VARCHAR(256)  NOT NULL DEFAULT '',
    updated_at  DATETIME      NOT NULL
);

-- ============================================================
-- 俱乐部体系(德州独立俱乐部,规则对齐扯旋)
-- ============================================================

-- 俱乐部(state: 1正常 2解散)
CREATE TABLE IF NOT EXISTS dz_club (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    club_no         BIGINT       NOT NULL,
    name            VARCHAR(32)  NOT NULL,
    remark          VARCHAR(100) NOT NULL DEFAULT '',
    avatar          VARCHAR(255) NOT NULL DEFAULT '',
    notice          VARCHAR(256) NOT NULL DEFAULT '',
    creator_user_id BIGINT       NOT NULL,
    state           TINYINT      NOT NULL DEFAULT 1,
    diamond_cost    BIGINT       NOT NULL DEFAULT 0,
    created_at      DATETIME     NOT NULL,
    dissolved_at    DATETIME     NULL
);
CREATE INDEX idx_dz_club_no ON dz_club (club_no);
CREATE INDEX idx_dz_club_creator ON dz_club (creator_user_id);

-- 俱乐部成员(role: 1成员 2管理员 3创建者 4合伙人;
--   parent_user_id/level=推荐树;invite_code 俱乐部内唯一 6 位;
--   partner_rate=上级让给该合伙人的抽水比例 0-100)
CREATE TABLE IF NOT EXISTS dz_club_member (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    club_id         BIGINT       NOT NULL,
    user_id         BIGINT       NOT NULL,
    nickname        VARCHAR(64)  NOT NULL DEFAULT '',
    role            TINYINT      NOT NULL DEFAULT 1,
    parent_user_id  BIGINT       NOT NULL DEFAULT 0,
    level           INT          NOT NULL DEFAULT 0,
    invite_code     BIGINT       NOT NULL DEFAULT 0,
    partner_rate    INT          NOT NULL DEFAULT 0,
    -- 俱乐部积分(对齐扯旋 ClubMember.score):每俱乐部独立一本账,带入/退筹/罚金/抽水都走它
    score           BIGINT       NOT NULL DEFAULT 0,
    status          TINYINT      NOT NULL DEFAULT 1,
    created_at      DATETIME     NOT NULL
);
CREATE INDEX idx_dz_cm_club_user ON dz_club_member (club_id, user_id);
CREATE INDEX idx_dz_cm_user ON dz_club_member (user_id);
CREATE INDEX idx_dz_cm_invite ON dz_club_member (club_id, invite_code);
-- 老库升级(H2/MySQL 重复执行报错由 continue-on-error 忽略)
ALTER TABLE dz_club_member ADD COLUMN score BIGINT NOT NULL DEFAULT 0;

-- 俱乐部积分流水(type 全套对齐扯旋 game_score_log):
--   1坐下带入 2起立返还 3每局结算 4系统调整 5其他
--   10玩家上分(成员+) 11玩家下分(成员-) 12赠送转出 13赠送转入 14增发积分 15提取红利(抽水分成)
--   16玩家上分(操作者-) 17玩家下分(操作者+) 18礼物赠送 19逃跑惩罚(玩家-) 20逃跑惩罚(群主+) 21核销积分
CREATE TABLE IF NOT EXISTS dz_score_log (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    club_id         BIGINT       NOT NULL,
    user_id         BIGINT       NOT NULL,
    other_user_id   BIGINT       NOT NULL DEFAULT 0,
    type            INT          NOT NULL,
    amount          BIGINT       NOT NULL,
    before_score    BIGINT       NOT NULL DEFAULT 0,
    after_score     BIGINT       NOT NULL DEFAULT 0,
    remark          VARCHAR(128) NOT NULL DEFAULT '',
    created_at      DATETIME     NOT NULL
);
CREATE INDEX idx_dz_sl_user ON dz_score_log (club_id, user_id, created_at);

-- 入会申请(status: 0待审 1已同意 2已拒绝;code_type: 1俱乐部号 2邀请码)
CREATE TABLE IF NOT EXISTS dz_club_join_request (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    club_id         BIGINT       NOT NULL,
    user_id         BIGINT       NOT NULL,
    nickname        VARCHAR(64)  NOT NULL DEFAULT '',
    code_used       BIGINT       NOT NULL DEFAULT 0,
    code_type       TINYINT      NOT NULL DEFAULT 1,
    inviter_user_id BIGINT       NOT NULL DEFAULT 0,
    status          TINYINT      NOT NULL DEFAULT 0,
    reviewer_user_id BIGINT      NOT NULL DEFAULT 0,
    created_at      DATETIME     NOT NULL,
    reviewed_at     DATETIME     NULL
);
CREATE INDEX idx_dz_cjr_club ON dz_club_join_request (club_id, status);
CREATE INDEX idx_dz_cjr_user ON dz_club_join_request (user_id);

-- 抽水分成流水(周期/站起结算的 rake 沿推荐链分给 群主/合伙人)
CREATE TABLE IF NOT EXISTS dz_commission_log (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    club_id         BIGINT       NOT NULL,
    room_id         BIGINT       NOT NULL,
    from_user_id    BIGINT       NOT NULL,
    to_user_id      BIGINT       NOT NULL,
    to_role         VARCHAR(8)   NOT NULL DEFAULT '',
    total_profit    BIGINT       NOT NULL DEFAULT 0,
    commission      BIGINT       NOT NULL DEFAULT 0,
    share_amount    BIGINT       NOT NULL DEFAULT 0,
    share_rate      INT          NOT NULL DEFAULT 0,
    created_at      DATETIME     NOT NULL
);
CREATE INDEX idx_dz_cl_to ON dz_commission_log (to_user_id, created_at);
CREATE INDEX idx_dz_cl_club ON dz_commission_log (club_id);

-- 礼物配置(对齐扯旋 gift_config):
--   cost_type: SCORE=扣桌面带入 / CLUB_SCORE=扣俱乐部积分(流水type18) / DIAMOND=扣钻石
--   gift_key 对应前端动画键(meigui/zhadan/huojiantong 等)
CREATE TABLE IF NOT EXISTS dz_gift_config (
    id              BIGINT       AUTO_INCREMENT PRIMARY KEY,
    gift_key        VARCHAR(32)  NOT NULL,
    name            VARCHAR(32)  NOT NULL,
    cost_score      BIGINT       NOT NULL DEFAULT 0,
    cost_type       VARCHAR(16)  NOT NULL DEFAULT '',
    icon_url        VARCHAR(128) NOT NULL DEFAULT '',
    anim_key        VARCHAR(32)  NOT NULL DEFAULT '',
    enabled         TINYINT      NOT NULL DEFAULT 1,
    sort_no         INT          NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_dz_gift_key ON dz_gift_config (gift_key);

-- 主服用户表最小结构 — 仅开发 H2 生效;
-- 生产 dzpk.diamond-user-table 配成 chexuan_game.user,本表不使用(建了也无妨)
CREATE TABLE IF NOT EXISTS `user` (
    id              BIGINT       PRIMARY KEY,
    diamond         BIGINT       NOT NULL DEFAULT 0
);

-- ============================================================
-- 增量迁移(表已存在时补列;重复执行报错由 continue-on-error 忽略)
-- ============================================================
ALTER TABLE dz_room ADD COLUMN club_id BIGINT NOT NULL DEFAULT 0;
ALTER TABLE dz_room ADD COLUMN rules_json VARCHAR(2048) NOT NULL DEFAULT '';
-- 扣钻矩阵 JSON 超过 256:cfg_value 扩到 4096(旧库补;H2/MySQL 兼容 MODIFY)
ALTER TABLE dz_system_config MODIFY cfg_value VARCHAR(4096) NOT NULL DEFAULT '';
-- 俱乐部简介/头像(对齐扯旋 CreateClubRequest,旧库补列)
ALTER TABLE dz_club ADD COLUMN remark VARCHAR(100) NOT NULL DEFAULT '';
ALTER TABLE dz_club ADD COLUMN avatar VARCHAR(255) NOT NULL DEFAULT '';
-- 结算记录带俱乐部维度(俱乐部战绩弹框过滤用,旧库补列)
ALTER TABLE dz_settle_record ADD COLUMN club_id BIGINT NOT NULL DEFAULT 0;

-- ============================================================
-- 独立账号体系(不再与扯旋主服混用;钻石也独立在本表 diamond 列)
-- ============================================================
-- 注册字段对标扯旋 RegisterRequest:phone/username(昵称)/avatar/password/confirmPassword/
--   inviteCode(可选)/registerDevice(1=iOS 2=Android 3=Web);number_id=6位唯一编号(对标 numberId)
CREATE TABLE IF NOT EXISTS dz_user (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    phone           VARCHAR(20)  NOT NULL,
    number_id       VARCHAR(8)   NOT NULL DEFAULT '',
    password_hash   VARCHAR(128) NOT NULL,
    salt            VARCHAR(32)  NOT NULL,
    nickname        VARCHAR(32)  NOT NULL,
    avatar          VARCHAR(255) NOT NULL DEFAULT '',
    invite_code     VARCHAR(32)  NOT NULL DEFAULT '',
    register_device TINYINT      NOT NULL DEFAULT 1,
    diamond         BIGINT       NOT NULL DEFAULT 0,
    state           TINYINT      NOT NULL DEFAULT 1,
    -- 机器人标记(对齐扯旋 user.is_robot):0=真人 1=打牌机器人。机器人是真实账号+真实俱乐部成员
    is_robot        TINYINT      NOT NULL DEFAULT 0,
    created_at      DATETIME     NOT NULL,
    last_login_at   DATETIME     NULL
);
CREATE UNIQUE INDEX idx_dz_user_phone ON dz_user (phone);
CREATE INDEX idx_dz_user_number ON dz_user (number_id);
-- 机器人标记(旧库补列)
ALTER TABLE dz_user ADD COLUMN is_robot TINYINT NOT NULL DEFAULT 0;

-- 机器人俱乐部参数(对齐扯旋 RobotClubConfig 精简德州版,每俱乐部一条;
--   房间级覆盖在内存,管理台热改;含决策延时/性格分布/站起策略/控盘)
CREATE TABLE IF NOT EXISTS dz_robot_club_config (
    club_id                  BIGINT PRIMARY KEY,
    min_action_delay_ms      INT         NOT NULL DEFAULT 800,
    max_action_delay_ms      INT         NOT NULL DEFAULT 2500,
    aggressive_prob          INT         NOT NULL DEFAULT 30,
    conservative_prob        INT         NOT NULL DEFAULT 30,
    period_win_standup_prob  INT         NOT NULL DEFAULT 40,
    period_lose_standup_prob INT         NOT NULL DEFAULT 30,
    chip_cap_multiplier      INT         NOT NULL DEFAULT 0,
    loss_cap_multiplier      INT         NOT NULL DEFAULT 0,
    profit_enabled           TINYINT     NOT NULL DEFAULT 0,
    profit_mode              VARCHAR(10) NOT NULL DEFAULT 'absolute',
    profit_target            BIGINT      NOT NULL DEFAULT 0,
    profit_target_rate       INT         NOT NULL DEFAULT 0,
    profit_per_hand_cap      BIGINT      NOT NULL DEFAULT 0,
    profit_adjust_strength   INT         NOT NULL DEFAULT 50,
    updated_at               DATETIME    NOT NULL
);
