package com.vca.bootstrap;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;

/**
 * 两个后台的入口: {@code /admin}(运营)与 {@code /merchant}(商家)。页面是 static/admin/、static/merchant/ 下的
 * 单页应用, 与语音助手首页互不相干。WebFlux 的静态资源不会把目录映射到 index.html, 所以这里显式指一下。
 *
 * <p>入口页不缓存: 每次发布后打开就是新版本, 不用让商家"强制刷新"。
 */
@Configuration(proxyBeanMethods = false)
public class ConsoleRoutes {

    @Bean
    RouterFunction<ServerResponse> consoleEntryRoutes() {
        return RouterFunctions.route(GET("/admin").or(GET("/admin/")), req -> page("static/admin/index.html"))
                .andRoute(GET("/merchant").or(GET("/merchant/")), req -> page("static/merchant/index.html"));
    }

    private static reactor.core.publisher.Mono<ServerResponse> page(String path) {
        return ServerResponse.ok()
                .contentType(MediaType.TEXT_HTML)
                .cacheControl(CacheControl.noCache())
                .bodyValue(new ClassPathResource(path));
    }
}
