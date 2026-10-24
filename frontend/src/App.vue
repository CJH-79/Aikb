<template>
  <div class="app">
    <header class="topbar">
      <div class="brand">
        <div class="logo">KB</div>
        <div class="titles">
          <h1>AI 知识库助手</h1>
          <p>上传资料 · 关键词检索 · 模型作答并标注来源</p>
        </div>
      </div>
      <div class="stats">
        <div class="stat">
          <b>{{ stats.docCount ?? '-' }}</b>
          <span>文档</span>
        </div>
        <div class="stat">
          <b>{{ stats.chunkCount ?? '-' }}</b>
          <span>切片</span>
        </div>
        <div class="stat">
          <b>{{ stats.indexSize ?? '-' }}</b>
          <span>已索引</span>
        </div>
      </div>
    </header>

    <main class="body">
      <DocPanel class="doc-panel" @changed="refresh" />
      <ChatPanel class="chat-panel" :ready="(stats.indexSize ?? 0) > 0" />
    </main>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import DocPanel from './components/DocPanel.vue'
import ChatPanel from './components/ChatPanel.vue'
import { fetchStats } from './api'

const stats = ref({})

const refresh = async () => {
  try {
    stats.value = await fetchStats()
  } catch (e) {
    // 错误提示已在拦截器里统一处理，这里只需要保证页面不白屏
  }
}

onMounted(refresh)
</script>

<style scoped>
.app {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.topbar {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 24px;
  background: #ffffff;
  border-bottom: 1px solid #e6e9ef;
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
}

.logo {
  width: 38px;
  height: 38px;
  border-radius: 10px;
  background: linear-gradient(135deg, #4f7cf7, #6f5cf5);
  color: #fff;
  font-weight: 700;
  font-size: 13px;
  letter-spacing: 0.5px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.titles h1 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
}

.titles p {
  margin: 2px 0 0;
  font-size: 12px;
  color: #8a90a0;
}

.stats {
  display: flex;
  gap: 28px;
}

.stat {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  line-height: 1.2;
}

.stat b {
  font-size: 18px;
  color: #2b3550;
}

.stat span {
  font-size: 11px;
  color: #9aa1b1;
}

.body {
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
  gap: 16px;
  padding: 16px;
}

.doc-panel {
  flex: 0 0 340px;
  min-width: 0;
}

.chat-panel {
  flex: 1 1 auto;
  min-width: 0;
}

@media (max-width: 900px) {
  .body {
    flex-direction: column;
  }

  .doc-panel {
    flex: 0 0 auto;
    max-height: 40vh;
  }
}
</style>
