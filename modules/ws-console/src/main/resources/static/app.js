// WS Console: 게이트웨이(relay)를 통해 ws-backend / ws-chat의 각 케이스를 눌러 보는 화면(D23).
// 토큰·티켓은 콘솔 서버(/api)가 auth-api로 중계하고, WebSocket은 브라우저가 게이트웨이에 직접 연다.

const $ = (sel, root = document) => root.querySelector(sel);
const el = (tag, attrs = {}, ...children) => {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k === 'class') node.className = v;
    else if (k.startsWith('on')) node.addEventListener(k.slice(2), v);
    else node.setAttribute(k, v);
  }
  for (const c of children) node.append(c);
  return node;
};

// RFC 6455 close 코드와 이 PoC에서의 의미
const CLOSE_MEANING = {
  1000: '정상 종료',
  1001: 'Going Away — drain(서버/게이트웨이 종료)',
  1002: 'Protocol Error — relay에서 백엔드 연결 실패 등(D14)',
  1005: '상태 코드 없음',
  1006: '비정상 종료 — 핸드셰이크 거절(401/404/503)도 여기로 보임. "거절 케이스" 탭 참고',
  1008: 'Policy Violation',
  1011: '서버 내부 오류',
  3000: '토큰 만료(token expired)',
  3008: 'heartbeat 타임아웃',
};

let wsBase = '';

