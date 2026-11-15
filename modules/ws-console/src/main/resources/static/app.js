// WS Console: 게이트웨이(relay)를 통해 ws-backend / ws-chat의 각 시나리오를 눌러 보는 화면(D23, D25).
// 토큰·티켓은 콘솔 서버(/api)가 auth-api로 중계하고, WebSocket은 브라우저가 게이트웨이에 직접 연다.
//
// 시나리오마다 "화면에 보이는 것"과 "오간 프레임"을 같은 타임라인에 쌓는다(core.js). 여기서는 시나리오별로
// 프레임을 화면 표현(말풍선, 틱, 안내 줄)으로 바꾸는 규칙과 입력줄, 데모만 정의한다.

import {
  $, $$, el, sleep, store, config, api, toast, clock, shortClock,
  issueToken, issueTicket, makeSession, statTiles, notice, bubble, codeBlock, handshakeCurl,
} from './core.js';
import { setupMonitor } from './monitor.js';

// ---------- 탭 상태 점 ----------
function setTabState(tab, state) {
  const dot = $(`#tabs [data-tab="${tab}"] .state`);
  dot.className = `state ${state ?? ''}`;
}

/** 연결 상태를 사이드바 점으로 옮긴다. */
const tabStateOf = (tab) => (state, info) => {
  if (state === 'closed') setTabState(tab, info?.code === 1000 ? '' : (info?.opened ? 'warn' : 'bad'));
  else setTabState(tab, state === 'idle' ? '' : state);
};

// ---------- 공통 토큰(상단) ----------
const auth = { token: null, userId: null, expiresAt: null };

function renderTokenState() {
  const pill = $('#token-state');
  const label = pill.lastElementChild;
  if (!auth.token) {
    pill.className = 'pill idle';
    label.textContent = '토큰 없음';
    return;
  }
  const left = Math.round((new Date(auth.expiresAt) - Date.now()) / 1000);
  if (left <= 0) {
    pill.className = 'pill closed bad';
    label.textContent = `${auth.userId} · 만료됨`;
    return;
  }
  pill.className = 'pill open';
  label.textContent = `${auth.userId} · ${left >= 120 ? `${Math.round(left / 60)}분` : `${left}초`} 남음`;
}
setInterval(renderTokenState, 1000);

async function refreshSharedToken(trace) {
  const userId = $('#user-id').value.trim() || 'alice';
  const res = await issueToken(userId, $('#ttl').value, trace);
  Object.assign(auth, { token: res.token, userId, expiresAt: res.expiresAt, request: res.request });
  renderTokenState();
  return auth.token;
}

$('#auth-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  try {
    await refreshSharedToken();
    toast(`${auth.userId} 토큰을 발급했습니다`, 'ok');
  } catch (err) {
    auth.token = null;
    renderTokenState();
    toast(`토큰 발급 실패: ${err.message}`);
  }
});

/** 상단 토큰으로 티켓을 받는다. 토큰이 없거나 만료됐으면 상단 입력값으로 새로 발급한다. */
async function sharedTicket(trace) {
  const valid = auth.token && new Date(auth.expiresAt) > Date.now();
  const token = valid ? auth.token : await refreshSharedToken(trace);
  return issueTicket(token, trace, auth.request);
}

// ---------- 입력 도우미 ----------
const field = (attrs) => el('input', { autocomplete: 'off', ...attrs });
const onEnter = (input, action) => input.addEventListener('keydown', (e) => {
  if (e.key === 'Enter' && !e.isComposing) action();
});
const button = (label, onclick, attrs = {}) => el('button', { type: 'button', class: 'btn', onclick, ...attrs }, label);

