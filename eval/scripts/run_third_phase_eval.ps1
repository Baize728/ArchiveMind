param(
    [string]$EvalArgs = "--eval.runner.enabled=true --eval.runner.cases-path=eval/cases/rag_eval_cases.json --eval.runner.output-dir=eval/outputs --eval.runner.generate-answers=true --eval.runner.exit-on-complete=true",
    [string]$JavaProfile = "",
    [string]$InputPath = "eval/outputs/ragas_input.json",
    [string]$OutputPath = "eval/outputs/ragas_scores.json",
    [string]$SummaryPath = "eval/outputs/ragas_summary.json",
    [string]$EnvFile = "eval/config/ragas.env",
    [string]$EvalResultPath = "eval/outputs/eval_result.json",
    [string]$EvalProfilePath = "eval/config/eval-profile.yml",
    [string]$MergedSummaryPath = "eval/outputs/eval_summary_merged.json",
    [string]$BadcasePath = "eval/outputs/badcase_analysis.md",
    [string]$ManifestPath = "eval/outputs/eval_manifest.json",
    [string]$Metrics = "",
    [int]$Concurrency = 2,
    [int]$MaxContexts = 6,
    [int]$MaxContextChars = 4000,
    [int]$MaxContextCharsPerItem = 1200,
    [int]$MetricRetries = 1,
    [int]$OpenAIMaxRetries = 2,
    [double]$RequestTimeoutSeconds = 180,
    [double]$MetricTimeoutSeconds = 240,
    [double]$RetryBackoffSeconds = 2
)

$ErrorActionPreference = "Stop"

$ProjectRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
Set-Location $ProjectRoot

Write-Host "Step 1/3: Run Java EvalRunner..."
$MvnArgs = @(
    "-Dmaven.test.skip=true",
    "spring-boot:run"
)
if (-not [string]::IsNullOrWhiteSpace($JavaProfile)) {
    $MvnArgs += "-Dspring-boot.run.profiles=$JavaProfile"
}
$MvnArgs += "-Dspring-boot.run.arguments=$EvalArgs"
& .\mvnw.cmd @MvnArgs
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

Write-Host "Step 2/3: Run RAGAS..."
$Python = Join-Path $ProjectRoot ".venv-ragas\Scripts\python.exe"
if (-not (Test-Path -LiteralPath $Python)) {
    throw "RAGAS virtualenv not found: $Python"
}

$ArgsList = @(
    "eval\scripts\run_ragas_eval.py",
    "--env-file", $EnvFile,
    "--input", $InputPath,
    "--output", $OutputPath,
    "--summary", $SummaryPath,
    "--concurrency", "$Concurrency"
)
if (-not [string]::IsNullOrWhiteSpace($Metrics)) {
    $ArgsList += @("--metrics", $Metrics)
}
$ArgsList += @(
    "--max-contexts", "$MaxContexts",
    "--max-context-chars", "$MaxContextChars",
    "--max-context-chars-per-item", "$MaxContextCharsPerItem",
    "--metric-retries", "$MetricRetries",
    "--openai-max-retries", "$OpenAIMaxRetries",
    "--request-timeout-seconds", "$RequestTimeoutSeconds",
    "--metric-timeout-seconds", "$MetricTimeoutSeconds",
    "--retry-backoff-seconds", "$RetryBackoffSeconds"
)
& $Python @ArgsList
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

Write-Host "Step 3/3: Merge reports..."
& $Python "eval\scripts\merge_eval_reports.py" `
    "--eval-result" $EvalResultPath `
    "--ragas-scores" $OutputPath `
    "--eval-profile" $EvalProfilePath `
    "--summary-output" $MergedSummaryPath `
    "--badcase-output" $BadcasePath `
    "--manifest-output" $ManifestPath

exit $LASTEXITCODE
