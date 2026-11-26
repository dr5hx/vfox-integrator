<#
.SYNOPSIS
    Generates the RSA key pair and certificate chain JetBrains Marketplace signing needs.

.DESCRIPTION
    Creates, under -OutDir (default: ~/.vfox-integrator-signing, deliberately outside this
    repository):

        chain.crt      X509 certificate chain (one self-signed certificate)
        private.pem    the private key, AES-256 encrypted
        password.txt   the password that decrypts private.pem

    The private key is the plugin author's identity: anyone holding it could sign a plugin
    update that installers would accept as coming from this vendor. Keeping it outside the
    working tree is what makes an accidental `git add -A` harmless, and `scripts/publish.ps1`
    reads it from there.

    Nothing is overwritten without -Force, because replacing the key after a release would
    mean the next signed upload no longer chains to the previous one.

.PARAMETER OutDir
    Where to write the files. Defaults to ~/.vfox-integrator-signing.

.PARAMETER CommonName
    The certificate subject's CN. Defaults to the vendor name.

.PARAMETER Days
    Certificate validity in days. Defaults to 3650 (ten years) — a certificate that expires
    mid-release is a needless failure mode, and this one is self-signed trust anchor material
    rather than something a browser validates.

.PARAMETER OpenSslPath
    Explicit path to openssl.exe, for when auto-detection misses it.

.PARAMETER Force
    Overwrite an existing key pair. Only do this to start over deliberately.
#>
[CmdletBinding()]
param(
    [string]$OutDir = (Join-Path $HOME ".vfox-integrator-signing"),
    [string]$CommonName = "dr5hx",
    [int]$Days = 3650,
    [string]$OpenSslPath,
    [switch]$Force
)

$ErrorActionPreference = "Stop"

function Find-OpenSsl {
    param([string]$Explicit)

    if ($Explicit) {
        if (-not (Test-Path $Explicit)) { throw "OpenSslPath does not exist: $Explicit" }
        return $Explicit
    }

    $onPath = Get-Command openssl -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }

    # Git for Windows ships openssl, so it is the reliable fallback wherever this repository
    # lives. Deriving the root from the git *executable path* does not work here: on machines
    # where git is exposed through a package-manager shim (scoop, for one) that path leads to
    # the shim directory, not to the installation. `git --exec-path` reports the real
    # installation instead, and the openssl binary sits one directory level up from it.
    $git = Get-Command git -ErrorAction SilentlyContinue
    if ($git) {
        $execPath = & git --exec-path 2>$null
        if ($LASTEXITCODE -eq 0 -and $execPath) {
            # e.g. <git>/mingw64/libexec/git-core  ->  <git>
            $gitRoot = Split-Path (Split-Path (Split-Path $execPath.Trim() -Parent) -Parent) -Parent
            foreach ($rel in @("usr/bin/openssl.exe", "mingw64/bin/openssl.exe", "ucrt64/bin/openssl.exe")) {
                $candidate = Join-Path $gitRoot $rel
                if (Test-Path $candidate) { return (Resolve-Path $candidate).Path }
            }
        }
    }
    throw "openssl not found. Install it, or pass -OpenSslPath (Git ships one under <git>/usr/bin)."
}

$openssl = Find-OpenSsl -Explicit $OpenSslPath
Write-Host "Using openssl: $openssl"

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$chain = Join-Path $OutDir "chain.crt"
$key = Join-Path $OutDir "private.pem"
$passwordFile = Join-Path $OutDir "password.txt"

foreach ($f in @($chain, $key, $passwordFile)) {
    if ((Test-Path $f) -and -not $Force) {
        throw "$f already exists. Re-run with -Force only if you mean to replace the key pair."
    }
}

# A random password: the key is worthless to an attacker who finds the file but not this.
# 32 bytes of base64 is well beyond what the AES-256-CBC wrapping needs.
$bytes = New-Object byte[] 32
[System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
$password = [Convert]::ToBase64String($bytes)

$encrypted = Join-Path $OutDir "private_encrypted.pem"
$tmp = New-Object System.Security.Cryptography.X509Certificates.X509Certificate2
try {
    # -passout/-passin via file descriptors would leak the password into the process list, so
    # the password is passed on stdin instead.
    $password | & $openssl genpkey -aes-256-cbc -algorithm RSA `
        -pkeyopt rsa_keygen_bits:4096 -out $encrypted -pass stdin
    if ($LASTEXITCODE -ne 0) { throw "openssl genpkey failed" }

    # The chain is built from an unencrypted copy, which is then removed; what remains is the
    # encrypted key at private.pem.
    $password | & $openssl rsa -in $encrypted -out $key -passin stdin
    if ($LASTEXITCODE -ne 0) { throw "openssl rsa failed" }

    & $openssl req -key $key -new -x509 -days $Days -subj "/CN=$CommonName" -out $chain
    if ($LASTEXITCODE -ne 0) { throw "openssl req failed" }
} finally {
    Remove-Item -Force $encrypted -ErrorAction SilentlyContinue
}

# private.pem is left unencrypted on disk, so lock the directory down where the platform can:
# the per-user ACL keeps other accounts off it.
try {
    $acl = Get-Acl $OutDir
    $acl.SetAccessRuleProtection($true, $false)
    $rule = New-Object System.Security.AccessControl.FileSystemAccessRule(
        "$env:USERDOMAIN\$env:USERNAME", "FullControl", "ContainerInherit,ObjectInherit", "None", "Allow")
    $acl.AddAccessRule($rule)
    Set-Acl -Path $OutDir -AclObject $acl
} catch {
    Write-Warning "Could not restrict the permissions on ${OutDir}: $($_.Exception.Message)"
}

Set-Content -Path $passwordFile -Value $password -NoNewline -Encoding ascii

Write-Host ""
Write-Host "Wrote:"
Write-Host "  certificate chain : $chain"
Write-Host "  private key       : $key"
Write-Host "  key password      : $passwordFile"
Write-Host ""
Write-Host "Keep these safe and out of version control. Next: scripts/publish.ps1"