// ---------- Echo ----------
/** echo 입력줄과 응답 표시. Echo 탭과 토큰 만료 탭이 함께 쓴다. */
function echoBehaviour() {
  const pending = new Map(); // id -> 보낸 시각
  const errorLinks = []; // 오류 응답은 보낸 순서대로 온다
  let seq = 0;
  let replies = 0;
  let rttSum = 0;
  const tiles = statTiles(['응답', 'userId', '마지막 RTT', '평균 RTT']);

  const onMessage = (msg) => {
    if (msg.type === 'echo') {
      const sentAt = pending.get(msg.id);
      pending.delete(msg.id);
      const rtt = sentAt == null ? null : Math.round(performance.now() - sentAt);
      replies += 1;
      tiles.set('응답', replies);
      tiles.set('userId', msg.userId);
      if (rtt != null) {
        rttSum += rtt;
        tiles.set('마지막 RTT', `${rtt} ms`);
        tiles.set('평균 RTT', `${(rttSum / replies).toFixed(1)} ms`);
      }
      return {
        view: bubble({
          side: 'them',
          name: (msg.server ?? 'server').split('@')[0],
          text: String(msg.payload ?? ''),
          meta: `${rtt == null ? '' : `왕복 ${rtt}ms · `}userId ${msg.userId}`,
        }),
        link: msg.id,
      };
    }
    if (msg.type === 'error') {
      return { view: notice('bad', '서버가 메시지를 받아들이지 않았습니다', msg.reason), link: errorLinks.shift(), tone: 'bad' };
    }
    return null;
  };

  const attach = (session) => {
    const { conn, composer } = session;
    const text = field({ value: '안녕하세요', placeholder: '보낼 내용', class: 'grow' });
    const sendEcho = (value = text.value) => {
      const id = `e-${++seq}`;
      const view = bubble({ side: 'me', text: value, meta: shortClock() });
      if (conn.send({ type: 'echo', id, ts: Date.now(), payload: value }, { view, link: id })) pending.set(id, performance.now());
    };
    const sendBroken = (raw, tag) => {
      const link = `x-${++seq}`;
      if (conn.send(raw, { view: bubble({ side: 'me', text: typeof raw === 'string' ? raw : JSON.stringify(raw), mono: true, tag }), link })) {
        errorLinks.push(link);
      }
    };
    onEnter(text, () => sendEcho());
    composer.append(
      text,
      button('보내기', () => sendEcho(), { class: 'btn primary' }),
      button('잘못된 JSON', () => sendBroken('{not json', '잘못된 JSON'), { class: 'btn ghost', title: '기대: invalid json 오류, 연결 유지' }),
      button('모르는 type', () => sendBroken({ type: 'nope' }, '모르는 type'), { class: 'btn ghost', title: '기대: unknown type 오류, 연결 유지' }),
    );
    return { sendEcho, sendBroken };
  };

  return { tiles, onMessage, attach };
}

let echoScene;
function setupEcho() {
  const behaviour = echoBehaviour();
  const session = makeSession({
    parent: $('#echo-stage'),
    title: 'Echo',
    path: '/ws/echo',
    avatarOf: { name: 'echo', glyph: '↺' },
    credentials: sharedTicket,
    hooks: { onMessage: behaviour.onMessage },
    onState: tabStateOf('echo'),
  });
  session.widget.append(behaviour.tiles.node);
  session.widget.hidden = false;
  echoScene = { session, ...behaviour.attach(session) };
}

// ---------- Push (인증 / 공개) ----------
const SPARK_BARS = 40;

/** push 실시간 위젯: 토픽, seq, 초당 수신, 도착 간격 막대. */
function ticker() {
  const topic = el('span', { class: 'ticker-topic' }, '–');
  const seq = el('span', { class: 'ticker-seq' }, '#–');
  const tiles = statTiles(['수신', '초당', '간격', 'payload']);
  const bars = Array.from({ length: SPARK_BARS }, () => el('i'));
  const node = el('div', { class: 'ticker' },
    el('div', { class: 'ticker-main' }, el('span', { class: 'ticker-live' }, el('i'), 'LIVE'), topic, seq),
    tiles.node,
    el('div', { class: 'spark', title: '최근 도착 간격(막대가 높을수록 간격이 김)' }, bars));
  const gaps = [];
  const times = [];
  let total = 0;
  let lastAt = null;
  let lastSeq = null;
  setInterval(() => {
    const cutoff = Date.now() - 1000;
    while (times.length && times[0] < cutoff) times.shift();
    tiles.set('초당', times.length);
    node.classList.toggle('live', times.length > 0);
  }, 250);
  return {
    node,
    /** push 한 건을 반영하고, 타임라인에 넣을 화면 줄을 돌려준다. */
    onPush(msg) {
      const at = Date.now();
      const gap = lastAt == null ? null : at - lastAt;
      // 구독이 바뀌면 seq가 0부터 다시 시작한다. 그 외에 건너뛰면 서버가 버린 것이다(느린 소비자).
      const skipped = lastSeq != null && msg.seq > lastSeq + 1 ? msg.seq - lastSeq - 1 : 0;
      lastAt = at;
      lastSeq = msg.seq;
      total += 1;
      times.push(at);
      topic.textContent = msg.topic;
      seq.textContent = `#${msg.seq}`;
      tiles.set('수신', total);
      tiles.set('payload', `${msg.payload.length}B`);
      if (gap != null) {
        tiles.set('간격', `${gap} ms`);
        gaps.push(gap);
        if (gaps.length > SPARK_BARS) gaps.shift();
        const max = Math.max(...gaps, 1);
        bars.forEach((bar, i) => {
          const value = gaps[i - (SPARK_BARS - gaps.length)];
          bar.style.height = value == null ? '2px' : `${Math.max(8, Math.round((value / max) * 100))}%`;
        });
      }
      return el('div', { class: `tick${skipped ? ' skipped' : ''}` },
        el('i'), el('b', {}, `#${msg.seq}`), el('span', {}, msg.topic), el('span', {}, `${msg.payload.length}B`),
        gap != null && el('span', { class: 'dim' }, `+${gap}ms`),
        skipped > 0 && el('span', { class: 'warn' }, `${skipped}건 건너뜀`));
    },
    reset() {
      lastAt = null;
      lastSeq = null;
    },
  };
}

