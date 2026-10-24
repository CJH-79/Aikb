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

export const ask = (question, topK) => http.post('/chat', { question, topK })

export const searchOnly = (q, topK) => http.get('/search', { params: { q, topK } })

export default http
