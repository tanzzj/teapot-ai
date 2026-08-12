package com.teamer.teapot.ai;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Teapot AI 平台启动类（SPEC §4.2）。
 * 承担 Spring Boot 主类与 AG-UI starter 装配入口；配置见 application*.yml（SPEC §13）。
 */
@SpringBootApplication
@MapperScan("com.teamer.teapot.ai.**.dao")
public class TeapotAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(TeapotAiApplication.class, args);
    }
}
