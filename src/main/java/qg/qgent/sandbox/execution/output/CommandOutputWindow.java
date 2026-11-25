package qg.qgent.sandbox.execution.output;

/**
 * 一次分页读取得到的两个输出流窗口。
 */
public record CommandOutputWindow(CommandStreamWindow stdout, CommandStreamWindow stderr) {
}