let pushScene;
function setupPush() {
  const live = ticker();
  const session = makeSession({
    parent: $('#push-stage'),
    title: 'Push',
    path: '/ws/push',
    avatarOf: { name: 'push', glyph: '⚡' },
    credentials: sharedTicket,
    hooks: {
      onOpen: () => notice('ok', '연결되었습니다', '기본 토픽 구독이 바로 시작됩니다'),
      onMessage: (msg) => (msg.type === 'push' ? { view: live.onPush(msg) } : null),
    },
    onState: tabStateOf('push'),
  });
  session.widget.append(live.node);
  session.widget.hidden = false;
  const topic = field({ value: 'ticker', size: 9, 'aria-label': 'topic' });
  const interval = field({ type: 'number', value: 500, min: 1, size: 6, 'aria-label': 'intervalMs', title: '서버가 최소·최대값으로 보정합니다' });
  const bytes = field({ type: 'number', value: 64, min: 0, size: 6, 'aria-label': 'payloadBytes' });
  const subscribe = (t = topic.value, ms = Number(interval.value), size = Number(bytes.value)) => {
    live.reset();
    session.conn.send({ type: 'subscribe', topic: t, intervalMs: ms, payloadBytes: size },
      { view: notice('info', '구독을 바꿉니다', `${t} · ${ms}ms마다 · ${size}B`) });
  };
  const unsubscribe = () => session.conn.send({ type: 'unsubscribe' }, { view: notice('idle', '구독을 멈춥니다') });
  session.composer.append(
    el('label', {}, 'topic', topic), el('label', {}, '주기(ms)', interval), el('label', {}, '크기(B)', bytes),
    button('subscribe', () => subscribe(), { class: 'btn primary' }),
    button('unsubscribe', unsubscribe),
  );
  pushScene = { session, subscribe, unsubscribe };
}

let publicScene;
function setupPublic() {
  const live = ticker();
  const session = makeSession({
    parent: $('#public-stage'),
    title: '공개 Push',
    path: '/ws/public/push',
    avatarOf: { name: 'public', glyph: '◎' },
    hooks: {
      onOpen: () => notice('ok', '로그인 없이 연결되었습니다', '익명 세션 · 토큰 만료 없음'),
      onMessage: (msg) => {
        if (msg.type === 'push') return { view: live.onPush(msg) };
        if (msg.type === 'error') return { view: notice('bad', '읽기 전용 엔드포인트입니다', msg.reason), link: 'ro', tone: 'bad' };
        return null;
      },
    },
    onState: tabStateOf('public'),
  });
  session.widget.append(live.node);
  session.widget.hidden = false;
  const trySubscribe = () => session.conn.send({ type: 'subscribe', intervalMs: 10 },
    { view: notice('info', '주기를 10ms로 바꿔 달라고 요청합니다', '거절되어야 합니다'), link: 'ro' });
  session.composer.append(
    el('span', { class: 'composer-note' }, '익명 사용자가 주기나 크기를 키워 부하를 만들 수 없게, 보내는 메시지는 모두 거절됩니다.'),
    button('subscribe 보내 보기', trySubscribe),
  );
  publicScene = { session, trySubscribe };
}

// ---------- Chat ----------
const NAMES = ['alice', 'bob', 'carol', 'dave', 'erin', 'frank'];
const participants = new Map();

function refreshChatTabState() {
  const open = [...participants.values()].filter((p) => p.session.conn.open).length;
  setTabState('chat', open > 0 ? 'open' : '');
  $('#chat-empty').hidden = participants.size > 0;
}

