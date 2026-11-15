import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import http from 'node:http';
import net from 'node:net';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const options = Object.fromEntries(process.argv.slice(2).map(arg => {
  const index = arg.indexOf('=');
  assert(index > 2, 'Use --name=value arguments');
  return [arg.slice(2, index), arg.slice(index + 1)];
}));
const javaHome = options['java-home'] ?? process.env.JAVA_HOME;
const simulatorHome = options.simulator ?? process.env.JC_HOME_SIMULATOR;
assert(javaHome && simulatorHome, 'Provide --java-home and --simulator, or their environment variables');
const stamp = new Date().toISOString().replace(/[-:]/g, '').slice(0, 15);
const output = path.resolve(root, options.evidence ?? `logs/qa06-before-http-${stamp}`);
assert(!fs.existsSync(output), 'Evidence directory must be new');
const scratch = path.join(root, `session-2026-11-15-before-http-${crypto.randomBytes(4).toString('hex')}`);
fs.mkdirSync(output, { recursive: true });
fs.mkdirSync(scratch);
const journal = path.join(scratch, 'pending-journal.txt');
const children = [];
const require = createRequire(path.join(root, 'backend/package.json'));
const { Client } = require('pg');
const dotenv = require('dotenv');
const environment = dotenv.parse(fs.readFileSync(path.join(root, 'backend/.env')));
const database = new Client({ connectionString: environment.DATABASE_URL });
const cardId = crypto.randomBytes(8).toString('hex').toUpperCase();
const studentId = `QA06${cardId.slice(0, 6)}`;
const adminPin = Array.from({ length: 8 }, () => crypto.randomInt(10)).join('');
const studentPin = Array.from({ length: 6 }, () => crypto.randomInt(10)).join('');
const java = path.join(javaHome, 'bin/java.exe');
const javac = path.join(javaHome, 'bin/javac.exe');
const state = { cardId, studentId, evidence: output, journal, phase: 'starting', children: [] };
const proxyEvents = [];
let proxy;
let databaseConnected = false;
let app;
let restored;
let cleaning = false;

function event(name, details = {}) {
  const line = JSON.stringify({ time: new Date().toISOString(), event: name, ...details });
  fs.appendFileSync(path.join(output, 'harness.log'), `${line}\n`);
  console.log(line);
}

function phase(name) {
  state.phase = name;
  fs.writeFileSync(path.join(output, 'state.json'), `${JSON.stringify(state, null, 2)}\n`);
  event('phase', { name });
}

function child(label, executable, args, { cwd = root, input, env = {}, visible = false } = {}) {
  const stdout = fs.openSync(path.join(output, `${label}.log`), 'ax');
  const stderr = fs.openSync(path.join(output, `${label}-stderr.log`), 'ax');
  const processChild = spawn(executable, args, {
    cwd, env: { ...process.env, ...env }, stdio: ['pipe', stdout, stderr], windowsHide: !visible,
  });
  fs.closeSync(stdout);
  fs.closeSync(stderr);
  processChild.done = new Promise((resolve, reject) => {
    processChild.once('error', reject);
    processChild.once('exit', (code, signal) => {
      event('process-exit', { label, pid: processChild.pid, code, signal });
      resolve({ code, signal });
    });
  });
  processChild.stdin.on('error', () => {});
  processChild.stdin.end(input ?? '');
  children.push(processChild);
  state.children.push({ label, pid: processChild.pid, executable, args });
  event('process-start', { label, pid: processChild.pid });
  return processChild;
}

async function run(label, executable, args, options = {}) {
  const result = await child(label, executable, args, options).done;
  assert.equal(result.code, 0, `${label} failed; inspect its logs`);
}

function logOf(directory, label) {
  const file = path.join(directory, `${label}.log`);
  return fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : '';
}

function log(label) { return logOf(output, label); }
function digest(file) { return crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex'); }
function signal(name) { fs.writeFileSync(path.join(scratch, `${name}.flag`), 'go\n'); }

async function until(description, condition, timeout = 180000, watched = []) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    if (await condition()) return;
    for (const processChild of watched) {
      assert.equal(processChild.exitCode, null, `${description}: process ${processChild.pid} exited`);
    }
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  throw new Error(`Timed out: ${description}`);
}

async function freePort() {
  const server = net.createServer();
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  const port = server.address().port;
  await new Promise(resolve => server.close(resolve));
  return port;
}

function proxyEvent(value) {
  const entry = { time: new Date().toISOString(), ...value };
  proxyEvents.push(entry);
  fs.appendFileSync(path.join(output, 'http-proxy.jsonl'), `${JSON.stringify(entry)}\n`);
}

