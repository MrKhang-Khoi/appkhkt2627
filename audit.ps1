# audit.ps1 - Kich ban 1-cham kich hoat Codex Auditor giam sat APP KHKT

Write-Host "===============================================================" -ForegroundColor Cyan
Write-Host "CODEX AUDITOR: GIAM SAT DU AN CVA-SMARTGUARDIAN (APP KHKT)" -ForegroundColor Cyan
Write-Host "===============================================================" -ForegroundColor Cyan

# Kiem tra trang thai 9Router
$ROUTER_URL = "http://127.0.0.1:20128"
$RouterRunning = $false

try {
    $response = Invoke-WebRequest -Uri $ROUTER_URL -TimeoutSec 2 -UseBasicParsing -ErrorAction Stop
    $RouterRunning = $true
} catch {
    $RouterRunning = $false
}

if (-not $RouterRunning) {
    Write-Host "[!] LOI: 9Router Gateway chua chay tren http://127.0.0.1:20128." -ForegroundColor Red
    Write-Host "[!] Vui long khoi dong 9Router Gateway service tren cong 20128 truoc khi chay audit." -ForegroundColor Yellow
    exit 1
}

# Chay Dong co Codex Audit
node ./scripts/codex-audit.js

if ($LASTEXITCODE -eq 0) {
    Write-Host "`n>>> [PASS] Code dat chuan CVA-SmartGuardian! San sang commit." -ForegroundColor Green
    exit 0
} else {
    Write-Host "`n>>> [FAIL] Phat hien loi! Antigravity bat buoc phai sua lai ma nguon." -ForegroundColor Red
    exit 1
}
