$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$log=Join-Path (Get-Location) ".local/data-sync-$(Get-Date -Format yyyy-MM-dd).log"
try { & "$PSScriptRoot/sync-data.ps1" *> $log; if($LASTEXITCODE -ne 0){throw 'Data sync failed'} }
catch { $_ | Out-String | Add-Content -LiteralPath $log; exit 1 }