function startProxy(backendPort) {
  let nextId = 0;
  return http.createServer((request, response) => {
    const id = ++nextId;
    const meta = { id, method: request.method, path: request.url };
    proxyEvent({ event: 'request-arrived', ...meta });
    const chunks = [];
    request.on('data', chunk => chunks.push(chunk));
    request.on('end', () => {
      const body = Buffer.concat(chunks);
      const receiptMeta = {};
      if (request.method === 'POST' && request.url === '/receipts/verify') {
        const payload = Buffer.from(JSON.parse(body.toString('utf8')).payload, 'hex');
        receiptMeta.cardId = payload.subarray(1, 9).toString('hex').toUpperCase();
        receiptMeta.counter = payload.readUInt32BE(9);
        receiptMeta.operation = payload[13];
        receiptMeta.balance = payload.readUInt16BE(16);
      }
      proxyEvent({ event: 'forwarded', ...meta, ...receiptMeta });
      const upstream = http.request({ hostname: '127.0.0.1', port: backendPort,
        method: request.method, path: request.url,
        headers: { ...request.headers, host: `127.0.0.1:${backendPort}` } }, received => {
        const receivedChunks = [];
        received.on('data', chunk => receivedChunks.push(chunk));
        received.on('end', () => {
          const receivedBody = Buffer.concat(receivedChunks);
          const responseMeta = {};
          if (request.url === '/receipts/verify') {
            try { responseMeta.receiptStatus = JSON.parse(receivedBody.toString('utf8')).status; }
            catch { responseMeta.receiptStatus = 'unparseable'; }
          }
          proxyEvent({ event: 'response', ...meta, status: received.statusCode, ...receiptMeta, ...responseMeta });
          response.writeHead(received.statusCode, received.headers);
          response.end(receivedBody);
        });
      });
      upstream.on('error', error => {
        proxyEvent({ event: 'upstream-error', ...meta, code: error.code });
        response.writeHead(502).end();
      });
      upstream.end(body);
    });
  });
}

async function snapshot(name) {
  const ledger = await database.query('SELECT encode("cardId", \'hex\') AS "cardId", "lastCounter"::text, "lastBalance"::text, encode("activeVisitId", \'hex\') AS "activeVisitId" FROM card_ledgers WHERE "cardId" = $1', [Buffer.from(cardId, 'hex')]);
  const transactions = await database.query('SELECT id, "counterAfter"::text, operation, delta::text, "balanceAfter"::text, encode("visitId", \'hex\') AS "visitId", encode(payload, \'hex\') AS "payloadHex" FROM transactions WHERE "cardId" = $1 ORDER BY "counterAfter"', [Buffer.from(cardId, 'hex')]);
  const value = { time: new Date().toISOString(), name, ledger: ledger.rows, transactions: transactions.rows };
  fs.writeFileSync(path.join(output, `sql-${name}.json`), `${JSON.stringify(value, null, 2)}\n`);
  event('sql-snapshot', { name, ledger: ledger.rows, transactionCount: transactions.rowCount });
  return value;
}

async function closeWindow(label, mode, processChild) {
  event('close-window-requested', { label, pid: processChild.pid });
  signal(`go-close-${mode}`);
  await until(`${label} exits after close`, () => processChild.exitCode !== null, 20000);
  assert.equal((await processChild.done).code, 0, `${label} did not exit normally`);
}

async function cleanup() {
  if (cleaning) return;
  cleaning = true;
  for (const processChild of children.toReversed()) {
    if (processChild.exitCode === null) processChild.kill();
  }
  await Promise.allSettled(children.map(processChild => processChild.done));
  if (proxy) await new Promise(resolve => { proxy.closeAllConnections(); proxy.close(resolve); });
  if (databaseConnected) await database.end();
  if (!fs.existsSync(journal)) {
    assert(path.dirname(path.resolve(scratch)) === root && path.basename(scratch).startsWith('session-2026-11-15-before-http-'));
    fs.rmSync(scratch, { recursive: true });
    event('scratch-removed');
  } else {
    event('scratch-retained-pending', { path: scratch, reason: 'Failed run journal retained; simulator is stopped, do not claim recoverability' });
  }
}

