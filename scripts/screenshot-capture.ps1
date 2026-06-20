$scriptName = [System.IO.Path]::GetFileName($MyInvocation.MyCommand.Path)
$currentPid = $PID

# Kill any other PowerShell process running this same script name
Get-CimInstance Win32_Process -Filter "name = 'powershell.exe'" |
    Where-Object { $_.ProcessId -ne $currentPid -and $_.CommandLine -like "*$scriptName*" } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }

$scriptDir  = Split-Path -Parent $MyInvocation.MyCommand.Path
$pyScript   = Join-Path $scriptDir "screenshot-capture.py"
$logFile    = Join-Path $scriptDir "screenshot-capture.log"

while ($true) {
    try {
        $output = & python $pyScript 2>&1
        if ($LASTEXITCODE -ne 0) {
            $ts = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
            "$ts [ERROR exit=$LASTEXITCODE] $output" | Out-File -FilePath $logFile -Append -Encoding utf8
        }
    } catch {
        $ts = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
        "$ts [EXCEPTION] $_" | Out-File -FilePath $logFile -Append -Encoding utf8
    }
    Start-Sleep -Seconds 10
}