function addParticipant(userId) {
  if (participants.has(userId)) return participants.get(userId);
  // 참가자마다 자기 access token을 받는다(연결할 때마다 티켓은 새로).
  let token = null;
  const joined = new Set();
  let active = null;
  // 클라이언트가 보낸 요청은 순서대로 응답 하나씩을 받는다(joined / left / 내 message / error).
  const ops = [];
  let lastSpeaker = null;
  let opSeq = 0;
  const roomChips = el('div', { class: 'rooms' });

  const paintRooms = () => {
    roomChips.replaceChildren(
      ...[...joined].map((room) => el('span', { class: `room${room === active ? ' active' : ''}` },
        el('button', { type: 'button', title: '이 방으로 말하기', onclick: () => { active = room; paintRooms(); } }, `#${room}`),
        el('button', { type: 'button', class: 'x', title: '방 나가기', onclick: () => leave(room) }, '×'))),
      ...(joined.size === 0 ? [el('span', { class: 'dim' }, '들어간 방이 없습니다')] : []),
    );
  };

  const session = makeSession({
    parent: $('#chat-stage'),
    title: userId,
    path: '/ws/chat',
    avatarOf: { name: userId },
    emptyText: '연결 중입니다…',
    credentials: async (trace) => {
      if (!token || new Date(token.expiresAt) <= Date.now()) token = await issueToken(userId, null, trace);
      return issueTicket(token.token, trace, token.request);
    },
    hooks: {
      onOpen: () => notice('ok', `${userId} 님이 접속했습니다`),
      onMessage: (msg) => {
        if (msg.type === 'joined') {
          joined.add(msg.room);
          active = msg.room;
          paintRooms();
          return { view: notice('ok', `#${msg.room} 방에 들어왔습니다`, `지금 ${msg.members}명`), link: ops.shift()?.link };
        }
        if (msg.type === 'left') {
          joined.delete(msg.room);
          if (active === msg.room) active = [...joined][0] ?? null;
          paintRooms();
          return { view: notice('idle', `#${msg.room} 방에서 나왔습니다`), link: ops.shift()?.link };
        }
        if (msg.type === 'error') {
          const op = ops.shift();
          op?.bubble?.classList.add('failed');
          op?.bubble?.setMeta('전송 실패', 'bad');
          return { view: notice('bad', '요청이 거절되었습니다', msg.reason), link: op?.link, tone: 'bad' };
        }
        if (msg.type !== 'message') return null;
        const speaker = `${msg.room}:${msg.from}`;
        const grouped = speaker === lastSpeaker;
        lastSpeaker = speaker;
        const time = shortClock(msg.srvTs);
        if (msg.from === userId) {
          // 내가 보낸 말이 방 전체 broadcast로 되돌아온 것: 이미 그린 말풍선을 "전달됨"으로 바꾼다.
          const op = ops.shift();
          if (op?.bubble) {
            op.bubble.setMeta(`${time} · 전달됨 ✓`, 'ok');
            return { link: op.link };
          }
          return { view: bubble({ side: 'me', text: msg.text, tag: `#${msg.room}`, meta: time }) };
        }
        return { view: bubble({ side: 'them', name: msg.from, text: msg.text, tag: joined.size > 1 ? `#${msg.room}` : null, meta: time, grouped }) };
      },
      onClose: () => {
        joined.clear();
        active = null;
        ops.length = 0;
        lastSpeaker = null;
        paintRooms();
        return null;
      },
    },
    onState: refreshChatTabState,
    onRemove: () => {
      participants.delete(userId);
      refreshChatTabState();
    },
  });

  const request = (payload, view, bubbleNode) => {
    const link = `op-${++opSeq}`;
    if (session.conn.send(payload, { view, link })) ops.push({ link, bubble: bubbleNode });
  };
  const join = (room) => request({ type: 'join', room }, notice('info', `#${room} 방에 들어갑니다`));
  function leave(room) { request({ type: 'leave', room }, notice('idle', `#${room} 방에서 나갑니다`)); }
  const say = (text, room = active ?? roomInput.value.trim()) => {
    if (!text) return;
    const view = bubble({ side: 'me', text, tag: joined.size > 1 ? `#${room}` : null, meta: '보내는 중…' });
    lastSpeaker = `${room}:${userId}`;
    request({ type: 'say', room, text }, view, view);
  };

  const roomInput = field({ value: 'lobby', size: 7, 'aria-label': '방 이름', placeholder: '방 이름' });
  const text = field({ placeholder: '메시지를 입력하세요', class: 'grow', 'aria-label': '메시지' });
  const sendTyped = () => {
    say(text.value.trim());
    text.value = '';
  };
  onEnter(text, sendTyped);
  onEnter(roomInput, () => join(roomInput.value.trim()));
  session.composer.classList.add('chat');
  session.composer.append(
    el('div', { class: 'composer-row' },
      roomChips,
      roomInput, button('입장', () => join(roomInput.value.trim())),
      el('details', { class: 'more' }, el('summary', { title: '오류 케이스' }, '⋯'),
        el('div', { class: 'more-menu' },
          button('room 없이 join', () => request({ type: 'join' }, notice('info', '방 이름 없이 입장을 요청합니다')), { title: '기대: room required' }),
          button('안 들어간 방에 say', () => {
            const view = bubble({ side: 'me', text: '여기 누구 있나요?', tag: '#nowhere', meta: '보내는 중…' });
            request({ type: 'say', room: 'nowhere', text: '여기 누구 있나요?' }, view, view);
          }, { title: '기대: not joined' })))),
    el('div', { class: 'composer-row' }, text, button('보내기', sendTyped, { class: 'btn primary' })),
  );
  paintRooms();
  const participant = { userId, session, joined, join, leave, say };
  participants.set(userId, participant);
  refreshChatTabState();
  session.conn.connect();
  return participant;
}

