// 모니터링 탭(D28): ws-gateway / ws-backend / ws-chat의 메트릭(D27)을 한 화면에서 본다.
//
// 콘솔 서버의 /api/metrics가 대상들의 /actuator/prometheus를 대신 긁어 "지금 값"만 돌려준다. 저장소는 없다:
// 화면이 열려 있는 동안 여기서 주기적으로 받아 기록을 쌓고, 누적 카운터의 차이로 초당 변화량과 구간 합계를,
// 히스토그램 버킷의 차이로 백분위를 구한다(Prometheus의 rate, increase, histogram_quantile과 같은 계산).
// 탭이 보이지 않아도 수집은 계속하므로, 다른 탭에서 데모를 돌린 뒤 넘어와도 그 구간이 차트에 있다.

import { $, el, sleep, store, config, api, toast, clock, CLOSE_MEANING } from './core.js';

const WINDOWS = [
  { id: '1m', label: '1분', ms: 60_000 },
  { id: '5m', label: '5분', ms: 300_000 },
  { id: '15m', label: '15분', ms: 900_000 },
];
const KEEP_MS = 16 * 60_000;
/** "지금" 수치(처리량, 지연)를 구하는 구간. 한 번의 수집 간격만 쓰면 값이 너무 튄다. */
const RECENT_MS = 10_000;
const SVG_NS = 'http://www.w3.org/2000/svg';

// ---------- 기록 ----------
// 시계열 하나(대상 + 메트릭 이름 + label)에 번호를 붙이고, 수집 한 번은 그 번호로 찾는 숫자 배열 하나로 둔다.
const seriesKeys = [];           // 번호 → { target, name, labels }
const seriesIds = new Map();     // "대상\u0001이름\u0001label들" → 번호
const idsByMetric = new Map();   // "대상\u0001이름" → [번호]
const history = [];              // { ts, up: {대상: bool}, v: Float64Array }
let targets = [];                // 최신 응답의 대상 [{ name, url, up, error, scrapeMillis }]
let refreshMs = 2000;
let fetchError = null;
let paused = false;
let windowMs = 300_000;

function ingest(json) {
  refreshMs = json.refreshMillis || refreshMs;
  targets = json.targets.map(({ name, url, up, error, scrapeMillis }) => ({ name, url, up, error, scrapeMillis }));
  const found = [];
  for (const target of json.targets) {
    for (const sample of target.samples) {
      const labelKey = Object.keys(sample.labels).sort().map((k) => `${k}=${sample.labels[k]}`).join(',');
      const key = `${target.name}\u0001${sample.name}\u0001${labelKey}`;
      let id = seriesIds.get(key);
      if (id === undefined) {
        id = seriesKeys.length;
        seriesIds.set(key, id);
        seriesKeys.push({ target: target.name, name: sample.name, labels: sample.labels });
        const metric = `${target.name}\u0001${sample.name}`;
        if (!idsByMetric.has(metric)) idsByMetric.set(metric, []);
        idsByMetric.get(metric).push(id);
      }
      found.push(id, sample.value);
    }
  }
  const v = new Float64Array(seriesKeys.length).fill(NaN);
  for (let i = 0; i < found.length; i += 2) v[found[i]] = found[i + 1];
  history.push({ ts: json.timestamp, up: Object.fromEntries(json.targets.map((t) => [t.name, t.up])), v });
  while (history.length && history[0].ts < json.timestamp - KEEP_MS) history.shift();
}

const latest = () => history[history.length - 1];
const at = (snap, id) => (id < snap.v.length ? snap.v[id] : NaN);
const labelsOf = (id) => seriesKeys[id].labels;

/** 대상의 메트릭 시계열 번호들. where는 label 조건이다. */
function ids(target, name, where) {
  const all = idsByMetric.get(`${target}\u0001${name}`) ?? [];
  return where ? all.filter((id) => where(labelsOf(id))) : all;
}

/** 그 시점의 합. 값이 하나도 없으면 null. */
function total(snap, list) {
  let sum = 0;
  let any = false;
  for (const id of list) {
    const value = at(snap, id);
    if (!Number.isNaN(value)) { sum += value; any = true; }
  }
  return any ? sum : null;
}

/**
 * 누적 카운터가 from → to 사이에 늘어난 양. from에 없던 시계열은 0에서 시작한 것으로 본다(카운터는 처음 쓰일 때 생긴다).
 * 값이 줄었으면 프로세스가 재시작한 것이므로 to의 값을 그대로 쓴다.
 */
function increase(from, to, list) {
  let sum = 0;
  for (const id of list) {
    const now = at(to, id);
    if (Number.isNaN(now)) continue;
    const before = at(from, id);
    const diff = now - (Number.isNaN(before) ? 0 : before);
    sum += diff < 0 ? now : diff;
  }
  return sum;
}

