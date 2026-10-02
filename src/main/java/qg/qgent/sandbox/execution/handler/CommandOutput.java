package qg.qgent.sandbox.execution.handler;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 命令输出存于持久工作区，模型只读取有界窗口或诊断尾部。 */
public final class CommandOutput {
    private CommandOutput() { }
    public static Path directory(Path workspace, UUID id) {
        return workspace.getParent().resolve(".qgents-output").resolve(workspace.getFileName()).resolve(id.toString());
    }
    public static Map<String, Object> read(Path directory, String stream, long offset, int maxBytes) {
        Path file = directory.resolve(stream);
        if (!Files.exists(file)) return Map.of("text", "", "nextOffset", offset, "truncated", false);
        try (var input = java.nio.channels.FileChannel.open(file)) {
            long size = input.size();
            input.position(Math.min(offset, size));
            var buffer = java.nio.ByteBuffer.allocate((int) Math.min(maxBytes, Math.max(0, size - offset)));
            while (buffer.hasRemaining() && input.read(buffer) != -1) { }
            buffer.flip();
            var text = java.nio.CharBuffer.allocate(buffer.remaining());
            var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE);
            decoder.decode(buffer, text, false);
            long next = offset + buffer.position();
            text.flip();
            return Map.of("text", text.toString(), "nextOffset", next, "truncated", next < size);
        } catch (java.io.IOException exception) { throw new IllegalStateException("读取命令输出失败", exception); }
    }
    public static Map<String, Object> tail(Path directory, String stream) {
        Path file = directory.resolve(stream);
        if (!Files.exists(file)) return Map.of("text", "", "truncated", false);
        try (var input = java.nio.channels.FileChannel.open(file)) {
            long size = input.size();
            long offset = Math.max(0, size - 16000);
            input.position(offset);
            var buffer = java.nio.ByteBuffer.allocate((int) Math.min(size, 16000));
            while (buffer.hasRemaining() && input.read(buffer) != -1) { }
            buffer.flip();
            if (offset > 0) while (buffer.hasRemaining() && (buffer.get(buffer.position()) & 0xc0) == 0x80) buffer.get();
            return Map.of("text", StandardCharsets.UTF_8.decode(buffer).toString(), "truncated", offset > 0);
        } catch (java.io.IOException exception) { throw new IllegalStateException("读取命令输出失败", exception); }
    }
    public static Map<String, Object> redact(Map<String, Object> value, Map<String, String> environment) {
        Map<String, Object> result = new LinkedHashMap<>(value);
        String text = (String) result.getOrDefault("text", "");
        for (String secret : environment.values()) if (secret != null && secret.length() >= 8) text = text.replace(secret, "[redacted]");
        result.put("text", text);
        return result;
    }
}
