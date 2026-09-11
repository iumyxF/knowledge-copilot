# Knowledge Copilot V1 后端

Java 21 单体后端，通过 Knife4j 完成知识库、文档、问答和评测操作。没有前端业务代码、登录认证、聊天记忆或文档版本发布功能。

## 1. 运行环境

| 组件 | 本次锁定版本 |
| --- | --- |
| Java | 21 |
| Spring Boot | 3.5.13 |
| MyBatis-Plus | 3.5.14 |
| LangChain4j BOM | 1.11.0（Integration 由 BOM 选择 1.11.0-beta19） |
| Knife4j / springdoc | 4.5.0 / 2.8.13 |
| commons-compress | 1.27.1，与 POI 5.4.1 对齐 |
| MySQL | 8.0，已在 8.0.45 验证 |
| Elasticsearch | 8.18.8 |
| 构建工具 | Maven 3.9+ |

Knife4j 4.5.0 的旧增强定制器调用的 springdoc 方法签名与 2.8 不兼容，因此 `knife4j.enable=false`。这只关闭服务端增强定制，`/doc.html` 的文档展示和在线调试仍可用。不要将 springdoc 降到不兼容 Boot 3.5 的版本，也不要直接开启旧增强定制器。

应用使用 MySQL、Elasticsearch 和本地文件；不需要 Redis、MQ 或 Python 服务。数据库连接和迁移是启动前提，模型和 ES 不在启动时发出网络调用。

## 2. 启动

先在自己的 MySQL 中创建空数据库：

```sql
CREATE DATABASE knowledge_copilot CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

在项目根目录用 PowerShell 配置环境变量并构建：

```powershell
$env:MYSQL_URL='jdbc:mysql://localhost:3306/knowledge_copilot?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai'
$env:MYSQL_USERNAME='root'
$env:MYSQL_PASSWORD='<数据库密码>'
$env:ELASTICSEARCH_URL='http://localhost:9200'
$env:STORAGE_ROOT='E:/knowledge-copilot-data/documents'
mvn -f server/pom.xml verify
java -jar server/target/knowledge-copilot-1.0.0-SNAPSHOT.jar
```

也可以进入 server 目录运行 `mvn spring-boot:run`。相对 storage-root 以启动时工作目录为准，建议使用绝对路径。

打开 [Knife4j](http://localhost:8080/doc.html)。接口按知识库、文档、切片、问答、评测分组，[OpenAPI JSON](http://localhost:8080/v3/api-docs) 可导出。

默认禁用模型时，知识库和评测集管理可用；上传入库、问答、触发评测返回 503 / MODEL_NOT_CONFIGURED。系统不会用假向量或假答案代替真实模型。

### 模型配置

对话模型和 Embedding 模型分别配置，可使用不同的 OpenAI-compatible 服务：

```powershell
$env:CHAT_ENABLED='true'
$env:CHAT_BASE_URL='<对话服务的兼容API地址，例如以/v1结尾>'
$env:CHAT_API_KEY='<密钥>'
$env:CHAT_MODEL='<模型名称>'

$env:EMBEDDING_ENABLED='true'
$env:EMBEDDING_BASE_URL='<Embedding服务的兼容API地址>'
$env:EMBEDDING_API_KEY='<密钥>'
$env:EMBEDDING_MODEL='<Embedding模型名称>'
$env:EMBEDDING_DIMENSION='<模型实际输出维度>'

