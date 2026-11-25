package qg.qgent.common.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 全局 Jackson 与时钟配置：UTC 时间序列化、全应用共用的 ObjectMapper 和 UTC Clock。
 */
@Configuration
public class JacksonTimeConfiguration {

    /**
     * LocalDateTime → 带 Z 的 UTC ISO8601（如 {@code 2026-08-18T15:26:31.990054Z}）。
     * 全局 ObjectMapper 注册本模块，所有接口响应统一生效。
     */
    @Bean
    SimpleModule utcLocalDateTimeModule() {
        SimpleModule module = new SimpleModule("qgents-utc-localdatetime");
        module.addSerializer(
            LocalDateTime.class,
            new JsonSerializer<LocalDateTime>() {
                @Override
                public void serialize(
                    LocalDateTime value,
                    JsonGenerator generator,
                    SerializerProvider serializers)
                    throws IOException {
                    generator.writeString(value.toInstant(ZoneOffset.UTC).toString());
                }
            });
        return module;
    }

    /**
     * 全局 ObjectMapper：注册 JDK8/JSR310 等模块与 UTC 时间序列化模块。
     */
    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    ObjectMapper objectMapper(com.fasterxml.jackson.databind.Module utcLocalDateTimeModule) {
        return new ObjectMapper().findAndRegisterModules().registerModule(utcLocalDateTimeModule);
    }

    /**
     * 统一 UTC 时钟。
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
