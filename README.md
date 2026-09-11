
# Knowledge Copilot

基于 Java 21、Spring Boot、LangChain4j、MySQL 和 Elasticsearch 的知识库助手。

V1 已实现后端知识库管理、文档异步入库、单轮问答与引用、文档级检索评测。使用 Knife4j 验证接口，web 仅保留前端目录占位。

- [后端启动与接口验收](server/README.md)
- [V1 开发方案](doc/V1版本开发方案.md)
- [编码任务及验证记录](doc/V1编码任务清单.md)
- [示例文档和评测集](server/samples/README.md)

真实 Chat 和 Embedding 模型由使用者配置；固定响应测试不代表真实模型效果。
