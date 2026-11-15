// WS Console 공통 모듈(D23, D25): DOM 도우미, /api 호출, 타임라인, WebSocket 연결, 패널.
//
// 타임라인의 한 줄(entry)은 "화면에 보이는 것(view)"과 "실제로 오간 것(wire)"을 함께 가진다.
// 보기 방식(나란히/교차/화면/프로토콜)은 같은 DOM을 CSS로만 다르게 배치한다(style.css의 data-mode).

export const $ = (sel, root = document) => root.querySelector(sel);
export const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];

export function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v == null || v === false) continue;
    if (k === 'class') node.className = v;
    else if (k.startsWith('on')) node.addEventListener(k.slice(2), v);
    else node.setAttribute(k, v === true ? '' : v);
  }
  for (const c of children.flat()) if (c != null && c !== false) node.append(c);
  return node;
}

export const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/** 화면 설정(탭, 보기 방식, 테마) 저장. 저장할 수 없는 환경에서도 화면은 그대로 동작한다. */
export const store = {
  get(key, fallback = null) {
    try { return localStorage.getItem(`ws-console:${key}`) ?? fallback; } catch { return fallback; }
  },
  set(key, value) {
    try { localStorage.setItem(`ws-console:${key}`, value); } catch { /* 저장 불가 환경 */ }
  },
};

export const config = { wsBase: '', authBase: '' };

/** 로컬 시각 HH:MM:SS.mmm */
export function clock(date = new Date()) {
  const d = date instanceof Date ? date : new Date(date);
  return `${d.toTimeString().slice(0, 8)}.${String(d.getMilliseconds()).padStart(3, '0')}`;
}

/** 로컬 시각 HH:MM (말풍선용) */
export const shortClock = (date = new Date()) => (date instanceof Date ? date : new Date(date)).toTimeString().slice(0, 5);

// RFC 6455 close 코드와 이 PoC에서의 의미
export const CLOSE_MEANING = {
  1000: '정상 종료',
  1001: 'Going Away · 서버나 게이트웨이가 종료 중(drain)',
  1002: 'Protocol Error · relay에서 백엔드 연결 실패 등',
  1005: '상태 코드 없음',
  1006: '비정상 종료 · close 프레임 없이 끊김',
  1008: 'Policy Violation',
  1011: 'Internal Error · 서버 프로토콜 코드의 예외',
  3000: 'Unauthorized · 토큰 만료',
  3008: 'Timeout · heartbeat 응답 없음',
};

// ---------- 토스트 ----------
export function toast(text, tone = 'bad') {
  const host = $('#toasts');
  const node = el('div', { class: `toast ${tone}` }, text);
  host.append(node);
  setTimeout(() => node.classList.add('leaving'), 3600);
  setTimeout(() => node.remove(), 4000);
}