function setupChat() {
  $('#chat-add').addEventListener('submit', (e) => {
    e.preventDefault();
    const input = $('#chat-user');
    const name = input.value.trim();
    if (!name) return;
    if (participants.has(name)) toast(`${name}은(는) 이미 참가 중입니다`, 'warn');
    addParticipant(name);
    input.value = NAMES.find((n) => !participants.has(n)) ?? `user${participants.size + 1}`;
  });
}

// 데모 대화: 실제로 WebSocket으로 오간다. 뒤쪽은 방 격리(dev)와 퇴장 뒤 미수신(alice)을 보여 준다.
const CHAT_SCRIPT = [
  { who: 'alice', say: '다들 점심 뭐 먹을 거야?' },
  { who: 'bob', say: '나는 국밥 생각하고 있었는데 ㅎㅎ' },
  { who: 'carol', say: '어제도 국밥 먹지 않았어? 😂' },
  { who: 'bob', say: '그건 순대국이고, 오늘은 돼지국밥이지' },
  { who: 'alice', say: '좋아, 그럼 12시 10분에 1층 로비에서 보자' },
  { who: 'carol', say: '나 회의 끝나고 바로 내려갈게!' },
  { who: 'carol', join: 'dev', wait: 900 },
  { who: 'carol', say: '아 그리고 배포는 3시에 시작할게요', room: 'dev' },
  { who: 'alice', leave: 'lobby', wait: 1100 },
  { who: 'bob', say: 'alice는 먼저 나갔네. 자리 맡아 둘게 🙌', room: 'lobby' },
];

async function chatDemo() {
  const cast = ['alice', 'bob', 'carol'].map(addParticipant);
  for (const p of cast) {
    if (!p.session.conn.open) p.session.conn.connect();
    if (!(await p.session.conn.ready())) throw new Error(`${p.userId} 연결 실패`);
  }
  for (const p of cast) {
    if (!p.joined.has('lobby')) p.join('lobby');
    await sleep(350);
  }
  for (const step of CHAT_SCRIPT) {
    const p = participants.get(step.who);
    if (!p?.session.conn.open) return;
    // 읽고 치는 시간처럼 보이게: 글이 길수록 조금 더 기다린다.
    await sleep(step.wait ?? 700 + Math.min(1400, (step.say?.length ?? 0) * 45));
    if (step.join) p.join(step.join);
    else if (step.leave) p.leave(step.leave);
    else p.say(step.say, step.room ?? 'lobby');
  }
}

// ---------- 토큰 만료 ----------
const RING = 2 * Math.PI * 26;
let expiryScene;

function setupExpiry() {
  const behaviour = echoBehaviour();
  let expiresAt = null;
  let ttlMs = 0;
  let timer = null;
  const arc = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('viewBox', '0 0 64 64');
  const track = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
  for (const c of [track, arc]) {
    c.setAttribute('cx', '32');
    c.setAttribute('cy', '32');
    c.setAttribute('r', '26');
    svg.append(c);
  }
  track.setAttribute('class', 'track');
  arc.setAttribute('class', 'arc');
  arc.setAttribute('stroke-dasharray', String(RING));
  const seconds = el('b', {}, '–');
  const headline = el('b', {}, '아직 연결 전');
  const detail = el('span', {}, '위에서 TTL을 정하고 연결하면 만료 시각까지 세어 내려갑니다.');
  const countdown = el('div', { class: 'countdown' },
    el('div', { class: 'ring' }, svg, seconds), el('div', { class: 'countdown-text' }, headline, detail));

  const paint = () => {
    const left = Math.max(0, expiresAt - Date.now());
    seconds.textContent = (left / 1000).toFixed(left < 10_000 ? 1 : 0);
    arc.setAttribute('stroke-dashoffset', String(RING * (1 - Math.min(1, left / ttlMs))));
  };
  const setResult = (tone, title, text) => {
    countdown.className = `countdown ${tone}`;
    headline.textContent = title;
    detail.textContent = text;
  };

  const session = makeSession({
    parent: $('#expiry-stage'),
    title: '만료 테스트',
    path: '/ws/echo',
    avatarOf: { name: 'expiry', glyph: '⏳' },
    emptyText: '짧은 토큰으로 연결하면, 만료 시각에 서버가 보내는 close 3000을 여기서 볼 수 있습니다.',
    credentials: async (trace) => {
      const res = await issueToken($('#expiry-user').value.trim() || 'expiry-user', $('#expiry-ttl').value, trace);
      expiresAt = new Date(res.expiresAt).getTime();
      ttlMs = Math.max(1, expiresAt - Date.now());
      // 세션 수명은 티켓(30초)이 아니라 이 access token의 만료 시각을 따른다(티켓의 sexp 클레임).
      return issueTicket(res.token, trace, res.request);
    },
    hooks: {
      onOpen: () => {
        clearInterval(timer);
        timer = setInterval(paint, 100);
        paint();
        setResult('running', '세션이 열렸습니다', `access token 만료 ${clock(expiresAt).slice(0, 8)} · 그때 서버가 3000으로 닫아야 합니다`);
        return notice('ok', '로그인한 세션이 열렸습니다', `토큰 만료 ${clock(expiresAt).slice(0, 8)}`);
      },
      onMessage: behaviour.onMessage,
      onClose: (info) => {
        clearInterval(timer);
        if (!info.opened) {
          setResult('bad', '연결하지 못했습니다', '게이트웨이와 auth-api가 떠 있는지 확인하세요');
          return null;
        }
        const drift = ((Date.now() - expiresAt) / 1000).toFixed(1);
        if (info.code === 3000) {
          paint();
          setResult('ok', '3000 token expired', `기대한 대로 닫혔습니다 · 만료 시각 대비 ${drift >= 0 ? '+' : ''}${drift}초`);
          return notice('warn', '세션이 만료되었습니다. 다시 로그인해 주세요', `close 3000 "${info.reason}"`);
        }
        setResult(info.byClient ? 'idle' : 'bad', info.byClient ? '직접 종료했습니다' : `기대 3000, 실제 ${info.code}`, info.meaning);
        return null;
      },
    },
    onState: tabStateOf('expiry'),
  });
  session.widget.append(countdown, behaviour.tiles.node);
  session.widget.hidden = false;
  const echo = behaviour.attach(session);
  const start = async () => {
    session.conn.close();
    await sleep(session.conn.state === 'open' ? 150 : 0);
    await session.conn.connect();
  };
  $('#expiry-form').addEventListener('submit', (e) => {
    e.preventDefault();
    start();
  });
  expiryScene = { session, start, ...echo };
}

