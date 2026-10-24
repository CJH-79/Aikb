# Aikb · 知识库问答助手

上传 PDF / Markdown / TXT，自动切片入库，用 **BM25 + 向量双路召回** 找到相关片段，再让大模型**只依据这些片段**作答，并把每句话的出处（文件 + 页码）标出来。前端流式逐字显示，支持多轮追问。

前后端分离：后端 Spring Boot 3.5 + Spring AI，前端 Vue 3 + Vite。

---

## 它解决什么问题

公司内部的制度、手册、技术文档散落在各个 PDF 里，找一句话要翻半天。直接丢给大模型也不行——模型没见过你的内部资料，问它只会得到一本正经的胡编。

这个项目做的事：**先把资料切碎存起来，问到什么就翻出最相关的几段，再让模型拿着这几段作答**。模型看不到资料以外的内容，所以答案可核对。

## 功能

- **文档解析**：PDF 逐页抽取（PDFBox 3，保留页码）、Markdown / TXT 直读，单文件 30MB
- **章节级切片**：优先在章节标题处断开，再按句子滑窗补足，相邻块保留 60 字重叠
- **混合检索**：中文 bigram 分词的 BM25 + embedding 向量余弦，用 RRF（Reciprocal Rank Fusion）融合排序
- **流式输出**：SSE 逐字推送，`meta` 事件先给出引用来源，`delta` 事件推正文
- **多轮对话**：按 `conversationId` 保留最近 N 条消息，追问时自动改写检索词
- **引用溯源**：每条答案带 `[1] [2]` 编号，可对回具体文件和页码
- **三层防编造**：提示词约束 + 检索无命中不调模型 + 编号溯源
- **双重降级**：模型不可用退回纯检索；embedding 不可用退回纯 BM25。任一环节缺失，系统都还能用

## 技术栈

| 层 | 选型 |
|---|---|
| 后端 | Spring Boot 3.5.1、Spring AI 1.0.0、MyBatis-Plus 3.5.9、PDFBox 3.0.3 |
| 数据库 | MySQL 5.7+（向量以 JSON 数组存 `MEDIUMTEXT`，不引向量库） |
| 前端 | Vue 3.5、Vite 5、Element Plus、Axios |
| 模型 | OpenAI 兼容协议（chat 与 embedding 均可用 OpenAI / 通义 / 智谱 / 硅基流动 / Ollama） |

## 快速开始

### 1. 建库

新库直接执行：

```bash
mysql -uroot -p < db/schema.sql
```

老库升级（只加向量相关的两列，可重复执行）：

```bash
mysql -uroot -p --default-character-set=utf8mb4 < db/migration_v2_embedding.sql
```

### 2. 启动后端

```bash
cd backend
mvn spring-boot:run
```

默认端口 `8081`，监听 `0.0.0.0`（同一局域网内手机可访问）。

数据库账号密码可用环境变量覆盖：

```bash
KB_DB_USER=root KB_DB_PASSWORD=yourpassword mvn spring-boot:run
```

> **Windows 用户注意**：如果 shell 里有 `SERVER__PORT` 之类的环境变量，Spring Boot 的宽松绑定会把它当成 `server.port`，覆盖 yaml 里的配置。命令行启动时用 `env -u SERVER__PORT mvn spring-boot:run`。在 IDEA 里跑不受影响。

### 3. 启动前端

```bash
cd frontend
npm install
npm run dev
```

打开 `http://localhost:5173`。Vite 已配好 `/api` 代理到 `8081`。

### 4. 接入模型（可选，但强烈建议接）

不配也能用——纯检索模式，直接给你原文摘录。

**对话模型**：

```bash
# OpenAI
OPENAI_API_KEY=sk-xxx KB_AI_ENABLED=true mvn spring-boot:run

# DeepSeek（OpenAI 兼容）
OPENAI_API_KEY=sk-xxx \
OPENAI_BASE_URL=https://api.deepseek.com \
OPENAI_MODEL=deepseek-chat \
KB_AI_ENABLED=true mvn spring-boot:run
```

**向量模型**（开启混合检索）：

```bash
OPENAI_EMBEDDING_MODEL=text-embedding-3-small \
KB_EMBEDDING_ENABLED=true mvn spring-boot:run
```

各家的 `base-url` 与模型名：

| 服务 | base-url | embedding 模型 |
|---|---|---|
| OpenAI | `https://api.openai.com` | `text-embedding-3-small` |
| 阿里百炼 | `https://dashscope.aliyuncs.com/compatible-mode` | `text-embedding-v3` |
| 智谱 | `https://open.bigmodel.cn/api/paas/v4` | `embedding-3` |
| 硅基流动 | `https://api.siliconflow.cn` | `BAAI/bge-m3` |
| 本地 Ollama | `http://localhost:11434/v1` | `nomic-embed-text` |

### 5. 没有 Key 也想验证完整链路

仓库里带了一个本地 mock 服务，实现了 `/v1/embeddings` 和 `/v1/chat/completions`（含 SSE 流式）：

