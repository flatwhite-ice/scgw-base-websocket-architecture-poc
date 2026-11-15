// archify가 만든 HTML 뷰어의 초기 상태를 바꾼다: 데이(light) 테마 + 동적(Live) 모드.
//
// archify 스키마에는 이 두 기본값을 정하는 옵션이 없다. 기본 동작은 다음과 같다.
//   - 테마: 저장된 선택이 없으면 OS 설정(prefers-color-scheme)을 따른다.
//   - 동작: 사용자가 Live를 누른 적이 없으면 정적(Still)으로 시작한다.
// 그래서 렌더링된 HTML에서 초기값을 정하는 부분만 고친다. 사용자가 토글로 고른 값(localStorage)과
// URL 인자(?theme=dark), 시스템의 '동작 줄이기'(prefers-reduced-motion)는 계속 우선한다.
//
// 고친 HTML은 archify의 `check --require-provenance`를 통과하지 않는다(바이트가 달라진다).
// 검사는 이 스크립트를 실행하기 전에 끝낸다.
//
// 사용법: node scripts/archify-defaults.mjs <diagram.html> [...]
// 여러 번 실행해도 결과가 같다. 고칠 문자열이 없으면(archify 버전이 바뀌어 템플릿이 달라지면) 실패한다.
import { readFileSync, writeFileSync } from 'node:fs';

const patches = [
  {
    name: '테마 초기값(head 스크립트): OS 설정 대신 light',
    from: "theme = window.matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark';",
    to: "theme = 'light'; /* archify-defaults */",
  },
  {
    name: '테마 초기값(테마 토글): OS 설정 대신 light',
    from: "return window.matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark';",
    to: "return 'light'; /* archify-defaults */",
  },
  {
    name: '테마: 열려 있는 동안 OS 테마가 바뀌어도 따라가지 않음',
    from: "apply(e.matches ? 'light' : 'dark');",
    to: '/* archify-defaults: OS 테마 변경을 따르지 않는다 */',
  },
  {
    name: '동작 초기값: 사용자가 정적을 고른 경우에만 정지',
    from: "readerPaused = readStored() !== 'live';",
    to: "readerPaused = readStored() === 'still'; /* archify-defaults */",
  },
  {
    // 기본이 Live이므로, 사용자가 정적을 고른 사실을 저장해야 다음에 열 때도 정적으로 열린다.
    name: '동작: 정적 선택을 저장',
    from: 'if (readerPaused) localStorage.removeItem(STORAGE_KEY);',
    to: "if (readerPaused) localStorage.setItem(STORAGE_KEY, 'still'); /* archify-defaults */",
  },
];

const files = process.argv.slice(2);
if (files.length === 0) {
  console.error('usage: node scripts/archify-defaults.mjs <diagram.html> [...]');
  process.exit(2);
}

let failed = false;
for (const file of files) {
  let html = readFileSync(file, 'utf8');
  for (const p of patches) {
    const count = html.split(p.from).length - 1;
    if (count === 1) {
      html = html.replace(p.from, p.to);
    } else if (count === 0 && html.includes(p.to)) {
      // 이미 적용됨
    } else {
      console.error(`${file}: '${p.name}' 대상 문자열이 ${count}개다(1개여야 한다). archify 템플릿이 바뀌었는지 확인한다.`);
      failed = true;
    }
  }
  if (!failed) {
    writeFileSync(file, html);
    console.log(`${file}: 데이 테마 + 동적 모드 기본값 적용`);
  }
}
process.exit(failed ? 1 : 0);