// ---------- API ----------
export async function api(path, body) {
  const started = performance.now();
  const res = await fetch(`/api${path}`, {
    method: body === undefined ? 'GET' : 'POST',
    headers: { 'content-type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const ms = Math.round(performance.now() - started);
  const text = await res.text();
  let json;
  try { json = text ? JSON.parse(text) : {}; } catch { json = { raw: text }; }
  if (!res.ok) {
    const error = new Error(`${res.status} ${json.message || json.error || json.raw || ''}`.trim());
    Object.assign(error, { status: res.status, ms });
    throw error;
  }
  return { json, status: res.status, ms };
}

/**
 * access token 발급(콘솔 서버가 auth-api `/auth/token`으로 중계). [trace]를 주면 HTTP 단계를 타임라인에 남긴다.
 * 토큰 값 자체는 어디에도 표시하지 않는다.
 */
export async function issueToken(userId, ttlSeconds, trace) {
  const body = { userId };
  if (ttlSeconds) body.ttlSeconds = Number(ttlSeconds);
  const step = {
    method: 'POST',
    path: '/auth/token',
    relay: '/api/token',
    headers: ['Content-Type: application/json'],
    body: JSON.stringify(body),
    curl: tokenCurl(body, false),
    tokenRequest: body,
  };
  try {
    const r = await api('/token', body);
    trace?.({
      ...step,
      status: r.status,
      ms: r.ms,
      result: `access token · ${shortClock(r.json.expiresAt)} 만료`,
      response: { ...r.json, token: `*** (JWT ${r.json.token.length}자)` },
    });
    // request: 이 토큰을 다시 받는 curl을 만들 때 쓴다.
    return { ...r.json, request: body }; // {token, expiresAt, request}
  } catch (err) {
    trace?.({ ...step, status: err.status, ms: err.ms, result: err.message, failed: true });
    throw err;
  }
}

/** 1회용 WS 티켓 발급(auth-api `/auth/ws-ticket` 중계). 브라우저는 핸드셰이크에 헤더를 못 붙이므로 티켓을 query로 보낸다. */
export async function issueTicket(token, trace, tokenRequest = { userId: 'alice' }) {
  const step = {
    method: 'POST',
    path: '/auth/ws-ticket',
    relay: '/api/ticket',
    headers: ['Authorization: Bearer ***'],
    curl: `${tokenCurl(tokenRequest, true)}\n${ticketCurl(false)}`,
    tokenRequest,
  };
  try {
    const r = await api('/ticket', { token });
    trace?.({
      ...step,
      status: r.status,
      ms: r.ms,
      result: '1회용 WS 티켓',
      response: { ...r.json, ticket: `*** (JWT ${r.json.ticket.length}자)` },
    });
    return r.json.ticket;
  } catch (err) {
    trace?.({ ...step, status: err.status, ms: err.ms, result: err.message, failed: true });
    throw err;
  }
}

// ---------- JSON 표시 ----------
const utf8 = new TextEncoder();
const byteLength = (text) => utf8.encode(text).length;

/** JSON 값을 색을 입혀 그린다. [pretty]가 아니면 한 줄로, 긴 문자열(push payload 등)은 줄여서 길이만 보여 준다. */
export function jsonView(value, pretty = false) {
  const root = el('span', { class: 'json' });
  const part = (cls, text) => root.append(el('span', { class: cls }, text));
  const write = (v, depth) => {
    if (v === null) return part('j-null', 'null');
    if (typeof v === 'string') {
      const limit = pretty ? 400 : 48;
      if (v.length <= limit) return part('j-str', JSON.stringify(v));
      part('j-str', `${JSON.stringify(v.slice(0, pretty ? 96 : 12)).slice(0, -1)}…"`);
      return part('j-len', `${v.length}자`);
    }
    if (typeof v !== 'object') return part(typeof v === 'number' ? 'j-num' : 'j-bool', String(v));
    const isArray = Array.isArray(v);
    const entries = isArray ? v.map((x) => [null, x]) : Object.entries(v);
    part('j-p', isArray ? '[' : '{');
    entries.forEach(([key, x], i) => {
      if (pretty) root.append(`\n${'  '.repeat(depth + 1)}`);
      if (key !== null) {
        part('j-key', JSON.stringify(key));
        part('j-p', pretty ? ': ' : ':');
      }
      write(x, depth + 1);
      if (i < entries.length - 1) part('j-p', ',');
    });
    if (pretty && entries.length) root.append(`\n${'  '.repeat(depth)}`);
    return part('j-p', isArray ? ']' : '}');
  };
  write(value, 0);
  return root;
}

// ---------- 상세 패널 조각 ----------
function copyButton(text) {
  return el('button', {
    type: 'button',
    class: 'copy',
    onclick: async () => {
      try {
        await navigator.clipboard.writeText(text);
        toast('복사했습니다', 'ok');
      } catch {
        toast('복사하지 못했습니다. 직접 선택해서 복사하세요');
      }
    },
  }, '복사');
}

/** 제목과 복사 버튼이 있는 코드 블록. content는 문자열이나 노드(색 입힌 JSON)다. copyText는 복사할 원문. */
export function codeBlock(title, content, copyText = content, cls = '') {
  return el('div', { class: `block ${cls}` },
    el('div', { class: 'block-head' }, el('span', {}, title), typeof copyText === 'string' && copyButton(copyText)),
    el('pre', {}, content));
}

/** 이름-값 목록. 값이 null인 줄은 뺀다. */
function facts(rows) {
  return el('dl', { class: 'facts' }, rows.filter((r) => r && r[1] != null).flatMap(([k, v]) => [el('dt', {}, k), el('dd', {}, v)]));
}

const remark = (...parts) => el('p', { class: 'remark' }, parts);

// ---------- curl ----------
const shellQuote = (text) => `'${String(text).replaceAll("'", "'\\''")}'`;
const gatewayHttp = () => config.wsBase.replace(/^ws/, 'http');
const CONTINUE = ' \\\n  ';

/** access token 발급 curl. capture면 다음 명령이 쓰도록 $TOKEN에 담는다(jq 필요). */
function tokenCurl(request, capture) {
  const parts = [`curl -s -X POST ${config.authBase}/auth/token`, "-H 'Content-Type: application/json'", `-d ${shellQuote(JSON.stringify(request))}`];
  return capture ? `TOKEN=$(${parts.join(CONTINUE)} | jq -r .token)` : parts.join(CONTINUE);
}

function ticketCurl(capture) {
  const parts = [`curl -s -X POST ${config.authBase}/auth/ws-ticket`, '-H "Authorization: Bearer $TOKEN"'];
  return capture ? `TICKET=$(${parts.join(CONTINUE)} | jq -r .ticket)` : parts.join(CONTINUE);
}

/** 핸드셰이크 전에 필요한 값을 받아 변수에 담는 명령들($TOKEN, $TICKET). */
function handshakeSetup(credential, tokenRequest) {
  if (credential === 'ticket' || credential === 'replay') return [tokenCurl(tokenRequest, true), ticketCurl(true)];
  if (credential === 'bearer') return [tokenCurl(tokenRequest, true)];
  return [];
}

/**
 * WebSocket 핸드셰이크를 curl로 재현하는 명령. 토큰과 티켓 값은 넣지 않고, 앞줄에서 새로 받아 변수로 넘긴다
 * (티켓은 1회용이라 화면에서 쓴 값을 다시 쓸 수도 없다).
 * credential: none | ticket | bearer | spoof | replay(같은 티켓으로 두 번). statusOnly면 상태 코드만 출력한다.
 */
export function handshakeCurl({ path, credential = 'none', tokenRequest = { userId: 'alice' }, statusOnly = false }) {
  const setup = handshakeSetup(credential, tokenRequest);
  const headers = [
    "-H 'Connection: Upgrade' -H 'Upgrade: websocket'",
    "-H 'Sec-WebSocket-Version: 13' -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ=='",
  ];
  let url = gatewayHttp() + path;
  if (credential === 'ticket' || credential === 'replay') {
    url += '?ticket=$TICKET';
  } else if (credential === 'bearer') {
    headers.push('-H "Authorization: Bearer $TOKEN"');
  } else if (credential === 'spoof') {
    headers.push("-H 'X-User-Id: spoofed-admin' -H 'X-Scopes: admin' -H 'X-Auth-Exp: 9999999999'");
  }
  // 101이면 연결이 열린 채로 남으므로 --max-time으로 끊는다.
  const head = statusOnly ? "curl -s -o /dev/null -w '%{http_code}\\n' --max-time 3" : 'curl -s -i -N --max-time 3';
  const parts = [`${head} "${url}"`, ...headers];
  const command = credential === 'replay'
    ? `for attempt in 1 2; do\n  ${parts.join(' \\\n    ')}\ndone`
    : parts.join(CONTINUE);
  return [...setup, command].join('\n');
}

const websocatLine = (path, withTicket) => `websocat "${config.wsBase}${path}${withTicket ? '?ticket=$TICKET' : ''}"`;

// ---------- 메시지 설명 ----------
const MESSAGE_DOCS = {
  out: {
    echo: '그대로 돌려 달라는 요청입니다. 응답과는 id로 짝을 맞춥니다.',
    subscribe: 'push 구독을 바꿉니다. 서버가 intervalMs와 payloadBytes를 허용 범위로 보정하고, seq는 0부터 다시 셉니다.',
    unsubscribe: 'push를 멈춥니다. 응답은 없습니다.',
    join: '방에 들어갑니다. 응답은 joined입니다.',
    say: '방에 말합니다. 방의 모두(나 포함)에게 message로 전달됩니다. 들어가지 않은 방이면 not joined 오류입니다.',
    leave: '방에서 나갑니다. 응답은 left입니다.',
  },
  in: {
    echo: '보낸 객체에 srvTs, userId, server를 붙여 돌려준 응답입니다.',
    push: '구독 중인 토픽의 주기 메시지입니다. 보내는 큐가 가득 차면 서버가 버리므로 seq가 건너뛸 수 있습니다.',
    error: '요청을 받아들이지 않았습니다. 연결은 유지됩니다.',
    joined: '입장 응답입니다. members는 지금 그 방의 인원입니다(이 인스턴스 기준).',
    left: '퇴장 응답입니다.',
    message: '방 전체 broadcast입니다. 보낸 사람에게도 옵니다.',
  },
};

const FIELD_DOCS = {
  id: '요청과 응답의 짝을 맞추는 값(클라이언트가 정함)',
  ts: '클라이언트가 보낸 시각(그대로 돌아옴)',
  srvTs: '서버가 처리한 시각',
  userId: '서버가 인증한 사용자(티켓에서 옴. 클라이언트가 보낸 값이 아님)',
  server: '처리한 인스턴스 <앱>@<호스트>:<포트>',
  seq: '구독 안에서의 순번',
  topic: '구독 토픽',
  intervalMs: 'push 주기',
  payloadBytes: 'push payload 크기',
  payload: '본문',
  room: '방 이름',
  from: '말한 사용자',
  text: '말한 내용',
  members: '방 인원',
  reason: '거절 이유',
};

/** RFC 6455 §5.2 프레임 헤더 크기. 클라이언트가 보내는 프레임에는 4바이트 마스킹 키가 붙는다. */
function frameOverhead(payloadBytes, fromClient) {
  const length = payloadBytes < 126 ? 0 : (payloadBytes < 65536 ? 2 : 8);
  return 2 + length + (fromClient ? 4 : 0);
}

// ---------- 프레임(프로토콜 줄) ----------
/**
 * 프로토콜 한 줄. 누르면 detail()이 만든 상세 패널이 아래에 열린다(처음 열 때 한 번만 만든다).
 */
function frameShell(kind, dir, op, body, meta, detail) {
  const box = el('div', { class: 'frame-detail', hidden: true });
  const row = el('div', { class: 'frame-row', title: '누르면 상세를 봅니다', role: 'button', tabindex: '0' },
    el('span', { class: 'dir' }, dir), el('span', { class: 'op' }, op), body,
    el('span', { class: 'frame-meta' }, meta ?? ''), el('span', { class: 'caret', 'aria-hidden': 'true' }, '›'));
  const node = el('div', { class: `frame ${kind}` }, row, box);
  node.toggle = () => {
    const open = node.classList.toggle('expanded');
    if (open && !box.childElementCount) box.append(...[detail()].flat().filter(Boolean));
    box.hidden = !open;
  };
  return node;
}

/** TEXT 프레임. */
export function textFrame(kind, raw) {
  const at = new Date();
  const fromClient = kind === 'out';
  let parsed;
  let isJson = true;
  try { parsed = JSON.parse(raw); } catch { isJson = false; }
  const body = el('code', { class: 'frame-body' },
    isJson ? jsonView(parsed) : [el('span', { class: 'j-raw' }, raw), el('span', { class: 'j-len' }, 'JSON 아님')]);
  const bytes = byteLength(raw);
  return frameShell(kind, fromClient ? '↑' : '↓', 'TEXT', body, `${bytes}B`, () => {
    const overhead = frameOverhead(bytes, fromClient);
    const type = isJson && parsed && typeof parsed === 'object' ? parsed.type : null;
    const known = Object.entries(isJson && parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {})
      .filter(([key]) => FIELD_DOCS[key]);
    return [
      facts([
        ['방향', fromClient ? '클라이언트 → 서버' : '서버 → 클라이언트'],
        ['프레임', `opcode 0x1 TEXT · FIN=1${fromClient ? ' · MASK=1' : ''}`],
        ['크기', `payload ${bytes}B + 헤더 ${overhead}B = ${bytes + overhead}B${fromClient ? ' (마스킹 키 4B 포함)' : ''}`],
        ['시각', clock(at)],
        typeof type === 'string' && ['type', el('b', {}, type)],
        isJson && Number.isFinite(parsed?.srvTs) && !fromClient && ['서버→수신', `${at.getTime() - parsed.srvTs}ms (srvTs 기준, 같은 시계일 때만 의미 있음)`],
      ]),
      typeof type === 'string' && MESSAGE_DOCS[kind]?.[type] && remark(MESSAGE_DOCS[kind][type]),
      !isJson && remark('JSON으로 읽을 수 없는 텍스트입니다. 서버는 invalid json 오류로 답합니다.'),
      known.length > 0 && el('dl', { class: 'facts fields' }, known.flatMap(([key]) => [el('dt', {}, el('code', {}, key)), el('dd', {}, FIELD_DOCS[key])])),
      codeBlock('payload', isJson ? jsonView(parsed, true) : raw, raw),
    ];
  });
}

/**
 * 토큰·티켓 발급 HTTP 호출. step: { method, path, headers, body, status, ms, result, failed, response, curl, tokenRequest }.
 * 브라우저는 콘솔 서버(/api)를 부르고 콘솔 서버가 auth-api로 중계한다. 여기에는 auth-api가 받는 요청을 보여 준다.
 */
export function httpFrame(step) {
  const { method, path, status, ms, result, failed } = step;
  const body = el('code', { class: 'frame-body' },
    el('b', {}, `${method} ${path}`),
    el('span', { class: 'dim' }, ' → '),
    el('span', { class: failed ? 'st bad' : 'st ok' }, String(status ?? '실패')),
    result && el('span', { class: 'dim' }, ` ${result}`));
  return frameShell(`http${failed ? ' bad' : ''}`, '⇄', 'HTTP', body, ms == null ? '' : `${ms}ms`, () => {
    const host = config.authBase.replace(/^https?:\/\//, '');
    const request = [`${method} ${path} HTTP/1.1`, `Host: ${host}`, ...(step.headers ?? []), ...(step.body ? ['', step.body] : [])].join('\n');
    return [
      facts([
        ['대상', `auth-api ${config.authBase}`],
        ['경유', `브라우저 → 콘솔 서버 ${step.relay} → auth-api ${path} (같은 origin이라 CORS가 필요 없음)`],
        ['결과', `${status ?? '응답 없음'} · ${ms ?? '-'}ms`],
      ]),
      codeBlock('요청', request),
      step.response && codeBlock(`응답 ${status ?? ''}`, jsonView(step.response, true), null),
      codeBlock('curl', step.curl, step.curl, 'cmd'),
      remark('토큰과 티켓 값은 화면에 남기지 않습니다. 위 명령은 터미널에서 새 값을 받아 옵니다', step.curl.includes('jq') ? '(jq 필요).' : '.'),
    ];
  });
}

/** 업그레이드 핸드셰이크 상세: 브라우저가 보낸 요청, 재현용 curl, 게이트웨이가 한 일. ctx: { path, withTicket, tokenRequest, ws }. */
function handshakeDetail(ctx, accepted, ms) {
  const host = config.wsBase.replace(/^wss?:\/\//, '');
  const request = [
    `GET ${ctx.path}${ctx.withTicket ? '?ticket=***' : ''} HTTP/1.1`,
    `Host: ${host}`,
    'Connection: Upgrade',
    'Upgrade: websocket',
    'Sec-WebSocket-Version: 13',
    'Sec-WebSocket-Key: <브라우저가 만든 무작위 16바이트>',
    `Origin: ${location.origin}`,
  ].join('\n');
  const response = accepted
    ? ['HTTP/1.1 101 Switching Protocols', 'Upgrade: websocket', 'Connection: Upgrade', 'Sec-WebSocket-Accept: <Key로 계산한 값, 브라우저가 검증>'].join('\n')
    : null;
  const credential = ctx.withTicket ? 'ticket' : 'none';
  const curl = handshakeCurl({ path: ctx.path, credential, tokenRequest: ctx.tokenRequest });
  // 준비 명령(TOKEN, TICKET)은 curl과 같고 마지막 명령만 websocat으로 바꾼다.
  const websocat = [...handshakeSetup(credential, ctx.tokenRequest), websocatLine(ctx.path, ctx.withTicket)].join('\n');
  return [
    facts([
      ['대상', `게이트웨이 ${config.wsBase}`],
      ['결과', accepted ? `101 · ${ms}ms` : '거절 또는 연결 불가'],
      accepted && ['subprotocol', ctx.ws.protocol || '없음'],
      accepted && ['extensions', ctx.ws.extensions || '없음(압축 없음)'],
    ]),
    codeBlock('요청(브라우저가 보낸 핸드셰이크)', request),
    response && codeBlock('응답', response),
    !accepted && remark('브라우저 WebSocket API는 거절된 핸드셰이크의 HTTP 상태를 알려 주지 않습니다(close 1006만 옵니다). 아래 curl을 실행하면 실제 상태(401, 404, 503 등)를 볼 수 있습니다.'),
    codeBlock('curl (상태와 헤더 확인)', curl, curl, 'cmd'),
    codeBlock('websocat (메시지까지 주고받기)', websocat, websocat, 'cmd'),
    remark(ctx.withTicket
      ? '게이트웨이(WsAuth)가 티켓을 검증하고, ticket과 Authorization을 지운 뒤 X-User-Id · X-Scopes · X-Auth-Exp를 넣어 서비스로 넘깁니다. 티켓은 1회용이라 같은 값으로 다시 연결하면 401입니다.'
      : '인증 없는 라우트입니다. 게이트웨이는 클라이언트가 보낸 신원 헤더만 지우고 그대로 넘깁니다.'),
    remark('헤더 원문은 브라우저 JS에서 읽을 수 없어, WebSocket 표준에 따라 브라우저가 보내는 형태로 적었습니다.'),
  ];
}

function upgradeFrame(line, ms, ctx) {
  const body = el('code', { class: 'frame-body' },
    el('b', {}, `GET ${line}`), el('span', { class: 'dim' }, ' Upgrade: websocket → '),
    el('span', { class: 'st ok' }, '101'), el('span', { class: 'dim' }, ' Switching Protocols'));
  return frameShell('open', '⇅', 'OPEN', body, `${ms}ms`, () => handshakeDetail(ctx, true, ms));
}

function rejectedFrame(line, ctx) {
  const body = el('code', { class: 'frame-body' },
    el('b', {}, `GET ${line}`), el('span', { class: 'dim' }, ' Upgrade: websocket → '),
    el('span', { class: 'st bad' }, '거절'),
    el('span', { class: 'dim' }, ' 브라우저에는 close 1006만 보입니다'));
  return frameShell('err', '✕', 'OPEN', body, '', () => handshakeDetail(ctx, false));
}

function closeFrame(info) {
  const { code, reason, byClient } = info;
  const body = el('code', { class: 'frame-body' },
    el('b', {}, String(code)), reason && el('span', {}, ` "${reason}"`),
    el('span', { class: 'dim' }, ` ${CLOSE_MEANING[code] ?? ''}`));
  return frameShell(`close${code === 1000 ? '' : ' warn'}`, byClient ? '↑' : '↓', 'CLOSE', body, '', () => [
    facts([
      ['코드', `${code}${code >= 3000 ? ' (RFC 6455 §7.4.2 애플리케이션 범위)' : ' (RFC 6455 §7.4.1)'}`],
      ['reason', reason ? `"${reason}"` : '없음'],
      ['의미', CLOSE_MEANING[code] ?? '이 PoC에서 따로 쓰지 않는 코드'],
      ['닫은 쪽', byClient ? '클라이언트(이 화면)' : '서버 또는 게이트웨이'],
      ['프레임', 'opcode 0x8 CLOSE · payload = 코드 2B + reason(UTF-8)'],
      ['정상 종료 절차', info.wasClean ? '완료(close 프레임을 주고받음)' : '완료되지 않음(close 프레임 없이 끊김)'],
      ['연결 시간', `${(info.livedMs / 1000).toFixed(1)}초`],
    ]),
    code === 1002 && remark('relay에서는 게이트웨이가 101을 먼저 응답하고 그 뒤에 서비스에 연결합니다. 서비스가 거절하거나 죽어 있으면 "101 뒤 1002"로 보입니다.'),
    code === 1006 && remark('close 프레임을 받지 못했습니다. 1006은 실제로 전송되는 코드가 아니라 브라우저가 붙이는 표시입니다.'),
    code === 1001 && remark('재연결하면 됩니다. 서버는 한꺼번에 재연결이 몰리지 않게 세션마다 시차를 두고 닫습니다.'),
    code === 3000 && remark('새 access token과 티켓을 받아 다시 연결해야 합니다.'),
  ]);
}

// ---------- 화면 쪽 공통 조각 ----------
/** 가운데 한 줄 안내(입장, 연결, 오류 등). */
export function notice(tone, text, sub) {
  return el('div', { class: `notice ${tone}` }, el('span', {}, text), sub && el('small', {}, sub));
}

/** 이름에서 색을 정한 원형 아바타. */
export function avatar(name, glyph) {
  let hash = 0;
  for (const ch of name) hash = (hash * 31 + ch.codePointAt(0)) % 360;
  return el('span', { class: 'avatar', style: `--hue:${hash}`, 'aria-hidden': 'true' }, glyph ?? name.slice(0, 1).toUpperCase());
}

/**
 * 말풍선. side: 'me'(오른쪽) | 'them'(왼쪽).
 * 반환한 노드의 `setMeta(text, cls)`로 전달 상태를 바꾼다.
 */
export function bubble({ side, text, name, mono, tag, meta, grouped }) {
  const metaNode = el('div', { class: 'msg-meta' }, meta ?? '');
  const node = el('div', { class: `msg ${side}${grouped ? ' grouped' : ''}` },
    side === 'them' && avatar(name ?? '?'),
    el('div', { class: 'msg-main' },
      side === 'them' && name && el('div', { class: 'msg-name' }, name),
      el('div', { class: `bubble${mono ? ' mono' : ''}` }, text, tag && el('span', { class: 'bubble-tag' }, tag)),
      metaNode));
  node.setMeta = (value, cls = '') => {
    metaNode.textContent = value;
    metaNode.className = `msg-meta ${cls}`;
  };
  return node;
}

// ---------- 타임라인 ----------
const MAX_ENTRIES = 400;

export class Timeline {
  constructor(emptyText) {
    this.list = el('div', { class: 'timeline' });
    this.empty = el('div', { class: 'timeline-empty' }, emptyText);
    this.scroll = el('div', { class: 'timeline-scroll' }, this.empty, this.list);
    this.hot = null;
    // 같은 link를 가진 줄(예: 보낸 말풍선과 돌아온 응답 프레임)을 함께 강조한다.
    this.list.addEventListener('mouseover', (e) => this.highlight(e.target.closest('.entry')?.dataset.link ?? null));
    this.list.addEventListener('mouseleave', () => this.highlight(null));
    // 프레임을 누르면 펼친다(글자를 선택하는 중이면 그대로 둔다).
    this.list.addEventListener('click', (e) => {
      const row = e.target.closest('.frame-row');
      if (!row || String(getSelection())) return;
      row.parentElement.toggle();
    });
    this.list.addEventListener('keydown', (e) => {
      if (e.key !== 'Enter' && e.key !== ' ') return;
      const row = e.target.closest('.frame-row');
      if (!row) return;
      e.preventDefault();
      row.parentElement.toggle();
    });
  }

  highlight(link) {
    if (link === this.hot) return;
    this.hot = link;
    for (const entry of this.list.children) entry.classList.toggle('linked', link != null && entry.dataset.link === link);
  }

  /** view: 화면에 보이는 것, wire: 오간 프레임. 둘 중 하나만 있어도 된다. */
  add({ view, wire, link, tone }) {
    const box = this.scroll;
    const stick = box.scrollHeight - box.scrollTop - box.clientHeight < 96;
    const entry = el('div', {
      class: `entry${view ? ' has-view' : ''}${wire ? ' has-wire' : ''}${tone ? ` t-${tone}` : ''}`,
      'data-link': link,
    },
    el('div', { class: 'cell view' }, view),
    el('div', { class: 'cell axis' }, el('i', { class: 'dot' }), el('time', {}, clock())),
    el('div', { class: 'cell wire' }, wire));
    this.list.append(entry);
    while (this.list.childElementCount > MAX_ENTRIES) this.list.firstChild.remove();
    this.empty.hidden = true;
    if (stick) box.scrollTop = box.scrollHeight;
    return entry;
  }

  clear() {
    this.list.replaceChildren();
    this.empty.hidden = false;
  }
}

// ---------- 연결 ----------
/**
 * WebSocket 연결 하나. 프레임은 모두 타임라인에 남기고, 화면 쪽 표현은 hooks가 정한다.
 *  - credentials(trace): 연결할 때마다 새 티켓을 돌려준다(없으면 익명 연결). trace로 HTTP 단계를 남긴다.
 *  - hooks.onOpen() -> view, hooks.onMessage(msg, raw) -> {view, link, tone}, hooks.onClose(info) -> view
 *  - hooks.onState(state, info), hooks.onServer(serverId)
 */
export class Connection {
  constructor({ path, credentials, timeline, hooks = {} }) {
    Object.assign(this, { path, credentials, timeline, hooks });
    this.ws = null;
    this.state = 'idle';
  }

  get open() { return this.ws?.readyState === WebSocket.OPEN; }

  setState(state, info) {
    this.state = state;
    this.hooks.onState?.(state, info);
  }

  async connect() {
    if (this.ws && this.ws.readyState <= WebSocket.OPEN) return;
    this.setState('connecting');
    let url = config.wsBase + this.path;
    try {
      if (this.credentials) {
        const ticket = await this.credentials((step) => {
          // 핸드셰이크 curl이 같은 사용자의 토큰을 받도록 기억해 둔다.
          this.tokenRequest = step.tokenRequest ?? this.tokenRequest;
          this.timeline.add({ wire: httpFrame(step), tone: step.failed ? 'bad' : 'http' });
        });
        url += `?ticket=${encodeURIComponent(ticket)}`;
      }
    } catch (err) {
      this.setState('closed', { failed: true });
      this.timeline.add({ view: notice('bad', '연결을 시작하지 못했습니다', err.message), tone: 'bad' });
      return;
    }
    // 티켓은 화면에 남기지 않는다.
    const line = `${this.path}${this.credentials ? '?ticket=***' : ''}`;
    const started = performance.now();
    const ws = new WebSocket(url);
    this.ws = ws;
    const ctx = { path: this.path, withTicket: Boolean(this.credentials), tokenRequest: this.tokenRequest, ws };
    let openedAt = null;
    this.closedByClient = false;
    ws.onopen = () => {
      openedAt = Date.now();
      this.setState('open');
      this.timeline.add({
        view: this.hooks.onOpen?.() ?? notice('ok', '연결되었습니다'),
        wire: upgradeFrame(line, Math.round(performance.now() - started), ctx),
        tone: 'ok',
      });
    };
    ws.onmessage = (ev) => {
      let msg = null;
      try { msg = JSON.parse(ev.data); } catch { /* JSON이 아닌 메시지 */ }
      const shown = (msg && this.hooks.onMessage?.(msg, ev.data)) || {};
      this.timeline.add({ view: shown.view, wire: textFrame('in', ev.data), link: shown.link, tone: shown.tone ?? 'in' });
      if (msg?.server) this.hooks.onServer?.(msg.server);
    };
    ws.onclose = (ev) => {
      const info = {
        code: ev.code,
        reason: ev.reason,
        meaning: CLOSE_MEANING[ev.code] ?? '',
        opened: openedAt != null,
        livedMs: openedAt == null ? 0 : Date.now() - openedAt,
        byClient: this.closedByClient,
        wasClean: ev.wasClean,
      };
      this.setState('closed', info);
      const custom = this.hooks.onClose?.(info);
      if (!info.opened) {
        this.timeline.add({
          view: custom ?? notice('bad', '연결하지 못했습니다', '서버가 핸드셰이크를 거절했거나 닿지 않습니다'),
          wire: rejectedFrame(line, ctx),
          tone: 'bad',
        });
        return;
      }
      const lived = `${(info.livedMs / 1000).toFixed(1)}초 동안 연결`;
      this.timeline.add({
        view: custom ?? notice(ev.code === 1000 ? 'idle' : 'warn', `연결이 끝났습니다 · ${ev.code}`, `${info.meaning} · ${lived}`),
        wire: closeFrame(info),
        tone: ev.code === 1000 ? 'idle' : 'warn',
      });
    };
  }

  /** 프레임을 보내고 타임라인에 남긴다. view는 그 동작이 화면에 어떻게 보이는지다. */
  send(payload, { view, link } = {}) {
    if (!this.open) {
      this.timeline.add({ view: notice('bad', '연결되어 있지 않습니다', '먼저 연결하세요'), tone: 'bad' });
      return false;
    }
    const raw = typeof payload === 'string' ? payload : JSON.stringify(payload);
    this.ws.send(raw);
    this.timeline.add({ view, wire: textFrame('out', raw), link, tone: 'out' });
    return true;
  }

  close() {
    if (!this.open) return;
    this.closedByClient = true;
    this.ws.close(1000, 'bye');
  }

  /** 열리거나 실패할 때까지 기다린다(데모용). */
  async ready(timeoutMs = 5000) {
    const until = Date.now() + timeoutMs;
    while (Date.now() < until) {
      if (this.open) return true;
      if (this.state === 'closed') return false;
      await sleep(40);
    }
    return false;
  }
}

// ---------- 패널(연결 하나의 화면) ----------
const STATE_LABEL = { idle: '대기', connecting: '연결 중', open: '연결됨' };

/**
 * 연결 하나를 감싼 패널: 머리(상태, server, 연결/종료), 위젯 자리, 타임라인, 입력줄.
 * 반환: { root, conn, timeline, widget, composer, head }
 */
export function makeSession({ parent, title, path, avatarOf, credentials, hooks = {}, onState, onRemove, emptyText }) {
  const timeline = new Timeline(emptyText ?? '연결하면 화면에 보이는 것과 실제로 오간 프레임이 시간순으로 쌓입니다.');
  const pill = el('span', { class: 'pill idle' }, el('i'), el('span', {}, STATE_LABEL.idle));
  // 경로 옆에 응답을 처리한 서버 인스턴스(server 필드)를 이어 붙인다.
  const where = el('code', { title: '경로 · 응답을 처리한 서버 인스턴스(server 필드)' }, path);
  // 연결과 종료는 한 버튼이 번갈아 맡는다(좁은 패널에서도 머리줄이 한 줄에 들어가게).
  const toggleBtn = el('button', { class: 'btn primary', type: 'button' }, '연결');
  const clearBtn = el('button', { class: 'btn ghost', type: 'button', title: '기록 지우기' }, '지우기');
  const widget = el('div', { class: 'panel-widget', hidden: true });
  const composer = el('div', { class: 'composer' });
  const head = el('header', { class: 'panel-head' },
    avatar(avatarOf?.name ?? title, avatarOf?.glyph),
    el('div', { class: 'panel-title' }, el('b', {}, title), where),
    pill, el('span', { class: 'spacer' }), toggleBtn, clearBtn);
  const conn = new Connection({
    path,
    credentials,
    timeline,
    hooks: {
      ...hooks,
      onState(state, info) {
        const label = pill.lastChild;
        pill.className = `pill ${state}`;
        pill.title = '';
        if (state === 'closed') {
          const clean = info?.code === 1000;
          label.textContent = info?.code && info.opened ? `종료 ${info.code}` : '연결 실패';
          pill.title = info?.meaning ?? '';
          pill.classList.add(clean ? 'clean' : (info?.opened ? 'warn' : 'bad'));
        } else {
          label.textContent = STATE_LABEL[state];
        }
        toggleBtn.disabled = state === 'connecting';
        toggleBtn.textContent = state === 'open' ? '종료' : '연결';
        toggleBtn.className = state === 'open' ? 'btn' : 'btn primary';
        toggleBtn.title = state === 'open' ? 'close 1000 "bye"를 보냅니다' : '';
        hooks.onState?.(state, info);
        onState?.(state, info);
      },
      onServer(id) {
        where.textContent = `${path} · ${id}`;
      },
    },
  });
  toggleBtn.addEventListener('click', () => (conn.open ? conn.close() : conn.connect()));
  clearBtn.addEventListener('click', () => timeline.clear());
  const root = el('section', { class: 'panel' }, head, widget,
    el('div', { class: 'col-heads', 'aria-hidden': 'true' }, el('span', {}, '화면'), el('span', {}, '시각'), el('span', {}, '프로토콜')),
    timeline.scroll, composer);
  if (onRemove) {
    head.append(el('button', {
      class: 'btn ghost',
      type: 'button',
      title: '참가자 제거',
      onclick: () => {
        conn.close();
        root.remove();
        onRemove();
      },
    }, '✕'));
  }
  parent.append(root);
  return { root, conn, timeline, widget, composer, head };
}

/** 패널 위젯용 수치 타일. set(label, value)로 갱신한다. */
export function statTiles(labels) {
  const values = new Map();
  const node = el('div', { class: 'tiles' }, labels.map((label) => {
    const value = el('b', {}, '–');
    values.set(label, value);
    return el('div', { class: 'tile' }, el('span', {}, label), value);
  }));
  return { node, set: (label, value) => { values.get(label).textContent = value; } };
}
