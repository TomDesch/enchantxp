[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$ServerJar,
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [Parameter(Mandatory=$true)][string]$FixtureJar,
    [Parameter(Mandatory=$true)][ValidateSet('Spigot','Paper')][string]$Platform,
    [string]$PluginJar,
    [ValidatePattern('^[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}$')][string]$ServerName = ('native-qa-' + [guid]::NewGuid().ToString('N')),
    [ValidateRange(1024,65535)][int]$Port = 25595,
    [ValidateRange(1,600)][int]$RunSeconds = 90,
    [switch]$AcceptEula,
    [switch]$Plan
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'qa-controls.ps1')
$project = Get-QaProject
Assert-QaJava $JavaHome 8
if (-not $PluginJar) { $PluginJar = $project.Plugin }
$server = (Resolve-Path -LiteralPath $ServerJar).Path
$plugin = (Resolve-Path -LiteralPath $PluginJar).Path
$fixture = (Resolve-Path -LiteralPath $FixtureJar).Path
foreach ($file in @($server, $plugin, $fixture)) { Assert-QaRegularPath $file }
if (Test-Path -LiteralPath $project.Work) { Assert-QaRegularPath $project.Work -Directory }
$directory = Join-Path $project.Work $ServerName
if (Test-Path -LiteralPath $directory) { throw "Use a new ServerName: $directory already exists." }
if ($Plan) {
    @{directory=$directory;platform=$Platform;server=$server;plugin=$plugin;fixture=$fixture;
        javaHome=$JavaHome;port=$Port;runSeconds=$RunSeconds;bind='127.0.0.1';onlineMode=$true;
        rcon=$false;query=$false;requiresExplicitEula=$true;
        serverSha256=(Get-FileHash -LiteralPath $server -Algorithm SHA256).Hash} | ConvertTo-Json
    return
}
if (-not $AcceptEula) { throw 'Execution requires explicit local EULA authorization with -AcceptEula.' }
Assert-QaPortFree $Port
$directory = New-QaDirectory $project.Work $ServerName
New-Item -ItemType Directory (Join-Path $directory 'plugins') | Out-Null
Copy-Item -LiteralPath $server -Destination (Join-Path $directory 'server.jar')
Copy-Item -LiteralPath $plugin -Destination (Join-Path $directory 'plugins\EnchantXP.jar')
Copy-Item -LiteralPath $fixture -Destination (Join-Path $directory 'plugins\EnchantQA.jar')
[IO.File]::WriteAllText((Join-Path $directory 'native-qa.txt'), $Platform, [Text.Encoding]::ASCII)
[IO.File]::WriteAllText((Join-Path $directory 'eula.txt'), 'eula=true', [Text.Encoding]::ASCII)
@"
server-ip=127.0.0.1
server-port=$Port
online-mode=true
enable-rcon=false
enable-query=false
max-players=1
spawn-protection=0
view-distance=4
level-name=qa-world
level-type=FLAT
generate-structures=false
gamemode=0
difficulty=0
motd=EnchantXP isolated native QA - Minecraft 1.12.2
"@ | Set-Content -LiteralPath (Join-Path $directory 'server.properties') -Encoding ascii
$info = [Diagnostics.ProcessStartInfo]::new()
$info.FileName = Join-Path $JavaHome 'bin\java.exe'
$info.Arguments = '-Xms256M -Xmx1024M -jar server.jar nogui'
$info.WorkingDirectory = $directory
$info.UseShellExecute = $false
$info.RedirectStandardInput = $true
$info.RedirectStandardOutput = $true
$info.RedirectStandardError = $true
$info.EnvironmentVariables['JAVA_HOME'] = $JavaHome
$info.EnvironmentVariables['PATH'] = "$JavaHome\bin;$env:PATH"
$process = $null
$ready = $false
$stopped = $false
$failure = $null
$stopFailure = $null
$exitCode = -1
$log = Join-Path $directory 'logs\latest.log'
try {
    $process = [Diagnostics.Process]::Start($info)
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    Write-Output "QA_DIRECTORY=$directory PID=$($process.Id)"
    $timer = [Diagnostics.Stopwatch]::StartNew()
    while (-not $process.HasExited -and $timer.Elapsed.TotalSeconds -lt 300) {
        if (Test-QaReadyLog (Read-QaLog $log)) {
            $tcp = [Net.Sockets.TcpClient]::new()
            try { $tcp.Connect('127.0.0.1', $Port); $ready = $tcp.Connected } finally { $tcp.Dispose() }
            if ($ready) { break }
        }
        Start-Sleep -Milliseconds 500
    }
    if (-not $ready) { throw "Startup failed or timed out. Inspect $directory" }
    $timer.Restart()
    while (-not $process.HasExited -and $timer.Elapsed.TotalSeconds -lt $RunSeconds) {
        if (Test-QaSummary (Read-QaLog $log)) { break }
        Start-Sleep -Milliseconds 500
    }
    if ($process.HasExited) { throw 'Server exited before native QA finished.' }
    if (-not (Test-QaSummary (Read-QaLog $log))) { throw 'Native QA summary did not arrive within the bounded window.' }
} catch { $failure = $_ }
finally {
    if ($null -ne $process) {
        try { $stopped = Stop-QaOwnedProcess $process }
        catch { $stopFailure = $_; Write-Warning $_.Exception.Message }
        if ($process.HasExited) {
            $stdout.GetAwaiter().GetResult() | Set-Content -LiteralPath (Join-Path $directory 'stdout.log')
            $stderr.GetAwaiter().GetResult() | Set-Content -LiteralPath (Join-Path $directory 'stderr.log')
            $exitCode = $process.ExitCode
            $process.Dispose()
        }
    }
}
if ($failure) { throw $failure }
if ($stopFailure) { throw $stopFailure }
Assert-QaResult (Read-QaLog $log) $ready $stopped $exitCode
Write-Output "PASS: $Platform 1.12.2, 133 native assertions, exact config restoration and graceful shutdown."
