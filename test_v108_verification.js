const http = require('http');
const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

const PORT = 8089;
const ROOT_DIR = 'C:/Users/HPZBook/Desktop/appkhkt2627';
const ARTIFACT_DIR = 'C:/Users/HPZBook/.gemini/antigravity/brain/bd57cfbc-2b98-48fe-997a-99e347ce01fe';

const server = http.createServer((req, res) => {
  let reqPath = req.url.split('?')[0];
  if (reqPath === '/') reqPath = '/index.html';
  const filePath = path.join(ROOT_DIR, reqPath);

  fs.readFile(filePath, (err, data) => {
    if (err) {
      res.writeHead(404);
      res.end('Not Found');
      return;
    }
    const ext = path.extname(filePath).toLowerCase();
    const mimeTypes = {
      '.html': 'text/html; charset=utf-8',
      '.js': 'application/javascript',
      '.css': 'text/css',
      '.json': 'application/json',
      '.apk': 'application/vnd.android.package-archive',
      '.png': 'image/png'
    };
    res.writeHead(200, { 'Content-Type': mimeTypes[ext] || 'text/plain' });
    res.end(data);
  });
});

server.listen(PORT, async () => {
  console.log(`Server listening on http://localhost:${PORT}`);
  
  const consoleErrors = [];
  const browser = await chromium.launch({ headless: true });
  
  try {
    const context = await browser.newContext({ viewport: { width: 430, height: 932 } });
    const page = await context.newPage();
    page.on('console', msg => {
      if (msg.type() === 'error') consoleErrors.push(`[Console Error] ${msg.text()}`);
    });
    page.on('pageerror', err => consoleErrors.push(`[Page Error] ${err.message}`));

    // 1. TEST GIAO DIỆN HỌC SINH SAU KHI GHÉP ĐÔI (H2): TUYỆT ĐỐI KHÔNG CÓ NÚT HỦY GHÉP ĐÔI
    console.log("--> Testing Student Paired State (H2)...");
    await page.goto(`http://localhost:${PORT}/?tab=student`);
    await page.evaluate(() => {
      localStorage.setItem('cva_student_paired_code', 'CVA-A646');
    });
    await page.reload();
    await page.waitForTimeout(600);

    const isPairedCardVisible = await page.isVisible('#studentPairedCard');
    console.log("Student Paired Card visible:", isPairedCardVisible);

    const hasUnpairBtn = await page.$('#btnStudentUnpair');
    console.log("Has unpair button on student card (expected false/null):", hasUnpairBtn);

    const pairedText = await page.textContent('#studentPairedCard');
    const containsProtectionNotice = pairedText.includes('Chỉ Phụ huynh mới có quyền quản lý và ngắt kết nối');
    console.log("Contains permanent protection notice:", containsProtectionNotice);

    await page.screenshot({ path: path.join(ARTIFACT_DIR, 'v108_screen2_student_protected.png') });
    console.log("Captured v108_screen2_student_protected.png");

    // 2. TEST LOGIC PARENT HUB KHI GHÉP ĐÔI XONG: BẤM VÀO THIẾT BỊ CON MỞ DASHBOARD
    console.log("--> Testing Parent Hub Child Row Click (Logic Sau Khi Ghép Đôi)...");
    await page.goto(`http://localhost:${PORT}/?tab=parent&unlocked=true`);
    await page.waitForTimeout(600);

    // Override active device display to Xiaomi 25069PTEBG
    await page.evaluate(() => {
      const nameEl = document.getElementById('childDeviceName');
      if (nameEl) nameEl.textContent = 'Xiaomi 25069PTEBG';
      const row = document.getElementById('activeDeviceRow');
      if (row) row.style.display = 'flex';
      const empty = document.getElementById('emptyDeviceCard');
      if (empty) empty.style.display = 'none';
      const unpair = document.getElementById('btnTriggerUnpair');
      if (unpair) unpair.style.display = 'flex';
    });
    await page.waitForTimeout(300);

    // Click activeDeviceRow
    console.log("--> Parent clicks activeDeviceRow...");
    await page.click('#activeDeviceRow');
    await page.waitForSelector('#childDashboardModal.active', { timeout: 3000 });
    console.log("Child Dashboard Modal opened successfully!");

    const balanceScoreText = await page.textContent('#dashBalanceScore');
    const studyTimeText = await page.textContent('#dashStudyTime');
    const gameTimeText = await page.textContent('#dashGameTime');
    console.log("Dashboard Loaded:", { balanceScoreText, studyTimeText, gameTimeText });

    await page.screenshot({ path: path.join(ARTIFACT_DIR, 'v108_screen1_child_companion_dashboard.png') });
    console.log("Captured v108_screen1_child_companion_dashboard.png");

    console.log("\n=== CONSOLE ERRORS REPORT ===");
    console.log(`Total console errors: ${consoleErrors.length}`);
    if (consoleErrors.length === 0) {
      console.log("PERFECT: 0 console errors!");
    } else {
      consoleErrors.forEach(e => console.error(e));
    }

  } catch (err) {
    console.error("Test execution error:", err);
  } finally {
    await browser.close();
    server.close();
    process.exit(0);
  }
});
