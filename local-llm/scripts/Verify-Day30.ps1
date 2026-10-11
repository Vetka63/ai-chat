param([string]$BaseUrl = 'http://127.0.0.1:8583')
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$taskPasswordLine = Get-Content -LiteralPath (Join-Path $taskRoot '.env.day30') | Where-Object { $_ -like 'DAY30_PASSWORD=*' }
$taskPassword = $taskPasswordLine.Substring('DAY30_PASSWORD='.Length)
Add-Type -AssemblyName System.Net.Http
$taskHandler = [Net.Http.HttpClientHandler]::new()
$taskHttp = [Net.Http.HttpClient]::new($taskHandler)
$taskHttp.BaseAddress = [Uri]($BaseUrl.TrimEnd('/') + '/api/v1/')
$taskHttp.Timeout = [TimeSpan]::FromSeconds(360)
$taskHttp.DefaultRequestHeaders.Add('X-Day30-Request', '1')
$taskEvidence = [Collections.Generic.List[object]]::new()
function Call-Day30([string]$Method, [string]$Path, $Body = $null) {
    $taskRequest = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::new($Method), $Path)
    if ($null -ne $Body) { $taskRequest.Content = [Net.Http.StringContent]::new(($Body | ConvertTo-Json -Depth 12 -Compress), [Text.Encoding]::UTF8, 'application/json') }
    $taskResponse = $taskHttp.SendAsync($taskRequest).GetAwaiter().GetResult()
    $taskText = $taskResponse.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    $taskResult = [pscustomobject]@{ status = [int]$taskResponse.StatusCode; body = if ($taskText) { $taskText | ConvertFrom-Json } else { $null }; retryAfter = [string]$taskResponse.Headers.RetryAfter }
    $taskRequest.Dispose(); $taskResponse.Dispose(); return $taskResult
}
function Check-Day30([bool]$Condition, [string]$Name) {
    $taskEvidence.Add([pscustomobject]@{ check = $Name; passed = $Condition })
    if (-not $Condition) { throw "Не пройдена проверка: $Name" }
    Write-Host "PASS $Name"
}
function Message-Day30([string]$Text, [int]$Cap = 128) {
    return @{ requestId = [Guid]::NewGuid().ToString(); content = $Text; temperature = 0; thinking = $false; maxOutputTokens = $Cap }
}
try {
    Check-Day30 ((Call-Day30 GET 'chats').status -eq 401) 'История закрыта без сессии'
    Check-Day30 ((Call-Day30 POST 'private/session' @{ password = 'wrong-test-password' }).status -eq 401) 'Неверный пароль отклонён'
    Check-Day30 ((Call-Day30 POST 'private/session' @{ password = $taskPassword }).status -eq 200) 'Вход владельца'
    $taskState = (Call-Day30 GET 'private/session').body
    Check-Day30 ($taskState.enabled -and $taskState.authenticated -and $taskState.contextTokens -eq 8192 -and $taskState.generationsPerMinute -eq 10) 'Боевые ограничения 8192 и 10/мин'
    $taskRuntime = (Call-Day30 GET 'runtime').body
    Check-Day30 ($taskRuntime.connected -and @($taskRuntime.models).Count -gt 0) 'Локальная модель установлена'
    $taskChat = (Call-Day30 POST 'chats' @{ model = $taskRuntime.models[0].name; title = 'День 30 · память и стабильность' }).body
    $taskFirst = Message-Day30 'Запомни: мой проект называется Листок, язык Kotlin. Ответь коротко.'
    $taskSaved = Call-Day30 POST "chats/$($taskChat.id)/messages" $taskFirst
    Check-Day30 ($taskSaved.status -eq 200 -and $taskSaved.body.messages[-1].status -eq 'COMPLETED') 'Первый локальный ответ'
    $taskReplay = Call-Day30 POST "chats/$($taskChat.id)/messages" $taskFirst
    Check-Day30 ($taskReplay.status -eq 200 -and $taskReplay.body.messages.Count -eq 2) 'Replay не дублирует сообщения'
    $taskSecond = Call-Day30 POST "chats/$($taskChat.id)/messages" (Message-Day30 'Как называется мой проект и какой язык выбран?')
    Check-Day30 ($taskSecond.body.messages[-1].content -match 'Листок' -and $taskSecond.body.messages[-1].content -match 'Kotlin') 'Контекст предыдущего хода'
    $taskHuge = Message-Day30 ('я' * 13000)
    Check-Day30 ((Call-Day30 POST "chats/$($taskChat.id)/messages" $taskHuge).status -eq 422) 'История больше UTF8 budget отклонена'
    Check-Day30 ((Call-Day30 GET "chats/$($taskChat.id)").body.messages.Count -eq 4) 'Отклонённый ход не изменил историю'
    $taskLoadChat = (Call-Day30 POST 'chats' @{ model = $taskChat.model; title = 'День 30 · проверка нагрузки' }).body
    $taskLong = Message-Day30 'Перечисли подряд числа от 1 до 500, каждое с новой строки. Без пропусков.' 512
    $taskRequest = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::Post, "chats/$($taskLoadChat.id)/messages")
    $taskRequest.Content = [Net.Http.StringContent]::new(($taskLong | ConvertTo-Json -Compress), [Text.Encoding]::UTF8, 'application/json')
    $taskPending = $taskHttp.SendAsync($taskRequest)
    $taskRunning = $false
    for ($taskAttempt = 0; $taskAttempt -lt 60; $taskAttempt++) {
        if ($taskPending.IsCompleted) { break }
        if ((Call-Day30 GET "chats/$($taskLoadChat.id)").body.messages.status -contains 'RUNNING') { $taskRunning = $true; break }
        Start-Sleep -Milliseconds 100
    }
    Check-Day30 $taskRunning 'Первый конкурентный запрос реально RUNNING'
    $taskBusy = Call-Day30 POST "chats/$($taskChat.id)/messages" (Message-Day30 'Ответь только: 4' 32)
    Check-Day30 ($taskBusy.status -eq 503 -and $taskBusy.retryAfter) 'Второй конкурентный запрос 503 с Retry-After'
    $taskLongResponse = $taskPending.GetAwaiter().GetResult()
    Check-Day30 ([int]$taskLongResponse.StatusCode -eq 200) 'Активный запрос завершился после отказа второму'
    $taskLongResponse.Dispose(); $taskRequest.Dispose()
    $taskRate = $null
    for ($taskCount = 0; $taskCount -lt 20; $taskCount++) {
        $taskRate = Call-Day30 POST "chats/$($taskLoadChat.id)/messages" (Message-Day30 'Ответь одним числом: 2+2.' 16)
        if ($taskRate.status -eq 429) { break }
        Check-Day30 ($taskRate.status -eq 200 -and $taskRate.body.messages[-1].status -eq 'COMPLETED') "Последовательный запрос $($taskCount + 1)"
    }
    Check-Day30 ($taskRate.status -eq 429 -and $taskRate.retryAfter) 'Rate limit 429 с Retry-After'
    Check-Day30 ((Call-Day30 GET "chats/$($taskChat.id)").status -eq 200) 'Rate limit не блокирует чтение чата'
    Check-Day30 ((Call-Day30 DELETE 'private/session').status -eq 204) 'Выход'
    Check-Day30 ((Call-Day30 GET 'chats').status -eq 401) 'Отозванная сессия не читает историю'
    Write-Host "Демонстрационный чат: $BaseUrl/#chat/$($taskChat.id)"
} finally {
    $taskHttp.Dispose(); $taskHandler.Dispose(); $taskPassword = $null
    $taskOutput = Join-Path $taskRoot 'artifacts/day30'
    [IO.Directory]::CreateDirectory($taskOutput) | Out-Null
    [IO.File]::WriteAllText((Join-Path $taskOutput 'live-checks.json'), ($taskEvidence | ConvertTo-Json -Depth 5), [Text.UTF8Encoding]::new($false))
}
