INSERT IGNORE INTO knowledge_point(id, direction_id, parent_id, code, name, level, default_weight, status,
  created_at, created_by, updated_at, updated_by, version)
VALUES
  (1100,101,1000,'JAVA_COLLECTIONS','Java 集合与泛型',2,1.0000,'ACTIVE',UTC_TIMESTAMP(6),0,UTC_TIMESTAMP(6),0,0),
  (1101,101,1000,'JAVA_EXCEPTIONS','异常处理与资源管理',2,1.0000,'ACTIVE',UTC_TIMESTAMP(6),0,UTC_TIMESTAMP(6),0,0),
  (1102,101,1000,'JAVA_CONCURRENCY','并发编程基础',2,1.0000,'ACTIVE',UTC_TIMESTAMP(6),0,UTC_TIMESTAMP(6),0,0),
  (1103,101,1002,'SPRING_DATA','Spring 数据访问与事务',3,1.0000,'ACTIVE',UTC_TIMESTAMP(6),0,UTC_TIMESTAMP(6),0,0),
  (1104,101,1002,'SPRING_SECURITY','Spring Security 与认证授权',3,1.0000,'ACTIVE',UTC_TIMESTAMP(6),0,UTC_TIMESTAMP(6),0,0),
  (1105,101,1003,'BACKEND_TESTING','后端测试与可观测性',4,1.0000,'ACTIVE',UTC_TIMESTAMP(6),0,UTC_TIMESTAMP(6),0,0),
  (1110,102,1010,'HTML_CSS','HTML、CSS 与响应式布局',1,1.0000,'ACTIVE',UTC_TIMESTAMP(6),0,UTC_TIMESTAMP(6),0,0),
  (1111,102,1010,'JAVASCRIPT_TYPESCRIPT','JavaScript 与 TypeScript',1,1.0000,'ACTIVE',UTC_TIMESTAMP(6),0,UTC_TIMESTAMP(6),0,0),
  (1112,102,1010,'VUE_REACTIVITY','Vue 响应式与组合式 API',2,1.0000,'ACTIVE',UTC_TIMESTAMP(6),0,UTC_TIMESTAMP(6),0,0),
  (1113,102,1010,'VUE_ROUTER_STATE','路由与状态管理',2,1.0000,'ACTIVE',UTC_TIMESTAMP(6),0,UTC_TIMESTAMP(6),0,0),
  (1114,102,1010,'FRONTEND_HTTP','前后端通信与错误处理',2,1.0000,'ACTIVE',UTC_TIMESTAMP(6),0,UTC_TIMESTAMP(6),0,0),
  (1115,102,1010,'FRONTEND_TESTING','前端测试与生产构建',3,1.0000,'ACTIVE',UTC_TIMESTAMP(6),0,UTC_TIMESTAMP(6),0,0),
  (1120,103,1020,'PROMPT_ENGINEERING','提示词与结构化输出',2,1.0000,'ACTIVE',UTC_TIMESTAMP(6),0,UTC_TIMESTAMP(6),0,0),
  (1121,103,1021,'EMBEDDINGS_VECTOR_DB','向量嵌入与向量数据库',3,1.0000,'ACTIVE',UTC_TIMESTAMP(6),0,UTC_TIMESTAMP(6),0,0),
  (1122,103,1021,'RAG_EVALUATION','RAG 引用与效果评估',3,1.0000,'ACTIVE',UTC_TIMESTAMP(6),0,UTC_TIMESTAMP(6),0,0),
  (1123,103,1022,'AGENT_PLANNING','Agent 规划与状态管理',3,1.0000,'ACTIVE',UTC_TIMESTAMP(6),0,UTC_TIMESTAMP(6),0,0),
  (1124,103,1022,'AGENT_GUARDRAILS','Agent 守门、权限与审计',3,1.0000,'ACTIVE',UTC_TIMESTAMP(6),0,UTC_TIMESTAMP(6),0,0),
  (1125,103,1022,'AI_EVALUATION','智能应用测试与评估',4,1.0000,'ACTIVE',UTC_TIMESTAMP(6),0,UTC_TIMESTAMP(6),0,0),
  (3110,201,3010,'CONSUMER_CHOICE','消费者选择与效用',2,1.0000,'ACTIVE',UTC_TIMESTAMP(6),0,UTC_TIMESTAMP(6),0,0),
  (3111,201,3010,'PRODUCTION_COST','生产、成本与利润',2,1.0000,'ACTIVE',UTC_TIMESTAMP(6),0,UTC_TIMESTAMP(6),0,0),
  (3112,201,3012,'MARKET_FAILURE','外部性、公共品与市场失灵',3,1.0000,'ACTIVE',UTC_TIMESTAMP(6),0,UTC_TIMESTAMP(6),0,0),
  (3120,202,3020,'AGGREGATE_DEMAND_SUPPLY','总需求与总供给',2,1.0000,'ACTIVE',UTC_TIMESTAMP(6),0,UTC_TIMESTAMP(6),0,0),
  (3121,202,3020,'BUSINESS_CYCLE','经济周期与稳定政策',3,1.0000,'ACTIVE',UTC_TIMESTAMP(6),0,UTC_TIMESTAMP(6),0,0),
  (3122,202,3022,'OPEN_ECONOMY','开放经济与汇率基础',3,1.0000,'ACTIVE',UTC_TIMESTAMP(6),0,UTC_TIMESTAMP(6),0,0),
  (3130,203,3030,'ECON_DATA_VISUALIZATION','经济数据可视化',2,1.0000,'ACTIVE',UTC_TIMESTAMP(6),0,UTC_TIMESTAMP(6),0,0),
  (3131,203,3031,'CAUSAL_REASONING','相关、因果与政策论证',3,1.0000,'ACTIVE',UTC_TIMESTAMP(6),0,UTC_TIMESTAMP(6),0,0);