/** 히스토그램 버킷(`le`)의 증가분으로 구한 q 백분위(초). 구간에 관측이 없으면 null. */
function quantile(q, from, to, bucketIds) {
  const byBound = new Map();
  for (const id of bucketIds) {
    const le = labelsOf(id).le;
    const bound = le === '+Inf' ? Infinity : Number(le);
    byBound.set(bound, (byBound.get(bound) ?? 0) + increase(from, to, [id]));
  }
  const buckets = [...byBound].sort((a, b) => a[0] - b[0]);
  const count = buckets.length ? buckets[buckets.length - 1][1] : 0;
  if (!(count > 0)) return null;
  const rank = q * count;
  let lowerBound = 0;
  let lowerCount = 0;
  for (const [bound, cumulative] of buckets) {
    if (cumulative >= rank) {
      // 마지막(+Inf) 버킷이면 가장 큰 유한 경계를 답으로 한다.
      if (bound === Infinity) return lowerBound;
      const inBucket = cumulative - lowerCount;
      return inBucket > 0 ? lowerBound + (bound - lowerBound) * ((rank - lowerCount) / inBucket) : bound;
    }
    lowerBound = bound;
    lowerCount = cumulative;
  }
  return lowerBound;
}

/** 구간 계산의 기준 시점: since 이후에 그 대상이 살아 있던 가장 이른 수집. 최신 수집밖에 없으면 null. */
function baseline(target, since) {
  const last = latest();
  for (const snap of history) {
    if (snap === last) return null;
    if (snap.ts >= since && snap.up[target]) return snap;
  }
  return null;
}
const windowBase = (target) => baseline(target, latest().ts - windowMs);
function recentBase(target) {
  const last = latest();
  // 10초 전에 가장 가까운 수집. 기록이 그보다 짧으면 있는 만큼만 쓴다.
  for (let i = history.length - 2; i >= 0; i--) {
    if (history[i].up[target] && last.ts - history[i].ts >= RECENT_MS) return history[i];
  }
  return baseline(target, 0);
}
const perSecond = (from, to, list) => (from && to.ts > from.ts ? increase(from, to, list) / ((to.ts - from.ts) / 1000) : null);

// ---------- 메트릭별 조회 ----------
const scopeOf = (labels) => labels.route ?? labels.endpoint;
const isGateway = (target) => ids(target, 'ws_sessions_active').some((id) => labelsOf(id).route !== undefined);
const upTargets = () => targets.filter((t) => t.up).map((t) => t.name);
/**
 * 클라이언트와 맞닿은 프로세스. 게이트웨이가 보이면 게이트웨이, 아니면 서비스 전부다.
 * relay에서는 연결 하나가 게이트웨이와 서비스에 한 번씩 잡히므로, 전체 수치를 낼 때 둘을 더하지 않는다.
 */
function edgeTargets() {
  const up = upTargets();
  const gateways = up.filter(isGateway);
  return gateways.length ? gateways : up;
}

function lagP99(target, from, to) {
  const buckets = ids(target, 'ws_eventloop_lag_seconds_bucket');
  const pools = [...new Set(buckets.map((id) => labelsOf(id).pool))];
  let worst = null;
  for (const pool of pools) {
    const value = quantile(0.99, from, to, buckets.filter((id) => labelsOf(id).pool === pool));
    if (value !== null && (worst === null || value > worst.value)) worst = { value, pool };
  }
  return worst;
}

// ---------- 표기 ----------
const nf1 = new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 1 });
const nf2 = new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 2 });
const dash = '–';
function count(value) {
  if (value === null || value === undefined) return dash;
  if (Math.abs(value) >= 10_000) return new Intl.NumberFormat('ko-KR', { notation: 'compact', maximumFractionDigits: 1 }).format(value);
  return nf1.format(Math.round(value * 10) / 10);
}
const rate = (value) => (value === null ? dash : `${value >= 100 ? count(Math.round(value)) : nf1.format(value)}/s`);
function seconds(value) {
  if (value === null || value === undefined || Number.isNaN(value)) return dash;
  if (value < 0.001) return '<1ms';
  if (value < 1) return `${nf1.format(value * 1000)}ms`;
  return `${nf2.format(value)}s`;
}
function megabytes(bytes) {
  if (bytes === null) return dash;
  const mb = bytes / 1048576;
  return mb >= 1024 ? `${nf1.format(mb / 1024)}GB` : `${Math.round(mb)}MB`;
}
function uptime(value) {
  if (value === null) return dash;
  if (value < 60) return `${Math.floor(value)}초`;
  if (value < 3600) return `${Math.floor(value / 60)}분`;
  if (value < 86400) return `${Math.floor(value / 3600)}시간 ${Math.floor((value % 3600) / 60)}분`;
  return `${Math.floor(value / 86400)}일 ${Math.floor((value % 86400) / 3600)}시간`;
}
const windowLabel = () => WINDOWS.find((w) => w.ms === windowMs).label;
const colorOf = (index) => `var(--s${Math.min(index, 7) + 1})`;
const swatch = (index, cls = 'key-line') => el('i', { class: cls, style: `background:${colorOf(index)}` });

