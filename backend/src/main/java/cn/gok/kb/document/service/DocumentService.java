package cn.gok.kb.document.service;

import cn.gok.kb.chunk.entity.KbChunk;
import cn.gok.kb.chunk.mapper.KbChunkMapper;
import cn.gok.kb.common.BizException;
import cn.gok.kb.common.PageResult;
import cn.gok.kb.config.KbProperties;
import cn.gok.kb.document.dto.DocumentVO;
import cn.gok.kb.document.entity.KbDocument;
import cn.gok.kb.document.mapper.KbDocumentMapper;
import cn.gok.kb.parser.ParsedPage;
import cn.gok.kb.parser.ParserFactory;
import cn.gok.kb.embedding.EmbeddingService;
import cn.gok.kb.search.SearchIndexManager;
import cn.gok.kb.split.TextSplitter;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    /** 单批 insert 的切片数，避免一条 SQL 过长触发 max_allowed_packet */
    private static final int BATCH_SIZE = 200;

    private static final Set<String> ALLOWED_EXT = Set.of("pdf", "md", "markdown", "txt");

    private final KbDocumentMapper docMapper;
    private final KbChunkMapper chunkMapper;
    private final ParserFactory parserFactory;
    private final TextSplitter splitter;
    private final SearchIndexManager indexManager;
    private final EmbeddingService embeddingService;
    private final KbProperties props;

    /**
     * 上传 -> 解析 -> 切片 -> 入库 -> 重建索引。
     */
    @Transactional(rollbackFor = Exception.class)
    public DocumentVO upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException("请选择要上传的文件");
        }

        String original = sanitize(file.getOriginalFilename());
        String ext = extOf(original);
        if (!ALLOWED_EXT.contains(ext)) {
            throw new BizException(415, "仅支持 pdf / md / txt，当前为：" + ext);
        }

        Path target = null;
        try {
            // 1. 落盘。用 UUID 命名，避免同名文件互相覆盖，也避免中文名带来的编码问题
            Path dir = Paths.get(props.getUploadDir()).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            String storedName = UUID.randomUUID().toString().replace("-", "") + "." + ext;
            target = dir.resolve(storedName);
            file.transferTo(target);

            // 2. 解析
            List<ParsedPage> pages;
            try (InputStream in = Files.newInputStream(target)) {
                pages = parserFactory.parse(ext, in);
            }

            // 3. 切片。页码随切片一起存下来，检索结果才能定位到原文哪一页
            List<KbChunk> chunks = new ArrayList<>();
            int seq = 0;
            for (ParsedPage page : pages) {
                for (String piece : splitter.split(page.text())) {
                    KbChunk c = new KbChunk();
                    c.setPageNo(page.pageNo());
                    c.setSeqNo(seq++);
                    c.setContent(piece);
                    c.setCharCount(piece.length());
                    chunks.add(c);
                }
            }
            if (chunks.isEmpty()) {
                throw new BizException(422, "文档没有产出有效切片，请检查内容是否为空");
            }

            // 4. 入库
            KbDocument doc = new KbDocument();
            doc.setFileName(original);
            doc.setStoredName(storedName);
            doc.setFileType(ext);
            doc.setFileSize(file.getSize());
            doc.setPageCount(pages.size());
            doc.setChunkCount(chunks.size());
            doc.setStatus(1);
            doc.setCreateTime(LocalDateTime.now());
            docMapper.insert(doc);

            Long docId = doc.getId();
            chunks.forEach(c -> c.setDocId(docId));
            for (int i = 0; i < chunks.size(); i += BATCH_SIZE) {
                chunkMapper.batchInsert(chunks.subList(i, Math.min(i + BATCH_SIZE, chunks.size())));
            }

            // 5. 索引重建必须等事务提交后再做：
            //    在事务内重建会让重建耗时拉长事务持有时间，而且一旦回滚，
            //    索引里已经混进了不存在的数据。
            //    向量化（调 embedding 接口）同理，一并放到提交之后：
            //    外部网络调用绝不能进数据库事务，否则一次慢请求就把行锁拖成秒级。
            afterCommit(() -> {
                backfillEmbedding(docId);
                safeRebuild();
            });

            log.info("文档入库成功：{}，{} 页，{} 个切片", original, pages.size(), chunks.size());
            return toVO(doc);

        } catch (BizException e) {
            deleteQuietly(target);
            throw e;
        } catch (Exception e) {
            deleteQuietly(target);
            log.error("文档入库失败: {}", original, e);
            throw new BizException(500, "文件处理失败：" + e.getMessage());
        }
    }

    public PageResult<DocumentVO> list(int page, int size) {
        Page<KbDocument> query = Page.of(Math.max(1, page), Math.min(Math.max(1, size), 100));
        Page<KbDocument> result = docMapper.selectPage(query,
                Wrappers.<KbDocument>lambdaQuery().orderByDesc(KbDocument::getId));
        List<DocumentVO> records = result.getRecords().stream().map(this::toVO).toList();
        return PageResult.of(result.getTotal(), (int) result.getCurrent(), (int) result.getSize(), records);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        if (id == null || id <= 0) {
            throw new BizException("文档 id 不合法");
        }
        KbDocument doc = docMapper.selectById(id);
        if (doc == null) {
            throw new BizException(404, "文档不存在或已删除");
        }
        chunkMapper.delete(Wrappers.<KbChunk>lambdaQuery().eq(KbChunk::getDocId, id));
        docMapper.deleteById(id);

        // 磁盘文件在事务外清理：即使删文件失败，也不该让数据库回滚
        if (doc.getStoredName() != null && !doc.getStoredName().isBlank()) {
            Path file = Paths.get(props.getUploadDir()).toAbsolutePath().normalize()
                    .resolve(doc.getStoredName());
            afterCommit(() -> {
                deleteQuietly(file);
                safeRebuild();
            });
        } else {
            afterCommit(this::safeRebuild);
        }
    }

    /** 首页统计 */
    public Map<String, Object> stats() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("docCount", docMapper.selectCount(null));
        map.put("chunkCount", chunkMapper.selectCount(null));
        map.put("indexSize", indexManager.size());
        map.put("chunkSize", props.getChunk().getSize());
        map.put("chunkOverlap", props.getChunk().getOverlap());
        map.put("vectorEnabled", embeddingService.isEnabled());
        map.put("embeddingMissing", chunkMapper.countMissingEmbedding());
        return map;
    }

    /**
     * 给刚入库的切片补向量。
     *
     * <p>放在事务提交后执行的原因：embedding 是一次 HTTP 调用，
     * 本地模型要几百毫秒，云端接口遇到大文档更慢。放进事务里
     * 会让 undo log 和行锁一直挂着，并发上传直接互相阻塞。
     *
     * <p>失败不影响正确性：切片以 embedding = NULL 落库，
     * 检索时只走 BM25 那一路，功能仍然可用，只是少了一路召回。
     */
    private void backfillEmbedding(Long docId) {
        if (!embeddingService.isEnabled()) {
            return;
        }
        long start = System.currentTimeMillis();
        List<KbChunk> chunks = chunkMapper.selectList(
                Wrappers.<KbChunk>lambdaQuery().eq(KbChunk::getDocId, docId));
        if (chunks.isEmpty()) {
            return;
        }
        try {
            List<float[]> vectors = embeddingService.embedAll(
                    chunks.stream().map(KbChunk::getContent).toList());
            if (vectors.size() != chunks.size()) {
                log.warn("向量数量与切片数量不一致（{} vs {}），跳过补齐", vectors.size(), chunks.size());
                return;
            }
            String tag = embeddingService.modelTag();
            for (int i = 0; i < chunks.size(); i++) {
                KbChunk patch = new KbChunk();
                patch.setId(chunks.get(i).getId());
                patch.setEmbedding(EmbeddingService.toJson(vectors.get(i)));
                patch.setEmbedModel(tag);
                chunkMapper.updateById(patch);
            }
            log.info("切片向量化完成：docId={}，{} 条，耗时 {} ms",
                    docId, chunks.size(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.warn("切片向量化失败，该文档将只参与 BM25 检索：{}", e.getMessage());
        }
    }

    /**
     * 把动作注册到事务提交之后执行；没有事务上下文时直接执行。
     */
    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private void safeRebuild() {
        try {
            indexManager.rebuildAll();
        } catch (Exception e) {
            log.error("索引重建失败，检索结果可能不是最新", e);
        }
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (Exception e) {
            log.warn("临时文件清理失败: {}", path, e);
        }
    }

    /** 只取文件名部分，挡住 ../../etc/passwd 这类路径穿越 */
    private String sanitize(String name) {
        if (name == null || name.isBlank()) {
            return "unnamed";
        }
        String base = Paths.get(name.replace('\\', '/')).getFileName().toString();
        return base.isBlank() ? "unnamed" : base;
    }

    private String extOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private DocumentVO toVO(KbDocument doc) {
        return new DocumentVO(doc.getId(), doc.getFileName(), doc.getFileType(), doc.getFileSize(),
                doc.getPageCount(), doc.getChunkCount(), doc.getStatus(),
                DocumentVO.statusText(doc.getStatus()), doc.getCreateTime());
    }
}
