package qg.qgent.harness.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import qg.qgent.common.web.ApiException;

/** 工具注册合同：schema 形状、并行标记、参数绑定、结果序列化与实例上下文。 */
class ToolRegistryTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void schemaMatchesLegacyShapeForEverySupportedParameterType() throws Exception {
        ToolCallback callback = callback(new Fixture(), "read_files");
        assertThat(json.readTree(callback.inputSchema()))
            .isEqualTo(
                json.readTree(
                    """
                    {
                      "$schema": "https://json-schema.org/draft/2020-12/schema",
                      "type": "object",
                      "properties": {
                        "path": {"type": "string", "description": "工作区相对文件路径"},
                        "offset": {"type": "integer", "description": "stdout 字节游标"},
                        "limit": {"type": "integer", "description": "返回数量"},
                        "replaceAll": {"type": "boolean", "description": "是否替换所有匹配"},
                        "command": {
                          "type": "array",
                          "items": {"type": "string"},
                          "description": "可执行文件和参数"
                        },
                        "body": {"type": "object", "description": "JSON 请求正文"},
                        "repositories": {
                          "type": "array",
                          "items": {"type": "object"},
                          "description": "任务涉及的仓库"
                        }
                      },
                      "required": ["path", "offset", "command", "body", "repositories"],
                      "additionalProperties": false
                    }
                    """));
    }

    @Test
    void toolWithoutParametersKeepsEmptyPropertiesAndRequired() throws Exception {
        ToolCallback callback = callback(new Fixture(), "list_workspaces");
        assertThat(json.readTree(callback.inputSchema()))
            .isEqualTo(
                json.readTree(
                    """
                    {
                      "$schema": "https://json-schema.org/draft/2020-12/schema",
                      "type": "object",
                      "properties": {},
                      "required": [],
                      "additionalProperties": false
                    }
                    """));
    }

    @Test
    void parallelToolIsMarkedAndOthersAreNot() {
        ToolRegistry registry = registry(new Fixture());
        assertThat(registry.callbacks()).hasSize(4);
        assertThat(registry.callbacks()).filteredOn(ToolCallback::parallel)
            .extracting(ToolCallback::name).containsExactly("read_files");
    }

    @Test
    void callBindsArgumentsByParameterName() throws Exception {
        JsonNode result =
            json.readTree(
                callback(new Fixture(), "read_files")
                    .call(
                        """
                        {
                          "path": "a.txt",
                          "offset": 9007199254740993,
                          "limit": 5,
                          "replaceAll": true,
                          "command": ["git", "status"],
                          "body": {"state": "open"},
                          "repositories": [{"providerRepositoryId": "42", "branch": "main"}]
                        }
                        """));
        assertThat(result.get("owner").asText()).isEqualTo("instance");
        assertThat(result.get("path").asText()).isEqualTo("a.txt");
        assertThat(result.get("offset").asLong()).isEqualTo(9007199254740993L);
        assertThat(result.get("limit").asInt()).isEqualTo(5);
        assertThat(result.get("replaceAll").asBoolean()).isTrue();
        assertThat(result.get("command")).isEqualTo(json.readTree("[\"git\", \"status\"]"));
        assertThat(result.get("body")).isEqualTo(json.readTree("{\"state\": \"open\"}"));
        assertThat(result.get("repositories"))
            .isEqualTo(json.readTree("[{\"providerRepositoryId\": \"42\", \"branch\": \"main\"}]"));
    }

    @Test
    void callTreatsMissingOptionalArgumentsAsNull() throws Exception {
        JsonNode result =
            json.readTree(
                callback(new Fixture(), "read_files")
                    .call(
                        """
                        {
                          "path": "a.txt",
                          "offset": 0,
                          "command": [],
                          "body": {},
                          "repositories": []
                        }
                        """));
        assertThat(result.get("limit").isNull()).isTrue();
        assertThat(result.get("replaceAll").isNull()).isTrue();
    }

    @Test
    void callSerializesResultAndKeepsLongIdAsString() throws Exception {
        JsonNode result =
            json.readTree(
                callback(new Fixture(), "read_chat_message")
                    .call("{\"messageId\": \"9007199254740993\"}"));
        assertThat(result.get("id").isTextual()).isTrue();
        assertThat(result.get("id").asText()).isEqualTo("9007199254740993");
        assertThat(result.get("sequence").asLong()).isEqualTo(9L);
        assertThat(result.get("replyToId").isNull()).isTrue();
    }

    @Test
    void callUsesTheRegisteredInstance() throws Exception {
        JsonNode result = json.readTree(callback(new Fixture("甲"), "list_workspaces").call("{}"));
        assertThat(result.get("owner").asText()).isEqualTo("甲");
    }

    @Test
    void callRejectsJsonNullArguments() {
        assertThatThrownBy(() -> callback(new Fixture(), "list_workspaces").call("null"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("工具参数不是 JSON 对象");
    }

    @Test
    void callRethrowsToolExceptionWithItsCode() {
        Throwable failure =
            catchThrowable(() -> callback(new Fixture(), "reject").call("{}"));
        assertThat(failure).isInstanceOf(ApiException.class);
        assertThat(((ApiException) failure).code()).isEqualTo("APPLICATION_STATE_CHANGED");
    }

    @Test
    void duplicateToolNameFailsRegistration() {
        assertThatThrownBy(() -> registry(new Fixture("甲"), new Fixture("乙")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageStartingWith("重复的工具名：");
    }

    private ToolRegistry registry(Object... instances) {
        ToolRegistry registry = new ToolRegistry(json);
        registry.register(instances);
        return registry;
    }

    private ToolCallback callback(Object instance, String name) {
        return registry(instance).callbacks().stream()
            .filter(callback -> name.equals(callback.name()))
            .findFirst().orElseThrow();
    }

    record Message(String id, Long sequence, String replyToId) {}

    private static final class Fixture {
        private final String owner;

        Fixture() {
            this("instance");
        }

        Fixture(String owner) {
            this.owner = owner;
        }

        @Tool(name = "list_workspaces", description = "列出当前项目的 Workspace")
        public ToolResult listWorkspaces() {
            ToolResult result = ToolResult.ok();
            result.put("owner", owner);
            return result;
        }

        @ParallelTool
        @Tool(name = "read_files", description = "读取文件")
        public ToolResult readFiles(
            @ToolParam(description = "工作区相对文件路径") String path,
            @ToolParam(description = "stdout 字节游标") Long offset,
            @ToolParam(description = "返回数量", required = false) Integer limit,
            @ToolParam(description = "是否替换所有匹配", required = false) Boolean replaceAll,
            @ToolParam(description = "可执行文件和参数") List<String> command,
            @ToolParam(description = "JSON 请求正文") Map<String, Object> body,
            @ToolParam(description = "任务涉及的仓库") List<Map<String, Object>> repositories) {
            ToolResult result = ToolResult.ok();
            result.put("owner", owner);
            result.put("path", path);
            result.put("offset", offset);
            result.put("limit", limit);
            result.put("replaceAll", replaceAll);
            result.put("command", command);
            result.put("body", body);
            result.put("repositories", repositories);
            return result;
        }

        @Tool(name = "read_chat_message", description = "读取消息")
        public Message readChatMessage(@ToolParam(description = "消息 ID") String messageId) {
            return new Message(messageId, 9L, null);
        }

        @Tool(name = "reject", description = "拒绝申请")
        public ToolResult reject() {
            throw new ApiException(HttpStatus.CONFLICT, "APPLICATION_STATE_CHANGED", "申请已处理");
        }
    }
}
