package cn.gok.kb.split;

import cn.gok.kb.config.KbProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 文本切片。
 *
 * <p>策略：先按句子边界切散，再贪心累积到接近 chunkSize；相邻块之间保留 overlap 个字符，
 * 避免一句话被切断后，前后两块都检索不到。
 *
 * <p>为什么不用固定长度硬切：硬切会把「Spring 的事务传播行为有」/「七种」切成两块，
 * 任何一块单独看都没有信息量，模型拿到也用不上。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TextSplitter {

    /** 句末标点 + 换行，作为首选切分点 */
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[。！？!?;；\\n])");

    private final KbProperties props;

    /**
     * @return 切片列表，不含空白块
     */
    public List<String> split(String raw) {
        int size = Math.max(80, props.getChunk().getSize());
        int overlap = Math.max(0, Math.min(props.getChunk().getOverlap(), size / 2));

        String text = normalize(raw);
        if (text.isEmpty()) {
            return List.of();
        }

        List<String> chunks = new ArrayList<>();
        StringBuilder cur = new StringBuilder();

        for (String sentence : sentences(text)) {
            // 章节标题处优先断开：否则「考勤制度」和「年假制度」会挤在同一个切片里，
            // 检索年假时把考勤段落一起捞出来，溯源页码也不准。
            // 标题是新章节的起点，这里刻意不带上一节的 overlap。
            if (isHeading(sentence) && cur.length() >= size / 3) {
                String done = cur.toString().trim();
                if (!done.isEmpty()) {
                    chunks.add(done);
                }
                cur.setLength(0);
            }

            // 超长句（无标点的长段落）单独硬切，否则一块永远装不下
            if (sentence.length() > size) {
                if (!cur.isEmpty()) {
                    chunks.add(cur.toString().trim());
                    cur.setLength(0);
                }
                chunks.addAll(hardSplit(sentence, size, overlap));
                continue;
            }

            if (cur.length() + sentence.length() > size) {
                String done = cur.toString().trim();
                if (!done.isEmpty()) {
                    chunks.add(done);
                }
                cur.setLength(0);
                // 保留上一块的尾部，作为下一块的开头重叠
                if (overlap > 0 && done.length() > overlap) {
                    cur.append(done, done.length() - overlap, done.length());
                }
            }
            cur.append(sentence);
        }

        if (!cur.isEmpty()) {
            String tail = cur.toString().trim();
            if (!tail.isEmpty()) {
                chunks.add(tail);
            }
        }

        List<String> result = chunks.stream().filter(c -> !c.isBlank()).toList();
        log.debug("切片完成：原文 {} 字 -> {} 块", text.length(), result.size());
        return result;
    }

    /** 压缩多余空白，但保留段落分隔 */
    private String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[ \\t\\x0B\\f]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private List<String> sentences(String text) {
        List<String> list = new ArrayList<>();
        for (String s : SENTENCE_END.split(text)) {
            if (!s.isBlank()) {
                list.add(s);
            }
        }
        return list;
    }

    /** Markdown 标题行，用作章节边界 */
    private boolean isHeading(String sentence) {
        return sentence.stripLeading().startsWith("#");
    }

    private List<String> hardSplit(String text, int size, int overlap) {
        List<String> list = new ArrayList<>();
        int step = Math.max(1, size - overlap);
        for (int i = 0; i < text.length(); i += step) {
            list.add(text.substring(i, Math.min(i + size, text.length())).trim());
            if (i + size >= text.length()) {
                break;
            }
        }
        return list.stream().filter(s -> !s.isBlank()).toList();
    }
}