INSERT IGNORE INTO knowledge_dependency(predecessor_id, successor_id, type)
VALUES
  (1000,1100,'PREREQUISITE'),(1000,1101,'PREREQUISITE'),(1001,1102,'PREREQUISITE'),
  (1002,1103,'PREREQUISITE'),(1003,1104,'PREREQUISITE'),(1103,1105,'PREREQUISITE'),
  (1110,1010,'PREREQUISITE'),(1111,1010,'PREREQUISITE'),(1010,1112,'PREREQUISITE'),
  (1112,1113,'PREREQUISITE'),(1112,1114,'PREREQUISITE'),(1114,1115,'PREREQUISITE'),
  (1020,1120,'PREREQUISITE'),(1021,1121,'PREREQUISITE'),(1121,1122,'PREREQUISITE'),
  (1022,1123,'PREREQUISITE'),(1123,1124,'PREREQUISITE'),(1124,1125,'PREREQUISITE'),
  (3010,3110,'PREREQUISITE'),(3010,3111,'PREREQUISITE'),(3012,3112,'PREREQUISITE'),
  (3020,3120,'PREREQUISITE'),(3120,3121,'PREREQUISITE'),(3022,3122,'PREREQUISITE'),
  (3030,3130,'PREREQUISITE'),(3031,3131,'PREREQUISITE');

INSERT IGNORE INTO knowledge_point_reference(id,knowledge_point_id,title,url,source_type,summary,status,created_at)
VALUES
  (7000,1000,'Dev.java Learn Java','https://dev.java/learn/','OFFICIAL_WEB','Java 官方学习入口，覆盖语言基础、面向对象、集合、异常与并发。','ACTIVE',UTC_TIMESTAMP(6)),
  (7001,1002,'Spring Boot Reference Documentation','https://docs.spring.io/spring-boot/reference/','OFFICIAL_WEB','Spring Boot 官方参考文档。','ACTIVE',UTC_TIMESTAMP(6)),
  (7002,1003,'Spring Web MVC Reference','https://docs.spring.io/spring-framework/reference/web/webmvc.html','OFFICIAL_WEB','Spring Framework 官方 Web MVC 与 REST 文档。','ACTIVE',UTC_TIMESTAMP(6)),
  (7003,1010,'Vue.js Guide','https://vuejs.org/guide/introduction.html','OFFICIAL_WEB','Vue 官方指南。','ACTIVE',UTC_TIMESTAMP(6)),
  (7004,1111,'MDN JavaScript Guide','https://developer.mozilla.org/docs/Web/JavaScript/Guide','OFFICIAL_WEB','MDN JavaScript 学习指南。','ACTIVE',UTC_TIMESTAMP(6)),
  (7005,1113,'Pinia Documentation','https://pinia.vuejs.org/','OFFICIAL_WEB','Pinia 官方状态管理文档。','ACTIVE',UTC_TIMESTAMP(6)),
  (7006,1020,'Hugging Face LLM Course','https://huggingface.co/learn/llm-course/chapter1/1','CURATED_WEB','Hugging Face 的开放大模型课程。','ACTIVE',UTC_TIMESTAMP(6)),
  (7007,1021,'Qdrant Documentation','https://qdrant.tech/documentation/','OFFICIAL_WEB','Qdrant 官方向量检索文档。','ACTIVE',UTC_TIMESTAMP(6)),
  (7008,1022,'LangGraph Documentation','https://docs.langchain.com/oss/python/langgraph/overview','OFFICIAL_WEB','LangGraph 官方 Agent 工作流文档。','ACTIVE',UTC_TIMESTAMP(6)),
  (7009,3000,'OpenStax Principles of Economics 3e','https://openstax.org/books/principles-economics-3e/pages/1-introduction','CURATED_WEB','开放教材，覆盖微观和宏观经济学基础。','ACTIVE',UTC_TIMESTAMP(6)),
  (7010,3010,'OpenStax Principles of Microeconomics 3e','https://openstax.org/books/principles-microeconomics-3e/pages/1-introduction','CURATED_WEB','开放微观经济学教材。','ACTIVE',UTC_TIMESTAMP(6)),
  (7011,3020,'OpenStax Principles of Macroeconomics 3e','https://openstax.org/books/principles-macroeconomics-3e/pages/1-introduction','CURATED_WEB','开放宏观经济学教材。','ACTIVE',UTC_TIMESTAMP(6)),
  (7012,3030,'FRED Economic Data','https://fred.stlouisfed.org/','OFFICIAL_DATA','圣路易斯联储公开经济数据。','ACTIVE',UTC_TIMESTAMP(6));
