#!/usr/bin/env node
/**
 * VERIFY OTA DOWNLOAD & CHECKSUM GATEKEEPER
 * Kiểm thử tải trực tiếp file APK qua mạng, xác thực HTTP 200, đo dung lượng và đối chiếu SHA-256
 */
const fs = require('fs');
const path = require('path');
const https = require('https');
const crypto = require('crypto');
const { URL } = require('url');

const versionJsonPath = path.join(process.cwd(), 'version.json');
if (!fs.existsSync(versionJsonPath)) {
  console.error('❌ [OTA Gatekeeper] Không tìm thấy tệp version.json bắt buộc để kiểm định phát hành!');
  process.exit(1);
}

const vJson = JSON.parse(fs.readFileSync(versionJsonPath, 'utf8'));
const localApkPath = path.join(process.cwd(), 'apk', `CVA-SmartGuardian-v${vJson.versionName}.apk`);

if (!fs.existsSync(localApkPath)) {
  console.error(`❌ [OTA Gatekeeper] Không tìm thấy file APK local tại: ${localApkPath}`);
  process.exit(1);
}

const localBuffer = fs.readFileSync(localApkPath);
const localSha = crypto.createHash('sha256').update(localBuffer).digest('hex').toUpperCase();
if (localSha !== vJson.sha256) {
  console.error(`❌ [OTA Gatekeeper] Sai lệch SHA-256! File: ${localSha}, version.json: ${vJson.sha256}`);
  process.exit(1);
}

console.log(`✅ [OTA Gatekeeper] Binary local toàn vẹn: v${vJson.versionName} (SHA-256=${localSha})`);

// Kiểm thử tải từ mạng
const candidateUrls = [];
if (vJson.apkUrl) candidateUrls.push(vJson.apkUrl);
if (vJson.apkFallbackUrl) candidateUrls.push(vJson.apkFallbackUrl);
  candidateUrls.push(`https://raw.githubusercontent.com/MrKhang-Khoi/appkhkt2627/refs/heads/main/apk/CVA-SmartGuardian-v${vJson.versionName}.apk`);
  candidateUrls.push(`https://github.com/MrKhang-Khoi/appkhkt2627/raw/main/apk/CVA-SmartGuardian-v${vJson.versionName}.apk`);
  candidateUrls.push('https://raw.githubusercontent.com/MrKhang-Khoi/appkhkt2627/refs/heads/main/apk/app-release.apk');
  candidateUrls.push('https://github.com/MrKhang-Khoi/appkhkt2627/raw/main/apk/app-release.apk');
  candidateUrls.push(`https://raw.githubusercontent.com/MrKhang-Khoi/appkhkt2627/main/apk/CVA-SmartGuardian-v${vJson.versionName}.apk`);
  candidateUrls.push('https://raw.githubusercontent.com/MrKhang-Khoi/appkhkt2627/main/apk/app-release.apk');
const nowTs = Date.now();
candidateUrls.push(`https://raw.githubusercontent.com/MrKhang-Khoi/appkhkt2627/main/apk/CVA-SmartGuardian-v${vJson.versionName}.apk?t=${nowTs}`);
candidateUrls.push(`https://raw.githubusercontent.com/MrKhang-Khoi/appkhkt2627/main/apk/app-release.apk?t=${nowTs}`);
let headCommit = '';
let shortCommit = '';
try {
  const { execSync } = require('child_process');
  headCommit = execSync('git rev-parse HEAD', { encoding: 'utf8' }).trim();
  shortCommit = execSync('git rev-parse --short HEAD', { encoding: 'utf8' }).trim();
} catch (e) {
  console.warn(`[WARN] Không thể lấy commit hash: ${e.message}`);
}
if (shortCommit) {
  candidateUrls.push(`https://raw.githubusercontent.com/MrKhang-Khoi/appkhkt2627/${shortCommit}/apk/CVA-SmartGuardian-v${vJson.versionName}.apk`);
  candidateUrls.push(`https://raw.githubusercontent.com/MrKhang-Khoi/appkhkt2627/${shortCommit}/apk/app-release.apk`);
}
if (headCommit) {
  candidateUrls.push(`https://raw.githubusercontent.com/MrKhang-Khoi/appkhkt2627/${headCommit}/apk/CVA-SmartGuardian-v${vJson.versionName}.apk`);
  candidateUrls.push(`https://raw.githubusercontent.com/MrKhang-Khoi/appkhkt2627/${headCommit}/apk/app-release.apk`);
}

function downloadUrl(targetUrl, maxRedirects = 5) {
  return new Promise((resolve, reject) => {
    if (maxRedirects <= 0) return reject(new Error(`Quá nhiều lần redirect tại: ${targetUrl}`));
    const parsedUrl = new URL(targetUrl);
    const req = https.get(parsedUrl, (res) => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        const nextUrl = new URL(res.headers.location, targetUrl).href;
        res.resume();
        return resolve(downloadUrl(nextUrl, maxRedirects - 1));
      }
      if (res.statusCode !== 200) {
        res.resume();
        return reject(new Error(`HTTP ${res.statusCode} khi tải từ ${targetUrl}`));
      }
      const hash = crypto.createHash('sha256');
      let totalBytes = 0;
      res.on('data', chunk => {
        hash.update(chunk);
        totalBytes += chunk.length;
      });
      res.on('end', () => {
        const remoteSha = hash.digest('hex').toUpperCase();
        resolve({ targetUrl, statusCode: res.statusCode, totalBytes, remoteSha });
      });
    });
    req.on('error', reject);
    req.setTimeout(25000, () => req.destroy(new Error(`Timeout 25s khi tải ${targetUrl}`)));
  });
}

(async () => {
  let downloadSuccess = null;
  const errorLogs = [];

  for (const url of candidateUrls) {
    try {
      console.log(`⏳ Đang kiểm thử tải từ: ${url}...`);
      const result = await downloadUrl(url);
      if (result.statusCode === 200 && result.totalBytes > 1024 * 1024 && result.remoteSha === vJson.sha256) {
        downloadSuccess = result;
        break;
      } else {
        errorLogs.push(`${url}: HTTP ${result.statusCode}, Bytes: ${result.totalBytes}, Remote SHA: ${result.remoteSha} (kỳ vọng: ${vJson.sha256})`);
      }
    } catch (err) {
      errorLogs.push(`${url}: ${err.message}`);
    }
  }

  if (!downloadSuccess) {
    console.error(`❌ [OTA Gatekeeper] Tải file thất bại (0 URL trả về HTTP 200 hợp lệ)!`);
    errorLogs.forEach(e => console.error(`   - ${e}`));
    process.exit(1);
  }

  console.log(`🎉 [OTA Gatekeeper] KIỂM THỬ TẢI FILE THÀNH CÔNG!`);
  console.log(`   - Nguồn hoạt động: ${downloadSuccess.targetUrl}`);
  console.log(`   - Mã phản hồi: HTTP ${downloadSuccess.statusCode}`);
  console.log(`   - Dung lượng tải về: ${(downloadSuccess.totalBytes / (1024 * 1024)).toFixed(2)} MB (${downloadSuccess.totalBytes} bytes)`);
  console.log(`   - SHA-256 xác thực: ${downloadSuccess.remoteSha}`);
  process.exit(0);
})();
