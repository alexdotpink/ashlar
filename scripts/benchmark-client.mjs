#!/usr/bin/env node

import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { cpus, arch, platform, release } from 'node:os';
import { dirname, resolve } from 'node:path';
import { execFileSync } from 'node:child_process';

const options = parseArguments(process.argv.slice(2));
const port = Number(options.port ?? 9877);
const serverPort = Number(options['server-port'] ?? 25565);
const configPath = resolve(options.config ?? '/opt/minecraft-test/secondary/game/config/debugbridge.json');
const outputPath = resolve(options.output ?? 'build/reports/benchmarks/client.json');
const profileName = options.profile ?? 'small';
const profileIterations = { small: 3, typical: 20, stress: 100 };
const iterations = Number(options.iterations ?? profileIterations[profileName]);
if (!Number.isInteger(iterations) || iterations < 1) throw new Error('iterations must be a positive integer');
if (!(profileName in profileIterations)) throw new Error('profile must be small, typical, or stress');

const config = JSON.parse(readFileSync(configPath, 'utf8'));
if (!config.auth_token) throw new Error(`DebugBridge config ${configPath} has no auth_token`);

const bridge = await connect(`ws://127.0.0.1:${port}`);
try {
  requireSuccess(await bridge.call('authenticate', { token: config.auth_token }));
  const capabilities = requireSuccess(await bridge.call('capabilities'));
  if (!capabilities.runCommand) throw new Error('DebugBridge runCommand is disabled');
  const status = requireSuccess(await bridge.call('status'));
  if (status.version !== '26.2') throw new Error(`Expected Minecraft 26.2, found ${status.version}`);
  const snapshot = requireSuccess(await bridge.call('snapshot'));
  if (!snapshot.player?.name) throw new Error('The client is not connected to a server');

  await closeAshlarMenu(bridge, snapshot.player.name);
  const cold = await openAndObserve(bridge, snapshot.player.name, serverPort);
  const warm = [];
  for (let index = 0; index < iterations; index++) {
    await closeAshlarMenu(bridge, snapshot.player.name);
    warm.push(await openAndObserve(bridge, snapshot.player.name, serverPort));
  }
  await closeAshlarMenu(bridge, snapshot.player.name);

  const result = benchmarkResult({
    revision: options.revision ?? 'working-tree',
    ashlarVersion: options['ashlar-version'] ?? 'development',
    profileName,
    port,
    status,
    cold,
    warm,
  });
  mkdirSync(dirname(outputPath), { recursive: true });
  writeFileSync(outputPath, `${JSON.stringify(result, null, 2)}\n`);
  console.log(`Wrote ${result.cases.length} connected-client benchmark cases to ${outputPath}`);
} finally {
  bridge.close();
}

async function closeAshlarMenu(bridge, playerName) {
  requireSuccess(await bridge.call('runCommand', { command: `menus close ${playerName}` }));
  await poll(async () => !requireSuccess(await bridge.call('screenInspect')).open, 5_000, 'menu close');
}

async function openAndObserve(bridge, playerName, minecraftServerPort) {
  const networkBefore = connectionBytes(minecraftServerPort);
  const started = process.hrtime.bigint();
  requireSuccess(await bridge.call('runCommand', { command: `menus hosts ${playerName}` }));
  let screen;
  await poll(async () => {
    screen = requireSuccess(await bridge.call('screenInspect'));
    return screen.open && screen.title === 'Every native host';
  }, 5_000, 'host catalogue open');
  if (!Array.isArray(screen.slots) || screen.slots.length < 27) {
    throw new Error(`Host catalogue exposed ${screen.slots?.length ?? 0} slots`);
  }
  const visibleNanos = Number(process.hrtime.bigint() - started);
  const snapshot = requireSuccess(await bridge.call('snapshot'));
  if (!(snapshot.fps > 0)) throw new Error(`Client reported invalid FPS ${snapshot.fps}`);
  const networkAfter = connectionBytes(minecraftServerPort);
  return {
    durationNanos: visibleNanos,
    frameNanos: 1_000_000_000 / snapshot.fps,
    packetBytes: Math.max(0, networkAfter - networkBefore),
  };
}

function benchmarkResult({ revision, ashlarVersion, profileName, port, status, cold, warm }) {
  return {
    schemaVersion: 2,
    runId: crypto.randomUUID(),
    revision,
    startedAtEpochMillis: Date.now(),
    environment: {
      environmentId: 'connected-client-local',
      operatingSystem: `${platform()} ${release()}`,
      architecture: arch(),
      availableProcessors: cpus().length,
      cpuModel: cpus()[0]?.model ?? 'unknown',
      jvmVendor: 'Minecraft client JVM',
      jvmVersion: '25',
      jvmArguments: [],
      garbageCollectors: [],
      kotlinVersion: '2.4.10',
      ashlarVersion,
      platform: 'CLIENT',
      platformVersion: `Minecraft ${status.version}`,
      attributes: {
        bridgePort: String(port),
        gameDirectory: status.gameDir,
      },
    },
    configuration: {
      warmupIterations: 1,
      measurementIterations: warm.length,
      forks: 1,
      warmupTimeMillis: 250,
      measurementTimeMillis: 500,
      collectAllocation: false,
      authoritative: false,
    },
    cases: [clientCase(profileName, 'COLD', [cold]), clientCase(profileName, 'WARM', warm)],
  };
}

