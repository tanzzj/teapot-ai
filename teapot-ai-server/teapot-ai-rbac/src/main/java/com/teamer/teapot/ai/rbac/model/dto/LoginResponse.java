package com.teamer.teapot.ai.rbac.model.dto;

import com.teamer.teapot.ai.rbac.model.TeapotUser;
import lombok.Data;

import java.util.List;

/**
 * 登录响应（SPEC §5.3）：accessToken 2h + refreshToken 7d。
 * usingDefaultPassword=true 时前端提示首次登录改密（SPEC §10.4）。
 */
@Data
public class LoginResponse {

    private String accessToken;
    private String refreshToken;
    private TeapotUser user;
    private boolean usingDefaultPassword;

    public static LoginResponse of(String accessToken, String refreshToken, TeapotUser user,
                                   boolean usingDefaultPassword) {
        LoginResponse resp = new LoginResponse();
        resp.setAccessToken(accessToken);
        resp.setRefreshToken(refreshToken);
        resp.setUser(user);
        resp.setUsingDefaultPassword(usingDefaultPassword);
        return resp;
    }
}
