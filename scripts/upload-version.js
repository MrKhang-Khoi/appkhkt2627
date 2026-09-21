const fs = require('fs');
const path = require('path');
const https = require('https');

const versionPath = path.join(__dirname, '..', 'version.json');
const rawData = fs.readFileSync(versionPath, 'utf8');
const versionObj = JSON.parse(rawData);

function performHttpRequest(options, bodyData = null) {
  return new Promise((resolve, reject) => {
    const req = https.request(options, (res) => {
      let body = '';
      res.on('data', chunk => body += chunk);
      res.on('end', () => {
        resolve({ statusCode: res.statusCode, body });
      });
    });

    req.on('error', (err) => {
      reject(err);
    });

    if (bodyData) {
      req.write(bodyData);
    }
    req.end();
  });
}

async function updateBothEndpoints() {
  console.log('🔄 [Firebase RTDB] Executing atomic dual-endpoint update (PUT /version.json and PUT /app_release.json)...');
  const payload = JSON.stringify(versionObj);
  const putOptions = (nodePath) => ({
    hostname: 'cva-smartguardian-default-rtdb.asia-southeast1.firebasedatabase.app',
    path: nodePath,
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      'Content-Length': Buffer.byteLength(payload)
    }
  });

  const [resVersion, resRelease] = await Promise.all([
    performHttpRequest(putOptions('/version.json'), payload),
    performHttpRequest(putOptions('/app_release.json'), payload)
  ]);

  if (resVersion.statusCode !== 200) {
    throw new Error(`PUT /version.json failed with HTTP ${resVersion.statusCode}: ${resVersion.body}`);
  }
  if (resRelease.statusCode !== 200) {
    throw new Error(`PUT /app_release.json failed with HTTP ${resRelease.statusCode}: ${resRelease.body}`);
  }
  console.log('✅ [Firebase RTDB] Dual endpoint PUT succeeded (HTTP 200).');
}

async function readBackAndVerify() {
  console.log('🔍 [Firebase RTDB] Verifying read-back parity across both nodes (/version.json and /app_release.json)...');
  
  const getOptions = (nodePath) => ({
    hostname: 'cva-smartguardian-default-rtdb.asia-southeast1.firebasedatabase.app',
    path: nodePath,
    method: 'GET',
    headers: {
      'Accept': 'application/json'
    }
  });

  const [versionRes, releaseRes] = await Promise.all([
    performHttpRequest(getOptions('/version.json')),
    performHttpRequest(getOptions('/app_release.json'))
  ]);

  if (versionRes.statusCode !== 200) {
    throw new Error(`Failed to read back /version.json: HTTP ${versionRes.statusCode}`);
  }
  if (releaseRes.statusCode !== 200) {
    throw new Error(`Failed to read back /app_release.json: HTTP ${releaseRes.statusCode}`);
  }

  const vData = JSON.parse(versionRes.body);
  const rData = JSON.parse(releaseRes.body);

  const requiredFields = [
    'versionCode',
    'versionName',
    'sha256',
    'sizeBytes',
    'apkUrl',
    'apkFallbackUrl',
    'changelog'
  ];

  for (const field of requiredFields) {
    if (vData[field] === undefined) {
      throw new Error(`Missing required field '${field}' in /version.json response`);
    }
    if (rData[field] === undefined) {
      throw new Error(`Missing required field '${field}' in /app_release.json response`);
    }
    const expectedVal = JSON.stringify(versionObj[field]);
    const vVal = JSON.stringify(vData[field]);
    const rVal = JSON.stringify(rData[field]);

    if (vVal !== expectedVal) {
      throw new Error(`Field '${field}' in /version.json (${vVal}) mismatch with local version.json (${expectedVal})`);
    }
    if (rVal !== expectedVal) {
      throw new Error(`Field '${field}' in /app_release.json (${rVal}) mismatch with local version.json (${expectedVal})`);
    }
  }

  console.log('✅ [Firebase RTDB] 100% field parity verified across all required fields (versionCode, versionName, sha256, sizeBytes, apkUrl, apkFallbackUrl, changelog).');
}

async function main() {
  try {
    await updateBothEndpoints();
    await readBackAndVerify();
    console.log('🎉 Dual-node release update and full-field parity verification complete.');
    process.exit(0);
  } catch (err) {
    console.error('❌ [Firebase RTDB ERROR]', err.message);
    process.exit(1);
  }
}

main();