// ---------- API ----------
async function api(path, body) {
  const res = await fetch(`/api${path}`, {
    method: body === undefined ? 'GET' : 'POST',
    headers: { 'content-type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  let json;
  try { json = text ? JSON.parse(text) : {}; } catch { json = { raw: text }; }
  if (!res.ok) throw new Error(`${res.status} ${json.message || json.error || json.raw || ''}`.trim());
  return json;
}

async function issueToken(userId, ttlSeconds) {
  const body = { userId };
  if (ttlSeconds) body.ttlSeconds = Number(ttlSeconds);
  return api('/token', body); // {token, expiresAt}
}

const issueTicket = async (token) => (await api('/ticket', { token })).ticket;

// ---------- 공통 토큰(상단) ----------
const auth = { token: null, userId: null, expiresAt: null };

function renderTokenState() {
  const badge = $('#token-state');
  if (!auth.token) { badge.textContent = '토큰 없음'; badge.className = 'badge'; return; }
  const left = Math.round((new Date(auth.expiresAt) - Date.now()) / 1000);
  if (left <= 0) { badge.textContent = `${auth.userId} 토큰 만료됨`; badge.className = 'badge bad'; return; }
  badge.textContent = `${auth.userId} · ${left}s 남음`;
  badge.className = 'badge ok';
}
setInterval(renderTokenState, 1000);

$('#auth-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  try {
    const userId = $('#user-id').value.trim();
    const res = await issueToken(userId, $('#ttl').value);
    Object.assign(auth, { token: res.token, userId, expiresAt: res.expiresAt });
  } catch (err) {
    Object.assign(auth, { token: null });
    alert(`토큰 발급 실패: ${err.message}`);
  }
  renderTokenState();
});

// 공통 토큰이 없으면 상단 입력값으로 하나 발급한다.
async function ensureToken() {
  if (auth.token && new Date(auth.expiresAt) > Date.now()) return auth.token;
  const userId = $('#user-id').value.trim() || 'alice';
  const res = await issueToken(userId, $('#ttl').value);
  Object.assign(auth, { token: res.token, userId, expiresAt: res.expiresAt });
  renderTokenState();
  return auth.token;
}

// ---------- 연결 카드 ----------
const MAX_LOG_LINES = 300;
const now = () => new Date().toISOString().slice(11, 23);

/** 화면에 보이기 좋게: push의 긴 payload는 길이만 보여 준다. */
function pretty(text) {
  try {
    const obj = JSON.parse(text);
    if (typeof obj.payload === 'string' && obj.payload.length > 24) obj.payload = `<${obj.payload.length} bytes>`;
    return JSON.stringify(obj);
  } catch {
    return text;
  }
}

/**
 * WebSocket 연결 하나를 나타내는 카드. 상태, server 필드, 송수신 로그, close 코드와 의미를 보여 준다.
 * opts.getTicket: 연결할 때마다 새 티켓을 돌려주는 함수(없으면 익명 연결).
 */
class WsCard {
  constructor(parent, { title, path, getTicket, onMessage, onClose, closable = false }) {
    Object.assign(this, { path, getTicket, onMessage, onClose });
    this.ws = null;
    this.status = el('span', { class: 'badge' }, 'closed');
    this.server = el('span', { class: 'badge info', title: '응답을 처리한 서버 인스턴스' }, 'server -');
    this.connectBtn = el('button', { class: 'primary', onclick: () => this.connect() }, '연결');
    this.closeBtn = el('button', { onclick: () => this.close() }, '종료(1000)');
    this.closeBtn.disabled = true;
    this.controls = el('div', { class: 'controls' });
    this.stats = el('div', { class: 'stats' });
    this.closeInfo = el('div', { class: 'close-info' });
    this.log = el('div', { class: 'log' });
    const head = el('div', { class: 'card-head' },
      el('span', { class: 'title' }, title), el('code', {}, path), this.status, this.server, this.connectBtn, this.closeBtn);
    if (closable) head.append(el('button', { title: '카드 제거', onclick: () => { this.close(); this.root.remove(); } }, '✕'));
    this.root = el('div', { class: 'card' }, head, this.controls, this.stats, this.closeInfo, this.log);
    parent.append(this.root);
  }

  setStatus(text, kind = '') {
    this.status.textContent = text;
    this.status.className = `badge ${kind}`;
  }

  line(kind, text) {
    const arrow = { out: '→', in: '←', sys: '•', err: '!' }[kind];
    this.log.append(el('div', { class: 'line' }, el('span', { class: 't' }, now()), el('span', { class: kind }, `${arrow} ${text}`)));
    while (this.log.childElementCount > MAX_LOG_LINES) this.log.firstChild.remove();
    this.log.scrollTop = this.log.scrollHeight;
  }

  get open() { return this.ws && this.ws.readyState === WebSocket.OPEN; }

  async connect() {
    if (this.ws && this.ws.readyState <= WebSocket.OPEN) return;
    this.closeInfo.textContent = '';
    this.setStatus('connecting', 'warn');
    let url = wsBase + this.path;
    try {
      if (this.getTicket) url += `?ticket=${encodeURIComponent(await this.getTicket())}`;
    } catch (err) {
      this.setStatus('closed', 'bad');
      this.line('err', `티켓 발급 실패: ${err.message}`);
      return;
    }
    // 티켓은 로그에 남기지 않는다.
    this.line('sys', `connect ${wsBase}${this.path}${this.getTicket ? '?ticket=***' : ''}`);
    const ws = new WebSocket(url);
    this.ws = ws;
    this.openedAt = null;
    this.connectBtn.disabled = true;
    ws.onopen = () => {
      this.openedAt = Date.now();
      this.setStatus('open', 'ok');
      this.closeBtn.disabled = false;
      this.line('sys', 'open (101)');
    };
    ws.onmessage = (ev) => {
      this.line('in', pretty(ev.data));
      let msg = null;
      try { msg = JSON.parse(ev.data); } catch { /* JSON이 아닌 메시지 */ }
      if (msg && msg.server) this.server.textContent = `server ${msg.server}`;
      if (msg && this.onMessage) this.onMessage(msg);
    };
    ws.onclose = (ev) => {
      const meaning = CLOSE_MEANING[ev.code] || '';
      const lived = this.openedAt ? ` · ${((Date.now() - this.openedAt) / 1000).toFixed(1)}s 동안 연결` : ' · 열리지 못함';
      this.setStatus(`closed ${ev.code}`, ev.code === 1000 ? '' : (ev.code === 1006 ? 'bad' : 'warn'));
      this.closeInfo.textContent = `close ${ev.code}${ev.reason ? ` "${ev.reason}"` : ''} — ${meaning}${lived}`;
      this.line(ev.code === 1006 ? 'err' : 'sys', `close code=${ev.code} reason=${ev.reason || '-'} (${meaning})`);
      this.connectBtn.disabled = false;
      this.closeBtn.disabled = true;
      if (this.onClose) this.onClose(ev);
    };
  }

  send(obj) {
    if (!this.open) { this.line('err', '연결되어 있지 않습니다'); return false; }
    const text = typeof obj === 'string' ? obj : JSON.stringify(obj);
    this.ws.send(text);
    this.line('out', text);
    return true;
  }

  close() { if (this.open) this.ws.close(1000, 'bye'); }
}

const stat = (label) => {
  const value = el('b', {}, '-');
  return { node: el('span', {}, `${label} `, value), set: (v) => { value.textContent = v; } };
};

const input = (attrs) => el('input', attrs);

// ---------- Echo ----------
function setupEcho() {
  const pending = new Map();
  let seq = 0;
  const rtt = stat('RTT'), user = stat('userId'), count = stat('응답');
  let replies = 0;
  const card = new WsCard($('#echo-card'), {
    title: 'Echo', path: '/ws/echo',
    getTicket: async () => issueTicket(await ensureToken()),
    onMessage: (msg) => {
      if (msg.type !== 'echo') return;
      count.set(++replies);
      user.set(msg.userId);
      const sent = pending.get(msg.id);
      if (sent) { rtt.set(`${Date.now() - sent} ms`); pending.delete(msg.id); }
    },
  });
  const payload = input({ value: 'hello', size: 24, placeholder: 'payload' });
  const sendEcho = () => {
    const id = `e-${++seq}`;
    if (card.send({ type: 'echo', id, ts: Date.now(), payload: payload.value })) pending.set(id, Date.now());
  };
  payload.addEventListener('keydown', (e) => { if (e.key === 'Enter') sendEcho(); });
  card.controls.append(
    payload,
    el('button', { onclick: sendEcho }, 'echo 보내기'),
    el('button', { onclick: () => card.send('{not json'), title: '기대: invalid json' }, '잘못된 JSON'),
    el('button', { onclick: () => card.send({ type: 'nope' }), title: '기대: unknown type' }, '모르는 type'),
  );
  card.stats.append(count.node, user.node, rtt.node);
}

// ---------- Push (인증 / 공개) ----------
/** push 수신 통계: 개수, 마지막 seq, 최근 1초 수신 수. */
function pushStats(card) {
  const count = stat('수신'), seqStat = stat('seq'), rate = stat('초당'), topic = stat('topic');
  card.stats.append(count.node, topic.node, seqStat.node, rate.node);
  let total = 0;
  const times = [];
  setInterval(() => {
    const cutoff = Date.now() - 1000;
    while (times.length && times[0] < cutoff) times.shift();
    rate.set(times.length);
  }, 250);
  return (msg) => {
    if (msg.type !== 'push') return;
    count.set(++total);
    seqStat.set(msg.seq);
    topic.set(msg.topic);
    times.push(Date.now());
  };
}

function setupPush() {
  let onPush = () => {};
  const card = new WsCard($('#push-card'), {
    title: 'Push', path: '/ws/push',
    getTicket: async () => issueTicket(await ensureToken()),
    onMessage: (msg) => onPush(msg),
  });
  onPush = pushStats(card);
  const topic = input({ value: 'ticker', size: 10 });
  const interval = input({ type: 'number', value: 500, min: 1, size: 6, title: 'intervalMs (서버 최소값으로 보정됨)' });
  const bytes = input({ type: 'number', value: 64, min: 0, size: 6, title: 'payloadBytes' });
  card.controls.append(
    'topic', topic, 'intervalMs', interval, 'payloadBytes', bytes,
    el('button', {
      onclick: () => card.send({ type: 'subscribe', topic: topic.value, intervalMs: Number(interval.value), payloadBytes: Number(bytes.value) }),
    }, 'subscribe'),
    el('button', { onclick: () => card.send({ type: 'unsubscribe' }) }, 'unsubscribe'),
  );
}

function setupPublic() {
  let onPush = () => {};
  let lastError = null;
  const card = new WsCard($('#public-card'), {
    title: '공개 Push (인증 없음)', path: '/ws/public/push',
    onMessage: (msg) => {
      onPush(msg);
      if (msg.type === 'error') lastError.set(msg.reason);
    },
  });
  onPush = pushStats(card);
  lastError = stat('오류');
  card.stats.append(lastError.node);
  card.controls.append(
    el('button', { onclick: () => card.send({ type: 'subscribe', intervalMs: 10 }), title: '기대: read only' }, 'subscribe 보내기(거절되어야 함)'),
  );
}

// ---------- Chat ----------
const NAMES = ['alice', 'bob', 'carol', 'dave', 'erin', 'frank'];
let chatCount = 0;

function addChatParticipant(userId) {
  // 참가자마다 자기 access token을 받는다(연결할 때마다 티켓은 새로).
  let token = null;
  const joined = new Set();
  const rooms = stat('참여 방'), members = stat('members'), received = stat('받은 메시지');
  let got = 0;
  const card = new WsCard($('#chat-cards'), {
    title: `Chat · ${userId}`, path: '/ws/chat', closable: true,
    getTicket: async () => {
      if (!token || new Date(token.expiresAt) <= Date.now()) token = await issueToken(userId);
      return issueTicket(token.token);
    },
    onMessage: (msg) => {
      if (msg.type === 'joined') { joined.add(msg.room); members.set(`${msg.room}: ${msg.members}`); }
      if (msg.type === 'left') joined.delete(msg.room);
      if (msg.type === 'message') received.set(++got);
      rooms.set([...joined].join(', ') || '-');
    },
    onClose: () => { joined.clear(); rooms.set('-'); },
  });
  card.stats.append(rooms.node, members.node, received.node);
  const room = input({ value: 'lobby', size: 8, title: 'room' });
  const text = input({ value: `hi from ${userId}`, size: 16 });
  const say = () => card.send({ type: 'say', room: room.value, text: text.value });
  text.addEventListener('keydown', (e) => { if (e.key === 'Enter') say(); });
  card.controls.append(
    'room', room,
    el('button', { onclick: () => card.send({ type: 'join', room: room.value }) }, 'join'),
    el('button', { onclick: () => card.send({ type: 'leave', room: room.value }) }, 'leave'),
    text, el('button', { onclick: say }, 'say'),
    el('button', { onclick: () => card.send({ type: 'join' }), title: '기대: room required' }, 'room 없이 join'),
    el('button', { onclick: () => card.send({ type: 'say', room: `${room.value}-x`, text: 'x' }), title: '기대: not joined' }, '안 들어간 방에 say'),
  );
  card.connect();
}

function setupChat() {
  $('#chat-add').addEventListener('submit', (e) => {
    e.preventDefault();
    const field = $('#chat-user');
    addChatParticipant(field.value.trim() || `user${chatCount}`);
    chatCount += 1;
    field.value = NAMES[chatCount % NAMES.length];
  });
}

// ---------- 토큰 만료 ----------
function setupExpiry() {
  const state = $('#expiry-state');
  let expiresAt = null;
  let timer = null;
  const setState = (text, kind = '') => { state.textContent = text; state.className = `badge ${kind}`; };
  let pendingTicket = null;
  const card = new WsCard($('#expiry-card'), {
    title: '만료 테스트 (echo)', path: '/ws/echo',
    getTicket: async () => {
      const t = pendingTicket;
      pendingTicket = null;
      if (!t) throw new Error('위의 "짧은 토큰으로 연결" 버튼을 사용하세요');
      return t;
    },
    onClose: (ev) => {
      clearInterval(timer);
      const drift = expiresAt ? ((Date.now() - expiresAt) / 1000).toFixed(1) : '?';
      if (ev.code === 3000) setState(`✓ 3000 token expired (만료 시각 대비 ${drift}s)`, 'ok');
      else setState(`✗ 기대 3000, 실제 ${ev.code}`, 'bad');
    },
  });
  card.connectBtn.hidden = true;
  $('#expiry-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    card.close();
    clearInterval(timer);
    try {
      const res = await issueToken($('#expiry-user').value.trim(), $('#expiry-ttl').value);
      expiresAt = new Date(res.expiresAt).getTime();
      pendingTicket = await issueTicket(res.token);
    } catch (err) {
      setState(`발급 실패: ${err.message}`, 'bad');
      return;
    }
    await card.connect();
    timer = setInterval(() => {
      const left = Math.max(0, (expiresAt - Date.now()) / 1000);
      setState(`만료까지 ${left.toFixed(1)}s`, 'warn');
    }, 100);
  });
}