// ---------- 차트 ----------
function svg(tag, attrs = {}) {
  const node = document.createElementNS(SVG_NS, tag);
  for (const [k, v] of Object.entries(attrs)) node.setAttribute(k, v);
  return node;
}

/** y축 최댓값: 1, 2, 5 × 10ⁿ 가운데 값보다 크거나 같은 가장 작은 수. */
function niceMax(value, floor) {
  const target = Math.max(value, floor);
  const power = 10 ** Math.floor(Math.log10(target));
  for (const step of [1, 2, 5, 10]) if (step * power >= target * 0.999) return step * power;
  return 10 * power;
}

/**
 * 시간에 따른 선 차트. 시리즈는 프로세스이고 색은 대상 순서로 고정이다.
 * 가로축은 [지금 - 구간, 지금]으로 고정해서, 기록이 쌓이는 동안 선이 오른쪽에서부터 자란다.
 */
function makeChart({ title, hint, format, floor }) {
  const plot = el('div', { class: 'chart-plot' });
  const tip = el('div', { class: 'chart-tip', hidden: true });
  const now = el('div', { class: 'chart-now' });
  const root = el('figure', { class: 'chart' },
    el('figcaption', {}, el('div', {}, el('b', {}, title), el('small', {}, hint)), now),
    plot, tip);
  let data = null;
  let hoverX = null;

  function draw() {
    if (!data) return;
    const width = plot.clientWidth;
    if (!width) return;
    const height = 148;
    const m = { l: 40, r: 12, t: 8, b: 20 };
    const { times, series, from, to } = data;
    const peak = Math.max(0, ...series.flatMap((s) => s.points.filter((p) => p !== null)));
    const yMax = niceMax(peak, floor);
    const x = (ts) => m.l + ((ts - from) / (to - from)) * (width - m.l - m.r);
    const y = (value) => m.t + (1 - value / yMax) * (height - m.t - m.b);
    const node = svg('svg', { width, height, viewBox: `0 0 ${width} ${height}`, role: 'img', 'aria-label': title });

    for (const tick of [0, yMax / 2, yMax]) {
      node.append(svg('line', { class: tick === 0 ? 'axis' : 'grid', x1: m.l, x2: width - m.r, y1: y(tick), y2: y(tick) }));
      const text = svg('text', { x: m.l - 6, y: y(tick) + 3.5, 'text-anchor': 'end' });
      text.textContent = format(tick);
      node.append(text);
    }
    const left = svg('text', { x: m.l, y: height - 5 });
    left.textContent = clock(from).slice(0, 8);
    const right = svg('text', { x: width - m.r, y: height - 5, 'text-anchor': 'end' });
    right.textContent = '지금';
    node.append(left, right);

    series.forEach((s) => {
      // 값이 없는 구간(대상이 죽어 있었거나 수집이 끊긴 구간)에서 선을 끊는다.
      let path = '';
      let run = 0;
      let lastIndex = -1;
      s.points.forEach((value, i) => {
        if (value === null) { run = 0; return; }
        path += `${run ? 'L' : 'M'}${x(times[i]).toFixed(1)},${y(value).toFixed(1)}`;
        run += 1;
        lastIndex = i;
      });
      if (path) node.append(svg('path', { class: 'line', d: path, stroke: s.color }));
      if (lastIndex >= 0) {
        node.append(svg('circle', { class: 'dot', cx: x(times[lastIndex]), cy: y(s.points[lastIndex]), r: 4, fill: s.color }));
      }
    });

    // 십자선: 포인터에서 가장 가까운 수집 시점에 붙는다.
    let index = -1;
    if (hoverX !== null && times.length) {
      let best = Infinity;
      times.forEach((ts, i) => {
        const distance = Math.abs(x(ts) - hoverX);
        if (distance < best) { best = distance; index = i; }
      });
    }
    if (index >= 0) {
      const cx = x(times[index]);
      node.append(svg('line', { class: 'cross', x1: cx, x2: cx, y1: m.t, y2: height - m.b }));
      for (const s of series) {
        if (s.points[index] !== null) node.append(svg('circle', { class: 'dot', cx, cy: y(s.points[index]), r: 4, fill: s.color }));
      }
      tip.replaceChildren(
        el('time', {}, clock(times[index]).slice(0, 8)),
        ...series.map((s) => el('p', {}, el('i', { class: 'key-line', style: `background:${s.color}` }),
          el('b', {}, s.points[index] === null ? dash : format(s.points[index])), el('span', {}, s.name))),
      );
      tip.hidden = false;
      const tipWidth = tip.offsetWidth;
      tip.style.left = `${Math.max(4, Math.min(width - tipWidth - 4, cx > width / 2 ? cx - tipWidth - 12 : cx + 12))}px`;
    } else {
      tip.hidden = true;
    }
    plot.replaceChildren(node);
    now.replaceChildren(...series.map((s) => {
      const value = s.points[s.points.length - 1];
      return el('span', { title: s.name }, el('i', { class: 'key-line', style: `background:${s.color}` }), value == null ? dash : format(value));
    }));
  }

  plot.addEventListener('pointermove', (e) => { hoverX = e.clientX - plot.getBoundingClientRect().left; draw(); });
  plot.addEventListener('pointerleave', () => { hoverX = null; draw(); });
  return { root, update(next) { data = next; draw(); }, redraw: draw };
}

