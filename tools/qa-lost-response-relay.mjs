#!/usr/bin/env node
import net from 'node:net';
import fs from 'node:fs';

const options = parseOptions(process.argv.slice(2));

function parseOptions(argv) {
    const parsed = { };
    for (const arg of argv) {
        const match = /^--([^=]+)=(.*)$/.exec(arg);
        if (match) {
            parsed[match[1]] = match[2];
        }
    }
    return parsed;
}

const config = {
    listenPort: Number(options.listen ?? 9026),
    upstreamHost: options['upstream-host'] ?? '127.0.0.1',
    upstreamPort: Number(options['upstream-port'] ?? 9025),
    mode: options.mode ?? 'passthrough',
    logFile: options.log ?? null,
    releaseDelayMs: Number(options['release-delay-ms'] ?? 11500),
    armCla: 0x80,
    armIns: 0x51,
    armP1P2: 0x0000,
    armLc: 16,
};

const counters = {
    connections: 0,
    appToSimFrames: 0,
    simToAppFrames: 0,
    startRequestsSeen: 0,
    startRequestsForwarded: 0,
    responsesDropped: 0,
    lrcErrors: 0,
    unparsedBytes: 0,
};

function emit(entry) {
    const line = JSON.stringify({ t: new Date().toISOString(), ...entry });
    console.log(line);
    if (config.logFile) {
        fs.appendFileSync(config.logFile, line + '\n', 'utf8');
    }
}

function hex(buffer, from = 0, to = buffer.length) {
    return buffer.subarray(from, to).toString('hex').toUpperCase();
}

function xorBlock(buffer, from, to) {
    let value = 0;
    for (let index = from; index < to; index += 1) {
        value ^= buffer[index];
    }
    return value;
}

function popcount(value) {
    let count = 0;
    let rest = value;
    while (rest) {
        count += rest & 1;
        rest >>= 1;
    }
    return count;
}

const SENSITIVE_INS = new Set([0x20, 0x21, 0x23, 0x30, 0x32, 0x50]);

const SENSITIVE_APDU = /80(?:20|21|23|30|32|50)0000/;

function maskedHex(buffer) {
    return SENSITIVE_APDU.test(hex(buffer)) ? '<masked: sensitive apdu>' : hex(buffer);
}

function describeCommand(block) {
    const length = block[2];
    const inf = block.subarray(3, 3 + length);
    const info = {
        nad: block[0],
        pcb: block[1],
        infLen: length,
        blockLen: block.length,
        pcbType: (block[1] & 0xC0) === 0x00 ? 'I' : (block[1] & 0xC0) === 0x80 ? 'R' : 'S',
    };
    if (inf.length >= 4) {
        info.cla = inf[0];
        info.ins = inf[1];
        info.p1 = inf[2];
        info.p2 = inf[3];
        if (inf.length >= 5) {
            info.lc = inf[4];
        }
        info.hex = SENSITIVE_INS.has(inf[1]) ? `${hex(inf.subarray(0, 5))} data=***` : hex(inf);
    } else {
        info.hex = hex(inf);
    }
    return info;
}

function describeResponse(block) {
    const length = block[2];
    const inf = block.subarray(3, 3 + length);
    const info = {
        nad: block[0],
        pcb: block[1],
        infLen: length,
        blockLen: block.length,
        pcbType: (block[1] & 0xC0) === 0x00 ? 'I' : (block[1] & 0xC0) === 0x80 ? 'R' : 'S',
        hex: maskedHex(inf),
    };
    if (inf.length >= 2) {
        info.sw = (inf[inf.length - 2] << 8) | inf[inf.length - 1];
        info.dataLen = inf.length - 2;
        info.swHex = info.sw.toString(16).toUpperCase().padStart(4, '0');
        info.data = hex(inf, 0, inf.length - 2);
    }
    return info;
}

let armConsumed = false;

class Pair {
    constructor(id, downstream) {
        this.id = id;
        this.downstream = downstream;
        this.upstream = null;
        this.released = false;
        this.armState = config.mode === 'arm' && !armConsumed ? 'armed' : 'off';
        this.pendingReplies = [];
        this.buffers = { app: Buffer.alloc(0), sim: Buffer.alloc(0) };
        this.releaseTimer = null;
    }

    attachUpstream(socket) {
        this.upstream = socket;
    }

    release(reason) {
        if (this.released) {
            return;
        }
        this.released = true;
        if (this.releaseTimer) {
            clearTimeout(this.releaseTimer);
            this.releaseTimer = null;
        }
        emit({ ev: 'pair-release', pair: this.id, reason, counters });
        this.downstream.destroy();
        if (this.upstream) {
            this.upstream.destroy();
        }
    }