// ---------- 거절 케이스(probe) ----------
const PROBES = [
  { name: 'echo 티켓', path: '/ws/echo', credential: 'ticket', expect: [101] },
  { name: 'echo Bearer 헤더(브라우저는 불가)', path: '/ws/echo', credential: 'bearer', expect: [101] },
  { name: 'echo 자격증명 없음', path: '/ws/echo', credential: 'none', expect: [401] },
  { name: 'push 자격증명 없음', path: '/ws/push', credential: 'none', expect: [401] },
  { name: 'chat 티켓', path: '/ws/chat', credential: 'ticket', expect: [101] },
  { name: 'chat 자격증명 없음', path: '/ws/chat', credential: 'none', expect: [401] },
  { name: '티켓 재사용(1회용)', path: '/ws/echo', credential: 'replay', expect: [101, 401] },
  { name: '위조 X-User-Id 헤더만', path: '/ws/echo', credential: 'spoof', expect: [401] },
  { name: '라우트 없는 경로', path: '/ws/unknown', credential: 'none', expect: [404] },
  { name: '공개 push 자격증명 없음', path: '/ws/public/push', credential: 'none', expect: [101] },
];

function setupProbe() {
  const rows = PROBES.map((p) => {
    const result = el('td', {}, '-');
    const run = async () => {
      result.textContent = '실행 중…';
      result.className = '';
      try {
        const res = await api('/probe', { path: p.path, credential: p.credential, userId: 'console-probe' });
        const statuses = res.results.map((r) => r.status);
        const ok = statuses.length === p.expect.length && statuses.every((s, i) => s === p.expect[i]);
        result.textContent = `${ok ? '✓' : '✗'} ${res.results.map((r) => `${r.label}: ${r.status ?? '-'} ${r.detail}`).join(' / ')}`;
        result.style.color = ok ? 'var(--ok)' : 'var(--bad)';
        return ok;
      } catch (err) {
        result.textContent = `✗ ${err.message}`;
        result.style.color = 'var(--bad)';
        return false;
      }
    };
    $('#probe-rows').append(el('tr', {},
      el('td', {}, p.name), el('td', {}, el('code', {}, p.path)), el('td', {}, p.credential),
      el('td', {}, p.expect.join(' → ')), result, el('td', {}, el('button', { onclick: run }, '실행'))));
    return run;
  });
  $('#probe-all').addEventListener('click', async () => {
    const summary = $('#probe-summary');
    summary.textContent = '실행 중…';
    summary.className = 'badge warn';
    let passed = 0;
    for (const run of rows) if (await run()) passed += 1; // 순서대로(티켓 재사용 등 서로 간섭하지 않게)
    summary.textContent = `${passed}/${rows.length} 통과`;
    summary.className = `badge ${passed === rows.length ? 'ok' : 'bad'}`;
  });
}

// ---------- 탭 ----------
$('#tabs').addEventListener('click', (e) => {
  const tab = e.target.dataset && e.target.dataset.tab;
  if (!tab) return;
  for (const b of $('#tabs').children) b.classList.toggle('active', b === e.target);
  for (const s of document.querySelectorAll('.tab')) s.classList.toggle('active', s.id === `tab-${tab}`);
  try { localStorage.setItem('ws-console-tab', tab); } catch { /* 저장 불가 환경 */ }
});

async function main() {
  wsBase = (await api('/config')).wsBase;
  $('#ws-base').textContent = wsBase;
  setupEcho();
  setupPush();
  setupPublic();
  setupChat();
  setupExpiry();
  setupProbe();
  let saved = null;
  try { saved = localStorage.getItem('ws-console-tab'); } catch { /* 저장 불가 환경 */ }
  if (saved) $(`#tabs [data-tab="${saved}"]`)?.click();
}

main().catch((err) => alert(`초기화 실패: ${err.message}`));
