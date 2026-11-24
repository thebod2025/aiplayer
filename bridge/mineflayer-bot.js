#!/usr/bin/env node
/*
 * AIPlayer 外部 bot 参考实现（mineflayer）。
 *
 * 作用：真正的 Minecraft 客户端在这里 —— 它去登录远程服务器（服务器不需要装 AIPlayer 模组），
 * 同时通过本地 TCP 连到模组里的「远程」桥接，把状态 / 聊天上报给大模型，并执行模组下发的指令。
 *
 *   模组（LLM 决策）  <-- JSON Lines / TCP -->  本脚本 (mineflayer)  <-- Minecraft 协议 -->  远程服务器
 *
 * 用法（先安装依赖）：
 *   cd bridge
 *   npm install
 *   node mineflayer-bot.js --name 小爱 --host mc.example.com --port 25565 --username XiaoAi
 *
 * 参数（也可用环境变量，命令行优先）：
 *   --name           桥接握手名，必须和 GUI「远程」页里的「名字」一致
 *   --host/--port    远程服务器地址 / 端口（默认 127.0.0.1:25565）
 *   --username       登录名（默认与 --name 相同，离线模式）
 *   --version        协议版本，留空由 mineflayer 自动判断
 *   --auth           offline（默认）/ microsoft
 *   --bridge-host    模组桥接地址（默认 127.0.0.1）
 *   --bridge-port    模组桥接端口（默认 8765，对应 GUI 里的「端口」）
 *   --reconnect      断线后是否自动重连（默认 true）
 *
 * 注意：mineflayer 能识别的版本有上限（大致 1.8 ~ 1.21.x）。如果远程服务器是更新版本，
 * 需要换成与该版本协议兼容的 bot 实现；桥接协议本身与 Minecraft 版本无关。
 */

'use strict';

const net = require('net');
const path = require('path');
const fs = require('fs');
const os = require('os');
const { execFile } = require('child_process');

// ---------------- 参数 --------------------------
function arg(name, fallback) {
  const i = process.argv.indexOf('--' + name);
  if (i >= 0 && i + 1 < process.argv.length) return process.argv[i + 1];
  const env = 'AI_BOT_' + name.toUpperCase().replace(/-/g, '_');
  return process.env[env] !== undefined ? process.env[env] : fallback;
}

const opts = {
  name: arg('name', 'Bot'),
  host: arg('host', '127.0.0.1'),
  port: Number(arg('port', 25565)),
  username: arg('username', arg('name', 'Bot')),
  version: arg('version', ''),
  auth: arg('auth', 'offline'),
  bridgeHost: arg('bridge-host', '127.0.0.1'),
  bridgePort: Number(arg('bridge-port', 8765)),
  reconnect: arg('reconnect', 'true') !== 'false',
  refreshMsa: arg('msa-fresh', 'false') === 'true',
  // 密码插件（AuthMe 等）：留空不自动认证。GUI 里填的密码经桥接下发，也可以用环境变量 AI_BOT_PASSWORD
  password: arg('password', ''),
  authMode: arg('auth-mode', 'auto')
};

let mineflayer;
try {
  mineflayer = require('mineflayer');
} catch (e) {
  console.error('[bridge] 缺少依赖 mineflayer：先在 bridge 目录执行 npm install');
  process.exit(1);
}

let pathfinder = null;
let goals = null;
try {
  pathfinder = require('mineflayer-pathfinder');
  goals = pathfinder.goals;
} catch (e) {
  console.warn('[bridge] 未安装 mineflayer-pathfinder，寻路退化为直线移动');
}

// ---------------- 桥接 TCP --------------------------
let bridge = null;
let bridgeReady = false;
let settings = { pvp: false };
/** 名字是 pvpbot 的远程 bot：专职 PvP，强制开打、无视主人 */
const IS_PVPBOT = /^pvpbot/i.test(String(opts.name || opts.username || ''));
let bridgeTimer = null;
/** 微软设备码：桥接断开时先缓存，重连握手后补发（否则验证码就只留在日志里） */
let pendingMsa = null;

function sendBridge(obj) {
  if (!bridgeReady || !bridge) return;
  try {
    bridge.write(JSON.stringify(obj) + '\n');
  } catch (e) {
    // 写失败等下次重连，不阻塞游戏逻辑
  }
}

function connectBridge() {
  if (bridgeTimer) clearTimeout(bridgeTimer);
  bridgeTimer = null;
  console.log('[bridge] 正在连接模组桥接 ' + opts.bridgeHost + ':' + opts.bridgePort);
  const socket = net.createConnection({ host: opts.bridgeHost, port: opts.bridgePort }, () => {
    bridge = socket;
    bridgeReady = true;
    console.log('[bridge] 已连接模组桥接 ' + opts.bridgeHost + ':' + opts.bridgePort);
    sendBridge({
      type: 'hello',
      name: opts.name,
      username: opts.username,
      server: opts.host + ':' + opts.port,
      version: bot && bot.version ? bot.version : opts.version,
      protocol: 1
    });
    // 握手后补发缓存里的设备码（脚本先拿到码、桥接后连上的情况）
    if (pendingMsa) sendBridge(pendingMsa);
  });

  socket.setEncoding('utf8');
  let buffer = '';
  socket.on('data', (chunk) => {
    buffer += chunk;
    const lines = buffer.split('\n');
    buffer = lines.pop();
    for (const line of lines) {
      if (!line.trim()) continue;
      let msg;
      try {
        msg = JSON.parse(line);
      } catch (e) {
        continue;
      }
      handleBridgeMessage(msg);
    }
  });
  socket.on('close', () => {
    if (bridgeReady) console.warn('[bridge] 与模组桥接断开');
    bridgeReady = false;
    bridge = null;
    scheduleBridgeReconnect();
  });
  socket.on('error', (err) => {
    // 连不上时一定要留日志，否则界面上只会看到「运行中 · 离线」，无从判断是端口不对还是桥接没开
    console.warn('[bridge] 桥接连接失败：' + (err && err.message || err));
    bridgeReady = false;
    bridge = null;
    scheduleBridgeReconnect();
  });
}

function scheduleBridgeReconnect() {
  if (bridgeTimer) return;
  bridgeTimer = setTimeout(() => {
    bridgeTimer = null;
    connectBridge();
  }, 5000);
}

function applySettings(s) {
  if (!s) return;
  settings = s;
  if (IS_PVPBOT) {
    // pvpbot 的设定由名字决定，允许它打任何玩家（包括主人），
    // 免得 GUI 里默认的"不打人 / 保护主人"把它变成只会跟路的木头人。
    settings.pvp = true;
    settings.owner = '';
  }
  if (typeof s.password === 'string' && s.password) {
    auth.password = s.password;
  }
  if (typeof s.authMode === 'string' && s.authMode) {
    auth.mode = s.authMode;
  }
}

function handleBridgeMessage(msg) {
  switch (msg.type) {
    case 'welcome':
      if (msg.protocol !== 1) {
        console.error('[bridge] 协议版本不一致：模组=' + msg.protocol + ' 脚本=1');
      }
      if (msg.settings) applySettings(msg.settings);
      console.log('[bridge] 握手成功，名字=' + opts.name + (auth.password ? '，已获得服务器密码' : ''));
      break;
    case 'settings':
      applySettings(msg.settings || {});
      break;
    case 'say':
      say(msg.text);
      break;
    case 'command':
      runCommand(msg);
      break;
    case 'request_state':
      reportState();
      break;
    case 'ping':
      sendBridge({ type: 'pong' });
      break;
    default:
      break;
  }
}

