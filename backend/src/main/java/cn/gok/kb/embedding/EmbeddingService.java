package cn.gok.kb.embedding;

import cn.gok.kb.config.KbProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * embedding 的统一入口。
 *
 * <p>设计上把「有没有向量能力」做成一等公民，而不是假设它一定存在：
 * <ul>
 *   <li>没配 embedding 模型时 {@link #isEnabled()} 返回 false，
 *       上层自动退回纯 BM25 检索，整个系统仍然可用；</li>
 *   <li>配了就走 Spring AI 的 {@link EmbeddingModel}，协议是 OpenAI 兼容的
 *       {@code POST /v1/embeddings}，所以阿里百炼、智谱、硅基流动、Ollama 都能直接换地址接上。</li>
 * </ul>
 *
 * <p>关键细节：向量在写入时就做 L2 归一化。这样余弦相似度退化成点积，
 * 检索时省掉每个候选两次开方；库里存的也是归一化后的值，跨语言、跨批次都不会算错。
 */
@Slf4j
@Service
public class EmbeddingService {

    private final KbProperties props;
    private final EmbeddingModel model;
    private final boolean enabled;

    public EmbeddingService(ObjectProvider<EmbeddingModel> modelProvider, KbProperties props) {
        this.props = props;
        this.model = modelProvider.getIfAvailable();
        this.enabled = props.getAi().isEmbeddingEnabled() && this.model != null;
        if (props.getAi().isEmbeddingEnabled() && this.model == null) {
            log.warn("已开启向量检索但容器里没有 EmbeddingModel，"
                    + "请检查 spring.ai.openai.api-key / base-url 是否配置完整");
        }
        log.info("向量检索：{}", this.enabled ? "已启用" : "未启用（检索走纯 BM25）");
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 向量来源标识。存进 kb_chunk.embed_model，将来换模型时能一眼看出哪些切片的向量是旧的。
     * 不用配置里的模型名，是因为同一个名字在不同服务商下维度可能不同，带上维度更可靠。
     */
    public String modelTag() {
        if (!enabled) {
            return null;
        }
        return model.getClass().getSimpleName() + "#" + dimensions();
    }

    public int dimensions() {
        if (!enabled) {
            return 0;
        }
        try {
            return model.dimensions();
        } catch (Exception e) {
            // dimensions() 的实现可能直接打网络请求，失败不该影响主流程
            log.debug("获取向量维度失败：{}", e.getMessage());
            return 0;
        }
    }

    /**
     * 批量向量化。调用方负责分批大小（见 {@link KbProperties.Ai#getEmbeddingBatchSize()}）。
     *
     * @return 与入参等长的归一化向量；任一批失败即抛异常，由调用方决定降级策略
     */
    public List<float[]> embedAll(List<String> texts) {
        if (!enabled || texts.isEmpty()) {
            return List.of();
        }
        List<float[]> result = new ArrayList<>(texts.size());
        int batch = Math.max(1, props.getAi().getEmbeddingBatchSize());
        for (int i = 0; i < texts.size(); i += batch) {
            List<String> part = texts.subList(i, Math.min(i + batch, texts.size()));
            List<float[]> vectors = model.embed(part);
            for (float[] v : vectors) {
                result.add(normalize(v));
            }
        }
        return result;
    }

    /** 单条向量化，返回归一化向量；未启用或失败返回 null */
    public float[] embed(String text) {
        if (!enabled || text == null || text.isBlank()) {
            return null;
        }
        try {
            return normalize(model.embed(text));
        } catch (Exception e) {
            log.warn("向量化失败：{}", e.getMessage());
            return null;
        }
    }

    /** L2 归一化，保证后续余弦相似度可以直接用点积算 */
    public static float[] normalize(float[] v) {
        double sum = 0;
        for (float x : v) {
            sum += (double) x * x;
        }
        double norm = Math.sqrt(sum);
        if (norm < 1e-8) {
            return v;
        }
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = (float) (v[i] / norm);
        }
        return out;
    }

    /** float[] -> JSON 数组。手写而不走 Jackson：几万条切片重建索引时这个差别是看得见的 */
    public static String toJson(float[] v) {
        if (v == null || v.length == 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder(v.length * 9);
        sb.append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(round(v[i]));
        }
        return sb.append(']').toString();
    }

    public static float[] fromJson(String json) {
        if (json == null || json.length() < 2) {
            return null;
        }
        String body = json.trim();
        if (body.charAt(0) == '[') {
            body = body.substring(1, body.length() - 1);
        }
        if (body.isBlank()) {
            return null;
        }
        String[] parts = body.split(",");
        float[] v = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                v[i] = Float.parseFloat(parts[i].trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return v;
    }

    /** 保留 6 位小数：cosine 对这点精度完全不敏感，但存储体积能小一半 */
    private static String round(float x) {
        return String.valueOf(Math.round(x * 1_000_000.0) / 1_000_000.0);
    }
}
