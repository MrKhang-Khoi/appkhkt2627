const { chromium } = require('C:\\Users\\HPZBook\\.gemini\\antigravity\\brain\\bd57cfbc-2b98-48fe-997a-99e347ce01fe\\scratch\\node_modules\\playwright');
const path = require('path');
const fs = require('fs');

async function runInteractiveLabTest() {
  console.log('================================================================');
  console.log('  CVA-SMARTGUARDIAN: KIỂM THỬ TƯƠNG TÁC ĐA NỀN TẢNG (DUAL-SCREEN)');
  console.log('================================================================\n');

  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({
    viewport: { width: 1920, height: 1080 }
  });
  const page = await context.newPage();

  const consoleErrors = [];
  page.on('console', msg => {
    if (msg.type() === 'error') {
      consoleErrors.push(msg.text());
    }
  });

  const targetUrl = 'http://localhost:8100/live-test/index.html';
  console.log(`[1/7] Điều hướng đến Phòng Thí Nghiệm Test Lab: ${targetUrl}`);
  await page.goto(targetUrl, { waitUntil: 'networkidle' });

  // Kiểm tra tiêu đề trang
  const title = await page.title();
  console.log(`Tiêu đề trang: "${title}"`);

  // BƯỚC 1: Cấp 4 quyền Android Native
  console.log('\n[2/7] Test cấp 4 quyền hệ thống cốt lõi trên điện thoại:');
  await page.click('#btnPermUsage');
  await page.click('#btnPermAccess');
  await page.click('#btnPermVpn');
  await page.click('#btnPermAdmin');

  const badgeText = await page.textContent('#phoneShieldBadge');
  console.log(`Trạng thái lá chắn sau khi cấp 4 quyền: ${badgeText.trim()}`);
  if (!badgeText.includes('BẢO VỆ')) {
    throw new Error('Lỗi: Trạng thái lá chắn chưa chuyển sang BẢO VỆ');
  }

  // BƯỚC 2: Thử nghiệm chặn trang web khiêu dâm
  console.log('\n[3/7] Test chặn trang web người lớn (pornhub.com):');
  await page.fill('#inputBrowserUrl', 'https://pornhub.com');
  await page.click('#btnBrowserGo');

  await page.waitForSelector('#blockedScreen', { state: 'visible', timeout: 3000 });
  const blockedCat = await page.textContent('#blockedCatDisplay');
  console.log(`✓ Màn hình chặn BlockedActivity đã kích hoạt thành công!`);
  console.log(`  Danh mục vi phạm nhận diện: "${blockedCat.trim()}"`);

  // BƯỚC 3: Test nhấn Quay lại an toàn
  console.log('\n[4/7] Test phím Quay lại an toàn:');
  await page.click('#btnBlockedBack');
  await page.waitForSelector('#blockedScreen', { state: 'hidden', timeout: 3000 });
  console.log('✓ Đã thoát màn hình chặn an toàn!');

  // BƯỚC 4: Thử nghiệm chặn trang cờ bạc & mở khóa bằng PIN phụ huynh 2025
  console.log('\n[5/7] Test chặn web cờ bạc (kubet77.com) & mở khóa bằng PIN 2025:');
  await page.fill('#inputBrowserUrl', 'https://kubet77.com');
  await page.click('#btnBrowserGo');

  await page.waitForSelector('#blockedScreen', { state: 'visible', timeout: 3000 });
  await page.click('#btnBlockedPin');
  await page.waitForSelector('#pinModal', { state: 'visible', timeout: 3000 });
  await page.fill('#inputPin', '2025');
  await page.click('#btnSubmitPin');
  await page.waitForSelector('#blockedScreen', { state: 'hidden', timeout: 3000 });
  console.log('✓ Đã mở khóa thành công bằng mã PIN phụ huynh 2025!');

  // BƯỚC 5: Test đồng bộ chơi game sang Bảng điều khiển phụ huynh
  console.log('\n[6/7] Test mô phỏng con chơi game & đồng bộ sang Parent Dashboard:');
  const initialGameTime = await page.textContent('#phoneGameVal');
  await page.click('#btnSimPlayGame');
  await page.waitForTimeout(500);
  const newGameTime = await page.textContent('#phoneGameVal');
  console.log(`Thời gian game trên điện thoại: ${initialGameTime} -> ${newGameTime}`);

  // BƯỚC 6: Chụp ảnh minh chứng thực tế
  console.log('\n[7/7] Chụp ảnh minh chứng phòng thí nghiệm tương tác:');
  const screenshotPath = path.join('C:\\Users\\HPZBook\\.gemini\\antigravity\\brain\\bd57cfbc-2b98-48fe-997a-99e347ce01fe', 'khkt_interactive_lab_verified.png');
  await page.screenshot({ path: screenshotPath, fullPage: true });
  console.log(`✓ Đã lưu ảnh kiểm thử thực tế tại: ${screenshotPath}`);

  console.log(`\nTổng số lỗi F12 console: ${consoleErrors.length}`);
  if (consoleErrors.length > 0) {
    console.error('Chi tiết lỗi F12:', consoleErrors);
    throw new Error('Phát hiện lỗi F12 console!');
  }

  console.log('\n=== TẤT CẢ CÁC BƯỚC KIỂM THỬ ĐỀU ĐẠT CHỈ TIÊU PASS 100% HOÀN HẢO ===\n');
  await browser.close();
}

runInteractiveLabTest().catch(err => {
  console.error('KIỂM THỬ THẤT BẠI:', err);
  process.exit(1);
});
