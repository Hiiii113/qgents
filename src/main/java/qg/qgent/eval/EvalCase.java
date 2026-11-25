package qg.qgent.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import lombok.Data;
import org.yaml.snakeyaml.Yaml;

/**
 * 评测用例定义（从 evals/cases/*.yaml 加载），同时支持单轮/多轮记忆评测与本地代码任务评测。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class EvalCase {
    private String id;
    private String suite = "memory";
    private String description = "";
    private int repeat = 1;
    private int timeoutSeconds = 180;
    private String instruction;
    private List<Turn> turns = new ArrayList<>();
    private InitialState initialState = new InitialState();
    private List<CheckSpec> checks = new ArrayList<>();

    public List<Turn> effectiveTurns() {
        if (turns != null && !turns.isEmpty()) {
            return turns;
        }
        if (instruction != null && !instruction.isBlank()) {
            Turn turn = new Turn();
            turn.setInstruction(instruction);
            turn.setActorRole(initialState == null ? "PROJECT_ADMIN" : initialState.getActorRole());
            return List.of(turn);
        }
        return List.of();
    }

    public static List<EvalCase> loadAll(Path casesRoot, ObjectMapper json) throws IOException {
        if (!Files.exists(casesRoot)) {
            return List.of();
        }
        List<Path> files;
        try (Stream<Path> stream = Files.walk(casesRoot)) {
            files =
                stream
                    .filter(Files::isRegularFile)
                    .filter(
                        path -> {
                            String name = path.getFileName().toString().toLowerCase();
                            return name.endsWith(".yaml") || name.endsWith(".yml");
                        })
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
        Yaml yaml = new Yaml();
        List<EvalCase> loaded = new ArrayList<>();
        for (Path file : files) {
            Object raw = yaml.load(Files.readString(file));
            EvalCase evalCase = json.convertValue(raw, EvalCase.class);
            if (evalCase.getId() == null || evalCase.getId().isBlank()) {
                throw new IllegalArgumentException("评测用例缺少 id: " + file);
            }
            loaded.add(evalCase);
        }
        return loaded;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Turn {
        private String instruction;
        private String actorRole = "PROJECT_ADMIN";
        private boolean direct;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class InitialState {
        private String actorRole = "PROJECT_ADMIN";
        private String projectMemory;
        private String groupMemory;
        private String agentMemory;
        private List<HistoryMessage> groupHistory = new ArrayList<>();
        private List<OtherGroupSpec> otherGroups = new ArrayList<>();
        private CodingRepoSpec codingRepo;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class HistoryMessage {
        // USER / AGENT
        private String sender = "USER";
        private String text = "";
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OtherGroupSpec {
        private String name = "私密讨论群";
        private boolean requesterInGroup;
        private boolean agentInGroup = true;
        private String groupMemory;
        private List<HistoryMessage> history = new ArrayList<>();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CodingRepoSpec {
        // 相对于 evals/ 的模板目录
        private String fixtureDir;
        // 在工作区默认目录下的子目录名
        private String targetDir = "repo";
        private String hiddenTestDir;
        private String verifyCommand;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CheckSpec {
        // trace / response / file / coding / rubric
        private String kind;
        // trace
        private List<String> requireTools = new ArrayList<>();
        private List<String> forbidTools = new ArrayList<>();
        private String readTool;
        private String beforeTool;
        // response & file
        private String target;
        private List<String> mustContain = new ArrayList<>();
        private List<String> mustNotContain = new ArrayList<>();
        private String regex;
        private boolean unchanged;
        // coding
        private List<String> allowedPathPrefixes = new ArrayList<>();
        private boolean requireNonEmptyDiff = true;
        // rubric (LLM binary YES/NO checklist)
        private List<String> questions = new ArrayList<>();
    }
}
