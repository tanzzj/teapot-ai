package com.teamer.teapot.ai.rbac.context;

import com.teamer.teapot.ai.rbac.model.TeapotUser;

/**
 * 当前用户上下文（保留老 teapot ContextUtil 的 ThreadLocal 语义，SPEC §5.1 第 4 条）。
 * 在过滤器链首尾 setUp/cleanUp；业务代码通过 getUserFromContext() 获取当前用户。
 */
public final class ContextUtil {

    private static final ThreadLocal<TeapotUser> USER_HOLDER = new ThreadLocal<>();

    private ContextUtil() {
    }

    public static void setUp(TeapotUser user) {
        USER_HOLDER.set(user);
    }

    public static TeapotUser getUserFromContext() {
        return USER_HOLDER.get();
    }

    public static String currentUserId() {
        TeapotUser user = USER_HOLDER.get();
        return user == null ? null : user.getUserId();
    }

    public static void cleanUp() {
        USER_HOLDER.remove();
    }
}
