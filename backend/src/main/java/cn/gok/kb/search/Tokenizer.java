package cn.gok.kb.search;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 中英文混排分词，仅供关键词检索使用。
 *
 * <p>中文没有空格，用一个成熟度足够又零依赖的做法：**二元组（bigram）**。
 * 「事务传播」-> 事务 / 务传 / 传播。这样「传播行为」能命中「事务传播行为」，
 * 比单字切分精准，比引入 HanLP / jieba 轻。
 *
 * <p>英文和数字按连续字母数字切，转小写。
 */
@Component
public class Tokenizer {

    /** 高频虚词与疑问词，检索时无区分度，直接丢掉 */
    private static final Set<String> STOP_WORDS = Set.of(
            "的", "了", "是", "在", "和", "与", "或", "有", "就", "都", "而", "及",
            "什么", "怎么", "哪些", "哪个", "多少", "可以", "是否", "如何",
            "为什", "么样", "一下", "这个", "那个", "我们", "你们", "请问", "能否",
            "the", "a", "an", "is", "are", "was", "were", "of", "to", "in", "on",
            "and", "or", "for", "with", "as", "at", "by", "it", "this", "that"
    );

    public List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }

        StringBuilder ascii = new StringBuilder();
        StringBuilder cjk = new StringBuilder();

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                flushAscii(ascii, tokens);
                cjk.append(c);
            } else if (Character.isLetterOrDigit(c)) {
                flushCjk(cjk, tokens);
                ascii.append(Character.toLowerCase(c));
            } else {
                flushAscii(ascii, tokens);
                flushCjk(cjk, tokens);
            }
        }
        flushAscii(ascii, tokens);
        flushCjk(cjk, tokens);
        return tokens;
    }

    private void flushAscii(StringBuilder sb, List<String> out) {
        if (sb.isEmpty()) {
            return;
        }
        String word = sb.toString();
        sb.setLength(0);
        if (word.length() > 1 || Character.isDigit(word.charAt(0))) {
            if (!STOP_WORDS.contains(word)) {
                out.add(word);
            }
        }
    }

    private void flushCjk(StringBuilder sb, List<String> out) {
        if (sb.isEmpty()) {
            return;
        }
        String seg = sb.toString();
        sb.setLength(0);
        if (seg.length() == 1) {
            if (!STOP_WORDS.contains(seg)) {
                out.add(seg);
            }
            return;
        }
        for (int i = 0; i < seg.length() - 1; i++) {
            String bigram = seg.substring(i, i + 2);
            if (!STOP_WORDS.contains(bigram)) {
                out.add(bigram);
            }
        }
    }

    private boolean isCjk(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }
}
