const fs = require('fs');
const path = require('path');
const https = require('https');

const versionPath = path.join(__dirname, '..', 'version.json');
const data = fs.readFileSync(versionPath, 'utf8');

function putEndpoint(endpoint) {
  return new Promise((resolve, reject) => {
    const options = {
      hostname: 'cva-smartguardian-default-rtdb.asia-southeast1.firebasedatabase.app',
      path: endpoint,
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
        console.log(`[Firebase PUT ${endpoint}] HTTP ${res.statusCode}:`, body);
        if (res.statusCode >= 200 && res.statusCode < 300) {
          resolve();
        } else {
          reject(new Error(`HTTP ${res.statusCode}`));
        }
      });
    });

    req.on('error', (err) => {
      console.error(`[Firebase PUT ERROR ${endpoint}]`, err);
      reject(err);
    });

    req.write(data);
    req.end();
  });
}

async function main() {
  await putEndpoint('/version.json');
  await putEndpoint('/app_release.json');
  console.log('Firebase RTDB version metadata updated successfully on both /version.json and /app_release.json.');
}

main().catch(() => process.exit(1));
