[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$JavaHome)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'qa-controls.ps1')

function Assert-That {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw "FAIL: $Message" }
}

function Assert-Throws {
    param([scriptblock]$Action, [string]$Message)
    $thrown = $false
    try { & $Action | Out-Null } catch { $thrown = $true }
    Assert-That $thrown $Message
}

function New-FakeProcess {
    $writer = [pscustomobject]@{Lines=[Collections.Generic.List[string]]::new();Flushes=0}
    $writer | Add-Member ScriptMethod WriteLine {
        param([string]$Line)
        $this.Lines.Add($Line)
    }
    $writer | Add-Member ScriptMethod Flush { $this.Flushes++ }
    $process = [pscustomobject]@{
        Id=2147483647;HasExited=$false;StandardInput=$writer;Waits=0;Timeout=0;ExitOnWait=$true
    }
    $process | Add-Member ScriptMethod WaitForExit {
        param([int]$Timeout)
        $this.Waits++
        $this.Timeout=$Timeout
        $this.HasExited=$this.ExitOnWait
        return $this.HasExited
    }
    return $process
}

foreach ($name in @('build.ps1', 'run.ps1', 'qa-controls.ps1')) {
    $tokens = $null
    $errors = $null
    $null = [Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot $name), [ref]$tokens, [ref]$errors)
    Assert-That ($errors.Count -eq 0) "PowerShell syntax: $name"
}
$project = Get-QaProject
$root = New-QaDirectory $project.Work ('native-controls-' + [guid]::NewGuid().ToString('N'))
$name = 'native-plan-' + [guid]::NewGuid().ToString('N')
$planned = Join-Path $project.Work $name
try {
    $fakeJar = Join-Path $root 'plan-only.jar'
    [IO.File]::WriteAllText($fakeJar, 'Plan-only placeholder; never executed.')
    $hash = (Get-FileHash -LiteralPath $fakeJar).Hash
    $arguments = @{
        ServerJar=$fakeJar;PluginJar=$fakeJar;FixtureJar=$fakeJar;JavaHome=$JavaHome;
        Platform='Spigot';ServerName=$name
    }
    $plan = & (Join-Path $PSScriptRoot 'run.ps1') @arguments -Plan | ConvertFrom-Json
    Assert-That (-not (Test-Path -LiteralPath $planned)) 'Plan creates no server directory or EULA'
    Assert-That ((Get-FileHash -LiteralPath $fakeJar).Hash -eq $hash) 'Plan preserves input files'
    Assert-That ($plan.onlineMode -and $plan.bind -eq '127.0.0.1' -and
        -not $plan.rcon -and -not $plan.query -and $plan.requiresExplicitEula) 'Plan keeps the isolated safety contract'
    Assert-Throws { & (Join-Path $PSScriptRoot 'run.ps1') @arguments } 'Missing EULA authorization must fail before writes'
    Assert-That (-not (Test-Path -LiteralPath $planned)) 'Refused execution creates no server files'
    $arguments.ServerName = '..\outside'
    Assert-Throws { & (Join-Path $PSScriptRoot 'run.ps1') @arguments -Plan } 'Traversal is rejected'
    $arguments.ServerName = Split-Path $root -Leaf
    Assert-Throws { & (Join-Path $PSScriptRoot 'run.ps1') @arguments -Plan } 'An existing directory is never reused'
    Write-Output 'PASS: no-write Plan, explicit EULA gate and fresh scoped directories'

    $process = New-FakeProcess
    Assert-That (Stop-QaOwnedProcess $process) 'Owned running process stops'
    Assert-That ($process.StandardInput.Lines.Count -eq 1 -and $process.StandardInput.Lines[0] -eq 'stop' -and
        $process.StandardInput.Flushes -eq 1 -and $process.Timeout -eq 90000 -and $process.HasExited) 'Stop is flushed and bounded'
    Assert-That (-not (Stop-QaOwnedProcess $process)) 'Already exited is not graceful owned shutdown'
    Assert-That ($process.StandardInput.Lines.Count -eq 1) 'Exited process is not sent another stop'
    $process = New-FakeProcess
    $process.ExitOnWait = $false
    Assert-Throws { Stop-QaOwnedProcess $process } 'Shutdown timeout is a failure'
    Assert-That (-not $process.HasExited) 'Timeout never claims shutdown or kills an unrelated process'
    Write-Output 'PASS: once-only stdin stop, successful wait and visible shutdown timeout'

    $liveLog = Join-Path $root 'live.log'
    $sharing = [IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete
    $writer = [IO.File]::Open($liveLog, [IO.FileMode]::CreateNew, [IO.FileAccess]::ReadWrite, $sharing)
    try {
        $bytes = [Text.Encoding]::UTF8.GetBytes("Done (0.250s)!`n[EnchantQA] QA PASS live writer")
        $writer.Write($bytes, 0, $bytes.Length)
        $writer.Flush()
        Assert-That ((Read-QaLog $liveLog) -eq [Text.Encoding]::UTF8.GetString($bytes)) 'An open write-capable handle permits live log reads'
        $writer.SetLength(16MB)
        $writer.Flush()
        Assert-That ((Read-QaLog $liveLog).Length -eq 16MB) 'A log at the byte limit remains readable'
        $writer.SetLength(16MB + 1)
        $writer.Flush()
        Assert-Throws { Read-QaLog $liveLog } 'An oversized live log is rejected with the writer still open'
        $writer.SetLength($bytes.Length)
        [IO.File]::Move($liveLog, (Join-Path $root 'rotated.log'))
        Assert-That ((Read-QaLog $liveLog) -eq '') 'A rotated-away path is temporarily absent'
    } finally { $writer.Dispose() }
    $exclusive = [IO.File]::Open($liveLog, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try {
        Assert-Throws { Read-QaLog $liveLog } 'A real sharing denial remains a read failure'
    } finally { $exclusive.Dispose() }
    Write-Output 'PASS: shared live log reads, bounded bytes, rotation and surfaced read failures'

    $log = "Done (0.250s)!`n" + ((1..133 | ForEach-Object { "[EnchantQA] QA PASS assertion $_" }) -join "`n") +
        "`n[EnchantQA] QA SUMMARY passed=133 failed=0"
    Assert-That (Test-QaReadyLog $log) 'Burst output after Done retains readiness'
    Assert-That (-not (Test-QaReadyLog '[EnchantQA] QA PASS startup')) 'Assertions alone do not imply readiness'
    Assert-QaResult $log $true $true 0
    Assert-Throws { Assert-QaResult 'Done (0.250s)!' $true $true 0 } 'Startup-only output never passes QA'
    Assert-Throws { Assert-QaResult $log.Replace('failed=0', 'failed=1') $true $true 0 } 'Behavioral failures fail the run'
    Assert-Throws { Assert-QaResult $log.Replace('passed=133', 'passed=132') $true $true 0 } 'Incomplete assertion set fails'
    Assert-Throws { Assert-QaResult ($log + "`n[EnchantQA] QA config writer did not stop within five seconds") $true $true 0 } 'Shutdown failures after summary fail'
    Assert-Throws { Assert-QaResult ($log + "`n[EnchantQA] QA SUMMARY passed=133 failed=0") $true $true 0 } 'Duplicate summary fails'
    Assert-Throws { Assert-QaResult $log $false $true 0 } 'Unresponsive startup fails'
    Assert-Throws { Assert-QaResult $log $true $false 0 } 'Unverified graceful stop fails'
    Assert-Throws { Assert-QaResult $log $true $true 1 } 'Nonzero exit fails'
    Write-Output 'PASS: behavioral completeness, single summary and post-shutdown diagnostics'
} finally {
    Remove-Item -LiteralPath $root -Recurse -Force
}
