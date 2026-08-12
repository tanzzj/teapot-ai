package com.teamer.teapot.ai.rbac.service;

import com.auth0.jwt.interfaces.DecodedJWT;
import com.teamer.teapot.ai.common.exception.BizException;
import com.teamer.teapot.ai.common.model.PageData;
import com.teamer.teapot.ai.common.model.Result;
import com.teamer.teapot.ai.rbac.context.ContextUtil;
import com.teamer.teapot.ai.rbac.dao.UserMapper;
import com.teamer.teapot.ai.rbac.model.TeapotUser;
import com.teamer.teapot.ai.rbac.model.dto.LoginRequest;
import com.teamer.teapot.ai.rbac.model.dto.LoginResponse;
import com.teamer.teapot.ai.rbac.model.dto.RefreshRequest;
import com.teamer.teapot.ai.rbac.model.dto.UserCreateRequest;
import com.teamer.teapot.ai.rbac.model.dto.UserUpdateRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 用户与认证服务（SPEC §5）。
 */
@Service
public class UserService {

    /** 种子管理员初始密码（sql/V2，SPEC §10.4），仅用于登录时的改密提示判断 */
    private static final String SEED_DEFAULT_PASSWORD = "Teapot@2026";
    private static final Set<String> VALID_ROLES = Set.of("admin", "developer", "viewer");
    private static final String LOGIN_FAIL_MSG = "账号名或密码错误";

    private final UserMapper userMapper;
    private final JwtService jwtService;
    private final LoginAttemptLimiter limiter;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public UserService(UserMapper userMapper, JwtService jwtService, LoginAttemptLimiter limiter) {
        this.userMapper = userMapper;
        this.jwtService = jwtService;
        this.limiter = limiter;
    }

    // ---------- 认证（SPEC §5.3） ----------

    public LoginResponse login(LoginRequest request) {
        String username = request.getUsername();
        if (limiter.isLocked(username)) {
            throw new BizException(Result.CODE_UNAUTHORIZED, "失败次数过多，请 10 分钟后再试");
        }
        TeapotUser user = userMapper.selectByUsername(username);
        if (user == null || user.getStatus() == null || user.getStatus() != 1
                || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            // 统一文案，不区分"用户不存在/密码错误"（SPEC §14 第 2 条）
            limiter.recordFailure(username);
            throw new BizException(Result.CODE_UNAUTHORIZED, LOGIN_FAIL_MSG);
        }
        limiter.reset(username);
        String access = jwtService.issue(user, JwtService.TYPE_ACCESS);
        String refresh = jwtService.issue(user, JwtService.TYPE_REFRESH);
        boolean usingDefault = passwordEncoder.matches(SEED_DEFAULT_PASSWORD, user.getPassword());
        return LoginResponse.of(access, refresh, user, usingDefault);
    }

    public String refresh(RefreshRequest request) {
        DecodedJWT jwt;
        try {
            jwt = jwtService.verify(request.getRefreshToken());
        } catch (Exception e) {
            throw new BizException(Result.CODE_UNAUTHORIZED, "refreshToken 无效或已过期");
        }
        if (!JwtService.isType(jwt, JwtService.TYPE_REFRESH)) {
            throw new BizException(Result.CODE_UNAUTHORIZED, "token 类型错误");
        }
        TeapotUser user = userMapper.selectByUserId(jwt.getClaim("uid").asString());
        if (user == null || user.getStatus() == null || user.getStatus() != 1) {
            throw new BizException(Result.CODE_UNAUTHORIZED, "用户不存在或已停用");
        }
        return jwtService.issue(user, JwtService.TYPE_ACCESS);
    }

    // ---------- 用户管理（SPEC §5.4） ----------

    public TeapotUser profile() {
        String userId = ContextUtil.currentUserId();
        TeapotUser user = userMapper.selectByUserId(userId);
        if (user == null) {
            throw new BizException(Result.CODE_UNAUTHORIZED, "用户不存在");
        }
        return user;
    }

    public PageData<TeapotUser> list(int page, int size) {
        page = Math.max(page, 1);
        size = Math.min(Math.max(size, 1), 100);
        long total = userMapper.countAll();
        List<TeapotUser> list = userMapper.selectPage((page - 1) * size, size);
        return PageData.of(total, list);
    }

    public TeapotUser create(UserCreateRequest request) {
        validateRoles(request.getRoles());
        if (userMapper.selectByUsername(request.getUsername()) != null) {
            throw new BizException("登录名已存在");
        }
        if (userMapper.selectByUserId(request.getUserId()) != null) {
            throw new BizException("用户ID已存在");
        }
        TeapotUser user = new TeapotUser();
        user.setUserId(request.getUserId());
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setRealName(request.getRealName());
        user.setMobile(request.getMobile());
        user.setEmail(request.getEmail());
        user.setRoles(normalizeRoles(request.getRoles()));
        user.setStatus(1);
        userMapper.insert(user);
        return user;
    }

    public TeapotUser update(String userId, UserUpdateRequest request) {
        TeapotUser user = requireUser(userId);
        TeapotUser patch = new TeapotUser();
        patch.setUserId(userId);
        patch.setRealName(request.getRealName());
        patch.setMobile(request.getMobile());
        patch.setEmail(request.getEmail());
        if (request.getRoles() != null) {
            validateRoles(request.getRoles());
            patch.setRoles(normalizeRoles(request.getRoles()));
        }
        if (request.getStatus() != null) {
            if (request.getStatus() != 0 && request.getStatus() != 1) {
                throw new BizException("status 仅允许 0/1");
            }
            if (request.getStatus() == 0 && userId.equals(ContextUtil.currentUserId())) {
                throw new BizException("不能停用自己");
            }
            patch.setStatus(request.getStatus());
        }
        if (request.getNewPassword() != null) {
            if (request.getNewPassword().length() < 8 || request.getNewPassword().length() > 64) {
                throw new BizException("密码长度 8-64 位");
            }
            patch.setPassword(passwordEncoder.encode(request.getNewPassword()));
        }
        if (patch.getRealName() == null && patch.getMobile() == null && patch.getEmail() == null
                && patch.getRoles() == null && patch.getStatus() == null && patch.getPassword() == null) {
            // 空补丁直接返回，避免拼出无 SET 子句的无效 UPDATE
            return requireUser(userId);
        }
        userMapper.update(patch);
        return requireUser(userId);
    }

    /** 停用用户（一期不做物理删除，SPEC §5.4） */
    public void disable(String userId) {
        TeapotUser user = requireUser(userId);
        if (user.getUserId().equals(ContextUtil.currentUserId())) {
            throw new BizException("不能停用自己");
        }
        TeapotUser patch = new TeapotUser();
        patch.setUserId(userId);
        patch.setStatus(0);
        userMapper.update(patch);
    }

    // ---------- 内部 ----------

    private TeapotUser requireUser(String userId) {
        TeapotUser user = userMapper.selectByUserId(userId);
        if (user == null) {
            throw new BizException("用户不存在");
        }
        return user;
    }

    private void validateRoles(String roles) {
        for (String role : roles.split(",")) {
            if (!VALID_ROLES.contains(role.trim())) {
                throw new BizException("非法角色：" + role.trim());
            }
        }
    }

    private String normalizeRoles(String roles) {
        return Arrays.stream(roles.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .distinct().collect(Collectors.joining(","));
    }
}
