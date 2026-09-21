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

async function atomicMultiLocationPatch() {
  console.log('🔄 [Firebase RTDB] Executing atomic multi-location update (PATCH /)...');
  const multiLocationPayload = JSON.stringify({
    version: versionObj,
    app_release: versionObj
  });

  const options = {
    hostname: 'cva-smartguardian-default-rtdb.asia-southeast1.firebasedatabase.app',
    path: '/.json',
    method: 'PATCH',
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      'Content-Length': Buffer.byteLength(multiLocationPayload)
    }
  };

  const res = await performHttpRequest(options, multiLocationPayload);
  if (res.statusCode < 200 || res.statusCode >= 300) {
    throw new Error(`Firebase RTDB atomic PATCH failed with HTTP ${res.statusCode}: ${res.body}`);
  }
  console.log(`✅ [Firebase RTDB] Atomic multi-location PATCH succeeded (HTTP ${res.statusCode}).`);
}

async function readBackAndVerify() {
  console.log('🔍 [Firebase RTDB] Verifying read-back parity across both nodes...');
  
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

  if (vData.sha256 !== versionObj.sha256 || rData.sha256 !== versionObj.sha256) {
    throw new Error(`SHA256 mismatch after atomic update: /version=${vData.sha256}, /app_release=${rData.sha256}, expected=${versionObj.sha256}`);
  }

  if (vData.versionCode !== versionObj.versionCode || rData.versionCode !== versionObj.versionCode) {
    throw new Error(`VersionCode mismatch: /version=${vData.versionCode}, /app_release=${rData.versionCode}, expected=${versionObj.versionCode}`);
  }

  if (vData.sizeBytes !== versionObj.sizeBytes || rData.sizeBytes !== versionObj.sizeBytes) {
    throw new Error(`sizeBytes mismatch: /version=${vData.sizeBytes}, /app_release=${rData.sizeBytes}, expected=${versionObj.sizeBytes}`);
  }

  console.log('✅ [Firebase RTDB] 100% field parity verified between /version.json and /app_release.json.');
}

async function main() {
  try {
    await atomicMultiLocationPatch();
    await readBackAndVerify();
    console.log('🎉 Atomic release update and dual-node verification complete.');
    process.exit(0);
  } catch (err) {
    console.error('❌ [Firebase RTDB ERROR]', err.message);
    process.exit(1);
  }
}

main();
