const http = require('http');
const fs = require('fs');
const path = require('path');

// Playwright from scratch directory
const { chromium } = require('C:\\Users\\HPZBook\\.gemini\\antigravity\\brain\\bd57cfbc-2b98-48fe-997a-99e347ce01fe\\scratch\\node_modules\\playwright');

const PORT = 8101;
const BASE_DIR = 'c:\\Users\\HPZBook\\Desktop\\PM ALL\\khkt-smart-guardian';
const ARTIFACTS_DIR = 'C:\\Users\\HPZBook\\.gemini\\antigravity\\brain\\bd57cfbc-2b98-48fe-997a-99e347ce01fe';

// Simple static server
const server = http.createServer((req, res) => {
  let reqPath = req.url.split('?')[0];
  if (reqPath === '/' || reqPath === '') reqPath = '/src/parent-dashboard/index.html';
  const filePath = path.join(BASE_DIR, reqPath);

  if (fs.existsSync(filePath)) {
    const ext = path.extname(filePath);
    const mimeTypes = {
      '.html': 'text/html; charset=utf-8',
      '.js': 'text/javascript; charset=utf-8',
      '.json': 'application/json',
      '.css': 'text/css',
      '.png': 'image/png'
    };
    res.writeHead(200, { 'Content-Type': mimeTypes[ext] || 'application/octet-stream' });
    fs.createReadStream(filePath).pipe(res);
  } else {
    res.writeHead(404);
    res.end('Not found: ' + reqPath);
  }
});

