import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { spawn, execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const options = Object.fromEntries(process.argv.slice(2).map(arg => {
  const index = arg.indexOf('=');
  assert(index > 2, 'Use --name=value');
  return [arg.slice(2, index), arg.slice(index + 1)];
}));
const javaHome = process.env.JAVA_HOME;
const toolsHome = process.env.JC_HOME_TOOLS;
const simulatorHome = process.env.JC_HOME_SIMULATOR;
assert(javaHome && toolsHome && simulatorHome, 'Set JAVA_HOME, JC_HOME_TOOLS, JC_HOME_SIMULATOR');
assert(options.evidence && options['scratch-root'], 'Provide --evidence and --scratch-root');
const output = path.resolve(root, options.evidence);
assert(!fs.existsSync(output), 'Evidence directory must be new');
const scratch = fs.mkdtempSync(path.join(path.resolve(options['scratch-root']), 'labaccess-apdu-'));
fs.mkdirSync(output, { recursive: true });
const children = [];
const java = path.join(javaHome, 'bin/java.exe');
const javac = path.join(javaHome, 'bin/javac.exe');
const sdkModules = `${simulatorHome}/client/AMService/amservice.jar;${simulatorHome}/client/COMService/socketprovider.jar`;
const pins = [8, 6, 6, 6].map(length => Array.from({ length }, () => crypto.randomInt(10)).join(''));
const env = { ...process.env, HOME: scratch, USERPROFILE: scratch, TMP: scratch, TEMP: scratch };
const digest = file => crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
const readLog = label => fs.readFileSync(path.join(output, `${label}.log`), 'utf8')
  + fs.readFileSync(path.join(output, `${label}-stderr.log`), 'utf8');
const psQuote = value => `'${value.replaceAll("'", "''")}'`;
let manifest;

function child(label, executable, args, input = '') {
  const stdout = fs.openSync(path.join(output, `${label}.log`), 'ax');
  const stderr = fs.openSync(path.join(output, `${label}-stderr.log`), 'ax');
  const processChild = spawn(executable, args, {
    cwd: scratch, env, stdio: ['pipe', stdout, stderr], windowsHide: true,
  });
  fs.closeSync(stdout);
  fs.closeSync(stderr);
  processChild.done = new Promise((resolve, reject) => {
    processChild.once('error', reject);
    processChild.once('exit', code => resolve(code));
  });
  processChild.stdin.on('error', () => {});
  processChild.stdin.end(input);
  children.push(processChild);
  fs.appendFileSync(path.join(output, 'commands.jsonl'), `${JSON.stringify({ label, executable, args, pid: processChild.pid })}\n`);
  return processChild;
}

async function run(label, executable, args, input = '') {
  const processChild = child(label, executable, args, input);
  const timeout = setTimeout(() => processChild.kill(), 180000);
  try {
    const code = await processChild.done;
    console.log(`${label}: exit=${code}`);
    assert.equal(code, 0, `${label} failed; inspect ${output}`);
  } finally {
    clearTimeout(timeout);
  }
}

async function batch(label, executable, args) {
  await run(label, 'powershell.exe', ['-NoProfile', '-NonInteractive', '-Command',
    `& ${psQuote(executable)} ${args.map(psQuote).join(' ')}; exit $LASTEXITCODE`]);
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

try {
  const sourceDir = path.join(root, 'applet/src/vn/labaccess/card');
  const sources = fs.readdirSync(sourceDir, { recursive: true }).filter(name => name.endsWith('.java')).sort().map(name => path.join(sourceDir, name));
  const harnessSources = ['PersonalizeSelfTest.java', 'ProfileV2Cases.java', 'ApduErrorCases.java', 'ApduTransportCases.java', 'GatewayTransportCases.java']
    .map(name => path.join(root, 'applet/scripts', name));
  const gatewaySources = ['card/CardGateway', 'card/CardLifecycle', 'card/CardOperationResult', 'card/CardPhotoCommands', 'card/CardPinCommands',
    'card/CardProfileCommands', 'card/CardProtocol', 'card/CardStatus', 'card/CardStatusCodec', 'transport/CardTransport', 'card/PendingJournal',
    'card/PersonalizeRequest', 'card/PhotoImage', 'card/Profile', 'card/ProfileCodec', 'card/ProfileCryptoMeta', 'card/ProfileMetaCodec',
    'card/Receipt', 'card/ReceiptCodec', 'card/ReceiptExpectation', 'card/ReceiptVerifier', 'card/Reconciliation', 'transport/SimulatorConnection', 'card/Sw',
    'card/ReceiptReadRecovery', 'card/CardOverviewReader', 'card/CardAdminCommands', 'card/StudentLedgerGate',
    'card/LedgerAlignment', 'validation/PinFormat', 'card/CardPersonalizationCommands', 'card/CardEnrollmentCommands',
    'card/CardProfileUpdateCommands', 'validation/ClassCode', 'validation/StudentMajor', 'card/ProfileEditRequest', 'card/StateTransactionFlow', 'card/ReconciliationFlow',
        'virtualreader/protocol/VirtualReaderProtocol',
        'virtualreader/protocol/VirtualReaderSession'];
  harnessSources.push(...gatewaySources.map(name => name === 'transport/CardTransport' && options['baseline-transport']
    ? path.resolve(root, options['baseline-transport'])
    : path.join(root, `desktop/src/main/java/vn/labaccess/desktop/${name}.java`)));
  manifest = {
    time: new Date().toISOString(), groups: options.groups ?? 'all',
    transportProbe: options.transport ?? null,
    baselineTransport: options['baseline-transport'] ?? null,
    head: execFileSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8', windowsHide: true }).trim(),
    javaHome, toolsHome, simulatorHome, target: '3.2.0',
    packageAid: 'F04C414202', appletAid: 'F04C4142020101', instanceAid: 'F04C414202EE01',
    cardId: 'A1B2C3D411223344',
    sources: [...sources, ...harnessSources, fileURLToPath(import.meta.url),
      path.join(root, 'applet/scripts/test-personalize.ps1'),
      path.join(root, 'applet/scripts/build.ps1'),
      path.join(root, 'tools/qa-lost-response-relay.mjs'),
      path.join(root, 'docs/design/apdu-commands.md'),
      path.join(root, 'applet/configurations/labaccess.conf'),
      path.join(root, 'applet/configurations/labaccess-test.conf')].map(file => ({
      path: path.relative(root, file).replaceAll('\\', '/'), sha256: digest(file),
    })), caps: {},
  };
  manifest.environment = [path.join(simulatorHome, 'client/COMService/socketprovider.jar'),
    path.join(simulatorHome, 'client/AMService/amservice.jar'), path.join(simulatorHome, 'runtime/bin/jcsw.exe'),
    path.join(toolsHome, 'lib/api_classic-3.2.0.jar')].map(file => ({ path: file, sha256: digest(file), bytes: fs.statSync(file).size }));
  for (const source of manifest.sources) {
    source.snapshot = `source/${source.path}`;
    const saved = path.join(output, source.snapshot);
    fs.mkdirSync(path.dirname(saved), { recursive: true });
    fs.copyFileSync(path.join(root, source.path), saved);
  }
  fs.writeFileSync(path.join(output, 'source.patch'), execFileSync('git', ['diff', '--', 'applet/src', 'applet/scripts', 'desktop/src', 'tools/qa-lost-response-relay.mjs'], { cwd: root, windowsHide: true }));
  const classes = path.join(scratch, 'classes');
  const harness = path.join(scratch, 'harness');
  fs.mkdirSync(classes);
  fs.mkdirSync(harness);
  await run('javac-applet', javac, ['-g', '-d', classes, '-source', '10', '-target', '10', '-Xlint:-options', '-cp', `${toolsHome}/lib/api_classic-3.2.0.jar`, ...sources]);
  const variants = options.tamper === 'true' ? ['ciphertext', 'tag', 'nonce'] : [];
  manifest.tamper = variants;
  const buildSource = fs.readFileSync(path.join(root, 'applet/scripts/build.ps1'), 'utf8');
  for (const [label, configName] of [['product', 'labaccess.conf'], ['test', 'labaccess-test.conf'],
    ...variants.map(name => [`${name}-deliverables`, 'labaccess-test.conf'])]) {
    if (variants.some(name => label === `${name}-deliverables`)) {
      const mode = label.replace('-deliverables', '');
      const definition = buildSource.match(new RegExp(`Name = '${mode}'[\\s\\S]*?Lines = @\\('([^']+)'\\)`));
      assert(definition, `Missing ${mode} injection in build.ps1`);
      const original = fs.readFileSync(path.join(sourceDir, 'crypto/ProfileCrypto.java'), 'utf8');
      const anchor = '        target[(short) 0] = PROFILE_RECORD_VERSION;';
      assert.equal(original.split(anchor).length, 2);
      const instrumented = original.replace(anchor, `${definition[1]}\n${anchor}`);
      const variantSource = path.join(scratch, 'ProfileCrypto.java');
      fs.writeFileSync(variantSource, instrumented);
      fs.writeFileSync(path.join(output, `${mode}-ProfileCrypto.java`), instrumented);
      await run(`javac-${mode}`, javac, ['-g', '-d', classes, '-source', '10', '-target', '10', '-Xlint:-options',
        '-cp', `${toolsHome}/lib/api_classic-3.2.0.jar;${classes}`, variantSource]);
    }
    if (label === 'test' && ['receive', 'short-input'].includes(options.transport)) {
      const appletSource = fs.readFileSync(path.join(sourceDir, 'LabAccessApplet.java'), 'utf8');
      const checksSource = fs.readFileSync(path.join(sourceDir, 'protocol/ApduChecks.java'), 'utf8');
      const instrumentedApplet = appletSource.replace('requireApplicationCla(buffer);', `requireApplicationCla(buffer);
            if (ins == (byte) 0x7E) {
                Util.arrayCopyNonAtomic(ApduChecks.receiveTrace, (short) 0, buffer, (short) 0,
                        (short) ApduChecks.receiveTrace.length);
                apdu.setOutgoingAndSend((short) 0, (short) ApduChecks.receiveTrace.length);
                return;
            }`);
      const instrumentedChecks = checksSource
        .replace('final class ApduChecks {',
          'final class ApduChecks {\n\n    static final byte[] receiveTrace = new byte[65];')
        .replace('short received = apdu.setIncomingAndReceive();\n        short lc',
          'Util.arrayFillNonAtomic(receiveTrace, (short) 0, (short) receiveTrace.length, (byte) 0);\n        receiveTrace[64] = 1;\n        short received = apdu.setIncomingAndReceive();\n        short lc')
        .replace('while (total < lc) {', `while (total < lc) {
            Util.setShort(receiveTrace, (short) (1 + receiveTrace[0] * 2), received);
            receiveTrace[0]++;`);
      assert.notEqual(instrumentedApplet, appletSource, 'Probe anchor moved out of LabAccessApplet.process');
      assert(instrumentedChecks.includes('static final byte[] receiveTrace = new byte[65];'),
        'Probe anchor moved out of the ApduChecks declaration');
      assert(instrumentedChecks.includes('receiveTrace[64] = 1;'),
        'Probe anchor moved out of ApduChecks.receiveCommandData entry');
      assert(instrumentedChecks.includes('receiveTrace[0]++;'),
        'Probe anchor moved out of the ApduChecks.receiveCommandData loop');
      const probeApplet = path.join(scratch, 'LabAccessApplet.java');
      const probeChecks = path.join(scratch, 'ApduChecks.java');
      fs.writeFileSync(probeApplet, instrumentedApplet);
      fs.writeFileSync(probeChecks, instrumentedChecks);
      fs.writeFileSync(path.join(output, 'receive-probe-LabAccessApplet.java'), instrumentedApplet);
      fs.writeFileSync(path.join(output, 'receive-probe-ApduChecks.java'), instrumentedChecks);
      manifest.receiveProbe = {
        sources: [
          { source: 'receive-probe-LabAccessApplet.java', sha256: digest(probeApplet) },
          { source: 'receive-probe-ApduChecks.java', sha256: digest(probeChecks) }],
        scope: 'test CAP only: read-only receive byte counts; product CAP uses unchanged source' };
      await run('javac-receive-probe', javac, ['-g', '-d', classes, '-source', '10', '-target', '10', '-Xlint:-options',
        '-cp', `${toolsHome}/lib/api_classic-3.2.0.jar;${classes}`, probeChecks, probeApplet]);
    }
    const deliverables = path.join(scratch, label);
    fs.mkdirSync(deliverables);
    const original = fs.readFileSync(path.join(root, 'applet/configurations', configName), 'utf8');
    const config = original.replace(/-classdir [^\r\n]+/, '-classdir classes').replace(/-d [^\r\n]+/, `-d ${label}`);
    fs.writeFileSync(path.join(scratch, configName), config);
    await batch(`converter-${label}`, path.join(toolsHome, 'bin/converter.bat'), ['-config', configName]);
    assert.match(readLog(`converter-${label}`), /conversion completed with 0 errors and 0 warnings/);
    const cap = path.join(deliverables, 'vn/labaccess/card/javacard/card.cap');
    await batch(`verifycap-${label}`, path.join(toolsHome, 'bin/verifycap.bat'), [cap]);
    assert.match(readLog(`verifycap-${label}`), /Verification completed with 0 warnings and 0 errors/);
    const savedCap = path.join(output, `${label}.cap`);
    fs.copyFileSync(cap, savedCap);
    manifest.caps[label] = { path: `${label}.cap`, sha256: digest(savedCap), bytes: fs.statSync(savedCap).size };
  }
  await run('javac-harness', javac, ['-d', harness, '-cp', sdkModules, ...harnessSources]);
  fs.writeFileSync(path.join(output, 'manifest.json'), `${JSON.stringify(manifest, null, 2)}\n`);
  const port = await freePort();
  const simulator = child('simulator', path.join(simulatorHome, 'runtime/bin/jcsw.exe'), [`-p=${port}`]);
  const deadline = Date.now() + 30000;
  while (!readLog('simulator').includes(`server ready on port #${port}`)) {
    assert.equal(simulator.exitCode, null, 'Simulator exited before ready');
    assert(Date.now() < deadline, 'Simulator readiness timeout');
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  console.log(`runtime CAP SHA256=${manifest.caps.test.sha256} cardId=${manifest.cardId}`);
  let clientPort = port;
  if (['sw6c', 'gateway-sw6c', 'short-input'].includes(options.transport)) {
    clientPort = await freePort();
    child('relay', process.execPath, [path.join(root, 'tools/qa-lost-response-relay.mjs'),
      `--listen=${clientPort}`, `--upstream-port=${port}`, `--mode=${options.transport === 'gateway-sw6c' ? 'sw6c' : options.transport}`]);
    const relayDeadline = Date.now() + 10000;
    while (!readLog('relay').includes('"ev":"listening"')) {
      assert(Date.now() < relayDeadline, 'Relay readiness timeout');
      await new Promise(resolve => setTimeout(resolve, 100));
    }
  }
  manifest.runtimePort = port;
  manifest.clientPort = clientPort;
  await run('runtime', java, ['-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
    '-Dlabaccess.apduErrorsOnly=true', `-Dlabaccess.tamperOnly=${options.tamper === 'true'}`, `-Dlabaccess.apduGroups=${manifest.groups}`,
    `-Dlabaccess.transportProbe=${options.transport ?? ''}`,
    `-Dlabaccess.evidence=${output}`,
    '--add-opens', 'com.oracle.smartcardio.socketprovider/com.oracle.smartcardio.socket=ALL-UNNAMED',
    `-Dlabaccess.simulator.port=${clientPort}`, '--module-path', sdkModules, '--add-modules', 'ALL-MODULE-PATH',
    '-cp', harness, 'PersonalizeSelfTest', `${simulatorHome}/samples/client.config.properties`,
    path.join(output, 'test.cap'), scratch], `${pins.join('\n')}\n`);
  if (variants.length) {
    const runtime = readLog('runtime');
    assert.match(runtime, /KET LUAN: PASS/);
    assert.equal((runtime.match(/\| PASS/g) ?? []).length, 33);
    for (const id of ['R01-D', 'R02-D', 'R03-D', 'R01-H', 'R02-H', 'R03-H']) {
      assert.match(runtime, new RegExp(`${id}[^\\r\\n]+\\| PASS`));
    }
  } else {
    assert.match(readLog('runtime'), /APDU_ERRORS cases=\d+ failed=0 skipped=0/);
  }
  if (['sw6c', 'gateway-sw6c'].includes(options.transport)) {
    const entries = readLog('relay').split(/\r?\n/).filter(line => line.startsWith('{')).map(line => JSON.parse(line));
    const starts = entries.filter(entry => entry.ev === 'app-frame' && entry.startCommand);
    const replacements = entries.filter(entry => entry.action === 'REPLACE_SW_6C06');
    assert.equal(starts.length, 1, 'Provider/transport replayed START');
    assert.equal(replacements.length, 1, 'Expected one injected 6C06');
    manifest.wire = { startRequests: starts.length, injected6cResponses: replacements.length,
      lrcErrors: entries.filter(entry => entry.lrc === 'MISMATCH').length };
    assert.equal(manifest.wire.lrcErrors, 0);
  }
  manifest.pass = true;
} catch (error) {
  console.error(error.message);
  if (manifest) manifest.pass = false;
  process.exitCode = 1;
} finally {
  for (const processChild of children.reverse()) {
    if (processChild.exitCode === null && processChild.signalCode === null) processChild.kill();
    await processChild.done.catch(() => {});
  }
  const testedPins = pins.flatMap(pin => [pin, `${(Number(pin[0]) + 1) % 10}${pin.slice(1)}`]);
  const maskedKnownSha256Fields = text => text.replace(
    /\bsha[\s-]?256["']?\s*[:=]\s*["']?([0-9a-f]{64})(?=["']?(?:\s|[,}\])]|$))/gi,
    (_, digest) => 'h'.repeat(digest.length));
  const hexOfPin = value => [...value]
    .map(character => character.charCodeAt(0).toString(16).padStart(2, '0')).join('').toUpperCase();
  const hexPinPattern = value => hexOfPin(value).match(/../g).join('[\\s:-]*');
  const containsPin = (text, values) => {
    const masked = maskedKnownSha256Fields(text);
    return values.some(value => new RegExp(`(?<![0-9A-Fa-f])${value}(?![0-9A-Fa-f])`).test(masked)
      || new RegExp(hexPinPattern(value), 'i').test(masked));
  };
  const controlPin = '246813';
  const controlPayload = `CASE-CONTROL -> 80 20 00 00 06 80 ${hexOfPin(controlPin).match(/../g).join(' ')} 90 00`;
  const controlHash = `CAP SHA256=${'AB'.repeat(8)}${controlPin}${'CD'.repeat(21)}`;
  const scanSelfTest = {
    payloadHexLeakDetected: containsPin(controlPayload, [controlPin]),
    hashFieldIgnored: !containsPin(controlHash, [controlPin]),
  };
  console.log(`SCAN-CONTROL-PAYLOAD-HEX | ${scanSelfTest.payloadHexLeakDetected ? 'PASS' : 'FAIL'}`);
  console.log(`SCAN-CONTROL-HASH-RANDOM | ${scanSelfTest.hashFieldIgnored ? 'PASS' : 'FAIL'}`);
  const simulatorLog = path.join(output, 'simulator.log');
  let simulatorLogKept = null;
  if (fs.existsSync(simulatorLog)) {
    const lines = fs.readFileSync(simulatorLog, 'utf8').split(/\r?\n/);
    const kept = lines.filter(line => !containsPin(line, testedPins)
      && !/(\b[0-9A-F]{2}\b[\s:,-]*){12,}/i.test(line));
    fs.writeFileSync(simulatorLog, `${kept.join('\n')}\n`);
    simulatorLogKept = { total: lines.length, kept: kept.length,
      scope: 'simulator log filtered: JCRE payload dumps and PIN bytes removed before evidence is kept' };
  }
  const leaks = fs.readdirSync(output).filter(name => name.endsWith('.log'))
    .filter(name => containsPin(fs.readFileSync(path.join(output, name), 'utf8'), testedPins));  const resolvedScratch = path.resolve(scratch);
  assert.equal(path.dirname(resolvedScratch), path.resolve(options['scratch-root']));
  assert(path.basename(resolvedScratch).startsWith('labaccess-apdu-'));
  fs.rmSync(resolvedScratch, { recursive: true, force: true });
  if (manifest) {
    manifest.sensitivePayloadHits = leaks;
    manifest.scanSelfTest = scanSelfTest;
    manifest.simulatorLog = simulatorLogKept;
    if (['sw6c', 'gateway-sw6c'].includes(options.transport) && fs.existsSync(path.join(output, 'relay.log'))) {
      const entries = readLog('relay').split(/\r?\n/).filter(line => line.startsWith('{')).map(line => JSON.parse(line));
      manifest.wire = { startRequests: entries.filter(entry => entry.ev === 'app-frame' && entry.startCommand).length,
        injected6cResponses: entries.filter(entry => entry.action === 'REPLACE_SW_6C06').length,
        lrcErrors: entries.filter(entry => entry.lrc === 'MISMATCH').length };
    }
    manifest.scratchRemoved = !fs.existsSync(resolvedScratch);
    if (fs.existsSync(path.join(output, 'runtime.log'))) {
      const runtime = readLog('runtime');
      const counts = runtime.match(/APDU_ERRORS cases=(\d+) failed=(\d+) skipped=(\d+)/);
      manifest.result = counts ? { cases: Number(counts[1]), failed: Number(counts[2]), skipped: Number(counts[3]) }
        : options.tamper === 'true' ? { cases: (runtime.match(/\| PASS/g) ?? []).length,
          failed: (runtime.match(/\| FAIL/g) ?? []).length, skipped: (runtime.match(/\| SKIP/g) ?? []).length } : null;
      manifest.failures = runtime.split(/\r?\n/).filter(line => line.includes(' | FAIL | '));
    }
    if (leaks.length || !scanSelfTest.payloadHexLeakDetected || !scanSelfTest.hashFieldIgnored) {
      manifest.pass = false;
      process.exitCode = 1;
    }
    fs.writeFileSync(path.join(output, 'manifest.json'), `${JSON.stringify(manifest, null, 2)}\n`);
  }
  console.log(`Evidence: ${output}`);
}