    handle(kind, chunk) {
        if (config.mode === 'rawdump') {
            emit({ ev: 'chunk', pair: this.id, dir: kind, len: chunk.length, hex: maskedHex(chunk) });
            const target = kind === 'app' ? this.upstream : this.downstream;
            writeAll(target, chunk);
            return;
        }
        this.buffers[kind] = Buffer.concat([this.buffers[kind], chunk]);
        if (kind === 'app') {
            this.drainApp();
        } else {
            this.drainSim();
        }
    }

    drainApp() {
        for (;;) {
            const buffer = this.buffers.app;
            if (buffer.length < 1) {
                return;
            }
            const first = buffer[0];
            let need;
            let kind;
            if (first === 0xf0 || first === 0xfe) {
                need = 4;
                kind = first === 0xf0 ? 'powerup' : 'powerdown';
            } else if (first === 0xff) {
                if (buffer.length < 2) {
                    return;
                }
                need = buffer[1] === 0xf0 ? 3 : 4;
                kind = 'negotiation';
            } else {
                if (buffer.length < 3) {
                    return;
                }
                need = 4 + buffer[2];
                kind = 'block';
            }
            if (buffer.length < need) {
                return;
            }
            const frame = buffer.subarray(0, need);
            this.buffers.app = buffer.subarray(need);
            this.onAppFrame(frame, kind);
        }
    }

    onAppFrame(frame, kind) {
        counters.appToSimFrames += 1;
        const entry = { ev: 'app-frame', pair: this.id, index: counters.appToSimFrames, kind, len: frame.length, hex: maskedHex(frame) };
        if (kind === 'block') {
            const lrc = xorBlock(frame, 0, frame.length - 1);
            if (lrc !== frame[frame.length - 1]) {
                counters.lrcErrors += 1;
                entry.lrc = 'MISMATCH';
            }
            const info = describeCommand(frame);
            entry.nad = info.nad;
            entry.pcb = info.pcb;
            entry.cla = info.cla;
            entry.ins = info.ins;
            entry.p1 = info.p1;
            entry.p2 = info.p2;
            entry.lc = info.lc;
            entry.inf = info.hex;
            const isStartStrict = info.cla === config.armCla && info.ins === config.armIns
                    && info.p1 === (config.armP1P2 >> 8) && info.p2 === (config.armP1P2 & 0xff)
                    && info.lc === config.armLc;
            entry.startCommand = isStartStrict;
            if (isStartStrict) {
                counters.startRequestsSeen += 1;
                entry.visitId = hex(frame, 3 + 5, 3 + 3 + config.armLc - 2);
                if (this.armState === 'armed') {
                    this.armState = 'forwarded';
                    armConsumed = true;
                    counters.startRequestsForwarded += 1;
                    entry.armAction = 'forward-single-start';
                }
                this.pendingReplies.push({ kind: 'block', start: true, dropped: false, frame });
            } else {
                this.pendingReplies.push({ kind: 'block', start: false, dropped: false });
            }
        } else {
            this.pendingReplies.push({ kind, start: false, dropped: false });
        }
        emit(entry);
        writeAll(this.upstream, frame);
    }

    drainSim() {
        for (;;) {
            const buffer = this.buffers.sim;
            const pending = this.pendingReplies[0];
            const kind = pending ? pending.kind : 'block';
            if (buffer.length < 1) {
                return;
            }
            let need;
                        if (kind === 'powerup') {
                if (buffer.length < 4) {
                    return;
                }
                need = 4 + (((buffer[2] << 8) | buffer[3]) & 0xffff);
            } else if (kind === 'negotiation' && buffer[0] === 0xff) {
                if (buffer.length < 2) {
                    return;
                }
                need = 2 + popcount((buffer[1] >> 4) & 0x07) + 1;
            } else {
                if (buffer.length < 3) {
                    return;
                }
                need = 4 + buffer[2];
            }
            if (buffer.length < need) {
                return;
            }
            const frame = buffer.subarray(0, need);
            this.buffers.sim = buffer.subarray(need);
            if (pending) {
                this.pendingReplies.shift();
            }
            this.onSimFrame(frame, kind, pending);
        }
    }

