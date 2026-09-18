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
        // 必须抢在任何网络调用之前: macOS 的 JVM 启动时会把系统代理读成系统属性, 阿里云走代理
        // 只多一跳、还多一个单点故障(代理一关, 识别/合成全挂)。详见 SystemProxyBypass。
        SystemProxyBypass.apply();
        SpringApplication.run(Application.class, args);
    }
}
