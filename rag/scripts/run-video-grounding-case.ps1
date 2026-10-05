param(
    [Parameter(Mandatory)][string]$Question,
    [string]$ApiUrl = 'http://localhost:8382/api/v1',
    [double]$SimilarityThreshold = .60,
    [switch]$PassThru
)
# Один настоящий запрос, без автоматических повторов. Результат можно импортировать
# в библиотеку дня 24; это просмотр сохранённого ответа, а не новая генерация.
$ErrorActionPreference = 'Stop'
if (!([Uri]$ApiUrl).IsLoopback) { throw 'Для видеопроверки разрешён только локальный API.' }
$backendBaseUrl = docker exec ai-chat-rag-day-21-backend-1 printenv DEEPSEEK_BASE_URL
if ($LASTEXITCODE -ne 0 -or $backendBaseUrl.Trim() -cne 'http://budget-guard:8787') { throw 'Backend должен использовать бюджетный шлюз. Прямые платные вызовы этим скриптом запрещены.' }
$budget = Invoke-RestMethod 'http://localhost:8787/budget' -TimeoutSec 10
$available = [Math]::Min($budget.stageCapCny, $budget.limitCny - 1) - $budget.committedUpperCny
if ($available -lt 1.5) { throw 'Недостаточно запаса бюджета для нового запроса. Предыдущие результаты не изменены.' }
$api = $ApiUrl.TrimEnd('/')
$indexes = Invoke-RestMethod "$api/indexes"
$index = $indexes | Where-Object {
    $_.config.strategy -eq 'STRUCTURAL' -and $_.config.maxCharacters -eq 3000 -and $_.config.overlapCharacters -eq 300
} | Select-Object -First 1
if (!$index) { throw 'Готовый индекс STRUCTURAL 3000/300 не найден.' }
$stamp = [DateTimeOffset]::UtcNow.ToString('yyyyMMddTHHmmssfff')
$directory = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../data'))
$prefix = Join-Path $directory "video-day24-$stamp-$([Guid]::NewGuid().ToString('N').Substring(0,8))"
$report = @{ at = [DateTimeOffset]::UtcNow.ToString('o'); indexId = $index.id; note = 'Один живой запрос; не полная приёмка. JSON результата не редактируется. Смысл и полноту сверить отдельно.'; budgetBefore = $budget; cases = @() }
function Save-VideoReport {
    [IO.File]::WriteAllText("$prefix-report.json", ($report | ConvertTo-Json -Depth 90), [Text.UTF8Encoding]::new($false))
}
New-Item -ItemType Directory -Path $directory -Force | Out-Null
Save-VideoReport
$body = @{ question = $Question; indexId = $index.id; candidateTopK = 20; finalTopK = 10; similarityThreshold = $SimilarityThreshold; contextMaxCharacters = 32000; maxOutputTokens = 16384; useRewrite = $false }
try {
    $response = Invoke-RestMethod "$api/grounded-answers" -Method Post -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json))) -TimeoutSec 900
    $report.cases = @(@{ case = @{ id = 'video-pilot'; question = $Question }; result = $response })
    [IO.File]::WriteAllText("$prefix-result.json", ($response | ConvertTo-Json -Depth 90), [Text.UTF8Encoding]::new($false))
    Write-Host "Результат: $($response.status), LLM-стадий: $($response.llmStagesAttempted). UI import: $prefix-result.json"
    if ($PassThru) { [pscustomobject]@{ ResultPath = "$prefix-result.json"; ReportPath = "$prefix-report.json"; Status = $response.status } }
} catch {
    $report.error = $_.Exception.Message
    throw
} finally {
    $report.budgetAfter = Invoke-RestMethod 'http://localhost:8787/budget' -TimeoutSec 10
    Save-VideoReport
    Write-Host "Trace: $prefix-report.json"
}