// ---------- 거절 케이스(probe) ----------
const CREDENTIAL = {
  none: '자격증명 없음',
  ticket: '?ticket= (1회용)',
  bearer: 'Authorization: Bearer',
  spoof: '위조한 X-User-Id 헤더만',
  replay: '같은 티켓으로 두 번',
};

const PROBE_NOTES = {
  none: '자격증명 없이 업그레이드를 요청합니다. 인증 라우트는 업그레이드 전에 401, 라우트가 없으면 인증보다 먼저 404입니다.',
  ticket: '브라우저와 같은 방식입니다. 토큰으로 1회용 티켓을 받아 query로 보냅니다(jq 필요). 101이면 curl이 --max-time까지 열려 있다가 끝납니다.',
  bearer: 'Authorization 헤더로 access token을 직접 보냅니다. 브라우저 WebSocket은 이 헤더를 붙일 수 없어 서버 간 호출에만 씁니다(jq 필요).',
  spoof: '자격증명 없이 게이트웨이가 넣어 주는 신원 헤더를 흉내 냅니다. 게이트웨이가 이 헤더를 항상 지우므로 401이어야 합니다.',
  replay: '같은 티켓으로 두 번 연결합니다. 티켓은 1회용이라 첫 번째만 101, 두 번째는 401입니다(jq 필요).',
};

const PROBES = [
  { name: 'echo · 티켓', why: '브라우저와 같은 방식의 정상 연결', path: '/ws/echo', credential: 'ticket', expect: [101] },
  { name: 'echo · Bearer 헤더', why: '브라우저는 못 붙이는 헤더. 서버 간 호출용', path: '/ws/echo', credential: 'bearer', expect: [101] },
  { name: 'echo · 자격증명 없음', why: '업그레이드 전에 401로 거절', path: '/ws/echo', credential: 'none', expect: [401] },
  { name: 'push · 자격증명 없음', why: '같은 라우트의 다른 경로도 인증', path: '/ws/push', credential: 'none', expect: [401] },
  { name: 'chat · 티켓', why: '경로로 ws-chat에 라우팅', path: '/ws/chat', credential: 'ticket', expect: [101] },
  { name: 'chat · 자격증명 없음', why: 'ws-chat 라우트도 WsAuth', path: '/ws/chat', credential: 'none', expect: [401] },
  { name: '티켓 재사용', why: '티켓은 1회용: 두 번째는 401', path: '/ws/echo', credential: 'replay', expect: [101, 401] },
  { name: '위조 신원 헤더', why: '게이트웨이가 클라이언트의 X-User-Id를 지운다', path: '/ws/echo', credential: 'spoof', expect: [401] },
  { name: '라우트 없는 경로', why: '인증 전에 404', path: '/ws/unknown', credential: 'none', expect: [404] },
  { name: '서비스에 없는 하위 경로', why: '라우트는 실제 엔드포인트 경로만(D24)', path: '/ws/chat/room1', credential: 'ticket', expect: [404] },
  { name: '공개 push', why: '익명 라우트는 자격증명 없이 101', path: '/ws/public/push', credential: 'none', expect: [101] },
];

