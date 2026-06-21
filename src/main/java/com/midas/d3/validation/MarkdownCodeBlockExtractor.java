package com.midas.d3.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts raw source from LLM responses wrapped in markdown code fences.
 */
public final class MarkdownCodeBlockExtractor {

    private static final Pattern MARKDOWN_FENCE = Pattern.compile(
            "```([a-zA-Z0-9_.\\- ]+)?\\s*\\r?\\n?([\\s\\S]*?)\\r?\\n?```",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    public record CodeBlock(String language, String content) {}

    private MarkdownCodeBlockExtractor() {}

    /**
     * Returns every non-blank fenced block in document order.
     */
    public static List<CodeBlock> extractAll(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<CodeBlock> blocks = new ArrayList<>();
        Matcher matcher = MARKDOWN_FENCE.matcher(text);
        while (matcher.find()) {
            String language = matcher.group(1);
            String content = matcher.group(2);
            if (content == null) {
                continue;
            }
            String stripped = content.strip();
            if (stripped.isBlank()) {
                continue;
            }
            blocks.add(new CodeBlock(language != null ? language.strip() : "", stripped));
        }
        return blocks;
    }

    public static String extract(String text) {
        Matcher matcher = MARKDOWN_FENCE.matcher(text);
        String fallback = null;
        while (matcher.find()) {
            String extracted = matcher.group(2);
            if (extracted == null) {
                continue;
            }
            String stripped = extracted.strip();
            if (stripped.isBlank()) {
                continue;
            }
            if (!stripped.startsWith("{")) {
                return stripped;
            }
            if (fallback == null) {
                fallback = stripped;
            }
        }
        return fallback;
    }
}
