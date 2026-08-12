package com.teamer.teapot.ai.rbac.util;

import java.util.regex.Pattern;

/**
 * URI 通配匹配（保留老 teapot ValidationUtil.stringMatcher 语义，SPEC §5.1 第 2 条）：
 * pattern 中的 `*` 匹配任意字符（跨路径段），整体锚定匹配；如 `/api/agent/*`、`/*`。
 */
public final class RbacPathMatcher {

    private RbacPathMatcher() {
    }

    public static boolean matches(String pattern, String uri) {
        if (pattern == null || uri == null) {
            return false;
        }
        if (pattern.equals(uri)) {
            return true;
        }
        if (!pattern.contains("*")) {
            return false;
        }
        StringBuilder regex = new StringBuilder();
        for (char c : pattern.toCharArray()) {
            if (c == '*') {
                regex.append(".*");
            } else if ("\\.[]{}()^$?+|".indexOf(c) >= 0) {
                regex.append('\\').append(c);
            } else {
                regex.append(c);
            }
        }
        return Pattern.matches(regex.toString(), uri);
    }
}
