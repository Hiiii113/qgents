package qg.qgent.support;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * 单测用的 MyBatis 配置：加载与生产相同的 XML 映射文件，并开启下划线转驼峰的全局映射。
 */
public final class MapperTestSupport {

    private MapperTestSupport() {
    }

    public static MybatisConfiguration configuration(Class<?>... mappers) {
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        for (var mapper : mappers) {
            configuration.addMapper(mapper);
        }
        for (var mapper : mappers) {
            loadXml(configuration, mapper);
        }
        return configuration;
    }

    private static void loadXml(MybatisConfiguration configuration, Class<?> mapper) {
        var resource = "mapper/" + mapper.getSimpleName() + ".xml";
        var loader = MapperTestSupport.class.getClassLoader();
        try (InputStream input = loader.getResourceAsStream(resource)) {
            if (input == null) {
                return;
            }
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        } catch (IOException e) {
            throw new UncheckedIOException("无法加载 Mapper XML: " + resource, e);
        }
    }
}