server.listen(PORT, async () => {
  console.log(`Test server running at http://localhost:${PORT}`);

  try {
    const browser = await chromium.launch({ headless: true });
    
    // --- 1. TEST GIAO DIỆN DESKTOP (1920x1080) ---
    console.log('\n--- 1. TEST GIAO DIỆN DESKTOP (1920x1080) ---');
    const desktopPage = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
    const consoleErrors = [];

    desktopPage.on('console', msg => {
      if (msg.type() === 'error') {
        consoleErrors.push(msg.text());
        console.error('[Browser Error]', msg.text());
      }
    });

    desktopPage.on('dialog', async dialog => {
      console.log('Bắt thông báo hộp thoại:', dialog.message().slice(0, 40) + '...');
      await dialog.accept();
    });

    await desktopPage.goto(`http://localhost:${PORT}/src/parent-dashboard/index.html`, { waitUntil: 'load' });
    await desktopPage.waitForTimeout(600);

    // Kiểm tra các chỉ số hiển thị
    const totalTimeText = await desktopPage.locator('#valTotalScreenTime').innerText();
    const gameTimeText = await desktopPage.locator('#valGameTime').innerText();
    const studyTimeText = await desktopPage.locator('#valStudyTime').innerText();
    const ratioText = await desktopPage.locator('#valRatio').innerText();

    console.log('Tổng thời gian:', totalTimeText);
    console.log('Thời gian chơi game:', gameTimeText);
    console.log('Thời gian học tập:', studyTimeText);
    console.log('Chỉ số cân bằng R_GS:', ratioText);

    if (!totalTimeText || !gameTimeText || !studyTimeText) {
      throw new Error('Các thẻ metric không hiển thị đúng dữ liệu');
    }

    // Chụp ảnh bằng chứng Dashboard Desktop
    await desktopPage.screenshot({ path: path.join(ARTIFACTS_DIR, 'khkt_parent_dashboard_desktop.png') });
    console.log('Đã chụp ảnh Dashboard Desktop: khkt_parent_dashboard_desktop.png');

    // Bấm nút thưởng điểm
    await desktopPage.locator('#btnRewardStudent').click();
    await desktopPage.waitForTimeout(400);
    const streakText = await desktopPage.locator('#valStreakDays').innerText();
    console.log('Chuỗi ngày sau khi thưởng điểm:', streakText);
    if (!streakText.includes('6 Ngày')) {
      throw new Error('Nút thưởng điểm không cập nhật chuỗi ngày Streak');
    }

    // --- 3. TEST GIẢ LẬP CON MỞ GAME THỜI GIAN THỰC ---
    console.log('\n--- 3. TEST GIẢ LẬP CON MỞ GAME THỜI GIAN THỰC ---');
    await desktopPage.locator('#btnSimulateActivity').click();
    await desktopPage.waitForTimeout(400);

    const updatedGameTime = await desktopPage.locator('#valGameTime').innerText();
    const updatedRatio = await desktopPage.locator('#valRatio').innerText();
    const subGameStatus = await desktopPage.locator('#subGameStatus').innerText();

    console.log('Thời gian game sau giả lập:', updatedGameTime);
    console.log('Chỉ số R_GS sau khi chơi game thêm:', updatedRatio);
    console.log('Trạng thái cảnh báo hạn mức:', subGameStatus);

    if (!subGameStatus.includes('ĐÃ VƯỢT HẠN MỨC')) {
      throw new Error('Hệ thống không kích hoạt cảnh báo vượt hạn mức chơi game');
    }

    // Chụp ảnh Dashboard sau khi có cảnh báo vượt hạn mức
    await desktopPage.screenshot({ path: path.join(ARTIFACTS_DIR, 'khkt_parent_dashboard_alert_triggered.png') });

    // --- 3.5. TEST TƯỜNG LỬA CHẶN WEB ĐỘC HẠI (SAFE WEB SHIELD) ---
    console.log('\n--- 3.5. TEST TƯỜNG LỬA CHẶN WEB ĐỘC HẠI (SAFE WEB SHIELD) ---');
    // 1. Kiểm tra các nút toggle bảo vệ
    const isAdultChecked = await desktopPage.locator('#toggleBlockAdult').isChecked();
    const isScamChecked = await desktopPage.locator('#toggleBlockScam').isChecked();
    const isGamblingChecked = await desktopPage.locator('#toggleBlockGambling').isChecked();
    console.log('Trạng thái chốt chặn Tình dục:', isAdultChecked, '| Lừa đảo:', isScamChecked, '| Cờ bạc:', isGamblingChecked);
    if (!isAdultChecked || !isScamChecked || !isGamblingChecked) {
      throw new Error('Các chốt chặn website độc hại chưa được bật mặc định');
    }

    // 2. Thêm domain cấm riêng của phụ huynh
    await desktopPage.locator('#inputCustomDomain').fill('webxau.vn');
    await desktopPage.locator('#btnAddCustomDomain').click();
    await desktopPage.waitForTimeout(300);
    const customListText = await desktopPage.locator('#customDomainList').innerText();
    console.log('Danh sách domain riêng sau khi thêm:', customListText);
    if (!customListText.includes('webxau.vn')) {
      throw new Error('Chưa thêm được domain tùy chỉnh vào danh sách cấm');
    }

    // 3. Thử nghiệm URL Tester Sandbox với link khiêu dâm
    await desktopPage.locator('#inputTestUrl').fill('https://phimsexvn.net/video-hot');
    await desktopPage.locator('#btnTestUrl').click();
    await desktopPage.waitForTimeout(300);
    const resultText = await desktopPage.locator('#testerResultBox').innerText();
    const isBlockedClass = await desktopPage.locator('#testerResultBox').evaluate(el => el.classList.contains('blocked'));
    console.log('Kết quả kiểm tra link phimsexvn.net:', isBlockedClass ? 'CHẶN THÀNH CÔNG' : 'FAIL');
    console.log('Nội dung kết quả:', resultText.slice(0, 60) + '...');
    if (!isBlockedClass || !resultText.includes('PHÁT HIỆN VI PHẠM')) {
      throw new Error('Tường lửa không phát hiện và chặn website khiêu dâm');
    }

    // 4. Thử nghiệm URL an toàn (Azota)
    await desktopPage.locator('#inputTestUrl').fill('https://azota.vn/bai-tap');
    await desktopPage.locator('#btnTestUrl').click();
    await desktopPage.waitForTimeout(300);
    const safeResultText = await desktopPage.locator('#testerResultBox').innerText();
    const isSafeClass = await desktopPage.locator('#testerResultBox').evaluate(el => el.classList.contains('safe'));
    console.log('Kết quả kiểm tra link Azota an toàn:', isSafeClass ? 'PASS' : 'FAIL');
    if (!isSafeClass || !safeResultText.includes('TRANG AN TOÀN')) {
      throw new Error('Hệ thống chặn nhầm website học tập an toàn');
    }

    // 5. Mở trang màn hình chặn blocked.html và chụp ảnh bằng chứng
    const blockedPage = await browser.newPage({ viewport: { width: 1280, height: 720 } });
    await blockedPage.goto(`http://localhost:${PORT}/src/parent-dashboard/blocked.html?reason=Phat%20hien%20noi%20dung%20khieu%20dam%20va%20lua%20dao&cat=ADULT&url=https://phimsex.com/video`, { waitUntil: 'load' });
    await blockedPage.waitForTimeout(400);
    const blockedTitle = await blockedPage.locator('h1').innerText();
    console.log('Tiêu đề màn hình chặn:', blockedTitle);
    if (!blockedTitle.includes('TRANG WEB ĐÃ BỊ CHẶN')) {
      throw new Error('Màn hình cảnh báo blocked.html không hiển thị đúng');
    }
    await blockedPage.screenshot({ path: path.join(ARTIFACTS_DIR, 'khkt_blocked_splash_page.png') });
    console.log('Đã chụp ảnh màn hình chặn: khkt_blocked_splash_page.png');
    await blockedPage.close();

    // --- 4. TEST MOBILE VIEWPORT (390x844) & CHỐNG TRÀN NGANG ---
    console.log('\n--- 4. TEST MOBILE VIEWPORT (390x844) ---');
    const mobilePage = await browser.newPage({
      viewport: { width: 390, height: 844 },
      userAgent: 'Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X)'
    });
    await mobilePage.goto(`http://localhost:${PORT}/src/parent-dashboard/index.html`, { waitUntil: 'load' });
    await mobilePage.waitForTimeout(500);

    const overflowCheck = await mobilePage.evaluate(() => {
      return {
        overflow: document.documentElement.scrollWidth > document.documentElement.clientWidth,
        scrollWidth: document.documentElement.scrollWidth,
        clientWidth: document.documentElement.clientWidth
      };
    });
    console.log('Kiểm tra tràn ngang Mobile (390px):', overflowCheck);
    if (overflowCheck.overflow) {
      throw new Error(`Tràn ngang màn hình Mobile: scrollWidth ${overflowCheck.scrollWidth} > clientWidth ${overflowCheck.clientWidth}`);
    }

    await mobilePage.screenshot({ path: path.join(ARTIFACTS_DIR, 'khkt_parent_dashboard_mobile.png') });
    console.log('Đã chụp ảnh Dashboard Mobile: khkt_parent_dashboard_mobile.png');

    // Kiểm tra Console Errors
    console.log('\nTổng số lỗi F12 console:', consoleErrors.length);
    if (consoleErrors.length > 0) {
      throw new Error('Phát hiện lỗi F12 console: ' + consoleErrors.join('; '));
    }

    await browser.close();
    console.log('\n=== TẤT CẢ CÁC BƯỚC KIỂM THỬ KỸ THUẬT ĐỀU PASS 100% HOÀN HẢO ===');
  } catch (err) {
    console.error('TEST THẤT BẠI:', err);
    process.exit(1);
  } finally {
    server.close();
  }
});