try {
  phase('build');
  await database.connect();
  databaseConnected = true;
  assert.equal((await snapshot('initial')).ledger.length, 0, 'Generated cardId already exists');
  if (options['reuse-build']) {
    const previous = path.resolve(root, options['reuse-build']);
    assert(logOf(previous, 'desktop-build').includes('BUILD SUCCESS'));
    assert(logOf(previous, 'backend-build').includes('nest build'));
    const previousState = JSON.parse(fs.readFileSync(path.join(previous, 'state.json'), 'utf8'));
    fs.copyFileSync(path.join(path.dirname(previousState.journal), 'classpath.txt'), path.join(scratch, 'classpath.txt'));
    event('reused-product-builds', { evidence: previous });
  } else {
    await run('desktop-build', 'cmd.exe', ['/d', '/s', '/c', 'mvn -B -f desktop/pom.xml -DskipTests package']);
    await run('classpath', 'cmd.exe', ['/d', '/s', '/c', `mvn -B -f desktop/pom.xml dependency:build-classpath -Dmdep.outputFile=${path.join(scratch, 'classpath.txt')}`]);
    await run('backend-build', 'cmd.exe', ['/d', '/s', '/c', 'npm run build'], { cwd: path.join(root, 'backend') });
  }
  await run('gate-build', javac, ['--add-modules', 'jdk.jdi', '-d', scratch, path.join(root, 'tools/qa-before-http/BeforeHttpGate.java')]);
  await run('installer-build', javac, ['-d', scratch, '-cp', `${simulatorHome}/client/AMService/amservice.jar;${simulatorHome}/client/COMService/socketprovider.jar`, path.join(root, 'applet/scripts/InstallApplet.java')]);
  const classpath = fs.readFileSync(path.join(scratch, 'classpath.txt'), 'utf8').trim();
  const cap = path.join(root, 'applet/build/deliverables/vn/labaccess/card/javacard/card.cap');
  const jar = path.join(root, 'desktop/target/desktop-0.1.0-SNAPSHOT.jar');
  const scenarioClasses = path.join(scratch, 'scenario');
  fs.mkdirSync(scenarioClasses);
  await run('scenario-build', javac, ['--module-path', `${classpath};${jar}`,
    '--patch-module', `vn.labaccess.desktop=${root}/tools/qa-before-http`, '-d', scenarioClasses,
    path.join(root, 'tools/qa-before-http/BeforeHttpScenario.java')]);
  fs.writeFileSync(path.join(output, 'build-manifest.json'), `${JSON.stringify({
    time: new Date().toISOString(), cardId,
    cap: { path: cap, sha256: digest(cap), bytes: fs.statSync(cap).size },
    desktop: { path: jar, sha256: digest(jar), bytes: fs.statSync(jar).size },
    gateSha256: digest(path.join(root, 'tools/qa-before-http/BeforeHttpGate.java')),
    scenarioSha256: digest(path.join(root, 'tools/qa-before-http/BeforeHttpScenario.java')),
    runnerSha256: digest(fileURLToPath(import.meta.url)),
    appSourceSha256: digest(path.join(root, 'desktop/src/main/java/vn/labaccess/desktop/App.java')),
  }, null, 2)}\n`);
  const simPort = await freePort();
  const backendPort = await freePort();
  const relayPort = await freePort();
  const debugPort = await freePort();
  phase('start-services');
  const sim = child('simulator', path.join(simulatorHome, 'runtime/bin/jcsw.exe'), [`-p=${simPort}`], { cwd: scratch });
  await until('simulator ready', () => log('simulator').includes(`server ready on port #${simPort}`), 30000, [sim]);
  const backend = child('backend', process.execPath, ['dist/main.js'], { cwd: path.join(root, 'backend'), env: { PORT: String(backendPort) } });
  await until('backend ready', () => log('backend').includes('Nest application successfully started'), 30000, [backend]);
  proxy = startProxy(backendPort);
  await new Promise(resolve => proxy.listen(0, '127.0.0.1', resolve));
  const proxyPort = proxy.address().port;
  proxyEvent({ event: 'listening', port: proxyPort, upstreamPort: backendPort });
  const relay = child('card-relay', process.execPath, [path.join(root, 'tools/qa-lost-response-relay.mjs'),
    `--listen=${relayPort}`, `--upstream-port=${simPort}`, '--mode=passthrough', `--log=${path.join(output, 'card-relay.jsonl')}`]);
  await until('relay ready', () => log('card-relay').includes('"ev":"listening"'), 10000, [relay]);
  phase('install-fresh-test-card');
  await run('install', java, ['--module-path', `${simulatorHome}/client/AMService/amservice.jar;${simulatorHome}/client/COMService/socketprovider.jar`,
    '--add-modules', 'ALL-MODULE-PATH', '-cp', scratch, 'InstallApplet', '-mode=install', `-cap=${cap}`,
    `-host=127.0.0.1:${simPort}`, `-props=${simulatorHome}/samples/client.config.properties`], { input: `${adminPin}\n` });
  const baseArgs = ['-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-Dlabaccess.host=127.0.0.1',
    '--enable-native-access=javafx.graphics', `-Dlabaccess.port=${relayPort}`,
    `-Dlabaccess.backend=http://127.0.0.1:${proxyPort}`, `-Dlabaccess.journal=${journal}`,
    '-Dlabaccess.demo=false', `-Dlabaccess.demo.cardId=${cardId}`, `-Dlabaccess.demo.studentId=${studentId}`,
    `-Dlabaccess.demo.signalDir=${scratch}`, `-Dlabaccess.qa.evidence=${output}`];
  const moduleArgs = ['--module-path', `${classpath};${jar}`, '--patch-module', `vn.labaccess.desktop=${scenarioClasses}`,
    '-m', 'vn.labaccess.desktop/vn.labaccess.desktop.BeforeHttpScenario'];
  phase('first-javafx-lifecycle');
  app = child('app-before-close', java, [`-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:${debugPort}`,
    ...baseArgs, '-Dlabaccess.qa.mode=first', ...moduleArgs], { visible: true, input: `${adminPin}\n${studentPin}\n` });
  await until('JDWP listener', () => log('app-before-close').includes('Listening for transport dt_socket'), 15000, [app]);
  const gate = child('gate', java, ['--add-modules', 'jdk.jdi', '-cp', scratch, 'BeforeHttpGate', String(debugPort), journal, cardId, output]);
  await until('baseline ready', () => log('app-before-close').includes('SCENARIO_BASELINE_READY'), 90000, [app, gate]);
  const baseline = await snapshot('baseline');
  assert.equal(baseline.ledger[0].lastCounter, '1');
  assert.equal(baseline.ledger[0].lastBalance, '5');
  assert.equal(baseline.transactions.length, 1);
  const verifyArrivals = () => proxyEvents.filter(entry => entry.event === 'request-arrived' && entry.method === 'POST' && entry.path === '/receipts/verify');
  assert.equal(verifyArrivals().length, 1);
  proxyEvent({ event: 'checkpoint-before-start', baselineVerifyPosts: 1, targetVerifyPosts: 0 });
  signal('go-start');
  await until('before HTTP gate held and captured', () => fs.existsSync(path.join(output, 'gate-held.txt'))
    && log('app-before-close').includes('SCENARIO_GATED_CAPTURED'), 45000, [app, gate]);
  phase('gate-held-before-close');
  const before = await snapshot('before-close');
  assert.deepEqual(before.ledger, baseline.ledger);
  assert.deepEqual(before.transactions, baseline.transactions);
  assert.equal(verifyArrivals().length, 1, 'No verify POST may arrive after the baseline checkpoint');
  assert.equal(proxyEvents.filter(entry => entry.event === 'forwarded' && entry.operation === 2).length, 0);
  proxyEvent({ event: 'checkpoint-before-close', totalVerifyPosts: 1, targetVerifyPosts: 0 });
  await closeWindow('first-app', 'first', app);
  await until('debugger observes disconnection', () => gate.exitCode !== null, 15000);
  assert.equal((await gate.done).code, 0);
  const closed = await snapshot('after-close');
  assert.deepEqual(closed.ledger, before.ledger);
  assert.deepEqual(closed.transactions, before.transactions);
  assert.equal(verifyArrivals().length, 1);
  assert(fs.existsSync(journal), 'Pending journal removed before Backend ACK');
  const pendingDigest = digest(journal);
  assert.equal(pendingDigest, digest(path.join(output, 'journal-pending.txt')));
  proxyEvent({ event: 'checkpoint-after-close', totalVerifyPosts: 1, targetVerifyPosts: 0, journalSha256: pendingDigest });
  const relayAtRecovery = fs.readFileSync(path.join(output, 'card-relay.jsonl'), 'utf8').trim().split('\n').length;
  phase('reopen-same-journal');
  restored = child('app-recovery', java, [...baseArgs, '-Dlabaccess.qa.mode=recovery', ...moduleArgs],
    { visible: true, input: `${adminPin}\n${studentPin}\n` });
  await until('startup pending lock captured', () => log('app-recovery').includes('KHÓA giao dịch ngay')
    && log('app-recovery').includes('SCENARIO_REOPENED_CAPTURED'), 20000, [restored]);
  assert.equal(digest(journal), pendingDigest);
  signal('go-connect');
  await until('reconciliation dialog', () => log('app-recovery').includes('SCENARIO_WAIT_GO_OK'), 45000, [restored]);
  signal('go-ok');
  await until('journal removed and recovery captured', () => !fs.existsSync(journal)
    && log('app-recovery').includes('SCENARIO_RECOVERY_COMPLETE'), 45000, [restored]);
  const after = await snapshot('recovered');
  assert.equal(after.ledger[0].lastCounter, '2');
  assert.equal(after.ledger[0].lastBalance, '4');
  assert.equal(after.transactions.length, 2);
  assert.equal(after.transactions.filter(entry => entry.operation === 'START').length, 1);
  assert.equal(verifyArrivals().length, 2, 'Exactly one recovery POST must arrive');
  const startForwards = proxyEvents.filter(entry => entry.event === 'forwarded' && entry.operation === 2);
  assert.equal(startForwards.length, 1);
  assert.equal(startForwards[0].counter, 2);
  assert.equal(startForwards[0].balance, 4);
  assert(proxyEvents.some(entry => entry.event === 'response' && entry.operation === 2 && entry.status === 201 && entry.receiptStatus === 'recorded'));
  const signed = JSON.parse(fs.readFileSync(path.join(output, 'verified-receipt.json'), 'utf8'));
  assert.equal(after.transactions[1].payloadHex, signed.payloadHex);
  assert.equal(after.ledger[0].activeVisitId, signed.visitId);
  assert.match(log('app-recovery'), /GET_STATUS state SW=9000 balance=4 counter=2 activeVisit=true/);
  const frames = fs.readFileSync(path.join(output, 'card-relay.jsonl'), 'utf8').trim().split('\n').map(line => JSON.parse(line));
  const stateFrames = frames.filter(entry => entry.ev === 'app-frame' && entry.cla === 128 && [80, 81, 82].includes(entry.ins));
  const recoveryStateFrames = frames.slice(relayAtRecovery).filter(entry => entry.ev === 'app-frame' && entry.cla === 128 && [80, 81, 82].includes(entry.ins));
  assert.equal(stateFrames.filter(entry => entry.ins === 80).length, 1);
  assert.equal(stateFrames.filter(entry => entry.ins === 81).length, 1);
  assert.equal(stateFrames.filter(entry => entry.ins === 82).length, 0);
  assert.equal(recoveryStateFrames.length, 0, 'Recovery sent a state APDU');
  assert.equal(frames.filter(entry => entry.action === 'DROP').length, 0);
  proxyEvent({ event: 'checkpoint-recovered', totalVerifyPosts: 2, recoveryPosts: 1,
    startRows: 1, journalPresent: false, recoveryStateApdus: 0 });
  await closeWindow('restored-app', 'recovery', restored);
  const final = await snapshot('final');
  assert.deepEqual(final.ledger, after.ledger);
  assert.deepEqual(final.transactions, after.transactions);
  const sensitive = [adminPin, studentPin, Buffer.from(adminPin).toString('hex').toUpperCase(), Buffer.from(studentPin).toString('hex').toUpperCase()];
  const sensitiveHits = [];
  for (const entry of fs.readdirSync(output, { withFileTypes: true })) {
    if (entry.isFile() && /\.(?:log|json|jsonl|txt)$/.test(entry.name)) {
      const content = fs.readFileSync(path.join(output, entry.name), 'utf8');
      for (const value of sensitive) if (content.includes(value)) sensitiveHits.push(entry.name);
    }
  }
  assert.equal(sensitiveHits.length, 0, `Sensitive data found in ${[...new Set(sensitiveHits)].join(', ')}`);
  fs.writeFileSync(path.join(output, 'result.json'), `${JSON.stringify({ pass: true, cardId,
    card: { counter: 2, balance: 4, activeVisitId: signed.visitId }, ledger: after.ledger[0],
    beforeClose: { baselineVerifyPosts: 1, targetVerifyPosts: 0, counter: 1, balance: 5 },
    recovery: { verifyPosts: 1, startRows: 1, stateApdus: 0, journalRemoved: true },
    total: { grantApdus: 1, startApdus: 1, endApdus: 0, verifyPosts: 2 },
    sensitivePayloadHits: 0, pendingJournalSha256: pendingDigest,
    firstClose: 'window-close, worker held at breakpoint; 5s watchdog exit',
  }, null, 2)}\n`);
  phase('PASS');
} catch (error) {
  phase('FAIL');
  event('failure', { message: error.message, stack: error.stack });
  process.exitCode = 1;
} finally {
  await cleanup();
  fs.writeFileSync(path.join(output, 'state.json'), `${JSON.stringify(state, null, 2)}\n`);
}