/** 구간 안의 수집 시점마다 대상별 값을 구해 차트 시리즈로 만든다. valueAt(target, 이전 수집, 이번 수집)이 null이면 선이 끊긴다. */
function chartData(valueAt) {
  const last = latest();
  const from = last.ts - windowMs;
  const first = Math.max(0, history.findIndex((snap) => snap.ts >= from));
  const times = [];
  const series = targets.map((t, i) => ({ name: t.name, color: colorOf(i), points: [] }));
  for (let i = first; i < history.length; i++) {
    const snap = history[i];
    const previous = history[i - 1];
    times.push(snap.ts);
    targets.forEach((t, k) => {
      // 수집이 끊겼던 자리(일시정지, 탭 절전)는 간격이 길어 초당 값이 뭉개지므로 비운다.
      const continuous = previous && previous.up[t.name] && snap.ts - previous.ts < refreshMs * 3.5;
      series[k].points.push(snap.up[t.name] ? valueAt(t.name, continuous ? previous : null, snap) : null);
    });
  }
  return { times, series, from, to: last.ts };
}

const CHARTS = [
  {
    title: '활성 세션', hint: '지금 열려 있는 WebSocket 세션', floor: 2, format: (v) => count(v),
    value: (t, _prev, snap) => total(snap, ids(t, 'ws_sessions_active')),
  },
  {
    title: '핸드셰이크', hint: '초당 건수(성공과 거절 모두)', floor: 1, format: (v) => rate(v),
    value: (t, prev, snap) => perSecond(prev, snap, ids(t, 'ws_handshake_seconds_count')),
  },
  {
    title: '받은 메시지', hint: '클라이언트 → 서버, 초당', floor: 1, format: (v) => rate(v),
    value: (t, prev, snap) => perSecond(prev, snap, ids(t, 'ws_messages_total', (l) => l.direction === 'in')),
  },
  {
    title: '보낸 메시지', hint: '서버 → 클라이언트, 초당', floor: 1, format: (v) => rate(v),
    value: (t, prev, snap) => perSecond(prev, snap, ids(t, 'ws_messages_total', (l) => l.direction === 'out')),
  },
  {
    title: 'event loop 지연 p99', hint: '예약한 작업이 늦게 실행된 시간(풀 가운데 큰 값)', floor: 0.005, format: (v) => (v === 0 ? '0' : seconds(v)),
    value: (t, prev, snap) => (prev ? lagP99(t, prev, snap)?.value ?? null : null),
  },
  {
    title: 'CPU', hint: '프로세스가 쓰는 코어 수(1.0 = 1 vCPU)', floor: 0.1, format: (v) => nf2.format(v),
    value: (t, _prev, snap) => {
      const usage = total(snap, ids(t, 'process_cpu_usage'));
      const cores = total(snap, ids(t, 'system_cpu_count'));
      return usage === null || cores === null ? null : usage * cores;
    },
  },
];

// ---------- 화면 조각 ----------
function kpi(label) {
  const value = el('b', {}, dash);
  const sub = el('small', {}, '');
  const root = el('div', { class: 'kpi' }, el('span', {}, label), value, sub);
  return {
    root,
    set(text, detail, tone = '') {
      value.textContent = text;
      sub.textContent = detail;
      root.dataset.tone = tone;
    },
  };
}

const fact = (label, value, title) => el('div', { class: 'fact', title }, el('span', {}, label), el('b', {}, value));