function clientCase(profile, temperature, samples) {
  const durations = samples.map((sample) => sample.durationNanos);
  const frames = samples.map((sample) => sample.frameNanos);
  const packetBytes = samples.map((sample) => sample.packetBytes);
  const mean = durations.reduce((sum, value) => sum + value, 0) / durations.length;
  return {
    id: {
      scenario: { value: 'native.hosts' },
      profile,
      layer: 'CLIENT',
      temperature,
    },
    status: 'EXPLORATORY',
    metrics: [
      { metric: 'LATENCY_MEAN', value: mean },
      { metric: 'LATENCY_P50', value: percentile(durations, 0.50) },
      { metric: 'LATENCY_P95', value: percentile(durations, 0.95) },
      { metric: 'LATENCY_P99', value: percentile(durations, 0.99) },
      { metric: 'THROUGHPUT', value: durations.length * 1_000_000_000 / durations.reduce((a, b) => a + b, 0) },
      { metric: 'END_TO_END', value: percentile(durations, 0.99) },
      { metric: 'CLIENT_FRAME_P99', value: percentile(frames, 0.99) },
      { metric: 'PACKET_BYTES', value: percentile(packetBytes, 0.99) },
    ],
    samples: durations.map((durationNanos) => ({ durationNanos, allocatedBytes: null })),
    supplementalSamples: {
      END_TO_END: durations,
      CLIENT_FRAME_P99: frames,
      PACKET_BYTES: packetBytes,
    },
    budgets: { relative: {}, absolute: {} },
  };
}

function connectionBytes(minecraftServerPort) {
  const output = execFileSync('ss', ['-tinp', `( dport = :${minecraftServerPort} )`], { encoding: 'utf8' });
  const sent = /bytes_sent:(\d+)/.exec(output)?.[1];
  const received = /bytes_received:(\d+)/.exec(output)?.[1];
  if (sent === undefined || received === undefined) {
    throw new Error(`Could not read TCP byte counters for the client connection to port ${minecraftServerPort}`);
  }
  return Number(sent) + Number(received);
}

function percentile(values, fraction) {
  const ordered = [...values].sort((a, b) => a - b);
  const index = Math.ceil(fraction * ordered.length) - 1;
  return ordered[Math.max(0, index)];
}

async function poll(check, timeoutMillis, name) {
  const deadline = performance.now() + timeoutMillis;
  while (performance.now() < deadline) {
    if (await check()) return;
    await new Promise((resolvePoll) => setTimeout(resolvePoll, 10));
  }
  throw new Error(`Timed out waiting for ${name}`);
}

function requireSuccess(response) {
  if (!response.success) throw new Error(response.error ?? 'DebugBridge request failed');
  return response.result;
}

async function connect(url) {
  const socket = new WebSocket(url);
  await new Promise((resolveOpen, rejectOpen) => {
    const timeout = setTimeout(() => rejectOpen(new Error(`Timed out connecting to ${url}`)), 5_000);
    socket.addEventListener('open', () => { clearTimeout(timeout); resolveOpen(); }, { once: true });
    socket.addEventListener('error', () => { clearTimeout(timeout); rejectOpen(new Error(`Could not connect to ${url}`)); }, { once: true });
  });
  let nextId = 0;
  const pending = new Map();
  socket.addEventListener('message', (event) => {
    const response = JSON.parse(event.data);
    pending.get(response.id)?.(response);
    pending.delete(response.id);
  });
  return {
    call(type, payload = {}) {
      return new Promise((resolveCall, rejectCall) => {
        const id = String(++nextId);
        const timeout = setTimeout(() => {
          pending.delete(id);
          rejectCall(new Error(`${type} timed out`));
        }, 10_000);
        pending.set(id, (response) => { clearTimeout(timeout); resolveCall(response); });
        socket.send(JSON.stringify({ id, type, payload }));
      });
    },
    close() { socket.close(); },
  };
}

function parseArguments(argumentsList) {
  const parsed = {};
  for (let index = 0; index < argumentsList.length; index += 2) {
    const key = argumentsList[index];
    if (!key?.startsWith('--') || argumentsList[index + 1] === undefined) {
      throw new Error(`Expected --name value, found '${key ?? ''}'`);
    }
    parsed[key.slice(2)] = argumentsList[index + 1];
  }
  return parsed;
}
