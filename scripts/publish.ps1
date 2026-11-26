<#
.SYNOPSIS
    Signs and uploads the plugin to JetBrains Marketplace.

.DESCRIPTION
    Runs the Gradle `publishPlugin` task with the secrets it needs taken from the environment:

        CERTIFICATE_CHAIN       the PEM certificate chain
        PRIVATE_KEY             the PEM private key
        PRIVATE_KEY_PASSWORD    the password decrypting the key

    Those are read from -SigningDir (the output of scripts/generate-signing-key.ps1) rather than
    being pasted into any file. PUBLISH_TOKEN is a JetBrains Marketplace personal access token,
    which is NOT stored here: pass it in the environment, or let this script prompt for it.

    `publishPlugin` depends on `signPlugin`, so the upload is signed; the Marketplace then
    verifies the signature and re-signs with its own certificate.

    The version in gradle.properties must not have been uploaded before — the Marketplace
    rejects a second artifact for one version. Bump it and add a CHANGELOG entry first.

.PARAMETER SigningDir
    Where the PEM files live. Defaults to ~/.vfox-integrator-signing.

.PARAMETER Channel
    Release channel to publish to. Defaults to `alpha`, matching the pre-release version; the
    Marketplace serves a non-default channel as a separate repository rather than releasing to
    everyone. Pass `default` to publish a stable release.

.PARAMETER DryRun
    Build and sign without uploading.
#>
[CmdletBinding()]
param(
    [string]$SigningDir = (Join-Path $HOME ".vfox-integrator-signing"),
    [string]$Channel = "alpha",
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path $PSScriptRoot -Parent
Push-Location $repoRoot
try {
    $chain = Join-Path $SigningDir "chain.crt"
    $key = Join-Path $SigningDir "private.pem"
    $passwordFile = Join-Path $SigningDir "password.txt"
    foreach ($f in @($chain, $key, $passwordFile)) {
        if (-not (Test-Path $f)) {
            throw "Missing $f. Run scripts/generate-signing-key.ps1 first."
        }
    }

    # A Gradle property must exist even when empty, or the build script fails to compile.
    $env:ORG_GRADLE_PROJECT_vfoxPublishChannel = $Channel

    # Gradle only passes -P/ORG_GRADLE_PROJECT_* through; setting the plain names is what the
    # build script's providers.environmentVariable(...) reads.
    $env:CERTIFICATE_CHAIN = Get-Content $chain -Raw
    $env:PRIVATE_KEY = Get-Content $key -Raw
    $env:PRIVATE_KEY_PASSWORD = (Get-Content $passwordFile -Raw).Trim()

    $task = if ($DryRun) { "signPlugin" } else { "publishPlugin" }

    if (-not $DryRun -and [string]::IsNullOrWhiteSpace($env:PUBLISH_TOKEN)) {
        Write-Host "PUBLISH_TOKEN is not set."
        Write-Host "Create one at https://plugins.jetbrains.com/author/me/tokens (it is shown once)."
        $secure = Read-Host "Paste the token" -AsSecureString
        $env:PUBLISH_TOKEN = [System.Net.NetworkCredential]::new("", $secure).Password
    }

    Write-Host "Running ./gradlew $task (channel: $Channel)"
    & ./gradlew $task --console=plain
    if ($LASTEXITCODE -ne 0) { throw "gradle $task failed with exit code $LASTEXITCODE" }
} finally {
    Remove-Item Env:CERTIFICATE_CHAIN -ErrorAction SilentlyContinue
    Remove-Item Env:PRIVATE_KEY -ErrorAction SilentlyContinue
    Remove-Item Env:PRIVATE_KEY_PASSWORD -ErrorAction SilentlyContinue
    Pop-Location
}
