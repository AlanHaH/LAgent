INSERT IGNORE INTO learning_direction(id, parent_id, code, name, status, sort_no,
  created_at, created_by, updated_at, updated_by, version)
VALUES
  (200, NULL, 'ECONOMICS', '经济学', 'ACTIVE', 100, UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0),
  (201, 200, 'MICROECONOMICS', '微观经济学', 'ACTIVE', 110, UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0),
  (202, 200, 'MACROECONOMICS', '宏观经济学', 'ACTIVE', 120, UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0),
  (203, 200, 'ECONOMIC_THINKING', '经济学思维与应用', 'ACTIVE', 130, UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0);

INSERT IGNORE INTO knowledge_point(id, direction_id, parent_id, code, name, level, default_weight, status,
  created_at, created_by, updated_at, updated_by, version)
VALUES
  (3000, 200, NULL, 'ECON_BASIC_FRAMEWORK', '经济学基本框架', 1, 1.0000, 'ACTIVE', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0),
  (3001, 200, 3000, 'SCARCITY_AND_CHOICE', '稀缺性与选择', 2, 1.0000, 'ACTIVE', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0),
  (3002, 200, 3000, 'OPPORTUNITY_COST_AND_MARGIN', '机会成本与边际分析', 2, 1.0000, 'ACTIVE', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0),
  (3003, 200, 3000, 'ECONOMIC_MODELS', '经济模型与其他条件不变', 2, 1.0000, 'ACTIVE', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0),
  (3004, 200, 3000, 'SUPPLY_DEMAND_INTRO', '供给需求入门', 2, 1.0000, 'ACTIVE', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0),
  (3005, 200, 3000, 'MACRO_INDICATORS_INTRO', '宏观指标入门', 2, 1.0000, 'ACTIVE', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0),
  (3006, 200, 3000, 'ECON_CASE_SUMMARY', '经济案例阅读与总结', 2, 1.0000, 'ACTIVE', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0),
  (3010, 201, NULL, 'SUPPLY_DEMAND', '供给、需求与均衡', 1, 1.0000, 'ACTIVE', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0),
  (3011, 201, 3010, 'ELASTICITY', '弹性与消费者反应', 2, 1.0000, 'ACTIVE', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0),
  (3012, 201, 3010, 'MARKET_STRUCTURES', '市场结构与效率', 2, 1.0000, 'ACTIVE', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0),
  (3020, 202, NULL, 'GDP_AND_GROWTH', 'GDP 与经济增长', 1, 1.0000, 'ACTIVE', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0),
  (3021, 202, 3020, 'INFLATION_UNEMPLOYMENT', '通货膨胀与失业', 2, 1.0000, 'ACTIVE', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0),
  (3022, 202, 3020, 'FISCAL_MONETARY_POLICY', '财政政策与货币政策', 2, 1.0000, 'ACTIVE', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0),
  (3030, 203, NULL, 'ECON_DATA_READING', '经济数据阅读', 1, 1.0000, 'ACTIVE', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0),
  (3031, 203, 3030, 'ECON_CASE_WRITING', '经济案例分析与书面表达', 2, 1.0000, 'ACTIVE', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0);

INSERT IGNORE INTO knowledge_dependency(predecessor_id, successor_id, type)
VALUES
  (3000, 3001, 'PREREQUISITE'),
  (3001, 3002, 'PREREQUISITE'),
  (3002, 3003, 'PREREQUISITE'),
  (3003, 3004, 'PREREQUISITE'),
  (3004, 3005, 'PREREQUISITE'),
  (3005, 3006, 'PREREQUISITE'),
  (3002, 3010, 'PREREQUISITE'),
  (3010, 3011, 'PREREQUISITE'),
  (3010, 3012, 'PREREQUISITE'),
  (3002, 3020, 'PREREQUISITE'),
  (3020, 3021, 'PREREQUISITE'),
  (3021, 3022, 'PREREQUISITE'),
  (3030, 3031, 'PREREQUISITE');

INSERT IGNORE INTO question(id, public_id, owner_user_id, visibility, current_version_no, status, source_type,
  created_at, created_by, updated_at, updated_by, version)
