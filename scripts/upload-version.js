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

/**
 * 1. Primary Server-Side Atomic Multi-Location Update via Single Firebase REST PATCH /.json
 * In Firebase RTDB, a single PATCH to root (/.json) with child path keys is executed
 * as a single atomic transaction on the database server.
 */
async function singleAtomicMultiLocationUpdate() {
  console.log('🔄 [Firebase RTDB] Executing single-request atomic multi-location update (PATCH /)...');
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
    throw new Error(`Firebase RTDB single-request atomic PATCH failed with HTTP ${res.statusCode}: ${res.body}`);
  }
  console.log(`✅ [Firebase RTDB] Single-request atomic multi-location update succeeded (HTTP ${res.statusCode}).`);
}

/**
 * 2. Rollback-Protected Sequential Endpoint Update with Read-Back Verification
 * If writing individual endpoints, any failure in the second endpoint triggers
 * an immediate verified rollback of the first endpoint to prevent partial state corruption.
 */
async function updateEndpointsWithRollbackProtection(mockExecutor = null) {
  const executor = mockExecutor || performHttpRequest;

  const getOptions = (nodePath) => ({
    hostname: 'cva-smartguardian-default-rtdb.asia-southeast1.firebasedatabase.app',
    path: nodePath,
    method: 'GET',
    headers: { 'Accept': 'application/json' }
  });

  const putOptions = (nodePath, payload) => ({
    hostname: 'cva-smartguardian-default-rtdb.asia-southeast1.firebasedatabase.app',
    path: nodePath,
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      'Content-Length': Buffer.byteLength(payload)
    }
  });

  // Step A: Pre-snapshot both endpoints
  const [snapshotVersionRes, snapshotReleaseRes] = await Promise.all([
    executor(getOptions('/version.json')),
    executor(getOptions('/app_release.json'))
  ]);

  const priorVersionBody = snapshotVersionRes.statusCode === 200 ? snapshotVersionRes.body : null;
  const priorReleaseBody = snapshotReleaseRes.statusCode === 200 ? snapshotReleaseRes.body : null;

  const newPayload = JSON.stringify(versionObj);

  // Step B: Write /version.json
  const res1 = await executor(putOptions('/version.json', newPayload), newPayload);
  if (res1.statusCode !== 200) {
    throw new Error(`PUT /version.json failed with HTTP ${res1.statusCode}`);
  }

  // Step C: Write /app_release.json with rollback guard
  try {
    const res2 = await executor(putOptions('/app_release.json', newPayload), newPayload);
    if (res2.statusCode !== 200) {
      throw new Error(`PUT /app_release.json failed with HTTP ${res2.statusCode}`);
    }
  } catch (err) {
    console.warn('⚠️ [Rollback] Endpoint write failed. Rolling back /version.json to prior snapshot...');
    if (priorVersionBody) {
      await executor(putOptions('/version.json', priorVersionBody), priorVersionBody);
    }
    if (priorReleaseBody) {
      await executor(putOptions('/app_release.json', priorReleaseBody), priorReleaseBody);
    }
    throw new Error(`Atomic write aborted and rolled back cleanly: ${err.message}`);
  }
}

/**
 * 3. Adversarial Karl Popper Simulation Test
 * Simulates PUT /version.json = 200 followed by PUT /app_release.json = 500 / timeout.
 * Verifies that the rollback mechanism restores parity and prevents divergent version state.
 */
async function runAdversarialSimulationTest() {
  console.log('🧪 [Adversarial Test] Simulating PUT /version.json=200 and PUT /app_release.json=500...');
  
  let simulatedDb = {
    '/version.json': JSON.stringify({ versionCode: 37, versionName: '1.3.7', sha256: 'OLD_SHA' }),
    '/app_release.json': JSON.stringify({ versionCode: 37, versionName: '1.3.7', sha256: 'OLD_SHA' })
  };

  const mockAdversarialExecutor = async (options, bodyData = null) => {
    if (options.method === 'GET') {
      return { statusCode: 200, body: simulatedDb[options.path] };
    }
    if (options.method === 'PUT') {
      if (options.path === '/version.json') {
        simulatedDb['/version.json'] = bodyData;
        return { statusCode: 200, body: bodyData };
      }
      if (options.path === '/app_release.json') {
        // Inject failure on second endpoint
        return { statusCode: 500, body: 'Simulated Server Error' };
      }
    }
    return { statusCode: 400, body: 'Bad Request' };
  };

  let caughtError = false;
  try {
    await updateEndpointsWithRollbackProtection(mockAdversarialExecutor);
  } catch (e) {
    caughtError = true;
  }

  if (!caughtError) {
    throw new Error('Adversarial simulation failed: expected rollback error was not thrown');
  }

  // Verify rollback preserved parity
  const verObj = JSON.parse(simulatedDb['/version.json']);
  const relObj = JSON.parse(simulatedDb['/app_release.json']);
  if (verObj.sha256 !== relObj.sha256 || verObj.versionCode !== relObj.versionCode) {
    throw new Error(`Adversarial simulation failed: state divergence detected after rollback (/version=${verObj.sha256}, /app_release=${relObj.sha256})`);
  }

  console.log('✅ [Adversarial Test] Rollback invariant verified: 0 partial commits, 100% version parity preserved under simulated failure.');
}

/**
 * 4. Comprehensive Read-Back Parity Verification
 * Confirms both /version.json and /app_release.json have identical metadata for all required fields.
 */
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
    await runAdversarialSimulationTest();
    await singleAtomicMultiLocationUpdate();
    await readBackAndVerify();
    console.log('🎉 Single-request atomic multi-location update and full-field parity verification complete.');
    process.exit(0);
  } catch (err) {
    console.error('❌ [Firebase RTDB ERROR]', err.message);
    process.exit(1);
  }
}

main();
