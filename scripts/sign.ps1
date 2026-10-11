# Signs Windows files (Authenticode, SHA-256, timestamped).
# Uses, in this order:
#   1. Azure Key Vault (AZURE_KEY_VAULT_URI, AZURE_CLIENT_ID, AZURE_CLIENT_SECRET, AZURE_TENANT_ID, AZURE_CERT_NAME)
#   2. A PFX certificate (WINDOWS_CERTIFICATE_PFX = base64 of the .pfx, WINDOWS_CERTIFICATE_PASSWORD)
#   3. With -TestCertificate: a throwaway self-signed certificate, only to check the pipeline.
# Does nothing (exit 0) when no certificate is configured.
param(
    [Parameter(Mandatory = $true)][string[]]$Files,
    [switch]$TestCertificate
)
$ErrorActionPreference = "Stop"
$timestamp = "http://timestamp.digicert.com"
$description = "CloudStreamPC"
$url = "https://github.com/Nikola17/CloudStreamPC"

function Find-SignTool {
    $tool = Get-ChildItem "${env:ProgramFiles(x86)}\Windows Kits\10\bin\*\x64\signtool.exe" -ErrorAction SilentlyContinue |
        Sort-Object FullName | Select-Object -Last 1
    if (-not $tool) { throw "signtool.exe introuvable (Windows SDK)" }
    return $tool.FullName
}

$Files = $Files | ForEach-Object { (Resolve-Path $_).Path }

if ($env:AZURE_KEY_VAULT_URI -and $env:AZURE_CERT_NAME) {
    Write-Host "Signature avec Azure Key Vault ($env:AZURE_CERT_NAME)"
    dotnet tool install --global AzureSignTool | Out-Null
    $env:PATH += ";$env:USERPROFILE\.dotnet\tools"
    AzureSignTool sign -kvu $env:AZURE_KEY_VAULT_URI -kvi $env:AZURE_CLIENT_ID -kvs $env:AZURE_CLIENT_SECRET `
        -kvt $env:AZURE_TENANT_ID -kvc $env:AZURE_CERT_NAME -tr $timestamp -td sha256 -fd sha256 `
        -d $description -du $url -v @Files
    if ($LASTEXITCODE -ne 0) { throw "AzureSignTool a échoué" }
}
elseif ($env:WINDOWS_CERTIFICATE_PFX -or $TestCertificate) {
    $pfx = Join-Path $env:RUNNER_TEMP "codesign.pfx"
    if ($env:WINDOWS_CERTIFICATE_PFX) {
        Write-Host "Signature avec le certificat PFX"
        [IO.File]::WriteAllBytes($pfx, [Convert]::FromBase64String($env:WINDOWS_CERTIFICATE_PFX))
        $password = $env:WINDOWS_CERTIFICATE_PASSWORD
    } else {
        Write-Host "TEST : signature avec un certificat auto-signé jetable (non reconnu par Windows)"
        $cert = New-SelfSignedCertificate -Type CodeSigningCert -Subject "CN=CloudStreamPC (test)" `
            -CertStoreLocation Cert:\CurrentUser\My -NotAfter (Get-Date).AddDays(2)
        $password = "test-" + [Guid]::NewGuid().ToString("N")
        Export-PfxCertificate -Cert $cert -FilePath $pfx -Password (ConvertTo-SecureString $password -AsPlainText -Force) | Out-Null
    }
    $signtool = Find-SignTool
    & $signtool sign /f $pfx /p $password /fd SHA256 /tr $timestamp /td SHA256 /d $description /du $url /v @Files
    if ($LASTEXITCODE -ne 0) { throw "signtool a échoué" }
    Remove-Item $pfx -Force
}
else {
    Write-Host "Aucun certificat configuré : fichiers non signés."
    exit 0
}

foreach ($file in $Files) {
    $sig = Get-AuthenticodeSignature $file
    if (-not $sig.SignerCertificate) { throw "Pas de signature sur $file" }
    Write-Host ("{0} : {1} — {2}" -f (Split-Path $file -Leaf), $sig.Status, $sig.SignerCertificate.Subject)
}
