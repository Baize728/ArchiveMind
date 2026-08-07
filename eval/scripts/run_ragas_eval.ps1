param(
    [string]$InputPath = "eval/outputs/ragas_input.json",
    [string]$OutputPath = "eval/outputs/ragas_scores.json",
    [string]$EnvFile = "eval/config/ragas.env",
    [string]$Metrics = "",
    [int]$Concurrency = 2
)

$ErrorActionPreference = "Stop"

$ProjectRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
Set-Location $ProjectRoot

$Python = Join-Path $ProjectRoot ".venv-ragas\Scripts\python.exe"
if (-not (Test-Path -LiteralPath $Python)) {
    throw "RAGAS virtualenv not found: $Python. Create it with python -m venv .venv-ragas and install eval/requirements-ragas.txt."
}

if (-not (Test-Path -LiteralPath $InputPath)) {
    throw "RAGAS input not found: $InputPath. Run EvalRunner first."
}

$ArgsList = @(
    "eval\scripts\run_ragas_eval.py",
    "--env-file", $EnvFile,
    "--input", $InputPath,
    "--output", $OutputPath,
    "--concurrency", "$Concurrency"
)

if (-not [string]::IsNullOrWhiteSpace($Metrics)) {
    $ArgsList += @("--metrics", $Metrics)
}

& $Python @ArgsList

exit $LASTEXITCODE
