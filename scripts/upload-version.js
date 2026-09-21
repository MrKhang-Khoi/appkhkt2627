const fs = require('fs');
const path = require('path');
const https = require('https');

const versionPath = path.join(__dirname, '..', 'version.json');
const data = fs.readFileSync(versionPath, 'utf8');

const options = {
  hostname: 'cva-smartguardian-default-rtdb.asia-southeast1.firebasedatabase.app',
  path: '/version.json',
  method: 'PUT',
  headers: {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(data)
  }
};

const req = https.request(options, (res) => {
  let body = '';
  res.on('data', chunk => body += chunk);
  res.on('end', () => {
    console.log(`[Firebase PUT] HTTP ${res.statusCode}:`, body);
    if (res.statusCode >= 200 && res.statusCode < 300) {
      process.exit(0);
    } else {
      process.exit(1);
    }
  });
});

req.on('error', (err) => {
  console.error('[Firebase PUT ERROR]', err);
  process.exit(1);
});

req.write(data);
req.end();
