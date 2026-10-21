param([string]$ResourceGroup='nhlprediction',[string]$StaticWebAppName='nhl-analyzer-online',[string]$WebsiteUrl='',[switch]$UploadFrontend,[switch]$RotateSecret)
$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
function Invoke-AzJson([string[]]$Arguments) {
    $output = & az @Arguments --output json
    if ($LASTEXITCODE -ne 0) { throw "Azure command failed: $($Arguments[0..1] -join ' ')" }
    if ($output) { return ($output -join "`n" | ConvertFrom-Json) }
}
$infra=Invoke-AzJson @('deployment','group','show','--resource-group',$ResourceGroup,'--name','nhl-infrastructure','--query','properties.outputs')
$app=$infra.functionAppName.value
$base=$infra.functionUrl.value
$storage=$infra.storageAccountName.value
$account=Invoke-AzJson @('account','show')
$owner=Invoke-AzJson @('ad','signed-in-user','show','--query','{id:id}')
New-Item -ItemType Directory -Force .local | Out-Null
$identityFile=Join-Path (Get-Location) '.local/identity.json'
if (Test-Path -LiteralPath $identityFile) {
    $identity=Get-Content -LiteralPath $identityFile -Raw | ConvertFrom-Json
    if ($identity.subscriptionId -ne $account.id -or $identity.functionUrl -ne $base) { throw 'Saved identity belongs to another deployment.' }
} else {
    $registrations=@(Invoke-AzJson @('ad','app','list','--display-name','NHL Prediction MCP')) | Where-Object { $_.web.redirectUris -contains "$base/oauth/microsoft/callback" }
    if (@($registrations).Count -gt 1) { throw 'More than one matching identity registration exists.' }
    if (@($registrations).Count -eq 1) { $registration=@($registrations)[0] }
    else { $registration=Invoke-AzJson @('ad','app','create','--display-name','NHL Prediction MCP','--sign-in-audience','AzureADMyOrg','--web-redirect-uris',"$base/oauth/microsoft/callback",'--query','{appId:appId,id:id}') }
    $identity=[pscustomobject]@{ appId=$registration.appId; objectId=$registration.id; tenantId=$account.tenantId; ownerId=$owner.id; subscriptionId=$account.id; functionUrl=$base }
    $identity | ConvertTo-Json | Set-Content -LiteralPath $identityFile -Encoding utf8
}
$secretExists=Invoke-AzJson @('functionapp','config','appsettings','list','--resource-group',$ResourceGroup,'--name',$app,'--query',"length([?name=='AUTH_CLIENT_SECRET' && value!=''])")
$credential=$null
if ($RotateSecret -or $secretExists -eq 0) { $credential=Invoke-AzJson @('ad','app','credential','reset','--id',$identity.appId,'--append','--display-name',"nhl-deploy-$(Get-Date -Format yyyyMMddHHmmss)",'--years','1') }
$previousToken=$env:SWA_CLI_DEPLOYMENT_TOKEN
try {
    $site=Invoke-AzJson @('staticwebapp','show','--resource-group',$ResourceGroup,'--name',$StaticWebAppName)
    $fallback="https://$($site.defaultHostname)"
    $domains=@(Invoke-AzJson @('staticwebapp','hostname','list','--resource-group',$ResourceGroup,'--name',$StaticWebAppName))
    $ready=@($domains | Where-Object { ($_.domainName -eq 'www.nhlanalyzer.online' -or $_.name -eq 'www.nhlanalyzer.online') -and $_.status -eq 'Ready' }).Count -gt 0
    $web=if ($WebsiteUrl) { $WebsiteUrl.TrimEnd('/') } elseif ($ready) { 'https://www.nhlanalyzer.online' } else { $fallback }
    if ($web -notmatch '^https://[^/]+$') { throw 'WebsiteUrl must be an HTTPS origin' }
    $settings=@{ AUTH_TENANT_ID=$identity.tenantId; AUTH_CLIENT_ID=$identity.appId; AUTH_OWNER_ID=$identity.ownerId; PUBLIC_BASE_URL=$base; WEB_URL=$web }
    if ($credential) { $settings.AUTH_CLIENT_SECRET=$credential.password }
    $settingsFile=Join-Path (Get-Location) '.local/oauth-settings.json'
    try {
        $settings | ConvertTo-Json | Set-Content -LiteralPath $settingsFile -Encoding utf8
        $null=Invoke-AzJson @('functionapp','config','appsettings','set','--resource-group',$ResourceGroup,'--name',$app,'--settings',"@$settingsFile",'--query','[].name')
    } finally { if (Test-Path -LiteralPath $settingsFile) { Remove-Item -LiteralPath $settingsFile -Force } }
    $credential=$null; $settings=$null
    & az functionapp cors add --resource-group $ResourceGroup --name $app --allowed-origins $web $fallback 'https://www.nhlanalyzer.online' --output none
    if ($LASTEXITCODE -ne 0) { throw 'CORS setup failed' }
    $deployment=[pscustomobject]@{ resourceGroup=$ResourceGroup; subscriptionId=$account.id; functionApp=$app; functionUrl=$base; mcpUrl="$base/mcp"; website=$web; staticWebApp=$StaticWebAppName; azureWebsite=$fallback; storageAccount=$storage; oauthApplicationId=$identity.appId }
    $deployment | ConvertTo-Json | Set-Content -LiteralPath '.local/deployment.json' -Encoding utf8
    if ($UploadFrontend) {
        "window.NHL_CONFIG = { apiBase: '$base' };" | Set-Content -LiteralPath frontend/config.js -Encoding utf8
        $publish=Join-Path (Get-Location) '.local/frontend-publish'
        New-Item -ItemType Directory -Force $publish | Out-Null
        foreach ($asset in @('index.html','app.js','styles.css','config.js','staticwebapp.config.json')) { Copy-Item -LiteralPath "frontend/$asset" -Destination (Join-Path $publish $asset) -Force }
        Copy-Item -LiteralPath 'frontend/assets' -Destination $publish -Recurse -Force
        $env:SWA_CLI_DEPLOYMENT_TOKEN=Invoke-AzJson @('staticwebapp','secrets','list','--resource-group',$ResourceGroup,'--name',$StaticWebAppName,'--query','properties.apiKey')
        & npx --yes '@azure/static-web-apps-cli@2.1.0' deploy $publish --env production --app-name $StaticWebAppName --resource-group $ResourceGroup
        if ($LASTEXITCODE -ne 0) { throw 'Static Web Apps frontend deployment failed' }
    }
    $deployment | ConvertTo-Json
} finally { $env:SWA_CLI_DEPLOYMENT_TOKEN=$previousToken }