```bash
# 终端 A
python tools/mock-openai-server.py --port 8090

# 终端 B
OPENAI_BASE_URL=http://127.0.0.1:8090 \
OPENAI_API_KEY=mock \
OPENAI_MODEL=mock-chat \
OPENAI_EMBEDDING_MODEL=mock-embed \
KB_AI_ENABLED=true KB_EMBEDDING_ENABLED=true \
mvn spring-boot:run
```

> **它只用于验证协议链路，不是可用的模型。** 它的向量是字符 bigram 哈希出来的，
> 只反映字面重合度，没有语义能力——同义改写召回不了。
> 换句话说：它能证明「管线跑得通」，不能证明「检索效果好」。
> 真实语义召回必须配真的 embedding 模型。

## 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/documents` | 上传文档（multipart，字段名 `file`） |
| GET | `/api/documents?page=1&size=10` | 文档列表 |
| DELETE | `/api/documents/{id}` | 删除文档及其切片 |
| GET | `/api/documents/stats` | 文档数 / 切片数 / 索引大小 / 向量是否启用 / 缺失向量的切片数 |
| GET | `/api/search?q=关键词&topK=5` | 只检索不生成，结果带 `recall` 字段标出召回来源 |
| POST | `/api/chat` | 同步问答，body：`{"question":"...","topK":5,"conversationId":"可选"}` |
| POST | `/api/chat/stream` | **SSE 流式问答**，body 同上 |
| POST | `/api/chat/reset?conversationId=xxx` | 清空该会话的多轮记忆 |

`/api/chat` 返回的 `mode` 有三种：`llm`（模型生成）、`retrieval`（未配模型或模型失败，返回原文）、`empty`（检索无命中）。

### SSE 协议

```
event: meta
data: {"conversationId":"c-xxx","mode":"llm","citations":[{...}],"query":"实际检索词","elapsedMs":12}

event: delta
data: {"t":"年假"}

event: delta
data: {"t":" 5 天"}

event: done
data: {"mode":"llm","elapsedMs":3200}
```

`meta` 先到，前端可以立刻把引用来源渲染出来，再等正文逐字出现。

> 为什么用 POST 而不是 `EventSource`：EventSource 只支持 GET、不能带请求体，而我们既要把问题放 body（避免超长 URL 编码），又要传 `conversationId`。所以前端用 `fetch` + `ReadableStream` 自己按 SSE 帧切分，协议本身仍是标准 SSE。

## 关键设计与取舍

### 为什么要向量检索，又为什么不引向量库

只用 BM25 时，用户问法和原文用词不一致就召回不到（「几天」对不上「5 天」）。向量能处理同义改写，所以加了一路。

但**没有引入 Milvus / Qdrant / ES**：切片全量向量的大小是 `切片数 × 维度 × 4 字节`，一万切片、512 维也就 20MB，全量暴力算点积在毫秒级。引入向量库要多部署一套服务、还要处理数据同步和一致性问题，对这个体量是负收益。向量以 JSON 数组存在 `kb_chunk.embedding` 里，和切片同生共死，省掉一次关联查询和一套一致性维护。

真需要 ANN 索引的规模是百万级，那时候再换，检索层接口不用动。

### 为什么用 RRF 融合，而不是加权求和

BM25 分是「归一化后的对数概率」，余弦是「夹角余弦」，量纲完全不同。加权求和得先做分数对齐，而各批数据的分布不同，对齐系数没法固定。RRF 只用排名不用分数：`score(d) = Σ wᵢ / (k + rankᵢ(d))`，天然免疫量纲问题，实现就一行累加。

**RRF 的默认假设是两路质量相当，这个假设不一定成立。** 所以留了两个旋钮：`kb.search.vector-weight` 和 `kb.search.vector-min-similarity`。一旦发现无关切片被向量那一路顶到第一，就把权重调到 0.3 左右，让向量只起补充作用。

### 为什么在章节标题处强制断开切片

纯定长滑窗会把「考勤制度」和「年假制度」混进同一个切片。用户问年假，捞出来的块前半截讲打卡、后半截讲年假，页码溯源也不准。按标题切之后每个切片语义自洽。

### 为什么参考资料放在 system 消息里

因为多轮记忆记录的是「请求里的 user 消息」。如果把「【参考资料】……【问题】……」拼成一条 user 消息，存进记忆的就是这一整坨，会出两个问题：

1. 记忆窗口每条都膨胀到上千字，几轮就把上下文塞满；
2. 下一轮做检索改写时，取到的「上一轮问题」其实是上一轮的整段 prompt。

把资料移到 system、user 只留用户原话，两个问题一起消失。**这是我在这个项目里踩到的最隐蔽的一个坑。**

### 为什么 embedding 放在事务提交之后做

embedding 是一次 HTTP 调用，本地模型几百毫秒，云端接口遇到大文档更慢。放进数据库事务里会让 undo log 和行锁一直挂着，并发上传互相阻塞。所以入库只做本地动作，向量化在 `afterCommit` 里补，失败则该文档只参与 BM25，不影响入库成功。

### 为什么检索没命中就不调模型

把空上下文发给模型，它一定会自己编。宁可回一句「知识库里没有」，也不要一个看起来像模像样的错误答案。

### 为什么做降级