    onSimFrame(frame, kind, pending) {
        counters.simToAppFrames += 1;
        const entry = { ev: 'sim-frame', pair: this.id, index: counters.simToAppFrames, kind, len: frame.length, hex: maskedHex(frame) };
        let drop = false;
        if (kind === 'block') {
            const lrc = xorBlock(frame, 0, frame.length - 1);
            if (lrc !== frame[frame.length - 1]) {
                counters.lrcErrors += 1;
                entry.lrc = 'MISMATCH';
            }
            const info = describeResponse(frame);
            entry.sw = info.swHex;
            entry.dataLen = info.dataLen;
            entry.data = info.data;
            entry.pcbType = info.pcbType;
            if (pending && pending.start) {
                entry.answersStart = true;
                if (config.mode === 'arm' && info.sw === 0x9000 && info.dataLen === 6) {
                    drop = true;
                } else {
                    entry.dropSkip = info.sw !== 0x9000 ? 'sw-not-9000' : 'dataLen-not-6';
                }
            }
        } else if (kind === 'powerup') {
            entry.note = 'power-up reply carries ATR';
        }
        if (drop) {
            counters.responsesDropped += 1;
            entry.action = 'DROP';
            entry.dropDetail = {
                balanceAfter: parseInt(entry.data.slice(0, 4), 16),
                counterAfter: parseInt(entry.data.slice(4, 12), 16),
                sw: entry.sw,
            };
            emit(entry);
            this.scheduleRelease();
            return;
        }
        emit(entry);
        writeAll(this.downstream, frame);
    }

    scheduleRelease() {
        if (this.releaseTimer) {
            return;
        }
        emit({ ev: 'hold-app', pair: this.id, releaseAfterMs: config.releaseDelayMs, note: 'app side stays open so the caller hits its own APDU timeout' });
        this.releaseTimer = setTimeout(() => {
            emit({ ev: 'after-drop-summary', pair: this.id, counters });
            this.release('stale-pair-after-dropped-response');
        }, config.releaseDelayMs);
    }
}

function writeAll(socket, chunk) {
    if (!socket || socket.destroyed) {
        return;
    }
    socket.write(chunk);
}

let nextPairId = 0;

const server = net.createServer((client) => {
    nextPairId += 1;
    counters.connections += 1;
    const pair = new Pair(nextPairId, client);
    client.setNoDelay(true);
    emit({ ev: 'pair-open', pair: pair.id, mode: config.mode, arm: pair.armState, remote: `${client.remoteAddress}:${client.remotePort}` });

    const upstream = net.connect({ host: config.upstreamHost, port: config.upstreamPort });
    upstream.setNoDelay(true);
    pair.attachUpstream(upstream);

    upstream.on('connect', () => {
        emit({ ev: 'upstream-open', pair: pair.id, host: config.upstreamHost, port: config.upstreamPort });
    });
    client.on('data', (chunk) => pair.handle('app', chunk));
    upstream.on('data', (chunk) => pair.handle('sim', chunk));
    client.on('error', (error) => emit({ ev: 'socket-error', pair: pair.id, dir: 'app', message: error.message }));
    upstream.on('error', (error) => emit({ ev: 'socket-error', pair: pair.id, dir: 'sim', message: error.message }));
    client.on('close', () => {
        emit({ ev: 'socket-close', pair: pair.id, dir: 'app' });
        pair.release('app-closed');
    });
    upstream.on('close', () => {
        emit({ ev: 'socket-close', pair: pair.id, dir: 'sim' });
        pair.release('upstream-closed');
    });
});

emit({
    ev: 'relay-start',
    listen: `127.0.0.1:${config.listenPort}`,
    upstream: `${config.upstreamHost}:${config.upstreamPort}`,
    mode: config.mode,
    arm: { cla: '80', ins: '51', p1p2: '0000', lc: config.armLc },
    framing: {
        source: 'com.oracle.smartcardio.socket.SocketT1Protocol.receiveBlockProtocolMessage / T1Block',
        blockFormat: 'NAD(1) PCB(1) LEN(1) INF(LEN) LRC(1) => frame length = 4 + LEN',
        lrc: 'LRC = XOR of NAD..INF, verified on every block',
        controlFrames: 'app power-up F0 00 00 00 and power-down FE 00 00 00 use a 4-byte envelope; the power-up reply is a 4-byte header with a big-endian length at bytes 2..3 followed by the ATR',
        negotiationFrames: 'PPS request FF 11 xx LRC (4 bytes), IFS request FF F0 LRC (3 bytes)',
    },
});

server.listen(config.listenPort, '127.0.0.1', () => {
    emit({ ev: 'listening', address: `127.0.0.1:${config.listenPort}` });
});

function summary(reason) {
    emit({ ev: 'summary', reason, counters, arm: { mode: config.mode, remainingArmState: 'see pair logs' } });
}

process.on('SIGINT', () => {
    summary('sigint');
    server.close();
    setTimeout(() => process.exit(0), 200);
});
process.on('SIGTERM', () => {
    summary('sigterm');
    server.close();
    setTimeout(() => process.exit(0), 200);
});
