param([Parameter(Mandatory)][string]$ReportPath, [string]$ApiUrl = 'http://localhost:8382/api/v1')
# Только GET: после прерывания тестового клиента сохраняет уже завершённые серверные turns.
# Ничего не повторяет, не меняет исходный отчёт, не объявляет неполный сценарий успешным.
$ErrorActionPreference = 'Stop'
$source = Get-Content -LiteralPath $ReportPath -Raw | ConvertFrom-Json
$results = @()
foreach ($chat in $source.chats.PSObject.Properties) {
    $view = Invoke-RestMethod "$($ApiUrl.TrimEnd('/'))/conversations/$($chat.Value)" -TimeoutSec 15
    if (@($view.turns | Where-Object status -eq 'PENDING').Count) { throw 'Есть незавершённый turn; backend не перезапускать, повторить только GET позже.' }
    $position = 0
    foreach ($turn in $view.turns) {
        $position++
        $results += @{ scenario = $chat.Name; position = $position; conversationId = $chat.Value; turn = $turn }
    }
}
$recovered = @{ at = [DateTimeOffset]::UtcNow.ToString('o'); sourceReport = [IO.Path]::GetFileName($ReportPath); note = 'GET-only recovery of an interrupted research run, NOT a passed full scenario.'; results = $results }
$destination = Join-Path $PSScriptRoot "../data/day25-confidence-recovered-$([DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfff')).json"
[IO.File]::WriteAllText($destination, ($recovered | ConvertTo-Json -Depth 90), [Text.UTF8Encoding]::new($false))
"Сохранено $($results.Count) turns без повторных вызовов: $destination"
