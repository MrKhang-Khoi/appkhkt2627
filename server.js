const http = require('http');
const fs = require('fs');
const path = require('path');

const PORT = 8100;
const BASE_DIR = __dirname;

const mimeTypes = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.json': 'application/json',
  '.css': 'text/css',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon'
};

const server = http.createServer((req, res) => {
  let reqPath = req.url.split('?')[0];

  // Tự động chuyển hướng trang chủ vào Parent Dashboard
  if (reqPath === '/' || reqPath === '') {
    res.writeHead(302, { 'Location': '/index.html' });
    res.end();
    return;
  }

  const filePath = path.join(BASE_DIR, reqPath);

  if (fs.existsSync(filePath) && fs.statSync(filePath).isFile()) {
    const ext = path.extname(filePath);
    res.writeHead(200, {
      'Content-Type': mimeTypes[ext] || 'application/octet-stream',
      'Cache-Control': 'no-cache'
    });
    fs.createReadStream(filePath).pipe(res);
  } else {
    res.writeHead(404, { 'Content-Type': 'text/html; charset=utf-8' });
    res.end(`<h2>404 - Không tìm thấy tệp: ${reqPath}</h2><p><a href="/src/parent-dashboard/index.html">Quay về Bảng Điều Khiển Phụ Huynh</a></p>`);
  }
});

server.listen(PORT, '0.0.0.0', () => {
  console.log('================================================================');
  console.log('  CVA-SMARTGUARDIAN - MÁY CHỦ DEMO ĐANG CHẠY THỜI GIAN THỰC');
  console.log('================================================================');
  console.log(`  👉 Cổng KHKT & Test Lab 2 Màn Hình: http://localhost:${PORT}/index.html`);
  console.log(`  👉 Màn Hình Chặn Web Độc Hại: http://localhost:${PORT}/src/parent-dashboard/blocked.html?reason=Web%20khieu%20dam%20nguoi%20lon&cat=adult&url=https://phimsex.com`);
  console.log('================================================================\n');
});
