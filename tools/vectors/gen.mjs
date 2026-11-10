#!/usr/bin/env node
/**
 * Golden vectors 生成器。
 *
 * 参考实现 = iOS 仓库里的 web 版（`BaiyunKeys-iOS/web/js/core/*.js`）。它与
 * `BaiyunKeys-iOS/BaiyunKeys/Core/*.swift` 是同一套协议的两份实现，语义逐函数一致
 * （`des.js` 是纯 JS DES，不依赖 WebCrypto，所以能在 Node 上直接跑）。
 *
 * 这样 P1 不必等 macOS runner 跑 Swift 工具：改 iOS 协议 → 重跑本脚本 → Kotlin 测试变红。
 *
 * 用法：
 *   node tools/vectors/gen.mjs
 *   REF_DIR="/path/to/BaiyunKeys-iOS/web/js/core" node tools/vectors/gen.mjs
 *
 * 产物：tools/vectors/vectors.json（提交进仓库，core 测试直接读）
 */
import { writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

// 时间向量固定时区，避免生成结果随机器变化
process.env.TZ = 'Asia/Shanghai';

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, '..', '..');
const refDir = process.env.REF_DIR
  ? resolve(process.env.REF_DIR)
  : resolve(repoRoot, '..', 'BaiyunKeys-iOS', 'web', 'js', 'core');

const load = (name) => import(pathToFileURL(join(refDir, name)).href);

if (!refDir.includes('core')) {
  console.warn('⚠️  REF_DIR 看起来不是 web/js/core：', refDir);
}

const H = await load('hex.js');
const D = await load('des.js');
const P = await load('ble-protocol.js');

// ---------------------------------------------------------------- 输入素材

const PRODUCT_KEY = '00112233445566778899AABBCCDDEEFF';
const PRODUCT_KEY_SHORT = 'A1B2C3D4E5F60718';
const RANDOM_4 = [0x3C, 0x91, 0x0F, 0xA7];
const BLUETOOTH_NAME = 'BY0AABBCCDD';
const MAC = '00:0A:AB:BC:CD:DD';
const SESSION_KEY = '5A6B7C8D9E0FA1B2';

const toHex = (u8) => H.hexFromBytes(u8);

// ---------------------------------------------------------------- Hex

const hexVectors = {
  normalize: [
    { in: '  aa:bb:cc  ', out: H.normalize('  aa:bb:cc  ') },
    { in: 'abc', out: H.normalize('abc') },
    { in: '', out: H.normalize('') },
    { in: '00ff', out: H.normalize('00ff') },
    { in: 'ZZ01zz', out: H.normalize('ZZ01zz') },
  ].map((c) => ({ in: c.in, out: c.out })),
  bytesFromHex: ['0011223344556677', 'AABBCCDD', '', '0F'].map((h) => ({
    in: h,
    out: toHex(H.bytesFromHex(h)),
  })),
  formatMac: [
    'AA:BB:CC:DD:EE:FF',
    'aabbccddeeff',
    'AABBCCDDEEFF',
    'AA:BB:CC:DD:EE',
    '',
    'xy12',
  ].map((v) => ({ in: v, out: H.formatMac(v) })),
  isValidMac: [
    'AA:BB:CC:DD:EE:FF',
    'aa:bb:cc:dd:ee:ff',
    'AA:BB:CC:DD:EE',
    'AABBCCDDEEFF',
    'AA:BB:CC:DD:EE:FF:00',
    '',
  ].map((v) => ({ in: v, out: H.isValidMac(v) })),
  isValidKey: ['0011223344556677', 'A1B2C3D4E5F60718', 'ABCD', 'ABC', '', '00112233445566778899AABBCCDDEEFF0011'].map(
    (v) => ({ in: v, out: H.isValidKey(v) }),
  ),
  sanitizeKey: ['00:11-22 33', 'a1b2c3', 'ZZ'].map((v) => ({ in: v, out: H.sanitizeKey(v) })),
  reversedPairs: ['AABBCC', 'AABBCCDD', 'ABCDE'].map((v) => ({ in: v, out: H.reversedPairs(v) })),
  ascii: ['BY0AABBCCDD', 'ABC', ''].map((v) => ({ in: v, out: H.ascii(v) })),
  hexSlice: [
    { in: 'AABBCCDDEEFF', start: 0, len: 2 },
    { in: 'AABBCCDDEEFF', start: 3, len: 4 },
    { in: 'AABBCCDDEEFF', start: 20, len: 16 },
    { in: 'AABBCCDDEEFF', start: 10, len: 16 },
    { in: 'AABBCCDDEEFF', start: -1, len: 2 },
  ].map((c) => ({ ...c, out: H.hexSlice(c.in, c.start, c.len) })),
};