java -jar server/target/knowledge-copilot-1.0.0-SNAPSHOT.jar
```

EMBEDDING_DIMENSION 用于校验实际输出和 ES mapping，不会强制向所有兼容服务发送 `dimensions` 参数。请配置所选模型默认实际输出的维度。其他协议需要使用对应的 LangChain4j Integration。

密钥不能提交到仓库；本地配置文件已列入 .gitignore。ES 支持 ELASTICSEARCH_USERNAME / ELASTICSEARCH_PASSWORD。默认索引名为 knowledge-copilot-v1，可以通过 ELASTICSEARCH_INDEX 修改。

首次入库由程序创建专用索引，并在 mapping 的 _meta 保存模型配置指纹。模型名称、地址或维度变化均不能继续混用旧索引；使用新的索引与知识库并重新上传。旧知识库不应继续参与该新索引的验收。不要把已有的非本项目索引配置给应用。

### 默认参数

| 参数 | 默认值 |
| --- | --- |
| 单文件大小 | 20 MB |
| 文件类型 | PDF、DOC、DOCX、TXT、MD |
| TXT / MD 编码 | UTF-8 |
| 切片 / 重叠 | 500 / 50 token |
| token 估算器 | gpt-4o-mini 编码估算，未承诺与用户模型分词完全一致 |
| 问答 topK / 资料预算 | 5 个切片 / 3000 token |
| min-score | 0.0，建立实际基线后再调整 |
| 评测候选窗口 | 100 个切片 |
| 入库线程 / 队列 | 2 / 20 |
| 评测线程 / 队列 | 1 / 5 |
| Embedding 批大小 | 16 |
| 模型超时 / 重试 | 60 秒 / 1 次 |

其他参数见 application.yml 的 copilot 配置。调整上传上限时同步设置 MAX_FILE_SIZE、MAX_FILE_BYTES 和 SPRING_SERVLET_MULTIPART_MAX_REQUEST_SIZE，后者应略大于单文件限制。

## 3. 用 Knife4j 验证上传与问答

1. POST /api/v1/knowledge-bases，提交名称和描述，保存返回的字符串 id。
2. POST /api/v1/knowledge-bases/{id}/documents，选择 file。返回 202 只表示受理。
3. GET /api/v1/documents/{id}/status，轮询到 AVAILABLE；失败时查看 errorCode/errorMessage。
4. GET /api/v1/documents/{id}/chunks，核验切片正文。
5. POST /api/v1/chat，提交：

```json
{
  "knowledgeBaseId": "替换为知识库ID",
  "question": "设备购买后多久可以申请退货？"
}
```

响应 data 包含 answer、refused、citations。引用包含 documentId、documentName、chunkId、content，以及可空的 page/section。引用代表本次生成使用的上下文来源，不是逐句事实验证结果。

无候选切片时直接返回“当前知识库没有足够的信息回答该问题。”，不调用对话模型。有候选但内容不相关时，靠 Prompt 要求拒答，仍需人工核验；V1 没有语义充分性 Judge。

## 4. 失败重试与删除

正常状态：UPLOADED → PARSING → PARSED → INDEXING → AVAILABLE。

- PARSE_FAILED：解析失败或无有效文本，不支持 OCR。
- INDEX_FAILED：切片、Embedding 或 ES 写入/可见性验证失败。
- PROCESS_FAILED：队列拒绝或应用重启中断。
- 上述三类状态可 POST /api/v1/documents/{id}/retry。重试增加 attemptNo；重复领取返回 409。
- 全部索引写入且可见性验证完成后才 AVAILABLE；检索前根据数据库有效文档与批次添加过滤。
- DELETE /api/v1/documents/{id} 先停止文档参与检索，再清理 ES、文件和切片；处理中返回 409。
- DELETE_FAILED 可再次 DELETE，已完成删除幂等成功。失败删除仍然不可检索。
- 单实例启动先将中断任务转为失败，再放行业务请求。没有跨实例任务调度能力。

文件持久化与 MySQL/ES 不属于同一个事务。常规失败进行补偿，崩溃残留可以在应用停止后检查：将存储目录文件与 knowledge_document.storage_path 对照，只清理没有任何业务记录关联的 .upload 或孤立文件。不要直接清空存储目录或索引。

## 5. 导入中文基线

samples/documents 有五份自编资料：

| documentKey | 文件 |
| --- | --- |
| product | product.pdf |
| policy | policy.md |
| faq | faq.txt |
| rules | rules.docx |
| operations | operations.doc |

按顺序上传或任意顺序上传均可；每份记录真实 documentId，等待全部 AVAILABLE。

打开 samples/evaluation/baseline.json，将 knowledgeBaseId 和 documentMapping 中的占位 ID 替换为实际值，在 Knife4j 调用 POST /api/v1/evaluation/datasets/import。文件包含 20 条用例：12 条单文档、4 条多文档、4 条无答案。

也可以用 samples/prepare-import.ps1 生成已填好 ID 的请求文件：

```powershell
./server/samples/prepare-import.ps1 -KnowledgeBaseId '实际ID' -ProductId '实际ID' -PolicyId '实际ID' -FaqId '实际ID' -RulesId '实际ID' -OperationsId '实际ID'
```

该脚本只生成本地请求 JSON，不发送请求。将输出文件内容粘贴到 Knife4j。

1. POST /api/v1/evaluation/datasets/{id}/runs，保存 runId。
2. GET /api/v1/evaluation/runs/{id}，查询状态和指标。
3. GET /api/v1/evaluation/runs/{id}/results，查询逐条快照。
4. 对 MANUAL_REVIEW 用例另行调用 Chat API，记录实际答案及是否合理拒答。

运行使用同一检索服务和阈值，但候选窗口为 100 个切片。按首次出现位置去重文档，取前 10 个文档计算 Recall@5、Recall@10、MRR@10、Hit Rate@10。K 是文档数，指标不能替代线上 5 个上下文切片的质量评价。

无答案用例不进入正向分母；执行错误不当作普通未命中。有错误或运行期间语料变化的 Run 标记 FAILED，不生成有效总分。重跑新建 runId，历史快照保留；数据集逻辑删除不会删除旧快照。

source.json 保存自编资料原文，generate-office.ps1 可在安装了 Word 的 Windows 环境重新生成二进制样本，运行后需重新验证解析。应用本身不依赖 Word。

## 6. 测试

```powershell
mvn -f server/pom.xml test
mvn -f server/pom.xml verify
```

默认执行 H2 MySQL 兼容模式下的业务测试、真实文件解析、固定模型替身、指标及 Knife4j MockMvc 测试，不调用付费模型。它们不能代替真实 MySQL 和 ES 的兼容性验证。

InfrastructureTest 使用 Testcontainers 启动隔离的 MySQL/ES；无 Docker 时两项测试明确跳过。若已有仅用于测试的 ES，可执行：

```powershell
mvn -f server/pom.xml '-Dtest=LocalElasticsearchTest' '-Dtest.elasticsearch.url=http://localhost:9200' test
```

该测试使用随机命名索引和固定向量，不调用模型，结束时仅删除自己的测试索引。

EndToEndTest 使用真实 MySQL/ES 和本机临时 HTTP 模型协议替身，覆盖五份资料上传、AiServices 问答、引用、空库拒答、20 条评测和删除。它不衡量真实模型效果：

```powershell
mvn -f server/pom.xml '-Dtest=EndToEndTest' '-Dtest.e2e.mysql.url=jdbc:mysql://localhost:3306/copilot_e2e' '-Dtest.e2e.mysql.username=test_user' '-Dtest.elasticsearch.url=http://localhost:9200' test
```

预先创建独立的 copilot_e2e 数据库；测试会生成自己命名的知识库和临时索引。测试数据库密码使用 test.e2e.mysql.password 对应的受控测试配置提供。

针对真实 MySQL 验证业务测试时，必须提供可清空的独立测试数据库，相关测试会清理七张业务表：

```powershell
mvn -f server/pom.xml '-Dtest=ApiSmokeTest,PipelineLifecycleTest,EvaluationWorkflowTest' '-Dspring.datasource.url=jdbc:mysql://localhost:3306/copilot_test' '-Dspring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver' '-Dspring.datasource.username=test_user' test
```

密码通过受控测试配置提供，不应在生产库运行测试。Windows 的 Maven .cmd 对命令行 URL 中的 & 容易产生解释问题，因此测试示例 URL 不含额外查询参数，实际启动优先使用环境变量。

## 7. V1 验收边界

真实模型未配置时，不能宣称问答质量或真实基线已通过。当前代码、数据库/向量服务验证与真实模型验收分开记录于 doc/V1编码任务清单.md。

模型配置后需完成：

- 五份资料真实 Embedding 入库并进入 AVAILABLE。
- 实际 Chat API 回答、引用及跨知识库隔离核验。
- 20 条基线运行，记录真实配置、指标和人工拒答结论。
- 记录模型限流/超时下的重试结果，不以模型替身测试结果代替真实运行。

无需开发前端；所有验收通过 Knife4j 进行。