// ---------------- 密码插件自动登录 / 注册 --------------------------
const auth = {
  password: opts.password || '',
  mode: (opts.authMode || 'auto').toLowerCase(),
  solved: false,
  attempts: 0,
  lastAt: 0,
  timer: null,
  needRegister: false,
  loginCmd: 'login',
  registerCmd: 'register',
  registerWithConfirm: true
};

function clearAuthTimer() {
  if (auth.timer) {
    clearInterval(auth.timer);
    auth.timer = null;
  }
}

function authDone() {
  if (auth.solved) return;
  auth.solved = true;
  clearAuthTimer();
  console.log('[auth] 认证完成');
  sendBridge({ type: 'event', event: 'auth', detail: 'logged in' });
}

/**
 * 放弃认证但解除封锁：服务器没装登录插件、密码错误、或者一直不回话时，
 * 继续重试只会刷屏甚至被踢，不如让 AI 先正常活动。
 */
function authAbort(reason) {
  clearAuthTimer();
  if (auth.solved) return;
  auth.solved = true;
  console.warn('[auth] 停止自动认证：' + reason + '（继续正常活动）');
  sendBridge({ type: 'event', event: 'auth', detail: reason });
}

function sendAuth() {
  if (!botReady || auth.solved || !auth.password) return;
  const now = Date.now();
  if (now - auth.lastAt < 3000) return;
  if (auth.attempts >= 6) {
    // 有的服压根没登录插件，或者提示格式没见过：重试 6 次就收手，避免刷屏被踢
    authAbort('服务器没有响应登录命令');
    return;
  }
  auth.lastAt = now;
  auth.attempts += 1;
  const pwd = auth.password;
  const register = auth.mode === 'register' || (auth.mode === 'auto' && auth.needRegister);
  const cmd = register
    ? auth.registerCmd + ' ' + pwd + (auth.registerWithConfirm ? ' ' + pwd : '')
    : auth.loginCmd + ' ' + pwd;
  try {
    bot.chat('/' + cmd);
    // 日志里只打命令名，绝不把密码写进文件
    console.log('[auth] 发送 /' + cmd.split(' ')[0] + '（第 ' + auth.attempts + ' 次）');
  } catch (e) {
    console.warn('[auth] 发送失败：' + (e && e.message || e));
  }
}

function startAuthLoop() {
  clearAuthTimer();
  auth.solved = false;
  auth.attempts = 0;
  auth.lastAt = 0;
  auth.needRegister = false;
  auth.registerWithConfirm = true;
  auth.logged = 0;
  if (!auth.password) return;
  console.log('[auth] 已启用自动认证，模式=' + auth.mode);
  // 有的插件在出生前就要求登录，先抢一次，之后每 3 秒重试
  setTimeout(sendAuth, 1200);
  auth.timer = setInterval(sendAuth, 3000);
  // 超时也要解除封锁，否则 AI 会一直站着不说话
  setTimeout(() => {
    if (!auth.solved) authAbort('登录超时（服务器没有明确提示）');
  }, 45000);
}

/** 服务器提示里可能出现的成功 / 失败 / 没有登录插件 三类文本 */
const AUTH_OK = /(登录成功|登陆成功|注册成功|已登录|欢迎回来|successfully logged in|logged in successfully|you are now logged in|registration successful|already logged in|you are already logged)/i;
const AUTH_FAIL = /(密码错误|密码不正确|密码有误|登录失败|注册失败|wrong password|invalid password|incorrect password|login failed|too many attempts|attempts exceeded)/i;
const AUTH_NONE = /(unknown command|unknown or incomplete command|未知的命令|未知命令|没有这个命令|命令不存在|不支持该命令)/i;

/** 从服务器提示里判断要 login 还是 register，并识别登录成功 / 失败 */
function inspectAuthMessage(text) {
  const s = String(text || '');
  if (!s || auth.solved) return;
  if (auth.password && (auth.logged || 0) < 60) {
    // 未认证时把服务器提示原样记下来，密码不会出现在这里
    auth.logged = (auth.logged || 0) + 1;
    console.log('[auth] 服务器：' + s.replace(/\n/g, ' ').slice(0, 140));
  }
  if (AUTH_OK.test(s)) {
    authDone();
    return;
  }
  if (auth.password && AUTH_FAIL.test(s)) {
    authAbort('密码错误或登录失败，请检查密码');
    return;
  }
  if (auth.attempts > 0 && AUTH_NONE.test(s)) {
    // 服务器根本没装登录插件，继续发 /login 只会刷屏
    authAbort('服务器没有登录插件');
    return;
  }
  if (/register|reg|注册/.test(s.toLowerCase()) && /(usage|用法|格式|正确使用)/i.test(s) && auth.registerWithConfirm) {
    // 有些服务器只收 /register <密码>，带确认会被回用法提示
    auth.registerWithConfirm = false;
  }
  const m = s.toLowerCase().match(/\/\s*(register|reg|login|l)\b/);
  if (!m) return;
  if (m[1] === 'register' || m[1] === 'reg') {
    auth.needRegister = true;
    auth.registerCmd = m[1];
  } else {
    auth.loginCmd = m[1];
  }
  if (auth.password && !auth.solved) sendAuth();
}

// ---------------- Minecraft bot --------------------------
let bot = null;
let botReady = false;
let reconnectTimer = null;
let reconnectDelay = 15000;
let banUntil = 0;

/** 微软登录缓存目录：从用户目录挪到 aiplayer-bot 下，并限制为只有当前用户可访问 */
const MSA_DIR = path.join(__dirname, 'msa-cache');

function prepareMsaFolder() {
  try {
    fs.mkdirSync(MSA_DIR, { recursive: true });
  } catch (e) {
    console.warn('[auth] 创建微软登录缓存目录失败：' + (e && e.message || e));
    return;
  }
  // 旧版 prismarine-auth 把缓存放在游戏目录的 nmp-cache：首次升级时搬过来，不用重新授权
  try {
    const legacy = path.join(process.cwd(), 'nmp-cache');
    if (fs.existsSync(legacy) && fs.readdirSync(MSA_DIR).length === 0) {
      for (const f of fs.readdirSync(legacy)) {
        try {
          fs.copyFileSync(path.join(legacy, f), path.join(MSA_DIR, f));
        } catch (ignored) {}
      }
      console.log('[auth] 已迁移旧的微软登录缓存到 ' + MSA_DIR);
    }
  } catch (ignored) {}
  // Windows：去掉继承的权限，只给当前用户完全控制（避免同机其它账户读取 token）
  if (process.platform === 'win32') {
    const user = (os.userInfo && os.userInfo().username) || process.env.USERNAME || '';
    if (user) {
      execFile('icacls', [MSA_DIR, '/inheritance:r', '/grant:r', user + ':F'],
        { windowsHide: true }, (err) => {
          if (err) console.warn('[auth] 设置缓存目录权限失败：' + err.message);
        });
    }
  }
}

/** 把 chat component / 字符串 / 任意对象统一转成可读文字（踢出原因就是这类对象） */
function reasonText(reason) {
  const flat = (c) => {
    if (c == null) return '';
    if (typeof c === 'string') return c;
    if (typeof c !== 'object') return String(c);
    if (c.type === 'string' && typeof c.value === 'string') return c.value;
    if (c.type === 'compound' && c.value) return flat(c.value);
    if (c.type === 'list' && Array.isArray(c.value)) return c.value.map(flat).join('');
    let s = '';
    if (typeof c.text === 'string') s += c.text;
    else if (typeof c.translate === 'string') s += c.translate;
    if (Array.isArray(c.extra)) s += c.extra.map(flat).join('');
    if (Array.isArray(c.with)) s += '[' + c.with.map(flat).join(', ') + ']';
    if (!s) {
      for (const v of Object.values(c)) s += flat(v);
    }
    return s;
  };
  const text = flat(reason).trim();
  if (text) return text;
  try {
    return JSON.stringify(reason);
  } catch (e) {
    return String(reason);
  }
}

