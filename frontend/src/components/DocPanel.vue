<template>
  <section class="panel">
    <div class="panel-head">
      <h2>知识库文档</h2>
      <el-button text size="small" :loading="loading" @click="load">刷新</el-button>
    </div>

    <el-upload
        class="uploader"
        drag
        :show-file-list="false"
        :before-upload="beforeUpload"
        :http-request="doUpload"
        accept=".pdf,.md,.markdown,.txt"
    >
      <div v-loading="uploading" class="upload-inner">
        <div class="upload-title">{{ uploading ? '正在解析并入库…' : '拖入文件或点击上传' }}</div>
        <div class="upload-tip">PDF / Markdown / TXT，单文件不超过 30MB</div>
      </div>
    </el-upload>

    <div v-loading="loading" class="list">
      <div v-if="docs.length === 0 && !loading" class="empty">
        还没有文档，先上传一份资料
      </div>
      <div v-for="doc in docs" :key="doc.id" class="doc-item">
        <div class="doc-main">
          <div class="doc-name" :title="doc.fileName">{{ doc.fileName }}</div>
          <div class="doc-meta">
            <span class="tag">{{ (doc.fileType || '').toUpperCase() }}</span>
            <span>{{ doc.pageCount }} 页</span>
            <span>{{ doc.chunkCount }} 片</span>
          </div>
        </div>
        <button class="del" title="删除文档" @click="remove(doc)">删除</button>
      </div>
    </div>
  </section>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deleteDocument, listDocuments, uploadDocument } from '../api'

const emit = defineEmits(['changed'])

const docs = ref([])
const loading = ref(false)
const uploading = ref(false)

const MAX_SIZE = 30 * 1024 * 1024
const ALLOWED = ['pdf', 'md', 'markdown', 'txt']

const load = async () => {
  loading.value = true
  try {
    const page = await listDocuments(1, 50)
    docs.value = page.records || []
  } catch (e) {
    docs.value = []
  } finally {
    loading.value = false
  }
}

// 前端只做快速拦截，真正的格式校验在后端再走一遍
const beforeUpload = (file) => {
  const ext = (file.name.split('.').pop() || '').toLowerCase()
  if (!ALLOWED.includes(ext)) {
    ElMessage.warning('只支持 PDF / Markdown / TXT')
    return false
  }
  if (file.size > MAX_SIZE) {
    ElMessage.warning('文件超过 30MB')
    return false
  }
  return true
}

const doUpload = async ({ file }) => {
  uploading.value = true
  try {
    const doc = await uploadDocument(file)
    ElMessage.success(`入库成功：${doc.fileName}，共 ${doc.chunkCount} 个切片`)
    await load()
    emit('changed')
  } catch (e) {
    // 拦截器已提示，这里保证 loading 一定会关
  } finally {
    uploading.value = false
  }
}

const remove = async (doc) => {
  try {
    await ElMessageBox.confirm(
        `删除《${doc.fileName}》后，它的 ${doc.chunkCount} 个切片也会一并移除，确认吗？`,
        '删除文档',
        { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await deleteDocument(doc.id)
    ElMessage.success('已删除')
    await load()
    emit('changed')
  } catch (e) {
    // 已提示
  }
}

onMounted(load)
</script>

<style scoped>
.panel {
  display: flex;
  flex-direction: column;
  background: #fff;
  border-radius: 12px;
  border: 1px solid #e6e9ef;
  padding: 16px;
  overflow: hidden;
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.panel-head h2 {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: #2b3550;
}

.uploader {
  flex: 0 0 auto;
}

.uploader :deep(.el-upload-dragger) {
  padding: 0;
  border-radius: 10px;
  border-color: #d6dbe6;
  background: #fafbfd;
}

.upload-inner {
  padding: 22px 12px;
}

.upload-title {
  font-size: 13px;
  color: #4f7cf7;
  font-weight: 500;
}

.upload-tip {
  margin-top: 6px;
  font-size: 11px;
  color: #9aa1b1;
}

.list {
  flex: 1 1 auto;
  min-height: 0;
  margin-top: 14px;
  overflow-y: auto;
}

.empty {
  padding: 30px 0;
  text-align: center;
  font-size: 12px;
  color: #9aa1b1;
}

.doc-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 8px;
  border-radius: 8px;
  transition: background 0.15s;
}

.doc-item:hover {
  background: #f6f8fc;
}

.doc-main {
  flex: 1 1 auto;
  min-width: 0;
}

.doc-name {
  font-size: 13px;
  color: #2b3550;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.doc-meta {
  margin-top: 4px;
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 11px;
  color: #9aa1b1;
}

.tag {
  padding: 1px 6px;
  border-radius: 4px;
  background: #eef2ff;
  color: #4f7cf7;
  font-weight: 500;
}

.del {
  flex: 0 0 auto;
  border: none;
  background: transparent;
  color: #b6bcc9;
  font-size: 12px;
  cursor: pointer;
  padding: 4px 6px;
  border-radius: 6px;
}

.del:hover {
  color: #f56c6c;
  background: #fef0f0;
}
</style>
