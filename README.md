# Aikb · AI 知识库助手

上传 PDF / Markdown / TXT，自动切片入库、关键词检索，再让大模型**只依据检索到的片段**作答，并把答案里每句话的出处（文件 + 页码）标出来。

前后端分离：后端 Spring Boot 3.5 + Spring AI，前端 Vue 3 + Vite。

---

## 它解决什么问题

公司内部的制度、手册、技术文档散落在各个 PDF 里，找一句话要翻半天。直接丢给大模型也不行——模型没见过你的内部资料，问它只会得到一本正经的胡编。

这个项目做的事：**先把资料切碎存起来，问到什么就翻出最相关的几段，再让模型拿着这几段作答**。模型看不到资料以外的内容，所以答案可核对。

## 功能

- 文档上传：PDF 逐页解析（PDFBox 3）、Markdown / TXT 直读，单文件 30MB
- 智能切片：先按章节标题切，再按句子滑窗，相邻块保留 60 字重叠
- BM25 检索：中英文混排分词（中文二元组），内存索引，万级切片毫秒级响应
- 模型问答：检索结果拼成上下文交给模型，提示词约束「只依据资料作答」
- 引用溯源：每条答案都带 `[1] [2]` 编号，可对回具体文件和页码
- 无答案兜底：检索没命中就不调模型，直接回「查不到」，不给它编的机会
- 模型故障降级：模型超时 / 欠费 / Key 失效时自动退回纯检索，接口不报错

## 技术栈

| 层 | 选型 |
|---|---|
| 后端 | Spring Boot 3.5.1、Spring AI 1.0.0、MyBatis-Plus 3.5.9、PDFBox 3.0.3 |
| 数据库 | MySQL 5.7+ |
| 前端 | Vue 3.5、Vite 5、Element Plus、Axios |
| 模型 | OpenAI 兼容协议（OpenAI / DeepSeek / 通义 / 本地 Ollama 均可） |

## 快速开始

### 1. 建库

```bash
mysql -uroot -p < db/schema.sql
```

### 2. 启动后端

```bash
cd backend
mvn spring-boot:run
```

默认端口 `8081`。数据库账号密码在 `backend/src/main/resources/application.yml`，按自己环境改，或用环境变量覆盖：

```bash
KB_DB_USER=root KB_DB_PASSWORD=yourpassword mvn spring-boot:run
```

### 3. 启动前端

```bash
cd frontend
npm install
npm run dev
```

打开 `http://localhost:5173`。Vite 已配好 `/api` 代理到 `8081`，不需要额外设置跨域。

### 4. 接入模型（可选）

不配也能用——此时是纯检索模式，会直接把最相关的原文摘录给你看。

```bash
# OpenAI
OPENAI_API_KEY=sk-xxx KB_AI_ENABLED=true mvn spring-boot:run

# DeepSeek（走 OpenAI 兼容协议）
OPENAI_API_KEY=sk-xxx \
OPENAI_BASE_URL=https://api.deepseek.com \
OPENAI_MODEL=deepseek-chat \
KB_AI_ENABLED=true mvn spring-boot:run
```

也可以直接改 `application.yml` 里的 `spring.ai.openai.*` 和 `kb.ai.enabled`。

## 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/documents` | 上传文档（multipart，字段名 `file`） |
| GET | `/api/documents?page=1&size=10` | 文档列表 |
| DELETE | `/api/documents/{id}` | 删除文档及其切片 |
| GET | `/api/documents/stats` | 文档数 / 切片数 / 索引大小 |
| GET | `/api/search?q=关键词&topK=5` | 只检索不生成，用来单独看召回效果 |
| POST | `/api/chat` | 问答，body：`{"question":"...","topK":5}` |

`/api/chat` 返回的 `mode` 有三种：`llm`（模型生成）、`retrieval`（未配模型或模型失败，返回原文）、`empty`（检索无命中）。

## 几个设计取舍

**为什么不用向量检索**

切片量在万级以内时，全量 BM25 索引加载进内存只占几十 MB，检索耗时毫秒级。引入 embedding + 向量库要多一套部署、多一份模型调用成本，对单机知识库是负收益。数据量涨到十万级以上再换，接口层不用动。

**为什么在章节标题处强制断开切片**

纯定长滑窗会把「考勤制度」和「年假制度」混进同一个切片。用户问年假，捞出来的块前半截讲打卡、后半截讲年假，页码溯源也不准。按标题切之后，每个切片语义自洽，引用到的页码就是答案真正所在的章节。

**为什么检索没命中就不调模型**

把空上下文发给模型，它一定会自己编。宁可回一句「知识库里没有」，也不要一个看起来像模像样的错误答案。同理，提示词里明确禁止用模型自身知识补充细节。

**为什么要做模型故障降级**

模型接口会超时、会欠费、会被限流。这些都不该让问答接口整个 500——检索能力本身还在，退回展示原文摘录，用户至少能拿到资料。

## 已知局限

- **BM25 只认字面**。用户问「年假有几天」，文档写「年假 5 天」——「几天」和「5 天」不匹配，只能靠「年假」这个词勉强召回。同义改写、口语化提问的召回率不如向量检索。
- **语料外的问题可能硬凑**。问「公司股票代码是多少」，如果文档里恰好有「公司」两个字，仍会召回几段无关内容。目前靠提示词让模型拒答来兜底，纯检索模式（没配模型）下会看到这些无关结果。
- **扫描版 PDF 不支持**。没有文字的 PDF 会被判定为解析失败，需要先做 OCR。
- **单机内存索引**。进程重启要重建索引（启动时自动做），多实例部署时各实例索引不一致——真要多实例得上独立的检索服务。

## 目录结构

```
Aikb/
├── backend/
│   └── src/main/java/cn/gok/kb/
│       ├── parser/      文档解析（PDFBox 按页抽取、txt/md 直读）
│       ├── split/       切片（章节优先 + 句子滑窗）
│       ├── search/      BM25 索引、分词、检索
│       ├── chat/        提示词拼装、模型调用、降级
│       ├── document/    文档管理与上传接口
│       └── common/      统一响应、异常处理
├── frontend/
│   └── src/
│       ├── components/  DocPanel（文档）、ChatPanel（问答）
│       └── api/         接口封装
├── db/schema.sql
└── samples/             示例文档，可直接用来试效果
```

## 示例

`samples/员工手册示例.md` 是一份虚构员工手册，上传后可以问：

- 年假有几天？
- 出差住宿报销上限是多少？
- 生产环境什么时候能变更？
- 加班怎么调休？

问知识库里没有的内容（比如「今天中午吃什么」），应该得到「没有检索到相关内容」。
