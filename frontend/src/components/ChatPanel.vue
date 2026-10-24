<template>
  <section class="panel">
    <div ref="scrollRef" class="messages">
      <div v-if="messages.length === 0" class="welcome">
        <h2>基于你的资料回答问题</h2>
        <p>
          答案只会依据知识库里的内容生成，并标注出自哪个文件第几页；
          资料里没有的，会直接告诉你查不到，而不是编一个。
        </p>
        <div class="samples">
          <button v-for="s in samples" :key="s" class="sample" @click="draft = s; send()">
            {{ s }}
          </button>
        </div>
        <p v-if="!ready" class="warn">知识库还没有内容，先在左侧上传一份文档。</p>
      </div>

      <div v-for="(m, i) in messages" :key="i" :class="['msg', m.role]">
        <div class="avatar">{{ m.role === 'user' ? '我' : 'AI' }}</div>
        <div class="bubble-wrap">
          <div class="bubble">
            <div v-if="m.mode === 'retrieval'" class="mode-tip retrieval">
              {{ m.tip }}
            </div>
            <div v-else-if="m.mode === 'empty'" class="mode-tip empty">
              {{ m.tip }}
            </div>
            <div class="answer" v-html="render(m.text)"></div>
          </div>

          <div v-if="m.mode && m.mode !== 'empty'" class="meta-row">
            <span class="mode">{{ modeLabel(m.mode) }}</span>
            <span>耗时 {{ m.elapsedMs }} ms</span>
          </div>

          <div v-if="m.citations && m.citations.length" class="citations">
            <button class="cite-toggle" @click="toggle(i)">
              引用来源 {{ m.citations.length }} 条
              <span class="arrow">{{ expanded[i] ? '收起' : '展开' }}</span>
            </button>
            <div v-show="expanded[i]" class="cite-list">
              <div v-for="c in m.citations" :key="c.no" class="cite">
                <div class="cite-head">
                  <span class="no">[{{ c.no }}]</span>
                  <span class="file" :title="c.fileName">{{ c.fileName }}</span>
                  <span class="page">第 {{ c.pageNo }} 页</span>
                  <span class="score">相关度 {{ Number(c.score).toFixed(2) }}</span>
                </div>
                <div class="cite-body">{{ c.snippet }}</div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div v-if="loading" class="msg assistant">
        <div class="avatar">AI</div>
        <div class="bubble thinking">正在检索资料并生成答案…</div>
      </div>
    </div>

    <div class="composer">
      <textarea
          v-model="draft"
          class="input"
          rows="2"
          placeholder="输入问题，Enter 发送，Shift + Enter 换行"
          @keydown.enter.exact.prevent="send"
      ></textarea>
      <button class="send" :disabled="!canSend" @click="send">
        {{ loading ? '生成中' : '发送' }}
      </button>
    </div>
  </section>
</template>

<script setup>
import { computed, nextTick, reactive, ref } from 'vue'
import { ask } from '../api'

defineProps({
  ready: { type: Boolean, default: false }
})

const samples = [
  '这份资料主要讲了什么',
  '有哪些关键流程',
  '文档里提到了哪些注意事项'
]

const messages = ref([])
const draft = ref('')
const loading = ref(false)
const expanded = reactive({})
const scrollRef = ref(null)

const canSend = computed(() => draft.value.trim().length > 0 && !loading.value)

const modeLabel = (mode) => {
  if (mode === 'llm') return '模型生成'
  if (mode === 'retrieval') return '纯检索'
  return '无结果'
}

// 只处理 **加粗** 和换行，避免引入 markdown 解析库后还要防 XSS
const render = (text) => {
  const escaped = (text || '')
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
  return escaped
      .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
      .replace(/\n/g, '<br/>')
}

const scrollToBottom = async () => {
  await nextTick()
  const el = scrollRef.value
  if (el) el.scrollTop = el.scrollHeight
}

const toggle = (i) => {
  expanded[i] = !expanded[i]
}

const send = async () => {
  const question = draft.value.trim()
  if (!question || loading.value) return

  messages.value.push({ role: 'user', text: question })
  draft.value = ''
  loading.value = true
  await scrollToBottom()

  try {
    const res = await ask(question)
    messages.value.push({
      role: 'assistant',
      text: res.answer,
      mode: res.mode,
      citations: res.citations || [],
      elapsedMs: res.elapsedMs,
      tip: res.tip
    })
  } catch (e) {
    messages.value.push({
      role: 'assistant',
      text: '请求失败，请检查后端服务是否正常。',
      mode: 'empty',
      citations: []
    })
  } finally {
    loading.value = false
    await scrollToBottom()
  }
}
</script>

