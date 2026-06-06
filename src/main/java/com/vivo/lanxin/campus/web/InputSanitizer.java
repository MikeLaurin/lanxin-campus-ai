package com.vivo.lanxin.campus.web;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class InputSanitizer {
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\p{Cntrl}&&[^\r\n\t]]");
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]*>");
    private static final Pattern SCRIPT_BLOCK = Pattern.compile("(?is)<script.*?>.*?</script>");

    private InputSanitizer() {
    }

    public static String clean(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String cleaned = SCRIPT_BLOCK.matcher(value).replaceAll("");
        cleaned = HTML_TAG.matcher(cleaned).replaceAll("");
        cleaned = CONTROL_CHARS.matcher(cleaned).replaceAll("");
        cleaned = cleaned.trim();
        if (cleaned.length() > maxLength) {
            return cleaned.substring(0, maxLength);
        }
        return cleaned;
    }

    public static String nullable(String value, int maxLength) {
        String cleaned = clean(value, maxLength);
        return cleaned.isBlank() ? null : cleaned;
    }

    public static List<String> cleanList(List<String> values, int itemMaxLength, int maxItems) {
        if (values == null) {
            return new ArrayList<>();
        }
        return values.stream()
                .limit(maxItems)
                .map(item -> clean(item, itemMaxLength))
                .filter(item -> !item.isBlank())
                .collect(Collectors.toCollection(ArrayList::new));
    }
}
