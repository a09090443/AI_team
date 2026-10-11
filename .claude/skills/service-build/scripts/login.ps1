param([Parameter(Mandatory=$true)][string]$Config)
$ErrorActionPreference = 'Stop'
$profileData = Get-Content -LiteralPath $Config -Raw | ConvertFrom-Json
if ($profileData.transport -ne 'virtualbox') { throw 'This entry is for VirtualBox login only.' }
$destination = [IO.Path]::GetFullPath($profileData.password_file)
$directory = [IO.Path]::GetDirectoryName($destination)
if (Test-Path -LiteralPath $destination) { throw 'A login file already exists; it will not be overwritten.' }
if (-not (Test-Path -LiteralPath $directory -PathType Container)) { throw 'Private profile directory is missing.' }
$walk = Get-Item -LiteralPath $directory
while ($null -ne $walk) {
    if ($walk.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Linked credential directories are not supported.' }
    $walk = $walk.Parent
}
$identity = [Security.Principal.WindowsIdentity]::GetCurrent().User
$acl = New-Object Security.AccessControl.DirectorySecurity
$acl.SetAccessRuleProtection($true, $false)
$acl.SetOwner($identity)
$rule = New-Object Security.AccessControl.FileSystemAccessRule($identity, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
$acl.AddAccessRule($rule)
# Avoid module discovery inherited from a different PowerShell runtime.
if ($PSVersionTable.PSEdition -eq 'Desktop') {
    [IO.Directory]::SetAccessControl($directory, $acl)
} else {
    [IO.FileSystemAclExtensions]::SetAccessControl([IO.DirectoryInfo]::new($directory), $acl)
}
Write-Host ('AI Team: enter the existing password for ' + $profileData.guest_user + ' on target ' + $profileData.target)
Write-Host 'Input is masked. It is stored locally with access limited to your Windows account (not encrypted).'
Write-Host 'Do not paste the password into chat. Press Ctrl+C to cancel.'
$secret = Read-Host 'VM password' -AsSecureString
if ($secret.Length -eq 0) { throw 'Empty password; nothing saved.' }
$pointer = [IntPtr]::Zero
$stream = $null
$bytes = $null
try {
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
    $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    if ($plain.Contains("`r") -or $plain.Contains("`n")) { throw 'Multiline input is not supported.' }
    $bytes = [Text.Encoding]::UTF8.GetBytes($plain)
    $plain = $null
    $stream = [IO.File]::Open($destination, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    $stream.Write($bytes, 0, $bytes.Length)
    $stream.Flush()
    Write-Host 'LOGIN_SAVED: Codex can now verify the VM connection. You can close this window.'
} finally {
    if ($null -ne $stream) { $stream.Dispose() }
    if ($null -ne $bytes) { [Array]::Clear($bytes, 0, $bytes.Length) }
    if ($pointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
    $secret.Dispose()
}
