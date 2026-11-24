package qg.qgent.harness.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把工具实例上带 {@link Tool} 的方法登记成工具表；注册顺序就是模型看到的工具顺序。
 * 重名或参数类型不支持时注册即失败。
 */
public final class ToolRegistry {
    private static final String JSON_SCHEMA = "https://json-schema.org/draft/2020-12/schema";

    private final ObjectMapper json;
    private final Map<String, ToolCallback> callbacks = new LinkedHashMap<>();

    public ToolRegistry(ObjectMapper json) {
        this.json = json;
    }

    public void register(Object... instances) {
        for (Object instance : instances) add(instance);
    }

    public List<ToolCallback> callbacks() {
        return List.copyOf(callbacks.values());
    }

    private void add(Object instance) {
        for (Method method : instance.getClass().getDeclaredMethods()) {
            Tool tool = method.getAnnotation(Tool.class);
            if (tool == null) continue;
            method.trySetAccessible();
            ToolCallback callback =
                new ToolCallback(
                    tool.name(),
                    tool.description(),
                    schema(method),
                    method.isAnnotationPresent(ParallelTool.class),
                    arguments -> invoke(json, method, instance, arguments));
            if (callbacks.putIfAbsent(tool.name(), callback) != null)
                throw new IllegalStateException("重复的工具名：" + tool.name());
        }
    }

    // 形状与旧实现一致：$schema、type、properties、required（无参数时为空数组）、additionalProperties。
    private String schema(Method method) {
        ObjectNode schema = json.createObjectNode();
        schema.put("$schema", JSON_SCHEMA);
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = schema.putArray("required");
        for (Parameter parameter : method.getParameters()) {
            ObjectNode property = properties.putObject(parameter.getName());
            propertyType(property, parameter.getParameterizedType());
            ToolParam toolParam = parameter.getAnnotation(ToolParam.class);
            if (toolParam == null || toolParam.required()) required.add(parameter.getName());
            if (toolParam != null && !toolParam.description().isBlank())
                property.put("description", toolParam.description());
        }
        schema.put("additionalProperties", false);
        return schema.toString();
    }

    private static void propertyType(ObjectNode property, Type type) {
        if (type == String.class) property.put("type", "string");
        else if (type == Integer.class || type == int.class || type == Long.class || type == long.class)
            property.put("type", "integer");
        else if (type == Boolean.class || type == boolean.class) property.put("type", "boolean");
        else if (type instanceof ParameterizedType generic && generic.getRawType() == List.class) {
            property.put("type", "array");
            propertyType(property.putObject("items"), generic.getActualTypeArguments()[0]);
        } else if (type instanceof ParameterizedType generic && generic.getRawType() == Map.class) {
            property.put("type", "object");
        } else throw new IllegalArgumentException("不支持的参数类型：" + type.getTypeName());
    }

    private static String invoke(ObjectMapper json, Method method, Object target, String arguments) {
        Object[] values = argumentValues(json, method, arguments);
        Object result;
        try {
            result = method.invoke(target, values);
        } catch (InvocationTargetException exception) {
            // 业务异常原样抛出，调用方才能按错误码处置
            throw rethrow(exception.getCause());
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("无法调用工具 " + method.getName(), exception);
        }
        try {
            return json.writeValueAsString(result);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法编码工具结果 " + method.getName(), exception);
        }
    }

    private static Object[] argumentValues(ObjectMapper json, Method method, String arguments) {
        Map<String, Object> input;
        try {
            input = json.readValue(arguments, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("工具参数不是 JSON 对象：" + method.getName(), exception);
        }
        if (input == null)
            throw new IllegalArgumentException("工具参数不是 JSON 对象：" + method.getName());
        Parameter[] parameters = method.getParameters();
        Object[] values = new Object[parameters.length];
        for (int index = 0; index < parameters.length; index++) {
            Object value = input.get(parameters[index].getName());
            values[index] =
                value == null
                    ? null
                    : json.convertValue(
                        value,
                        json.getTypeFactory().constructType(parameters[index].getParameterizedType()));
        }
        return values;
    }

    private static RuntimeException rethrow(Throwable cause) {
        if (cause instanceof RuntimeException exception) return exception;
        if (cause instanceof Error error) throw error;
        return new IllegalStateException(cause.getMessage(), cause);
    }
}
