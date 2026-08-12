package com.teamer.teapot.ai.rbac.service;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.teamer.teapot.ai.rbac.config.RbacProperties;
import com.teamer.teapot.ai.rbac.model.TeapotUser;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Date;

/**
 * JWT 签发与校验（auth0 java-jwt，SPEC §3 / §5.3）。
 * claim：typ(access|refresh)、uid、uname、roles。
 */
@Service
public class JwtService {

    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";

    private final Algorithm algorithm;
    private final JWTVerifier verifier;
    private final RbacProperties.Jwt jwtProps;

    public JwtService(RbacProperties properties) {
        this.jwtProps = properties.getJwt();
        this.algorithm = Algorithm.HMAC256(jwtProps.getSecret());
        this.verifier = JWT.require(algorithm).build();
    }

    public String issue(TeapotUser user, String type) {
        Instant now = Instant.now();
        Instant exp = type.equals(TYPE_ACCESS)
                ? now.plus(jwtProps.getAccessTokenTtl())
                : now.plus(jwtProps.getRefreshTokenTtl());
        return JWT.create()
                .withClaim("typ", type)
                .withClaim("uid", user.getUserId())
                .withClaim("uname", user.getUsername())
                .withClaim("roles", user.getRoles())
                .withIssuedAt(Date.from(now))
                .withExpiresAt(Date.from(exp))
                .withIssuer("teapot-ai")
                .sign(algorithm);
    }

    /**
     * 校验并返回已验证的 JWT；签名/过期失败抛 JWTVerificationException。
     */
    public DecodedJWT verify(String token) throws JWTVerificationException {
        return verifier.verify(token);
    }

    public static boolean isType(DecodedJWT jwt, String type) {
        return type.equals(jwt.getClaim("typ").asString());
    }
}
