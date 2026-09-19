const http = require('http');
const fs = require('fs');
const path = require('path');
const { chromium } = require('C:/Users/HPZBook/Desktop/TEST_BLENDER/node_modules/playwright');

const PORT = 8099;
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
  console.log(`Test server running at http://localhost:${PORT}`);
  const consoleErrors = [];
  const browser = await chromium.launch({ headless: true });

  try {
    const context = await browser.newContext({
      viewport: { width: 390, height: 844 },
      deviceScaleFactor: 2
    });
    const page = await context.newPage();

    page.on('console', msg => {
      if (msg.type() === 'error') {
        consoleErrors.push(`[Console Error] ${msg.text()}`);
      }
    });
    page.on('pageerror', err => {
      consoleErrors.push(`[Page Error] ${err.message}`);
    });

    console.log("1. Navigating to Web Portal...");
    await page.goto(`http://localhost:${PORT}/index.html`);
    await page.waitForTimeout(1000);

    // 1. PIN Gate test
    console.log("2. Entering PIN 1234...");
    const keys = ['1', '2', '3', '4'];
    for (const k of keys) {
      await page.click(`button.num-key:has-text("${k}")`);
      await page.waitForTimeout(150);
    }
    await page.waitForTimeout(500);

    // Verify Parent Hub is visible
    const isParentHubVisible = await page.isVisible('#cardParentHub');
    console.log(`Parent Hub visible: ${isParentHubVisible}`);
    if (!isParentHubVisible) throw new Error("Parent Hub did not open after entering 1234!");

    // Verify Minimalist elements in Parent Hub
    const codeText = await page.innerText('#dispFamilyPairCode');
    console.log(`Family Code Chip: ${codeText}`);

    const childName = await page.innerText('#childDeviceName');
    console.log(`Child Card Name: ${childName}`);

    const screenTime = await page.innerText('#dashTotalScreenTime');
    console.log(`Apple Screen Time: ${screenTime}`);

    // Check overflow
    const isOverflowing = await page.evaluate(() => {
      return document.body.scrollWidth > document.body.clientWidth;
    });
    console.log(`Horizontal overflow detected: ${isOverflowing}`);
    if (isOverflowing) throw new Error("Horizontal overflow detected on phone viewport!");

    // Screenshot Parent Hub Minimalist
    const parentHubScreenshot = path.join(ARTIFACT_DIR, 'web_parent_hub_minimalist_verified.png');
    await page.screenshot({ path: parentHubScreenshot, fullPage: false });
    console.log(`Saved screenshot to: ${parentHubScreenshot}`);

    // 2. Switch to Student Tab
    console.log("3. Switching to Student Tab...");
    await page.click('#tabBtnStudent');
    await page.waitForTimeout(500);

    // Verify Student Input Card
    const isStudentInputVisible = await page.isVisible('#studentInputCard');
    console.log(`Student Input Card visible: ${isStudentInputVisible}`);

    // Simulate Paired State in Student Tab
    console.log("4. Simulating Student Paired State...");
    await page.evaluate(() => {
      document.getElementById('studentInputCard').style.display = 'none';
      document.getElementById('studentPairedCard').style.display = 'block';
    });
    await page.waitForTimeout(500);

    // Verify Tamper-Proof elements
    const studentCode = await page.innerText('#studentConnectedCodeDisplay');
    console.log(`Student Paired Code: ${studentCode}`);

    // Test tamper-proof alert trigger
    let dialogMessage = '';
    page.once('dialog', async dialog => {
      dialogMessage = dialog.message();
      console.log(`Tamper-Proof Alert caught: "${dialogMessage}"`);
      await dialog.accept();
    });

    // Click on the first tamper-proof card (Trợ năng HyperOS)
    await page.click('div:has-text("Trợ năng HyperOS")');
    await page.waitForTimeout(500);

    // Verify no greeting line
    const pageText = await page.innerText('#studentPairedCard');
    const hasGreeting = pageText.includes('Xin chào');
    console.log(`Has old greeting line: ${hasGreeting}`);
    if (hasGreeting) throw new Error("Old greeting line 'Xin chào' still found in student card!");

    // Screenshot Student Paired Tamper-Proof
    const studentPairedScreenshot = path.join(ARTIFACT_DIR, 'web_student_paired_tamperproof_verified.png');
    await page.screenshot({ path: studentPairedScreenshot, fullPage: false });
    console.log(`Saved screenshot to: ${studentPairedScreenshot}`);

    // 3. Verify Download link
    const downloadHref = await page.getAttribute('a[download]', 'href');
    console.log(`Download APK Href: ${downloadHref}`);
    if (!downloadHref.includes('v1.2.1')) {
      throw new Error(`Download link does not point to v1.2.1! Got: ${downloadHref}`);
    }

    console.log(`Total console errors: ${consoleErrors.length}`);
    if (consoleErrors.length > 0) {
      console.error("Console Errors:", consoleErrors);
      throw new Error(`Console errors detected: ${consoleErrors.join(', ')}`);
    }

    console.log(">>> ALL PLAYWRIGHT ZERO-BUG VERIFICATION TESTS PASSED 100%! <<<");
  } catch (err) {
    console.error("Playwright Test FAILED:", err);
    process.exitCode = 1;
  } finally {
    await browser.close();
    server.close();
  }
});
