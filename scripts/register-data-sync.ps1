param([string]$At='19:30')
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$entry=Join-Path $PSScriptRoot 'scheduled-sync.ps1'
$taskName='NHL Observatory Data Sync'
$taskArgs='-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File "'+$entry+'"'
$existing=Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
if($existing -and @($existing.Actions | Where-Object { $_.Arguments -ne $taskArgs }).Count){throw 'A task with this name belongs to another command. It was not changed.'}
$action=New-ScheduledTaskAction -Execute 'powershell.exe' -Argument $taskArgs -WorkingDirectory $root
$trigger=New-ScheduledTaskTrigger -Daily -At $At
$settings=New-ScheduledTaskSettingsSet -StartWhenAvailable -RunOnlyIfNetworkAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Minutes 10)
$principal=New-ScheduledTaskPrincipal -UserId ([Security.Principal.WindowsIdentity]::GetCurrent().Name) -LogonType Interactive -RunLevel Limited
Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Settings $settings -Principal $principal -Force | Select-Object TaskName,State
Write-Output "Daily data sync: $At in the Windows timezone. Requires this user logged in, PC awake, Internet and a valid az login."
