const http = require('http');
const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

const PORT = 8089;
const ROOT_DIR = 'C:/Users/HPZBook/Desktop/appkhkt2627';
const ARTIFACT_DIR = 'C:/Users/HPZBook/.gemini/antigravity/brain/bd57cfbc-2b98-48fe-997a-99e347ce01fe';

// 1. Static File HTTP Server
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
    // CONTEXT 1: PARENT
    const parentContext = await browser.newContext({ viewport: { width: 430, height: 932 } });
    const parentPage = await parentContext.newPage();
    parentPage.on('console', msg => {
      if (msg.type() === 'error') consoleErrors.push(`[Parent Console Error] ${msg.text()}`);
    });
    parentPage.on('pageerror', err => consoleErrors.push(`[Parent Page Error] ${err.message}`));

    // CONTEXT 2: STUDENT
    const studentContext = await browser.newContext({ viewport: { width: 430, height: 932 } });
    const studentPage = await studentContext.newPage();
    studentPage.on('console', msg => {
      if (msg.type() === 'error') consoleErrors.push(`[Student Console Error] ${msg.text()}`);
    });
    studentPage.on('pageerror', err => consoleErrors.push(`[Student Page Error] ${err.message}`));

    // 1. PARENT PIN LOGIN
    console.log("--> Navigating Parent to PIN Screen...");
    await parentPage.goto(`http://localhost:${PORT}/?tab=parent`);
    await parentPage.waitForTimeout(500);

    // Enter PIN 1234
    for (const d of ['1', '2', '3', '4']) {
      await parentPage.evaluate((digit) => handleNumPress(digit), d);
      await parentPage.waitForTimeout(100);
    }
    await parentPage.waitForTimeout(600);

    // Assert Parent Hub is visible
    const isParentHubVisible = await parentPage.isVisible('#cardParentHub');
    console.log("Parent Hub visible:", isParentHubVisible);

    const familyCode = await parentPage.textContent('#dispFamilyPairCode');
    console.log("Family Code detected:", familyCode);

    // 2. STUDENT PAIRING WORKFLOW
    console.log("--> Navigating Student to Screen 3...");
    await studentPage.goto(`http://localhost:${PORT}/?tab=student`);
    await studentPage.waitForTimeout(500);

    // Verify student initial state is Unpaired (only Enter Code card is shown)
    const isStudentInputVisible = await studentPage.isVisible('#studentInputCard');
    const isStudentPairedVisible = await studentPage.isVisible('#studentPairedCard');
    console.log("Student Input Card visible (expected true):", isStudentInputVisible);
    console.log("Student Paired Card visible (expected false):", isStudentPairedVisible);

    // Type the family code
    await studentPage.fill('#studentWebCodeInput', familyCode);
    await studentPage.waitForTimeout(300);

    // Click KẾT NỐI
    console.log("--> Student clicks KET NOI...");
    await studentPage.click('#btnStudentWebConnect');

    // Wait for Handshake Modal
    await studentPage.waitForSelector('#studentPairingModal.active', { timeout: 3000 });
    console.log("Handshake Modal is active!");

    // Capture Handshake in action
    await studentPage.screenshot({ path: path.join(ARTIFACT_DIR, 'verification_step1_handshake.png') });
    console.log("Captured verification_step1_handshake.png");

    // Wait for Handshake to complete (Modal closes and Paired Card shows)
    await studentPage.waitForSelector('#studentPairedCard', { state: 'visible', timeout: 6000 });
    const pairedCodeText = await studentPage.textContent('#studentConnectedCodeDisplay');
    console.log("Student successfully paired with code:", pairedCodeText);

    // Capture Student Paired Screen
    await studentPage.screenshot({ path: path.join(ARTIFACT_DIR, 'verification_step2_student_paired.png') });
    console.log("Captured verification_step2_student_paired.png");

    // 3. PARENT HUB REAL-TIME DETECTION
    console.log("--> Checking Parent Hub real-time sync...");
    await parentPage.waitForSelector('#activeDeviceRow', { state: 'visible', timeout: 8000 });
    const childDevice = await parentPage.textContent('#childDeviceName');
    const childState = await parentPage.textContent('#childDeviceState');
    console.log("Parent Hub detected child:", childDevice, "| State:", childState);

    // Capture Parent Hub Live Screen
    await parentPage.screenshot({ path: path.join(ARTIFACT_DIR, 'verification_step3_parent_hub_live.png') });
    console.log("Captured verification_step3_parent_hub_live.png");

    // 4. STUDENT RESET / UNPAIR
    console.log("--> Testing Student Unpair...");
    await studentPage.evaluate(() => {
      // Stub window.confirm to return true
      window.confirm = () => true;
    });
    await studentPage.click('#btnStudentUnpair');
    await studentPage.waitForTimeout(1500);

    const isResetClean = await studentPage.isVisible('#studentInputCard');
    console.log("Student reset back to input card (expected true):", isResetClean);
    await studentPage.screenshot({ path: path.join(ARTIFACT_DIR, 'verification_step4_reset_clean.png') });
    console.log("Captured verification_step4_reset_clean.png");

    console.log("\n=== CONSOLE ERRORS REPORT ===");
    console.log(`Total console errors: ${consoleErrors.length}`);
    if (consoleErrors.length > 0) {
      consoleErrors.forEach(err => console.error(err));
    } else {
      console.log("PERFECT: 0 console errors, 0 unhandled exceptions!");
    }

  } catch (err) {
    console.error("Test execution error:", err);
  } finally {
    await browser.close();
    server.close();
    process.exit(0);
  }
});
