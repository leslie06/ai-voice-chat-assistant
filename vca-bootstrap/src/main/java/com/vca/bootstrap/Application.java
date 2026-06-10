package com.vca.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 应用入口。各功能模块(gateway/web/deepseek)通过各自的 AutoConfiguration 装配,
 * 本模块只负责组装 + 提供 dev 桩 provider(com.vca.bootstrap.dev, 受组件扫描)。
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
