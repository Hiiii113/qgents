package qg.qgent.workspace.service;

/** 固定构建和测试命令的标识，由 Worker 转换成实际命令执行。 */
public enum DevelopmentCommandId {
    MAVEN_TEST,
    MAVEN_PACKAGE,
    MAVEN_WRAPPER_TEST,
    GRADLE_TEST,
    GRADLE_WRAPPER_TEST,
    NPM_TEST
}