function setupProbe() {
  const statusPill = (status, detail) => el('span', { class: `status s${String(status ?? 0)[0]}` }, status == null ? '응답 없음' : `${status}`, detail && el('small', {}, detail));
  const runs = PROBES.map((p) => {
    const seen = el('div', { class: 'probe-value' }, el('span', { class: 'dim' }, '–'));
    const actual = el('div', { class: 'probe-value' }, el('span', { class: 'dim' }, '–'));
    const verdict = el('span', { class: 'verdict' });
    const card = el('article', { class: 'probe-card' },
      el('div', { class: 'probe-title' }, verdict, el('div', {}, el('b', {}, p.name), el('small', {}, p.why))),
      el('div', { class: 'probe-req' }, el('code', {}, `GET ${p.path}`), el('span', { class: 'chip' }, CREDENTIAL[p.credential])),
      el('div', { class: 'probe-col' }, el('label', {}, '기대'), el('div', { class: 'probe-value' }, p.expect.map((s) => statusPill(s)))),
      el('div', { class: 'probe-col' }, el('label', {}, '실제 HTTP 응답'), actual),
      el('div', { class: 'probe-col' }, el('label', {}, '브라우저가 보는 것'), seen));
    // 상세: 이 케이스를 터미널에서 재현하는 curl(상태 코드만 출력)과 케이스 설명.
    const curl = handshakeCurl({ path: p.path, credential: p.credential, tokenRequest: { userId: 'console-probe' }, statusOnly: true });
    const detail = el('div', { class: 'probe-detail', hidden: true },
      codeBlock(`curl · 기대 출력 ${p.expect.join(', ')}`, curl, curl, 'cmd'),
      el('p', { class: 'remark' }, PROBE_NOTES[p.credential]));
    const run = async () => {
      card.className = `probe-card running${detail.hidden ? '' : ' detailed'}`;
      try {
        const { json } = await api('/probe', { path: p.path, credential: p.credential, userId: 'console-probe' });
        const statuses = json.results.map((r) => r.status);
        const ok = statuses.length === p.expect.length && statuses.every((s, i) => s === p.expect[i]);
        actual.replaceChildren(...json.results.map((r) => statusPill(r.status, r.detail)));
        // 브라우저 WebSocket API에는 상태 코드가 없다: 성공이면 onopen, 실패면 이유 없이 close 1006.
        seen.replaceChildren(...statuses.map((s) => el('span', { class: `status ${s === 101 ? 's1' : 'sx'}` }, s === 101 ? 'onopen' : 'close 1006')));
        card.className = `probe-card ${ok ? 'ok' : 'bad'}${detail.hidden ? '' : ' detailed'}`;
        return ok;
      } catch (err) {
        actual.replaceChildren(el('span', { class: 'status s5' }, err.message));
        card.className = `probe-card bad${detail.hidden ? '' : ' detailed'}`;
        return false;
      }
    };
    const toggle = button('curl', () => {
      detail.hidden = !detail.hidden;
      card.classList.toggle('detailed', !detail.hidden);
    }, { class: 'btn ghost', title: '같은 요청을 터미널에서 재현하는 curl을 봅니다' });
    card.append(el('div', { class: 'probe-actions' }, toggle, button('실행', run, { class: 'btn ghost' })), detail);
    $('#probe-list').append(card);
    return run;
  });

  let running = false;
  const runAll = async () => {
    if (running) return;
    running = true;
    const summary = $('#probe-summary');
    const bar = $('#probe-bar');
    summary.className = 'pill connecting';
    setTabState('probe', 'connecting');
    let passed = 0;
    let done = 0;
    // 순서대로 실행한다(티켓 재사용처럼 서로 간섭하면 안 되는 케이스가 있다).
    for (const run of runs) {
      summary.lastElementChild.textContent = `${done + 1}/${runs.length} 실행 중`;
      if (await run()) passed += 1;
      done += 1;
      bar.style.width = `${(done / runs.length) * 100}%`;
    }
    const all = passed === runs.length;
    summary.className = `pill ${all ? 'open' : 'closed bad'}`;
    summary.lastElementChild.textContent = `${passed}/${runs.length} 통과`;
    bar.className = all ? 'ok' : 'bad';
    setTabState('probe', all ? 'open' : 'bad');
    running = false;
  };
  return runAll;
}

// ---------- 데모 ----------
async function connected(session) {
  if (!session.conn.open) session.conn.connect();
  if (!(await session.conn.ready())) throw new Error('연결하지 못했습니다. 게이트웨이와 auth-api가 떠 있는지 확인하세요');
}

