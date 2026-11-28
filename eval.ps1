<#
.SYNOPSIS
  启动 Qgents Agent 独立评测（不启 Web 端口、不跑后台定时任务）。
.EXAMPLE
  ./eval.ps1 --suite memory --base-url https://api.deepseek.com --model deepseek-chat
  ./eval.ps1 --suite coding --profile deepseek-chat --repeat 3
  ./eval.ps1 --case memory-project-update-approved --compare data/evals/20261125-baseline
#>
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$EvalArgs
)

$ErrorActionPreference = "Stop"

# 使用独立的评测库与数据目录（如未在外部显式设置，则给出安全默认值提醒）
if (-not $env:PROJECT_DATA_ROOT) {
    $env:PROJECT_DATA_ROOT = (Join-Path $PSScriptRoot "data\eval-runtime")
}
# Docker 沙箱绑定挂载使用 PROJECT_DATA_DOCKER_HOST_ROOT，必须与 PROJECT_DATA_ROOT 指向同一目录。
if (-not $env:PROJECT_DATA_DOCKER_HOST_ROOT) {
    $env:PROJECT_DATA_DOCKER_HOST_ROOT = ($env:PROJECT_DATA_ROOT -replace '\\', '/')
}

$argLine = ($EvalArgs | ForEach-Object {
    if ($_ -match '\s') { '"' + $_ + '"' } else { $_ }
}) -join ' '

mvn -q compile exec:java `
    "-Dexec.mainClass=qg.qgent.eval.EvalMain" `
    "-Dexec.args=$argLine"
