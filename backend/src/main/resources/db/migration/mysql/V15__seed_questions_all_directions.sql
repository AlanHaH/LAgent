-- 补齐各方向种子题，保证每个可选方向都能生成诊断。
-- 设计：
--  1) 现有库中手工添加的经济学题(4000-4007)原样收录，保证全新库与现库一致；
--  2) 新增题使用 5000+ 段，避开已占用的 4000 段；
--  3) 全部使用 INSERT IGNORE：现有库自动跳过已存在行，全新库全量写入。
-- 覆盖（含递归聚合的父方向）：微观(201) 5 题、宏观(202) 6 题、经济思维(203) 2 题、
--   前端(102) 3 题、Java(101) 4 题、AI Agent(103) 3 题。

INSERT IGNORE INTO question(id, public_id, owner_user_id, visibility, current_version_no, status, source_type,
  created_at, created_by, updated_at, updated_by, version)
VALUES
  -- 现有库手工添加的经济学种子题（原样收录）
  (4000, 'seed-econ-scarcity-1', NULL, 'PUBLIC', 1, 'PUBLISHED', 'CURATED', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0),
  (4001, 'seed-econ-margin-1', NULL, 'PUBLIC', 1, 'PUBLISHED', 'CURATED', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0),
  (4002, 'seed-econ-model-1', NULL, 'PUBLIC', 1, 'PUBLISHED', 'CURATED', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0),
  (4003, 'seed-micro-demand-1', NULL, 'PUBLIC', 1, 'PUBLISHED', 'CURATED', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0),
  (4004, 'seed-micro-elasticity-1', NULL, 'PUBLIC', 1, 'PUBLISHED', 'CURATED', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0),
  (4005, 'seed-macro-gdp-1', NULL, 'PUBLIC', 1, 'PUBLISHED', 'CURATED', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0),
  (4006, 'seed-macro-inflation-1', NULL, 'PUBLIC', 1, 'PUBLISHED', 'CURATED', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0),
  (4007, 'seed-macro-policy-1', NULL, 'PUBLIC', 1, 'PUBLISHED', 'CURATED', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0),
  -- 新增题：微观/宏观/经济思维 补足，前端/Java/AI 加厚
  (5000, 'seed-micro-demand-2', NULL, 'PUBLIC', 1, 'PUBLISHED', 'CURATED', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0),
  (5001, 'seed-micro-elasticity-2', NULL, 'PUBLIC', 1, 'PUBLISHED', 'CURATED', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0),
  (5002, 'seed-micro-market-1', NULL, 'PUBLIC', 1, 'PUBLISHED', 'CURATED', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0),
  (5003, 'seed-macro-gdp-2', NULL, 'PUBLIC', 1, 'PUBLISHED', 'CURATED', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0),
  (5004, 'seed-macro-inflation-2', NULL, 'PUBLIC', 1, 'PUBLISHED', 'CURATED', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0),
  (5005, 'seed-macro-policy-2', NULL, 'PUBLIC', 1, 'PUBLISHED', 'CURATED', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0),
  (5006, 'seed-econ-data-1', NULL, 'PUBLIC', 1, 'PUBLISHED', 'CURATED', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0),
  (5007, 'seed-econ-case-1', NULL, 'PUBLIC', 1, 'PUBLISHED', 'CURATED', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0),
  (5008, 'seed-html-1', NULL, 'PUBLIC', 1, 'PUBLISHED', 'CURATED', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0),
  (5009, 'seed-js-1', NULL, 'PUBLIC', 1, 'PUBLISHED', 'CURATED', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0),
  (5010, 'seed-java-collections-1', NULL, 'PUBLIC', 1, 'PUBLISHED', 'CURATED', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0),
  (5011, 'seed-prompt-1', NULL, 'PUBLIC', 1, 'PUBLISHED', 'CURATED', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0);