const DEMOS = {
  async echo() {
    await connected(echoScene.session);
    for (const text of ['안녕하세요', '왕복 지연을 재 볼까요?']) {
      echoScene.sendEcho(text);
      await sleep(900);
    }
    echoScene.sendBroken('{not json', '잘못된 JSON');
    await sleep(900);
    echoScene.sendBroken({ type: 'nope' }, '모르는 type');
    await sleep(900);
    echoScene.sendEcho('오류 뒤에도 연결은 그대로입니다');
  },
  async push() {
    await connected(pushScene.session);
    await sleep(2600);
    pushScene.subscribe('fast', 200, 32);
    await sleep(2400);
    pushScene.unsubscribe();
  },
  async public() {
    await connected(publicScene.session);
    await sleep(2400);
    publicScene.trySubscribe();
  },
  chat: chatDemo,
  async expiry() {
    $('#expiry-ttl').value = 6;
    await expiryScene.start();
    if (!(await expiryScene.session.conn.ready())) throw new Error('연결하지 못했습니다');
    await sleep(800);
    expiryScene.sendEcho('만료 전에는 평소처럼 동작합니다');
  },
};

function setupDemos() {
  for (const btn of $$('[data-demo]')) {
    btn.addEventListener('click', async () => {
      if (btn.disabled) return;
      btn.disabled = true;
      btn.classList.add('busy');
      try {
        await DEMOS[btn.dataset.demo]();
      } catch (err) {
        toast(err.message);
      } finally {
        btn.disabled = false;
        btn.classList.remove('busy');
      }
    });
  }
}

// ---------- 탭, 보기 방식, 테마 ----------
const MODES = ['split', 'weave', 'screen', 'wire'];
let currentTab = 'echo';
let monitor = null;

function paintMode() {
  const section = $(`#tab-${currentTab}`);
  const mode = section.dataset.mode;
  const seg = $('#mode-seg');
  // 거절 케이스 탭은 프레임이 없어 보기 방식을 쓰지 않는다.
  seg.classList.toggle('disabled', !mode);
  for (const b of seg.children) {
    b.classList.toggle('active', b.dataset.mode === mode);
    b.disabled = !mode;
  }
}

function setMode(mode) {
  const section = $(`#tab-${currentTab}`);
  if (!section.dataset.mode || !MODES.includes(mode)) return;
  section.dataset.mode = mode;
  store.set(`mode:${currentTab}`, mode);
  paintMode();
  // 배치가 바뀌면 최신 줄이 보이게 맨 아래로 내린다.
  for (const box of $$('.timeline-scroll', section)) box.scrollTop = box.scrollHeight;
}

function showTab(tab) {
  if (!$(`#tab-${tab}`)) return;
  currentTab = tab;
  for (const b of $('#tabs').children) b.classList.toggle('active', b.dataset.tab === tab);
  for (const s of $$('.tab')) s.classList.toggle('active', s.id === `tab-${tab}`);
  store.set('tab', tab);
  history.replaceState(null, '', `#tab=${tab}`);
  paintMode();
  // 모니터링 탭은 보이는 동안에만 그린다(수집은 계속한다).
  if (tab === 'monitor') monitor?.show();
}

function setTheme(theme) {
  const root = document.documentElement;
  // 전환하는 동안만 색 transition을 켠다(평소에는 hover 등이 느려지지 않게).
  root.classList.add('theme-anim');
  root.dataset.theme = theme;
  $('#theme-toggle').setAttribute('aria-checked', String(theme === 'dark'));
  store.set('theme', theme);
  setTimeout(() => root.classList.remove('theme-anim'), 320);
}

function setupChrome() {
  $('#tabs').addEventListener('click', (e) => {
    const tab = e.target.closest('[data-tab]')?.dataset.tab;
    if (tab) showTab(tab);
  });
  $('#mode-seg').addEventListener('click', (e) => {
    const mode = e.target.closest('[data-mode]')?.dataset.mode;
    if (mode) setMode(mode);
  });
  $('#theme-toggle').addEventListener('click', () => setTheme(document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark'));
  $('#theme-toggle').setAttribute('aria-checked', String(document.documentElement.dataset.theme === 'dark'));
  for (const section of $$('.tab[data-mode]')) {
    const saved = store.get(`mode:${section.id.slice(4)}`);
    if (MODES.includes(saved)) section.dataset.mode = saved;
  }
}

async function main() {
  setupChrome();
  Object.assign(config, (await api('/config')).json);
  // 콘솔 서버가 authBase를 주지 않는 예전 빌드로 떠 있어도 curl 명령이 깨지지 않게 기본 주소를 쓴다.
  config.authBase ||= 'http://localhost:8070';
  $('#ws-base').textContent = config.wsBase;
  setupEcho();
  setupPush();
  setupPublic();
  setupChat();
  setupExpiry();
  DEMOS.probe = setupProbe();
  monitor = setupMonitor();
  DEMOS.monitor = monitor.demo;
  setupDemos();
  // 주소로 화면을 지정할 수 있다: #tab=chat&mode=split&theme=dark&demo=1
  const link = new URLSearchParams(location.hash.slice(1));
  showTab(link.get('tab') ?? store.get('tab', 'echo'));
  if (link.get('mode')) setMode(link.get('mode'));
  if (link.get('demo')) $(`#tab-${currentTab} [data-demo]`)?.click();
}

main().catch((err) => toast(`초기화 실패: ${err.message}`));
