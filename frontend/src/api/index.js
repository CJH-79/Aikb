import axios from 'axios'
import { ElMessage } from 'element-plus'

const http = axios.create({
  baseURL: '/api',
  timeout: 120000 // 模型生成答案可能较慢，超时给足
})

// 后端统一返回 { code, msg, data }，这里统一拆包，业务层只拿 data
http.interceptors.response.use(
  (resp) => {
    const body = resp.data
    if (body && typeof body.code === 'number') {
      if (body.code === 200) return body.data
      ElMessage.error(body.msg || '请求失败')
      return Promise.reject(new Error(body.msg || '请求失败'))
    }
    return body
  },
  (err) => {
    const msg = err.response?.data?.msg || err.message || '网络异常'
    ElMessage.error(msg)
    return Promise.reject(err)
  }
)

export const uploadDocument = (file) => {
  const form = new FormData()
  form.append('file', file)
  return http.post('/documents', form, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

export const listDocuments = (page = 1, size = 50) =>
  http.get('/documents', { params: { page, size } })

export const deleteDocument = (id) => http.delete(`/documents/${id}`)

export const fetchStats = () => http.get('/documents/stats')

export const ask = (question, topK, conversationId) =>
  http.post('/chat', { question, topK, conversationId })

export const searchOnly = (q, topK) => http.get('/search', { params: { q, topK } })

export const resetConversation = (conversationId) =>
  http.post('/chat/reset', null, { params: { conversationId } })

/**
 * SSE 流式问答。
 *
 * 为什么不用 EventSource：它只支持 GET、不能带请求体，
 * 而我们既要把问题放 body（避免超长 URL），又要传 conversationId。
 * 所以用 fetch 读 ReadableStream，自己按 SSE 帧格式切分——协议仍然是标准 SSE。
 *
 * @param handlers {{ meta, delta, done }} 按事件名分发
 */
export const streamChat = async ({ question, topK, conversationId }, handlers = {}) => {
  const resp = await fetch('/api/chat/stream', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream'
    },
    body: JSON.stringify({ question, topK, conversationId })
  })

  if (!resp.ok) {
    throw new Error(`请求失败：HTTP ${resp.status}`)
  }
  if (!resp.body) {
    throw new Error('当前环境不支持流式读取（浏览器过旧？）')
  }

  const reader = resp.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buf = ''

  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buf += decoder.decode(value, { stream: true })
    buf = buf.replace(/\r\n/g, '\n')

    // SSE 以空行分隔事件帧；一帧可能被 TCP 拆成多次读到，所以要在循环里持续切
    let idx
    while ((idx = buf.indexOf('\n\n')) >= 0) {
      const frame = buf.slice(0, idx)
      buf = buf.slice(idx + 2)
      dispatchFrame(frame, handlers)
    }
  }
}

function dispatchFrame(frame, handlers) {
  let event = 'message'
  const dataLines = []

  for (const line of frame.split('\n')) {
    if (line.startsWith(':')) continue // 注释行/心跳
    if (line.startsWith('event:')) {
      event = line.slice(6).trim()
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).replace(/^ /, ''))
    }
  }

  if (dataLines.length === 0) return

  const raw = dataLines.join('\n')
  let payload
  try {
    payload = JSON.parse(raw)
  } catch {
    payload = { t: raw }
  }
  const fn = handlers[event]
  if (typeof fn === 'function') fn(payload)
}

export default http
