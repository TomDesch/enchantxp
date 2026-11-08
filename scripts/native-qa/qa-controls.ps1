$ErrorActionPreference = 'Stop'

function Assert-QaRegularPath {
    param([string]$Path, [switch]$Directory)
    $item = Get-Item -LiteralPath $Path -Force
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 -or
        $item.PSIsContainer -ne [bool]$Directory) {
        throw "Expected a regular path: $Path"
    }
}

function Assert-QaPortFree {
    param([int]$Port)
    $probe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $Port)
    try { $probe.Start() } finally { $probe.Stop() }
}

function Assert-QaJava {
    param([string]$JavaHome, [int]$Major, [switch]$Compiler)
    Assert-QaRegularPath $JavaHome -Directory
    Assert-QaRegularPath (Join-Path $JavaHome 'bin\java.exe')
    if ($Compiler) { Assert-QaRegularPath (Join-Path $JavaHome 'bin\javac.exe') }
    Assert-QaRegularPath (Join-Path $JavaHome 'release')
    $release = [IO.File]::ReadAllText((Join-Path $JavaHome 'release'))
    $version = [regex]::Match($release, '(?m)^JAVA_VERSION="((?:1\.)?(\d+)[^"]*)"')
    if (-not $version.Success -or [int]$version.Groups[2].Value -ne $Major) {
        throw "Expected Java $Major at the supplied JavaHome."
    }
}

function Get-QaProject {
    $repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
    [xml]$pom = Get-Content -LiteralPath (Join-Path $repository 'pom.xml')
    return @{
        Root = $repository
        Work = Join-Path $repository '.playtest'
        Version = [string]$pom.project.version
        Plugin = Join-Path $repository "target\$($pom.project.artifactId)-$($pom.project.version).jar"
    }
}

function New-QaDirectory {
    param([string]$Root, [string]$Name)
    if (-not (Test-Path -LiteralPath $Root)) { New-Item -ItemType Directory $Root | Out-Null }
    Assert-QaRegularPath $Root -Directory
    $directory = Join-Path $Root $Name
    if (Test-Path -LiteralPath $directory) { throw "Use a fresh directory name: $directory already exists." }
    [IO.Directory]::CreateDirectory($directory) | Out-Null
    return $directory
}

function Read-QaLog {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return '' }
    Assert-QaRegularPath $Path
    if ((Get-Item -LiteralPath $Path).Length -gt 16MB) { throw 'QA log exceeded the bounded 16 MiB limit.' }
    return [IO.File]::ReadAllText($Path)
}

function Test-QaReadyLog {
    param([string]$Log)
    return $Log -match 'Done \(.+\)!'
}

function Test-QaSummary {
    param([string]$Log)
    return $Log -match '\[EnchantQA\] QA SUMMARY passed=\d+ failed=\d+'
}

function Assert-QaResult {
    param([string]$Log, [bool]$Ready, [bool]$StoppedGracefully, [int]$ExitCode)
    if (-not $Ready -or -not $StoppedGracefully -or $ExitCode -ne 0) {
        throw 'Native QA requires ready loopback service and successful bounded stdin shutdown.'
    }
    $summaries = [regex]::Matches($Log, '\[EnchantQA\] QA SUMMARY passed=(\d+) failed=(\d+)')
    $passes = [regex]::Matches($Log, '\[EnchantQA\] QA PASS ')
    if ($summaries.Count -ne 1 -or $summaries[0].Groups[1].Value -ne '133' -or
        $summaries[0].Groups[2].Value -ne '0' -or $passes.Count -ne 133) {
        throw 'Native QA requires exactly 133 passing assertions and one zero-failure summary.'
    }
    if ($Log -match '\[EnchantQA\].*(QA (FAIL|ERROR)|failed:|could not|did not stop|Interrupted|restoration refused)') {
        throw 'Native QA reported an assertion, lifecycle or restoration failure.'
    }
}

function Stop-QaOwnedProcess {
    param($Process)
    if ($Process.HasExited) { return $false }
    $Process.StandardInput.WriteLine('stop')
    $Process.StandardInput.Flush()
    if (-not $Process.WaitForExit(90000)) {
        throw "Graceful stop timeout: inspect PID $($Process.Id), do not launch another server."
    }
    return $true
}