function createBot() {
  if (reconnectTimer) { clearTimeout(reconnectTimer); reconnectTimer = null; }
  console.log('[bridge] 正在登录 ' + opts.host + ':' + opts.port + ' 身份=' + opts.username + ' 认证=' + opts.auth);
  const options = {
    host: opts.host,
    port: opts.port,
    // 离线登录也必须给用户名：GUI 里没填就用 bot 名，否则 mineflayer 直接拒绝创建
    username: opts.username || opts.name,
    auth: opts.auth,
    version: opts.version || false,
    hideErrors: false
  };
  if (opts.auth === 'microsoft') {
    console.log('[auth] 微软正版登录：首次运行会在游戏内弹窗显示验证码，在浏览器完成一次授权即可（之后自动复用缓存）');
    prepareMsaFolder();
    options.profilesFolder = MSA_DIR;
    // 换账号：丢掉该用户名下的旧令牌，重新走设备码授权
    if (opts.refreshMsa) {
      options.forceRefresh = true;
      opts.refreshMsa = false;
      console.log('[auth] 已清除缓存的微软令牌，请登录新的正版账号');
    }
    // mineflayer / prismarine-auth 拿到设备码时回调：转发给模组，由模组弹登录窗口
    options.onMsaCode = (data) => {
      // prismarine-auth 不同版本回调形状不一样：对象（userCode/verificationUri）、
      // 驼峰/下划线混用，甚至直接给一段提示文字。全部兜住，否则窗口永远不弹。
      const raw = (data && typeof data === 'object' && data.body && typeof data.body === 'object')
        ? data.body : data;
      const pick = (...keys) => {
        for (const k of keys) {
          const v = raw && raw[k];
          if (typeof v === 'string' && v.trim()) return v.trim();
        }
        return '';
      };
      let code = pick('userCode', 'user_code', 'code', 'deviceCode');
      let url = pick('verificationUri', 'verification_uri', 'verificationUrl', 'url');
      const message = typeof raw === 'string' ? raw : pick('message', 'verificationUriMessage');
      if ((!code || !url) && message) {
        // 优先取紧跟在 code 后面的那串；取不到再退回全文里第一段大写字母数字
        const near = /(?:code|\u4ee3\u7801)[^\w]{0,6}([A-Z0-9][A-Z0-9-]{5,11})/i.exec(message);
        if (!code && near) code = near[1];
        if (!code) {
          const any = /\b([A-Z0-9][A-Z0-9-]{5,11})\b/.exec(message);
          if (any) code = any[1];
        }
        const urlHit = /(https?:\/\/[^\s"'<>]+)/.exec(message);
        if (!url && urlHit) url = urlHit[1];
      }
      if (!url) url = 'https://microsoft.com/link';
      console.log('[auth] 微软登录回调：' + JSON.stringify(data));
      if (!code) {
        console.warn('[auth] 设备码解析失败，无法弹窗；请查看上面的回调内容');
        sendBridge({ type: 'event', event: 'error', detail: 'MSA code missing' });
        return;
      }
      console.log('[auth] 微软登录：打开 ' + url + ' 输入验证码 ' + code);
      const ttl = (data && (data.expiresIn || data.expires_in)) || 900;
      pendingMsa = { type: 'event', event: 'msa_code', code: code, url: url,
        expires: ttl, expiresAt: Date.now() + ttl * 1000 };
      sendBridge(pendingMsa);
    };
  }
  botReady = false;
  bot = mineflayer.createBot(options);

  // Grim 的 Timer / TickTimer 统计的是「单位时间内收到多少个移动包」。
  // mineflayer 物理循环每 50ms 发一个 position(_look)，寻路 / 转头又会额外插 look 包，
  // 同一 tick 里挤两个包就会被判成 timer；旧实现是「45ms 内直接丢」，
  // 既丢掉了转头，又放过了 45~50ms 的包（一秒 21~22 个，照样触发）。
  // 现在改成合并：同一个 50ms 时间片里只发最后一个包，并加 0~3ms 抖动，
  // 不会出现机械的 50.000ms 节拍，也保证一秒不超过 20 个包。
  try {
    const client = bot._client;
    if (client && typeof client.write === 'function') {
      const originalWrite = client.write.bind(client);
      const MOVE_PACKETS = { position: 1, position_look: 1, look: 1, flying: 1 };
      let nextMoveAt = 0;
      let queuedMove = null;
      let queuedTimer = null;
      const flushMove = () => {
        queuedTimer = null;
        const packet = queuedMove;
        queuedMove = null;
        if (!packet) return;
        nextMoveAt = Date.now() + 50 + Math.random() * 3;
        try {
          originalWrite(packet.name, packet.params);
        } catch (e) {
          // 连接已经断开：丢掉这一个包就行
        }
      };
      client.write = function (name, params) {
        if (!MOVE_PACKETS[name]) return originalWrite(name, params);
        const now = Date.now();
        if (now >= nextMoveAt) {
          nextMoveAt = now + 50 + Math.random() * 3;
          return originalWrite(name, params);
        }
        queuedMove = { name: name, params: params };
        if (!queuedTimer) {
          queuedTimer = setTimeout(flushMove, Math.max(1, nextMoveAt - now));
        }
        return true;
      };
    }
  } catch (e) {
    console.warn('[bridge] 移动包节流挂载失败：' + e.message);
  }

  try {
    if (pathfinder && typeof pathfinder.pathfinder === 'function') {
      bot.loadPlugin(pathfinder.pathfinder);
    } else if (typeof pathfinder === 'function') {
      bot.loadPlugin(pathfinder);
    }
  } catch (e) {
    console.warn('[bridge] 加载 pathfinder 失败：' + e.message);
  }

  bot.once('spawn', () => {
    botReady = true;
    pendingMsa = null;
    reconnectDelay = 15000;
    banUntil = 0;
    console.log('[bridge] 已进入服务器：' + (bot.game && bot.game.dimension));
    sendBridge({ type: 'event', event: 'join', detail: 'spawned' });
    // 有密码就先过认证；未登录完不执行行动指令（见 runCommand）
    startAuthLoop();
    if (goals && bot.pathfinder) {
      // 不允许自动跳进岩浆 / 挖穿房子：移动交给指令，保守一些
      try {
        const mcData = require('minecraft-data')(bot.version);
        const moves = new pathfinder.Movements(bot, mcData);
        moves.allowParkour = false;
        moves.canDig = false;
        moves.allow1by1towers = false;
        bot.pathfinder.setMovements(moves);
      } catch (e) {
        console.warn('[bridge] 初始化寻路参数失败：' + e.message);
      }
    }
    // 让模组知道寻路有没有到达 / 找不到路；找不到路立刻退化成直线，别站着干等
    try {
      if (bot.pathfinder) {
        bot.on('goal_reached', () => reportAction('goto', 'arrived'));
        bot.on('path_update', (r) => {
          if (!r || (r.status !== 'noPath' && r.status !== 'timeout')) return;
          const fixed = goalTarget;
          const who = followTargetName;
          console.log('[move] 寻路失败：' + r.status);
          goalSetAt = 0;
          goalAnchor = null;
          goalTarget = null;
          followTargetName = null;
          try {
            if (bot.pathfinder.stop) bot.pathfinder.stop();
          } catch (e) {
            // 忽略
          }
          if (fixed) {
            straightWalk(fixed.x, fixed.y, fixed.z);
            return;
          }
          const p = who ? bot.players[who] : null;
          if (p && p.entity) {
            straightWalk(p.entity.position.x, p.entity.position.y, p.entity.position.z);
          }
        });
      }
    } catch (e) {
      // 非致命
    }
    reportState();
  });

  bot.on('chat', (username, message) => {
    if (!username || username === bot.username) return;
    sendBridge({ type: 'chat', sender: username, text: message, whisper: false });
  });

  // 系统提示（登录 / 注册 / 被要求登录）都在这里：用来判断该发 /login 还是 /register
  bot.on('messagestr', (message) => inspectAuthMessage(message));

  // 私聊：mineflayer 某些版本会单独发 whisper 事件
  if (typeof bot.on === 'function') {
    bot.on('whisper', (username, message) => {
      if (!username || username === bot.username) return;
      sendBridge({ type: 'chat', sender: username, text: message, whisper: true });
    });
  }

  bot.on('death', () => {
    // 记下死亡前的意图：重生后恢复「跟随某人 / 去某坐标」，不然醒过来就发呆
    deathIntent = followTargetName
        ? { type: 'follow', name: followTargetName }
        : goalTarget
            ? { type: 'goto', x: goalTarget.x, y: goalTarget.y, z: goalTarget.z }
            : null;
    sendBridge({ type: 'event', event: 'death', detail: '死亡，等待重生' });
    startAutoRespawn();
  });

  // ---------------- 死亡自动重生 ----------------
  /** 死亡前的行动意图（跟随谁 / 去哪个坐标），重生后恢复 */
  let deathIntent = null;
  let respawnTimer = null;

  function startAutoRespawn() {
    if (respawnTimer) {
      clearInterval(respawnTimer);
      respawnTimer = null;
    }
    let tries = 0;
    respawnTimer = setInterval(() => {
      if (!bot) {
        clearInterval(respawnTimer);
        respawnTimer = null;
        return;
      }
      // 血量恢复说明已经重生（有些服务器会自动重生，或者玩家点了重生）
      if (bot.health > 0) {
        clearInterval(respawnTimer);
        respawnTimer = null;
        const intent = deathIntent;
        deathIntent = null;
        if (intent) {
          // 等实体 / 区块同步完再恢复意图，否则 pathfinder 会拿不到目标
          setTimeout(() => restoreIntent(intent), 1200);
        }
        return;
      }
      // 死亡界面一直没消失：主动点重生，最多试 20 次（约 16 秒）
      if (tries++ > 20) {
        clearInterval(respawnTimer);
        respawnTimer = null;
        console.warn('[bridge] 自动重生超时，可能被服务器拦住');
        return;
      }
      try {
        if (typeof bot.respawn === 'function') {
          bot.respawn();
        }
      } catch (e) {
        // 下一次再试
      }
    }, 800);
  }

  function restoreIntent(intent) {
    if (!botReady || !intent) return;
    if (intent.type === 'follow' && bot.players[intent.name]) {
      console.log('[bridge] 重生后继续跟随 ' + intent.name);
      follow(intent.name);
    } else if (intent.type === 'goto') {
      console.log('[bridge] 重生后继续前往 ' + Math.round(intent.x) + ',' + Math.round(intent.z));
      walkTo(intent.x, intent.y, intent.z);
    }
  }

  bot.on('kicked', (reason) => {
    // 踢出原因是 chat component 对象，直接 String() 只会得到 [object Object]
    const text = reasonText(reason);
    if (/verify username/i.test(text)) {
      // 典型正版服提示：离线登录必然被踢
      console.warn('[auth] 服务器开启了正版验证（online-mode=true）：把 GUI 的「登录：离线」改成「登录：微软」，'
          + '或让服主设置 online-mode=false');
      // 重连也还是同样的结果，停止重试避免刷屏
      opts.reconnect = false;
    }
    // 反机器人验证 / 临时封禁：重连只会不断刷新封禁时间，先等一段时间
    if (/无法验证|验证您是否|反机器人|robot|bot check|sonar|临时(禁止|封禁)|temporarily banned|try again later/i.test(text)) {
      banUntil = Date.now() + 5 * 60 * 1000;
      console.warn('[bridge] 服务器反机器人验证未通过，5 分钟内不再重连');
    }
    // 白名单 / 永久封禁 / 版本不兼容这类永久性拒绝，重连没有意义
    if (/whitelist|blacklist|permanently banned|you are banned|invalid session|outdated client|incompatible|not allowed to join/i.test(text)) {
      console.warn('[bridge] 服务器永久拒绝该账号（白名单/封禁/版本不符），已停止重连');
      opts.reconnect = false;
    }
    sendBridge({ type: 'event', event: 'kicked', detail: text.slice(0, 120) });
    console.warn('[bridge] 被服务器踢出：' + text);
  });

  bot.on('error', (err) => {
    const text = String((err && err.message) || err);
    const bad = /No data available for version (\S+)/.exec(text);
    if (bad) {
      // 目标版本比 mineflayer 内置协议新：让模组用 ViaProxy 转一道再试
      console.warn('[bridge] 服务器版本 MC ' + bad[1] + ' 不在 mineflayer 支持范围，尝试通过 ViaProxy 转译登录');
      opts.reconnect = false;
      sendBridge({ type: 'event', event: 'unsupported', detail: bad[1] });
    }
    // 正版校验失败（账号没买过 Minecraft / 令牌失效）：自动重连只会不停申请新设备码，
    // 登录窗口就会一直弹、一直换码，先停下来让玩家换个账号或手工重试
    if (/does not own|doesn'?t own|invalid_grant|invalid credentials|unauthorized|no profile|failed to (verify|authenticate)/i.test(text)) {
      console.warn('[auth] 正版校验失败，已停止自动重连：' + text);
      opts.reconnect = false;
      sendBridge({ type: 'event', event: 'msa_failed', detail: text.slice(0, 120) });
    }
    sendBridge({ type: 'event', event: 'error', detail: text.slice(0, 120) });
    console.warn('[bridge] 连接错误：' + text);
  });

  bot.on('end', (reason) => {
    botReady = false;
    // 掉线后重新登录：认证状态必须清零，重连时才能再发一次 /login
    auth.solved = false;
    auth.attempts = 0;
    clearAuthTimer();
    sendBridge({ type: 'event', event: 'end', detail: String(reason || '').slice(0, 120) });
    if (pendingMsa && pendingMsa.expiresAt && Date.now() > pendingMsa.expiresAt) {
      pendingMsa = null;
    }
    if (pendingMsa) {
      // 设备码还在等玩家授权：这时候重连只会再申请一个新码，游戏里的登录窗口
      // 就会“消失 → 又弹一个”，玩家永远输不完。等验证码过期或进服后再重连。
      console.log('[bridge] 微软授权还没完成，暂不重连');
      return;
    }
    if (opts.reconnect && !reconnectTimer) {
      const wait = Math.max(reconnectDelay, banUntil - Date.now(), 5000);
      console.log('[bridge] 连接结束，' + Math.ceil(wait / 1000) + ' 秒后重连');
      reconnectTimer = setTimeout(createBot, wait);
      reconnectDelay = Math.min(Math.round(reconnectDelay * 1.5), 300000);
    }
  });
}

// ---------------- 状态上报 --------------------------
const HOSTILE = new Set([
  'zombie', 'husk', 'drowned', 'skeleton', 'stray', 'creeper', 'spider', 'cave_spider',
  'enderman', 'witch', 'slime', 'magma_cube', 'pillager', 'vindicator', 'evoker', 'ravager',
  'phantom', 'blaze', 'ghast', 'wither_skeleton', 'zombified_piglin', 'hoglin', 'piglin_brute'
]);

function entityName(entity) {
  if (!entity) return '?';
  if (entity.username) return entity.username;
  if (entity.name) return entity.name;
  if (entity.displayName) return entity.displayName;
  return entity.type || 'entity';
}

function reportState() {
  if (!botReady || !bot || !bot.entity) return;
  const p = bot.entity.position;
  const feet = bot.blockAt(p.offset(0, 0, 0));
  const below = bot.blockAt(p.offset(0, -1, 0));
  const held = bot.heldItem && bot.heldItem.name ? bot.heldItem.name : '';
  const gameMode = (bot.game && bot.game.gameMode) || '';
  const players = [];
  for (const [name, info] of Object.entries(bot.players || {})) {
    if (name === bot.username || !info.entity || !info.entity.position) continue;
    const d = Math.round(p.distanceTo(info.entity.position));
    if (d <= 64) players.push(name + ':' + d);
  }
  const hostiles = [];
  for (const id in (bot.entities || {})) {
    const e = bot.entities[id];
    if (!e || !e.position || e === bot.entity) continue;
    const base = (e.name || '').replace(/^minecraft:/, '');
    if (!HOSTILE.has(base)) continue;
    const d = Math.round(p.distanceTo(e.position));
    if (d <= 24) hostiles.push(base + ':' + d);
  }
  const inventory = [];
  try {
    for (const item of bot.inventory.items()) inventory.push(item.name + ' x' + item.count);
  } catch (e) {
    // 库存还没同步时忽略
  }
  // 打开箱子 / 菜单时把界面里有东西的槽位报上去，大模型才能决定点哪个
  let windowInfo = null;
  try {
    if (bot.currentWindow) {
      const slots = [];
      const slotsArr = bot.currentWindow.slots || [];
      for (let i = 0; i < slotsArr.length && slots.length < 12; i++) {
        const it = slotsArr[i];
        if (it && it.name) slots.push(i + ':' + it.name + 'x' + (it.count || 1));
      }
      windowInfo = { title: String(bot.currentWindow.title || ''), slots };
    }
  } catch (e) {
    // 界面信息不影响主流程
  }
  sendBridge({
    type: 'state',
    window: windowInfo,
    x: Math.round(p.x), y: Math.round(p.y), z: Math.round(p.z),
    health: Math.round(bot.health || 0),
    food: Math.round(bot.food || 0),
    dimension: (bot.game && bot.game.dimension) || 'overworld',
    time: bot.time ? bot.time.timeOfDay : 0,
    isDay: bot.time ? bot.time.isDay : true,
    feet: blockName(feet),
    below: blockName(below),
    held,
    gameMode,
    serverBrand: String((bot.game && bot.game.serverBrand) || '').slice(0, 24),
    pathfinder: !!pathfinder,
    goal: followTargetName ? 'follow:' + followTargetName : (goalTarget ? 'goto' : 'none'),
    players, hostiles, inventory,
    lastAction: lastAction.slice(0, 80)
  });
}

function blockName(block) {
  if (!block || !block.name) return 'unknown';
  return String(block.name).replace(/^minecraft:/, '');
}

setInterval(() => {
  if (botReady && bot && bot.entity) {
    reportState();
  } else {
    // 还没进服（比如正在等微软设备码授权）也要报心跳。模组 20 秒收不到任何消息
    // 就会判定掉线并断开桥接，而断开后脚本会重连、重新申请设备码 ——
    // 玩家看到的就是登录窗口反复消失又弹出。
    sendBridge({ type: 'state_hold', stage: pendingMsa ? 'msa' : 'connecting' });
  }
}, 2000);

// ---------------- 指令执行 --------------------------
/** 最后一条指令的时间：长时间没指令时自动在附近走动，避免站着不动像挂机假人 */
let lastCommandAt = Date.now();
/** 最近一次动作结果：随 state 上报，方便大模型判断上一步有没有成功 */
let lastAction = '';

function reportAction(action, detail) {
  lastAction = action + ' ' + detail;
  console.log('[move] ' + lastAction);
  // 走到 / 走不到 / 停止，都不再算「有目标在跑」
  if (action === 'goto' || action === 'stop') {
    goalSetAt = 0;
    goalAnchor = null;
    goalTarget = null;
  }
  // 只上报动作名和结果，不带坐标细节
  sendBridge({ type: 'event', event: 'action', detail: lastAction.slice(0, 120) });
}

function pickItem(name) {
  const key = String(name || '').toLowerCase();
  return (bot.inventory.items() || []).find((i) => i.name.toLowerCase().includes(key)) || null;
}

function say(text) {
  const msg = String(text || '').slice(0, 256);
  if (!msg) return;
  if (!botReady) {
    // 模组下发时角色还没进服：把原因回报过去，界面上能看到"在线但说不了话"
    sendBridge({ type: 'event', event: 'say', detail: 'not in server yet' });
    return;
  }
  // 密码服务器未登录前不说话，避免被插件当成机器人
  if (auth.password && !auth.solved) {
    sendBridge({ type: 'event', event: 'say', detail: 'waiting for auth' });
    return;
  }
  try {
    bot.chat(msg);
  } catch (e) {
    sendBridge({ type: 'event', event: 'say', detail: 'chat failed: ' + (e && e.message || e) });
  }
}

/** 所有还在跑的直线移动计时器：新指令 / stop 时统一清掉，避免多个 timer 抢方向键 */
const moveTimers = new Set();

function trackTimer(timer) {
  moveTimers.add(timer);
  return timer;
}

function goIdle() {
  for (const t of moveTimers) clearInterval(t);
  moveTimers.clear();
  if (!botReady) return;
  try {
    if (bot.pathfinder && bot.pathfinder.stop) bot.pathfinder.stop();
    bot.clearControlStates();
  } catch (e) {
    // 忽略
  }
}

function stopMoving() {
  goIdle();
}

function nearestEntityByName(target) {
  if (!bot || !bot.entities) return null;
  const key = String(target || '').toLowerCase();
  let best = null;
  let bestDist = Infinity;
  for (const id in bot.entities) {
    const e = bot.entities[id];
    if (!e || !e.position || e === bot.entity) continue;
    const names = [e.username, e.name, e.displayName]
      .filter(Boolean).map((s) => String(s).toLowerCase());
    if (!names.some((n) => n === key || n.includes(key))) continue;
    const d = bot.entity.position.distanceTo(e.position);
    if (d < bestDist) { bestDist = d; best = e; }
  }
  return best;
}

/** 当前寻路目标的设置时间 / 起点 / 目标点：用来判断是否卡住（pathfinder 没有公开 goal 属性） */
let goalSetAt = 0;
let goalAnchor = null;
let goalTarget = null;
/** 正在跟随的玩家名：寻路失败时用它退化成直线跟随 */
let followTargetName = null;

function straightWalk(x, y, z) {
  goIdle();
  goalSetAt = 0;
  goalAnchor = null;
  goalTarget = null;
  followTargetName = null;
  if (!bot.entity) return;
  console.log('[move] 直线前进 ' + Math.round(x) + ',' + Math.round(z));
  let lastPos = bot.entity.position.clone();
  let lastMoveAt = Date.now();
  let jumpUntil = 0;
  let warned = false;
  const startedAt = Date.now();
  const timer = trackTimer(setInterval(() => {
    if (!botReady || !bot.entity) { goIdle(); return; }
    const now = Date.now();
    const p = bot.entity.position;
    const dx = x - p.x;
    const dz = z - p.z;
    const dist = Math.hypot(dx, dz);
    if (dist < 1.2 || now - startedAt > 25000) {
      const reached = dist < 1.2;
      goIdle();
      reportAction('goto', reached ? 'arrived' : 'timeout');
      return;
    }
    // 平滑转向：每 50ms 最多转 0.22 弧度，避免瞬间扭头（看起来也更像真人）
    const desired = Math.atan2(-dx, -dz);
    const diff = ((desired - bot.entity.yaw) % (Math.PI * 2) + Math.PI * 3) % (Math.PI * 2) - Math.PI;
    const step = Math.max(-0.22, Math.min(0.22, diff));
    if (Math.abs(step) > 0.01) bot.look(bot.entity.yaw + step, 0, false);
    // 方向偏差太大时先原地转，转正了再走
    bot.setControlState('forward', Math.abs(diff) < 1.2);
    if (bot.entity.isCollidedHorizontally && bot.entity.onGround && now > jumpUntil) {
      bot.setControlState('jump', true);
      jumpUntil = now + 260;
    } else if (now > jumpUntil) {
      bot.setControlState('jump', false);
    }
    if (p.distanceTo(lastPos) > 0.2) {
      lastPos = p.clone();
      lastMoveAt = now;
    }
    if (!warned && now - lastMoveAt > 2500) {
      warned = true;
      console.log('[move] 坐标没变化 ' + p.x.toFixed(1) + ',' + p.y.toFixed(1) + ',' + p.z.toFixed(1)
          + '（脚本在发移动，若人物不动则是服务端拦截）');
    }
    if (now - lastMoveAt > 8000) {
      goIdle();
      reportAction('goto', 'blocked');
    }
  }, 50));
}

function walkTo(x, y, z) {
  if (!botReady) return;
  goIdle();
  if (bot.pathfinder && goals) {
    try {
      bot.pathfinder.setGoal(new goals.GoalNear(Math.floor(x), Math.floor(y), Math.floor(z), 1));
      goalSetAt = Date.now();
      goalAnchor = { x: bot.entity.position.x, z: bot.entity.position.z };
      goalTarget = { x, y, z };
      console.log('[move] 寻路目标 ' + Math.round(x) + ',' + Math.round(z));
      return;
    } catch (e) {
      // 退回直线移动
    }
  }
  // 没有 pathfinder：朝目标方向按 W + 必要时跳，简单但不穿墙
  straightWalk(x, y, z);
}

function follow(name) {
  if (!botReady) return;
  goIdle();
  followTargetName = name;
  const player = bot.players[name];
  if (bot.pathfinder && goals && player && player.entity) {
    try {
      bot.pathfinder.setGoal(new goals.GoalFollow(player.entity, 2), true);
      goalSetAt = Date.now();
      goalAnchor = null;
      goalTarget = null;
      let anchor = null;
      trackTimer(setInterval(() => {
        if (!botReady || !bot.entity || followTargetName !== name) return;
        const cur = bot.players[name];
        if (!cur || !cur.entity) return;
        const d = bot.entity.position.distanceTo(cur.entity.position);
        if (d <= 4) {
          anchor = null;
          return;
        }
        const now = Date.now();
        if (!anchor) {
          anchor = { pos: bot.entity.position.clone(), at: now };
          return;
        }
        if (bot.entity.position.distanceTo(anchor.pos) > 0.4) {
          anchor = { pos: bot.entity.position.clone(), at: now };
          return;
        }
        if (now - anchor.at < 4000) return;
        console.log('[move] 跟随卡住，直线靠近 ' + name);
        straightWalk(cur.entity.position.x, cur.entity.position.y, cur.entity.position.z);
      }, 2000));
      return;
    } catch (e) {
      // 退化为直接跟
    }
  }
  const timer = trackTimer(setInterval(() => {
    if (!botReady || !bot.entity || !player || !player.entity) {
      clearInterval(timer);
      moveTimers.delete(timer);
      return;
    }
    const d = bot.entity.position.distanceTo(player.entity.position);
    if (d > 3) {
      bot.lookAt(player.entity.position.offset(0, 1.6, 0), false);
      bot.setControlState('forward', true);
    } else {
      bot.setControlState('forward', false);
    }
  }, 250));
  setTimeout(() => {
    clearInterval(timer);
    moveTimers.delete(timer);
    stopMoving();
  }, 60000);
}

function attack(target) {
  if (!botReady) return;
  const entity = nearestEntityByName(target);
  if (!entity) return;
  // 玩家目标：只有配置里开了 pvp 才打（公共服务器里默认不打人）
  const isPlayer = !!entity.username;
  if (isPlayer && !settings.pvp) {
    say('我现在不打架。');
    return;
  }
  try {
    bot.lookAt(entity.position.offset(0, 1.5, 0), false);
    bot.attack(entity, true);
  } catch (e) {
    // 目标已消失
  }
}

function useItem(name) {
  if (!botReady) return;
  const item = pickItem(name);
  if (!item) {
    reportAction('use', 'no item');
    return;
  }
  try {
    bot.equip(item, 'hand').then(() => bot.activateItem())
        .then(() => reportAction('use', 'ok ' + item.name))
        .catch(() => reportAction('use', 'fail'));
  } catch (e) {
    reportAction('use', 'fail');
  }
}

/** 挖掉指定坐标 / 名字的方块（最多 6 格内，避免跑远挖别人家） */
function mineBlock(target, x, y, z) {
  if (!botReady || !bot.entity) return;
  try {
    let block = null;
    const p = bot.entity.position;
    if (Number.isFinite(x) && Number.isFinite(y) && Number.isFinite(z)) {
      block = bot.blockAt(p.offset(Math.round(x - p.x), Math.round(y - p.y), Math.round(z - p.z)));
    } else if (target) {
      const key = String(target).toLowerCase();
      block = bot.findBlock({
        maxDistance: 16,
        matching: (b) => !!b && !!b.name && b.name.toLowerCase().includes(key)
      });
    }
    if (!block || block.name === 'air') {
      reportAction('mine', 'not found');
      return;
    }
    if (p.distanceTo(block.position) > 6) {
      reportAction('mine', 'too far');
      walkTo(block.position.x, block.position.y, block.position.z);
      return;
    }
    if (bot.canDigBlock && !bot.canDigBlock(block)) {
      reportAction('mine', 'cannot dig');
      return;
    }
    bot.dig(block)
        .then(() => reportAction('mine', 'ok ' + block.name))
        .catch(() => reportAction('mine', 'fail'));
  } catch (e) {
    reportAction('mine', 'fail');
  }
}

/** 在指定坐标放一个方块（用背包里的物品或当前手持） */
function placeBlock(itemName, x, y, z) {
  if (!botReady || !bot.entity) return;
  try {
    const p = bot.entity.position;
    if (![x, y, z].every(Number.isFinite)) {
      reportAction('place', 'need xyz');
      return;
    }
    const target = p.offset(Math.round(x - p.x), Math.round(y - p.y), Math.round(z - p.z));
    if (p.distanceTo(target) > 5) {
      reportAction('place', 'too far');
      return;
    }
    const dest = bot.blockAt(target);
    if (!dest || dest.name !== 'air') {
      reportAction('place', 'occupied');
      return;
    }
    const offsets = [[0, -1, 0], [0, 1, 0], [1, 0, 0], [-1, 0, 0], [0, 0, 1], [0, 0, -1]];
    let ref = null;
    let face = null;
    for (const o of offsets) {
      const b = bot.blockAt(target.offset(o[0], o[1], o[2]));
      if (b && b.boundingBox === 'block') {
        ref = b;
        face = { x: -o[0], y: -o[1], z: -o[2] };
        break;
      }
    }
    if (!ref) {
      reportAction('place', 'no support');
      return;
    }
    const item = itemName ? pickItem(itemName) : bot.heldItem;
    if (!item) {
      reportAction('place', 'no item');
      return;
    }
    bot.equip(item, 'hand')
        .then(() => bot.placeBlock(ref, face))
        .then(() => reportAction('place', 'ok'))
        .catch(() => reportAction('place', 'fail'));
  } catch (e) {
    reportAction('place', 'fail');
  }
}

/** 吃背包里任意食物 */
function eatFood() {
  if (!botReady) return;
  const food = (bot.inventory.items() || []).find((i) => /bread|apple|carrot|potato|steak|beef|porkchop|mutton|chicken|fish|melon|berries|cookie|golden_apple|rabbit|beetroot|pumpkin_pie|stew|soup|dried_kelp/.test(i.name));
  if (!food) {
    reportAction('eat', 'no food');
    return;
  }
  bot.equip(food, 'hand')
      .then(() => bot.consume())
      .then(() => reportAction('eat', 'ok ' + food.name))
      .catch(() => reportAction('eat', 'fail'));
}

/** 换上背包里最好的武器 / 工具（打怪前先保证手里不是空手） */
function equipBest() {
  if (!botReady) { reportAction('equip', 'not ready'); return; }
  const items = (bot.inventory && bot.inventory.items()) || [];
  if (!items.length) { reportAction('equip', 'empty'); return; }
  const score = (name) => {
    if (/netherite_sword/.test(name)) return 100;
    if (/diamond_sword/.test(name)) return 95;
    if (/iron_sword/.test(name)) return 90;
    if (/stone_sword/.test(name)) return 85;
    if (/sword/.test(name)) return 80;
    if (/bow$/.test(name)) return 70;
    if (/axe$/.test(name)) return 60;
    if (/pickaxe/.test(name)) return 50;
    if (/shovel/.test(name)) return 30;
    return 0;
  };
  let best = null;
  let bestScore = 0;
  for (const it of items) {
    const s = score(it.name);
    if (s > bestScore) { bestScore = s; best = it; }
  }
  if (!best) { reportAction('equip', 'no tool'); return; }
  bot.equip(best, 'hand')
      .then(() => reportAction('equip', 'ok ' + best.name))
      .catch(() => reportAction('equip', 'fail'));
}

/** 走到最近的掉落物旁边（靠近后会自动拾取） */
function pickupNearest() {
  if (!botReady || !bot.entities) { reportAction('pickup', 'not ready'); return; }
  let best = null;
  let bestD = 24;
  for (const id in bot.entities) {
    const e = bot.entities[id];
    if (!e || !e.position || e === bot.entity) continue;
    if (!/item|experience_orb/.test(String(e.name || ''))) continue;
    const d = bot.entity.position.distanceTo(e.position);
    if (d < bestD) { bestD = d; best = e; }
  }
  if (!best) { reportAction('pickup', 'none'); return; }
  console.log('[move] 去捡 ' + (best.name || 'item'));
  walkTo(best.position.x, best.position.y, best.position.z);
}

/** 攻击最近的敌对生物 */
function attackNearest() {
  const target = nearestHostile(16);
  if (!target) { reportAction('attack', 'none'); return; }
  attack(target.username || String(target.name || '').replace(/^minecraft:/, ''));
}

/** 附近最近的匹配方块（空闲时用来找点正事做，例如砍树） */
function nearestBlockMatching(pattern, maxDist) {
  if (!bot || !bot.entity) return null;
  const p = bot.entity.position;
  let best = null;
  let bestD = maxDist;
  for (let dx = -8; dx <= 8; dx++) {
    for (let dy = -4; dy <= 4; dy++) {
      for (let dz = -8; dz <= 8; dz++) {
        const b = bot.blockAt(p.offset(dx, dy, dz));
        if (!b || !pattern.test(b.name)) continue;
        const d = p.distanceTo(b.position);
        if (d < bestD) { bestD = d; best = b; }
      }
    }
  }
  return best;
}

/** 右键交互：按钮、拉杆、门、箱子、工作台等（4 格内，或指定坐标） */
function interactBlock(msg) {
  if (!botReady || !bot.entity) { reportAction('interact', 'not ready'); return; }
  const PATTERN = /button|lever|_door|trapdoor|fence_gate|chest|crafting_table|furnace|barrel|hopper|note_block|repeater|comparator|anvil|enchanting_table|_bed$/;
  const p = bot.entity.position;
  let block = null;
  const x = Number(msg.x);
  const y = Number(msg.y);
  const z = Number(msg.z);
  if ([x, y, z].every(Number.isFinite)) {
    block = bot.blockAt(p.offset(Math.round(x - p.x), Math.round(y - p.y), Math.round(z - p.z)));
  } else {
    let best = 4.5;
    for (let dx = -4; dx <= 4; dx++) {
      for (let dy = -2; dy <= 3; dy++) {
        for (let dz = -4; dz <= 4; dz++) {
          const b = bot.blockAt(p.offset(dx, dy, dz));
          if (!b || !PATTERN.test(b.name)) continue;
          const d = p.distanceTo(b.position);
          if (d < best) { best = d; block = b; }
        }
      }
    }
  }
  if (!block || !PATTERN.test(block.name)) { reportAction('interact', 'none'); return; }
  bot.activateBlock(block)
      .then(() => reportAction('interact', 'ok ' + block.name))
      .catch(() => reportAction('interact', 'fail'));
}

/** 点击当前打开的界面槽位：target 填槽位编号或物品名 */
function clickSlot(msg) {
  if (!botReady) { reportAction('click', 'not ready'); return; }
  const win = bot.currentWindow;
  if (!win) { reportAction('click', 'no window'); return; }
  const raw = String(msg.target || '').trim();
  let slot = Number(raw);
  if (!Number.isInteger(slot) || slot < 0 || slot >= win.slots.length) {
    slot = -1;
    const key = raw.toLowerCase();
    for (let i = 0; i < win.slots.length; i++) {
      const it = win.slots[i];
      if (it && it.name && key && it.name.toLowerCase().includes(key)) { slot = i; break; }
    }
  }
  if (slot < 0) { reportAction('click', 'no slot'); return; }
  bot.clickWindow(slot, 0, 0)
      .then(() => reportAction('click', 'ok slot ' + slot))
      .catch(() => reportAction('click', 'fail'));
}

/** 关掉当前打开的界面 */
function closeWindow() {
  if (!botReady || !bot.currentWindow) { reportAction('close_window', 'none'); return; }
  try {
    bot.closeWindow(bot.currentWindow);
    reportAction('close_window', 'ok');
  } catch (e) {
    reportAction('close_window', 'fail');
  }
}

/** 看向玩家 / 坐标 */
function lookAt(msg) {
  if (!botReady || !bot.entity) return;
  try {
    const player = msg.target ? bot.players[msg.target] : null;
    if (player && player.entity) {
      bot.lookAt(player.entity.position.offset(0, 1.6, 0), false);
      reportAction('look', 'ok');
      return;
    }
    const x = Number(msg.x);
    const y = Number(msg.y);
    const z = Number(msg.z);
    if ([x, y, z].every(Number.isFinite)) {
      const p = bot.entity.position;
      bot.lookAt(p.offset(x - p.x, y - p.y, z - p.z), false);
      reportAction('look', 'ok');
      return;
    }
    reportAction('look', 'need target');
  } catch (e) {
    reportAction('look', 'fail');
  }
}

function runCommand(msg) {
  const action = msg.action;
  lastCommandAt = Date.now();
  // 密码服务器里没登录就乱走会被插件当成机器人踢掉：先老老实实完成认证
  if (auth.password && !auth.solved && action !== 'stop') {
    console.log('[cmd] 已忽略 ' + action + '：还没完成登录认证');
    return;
  }
  const where = action === 'goto' ? ' -> ' + msg.x + ',' + msg.y + ',' + msg.z
      : (msg.target ? ' ' + msg.target : '');
  console.log('[cmd] ' + action + where);
  switch (action) {
    case 'goto':
      walkTo(Number(msg.x), Number(msg.y), Number(msg.z));
      break;
    case 'follow':
      follow(msg.target);
      break;
    case 'attack':
      if (msg.target) attack(msg.target);
      else attackNearest();
      break;
    case 'attack_nearest':
      attackNearest();
      break;
    case 'equip':
      equipBest();
      break;
    case 'pickup':
      pickupNearest();
      break;
    case 'stop':
      stopMoving();
      break;
    case 'jump':
      if (botReady) {
        bot.setControlState('jump', true);
        setTimeout(() => { try { bot.setControlState('jump', false); } catch (e) {} }, 250);
      }
      break;
    case 'use':
      useItem(msg.target);
      break;
    case 'mine':
      mineBlock(msg.target, Number(msg.x), Number(msg.y), Number(msg.z));
      break;
    case 'place':
      placeBlock(msg.target, Number(msg.x), Number(msg.y), Number(msg.z));
      break;
    case 'eat':
      eatFood();
      break;
    case 'interact':
      interactBlock(msg);
      break;
    case 'click':
      clickSlot(msg);
      break;
    case 'close_window':
      closeWindow();
      break;
    case 'look':
      lookAt(msg);
      break;
    default:
      reportAction(String(action), 'unknown');
      break;
  }
}

// ---------------- 空闲保活：一直在原地不动会被反作弊当作挂机假人 ----------------
function wanderTick() {
  if (!botReady || !bot || !bot.entity) return;
  if (settings.autoBrain === false) return;
  if (auth.password && !auth.solved) return;
  if (Date.now() - lastCommandAt < 20000) return;
  if (moveTimers.size > 0) return;
  if (goalSetAt && Date.now() - goalSetAt < 25000) return;
  const p = bot.entity.position;
  const below = bot.blockAt(p.offset(0, -1, 0));
  if (!below || below.boundingBox === 'empty') return;
  if (/water|lava|fire|magma|cactus|powder_snow/.test(blockName(below))) return;
  if (bot.entity.isInWater || bot.entity.isInLava) return;
  if (nearestPlayerName(24)) return;
  const dx = Math.random() * 4 - 2;
  const dz = Math.random() * 4 - 2;
  console.log('[move] 空闲，附近走一走');
  walkTo(p.x + dx, p.y, p.z + dz);
}

/** 寻路卡住看门狗：设了固定目标但 8 秒内几乎没位移，就放弃寻路改用直线走 */
function movementWatchdog() {
  if (!botReady || !bot.entity || !goalSetAt || !goalTarget) return;
  if (Date.now() - goalSetAt < 3000) return;
  const p = bot.entity.position;
  const moved = goalAnchor ? Math.hypot(p.x - goalAnchor.x, p.z - goalAnchor.z) : 99;
  if (moved >= 0.5) return;
  let moving = false;
  try {
    moving = !!(bot.pathfinder && bot.pathfinder.isMoving && bot.pathfinder.isMoving());
  } catch (e) {
    // 忽略
  }
  const target = goalTarget;
  reportAction('goto', moving ? 'stuck' : 'no path');
  goIdle();
  // 有路径但动不了 / 根本没有路径，都退回直线移动，至少别站着
  straightWalk(target.x, target.y, target.z);
}

setInterval(wanderTick, 6000);
setInterval(movementWatchdog, 2000);

// ---------------- 生存本能：不经过大模型也要会吃、会跑、会跟人 ----------------
function nearestPlayerName(maxDist, only) {
  if (!bot || !bot.players || !bot.entity) return null;
  let best = null;
  let bestD = maxDist;
  for (const name of Object.keys(bot.players)) {
    if (name === bot.username) continue;
    if (only && name !== only) continue;
    const info = bot.players[name];
    const ent = info && info.entity;
    if (!ent || !ent.position) continue;
    const d = bot.entity.position.distanceTo(ent.position);
    if (d < bestD) {
      bestD = d;
      best = name;
    }
  }
  return best;
}

function nearestHostile(maxDist) {
  if (!bot || !bot.entities || !bot.entity) return null;
  let best = null;
  let bestD = maxDist;
  for (const id in bot.entities) {
    const e = bot.entities[id];
    if (!e || !e.position || e === bot.entity) continue;
    const base = String(e.name || '').replace(/^minecraft:/, '');
    if (!HOSTILE.has(base)) continue;
    const d = bot.entity.position.distanceTo(e.position);
    if (d < bestD) {
      bestD = d;
      best = e;
    }
  }
  return best;
}

function instinctTick() {
  if (!botReady || !bot || !bot.entity) return;
  if (settings.autoBrain === false) return;
  if (auth.password && !auth.solved) return;
  // 刚收到大模型指令时不要抢方向盘
  if (Date.now() - lastCommandAt < 15000) return;
  if (moveTimers.size > 0) return;
  // 固定坐标目标让寻路跑完；跟随目标 8 秒刷新一次，目标走了能重新判断
  if (goalSetAt && goalTarget && Date.now() - goalSetAt < 25000) return;
  if (goalSetAt && !goalTarget && Date.now() - goalSetAt < 8000) return;
  const p = bot.entity.position;
  const hp = Number(bot.health || 20);
  const food = Number(bot.food || 20);

  // 1) 受伤且旁边有怪：先往反方向跑
  const hostile = nearestHostile(7);
  if (hostile && hp <= 10) {
    const dx = p.x - hostile.position.x;
    const dz = p.z - hostile.position.z;
    const len = Math.hypot(dx, dz) || 1;
    console.log('[move] 本能：血量低，先撤');
    straightWalk(p.x + (dx / len) * 10, p.y, p.z + (dz / len) * 10);
    return;
  }

  // 2) 饿了就吃
  if (food <= 14) {
    const has = (bot.inventory.items() || []).some((i) => /bread|apple|carrot|potato|steak|beef|porkchop|mutton|chicken|fish|melon|berries|cookie|golden_apple|rabbit|beetroot|pumpkin_pie|stew|soup|dried_kelp/.test(i.name));
    if (has) {
      console.log('[move] 本能：饿了，吃点东西');
      eatFood();
      return;
    }
  }

  // pvpbot 不跟人，交给下面的 PvP 循环处理
  if (IS_PVPBOT) return;

  // 3) 附近有玩家就跟上去（优先主人，陌生人只在很近时才跟）
  const owner = settings.owner && settings.owner !== bot.username ? settings.owner : '';
  const nearby = (owner && nearestPlayerName(64, owner)) || nearestPlayerName(12);
  if (nearby) {
    console.log('[move] 本能：跟着 ' + nearby);
    follow(nearby);
  }
}

setInterval(instinctTick, 2000);

// pvpbot 专用 PvP 循环：追最近玩家 + 近身平砍
// 注意：所有 lookAt 都必须 force=false，每 tick 只发一个视角包，避免 Grim TickTimer 误判
if (IS_PVPBOT) {
  let lastPvpAttack = 0;
  setInterval(() => {
    if (!botReady || !bot || !bot.entity) return;
    // 模型刚下发过指令就先听模型的
    if (Date.now() - lastCommandAt < 3000) return;
    const target = nearestPlayerName(24);
    if (!target) {
      stopMoving();
      return;
    }
    const e = nearestEntityByName(target);
    if (!e || !e.position) return;
    const d = bot.entity.position.distanceTo(e.position);
    bot.lookAt(e.position.offset(0, 1.5, 0), false);
    if (d > 2.8) {
      bot.setControlState('forward', true);
      bot.setControlState('sprint', true);
    } else {
      bot.setControlState('forward', false);
      bot.setControlState('sprint', false);
      // 剑的攻击冷却 0.6s，间隔短了服务端会按蓄力不足吞伤害
      if (Date.now() - lastPvpAttack > 650) {
        lastPvpAttack = Date.now();
        attack(target);
      }
    }
  }, 500);
}

// ---------------- 启动 --------------------------
connectBridge();
createBot();

process.on('SIGINT', () => {
  console.log('[bridge] 退出');
  try { if (bot) bot.quit(); } catch (e) {}
  try { if (bridge) bridge.end(); } catch (e) {}
  process.exit(0);
});