// ---------------------------------------------------------------- DES

const desVectors = [
  { keyHex: PRODUCT_KEY, blockHex: '0001020304050607' },
  { keyHex: PRODUCT_KEY_SHORT, blockHex: '3C910FA73C910FA7' },
  { keyHex: 'FFFFFFFFFFFFFFFF', blockHex: '0000000000000000' },
  { keyHex: '0123456789ABCDEF', blockHex: 'A5A5A5A55A5A5A5A' },
].map((c) => ({
  ...c,
  enc: D.encryptHex(c.keyHex, c.blockHex),
  dec: D.decryptHex(c.keyHex, D.encryptHex(c.keyHex, c.blockHex)),
}));

// ---------------------------------------------------------------- 帧

const handshakeVectors = [
  { random: RANDOM_4, mac: MAC, productKey: PRODUCT_KEY },
  { random: [0, 0, 0, 0], mac: '00:0A:AB:BC:CD:DD', productKey: PRODUCT_KEY_SHORT },
  { random: [0xFF, 0xFF, 0x10, 0x01], mac: '11:22:33:44:55:66', productKey: PRODUCT_KEY },
].map((c) => ({
  ...c,
  out: toHex(P.handshakeCommand(c.random, P.headerFromMac(c.mac), c.productKey)),
}));

const commKeyVectors = [
  { random: RANDOM_4, bluetoothName: BLUETOOTH_NAME, productKey: PRODUCT_KEY },
  { random: [0x11, 0x22, 0x33, 0x44], bluetoothName: 'BLE123456789', productKey: PRODUCT_KEY_SHORT },
].map((c) => ({
  ...c,
  out: toHex(P.commKeyCommand(c.random, c.bluetoothName, c.productKey)),
}));

const timeBytesVectors = [
  { timeBytes: [0x26, 1, 15, 10, 20, 30, 4], bluetoothName: BLUETOOTH_NAME, sessionKey: SESSION_KEY },
  { timeBytes: [0x25, 12, 31, 23, 59, 59, 3], bluetoothName: 'BY112233445', sessionKey: SESSION_KEY },
].map((c) => ({
  ...c,
  out: toHex(P.timeSyncCommand(c.timeBytes, c.bluetoothName, c.sessionKey)),
}));

// timeHex：用「本地时间分量」构造，避免 Date 解析带来的时区歧义
const timeHexVectors = [
  { y: 2026, mo: 1, d: 15, h: 10, mi: 20, s: 30 },
  { y: 2025, mo: 12, d: 31, h: 23, mi: 59, s: 59 },
  { y: 2030, mo: 6, d: 1, h: 0, mi: 0, s: 0 },
  { y: 2026, mo: 11, d: 8, h: 9, mi: 5, s: 7 }, // 2026-11-08 是周日 → 星期应为 0
].map((c) => {
  const date = new Date(c.y, c.mo - 1, c.d, c.h, c.mi, c.s);
  return {
    local: `${c.y}-${String(c.mo).padStart(2, '0')}-${String(c.d).padStart(2, '0')}T${String(
      c.h,
    ).padStart(2, '0')}:${String(c.mi).padStart(2, '0')}:${String(c.s).padStart(2, '0')}`,
    zone: 'Asia/Shanghai',
    hex: P.timeHex(date),
  };
});

// ---------------------------------------------------------------- 回执 / 密钥解析

/** 用 productKey 造一个 8 字节「回执体」，放进帧的 slice(20,16) 位置。 */
function makeReceiptFrame(codeHex, productKey) {
  // 明文 8 字节：[0][1] = 0000，[2] = 回执码，其余补 0 → hex 长度 16（正好一个 DES 块）
  const plain = '0000' + codeHex + '0'.repeat(10);
  const body = D.encryptHex(productKey, plain);
  // 真帧形如 A5 14 05 | header[4] | 00 01 07 | DES(8B) | 00 5A → 前 20 个 hex 字符正好到 DES 之前
  return 'A51405' + '000AABBC' + '000107' + body + '005A';
}

