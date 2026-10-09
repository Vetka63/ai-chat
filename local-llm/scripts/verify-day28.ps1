param(
    [string]$BaseUrl = 'http://127.0.0.1:8482',
    [ValidateRange(1,5)][int]$Repeats = 3,
    [ValidateSet('LOCAL','DEEPSEEK','COMPARE')][string]$Mode = 'COMPARE',
    [string]$CloudModel = 'deepseek-v4-pro',
    [ValidateSet('qwen3:8b','qwen3:4b-q4_K_M')][string]$LocalModel = 'qwen3:8b',
    [Nullable[int]]$MaxOutputTokens = $null,
    [string]$OutputDirectory = (Join-Path $PSScriptRoot "../artifacts/day28-$(Get-Date -Format 'yyyyMMdd-HHmmss')"),
    [switch]$StopOnFailure
)
$ErrorActionPreference = 'Stop'
if ($null -ne $MaxOutputTokens -and $MaxOutputTokens -notin 1..8192) { throw 'MaxOutputTokens должен быть 1..8192 или не задан.' }
$taskCases = Get-Content -LiteralPath (Join-Path $PSScriptRoot '../evaluation/day28-cases.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$taskIndexes = Invoke-RestMethod -Uri "$BaseUrl/api/v1/rag/indexes"
$taskIndex = $taskIndexes | Where-Object { $_.status -eq 'READY' } | Select-Object -First 1
if (-not $taskIndex) { throw 'Сначала постройте индекс.' }
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$taskResults = @()
foreach ($taskRepeat in 1..$Repeats) {
    foreach ($taskCase in $taskCases) {
        $taskResultPath = Join-Path $OutputDirectory "$($taskCase.id)-$taskRepeat.json"
        $taskReceiptPath = Join-Path $OutputDirectory "$($taskCase.id)-$taskRepeat-request.json"
        $taskRequest = @{ requestId = [guid]::NewGuid().ToString(); indexId = $taskIndex.id; question = $taskCase.question; mode = $Mode; localModel = $LocalModel; cloudModel = $CloudModel; topK = 5; threshold = 0.35; contextCharacters = 7000; temperature = 0; thinking = $false; maxOutputTokens = $MaxOutputTokens }
        if (Test-Path -LiteralPath $taskResultPath) {
            $taskRun = Get-Content -LiteralPath $taskResultPath -Raw -Encoding UTF8 | ConvertFrom-Json
            $taskSavedRequest = $taskRun.request
        } else {
            $taskSavedRequest = if (Test-Path -LiteralPath $taskReceiptPath) { Get-Content -LiteralPath $taskReceiptPath -Raw -Encoding UTF8 | ConvertFrom-Json } else { $null }
            if ($taskSavedRequest) {
                foreach ($taskField in @('indexId','question','mode','localModel','cloudModel','topK','threshold','contextCharacters','temperature','thinking','maxOutputTokens')) {
                    if ($taskSavedRequest.$taskField -cne $taskRequest[$taskField]) { throw 'Receipt имеет другие настройки. Выберите новый OutputDirectory.' }
                }
                $taskRequest = $taskSavedRequest
            }
            else { $taskRequest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $taskReceiptPath -Encoding UTF8 }
            $taskRun = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/rag/experiments" -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes(($taskRequest | ConvertTo-Json)))
        }
        foreach ($taskField in @('indexId','question','mode','localModel','cloudModel','topK','threshold','contextCharacters','temperature','thinking','maxOutputTokens')) {
            if ($taskRun.request.$taskField -cne $taskRequest.$taskField) { throw 'Сохранённый прогон имеет другие настройки. Выберите новый OutputDirectory.' }
        }
        $taskDeadline = [DateTime]::UtcNow.AddMinutes(7)
        while ($taskRun.status -in @('PREPARING','RUNNING')) {
            if ([DateTime]::UtcNow -gt $taskDeadline) { throw "Timeout для $($taskRun.id); не запускайте повтор вслепую." }
            Start-Sleep -Milliseconds 600
            try { $taskRun = Invoke-RestMethod -Uri "$BaseUrl/api/v1/rag/experiments/$($taskRun.id)" -TimeoutSec 10 }
            catch { Write-Output "GET временно недоступен id=$($taskRun.id); генерацию не повторяем." }
        }
        $taskRun | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $taskResultPath -Encoding UTF8
        foreach ($taskAnswer in $taskRun.answers) {
            $taskMatches = @($taskCase.patterns | Where-Object { $taskAnswer.answer -match $_ }).Count -eq @($taskCase.patterns).Count
            $taskResults += [pscustomobject]@{ case = $taskCase.id; repeat = $taskRepeat; experimentId = $taskRun.id; provider = $taskAnswer.provider; model = $taskAnswer.actualModel; status = $taskAnswer.status; latencyMs = $taskAnswer.milliseconds; loadMs = $taskAnswer.loadMilliseconds; inputTokens = $taskAnswer.inputTokens; outputTokens = $taskAnswer.outputTokens; citationStatus = $taskAnswer.citationStatus; expectedWordsMatched = $taskMatches; error = $taskAnswer.error; promptSha256 = $taskRun.retrieval.promptSha256 }
        }
        Write-Output "$($taskCase.id) repeat=$taskRepeat status=$($taskRun.status) id=$($taskRun.id)"
        $taskResults | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $OutputDirectory 'summary.json') -Encoding UTF8
        if ($StopOnFailure -and $taskRun.status -in @('FAILED','INTERRUPTED','PARTIAL')) { throw "Прогон остановлен: $($taskRun.status) id=$($taskRun.id). Receipt/результат сохранены; новые модели не запускаются." }
    }
}
$taskResults | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $OutputDirectory 'summary.json') -Encoding UTF8
Write-Output 'expectedWordsMatched — диагностическая эвристика, не смысловой judge; проверяйте ответы и источники.'
$taskResults | Group-Object provider | ForEach-Object { [pscustomobject]@{ provider = $_.Name; calls = $_.Count; completed = @($_.Group | Where-Object status -eq 'COMPLETED').Count; expectedWordsMatched = @($_.Group | Where-Object expectedWordsMatched).Count; averageMs = [math]::Round(($_.Group | Measure-Object latencyMs -Average).Average) } }
