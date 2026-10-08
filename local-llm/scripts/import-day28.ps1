param(
    [Parameter(Mandatory)][string[]]$Paths,
    [string]$BaseUrl = 'http://127.0.0.1:8482',
    [switch]$BuildIndex
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
$taskMetadata = @{
    'Kraken_RU_complete.txt' = @('Кракен', 'КРАКЕН')
    'Lightning_Run_RU_complete.txt' = @('Молниеносный рейс', 'МОЛНИЕНОСНЫЙ РЕЙС')
    'Nexus_RU_complete.txt' = @('Нексус', 'НЕКСУС')
    'Redeemer_RU_complete.txt' = @('Искупитель', 'ИСКУПИТЕЛЬ')
    'The_Darkling_Hours_RU_complete.txt' = @('Сумрачные часы', 'СУМРАЧНЫЕ ЧАСЫ')
    'The_Test_of_Faith_RU_complete.txt' = @('Испытание веры', 'ИСПЫТАНИЕ ВЕРЫ')
}
$taskClient = [System.Net.Http.HttpClient]::new()
try {
    foreach ($taskPath in $Paths) {
        $taskFile = Get-Item -LiteralPath $taskPath
        $taskDescription = $taskMetadata[$taskFile.Name]
        if (-not $taskDescription) { throw "Для $($taskFile.Name) используйте загрузку через UI и явно укажите название/начальный заголовок." }
        $taskBody = [System.Net.Http.MultipartFormDataContent]::new()
        try {
            $taskBytes = [System.Net.Http.ByteArrayContent]::new([IO.File]::ReadAllBytes($taskFile.FullName))
            $taskBody.Add($taskBytes, 'file', $taskFile.Name)
            $taskBody.Add([System.Net.Http.StringContent]::new($taskDescription[0]), 'title')
            $taskBody.Add([System.Net.Http.StringContent]::new($taskDescription[1]), 'startMarker')
            $taskResponse = $taskClient.PostAsync("$BaseUrl/api/v1/rag/documents", $taskBody).GetAwaiter().GetResult()
            $taskJson = $taskResponse.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            if (-not $taskResponse.IsSuccessStatusCode) { throw "Ошибка импорта: $taskJson" }
            $taskDocument = $taskJson | ConvertFrom-Json
            [pscustomobject]@{ title = $taskDocument.title; id = $taskDocument.id; characters = $taskDocument.characters; sha256 = $taskDocument.sha256 }
        } finally { $taskBody.Dispose() }
    }
    if ($BuildIndex) {
        Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/rag/indexes" -ContentType 'application/json' -Body '{"chunkCharacters":1400,"overlapCharacters":180}'
    }
} finally { $taskClient.Dispose() }