const openResultVectors = [
  { frameHex: makeReceiptFrame('00', PRODUCT_KEY), productKey: PRODUCT_KEY },
  { frameHex: makeReceiptFrame('02', PRODUCT_KEY), productKey: PRODUCT_KEY },
  { frameHex: makeReceiptFrame('0F', PRODUCT_KEY), productKey: PRODUCT_KEY },
  { frameHex: 'A51405', productKey: PRODUCT_KEY },
].map((c) => {
  const r = P.openResult(c.frameHex, c.productKey);
  return { ...c, code: r.code, message: r.message, isSuccess: r.isSuccess };
});

/** 通讯密钥片段：明文 32 hex 里，[16,32) 就是会话密钥。 */
function makeCommKeyBody(sessionKeyHex, productKey) {
  const plain = '0000000000000000' + sessionKeyHex; // 32 hex = 16 字节
  return D.encryptHex(productKey, plain);
}

const commKeyBodyVectors = [
  { bodyHex: makeCommKeyBody(SESSION_KEY, PRODUCT_KEY), productKey: PRODUCT_KEY },
  { bodyHex: 'AABB', productKey: PRODUCT_KEY },
].map((c) => ({ ...c, out: P.commKeyFromBody(c.bodyHex, c.productKey) }));

// ---------------------------------------------------------------- 文本解析（无 JS 参考，按 Swift 逐行手推）

const textParserVectors = [
  {
    text: [
      '门禁名称：一号楼大门',
      '门禁 MAC：00:0A:AB:BC:CD:DD',
      '门禁 Key：00112233445566778899AABBCCDDEEFF',
      '蓝牙名称：by0aabbccdd',
    ].join('\n'),
    doors: [
      {
        name: '一号楼大门',
        mac: '00:0A:AB:BC:CD:DD',
        key: '00112233445566778899AABBCCDDEEFF',
        bluetoothName: 'BY0AABBCCDD',
      },
    ],
  },
  {
    text: [
      '门禁名称：二号楼侧门',
      '门禁 MAC：112233445566',
      '门禁 Key：A1B2C3D4E5F60718',
      '蓝牙名称：BY112233445',
      '',
      '门禁名称：三号楼',
      '门禁 MAC：AABBCCDDEEFF',
      '门禁 Key：0011223344556677',
    ].join('\n'),
    doors: [
      {
        name: '二号楼侧门',
        mac: '11:22:33:44:55:66',
        key: 'A1B2C3D4E5F60718',
        bluetoothName: 'BY112233445',
      },
      {
        name: '三号楼',
        mac: 'AA:BB:CC:DD:EE:FF',
        key: '0011223344556677',
        bluetoothName: '',
      },
    ],
  },
  {
    // 缺 Key 的一条要被丢掉；用 "=" 与 ":" 分隔也一样能解析
    text: ['门禁名称:不完整', '门禁 MAC:AABBCCDDEEFF', '', '门禁名称=完整', '门禁 MAC=112233445566', '门禁 Key=0011223344556677'].join(
      '\n',
    ),
    doors: [{ name: '完整', mac: '11:22:33:44:55:66', key: '0011223344556677', bluetoothName: '' }],
  },
  { text: '', doors: [] },
];

// ---------------------------------------------------------------- 输出

const vectors = {
  meta: {
    generatedAt: new Date().toISOString(),
    generator: 'tools/vectors/gen.mjs',
    reference: 'BaiyunKeys-iOS/web/js/core (hex.js, des.js, ble-protocol.js)',
    timeZone: 'Asia/Shanghai',
    note: '文本解析向量按 Swift DoorTextParser 语义手推，其余全部由参考实现生成。',
  },
  hex: hexVectors,
  des: desVectors,
  frames: {
    handshake: handshakeVectors,
    commKey: commKeyVectors,
    timeSync: timeBytesVectors,
  },
  deviceId: [BLUETOOTH_NAME, 'BLE123456789', 'BY112233445'].map((name) => ({
    name,
    parts: toHex(P.deviceIdParts(name)),
    derivedMac: P.macFromBluetoothName(name),
  })),
  timeHex: timeHexVectors,
  openResult: openResultVectors,
  commKeyBody: commKeyBodyVectors,
  textParser: textParserVectors,
};

const outPath = join(here, 'vectors.json');
writeFileSync(outPath, JSON.stringify(vectors, null, 2), 'utf8');

const counts = {
  hex: Object.values(hexVectors).reduce((n, arr) => n + arr.length, 0),
  des: desVectors.length,
  frames:
    handshakeVectors.length + commKeyVectors.length + timeBytesVectors.length,
  timeHex: timeHexVectors.length,
  openResult: openResultVectors.length,
  textParser: textParserVectors.length,
};
console.log('✅ vectors.json 已生成：', outPath);
console.log('   ', JSON.stringify(counts));