<style scoped>
.panel {
  display: flex;
  flex-direction: column;
  background: #fff;
  border-radius: 12px;
  border: 1px solid #e6e9ef;
  overflow: hidden;
}

.messages {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  padding: 20px 22px;
}

.welcome {
  padding: 40px 10px;
  max-width: 560px;
  margin: 0 auto;
  text-align: center;
}

.welcome h2 {
  margin: 0 0 10px;
  font-size: 18px;
  color: #2b3550;
}

.welcome p {
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
  color: #8a90a0;
}

.warn {
  margin-top: 14px !important;
  color: #e6a23c !important;
}

.samples {
  margin-top: 20px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: center;
}

.sample {
  padding: 7px 13px;
  border-radius: 16px;
  border: 1px solid #dfe4ee;
  background: #fafbfd;
  color: #5a6072;
  font-size: 12px;
  cursor: pointer;
  transition: all 0.15s;
}

.sample:hover {
  border-color: #4f7cf7;
  color: #4f7cf7;
  background: #f4f7ff;
}

.msg {
  display: flex;
  gap: 10px;
  margin-bottom: 20px;
}

.msg.user {
  flex-direction: row-reverse;
}

.avatar {
  flex: 0 0 auto;
  width: 30px;
  height: 30px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  font-weight: 600;
}

.msg.user .avatar {
  background: #4f7cf7;
  color: #fff;
}

.msg.assistant .avatar {
  background: #eef2ff;
  color: #4f7cf7;
}

.bubble-wrap {
  max-width: 78%;
  min-width: 0;
}

.msg.user .bubble-wrap {
  display: flex;
  justify-content: flex-end;
}

.bubble {
  display: inline-block;
  padding: 11px 14px;
  border-radius: 10px;
  font-size: 13.5px;
  line-height: 1.75;
  word-break: break-word;
}

.msg.user .bubble {
  background: #4f7cf7;
  color: #fff;
  border-top-right-radius: 3px;
}

.msg.assistant .bubble {
  background: #f6f8fc;
  color: #2b3550;
  border-top-left-radius: 3px;
}

.bubble.thinking {
  color: #9aa1b1;
}

.mode-tip {
  margin-bottom: 8px;
  padding: 6px 9px;
  border-radius: 6px;
  font-size: 11.5px;
  line-height: 1.6;
}

.mode-tip.retrieval {
  background: #fdf6ec;
  color: #b88230;
}

.mode-tip.empty {
  background: #f4f4f5;
  color: #909399;
}

.meta-row {
  display: flex;
  gap: 10px;
  margin-top: 6px;
  font-size: 11px;
  color: #a8aebc;
}

.mode {
  color: #4f7cf7;
}

.citations {
  margin-top: 8px;
}

.cite-toggle {
  border: none;
  background: transparent;
  padding: 2px 0;
  font-size: 12px;
  color: #4f7cf7;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 6px;
}

.arrow {
  color: #a8aebc;
  font-size: 11px;
}

.cite-list {
  margin-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.cite {
  border: 1px solid #eaeef5;
  border-radius: 8px;
  padding: 9px 11px;
  background: #fcfdff;
}

.cite-head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 11.5px;
  color: #6b7280;
}

.cite-head .no {
  color: #4f7cf7;
  font-weight: 600;
}

.cite-head .file {
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #2b3550;
}

.cite-head .score {
  margin-left: auto;
  color: #9aa1b1;
}

.cite-body {
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.7;
  color: #6b7280;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.composer {
  flex: 0 0 auto;
  display: flex;
  gap: 10px;
  align-items: flex-end;
  padding: 12px 16px;
  border-top: 1px solid #eef1f6;
  background: #fff;
}

.input {
  flex: 1 1 auto;
  resize: none;
  border: 1px solid #dfe4ee;
  border-radius: 9px;
  padding: 9px 12px;
  font-size: 13.5px;
  font-family: inherit;
  line-height: 1.6;
  color: #2b3550;
  outline: none;
  transition: border-color 0.15s;
}

.input:focus {
  border-color: #4f7cf7;
}

.send {
  flex: 0 0 auto;
  height: 38px;
  padding: 0 20px;
  border: none;
  border-radius: 9px;
  background: #4f7cf7;
  color: #fff;
  font-size: 13px;
  cursor: pointer;
  transition: opacity 0.15s;
}

.send:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
</style>
