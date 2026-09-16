package com.vca.store;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code MyBatisSupport} 没有包扫描, 每个 Mapper 都要手工 {@code addMapper} 登记一行。
 *
 * <p>漏登记是个<b>沉默</b>的错误: 编译过、单测过、打包过, 只在真正连库启动时炸成
 * "Type interface … is not known to the MybatisPlusMapperRegistry", 整个服务起不来 ——
 * 一次线上事故就是这么来的(新增 UserVoiceCloneMapper 时漏了)。
 *
 * <p>这里用"mapper 包下的接口"与"源码里的登记行"对账, 把这个错误提前到构建期。
 */
class MapperRegistrationTest {

    @Test
    void 每个mapper都已登记到SqlSessionFactory() {
        String source = readSource();
        List<String> mappers = mapperInterfaceNames();

        assertThat(mappers).as("应当扫描到 mapper 接口").isNotEmpty();
        for (String name : mappers) {
            assertThat(source)
                    .as("新增的 %s 必须在 MyBatisSupport 里 addMapper 登记一行, "
                            + "否则服务连库启动时会直接失败", name)
                    .contains("addMapper(" + name + ".class)");
        }
    }

    /** 读 MyBatisSupport 源码而不是反射它的私有状态: 登记本身就是源码里的一串调用。 */
    private static String readSource() {
        Path path = Path.of("src/main/java/com/vca/store/MyBatisSupport.java");
        if (!Files.exists(path)) {
            path = Path.of("vca-store/src/main/java/com/vca/store/MyBatisSupport.java");
        }
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 列出 com.vca.store.mapper 包下的所有 *Mapper 接口(按 class 文件名, 不加载类)。 */
    private static List<String> mapperInterfaceNames() {
        URL url = MapperRegistrationTest.class.getResource("/com/vca/store/mapper");
        if (url == null) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(Path.of(url.toURI()))) {
            return files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith("Mapper.class"))
                    .map(n -> n.substring(0, n.length() - ".class".length()))
                    .sorted()
                    .toList();
        } catch (Exception e) {
            throw new IllegalStateException("扫描 mapper 包失败", e);
        }
    }
}