VALUES
  (4000, 'seed-econ-scarcity-1', NULL, 'PUBLIC', 1, 'PUBLISHED', 'CURATED', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0),
  (4001, 'seed-econ-margin-1', NULL, 'PUBLIC', 1, 'PUBLISHED', 'CURATED', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0),
  (4002, 'seed-econ-model-1', NULL, 'PUBLIC', 1, 'PUBLISHED', 'CURATED', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0),
  (4003, 'seed-micro-demand-1', NULL, 'PUBLIC', 1, 'PUBLISHED', 'CURATED', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0),
  (4004, 'seed-micro-elasticity-1', NULL, 'PUBLIC', 1, 'PUBLISHED', 'CURATED', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0),
  (4005, 'seed-macro-gdp-1', NULL, 'PUBLIC', 1, 'PUBLISHED', 'CURATED', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0),
  (4006, 'seed-macro-inflation-1', NULL, 'PUBLIC', 1, 'PUBLISHED', 'CURATED', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0),
  (4007, 'seed-macro-policy-1', NULL, 'PUBLIC', 1, 'PUBLISHED', 'CURATED', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0);

INSERT IGNORE INTO question_version(id, question_id, version_no, type, stem, options_json, answer_json, rubric_json, analysis, difficulty, created_at)
VALUES
  (4100, 4000, 1, 'SINGLE_CHOICE', '经济学研究稀缺性的核心原因是什么？', '["资源有限而人的需求多样","商品价格永远上涨","企业只追求利润","政府可以决定所有价格"]', '"A"', NULL, '稀缺性来自有限资源与多样需求之间的张力，因此必须做选择。', 1, UTC_TIMESTAMP(6)),
  (4101, 4001, 1, 'TRUE_FALSE', '边际分析关注的是“多做一单位”带来的额外收益和额外成本。', '["正确","错误"]', 'true', NULL, '边际分析比较增量收益与增量成本，是经济决策的基础工具。', 2, UTC_TIMESTAMP(6)),
  (4102, 4002, 1, 'SINGLE_CHOICE', '“其他条件不变”在经济模型中的作用是什么？', '["隔离主要变量之间的关系","证明现实永远不变","取消数据验证","让结论不需要假设"]', '"A"', NULL, '该假设用于简化复杂现实，观察关键变量之间的关系。', 2, UTC_TIMESTAMP(6)),
  (4103, 4003, 1, 'SINGLE_CHOICE', '在其他条件不变时，商品价格上升通常会导致需求量如何变化？', '["下降","上升","不可能变化","一定变为零"]', '"A"', NULL, '需求定律说明价格上升通常会减少需求量。', 2, UTC_TIMESTAMP(6)),
  (4104, 4004, 1, 'SINGLE_CHOICE', '价格弹性较高的商品，价格小幅上升通常意味着什么？', '["需求量可能明显下降","需求量完全不变","供给量必然为零","消费者收入一定增加"]', '"A"', NULL, '弹性较高表示需求量对价格变化更敏感。', 3, UTC_TIMESTAMP(6)),
  (4105, 4005, 1, 'SINGLE_CHOICE', 'GDP 通常衡量的是一个经济体在一定时期内的什么？', '["最终产品和服务的市场价值","所有二手商品交易总额","家庭全部资产存量","政府债务总额"]', '"A"', NULL, 'GDP 衡量一定时期内最终产品和服务的市场价值。', 2, UTC_TIMESTAMP(6)),
  (4106, 4006, 1, 'TRUE_FALSE', '通货膨胀表示总体价格水平持续上升，而不是某一种商品临时涨价。', '["正确","错误"]', 'true', NULL, '通货膨胀关注总体价格水平的持续变化。', 2, UTC_TIMESTAMP(6)),
  (4107, 4007, 1, 'SINGLE_CHOICE', '降低基准利率通常更接近哪类宏观政策工具？', '["货币政策","产业政策","税收征管","贸易配额"]', '"A"', NULL, '利率调节属于货币政策常见工具。', 3, UTC_TIMESTAMP(6));

INSERT IGNORE INTO question_knowledge_point(question_version_id, knowledge_point_id, allocation)
VALUES
  (4100, 3001, 1.0000),
  (4101, 3002, 1.0000),
  (4102, 3003, 1.0000),
  (4103, 3004, 1.0000),
  (4105, 3005, 1.0000),
  (4103, 3010, 1.0000),
  (4104, 3011, 1.0000),
  (4105, 3020, 1.0000),
  (4106, 3021, 1.0000),
  (4107, 3022, 1.0000);