function processCard(target, index) {
  const last = latest();
  const name = target.name;
  const head = el('header', {},
    swatch(index, 'key-dot'),
    el('div', { class: 'proc-name' }, el('b', {}, name), el('code', {}, target.url.replace(/^https?:\/\//, ''))),
    el('span', { class: `pill ${target.up ? 'open' : 'closed bad'}` }, el('i'), el('span', {}, target.up ? 'up' : 'down')));
  if (!target.up) {
    return el('article', { class: 'proc down' }, head,
      el('p', { class: 'proc-down' }, el('b', {}, target.error ?? '응답 없음'),
        ' 프로세스가 떠 있는지, ', el('code', {}, '/actuator/prometheus'), '가 열려 있는지 확인하세요.'));
  }
  const gauge = (metric, where) => total(last, ids(name, metric, where));
  const recent = recentBase(name);
  const base = windowBase(name);
  const cpu = gauge('process_cpu_usage');
  const cores = gauge('system_cpu_count');
  const heapMax = gauge('jvm_memory_max_bytes', (l) => l.area === 'heap');
  const lag = recent ? lagP99(name, recent, last) : null;
  const gcCount = base ? increase(base, last, ids(name, 'jvm_gc_pause_seconds_count')) : null;
  const gcTime = base ? increase(base, last, ids(name, 'jvm_gc_pause_seconds_sum')) : null;
  const facts = el('div', { class: 'facts' },
    fact('CPU', cpu === null || cores === null ? dash : `${nf2.format(cpu * cores)} 코어`, '프로세스가 쓰는 코어 수. 1.0이면 1 vCPU를 다 쓴다.'),
    fact('힙', `${megabytes(gauge('jvm_memory_used_bytes', (l) => l.area === 'heap'))}${heapMax > 0 ? ` / ${megabytes(heapMax)}` : ''}`, '사용 중인 힙 / 최대 힙'),
    fact('direct 메모리', megabytes(gauge('reactor_netty_bytebuf_allocator_used_direct_memory')), 'Netty ByteBuf 할당자가 쓰는 direct 메모리'),
    fact('열린 파일', `${count(gauge('process_files_open_files'))} / ${count(gauge('process_files_max_files'))}`, '파일 디스크립터(소켓 포함) / 한도'),
    fact('스레드', count(gauge('jvm_threads_live_threads'))),
    fact('GC 일시정지', gcCount === null ? dash : `${count(gcCount)}회 · ${seconds(gcTime)}`, `최근 ${windowLabel()} 동안의 횟수와 합계`),
    fact('지연 p99', lag ? `${seconds(lag.value)} · ${lag.pool}` : dash, 'ws.eventloop.lag의 최근 10초 p99. 풀 가운데 큰 쪽을 보여 준다.'),
    fact('대기 작업', count(gauge('reactor_netty_eventloop_pending_tasks')), 'event loop에 쌓여 있는 작업 수(모든 loop의 합)'),
    fact('가동 시간', uptime(gauge('process_uptime_seconds'))));

  const scopes = [...new Set(ids(name, 'ws_sessions_active').map((id) => scopeOf(labelsOf(id))))].sort();
  const hasDropped = ids(name, 'ws_messages_dropped_total').length > 0;
  const where = (scope, more = () => true) => (l) => scopeOf(l) === scope && more(l);
  const table = el('table', { class: 'grid-table' },
    el('thead', {}, el('tr', {},
      el('th', {}, isGateway(name) ? '라우트' : '엔드포인트'), el('th', {}, '세션'), el('th', {}, '받음'), el('th', {}, '보냄'),
      hasDropped && el('th', { title: `송신 큐가 가득 차서 버린 메시지(최근 ${windowLabel()})` }, '버림'))),
    el('tbody', {}, scopes.map((scope) => {
      const dropped = base ? increase(base, last, ids(name, 'ws_messages_dropped_total', where(scope))) : null;
      return el('tr', {},
        el('td', {}, el('code', {}, scope)),
        el('td', {}, count(gauge('ws_sessions_active', where(scope)))),
        el('td', {}, rate(perSecond(recent, last, ids(name, 'ws_messages_total', where(scope, (l) => l.direction === 'in'))))),
        el('td', {}, rate(perSecond(recent, last, ids(name, 'ws_messages_total', where(scope, (l) => l.direction === 'out'))))),
        hasDropped && el('td', { class: dropped > 0 ? 'bad' : '' }, count(dropped)));
    })));
  return el('article', { class: 'proc' }, head, facts,
    scopes.length ? table : el('p', { class: 'proc-down' }, 'ws.* 메트릭이 없습니다. D27 이전 빌드로 떠 있을 수 있습니다.'));
}

const RESULT_TONE = { ok: 's1', unauthorized: 's4', draining: 's4', error: 's5' };
const RESULT_TEXT = { ok: '성공', unauthorized: '인증 실패', draining: 'drain 중', error: '오류' };
const REASON_TEXT = {
  missing: '자격증명 없음', expired: '만료', replayed: '티켓 재사용', bad_signature: '서명 불일치', malformed: '형식 오류',
  wrong_type: '토큰 종류 불일치', unknown_key: '모르는 키',
};
const closeTone = (code) => (code === '1000' ? 's1' : ['1001', '3000', '3008'].includes(code) ? 's4' : 's5');

/** label 조합마다 한 줄인 표. 구간 안에서 늘어난 수와 누적을 함께 보여 준다. */
function breakdown({ metric, groupBy, cells, columns, empty }) {
  const last = latest();
  const rows = [];
  targets.forEach((target, index) => {
    if (!target.up) return;
    const groups = new Map();
    for (const id of ids(target.name, metric)) {
      const key = groupBy.map((k) => labelsOf(id)[k] ?? scopeOf(labelsOf(id))).join('\u0001');
      if (!groups.has(key)) groups.set(key, []);
      groups.get(key).push(id);
    }
    const base = windowBase(target.name);
    for (const list of groups.values()) {
      const labels = labelsOf(list[0]);
      const cumulative = total(last, list) ?? 0;
      if (cumulative === 0) continue;
      rows.push({ target, index, labels, cumulative, inWindow: base ? increase(base, last, list) : null });
    }
  });
  if (!rows.length) return el('p', { class: 'table-empty' }, empty);
  const peak = Math.max(...rows.map((r) => r.cumulative));
  return el('table', { class: 'grid-table' },
    el('thead', {}, el('tr', {}, el('th', {}, '프로세스'), columns.map((c) => el('th', {}, c)), el('th', {}, `최근 ${windowLabel()}`), el('th', {}, '누적'))),
    el('tbody', {}, rows.map((row) => el('tr', {},
      el('td', {}, el('span', { class: 'proc-ref' }, swatch(row.index, 'key-dot'), row.target.name)),
      cells(row).map((cell) => el('td', {}, cell)),
      el('td', { class: row.inWindow > 0 ? 'strong' : 'dim' }, count(row.inWindow)),
      el('td', {}, el('span', { class: 'meter' },
        el('i', { style: `width:${Math.max(2, (row.cumulative / peak) * 100)}%` })), count(row.cumulative))))));
}

/** Timer(`_count`, `_sum`)의 평균. 같은 label 조합의 sum을 찾아 나눈다. */
function meanSeconds(target, metric, labels) {
  const last = latest();
  const same = (l) => Object.keys(labels).every((k) => l[k] === labels[k]);
  const sum = total(last, ids(target, `${metric}_sum`, same));
  const n = total(last, ids(target, `${metric}_count`, same));
  return n > 0 && sum !== null ? sum / n : null;
}

// ---------- 화면 ----------
let ui = null;

function build() {
  const kpis = {
    sessions: kpi('활성 세션'),
    handshake: kpi('핸드셰이크 성공률'),
    messages: kpi('메시지 처리량'),
    dropped: kpi('버린 메시지'),
    lag: kpi('event loop 지연 p99'),
    upstream: kpi('upstream 연결 실패'),
  };
  const charts = CHARTS.map((spec) => ({ spec, chart: makeChart(spec) }));
  ui = {
    kpis,
    charts,
    status: $('#monitor-status'),
    basis: el('p', { class: 'monitor-note' }),
    legend: el('div', { class: 'chart-legend' }),
    procs: el('div', { class: 'procs' }),
    handshakes: el('div', { class: 'table-wrap' }),
    closes: el('div', { class: 'table-wrap' }),
    upstreams: el('div', { class: 'table-wrap' }),
  };
  const section = (title, hint, body, extra) => el('section', { class: 'mon-section' },
    el('header', {}, el('h3', {}, title), el('p', {}, hint), extra), body);
  const tableCard = (title, hint, body) => el('article', { class: 'table-card' }, el('header', {}, el('b', {}, title), el('small', {}, hint)), body);
  $('#monitor-stage').replaceChildren(
    el('div', { class: 'kpis' }, Object.values(kpis).map((k) => k.root)),
    ui.basis,
    section('프로세스', '프로세스별 자원과, 라우트·엔드포인트별 세션과 처리량(최근 10초 평균)입니다.', ui.procs),
    section('추이', '선 하나가 프로세스 하나입니다. 차트에 마우스를 올리면 그 시각의 값을 봅니다.',
      el('div', { class: 'charts' }, charts.map((c) => c.chart.root)), ui.legend),
    section('분류', '누적 카운터를 결과별로 나눈 것입니다. 구간 값은 화면을 연 뒤에 늘어난 만큼만 셉니다.',
      el('div', { class: 'tables' },
        tableCard('핸드셰이크 결과', 'ws.handshake', ui.handshakes),
        tableCard('종료 코드', 'ws.close', ui.closes),
        tableCard('upstream 연결', 'ws.relay.upstream.connect · 게이트웨이 → 서비스', ui.upstreams))),
  );
  new ResizeObserver(() => { for (const c of charts) c.chart.redraw(); }).observe($('#monitor-stage'));
}

function paintStatus() {
  const up = targets.filter((t) => t.up).length;
  let cls = 'open';
  let text = `${refreshMs / 1000}초마다 갱신 · ${history.length ? clock(latest().ts).slice(0, 8) : dash}`;
  if (paused) { cls = 'idle'; text = '일시정지'; }
  else if (fetchError) { cls = 'closed bad'; text = `콘솔 서버 응답 없음 · ${fetchError}`; }
  else if (!history.length) { cls = 'connecting'; text = '불러오는 중'; }
  else if (up < targets.length) { cls = up ? 'closed warn' : 'closed bad'; text = `${targets.length - up}개 down · ${text}`; }
  ui.status.className = `pill ${cls}`;
  ui.status.lastElementChild.textContent = text;
  const dot = $('#tabs [data-tab="monitor"] .state');
  dot.className = `state ${paused || !history.length ? '' : fetchError || !up ? 'bad' : up < targets.length ? 'warn' : 'open'}`;
}

function paintKpis() {
  const last = latest();
  const edge = edgeTargets();
  const gateways = edge.filter(isGateway);
  const sumOver = (names, fn) => names.reduce((acc, name) => { const v = fn(name); return v === null ? acc : (acc ?? 0) + v; }, null);
  const inWindow = (names, metric, where) => sumOver(names, (n) => { const base = windowBase(n); return base ? increase(base, last, ids(n, metric, where)) : null; });
  const recentRate = (names, metric, where) => sumOver(names, (n) => perSecond(recentBase(n), last, ids(n, metric, where)));

  const sessions = sumOver(edge, (n) => total(last, ids(n, 'ws_sessions_active')));
  ui.kpis.sessions.set(count(sessions),
    targets.filter((t) => t.up).map((t) => `${t.name} ${count(total(last, ids(t.name, 'ws_sessions_active')))}`).join(' · ') || '살아 있는 대상 없음');

  const ok = inWindow(edge, 'ws_handshake_seconds_count', (l) => l.result === 'ok');
  const all = inWindow(edge, 'ws_handshake_seconds_count');
  if (all === null) ui.kpis.handshake.set(dash, '기록을 모으는 중');
  else if (all === 0) ui.kpis.handshake.set(dash, `최근 ${windowLabel()} 동안 시도 없음`);
  else ui.kpis.handshake.set(`${nf1.format((ok / all) * 100)}%`, `성공 ${count(ok)} · 거절·오류 ${count(all - ok)} · 최근 ${windowLabel()}`, ok < all ? 'warn' : 'good');

  const inRate = recentRate(edge, 'ws_messages_total', (l) => l.direction === 'in');
  const outRate = recentRate(edge, 'ws_messages_total', (l) => l.direction === 'out');
  ui.kpis.messages.set(inRate === null ? dash : rate(inRate + outRate), inRate === null ? '기록을 모으는 중' : `받음 ${rate(inRate)} · 보냄 ${rate(outRate)}`);

  const services = upTargets().filter((n) => ids(n, 'ws_messages_dropped_total').length);
  const dropped = inWindow(services, 'ws_messages_dropped_total');
  const droppedTotal = sumOver(services, (n) => total(last, ids(n, 'ws_messages_dropped_total')));
  ui.kpis.dropped.set(count(dropped), services.length ? `누적 ${count(droppedTotal)} · 송신 큐가 가득 찬 느린 소비자` : '서비스가 보이지 않음', dropped > 0 ? 'bad' : '');

  let worst = null;
  for (const name of upTargets()) {
    const base = recentBase(name);
    const lag = base ? lagP99(name, base, last) : null;
    if (lag && (!worst || lag.value > worst.value)) worst = { ...lag, name };
  }
  ui.kpis.lag.set(worst ? seconds(worst.value) : dash, worst ? `${worst.name} · ${worst.pool} · 최근 10초` : '기록을 모으는 중');

  if (!gateways.length) {
    ui.kpis.upstream.set(dash, '게이트웨이가 보이지 않음');
  } else {
    const failed = inWindow(gateways, 'ws_relay_upstream_connect_seconds_count', (l) => l.result === 'error');
    const opened = inWindow(gateways, 'ws_relay_upstream_connect_seconds_count', (l) => l.result === 'ok');
    ui.kpis.upstream.set(count(failed), failed === null ? '기록을 모으는 중' : `성공 ${count(opened)} · 최근 ${windowLabel()} · 클라이언트에는 101 뒤 1002`, failed > 0 ? 'bad' : '');
  }

  const serviceSessions = sumOver(upTargets().filter((n) => !isGateway(n)), (n) => total(last, ids(n, 'ws_sessions_active')));
  const parts = [gateways.length
    ? '세션, 핸드셰이크, 메시지 수치는 게이트웨이 기준입니다(relay에서는 연결 하나가 게이트웨이와 서비스에 한 번씩 잡힙니다).'
    : '게이트웨이가 보이지 않아 세션, 핸드셰이크, 메시지 수치는 서비스의 합계입니다.'];
  if (gateways.length && serviceSessions !== null && serviceSessions !== sessions) {
    parts.push(`지금 게이트웨이 세션은 ${count(sessions)}, 서비스 세션 합계는 ${count(serviceSessions)}입니다. 차이는 서비스에 직접 붙은 연결이거나 열리고 닫히는 중인 연결입니다.`);
  }
  ui.basis.textContent = parts.join(' ');
}

function paintTables() {
  ui.handshakes.replaceChildren(breakdown({
    metric: 'ws_handshake_seconds_count',
    groupBy: ['scope', 'result', 'reason'],
    columns: ['범위', '결과', '평균 시간'],
    empty: '아직 핸드셰이크가 없습니다.',
    cells: ({ target, labels }) => [
      el('code', {}, scopeOf(labels)),
      el('span', { class: `status ${RESULT_TONE[labels.result] ?? 's5'}` }, RESULT_TEXT[labels.result] ?? labels.result,
        labels.reason && labels.reason !== 'none' && el('small', {}, REASON_TEXT[labels.reason] ?? labels.reason)),
      seconds(meanSeconds(target.name, 'ws_handshake_seconds', labels)),
    ],
  }));
  ui.closes.replaceChildren(breakdown({
    metric: 'ws_close_total',
    groupBy: ['scope', 'code'],
    columns: ['범위', '코드'],
    empty: '아직 끝난 세션이 없습니다.',
    cells: ({ labels }) => [
      el('code', {}, scopeOf(labels)),
      el('span', { class: `status ${closeTone(labels.code)}`, title: CLOSE_MEANING[labels.code] ?? '' }, labels.code,
        el('small', {}, (CLOSE_MEANING[labels.code] ?? '그 밖의 코드').split(' · ')[0])),
    ],
  }));
  ui.upstreams.replaceChildren(breakdown({
    metric: 'ws_relay_upstream_connect_seconds_count',
    groupBy: ['scope', 'result', 'exception'],
    columns: ['라우트', '결과', '평균 시간'],
    empty: '아직 upstream 연결이 없습니다(게이트웨이를 거친 연결이 있어야 생깁니다).',
    cells: ({ target, labels }) => [
      el('code', {}, scopeOf(labels)),
      el('span', { class: `status ${labels.result === 'ok' ? 's1' : 's5'}` }, labels.result === 'ok' ? '성공' : '실패',
        labels.exception && labels.exception !== 'none' && el('small', {}, labels.exception)),
      seconds(meanSeconds(target.name, 'ws_relay_upstream_connect_seconds', labels)),
    ],
  }));
}

function render() {
  if (!ui) return;
  paintStatus();
  if (!history.length || !$('#tab-monitor').classList.contains('active')) return;
  paintKpis();
  ui.procs.replaceChildren(...targets.map(processCard));
  ui.legend.replaceChildren(...targets.map((t, i) => el('span', {}, swatch(i), t.name)));
  for (const { spec, chart } of ui.charts) chart.update(chartData(spec.value));
  paintTables();
}

async function poll() {
  for (;;) {
    if (!paused && !document.hidden) {
      try {
        ingest((await api('/metrics')).json);
        fetchError = null;
      } catch (err) {
        fetchError = err.message;
      }
      render();
    }
    await sleep(refreshMs);
  }
}

/**
 * 데모 트래픽: 차트가 움직이는 것을 보려고 브라우저에서 게이트웨이로 연결을 몇 개 만든다.
 * 토큰이 필요 없는 공개 push에 12개를 열어 15초쯤 받고 닫고, 자격증명 없이 /ws/echo를 3번 시도해 401도 만든다.
 * 공개 push는 읽기 전용이라 열 때 보내는 한 줄에는 `read only` 오류가 온다. 받은 메시지 수가 움직이게 하려고 보낸다.
 */
async function demoTraffic() {
  const sockets = [];
  for (let i = 0; i < 12; i++) {
    const ws = new WebSocket(`${config.wsBase}/ws/public/push`);
    ws.onopen = () => ws.send(JSON.stringify({ type: 'subscribe', topic: 'demo' }));
    sockets.push(ws);
    if (i % 4 === 0) new WebSocket(`${config.wsBase}/ws/echo`).onerror = () => {};
    await sleep(350);
  }
  toast('공개 push 연결 12개를 열었습니다. 15초 뒤에 닫습니다', 'ok');
  await sleep(15_000);
  for (const ws of sockets) {
    ws.close(1000, 'demo done');
    await sleep(200);
  }
}

export function setupMonitor() {
  const saved = WINDOWS.find((w) => w.id === store.get('monitor:window'));
  if (saved) windowMs = saved.ms;
  build();
  const seg = $('#monitor-window');
  const paintSeg = () => { for (const b of seg.children) b.classList.toggle('active', Number(b.dataset.ms) === windowMs); };
  seg.replaceChildren(...WINDOWS.map((w) => el('button', { type: 'button', 'data-ms': w.ms, title: `최근 ${w.label}` }, w.label)));
  seg.addEventListener('click', (e) => {
    const ms = Number(e.target.closest('[data-ms]')?.dataset.ms);
    if (!ms) return;
    windowMs = ms;
    store.set('monitor:window', WINDOWS.find((w) => w.ms === ms).id);
    paintSeg();
    render();
  });
  paintSeg();
  const pause = $('#monitor-pause');
  pause.addEventListener('click', () => {
    paused = !paused;
    pause.textContent = paused ? '다시 시작' : '일시정지';
    render();
  });
  document.addEventListener('visibilitychange', render);
  poll();
  return { show: render, demo: demoTraffic };
}