模型接口会超时、欠费、限流；embedding 服务也可能连不上。这些都不该让问答接口整个 500 —— 检索能力本身还在，退回展示原文摘录，用户至少能拿到资料。

## 怎么验证的

代码写完必须自己跑过才有意义。实际跑过的项目：

| 验证项 | 结果 |
|---|---|
| 示例 md（3423 字节）切片 | 5 个章节级切片，每块都从标题开始 |
| 向量化 | 5 条切片全部落库，256 维，`embeddingMissing = 0` |
| 混合检索 | 输出带 `bm25` / `vector` / `bm25+vector` 三种召回标记，证明融合生效 |
| 「年假有几天」「报销上限」「生产环境何时变更」「加班怎么调休」 | 命中正确章节 |
| 「今天中午吃什么」 | 返回空，不硬凑 |
| SSE 流式 | `meta` 先到（含引用），随后 `delta` 帧连续推送，间隔约 200ms |
| 多轮记忆 | 第 2 轮发给模型的消息序列为 `[user, assistant, system, user]`，历史轮次 = 2 |
| 追问改写 | 「年假有几天」→「那病假呢」，检索词自动补全为「年假有几天 那病假呢」 |
| 模型/embedding 不可用 | 自动降级，接口仍返回 200 |

**没验证的**：真实模型的答案质量。没有 API Key，向量那一侧的语义召回能力也没有用真模型校准过阈值。

## 踩过的坑

1. **user 消息污染多轮记忆**（见上文「为什么参考资料放在 system 消息里」）——现象是第 2 轮检索词变成了整段 prompt。

2. **批量 embedding 用 chunked 编码**。本地 mock 服务最初只按 `Content-Length` 读 body，结果 Spring AI 发来的 5 条批量请求被当成空请求，只返回 1 条向量，日志报「向量数量与切片数量不一致（1 vs 5）」。

3. **HTTP 长连接池 + 服务重启**。mock 服务重启后，后端还持有着已失效的 keep-alive 连接，下一次请求直接 `ConnectException`。这也顺带验证了降级逻辑是对的：向量化失败只记一条 warn，文档正常入库。

4. **PDFBox 的 `Loader` 是 3.x API**。2.0.x 只有 `PDDocument.load()`，版本写错会编译失败。

5. **MyBatis-Plus 3.5.9 把 jsqlparser 拆成独立依赖**。不加 `mybatis-plus-jsqlparser`，分页插件静默不生效。

## 已知局限

- **BM25 只认字面**：「几天」对不上「5 天」，只能靠「年假」勉强召回。
- **向量召回质量取决于 embedding 模型**。阈值必须按模型校准：bge 系列中文模型无关文本的余弦也能到 0.4~0.5，阈值要设到 0.55 左右，否则噪声全放进来；OpenAI `text-embedding-3` 无关文本约 0.1~0.3，0.3~0.35 就够。**换模型一定要重新校准**。
- **检索改写是规则式的**，不是模型改写。判断依据是「短问题 + 含指代词 / 以『那』『还』开头」。语义跳跃的追问识别不出来（比如上一轮聊报销，这一轮突然问「审批人呢」）。要更准就得上模型改写，那是另一笔延迟和成本。
- **多轮记忆存在进程内存里**，重启即清空，多实例之间也不共享。要持久化得换成 Redis 或数据库实现 `ChatMemoryRepository`。
- **语料外的问题可能硬凑**：问「公司股票代码是多少」，如果文档里有「公司」两个字，仍可能召回无关内容。目前靠提示词让模型拒答兜底。
- **扫描版 PDF 不支持**：没有文字层的 PDF 会被判为解析失败，需要先做 OCR。
- **单机内存索引**：进程重启要重建（启动时自动做），多实例部署时各实例索引不一致。

## 目录结构

```
Aikb/
├── backend/src/main/java/cn/gok/kb/
│   ├── parser/      文档解析（PDFBox 按页抽取、txt/md 直读）
│   ├── split/       切片（章节优先 + 句子滑窗）
│   ├── embedding/   embedding 统一入口（可插拔，未配置则整体降级）
│   ├── search/      BM25 索引、向量索引、分词、混合检索（RRF）
│   ├── chat/        会话记忆、检索改写、提示词拼装、SSE 流式、降级
│   ├── document/    文档管理与上传接口
│   └── common/      统一响应、异常处理
├── frontend/src/
│   ├── components/  DocPanel（文档）、ChatPanel（流式问答）
│   └── api/         接口封装（含 SSE 手动解析）
├── db/              schema.sql、migration_v2_embedding.sql
├── tools/           mock-openai-server.py（本地链路验证用，非真模型）
└── samples/         示例文档，可直接用来试效果
```

## 示例

`samples/员工手册示例.md` 是一份虚构员工手册，上传后可以问：

- 年假有几天？
- 出差住宿报销上限是多少？
- 生产环境什么时候能变更？
- 加班怎么调休？

然后追问一句「那病假呢」，可以观察多轮改写是否生效——前端会显示实际使用的检索词。

问知识库里没有的内容（比如「今天中午吃什么」），应该得到「没有检索到相关内容」。
