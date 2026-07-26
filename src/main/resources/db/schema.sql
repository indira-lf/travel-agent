-- GoGo 差旅助手数据库初始化脚本
-- 请在 MySQL 中先执行 CREATE DATABASE gogo_travel DEFAULT CHARACTER SET utf8mb4;

CREATE TABLE IF NOT EXISTS agentscope_session (
                                                   session_id VARCHAR(255) NOT NULL,
    state_key VARCHAR(255) NOT NULL,
    item_index INT NOT NULL DEFAULT 0,
    state_data LONGTEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (session_id, state_key, item_index)
    ) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;


CREATE TABLE IF NOT EXISTS `travel_order` (
    `order_id`       VARCHAR(64)  NOT NULL COMMENT '差旅单ID',
    `user_id`        VARCHAR(64)  NOT NULL COMMENT '申请人ID',
    `destination`    VARCHAR(256) DEFAULT NULL COMMENT '目的地',
    `departure_city` VARCHAR(128) DEFAULT NULL COMMENT '出发城市',
    `departure_date` VARCHAR(32)  DEFAULT NULL COMMENT '出发日期',
    `return_date`    VARCHAR(32)  DEFAULT NULL COMMENT '返回日期',
    `purpose`        VARCHAR(512) DEFAULT NULL COMMENT '出差事由',
    `status`         VARCHAR(32)  NOT NULL DEFAULT 'DRAFT' COMMENT '状态: DRAFT/SUBMITTED/APPROVED/REJECTED/COMPLETED/CANCELLED',
    `approval_id`    VARCHAR(64)  DEFAULT NULL COMMENT '关联审批单ID',
    `created_at`     DATETIME     DEFAULT NULL COMMENT '创建时间',
    `updated_at`     DATETIME     DEFAULT NULL COMMENT '最后修改时间',
    PRIMARY KEY (`order_id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_user_status` (`user_id`, `status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '差旅申请单';


CREATE TABLE IF NOT EXISTS `approval_record` (
    `process_instance_id` VARCHAR(64)   NOT NULL COMMENT '审批流程实例ID',
    `user_id`             VARCHAR(64)   NOT NULL COMMENT '申请人ID',
    `title`               VARCHAR(256)  DEFAULT NULL COMMENT '审批标题',
    `status`              VARCHAR(32)   NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING/APPROVED/REJECTED/CANCELLED',
    `approval_form`       TEXT          DEFAULT NULL COMMENT '审批表单JSON',
    `remark`              VARCHAR(512)  DEFAULT NULL COMMENT '备注',
    `submit_time`         DATETIME      DEFAULT NULL COMMENT '提交时间',
    `update_time`         DATETIME      DEFAULT NULL COMMENT '最后更新时间',
    `order_id`            VARCHAR(64)   DEFAULT NULL COMMENT '关联差旅单ID',
    PRIMARY KEY (`process_instance_id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_order_id` (`order_id`),
    KEY `idx_order_submit` (`order_id`, `submit_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '差旅审批记录';


CREATE TABLE IF NOT EXISTS `user_profile` (
    `user_id`       VARCHAR(64)  NOT NULL COMMENT '用户ID',
    `base_city`     VARCHAR(128) DEFAULT NULL COMMENT '常驻城市',
    `level`         VARCHAR(16)  DEFAULT NULL COMMENT '职级，如 P5/P6/P7/P8',
    `name_pinyin`   VARCHAR(128) DEFAULT NULL COMMENT '姓名拼音（大写，姓在前名在后，空格分隔），如 ZHANG SAN，用于酒店预订',
    `email`         VARCHAR(128) DEFAULT NULL COMMENT '用户邮箱，用于酒店预订下单联系人',
    `chinese_name`  VARCHAR(64)  DEFAULT NULL COMMENT '中文姓名，如 张三，用于机票预订乘客信息',
    `id_type`       INT          DEFAULT NULL COMMENT '证件类型（0-身份证 1-护照 2-其他 3-回乡证 4-军官证 5-警官证 6-港澳通行证 7-台胞证 8-台湾通行证 9-外国人永久居留身份证）',
    `id_number`     VARCHAR(64)  DEFAULT NULL COMMENT '证件号码（身份证/护照等），用于机票预订',
    `phone`         VARCHAR(32)  DEFAULT NULL COMMENT '手机号，用于机票预订联系人',
    `gender`        VARCHAR(4)   DEFAULT NULL COMMENT '性别：M-男，F-女',
    PRIMARY KEY (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户档案';


CREATE TABLE IF NOT EXISTS `user_account` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `user_id`      VARCHAR(64)  NOT NULL COMMENT '关联 user_profile.user_id',
    `username`     VARCHAR(64)  NOT NULL COMMENT '登录账号',
    `password`     VARCHAR(128) NOT NULL COMMENT '登录密码（生产环境应使用 BCrypt 加密）',
    `real_name`    VARCHAR(64)  DEFAULT NULL COMMENT '用户真实姓名',
    `role`         VARCHAR(16)  NOT NULL DEFAULT 'USER' COMMENT '角色：USER 普通用户 / ADMIN 管理员',
    `created_time` DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_id`  (`user_id`),
    UNIQUE KEY `uk_username` (`username`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户登录账号表';


CREATE TABLE IF NOT EXISTS `user_preference` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `user_id`      VARCHAR(64)  NOT NULL COMMENT '用户ID',
    `source`       VARCHAR(32)  NOT NULL COMMENT '来源: EXPLICIT/FEEDBACK/IMPLICIT',
    `pref_key`     VARCHAR(128) NOT NULL COMMENT '偏好项名称',
    `pref_value`   VARCHAR(512) DEFAULT NULL COMMENT '偏好项内容',
    `weight`       DOUBLE       NOT NULL DEFAULT 1.0 COMMENT '权重',
    `created_time` DATETIME     DEFAULT NULL COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_id`     (`user_id`),
    KEY `idx_user_source` (`user_id`, `source`),
    UNIQUE KEY `uk_user_source_key` (`user_id`, `source`, `pref_key`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户偏好条目';


-- ============================================================
-- 差旅政策配置（城市等级已改由 application.yml 配置，此处仅管理政策规则）
-- ============================================================


CREATE TABLE IF NOT EXISTS `travel_policy_rule` (
    `id`                    BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `level_min`             INT         NOT NULL COMMENT '职级区间下限（数字）',
    `level_max`             INT         NOT NULL COMMENT '职级区间上限（99表示无上限）',
    `city_tier`             VARCHAR(16) NOT NULL COMMENT '城市等级：一线/新一线/其他',
    `flight_class`          VARCHAR(32) DEFAULT NULL COMMENT '允许的最高机票舱位',
    `train_seat_class`      VARCHAR(16) DEFAULT NULL COMMENT '允许的最高高铁座位',
    `hotel_limit`           DOUBLE      NOT NULL DEFAULT 0 COMMENT '酒店每晚上限（元）',
    `hotel_star_limit`      INT         NOT NULL DEFAULT 4 COMMENT '酒店星级上限',
    `daily_meal_limit`      DOUBLE      NOT NULL DEFAULT 0 COMMENT '餐补每日上限（元）',
    `daily_transport_limit` DOUBLE      NOT NULL DEFAULT 0 COMMENT '交通补贴每日上限（元）',
    `approval_threshold`    DOUBLE      NOT NULL DEFAULT 0 COMMENT '审批金额阈値（超过此金额需审批）',
    `advance_booking_days`  INT         NOT NULL DEFAULT 3 COMMENT '提前预订最少天数',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_level_city` (`level_min`, `level_max`, `city_tier`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '差旅政策规则表';


-- ============================================================
-- 对话与消息历史（前端历史会话展示）
-- ============================================================

CREATE TABLE IF NOT EXISTS `chat_conversation` (
    `conversation_id` VARCHAR(64)  NOT NULL COMMENT '会话ID（同前端 sessionId）',
    `user_id`         VARCHAR(64)  NOT NULL COMMENT '用户ID',
    `title`           VARCHAR(256) NOT NULL DEFAULT '新对话' COMMENT '会话标题',
    `created_at`      DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    `deleted`         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标志',
    PRIMARY KEY (`conversation_id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_updated_at` (`updated_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '对话会话';

CREATE TABLE IF NOT EXISTS `chat_message` (
    `message_id`      VARCHAR(64)  NOT NULL COMMENT '消息ID',
    `conversation_id` VARCHAR(64)  NOT NULL COMMENT '会话ID',
    `role`            VARCHAR(32)  NOT NULL COMMENT '角色：user/agent/system',
    `content`         TEXT         DEFAULT NULL COMMENT '消息内容',
    `agent_name`      VARCHAR(128) DEFAULT NULL COMMENT 'Agent名称（role=agent 时）',
    `extra`           JSON         DEFAULT NULL COMMENT '扩展信息（进度快照/推荐问题等）',
    `feedback`        VARCHAR(16)  DEFAULT NULL COMMENT '用户反馈：LIKE 点赞 / DISLIKE 点踩 / NULL 未反馈',
    `feedback_at`     DATETIME     DEFAULT NULL COMMENT '反馈时间',
    `created_at`      DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `deleted`         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标志',
    PRIMARY KEY (`message_id`),
    KEY `idx_conversation_id` (`conversation_id`),
    KEY `idx_created_at` (`created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '对话消息';

-- ============================================================
-- 用户 API Key 存储（加密存储，支持多服务提供商）
-- ============================================================

CREATE TABLE IF NOT EXISTS `user_api_key` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `user_id`      VARCHAR(64)  NOT NULL COMMENT '用户ID',
    `provider`     VARCHAR(64)  NOT NULL COMMENT '服务提供商，如 flight-manager',
    `api_key_enc`  VARCHAR(512) NOT NULL COMMENT 'AES 加密后的 API Key（Base64 编码）',
    `created_at`   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_provider` (`user_id`, `provider`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户第三方 API Key（加密存储）';

-- ============================================================
-- Agent 预订记录（机票 / 酒店 / 火车票等，兼容多预订平台）
-- ============================================================

CREATE TABLE IF NOT EXISTS `booking_record` (
    `id`                BIGINT        NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `booking_id`        VARCHAR(64)   NOT NULL COMMENT '内部预订单号（系统生成，全局唯一）',
    `user_id`           VARCHAR(64)   NOT NULL COMMENT '用户ID',
    `conversation_id`   VARCHAR(64)   DEFAULT NULL COMMENT '关联会话ID（chat_conversation.conversation_id）',
    `travel_order_id`    VARCHAR(64)   DEFAULT NULL COMMENT '关联差旅单ID（travel_order.order_id，可为空）',
    `biz_type`          VARCHAR(16)   NOT NULL COMMENT '预订业务类型: FLIGHT/HOTEL/TRAIN/TICKET/CRUISE/VACATION',
    `platform`          VARCHAR(32)   NOT NULL COMMENT '预订平台: tuniu / rolling-go-hotel / flight-manager 等',
    `external_order_no` VARCHAR(128)  DEFAULT NULL COMMENT '外部平台主订单号',
    `status`            VARCHAR(32)   NOT NULL DEFAULT 'CREATED' COMMENT '统一预订状态: CREATED/PENDING_PAYMENT/PAID/CONFIRMED/COMPLETED/CANCELLED/REFUNDED/FAILED',
    `external_status`   VARCHAR(64)   DEFAULT NULL COMMENT '外部平台原始状态码/文案（保留平台原值）',
    `payment_status`    VARCHAR(32)   DEFAULT NULL COMMENT '支付状态: UNPAID/PAID/REFUNDED',
    `title`             VARCHAR(256)  DEFAULT NULL COMMENT '预订标题（如“北京→上海 MU5101”或酒店名）',
    `total_amount`      DECIMAL(12,2) DEFAULT NULL COMMENT '订单总金额（元）',
    `currency`          VARCHAR(8)    DEFAULT 'CNY' COMMENT '币种',
    `contact_name`      VARCHAR(64)   DEFAULT NULL COMMENT '联系人姓名',
    `contact_phone`     VARCHAR(32)   DEFAULT NULL COMMENT '联系人手机号',
    `start_time`        DATETIME      DEFAULT NULL COMMENT '服务开始时间（出发/入住时间）',
    `end_time`          DATETIME      DEFAULT NULL COMMENT '服务结束时间（到达/离店时间）',
    `detail`            JSON          DEFAULT NULL COMMENT '平台原始/明细信息JSON（航班号、房型、乘客、行程等）',
    `remark`            VARCHAR(512)  DEFAULT NULL COMMENT '备注',
    `booked_at`         DATETIME      DEFAULT NULL COMMENT '下单时间',
    `created_at`        DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`        DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`           TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除标志',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_booking_id` (`booking_id`),
    UNIQUE KEY `uk_platform_order` (`platform`, `biz_type`, `external_order_no`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_user_biz` (`user_id`, `biz_type`),
    KEY `idx_travel_order_id` (`travel_order_id`),
    KEY `idx_status` (`status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Agent预订记录（机票/酒店/火车票等，兼容多平台）';


-- ============================================================
-- 测试数据（本地开发/演示用，生产环境按需清空）
-- ============================================================

-- 1. 用户档案
INSERT IGNORE INTO `user_profile` (`user_id`, `base_city`, `level`, `name_pinyin`, `email`, `chinese_name`, `id_type`, `phone`, `gender`) VALUES
    ('u_001', '北京', 'P7', 'ZHANG SAN',   'zhangsan@example.com', '张三', 0, '13800000001', 'M'),
    ('u001',  '上海', 'P7', 'LI SI',       'lisi@example.com',     '李四', 0, '13800000002', 'M'),
    ('u002',  '北京', 'P6', 'WANG WU',     'wangwu@example.com',   '王五', 0, '13800000003', 'M'),
    ('u003',  '成都', 'P5', 'ZHAO LIU',    'zhaoliu@example.com',  '赵六', 0, '13800000004', 'F'),
    ('u004',  '深圳', 'P8', 'CHEN QI',     'chenqi@example.com',   '陈七', 0, '13800000005', 'M');

-- 2. 登录账号（密码明文 123456，仅限本地开发；生产环境请替换为 BCrypt 哈希）
INSERT IGNORE INTO `user_account` (`user_id`, `username`, `password`, `real_name`, `role`) VALUES
    ('u_001', 'admin',   '123456', '系统管理员', 'ADMIN'),
    ('u001',  'alice',   '123456', '张三',     'USER'),
    ('u002',  'bob',     '123456', '李四',     'USER'),
    ('u003',  'charlie', '123456', '王五',     'USER'),
    ('u004',  'david',   '123456', '赵六',     'USER');

-- 3. 差旅政策规则（4 个职级区间 × 3 个城市等级 = 12 条）
-- 列顺序：level_min, level_max, city_tier, flight_class, train_seat_class,
--         hotel_limit, hotel_star_limit, daily_meal_limit, daily_transport_limit, approval_threshold, advance_booking_days
INSERT IGNORE INTO `travel_policy_rule`
    (`level_min`,`level_max`,`city_tier`,`flight_class`,`train_seat_class`,`hotel_limit`,`hotel_star_limit`,`daily_meal_limit`,`daily_transport_limit`,`approval_threshold`,`advance_booking_days`)
VALUES
    -- P8+：商务舱 / 一等座
    (8, 99, '一线',   '商务舱',        '一等座', 700, 5, 200, 300, 8000, 3),
    (8, 99, '新一线', '商务舱',        '一等座', 550, 5, 200, 300, 8000, 3),
    (8, 99, '其他',   '商务舱',        '一等座', 450, 5, 200, 300, 8000, 3),
    -- P7：经济舱/商务舱 / 一等座
    (7, 7,  '一线',   '经济舱/商务舱', '一等座', 600, 5, 200, 300, 8000, 3),
    (7, 7,  '新一线', '经济舱/商务舱', '一等座', 500, 5, 200, 300, 8000, 3),
    (7, 7,  '其他',   '经济舱/商务舱', '一等座', 400, 5, 200, 300, 8000, 3),
    -- P6：经济舱 / 一等座
    (6, 6,  '一线',   '经济舱',        '一等座', 500, 4, 150, 200, 5000, 3),
    (6, 6,  '新一线', '经济舱',        '一等座', 400, 4, 150, 200, 5000, 3),
    (6, 6,  '其他',   '经济舱',        '一等座', 350, 4, 150, 200, 5000, 3),
    -- P5-：经济舱 / 二等座
    (1, 5,  '一线',   '经济舱',        '二等座', 400, 4, 150, 200, 5000, 3),
    (1, 5,  '新一线', '经济舱',        '二等座', 350, 4, 150, 200, 5000, 3),
    (1, 5,  '其他',   '经济舱',        '二等座', 300, 4, 150, 200, 5000, 3);

-- 4. 差旅申请单示例（覆盖 DRAFT / SUBMITTED / APPROVED 三种典型状态）
INSERT IGNORE INTO `travel_order`
    (`order_id`, `user_id`, `destination`, `departure_city`, `departure_date`, `return_date`, `purpose`, `status`, `approval_id`, `created_at`, `updated_at`)
VALUES
    ('to_20260701_001', 'u001', '上海', '北京', '2026-07-10', '2026-07-12', '客户拜访',   'APPROVED',  'ap_20260701_001', '2026-07-01 10:00:00', '2026-07-02 09:00:00'),
    ('to_20260710_002', 'u001', '深圳', '北京', '2026-07-20', '2026-07-22', '产品交流会', 'SUBMITTED', 'ap_20260710_002', '2026-07-10 14:30:00', '2026-07-10 14:30:00'),
    ('to_20260715_003', 'u002', '成都', '上海', '2026-08-01', '2026-08-03', '业务培训',   'DRAFT',     NULL,              '2026-07-15 09:00:00', '2026-07-15 09:00:00');

-- 5. 审批记录示例（关联上面的差旅单）
INSERT IGNORE INTO `approval_record`
    (`process_instance_id`, `user_id`, `title`, `status`, `approval_form`, `remark`, `submit_time`, `update_time`, `order_id`)
VALUES
    ('ap_20260701_001', 'u001', '张三-北京→上海-2026/07/10~07/12', 'APPROVED', '{"destination":"上海","budget":3000}', '预算合理，同意', '2026-07-01 10:05:00', '2026-07-02 09:00:00', 'to_20260701_001'),
    ('ap_20260710_002', 'u001', '张三-北京→深圳-2026/07/20~07/22', 'PENDING',  '{"destination":"深圳","budget":4500}', NULL,             '2026-07-10 14:35:00', '2026-07-10 14:35:00', 'to_20260710_002');

-- 6. 外部预订记录示例（已通过 tuniu-cli / rolling-go-hotel 完成的机票 + 酒店）
INSERT IGNORE INTO `booking_record`
    (`booking_id`, `user_id`, `conversation_id`, `travel_order_id`, `biz_type`, `platform`, `external_order_no`, `status`, `payment_status`, `title`, `total_amount`, `currency`, `contact_name`, `contact_phone`, `start_time`, `end_time`, `remark`, `booked_at`)
VALUES
    ('bk_20260702_001', 'u001', NULL, 'to_20260701_001', 'FLIGHT', 'tuniu',             'TN2026070200001',  'CONFIRMED', 'PAID', '北京→上海 MU5101 07/10 09:00',    1280.00, 'CNY', '张三', '13800000002', '2026-07-10 09:00:00', '2026-07-10 11:20:00', '示例数据', '2026-07-02 10:30:00'),
    ('bk_20260702_002', 'u001', NULL, 'to_20260701_001', 'HOTEL',  'rolling-go-hotel',  'RGH20260702HTL88', 'CONFIRMED', 'PAID', '上海静安希尔顿酒店-高级大床房',    1580.00, 'CNY', '张三', '13800000002', '2026-07-10 15:00:00', '2026-07-12 12:00:00', '示例数据', '2026-07-02 10:45:00');