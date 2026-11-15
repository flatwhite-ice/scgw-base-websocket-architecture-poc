// archify HTML 다이어그램의 흐름 애니메이션 한 주기를 GIF로 만든다(README에서 보이게 하기 위해).
//
// GitHub README는 저장소 안의 .webm을 <video>로 재생하지 않지만 GIF는 그대로 보여 준다.
// archify의 WebM 내보내기 결과는 탐색 색인이 없어 프레임을 뽑기 어렵다. 그래서 HTML을 헤드리스 Chrome으로 열고,
// 모든 애니메이션을 멈춘 뒤(Web Animations API) 시간을 일정 간격으로 옮기며 다이어그램 영역만 캡처한다.
// 흐름 애니메이션은 --flow-cycle(기본 6.4s) 주기로 반복되므로 한 주기를 찍으면 끊김 없이 반복되는 GIF가 된다.
//
// 필요한 것: Google Chrome, gifski(brew install gifski)
// 사용법: node scripts/archify-gif.mjs <diagram.html> <out.gif> [--fps 12] [--width 960] [--cycle-ms 6400]
// 테마와 동작 모드는 HTML의 초기 상태를 따른다(scripts/archify-defaults.mjs 적용 시 light + Live).
import { spawn, spawnSync } from 'node:child_process';
import { mkdtempSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const args = process.argv.slice(2);
const opt = (name, def) => {
  const i = args.indexOf(`--${name}`);
  return i >= 0 ? args.splice(i, 2)[1] : def;
};
const fps = Number(opt('fps', '12'));
const width = Number(opt('width', '960'));
const cycleMs = Number(opt('cycle-ms', '6400'));
const [html, out] = args;
if (!html || !out) {
  console.error('usage: node scripts/archify-gif.mjs <diagram.html> <out.gif> [--fps 12] [--width 960] [--cycle-ms 6400]');
  process.exit(2);
}

const CHROME = process.env.CHROME_PATH ?? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PORT = 9400 + Math.floor(Math.random() * 500);
const work = mkdtempSync(join(tmpdir(), 'archify-gif-'));
const framesDir = join(work, 'frames');
spawnSync('mkdir', ['-p', framesDir]);

const chrome = spawn(
  CHROME,
  ['--headless=new', `--remote-debugging-port=${PORT}`, `--user-data-dir=${join(work, 'profile')}`, '--hide-scrollbars', 'about:blank'],
  { stdio: 'ignore' },
);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

try {
  let targets;
  for (let i = 0; i < 50 && !targets; i++) {
    await sleep(200);
    try {
      targets = await (await fetch(`http://127.0.0.1:${PORT}/json`)).json();
    } catch {}
  }
  if (!targets) throw new Error('Chrome DevTools에 연결하지 못했다');
  const ws = new WebSocket(targets.find((t) => t.type === 'page').webSocketDebuggerUrl);
  await new Promise((r) => ws.addEventListener('open', r));
  let id = 0;
  const pending = new Map();
  ws.addEventListener('message', (e) => {
    const m = JSON.parse(e.data);
    if (pending.has(m.id)) {
      pending.get(m.id)(m);
      pending.delete(m.id);
    }
  });
  const send = (method, params = {}) =>
    new Promise((r) => {
      const i = ++id;
      pending.set(i, r);
      ws.send(JSON.stringify({ id: i, method, params }));
    });
  const evaluate = async (expression) =>
    (await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true })).result.result.value;

  await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1400, deviceScaleFactor: 1, mobile: false });
  await send('Page.navigate', { url: pathToFileURL(resolve(html)).href });
  await sleep(4000);
  // 뷰어 조작 UI(확대/축소, 경로 안내)는 캡처에서 뺀다.
  await evaluate(`(() => {
    const st = document.createElement('style');
    st.textContent = '.no-print, .route-journey-controls { visibility: hidden !important }';
    document.head.appendChild(st);
  })()`);
  const box = await evaluate(`(() => {
    const r = document.querySelector('.diagram-container svg').getBoundingClientRect();
    return { x: r.x, y: r.y + scrollY, w: r.width, h: r.height, theme: document.documentElement.dataset.theme };
  })()`);

  const frames = Math.round((cycleMs / 1000) * fps);
  for (let i = 0; i < frames; i++) {
    // 진입 애니메이션이 끝난 뒤(20초 지점)부터 한 주기를 찍는다.
    const ms = 20000 + (i * cycleMs) / frames;
    await evaluate(`document.getAnimations().forEach((a) => { a.pause(); a.currentTime = ${ms}; })`);
    await evaluate('new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)))');
    const shot = await send('Page.captureScreenshot', {
      format: 'png',
      captureBeyondViewport: true,
      clip: { x: box.x, y: box.y, width: box.w, height: box.h, scale: 1 },
    });
    writeFileSync(join(framesDir, `f${String(i).padStart(4, '0')}.png`), Buffer.from(shot.result.data, 'base64'));
  }
  ws.close();

  const pngs = readdirSync(framesDir).sort().map((f) => join(framesDir, f));
  const gif = spawnSync('gifski', ['--fps', String(fps), '--width', String(width), '--quality', '80', '-o', out, ...pngs], {
    stdio: ['ignore', 'ignore', 'inherit'],
  });
  if (gif.error || gif.status !== 0) throw new Error(`gifski 실패: ${gif.error?.message ?? `exit ${gif.status}`}`);
  console.log(`${out}: ${frames} frames, ${fps}fps, width ${width}, theme ${box.theme}`);
} finally {
  chrome.kill();
  rmSync(work, { recursive: true, force: true });
}
