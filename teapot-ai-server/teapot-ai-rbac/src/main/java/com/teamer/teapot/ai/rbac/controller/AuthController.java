package com.teamer.teapot.ai.rbac.controller;

import com.teamer.teapot.ai.common.model.Result;
import com.teamer.teapot.ai.rbac.model.dto.LoginRequest;
import com.teamer.teapot.ai.rbac.model.dto.LoginResponse;
import com.teamer.teapot.ai.rbac.model.dto.RefreshRequest;
import com.teamer.teapot.ai.rbac.service.UserService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 认证接口（SPEC §5.3 / §5.4）。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return Result.ok(userService.login(request));
    }

    @PostMapping("/refresh")
    public Result<Map<String, String>> refresh(@Valid @RequestBody RefreshRequest request) {
        return Result.ok(Map.of("accessToken", userService.refresh(request)));
    }

    /** 一期不做服务端吊销，客户端丢弃 token 即可（SPEC §5.3） */
    @PostMapping("/logout")
    public Result<Void> logout() {
        return Result.ok();
    }
}