INSERT IGNORE INTO question_version(id, question_id, version_no, type, stem, options_json, answer_json, rubric_json, analysis, difficulty, created_at)
VALUES
  -- 现有库手工添加的经济学题（原样收录）
  (4100, 4000, 1, 'SINGLE_CHOICE', '经济学研究稀缺性的核心原因是什么？', '["资源有限而人的需求多样", "商品价格永远上涨", "企业只追求利润", "政府可以决定所有价格"]', '"A"', NULL, '稀缺性来自有限资源与多样需求之间的张力，因此必须做选择。', 1, UTC_TIMESTAMP(6)),
  (4101, 4001, 1, 'TRUE_FALSE', '边际分析关注的是“多做一单位”带来的额外收益和额外成本。', '["正确", "错误"]', 'true', NULL, '边际分析比较增量收益与增量成本，是经济决策的基础工具。', 2, UTC_TIMESTAMP(6)),
  (4102, 4002, 1, 'SINGLE_CHOICE', '“其他条件不变”在经济模型中的作用是什么？', '["隔离主要变量之间的关系", "证明现实永远不变", "取消数据验证", "让结论不需要假设"]', '"A"', NULL, '该假设用于简化复杂现实，观察关键变量之间的关系。', 2, UTC_TIMESTAMP(6)),
  (4103, 4003, 1, 'SINGLE_CHOICE', '在其他条件不变时，商品价格上升通常会导致需求量如何变化？', '["下降", "上升", "不可能变化", "一定变为零"]', '"A"', NULL, '需求定律说明价格上升通常会减少需求量。', 2, UTC_TIMESTAMP(6)),
  (4104, 4004, 1, 'SINGLE_CHOICE', '价格弹性较高的商品，价格小幅上升通常意味着什么？', '["需求量可能明显下降", "需求量完全不变", "供给量必然为零", "消费者收入一定增加"]', '"A"', NULL, '弹性较高表示需求量对价格变化更敏感。', 3, UTC_TIMESTAMP(6)),
  (4105, 4005, 1, 'SINGLE_CHOICE', 'GDP 通常衡量的是一个经济体在一定时期内的什么？', '["最终产品和服务的市场价值", "所有二手商品交易总额", "家庭全部资产存量", "政府债务总额"]', '"A"', NULL, 'GDP 衡量一定时期内最终产品和服务的市场价值。', 2, UTC_TIMESTAMP(6)),
  (4106, 4006, 1, 'TRUE_FALSE', '通货膨胀表示总体价格水平持续上升，而不是某一种商品临时涨价。', '["正确", "错误"]', 'true', NULL, '通货膨胀关注总体价格水平的持续变化。', 2, UTC_TIMESTAMP(6)),
  (4107, 4007, 1, 'SINGLE_CHOICE', '降低基准利率通常更接近哪类宏观政策工具？', '["货币政策", "产业政策", "税收征管", "贸易配额"]', '"A"', NULL, '利率调节属于货币政策常见工具。', 3, UTC_TIMESTAMP(6)),
  -- 新增题
  (5100, 5000, 1, 'SINGLE_CHOICE', '在其他条件不变时，商品需求曲线整体右移通常意味着什么？', '["同一价格水平下需求量增加", "需求量下降", "价格必然下降", "供给增加"]', '"A"', NULL, '需求曲线右移表示在每个价格水平上需求量都增加，常见于收入增长或偏好增强。', 2, UTC_TIMESTAMP(6)),
  (5101, 5001, 1, 'SINGLE_CHOICE', '某商品需求价格弹性大于 1，当价格上涨 10% 时，需求量最可能的变化是？', '["下降超过 10%", "下降不足 10%", "保持不变", "上涨超过 10%"]', '"A"', NULL, '弹性大于 1 属于富有弹性，价格上升导致需求量下降的比例大于价格上升比例。', 3, UTC_TIMESTAMP(6)),
  (5102, 5002, 1, 'SINGLE_CHOICE', '下列哪项是完全竞争市场的特征？', '["大量买者和卖者且产品同质", "单一厂商定价", "存在明显进入壁垒", "少数厂商合谋"]', '"A"', NULL, '完全竞争市场由大量买者和卖者构成，产品同质、信息充分、进入退出自由。', 3, UTC_TIMESTAMP(6)),
  (5103, 5003, 1, 'SINGLE_CHOICE', '国内生产总值（GDP）通常衡量的是？', '["一定时期内一国境内生产的最终产品与服务的市场价值", "一国居民持有的全部资产", "进出口差额", "政府财政支出总额"]', '"A"', NULL, 'GDP 是一国境内在一定时期内生产的最终产品与服务的市场价值总和。', 2, UTC_TIMESTAMP(6)),
  (5104, 5004, 1, 'TRUE_FALSE', '持续的高通胀通常伴随货币购买力下降。', '["正确", "错误"]', 'true', NULL, '通货膨胀意味着整体物价水平上升，单位货币能购买的商品和服务减少，购买力下降。', 2, UTC_TIMESTAMP(6)),
  (5105, 5005, 1, 'SINGLE_CHOICE', '中央银行实施紧缩性货币政策（如加息），最直接的影响是？', '["信贷成本上升、投资需求受到抑制", "政府财政支出增加", "出口税率下调", "工资水平自动上升"]', '"A"', NULL, '加息提高借贷成本，抑制投资和消费，是紧缩性货币政策的主要传导渠道。', 3, UTC_TIMESTAMP(6)),
  (5106, 5006, 1, 'TRUE_FALSE', '阅读经济数据时，需要同时关注数据的统计口径和数据来源，否则可能得出错误结论。', '["正确", "错误"]', 'true', NULL, '口径不同（如名义与实际、季调与非季调）会直接影响可比性，来源不明的数据可信度低。', 2, UTC_TIMESTAMP(6)),
  (5107, 5007, 1, 'SINGLE_CHOICE', '在经济案例分析中，从现象到结论之间的逻辑链条的作用是？', '["让推理可检验、可被质疑", "让结论字数更多", "替代数据证据", "保证结论唯一正确"]', '"A"', NULL, '清晰的逻辑链条把假设、证据与结论连接起来，使推理可检验，是案例分析的核心。', 3, UTC_TIMESTAMP(6)),
  (5108, 5008, 1, 'SINGLE_CHOICE', '在 HTML 中，使用语义化标签（如 article、nav）的主要好处是？', '["改善页面结构与可访问性", "让样式自动生效", "替代 CSS 的全部作用", "加快图片加载"]', '"A"', NULL, '语义化标签表达内容含义，有助于可访问性、搜索引擎理解与团队协作维护。', 2, UTC_TIMESTAMP(6)),
  (5109, 5009, 1, 'SINGLE_CHOICE', '关于 JavaScript 中 const 声明的变量，下列说法正确的是？', '["不能再重新赋值", "其值在任何情况下都可修改", "可以重复声明同名变量", "声明的变量必然提升到全局"]', '"A"', NULL, 'const 绑定不可重新赋值；对象内部属性仍可变，但变量名不能再次绑定。', 2, UTC_TIMESTAMP(6)),
  (5110, 5010, 1, 'SINGLE_CHOICE', '关于 HashMap 的特点，下列说法正确的是？', '["允许一个 null 键和多个 null 值", "键值都必须是基本类型", "保证元素插入顺序", "线程天然安全"]', '"A"', NULL, 'HashMap 允许一个 null 键和多个 null 值；不保证顺序，且非线程安全。', 2, UTC_TIMESTAMP(6)),
  (5111, 5011, 1, 'SINGLE_CHOICE', '在大模型应用中，要求模型输出结构化 JSON 的主要好处是？', '["便于程序可靠地解析与校验", "让回复更口语化", "避免调用任何模型", "提高模型训练速度"]', '"A"', NULL, '结构化输出让结果可被程序稳定解析和校验，是 AI 功能接入业务逻辑的常见方式。', 2, UTC_TIMESTAMP(6));

INSERT IGNORE INTO question_knowledge_point(question_version_id, knowledge_point_id, allocation) VALUES
 (4100, 3001, 1), (4101, 3002, 1), (4102, 3003, 1),
 (4103, 3004, 1), (4103, 3010, 1),
 (4104, 3011, 1),
 (4105, 3005, 1), (4105, 3020, 1),
 (4106, 3021, 1), (4107, 3022, 1),
 (5100, 3010, 1), (5101, 3011, 1), (5102, 3012, 1),
 (5103, 3020, 1), (5104, 3021, 1), (5105, 3022, 1),
 (5106, 3030, 1), (5107, 3031, 1),
 (5108, 1110, 1), (5109, 1111, 1),
 (5110, 1100, 1), (5111, 1120, 1);
