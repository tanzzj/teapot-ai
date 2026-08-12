package com.teamer.teapot.ai.rbac.controller;

import com.teamer.teapot.ai.common.model.PageData;
import com.teamer.teapot.ai.common.model.Result;
import com.teamer.teapot.ai.rbac.model.TeapotUser;
import com.teamer.teapot.ai.rbac.model.dto.UserCreateRequest;
import com.teamer.teapot.ai.rbac.model.dto.UserUpdateRequest;
import com.teamer.teapot.ai.rbac.service.UserService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户管理接口（SPEC §5.4；权限由 RbacAccessFilter 按 resource-list 控制）。
 */
@RestController
@RequestMapping("/api/user")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/profile")
    public Result<TeapotUser> profile() {
        return Result.ok(userService.profile());
    }

    @GetMapping("/list")
    public Result<PageData<TeapotUser>> list(@RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        return Result.ok(userService.list(page, size));
    }

    @PostMapping("/create")
    public Result<TeapotUser> create(@Valid @RequestBody UserCreateRequest request) {
        return Result.ok(userService.create(request));
    }

    @PutMapping("/{userId}")
    public Result<TeapotUser> update(@PathVariable String userId,
                                     @RequestBody UserUpdateRequest request) {
        return Result.ok(userService.update(userId, request));
    }

    /** 停用用户（一期不做物理删除） */
    @DeleteMapping("/{userId}")
    public Result<Void> disable(@PathVariable String userId) {
        userService.disable(userId);
        return Result.ok();
    }
}
