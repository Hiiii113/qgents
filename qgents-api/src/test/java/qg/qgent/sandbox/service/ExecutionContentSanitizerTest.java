package qg.qgent.sandbox.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionContentSanitizerTest {

    @Test
    void sanitizesDiagnosticDetails() {
        String detail = ExecutionContentSanitizer.sanitizeDiagnosticDetail(
                "TOKEN=secret-value endpoint=https://worker.internal/run path=C:\\worker\\secret "
                        + "command=./gradlew test\nProcess failed running ./gradlew test\n"
                        + "    at qg.qgent.Worker.execute(Worker.java:12)\nstderr=raw tool output");

        assertThat(detail).contains("[environment omitted]", "[endpoint omitted]", "[host path omitted]",
                "[command omitted]", "[stack frame omitted]", "[raw output omitted]");
        assertThat(detail).doesNotContain("secret-value", "worker.internal", "C:\\worker", "./gradlew", "raw tool output",
                "Worker.execute");
    }
}
