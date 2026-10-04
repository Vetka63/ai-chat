param([string]$Revision = 'bef0ce52475e44b8c9d66d7ff8c6a03b37758a03')
$ErrorActionPreference = 'Stop'
if ($Revision -notmatch '^[a-f0-9]{40}$') { throw 'Revision должна быть полным SHA коммита.' }
$ragProjectRoot = Split-Path -Parent $PSScriptRoot
$ragCorpusTarget = Join-Path $ragProjectRoot 'corpus\progit-ru'
$ragManifestPath = Join-Path $ragCorpusTarget 'manifest.json'
if (Test-Path -LiteralPath $ragManifestPath) {
    $ragExistingManifest = Get-Content -LiteralPath $ragManifestPath -Raw | ConvertFrom-Json
    if ($ragExistingManifest.revision -ne $Revision) { throw 'В каталоге уже другая версия корпуса. Для нового снимка выберите отдельный каталог.' }
    if (!$ragExistingManifest.PSObject.Properties['resources']) {
        $ragResourceRelative = 'book/07-git-tools/git-credential-read-only'
        $ragResourcePath = Join-Path $ragCorpusTarget $ragResourceRelative
        if (Test-Path -LiteralPath $ragResourcePath) { throw 'Незарегистрированный ресурс уже существует; сначала проверьте его происхождение.' }
        Invoke-WebRequest -Uri "https://raw.githubusercontent.com/progit/progit2-ru/$Revision/$ragResourceRelative" -OutFile $ragResourcePath
        $ragResource = @{path=$ragResourceRelative;sha256=(Get-FileHash -LiteralPath $ragResourcePath -Algorithm SHA256).Hash.ToLowerInvariant()}
        $ragExistingManifest | Add-Member -NotePropertyName resources -NotePropertyValue @($ragResource)
        [System.IO.File]::WriteAllText($ragManifestPath,($ragExistingManifest | ConvertTo-Json -Depth 8),[System.Text.UTF8Encoding]::new($false))
    }
    foreach ($ragFile in @($ragExistingManifest.files) + @($ragExistingManifest.resources)) {
        $ragExistingPath = Join-Path $ragCorpusTarget $ragFile.path
        if (!(Test-Path -LiteralPath $ragExistingPath) -or (Get-FileHash -LiteralPath $ragExistingPath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $ragFile.sha256) { throw "Контрольная сумма изменена: $($ragFile.path)" }
    }
    Write-Output 'Закреплённый корпус уже подготовлен и проверен.'
    exit 0
}
$ragTempDirectory = Join-Path ([System.IO.Path]::GetTempPath()) ('rag-progit-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $ragTempDirectory | Out-Null
$ragArchivePath = Join-Path $ragTempDirectory 'progit.zip'
Invoke-WebRequest -Uri "https://codeload.github.com/progit/progit2-ru/zip/$Revision" -OutFile $ragArchivePath
$ragArchiveSha = (Get-FileHash -LiteralPath $ragArchivePath -Algorithm SHA256).Hash.ToLowerInvariant()
Add-Type -AssemblyName System.IO.Compression.FileSystem
$ragArchive = [System.IO.Compression.ZipFile]::OpenRead($ragArchivePath)
$ragFiles = @()
$ragResources = @()
$ragChapters = @('02-git-basics','03-git-branching','07-git-tools','10-git-internals')
try {
    foreach ($ragEntry in $ragArchive.Entries) {
        $ragRelativePath = ($ragEntry.FullName -split '/',2)[1]
        $ragSelectedText = $ragRelativePath -match '^book/(02-git-basics|03-git-branching|07-git-tools|10-git-internals)/sections/[^/]+\.asc$'
        $ragSelectedResource = $ragRelativePath -eq 'book/07-git-tools/git-credential-read-only'
        if (!$ragSelectedText -and !$ragSelectedResource -and $ragRelativePath -ne 'LICENSE.asc') { continue }
        if ($ragEntry.Length -gt 2000000) { throw 'Неожиданно большой исходный файл.' }
        $ragDestination = [System.IO.Path]::GetFullPath((Join-Path $ragCorpusTarget $ragRelativePath))
        $ragValidatedRoot = [System.IO.Path]::GetFullPath($ragCorpusTarget) + [System.IO.Path]::DirectorySeparatorChar
        if (!$ragDestination.StartsWith($ragValidatedRoot,[StringComparison]::OrdinalIgnoreCase)) { throw 'Небезопасный путь ZIP.' }
        New-Item -ItemType Directory -Path (Split-Path -Parent $ragDestination) -Force | Out-Null
        [System.IO.Compression.ZipFileExtensions]::ExtractToFile($ragEntry,$ragDestination,$false)
        if ($ragSelectedText) { $ragFiles += @{path=$ragRelativePath;sha256=(Get-FileHash -LiteralPath $ragDestination -Algorithm SHA256).Hash.ToLowerInvariant()} }
        if ($ragSelectedResource) { $ragResources += @{path=$ragRelativePath;sha256=(Get-FileHash -LiteralPath $ragDestination -Algorithm SHA256).Hash.ToLowerInvariant()} }
    }
} finally { $ragArchive.Dispose() }
if ($ragFiles.Count -lt 10) { throw 'Не получены ожидаемые секции книги.' }
$ragManifest = @{
    corpusId='progit-ru-day21-v1';repository='https://github.com/progit/progit2-ru';revision=$Revision
    archiveSha256=$ragArchiveSha;license='CC BY-NC-SA 3.0'
    attribution='Pro Git, Second Edition. Scott Chacon and Ben Straub; Russian translation contributors. Source is unchanged; the backend produces a separate normalized representation.'
    selectedChapters=$ragChapters;files=@($ragFiles | Sort-Object { $_.path });resources=@($ragResources)
}
# Это производный manifest скачанных данных, не исходный код проекта.
[System.IO.File]::WriteAllText($ragManifestPath,($ragManifest | ConvertTo-Json -Depth 8),[System.Text.UTF8Encoding]::new($false))
Write-Output "Подготовлены $($ragFiles.Count) секций Pro Git. Revision: $Revision"
Write-Output "Временный архив для проверки SHA256: $ragArchivePath"
