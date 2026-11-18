/* 設計稿示範用：只負責「能操作的樣子」，不是產品邏輯。 */
const NS = 'http://www.w3.org/2000/svg';
const NW = 200, NH = 112, NHS = 136;
const $ = (s, r = document) => r.querySelector(s);
const icon = (id) => `<svg class="ico" aria-hidden="true"><use href="#i-${id}"/></svg>`;
const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]);

const T = {
  start: { label: '進入條件', cat: 'source', ico: 'start', ports: [''], sub: '只限 大於 500・購物車金額' },
  msg: { label: '訊息', cat: 'send', ico: 'msg', ports: [''], sub: '還沒設定要發的訊息', warn: true, desc: '發一則 LINE 訊息', grp: '發送' },
  wait: { label: '等待', cat: 'wait', ico: 'wait', ports: [''], sub: '1 小時', desc: '停一段時間再往下', grp: '等待' },
  wev: { label: '等事件', cat: 'wait', ico: 'wev', ports: [''], sub: '24 小時・尚未選事件', warn: true, desc: '等客戶做某件事', grp: '等待' },
  cond: { label: '條件', cat: 'branch', ico: 'cond', ports: ['是', '否'], sub: '還沒設定條件', warn: true, desc: '符合與否走不同路', grp: '分支' },
  ab: { label: 'A/B 分流', cat: 'branch', ico: 'ab', ports: ['A', 'B'], sub: 'A 50%・B 50%', desc: '隨機分兩組比較', grp: '分支' },
  merge: { label: '合流', cat: 'branch', ico: 'merge', ports: [''], sub: '多條路合成一條', desc: '把分開的路接回來', grp: '分支' },
  goal: { label: '達標', cat: 'action', ico: 'goal', ports: [''], sub: '客戶達成目標', desc: '標記客戶達成目標', grp: '結果' },
  exit: { label: '退出', cat: 'end', ico: 'exit', ports: [], sub: '客戶離開旅程', desc: '讓客戶離開旅程', grp: '結果' },
  end: { label: '結束', cat: 'end', ico: 'end', ports: [], sub: '旅程到此結束', desc: '這條路的終點', grp: '結果' },
};
const PAL = ['msg', 'wait', 'wev', 'cond', 'ab', 'merge', 'goal', 'exit', 'end'];

/* 靜態節點卡（規格圖用） */
function card(o) {
  const t = T[o.type];
  const stat = o.stats ? `<div class="t"><b>${o.stats[0]}</b> 人數<i>|</i><b>${o.stats[1]}</b> 目前</div>` : o.state ? `<div class="st">${o.state}</div>` : '';
  return `<div class="nc static cat-${t.cat} ${stat ? 'stat' : ''} ${o.cls || ''}" ${o.title ? `title="${esc(o.title)}"` : ''}><div class="c">${icon(t.ico)}${o.catLabel || t.label}</div><div class="n">${esc(o.name)}</div><div class="s ${o.warn ? 'warn' : ''}">${esc(o.sub || '')}</div>${stat}</div>`;
}

const bez = (x1, y1, x2, y2) => { const dx = Math.max(60, Math.abs(x2 - x1) / 2); return `M${x1},${y1} C${x1 + dx},${y1} ${x2 - dx},${y2} ${x2 - 2},${y2}`; };
const mid = (x1, y1, x2, y2) => { const dx = Math.max(60, Math.abs(x2 - x1) / 2); return [(x1 + 3 * (x1 + dx) + 3 * (x2 - dx) + x2) / 8, (y1 + 3 * y1 + 3 * y2 + y2) / 8]; };
const portY = (n, i) => { const k = T[n.type].ports.length; return k === 1 ? NH / 2 : [32, 80][i]; };

/* ================= 可操作的編輯畫布 ================= */
(function editor() {
  const cv = $('#cv'); if (!cv) return;
  const cvw = $('#cvw'), world = $('#world'), svg = $('#es'), labs = $('#elabs'), side = $('#side'), live = $('#live'), sizer = $('#sizer');
  let Z = 0.75, nodes = [], edges = [], sel = null, cnt = {}, linking = null, menuEl = null, suppress = false, eid = 0;
  const say = (m) => { live.textContent = ''; setTimeout(() => (live.textContent = m), 30); $('#toast').textContent = m; };
  const nodeById = (id) => nodes.find((n) => n.id === id);
  const small = () => matchMedia('(max-width:640px)').matches;

  function setZoom(z) {
    Z = Math.max(0.4, Math.min(1.25, z));
    cv.style.setProperty('--zoom', Z);
    sizer.style.width = 1130 * Z + 'px'; sizer.style.height = 580 * Z + 'px';
    const p = $('#zp'); p.textContent = Math.round(Z * 100) + '%';
    p.style.color = Z < 0.7 ? 'var(--color-warning-fg)' : ''; p.style.background = Z < 0.7 ? 'var(--color-warning-bg)' : '';
  }
  const toWorld = (e) => { const r = cv.getBoundingClientRect(); return { x: (e.clientX - r.left + cv.scrollLeft) / Z, y: (e.clientY - r.top + cv.scrollTop) / Z }; };
  const overlap = (x, y, ignore) => nodes.some((m) => m !== ignore && Math.abs(m.x - x) < NW + 24 && Math.abs(m.y - y) < NH + 24);
  const freeSpot = (x, y) => { let k = 0; while (overlap(x, y) && k++ < 40) y += 136, k % 4 === 0 && (x += 272, y = 24); return { x, y: Math.max(16, y) }; };

  function mk(type, x, y, name) {
    const t = T[type]; cnt[type] = (cnt[type] || 0) + 1;
    const n = { id: type + cnt[type], type, x, y, name: name || (type === 'start' ? '開始' : `${t.label} ${cnt[type]}`) };
    nodes.push(n);
    const w = document.createElement('div'); w.className = 'nw'; w.dataset.id = n.id; w.style.left = x + 'px'; w.style.top = y + 'px';
    const wm = t.ports.length;
    w.innerHTML = `<button type="button" class="nc cat-${t.cat}" aria-label="${esc(n.name)}（${t.label}）"><div class="c">${icon(t.ico)}${t.label}</div><div class="n"></div><div class="s ${t.warn ? 'warn' : ''}">${esc(t.sub)}</div></button>` +
      (type === 'start' ? '' : `<span class="hd in" style="left:0;top:${NH / 2}px"></span>`) +
      t.ports.map((p, i) => `<button type="button" class="hd out" data-i="${i}" style="left:${NW}px;top:${portY(n, i)}px" aria-label="從「${esc(n.name)}」${p ? `的「${p}」出口` : '的出口'}拉線到下一步"><span class="plabel">${p || '下一步'}</span><span class="tag">拉線接下一步</span></button>`).join('');
    n.el = w; w.querySelector('.n').textContent = n.name;
    world.appendChild(w);
    w.querySelector('.nc').addEventListener('pointerdown', (e) => nodeDown(e, n));
    w.querySelector('.nc').addEventListener('keydown', (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); select({ k: 'node', id: n.id }); } });
    w.querySelectorAll('.hd.out').forEach((h) => h.addEventListener('pointerdown', (e) => linkDown(e, n, +h.dataset.i)));
    return n;
  }
  const tag = (n) => `${n.name}`;

  /* 邊 */
  function drawEdges() {
    svg.innerHTML = ''; labs.innerHTML = '';
    edges.forEach((ed) => {
      const a = nodeById(ed.from), b = nodeById(ed.to); if (!a || !b) return;
      const x1 = a.x + NW, y1 = a.y + portY(a, ed.pi), x2 = b.x, y2 = b.y + NH / 2;
      const g = document.createElementNS(NS, 'g'); g.setAttribute('class', 'edge' + (sel && sel.k === 'edge' && sel.id === ed.id ? ' sel' : ''));
      const d = bez(x1, y1, x2, y2);
      const p = document.createElementNS(NS, 'path'); p.setAttribute('class', 'e'); p.setAttribute('d', d); p.setAttribute('marker-end', 'url(#ah)');
      const h = document.createElementNS(NS, 'path'); h.setAttribute('class', 'hit'); h.setAttribute('d', d);
      h.addEventListener('click', (e) => { e.stopPropagation(); select({ k: 'edge', id: ed.id }); });
      g.append(p, h); svg.appendChild(g);
      const [mx, my] = mid(x1, y1, x2, y2); const lab = T[a.type].ports[ed.pi];
      if (lab && !(sel && sel.k === 'edge' && sel.id === ed.id)) { const l = document.createElement('div'); l.className = 'elab'; l.textContent = lab; l.style.left = mx + 'px'; l.style.top = my + 'px'; labs.appendChild(l); }
      if (sel && sel.k === 'edge' && sel.id === ed.id) {
        const b2 = document.createElement('button'); b2.type = 'button'; b2.className = 'edel'; b2.style.left = mx + 'px'; b2.style.top = my + 'px';
        b2.innerHTML = `${icon('trash')}移除連線`; b2.setAttribute('aria-label', `移除連線：${a.name}${lab ? '（' + lab + '）' : ''} 到 ${b.name}`);
        b2.addEventListener('click', () => removeEdge(ed.id)); labs.appendChild(b2); if (!b2.dataset.f) setTimeout(() => b2.focus({ preventScroll: true }), 0);
      }
    });
    nodes.forEach((n) => n.el.querySelectorAll('.hd.out').forEach((h) => h.classList.toggle('free', !edges.some((e) => e.from === n.id && e.pi === +h.dataset.i))));
    const free = nodes.reduce((s, n) => s + T[n.type].ports.filter((_, i) => !edges.some((e) => e.from === n.id && e.pi === i)).length, 0);
    $('#cvstat').innerHTML = free ? `<span style="color:var(--color-warning-fg);font-weight:600">還有 ${free} 個出口沒接下一步</span>` : `<span class="ok">${icon('check')}所有出口都接好了</span>`;
    $('#cnt').textContent = `${nodes.length} 個節點・${edges.length} 條連線`;
  }

  function connect(n, pi, toId) {
    edges = edges.filter((e) => !(e.from === n.id && e.pi === pi));
    const to = nodeById(toId); if (!to) { drawEdges(); return; }
    const ed = { id: 'e' + ++eid, from: n.id, pi, to: toId }; edges.push(ed);
    $('#coach').hidden = true; drawEdges();
    say(`已把「${n.name}」${T[n.type].ports[pi] ? '的「' + T[n.type].ports[pi] + '」' : ''}接到「${to.name}」`);
    if (sel && sel.k === 'node') fillSide();
  }
  function removeEdge(id) {
    const ed = edges.find((e) => e.id === id); edges = edges.filter((e) => e.id !== id); sel = null; drawEdges(); fillSide();
    if (ed) { say(`已移除連線，「${nodeById(ed.from).name}」的出口現在沒接下一步`); const h = nodeById(ed.from).el.querySelectorAll('.hd.out')[ed.pi]; h && h.focus({ preventScroll: true }); }
  }
  function removeNode(id) {
    const n = nodeById(id); if (!n || n.type === 'start') return;
    edges = edges.filter((e) => e.from !== id && e.to !== id); nodes = nodes.filter((m) => m !== n); n.el.remove(); sel = null; drawEdges(); fillSide(); say(`已刪除「${n.name}」和它的連線`);
  }

  /* 選取與右側面板（下一步下拉＝鍵盤與無障礙替代） */
  function select(s) {
    sel = s; nodes.forEach((n) => { const on = s && s.k === 'node' && s.id === n.id; n.el.classList.toggle('sel', on); n.el.querySelector('.nc').classList.toggle('sel', on); n.el.style.zIndex = on ? 2 : ''; });
    drawEdges(); fillSide();
  }
  function fillSide() {
    if (!sel || sel.k !== 'node') { side.hidden = true; return; }
    const n = nodeById(sel.id); if (!n) { side.hidden = true; return; } const t = T[n.type]; side.hidden = false;
    const others = nodes.filter((m) => m !== n && m.type !== 'start');
    side.innerHTML = `<header>${icon(t.ico)}<h3></h3><button type="button" class="x" aria-label="關閉面板">${icon('x')}</button></header>
      <div class="field"><label for="nm">名稱</label><input id="nm" value="${esc(n.name)}" maxlength="40"></div>
      ${t.ports.length ? t.ports.map((p, i) => { const cur = edges.find((e) => e.from === n.id && e.pi === i); return `<div class="next"><label for="nx${i}">${p ? `「${p}」的下一步` : '下一步'}</label><select id="nx${i}" data-i="${i}"><option value="">（還沒接）</option>${others.map((m) => `<option value="${m.id}" ${cur && cur.to === m.id ? 'selected' : ''}>${esc(m.name)}</option>`).join('')}</select></div>`; }).join('') + `<p class="hint">也可以直接從節點右邊的圓點拉線到下一個節點。</p>` : `<p class="note">${t.label}是這條路的終點，不用接下一步。</p>`}
      ${n.type === 'start' ? `<p class="note">進入條件是旅程的入口，不能刪除。</p>` : `<button type="button" class="btn secondary danger" id="delnode">${icon('trash')}&nbsp;刪除這個節點</button>`}`;
    side.querySelector('h3').textContent = n.name;
    side.querySelector('.x').onclick = () => select(null);
    side.querySelector('#nm').oninput = (e) => { n.name = e.target.value || t.label; n.el.querySelector('.n').textContent = n.name; side.querySelector('h3').textContent = n.name; drawEdges(); };
    side.querySelectorAll('select').forEach((s) => (s.onchange = () => { if (s.value) connect(n, +s.dataset.i, s.value); else { const ed = edges.find((e) => e.from === n.id && e.pi === +s.dataset.i); edges = edges.filter((e) => e !== ed); drawEdges(); say('已清除這個出口的下一步'); } }));
    const d = side.querySelector('#delnode'); d && (d.onclick = () => removeNode(n.id));
  }

  /* 移動節點 */
  function nodeDown(e, n) {
    if (e.button > 0) return; closeMenu(); select({ k: 'node', id: n.id });
    const sx = e.clientX, sy = e.clientY, ox = n.x, oy = n.y; let moved = false;
    const mv = (ev) => { const dx = ev.clientX - sx, dy = ev.clientY - sy; if (!moved && Math.hypot(dx, dy) < 5) return; moved = true; n.x = Math.max(0, Math.round(ox + dx / Z)); n.y = Math.max(0, Math.round(oy + dy / Z)); n.el.style.left = n.x + 'px'; n.el.style.top = n.y + 'px'; drawEdges(); };
    const up = () => { removeEventListener('pointermove', mv); removeEventListener('pointerup', up); };
    addEventListener('pointermove', mv); addEventListener('pointerup', up);
  }

  /* 從連接點拉線 */
  function linkDown(e, n, pi) {
    e.preventDefault(); e.stopPropagation(); closeMenu(); select({ k: 'node', id: n.id });
    linking = { n, pi }; cv.classList.add('linking');
    const tmp = document.createElementNS(NS, 'path'); tmp.setAttribute('class', 'tmp'); svg.appendChild(tmp);
    const x1 = n.x + NW, y1 = n.y + portY(n, pi);
    const target = (ev) => { const w = document.elementFromPoint(ev.clientX, ev.clientY); const el = w && w.closest ? w.closest('.nw') : null; return el ? nodeById(el.dataset.id) : null; };
    const mv = (ev) => {
      const p = toWorld(ev); const t = target(ev); tmp.setAttribute('d', bez(x1, y1, t && t.id !== n.id && t.type !== 'start' ? t.x : p.x, t && t.id !== n.id && t.type !== 'start' ? t.y + NH / 2 : p.y));
      nodes.forEach((m) => { const c = m.el.querySelector('.nc'); c.classList.toggle('tgt-ok', t === m && m !== n && m.type !== 'start'); c.classList.toggle('tgt-no', (t === m && (m === n || m.type === 'start'))); });
    };
    const up = (ev) => {
      removeEventListener('pointermove', mv); removeEventListener('pointerup', up); cv.classList.remove('linking'); tmp.remove(); linking = null;
      nodes.forEach((m) => m.el.querySelector('.nc').classList.remove('tgt-ok', 'tgt-no'));
      const t = target(ev);
      if (t && t.id !== n.id && t.type !== 'start') connect(n, pi, t.id);
      else if (t) say(t === n ? '不能接回自己；要重複走請接到前面的節點' : '「進入條件」是入口，不能當下一步');
      else if (cv.contains(document.elementFromPoint(ev.clientX, ev.clientY)) && Math.hypot(ev.clientX - (cv.getBoundingClientRect().left + (x1 * Z - cv.scrollLeft)), ev.clientY - (cv.getBoundingClientRect().top + (y1 * Z - cv.scrollTop))) > 24) openMenu(ev, n, pi);
    };
    addEventListener('pointermove', mv); addEventListener('pointerup', up);
  }
  /* 放開在空白處：跳出「接上新節點」選單 */
  function openMenu(ev, n, pi) {
    const r = cv.getBoundingClientRect(); const p = toWorld(ev); const px = ev.clientX - r.left + cv.scrollLeft, py = ev.clientY - r.top + cv.scrollTop;
    menuEl = document.createElement('div'); menuEl.className = 'menu'; menuEl.setAttribute('role', 'menu'); menuEl.setAttribute('aria-label', '接上新節點');
    menuEl.style.left = Math.min(px, cv.scrollLeft + cv.clientWidth - 250) + 'px'; menuEl.style.top = Math.min(py, cv.scrollTop + cv.clientHeight - 380) + 'px';
    menuEl.innerHTML = `<h4>接上新節點</h4>` + PAL.map((k) => `<button type="button" role="menuitem" class="m cat-${T[k].cat}" data-k="${k}">${icon(T[k].ico)}${T[k].label}</button>`).join('') + `<button type="button" class="m cancel" data-k="">取消（Esc）</button>`;
    cv.appendChild(menuEl);
    menuEl.addEventListener('click', (e) => { const b = e.target.closest('button'); if (!b) return; const k = b.dataset.k; closeMenu(); if (!k) return; const s = freeSpot(Math.max(0, Math.round(p.x + 20)), Math.max(16, Math.round(p.y - NH / 2))); const m = mk(k, s.x, s.y); connect(n, pi, m.id); select({ k: 'node', id: m.id }); });
    menuEl.querySelector('button').focus();
  }
  function closeMenu() { if (menuEl) { menuEl.remove(); menuEl = null; } }

  /* 工具列拖進畫布，或點一下（Enter）新增 */
  function addByClick(k) {
    const s = sel && sel.k === 'node' ? nodeById(sel.id) : null; let from = null;
    if (s) { const pi = T[s.type].ports.findIndex((_, i) => !edges.some((e) => e.from === s.id && e.pi === i)); if (pi >= 0) from = { s, pi }; }
    const spot = from ? freeSpot(from.s.x + 272, from.s.y + (from.pi ? 136 : 0)) : freeSpot(24 + (nodes.length % 4) * 272, 24 + Math.floor(nodes.length / 4) * 136 + 150);
    const m = mk(k, spot.x, spot.y); if (from) connect(from.s, from.pi, m.id); else say(`已新增「${m.name}」，從它右邊的圓點拉線接下一步`);
    select({ k: 'node', id: m.id }); cv.scrollTo({ left: Math.max(0, (m.x + NW) * Z - cv.clientWidth + 60), behavior: 'smooth' });
  }
  document.querySelectorAll('.pi').forEach((b) => {
    b.addEventListener('click', () => { if (suppress) { suppress = false; return; } addByClick(b.dataset.k); });
    b.addEventListener('pointerdown', (e) => {
      if (small() || e.button > 0) return; const sx = e.clientX, sy = e.clientY; let ghost = null;
      const mv = (ev) => {
        if (!ghost && Math.hypot(ev.clientX - sx, ev.clientY - sy) > 6) { ghost = b.cloneNode(true); ghost.className += ' dragghost'; document.body.appendChild(ghost); }
        if (ghost) { ghost.style.left = ev.clientX + 'px'; ghost.style.top = ev.clientY + 'px'; const r = cv.getBoundingClientRect(); cvw.classList.toggle('dragover', ev.clientX > r.left && ev.clientX < r.right && ev.clientY > r.top && ev.clientY < r.bottom); }
      };
      const up = (ev) => {
        removeEventListener('pointermove', mv); removeEventListener('pointerup', up);
        if (!ghost) return; ghost.remove(); suppress = true; setTimeout(() => (suppress = false), 50);
        const r = cv.getBoundingClientRect(); const inside = cvw.classList.contains('dragover'); cvw.classList.remove('dragover');
        if (inside && !(ev.clientX > r.right - 340 && !side.hidden && matchMedia('(min-width:1101px)').matches)) { const p = toWorld(ev); const s = freeSpot(Math.max(0, Math.round(p.x - NW / 2)), Math.max(16, Math.round(p.y - NH / 2))); const m = mk(b.dataset.k, s.x, s.y); select({ k: 'node', id: m.id }); say(`已新增「${m.name}」，從它右邊的圓點拉線接下一步`); }
      };
      addEventListener('pointermove', mv); addEventListener('pointerup', up);
    });
  });

  cv.addEventListener('click', (e) => { if (e.target === cv || e.target === world || e.target === sizer) { closeMenu(); select(null); } });
  addEventListener('keydown', (e) => {
    if (e.key === 'Escape') { closeMenu(); if (linking) return; }
    if ((e.key === 'Delete' || e.key === 'Backspace') && sel && !/INPUT|SELECT|TEXTAREA/.test(document.activeElement.tagName)) { e.preventDefault(); sel.k === 'edge' ? removeEdge(sel.id) : removeNode(sel.id); }
  });
  $('#zin').onclick = () => setZoom(Z + 0.1); $('#zout').onclick = () => setZoom(Z - 0.1);
  $('#zfit').onclick = () => { const bw = Math.max(...nodes.map((n) => n.x + NW)) + 40, bh = Math.max(...nodes.map((n) => n.y + NH)) + 40; setZoom(Math.min(cv.clientWidth / bw, cv.clientHeight / bh, 1)); cv.scrollTo(0, 0); };
  $('#coachx').onclick = () => ($('#coach').hidden = true);
  $('#reset').onclick = () => { nodes.forEach((n) => n.el.remove()); nodes = []; edges = []; cnt = {}; sel = null; seed(); $('#coach').hidden = false; };

  function seed() {
    const s = mk('start', 24, 150, '開始'); const w = mk('wait', 296, 150, '等待 1 小時'); const c = mk('cond', 568, 150, '已下單？');
    const m = mk('msg', 840, 360, '提醒 1'); const e = mk('end', 840, 24, '結束');
    edges = [{ id: 'e0', from: s.id, pi: 0, to: w.id }, { id: 'e1', from: w.id, pi: 0, to: c.id }, { id: 'e2', from: c.id, pi: 0, to: e.id }, { id: 'e3', from: c.id, pi: 1, to: m.id }]; eid = 3;
    m.el.querySelector('.s').textContent = '歡迎回來，還在考慮嗎？'; m.el.querySelector('.s').classList.remove('warn'); c.el.querySelector('.s').textContent = '客戶已下單'; c.el.querySelector('.s').classList.remove('warn');
    setZoom(0.75); drawEdges(); fillSide();
  }
  $('#palette').innerHTML = ['發送', '等待', '分支', '結果'].map((g) => `<div class="grp">${g}</div>` + PAL.filter((k) => T[k].grp === g).map((k) => `<button type="button" class="pi cat-${T[k].cat}" data-k="${k}" title="${T[k].label}：${T[k].desc}（拖到畫布，或按一下）">${icon(T[k].ico)}<span class="tx"><b>${T[k].label}</b><small>${T[k].desc}</small></span><span class="grip">${icon('grip')}</span></button>`).join('')).join('');
  seed();
  /* 取消 mk 時多餘的 tag 函式警告 */ void tag;
})();

/* ================= 唯讀圖（旅程頁）================= */
(function readonlyGraph() {
  const host = $('#ro'); if (!host) return;
  const N = [
    { id: 'a', type: 'start', c: 0, y: 140, name: '開始', sub: '進入旅程', stats: [128, 0], catLabel: '進入條件' },
    { id: 'b', type: 'wait', c: 1, y: 140, name: '等待 1 小時', sub: '1 小時', stats: [128, 9] },
    { id: 'c', type: 'cond', c: 2, y: 140, name: 'Entry event', sub: '大於 500・購物車金額', stats: [128, 4] },
    { id: 'd', type: 'msg', c: 3, y: 20, name: 'Reminder', sub: 'LINE・提醒 1', stats: [87, 12] },
    { id: 'e', type: 'wev', c: 3, y: 300, name: 'Wait for order (24 hours)', sub: '24 小時・符合 2 項條件', stats: [41, 7] },
    { id: 'f', type: 'merge', c: 4, y: 140, name: '分支合流', sub: '多條路合成一條', stats: [128, 3] },
    { id: 'g', type: 'goal', c: 5, y: 140, name: 'Ordered', sub: '客戶達成目標', stats: [64, 0] },
    { id: 'h', type: 'end', c: 6, y: 140, name: '結束', sub: '旅程到此結束', stats: [64, 0] },
  ];
  const E = [['a', 'b'], ['b', 'c'], ['c', 'd', '是'], ['c', 'e', '否'], ['d', 'f'], ['e', 'f'], ['f', 'g'], ['g', 'h']];
  const W = 6 * 272 + NW + 48, H = 300 + NHS + 24; let Z = 0.75;
  host.innerHTML = `<div class="scroller" id="roscroll"><div class="box" id="robox"><div class="world" id="roworld" style="width:${W}px;height:${H}px"><svg id="rosvg" width="${W}" height="${H}"></svg></div></div></div><div class="fade"></div>
    <div class="zbar" role="group" aria-label="縮放"><button type="button" id="rzo" aria-label="縮小">${icon('minus')}</button><span id="rzp">75%</span><button type="button" id="rzi" aria-label="放大">${icon('plus')}</button><button type="button" class="fit" id="rfit">${icon('fit')}看全圖</button></div>
    <div class="more" role="status">${icon('info')}<span id="romsg">還有節點在右邊：左右拖曳或捲動，或按「看全圖」</span><button type="button" class="btn secondary sm" id="rfit2">看全圖</button></div>`;
  const world = $('#roworld'), svg = $('#rosvg');
  N.forEach((n) => { const d = document.createElement('div'); d.style.cssText = `position:absolute;left:${24 + n.c * 272}px;top:${n.y}px`; d.innerHTML = card({ ...n }).replace('nc static', 'nc'); const c = d.firstElementChild; c.style.position = 'relative'; c.setAttribute('tabindex', '0'); world.appendChild(d); n.x = 24 + n.c * 272; });
  svg.innerHTML = '<defs></defs>' + E.map(([f, t, l]) => { const a = N.find((n) => n.id === f), b = N.find((n) => n.id === t); const x1 = a.x + NW, y1 = a.y + NHS / 2, x2 = b.x, y2 = b.y + NHS / 2; const [mx, my] = mid(x1, y1, x2, y2); return `<path class="e" d="${bez(x1, y1, x2, y2)}" marker-end="url(#ah)"/>` + (l ? `<text x="${mx}" y="${my + 6}" text-anchor="middle" font-size="17" font-weight="600" fill="#57606a" paint-order="stroke" stroke="#f6f8fa" stroke-width="5">${l}</text>` : ''); }).join('');
  const sc = $('#roscroll');
  const apply = () => { host.style.setProperty('--zoom', Z); $('#robox').style.cssText = `width:${W * Z}px;height:${H * Z}px`; $('#rzp').textContent = Math.round(Z * 100) + '%'; $('#rzp').style.color = Z < 0.7 ? 'var(--color-warning-fg)' : ''; sync(); };
  const sync = () => host.classList.toggle('end', sc.scrollLeft + sc.clientWidth >= sc.scrollWidth - 4);
  sc.addEventListener('scroll', sync);
  const fit = () => { Z = Math.min(1, (sc.clientWidth - 8) / W); apply(); sc.scrollLeft = 0; $('#romsg').textContent = Z < 0.7 ? '縮小檢視中，文字偏小' : '已顯示全圖'; };
  $('#rfit').onclick = fit; $('#rfit2').onclick = fit; $('#rzi').onclick = () => { Z = Math.min(1.25, Z + 0.1); apply(); }; $('#rzo').onclick = () => { Z = Math.max(0.4, Z - 0.1); apply(); };
  let drag = null; sc.addEventListener('pointerdown', (e) => { if (e.target.closest('.nc')) return; drag = { x: e.clientX, l: sc.scrollLeft }; }); addEventListener('pointermove', (e) => { if (drag) sc.scrollLeft = drag.l - (e.clientX - drag.x); }); addEventListener('pointerup', () => (drag = null));
  apply(); addEventListener('resize', sync);
})();

/* ================= 規格圖：同一張卡在三處 ================= */
(function specs() {
  const put = (id, h) => { const e = document.getElementById(id); if (e) e.innerHTML = h; };
  put('f-edit', card({ type: 'msg', name: 'Reminder', sub: '歡迎回來，還在考慮嗎？' }));
  put('f-view', card({ type: 'msg', name: 'Reminder', sub: '歡迎回來，還在考慮嗎？', stats: [87, 12] }));
  put('f-trace', card({ type: 'msg', name: 'Reminder', sub: '歡迎回來，還在考慮嗎？', state: '目前在此', cls: 'current' }) + card({ type: 'wait', name: '等待 1 小時', sub: '1 小時', state: '已走過', cls: 'visited' }) + card({ type: 'end', name: '結束', sub: '旅程到此結束', state: '還沒走到', cls: 'unvisited' }));
  put('f-names', card({ type: 'wev', name: 'Wait for order (24 hours)', sub: '24 小時・符合 2 項條件', title: 'Wait for order (24 hours)' }) + card({ type: 'msg', name: 'Black Friday early-access reminder for returning VIP members', sub: 'LINE・提醒 1', title: 'Black Friday early-access reminder for returning VIP members' }) + card({ type: 'start', name: '開始', sub: '大於 500・購物車金額', catLabel: '進入條件' }));
  put('f-old', card({ type: 'msg', name: 'Reminder', sub: '歡迎回來，還在考慮嗎？', stats: [87, 12], cls: 'old' }) + card({ type: 'wev', name: 'Wait for order (24 hours)', sub: '24 小時', stats: [41, 7], cls: 'old' }));
})();

/* ================= 比較選單與條件試算 ================= */
(function compare() {
  const host = $('#cmp-new'); if (!host) return;
  const L = { GT: '大於（>）', GTE: '至少（≥）', LT: '小於（<）', LTE: '至多（≤）', EQ: '等於', NEQ: '不等於', CONTAINS: '包含', IN: '是其中之一', NOTIN: '不是其中任何一個', NN: '有填', NULL: '沒填' };
  const W = { GT: '大於', GTE: '至少', LT: '小於', LTE: '至多', EQ: '等於', NEQ: '不等於', CONTAINS: '包含', IN: '是其中之一', NOTIN: '不是其中任何一個', NN: '有填', NULL: '沒填' };
  const G = { num: [['數字比大小', ['GT', 'GTE', 'LT', 'LTE']], ['比對內容', ['EQ', 'NEQ']], ['有沒有填', ['NN', 'NULL']]], text: [['比對內容', ['EQ', 'NEQ', 'CONTAINS', 'IN', 'NOTIN']], ['有沒有填', ['NN', 'NULL']]], unk: [['數字比大小', ['GT', 'GTE', 'LT', 'LTE']], ['比對內容', ['EQ', 'NEQ', 'CONTAINS', 'IN', 'NOTIN']], ['有沒有填', ['NN', 'NULL']]] };
  const C = [
    { name: '購物車金額', code: 'properties.cart_total', t: 'num', op: 'GT', val: '500', sample: '650' },
    { name: '會員等級', code: 'trigger.properties.member_tier', t: 'text', op: 'EQ', val: 'gold', sample: 'silver' },
    { name: 'amount', code: 'properties.amount', t: 'unk', op: 'GT', val: '500', sample: '', manual: true },
  ];
  const isNum = (s) => /^-?\d+(\.\d+)?$/.test(String(s).trim());
  const pass = (c, raw) => { const v = String(raw ?? '').trim(); const num = c.t !== 'text' && ['GT', 'GTE', 'LT', 'LTE'].includes(c.op);
    if (c.op === 'NN') return v !== ''; if (c.op === 'NULL') return v === ''; if (v === '') return false;
    if (num) { if (!isNum(v) || !isNum(c.val)) return false; const a = +v, b = +c.val; return c.op === 'GT' ? a > b : c.op === 'GTE' ? a >= b : c.op === 'LT' ? a < b : a <= b; }
    const list = String(c.val).split(/[，,]/).map((s) => s.trim());
    return c.op === 'EQ' ? v === String(c.val).trim() : c.op === 'NEQ' ? v !== String(c.val).trim() : c.op === 'CONTAINS' ? v.includes(String(c.val).trim()) : c.op === 'IN' ? list.includes(v) : !list.includes(v); };
  const frag = (c) => ['NN', 'NULL'].includes(c.op) ? `${W[c.op]}` : `${W[c.op]} ${c.val || '…'}`;
  const examples = (c) => { const n = +c.val, ok = [], no = [];
    if (['GT', 'GTE', 'LT', 'LTE'].includes(c.op) && isNum(c.val)) { const big = n + Math.max(1, Math.round(Math.abs(n) * 0.2)), small = n - Math.max(1, Math.round(Math.abs(n) * 0.2));
      if (c.op === 'GT') { ok.push(big); no.push(`${n}（剛好等於不算）`, small); } if (c.op === 'GTE') { ok.push(`${n}（剛好等於也算）`, big); no.push(small); }
      if (c.op === 'LT') { ok.push(small); no.push(`${n}（剛好等於不算）`, big); } if (c.op === 'LTE') { ok.push(`${n}（剛好等於也算）`, small); no.push(big); }
      no.push('沒填'); }
    else if (c.op === 'EQ') { ok.push(c.val); no.push('其他任何值', '沒填'); } else if (c.op === 'NEQ') { ok.push('其他任何值'); no.push(c.val, '沒填'); }
    else if (c.op === 'CONTAINS') { ok.push(`含「${c.val}」的內容`); no.push('不含的內容', '沒填'); } else if (c.op === 'IN') { ok.push(...String(c.val).split(/[，,]/).map((s) => s.trim()).slice(0, 2)); no.push('清單以外的值', '沒填'); }
    else if (c.op === 'NN') { ok.push('任何有填的值'); no.push('沒填'); } else if (c.op === 'NULL') { ok.push('沒填'); no.push('任何有填的值'); } else { no.push('請先填完條件'); }
    return { ok, no }; };
  let last = 0; const state = C.map((c) => ({ ...c }));
  const render = () => {
    host.innerHTML = `<p class="sentence" aria-live="polite">客戶要<b>全部符合</b>：${state.map((c) => `<q>${esc(c.name)}</q>${esc(frag(c))}`).join('；')}。</p>` +
      state.map((c, i) => `<div class="cond"><div class="r1"><select aria-label="欄位" disabled style="opacity:1"><option>${esc(c.name)}${c.manual ? '（手動輸入）' : ''}</option></select><button type="button" class="rm" aria-label="移除這個條件">${icon('x')}</button></div>
        <div class="r2"><select class="cmp" data-i="${i}" aria-label="比較方式">${G[c.t].map(([g, ops]) => `<optgroup label="${g}">${ops.map((o) => `<option value="${o}" ${c.op === o ? 'selected' : ''}>${L[o]}</option>`).join('')}</optgroup>`).join('')}</select>${['NN', 'NULL'].includes(c.op) ? '' : `<input class="val" data-i="${i}" value="${esc(c.val)}" inputmode="${['GT', 'GTE', 'LT', 'LTE'].includes(c.op) ? 'decimal' : 'text'}" aria-label="比較的值" placeholder="${['GT', 'GTE', 'LT', 'LTE'].includes(c.op) ? '例：500' : '例：gold'}">`}</div>
        ${c.t === 'unk' ? `<p class="help">系統不知道「${esc(c.name)}」是數字還是文字。要比大小，請在比較欄選「數字比大小」那一組的項目（大於、至少、小於、至多），500 會當成數字比對。</p>` : ''}
        <details class="adv"><summary>進階資訊</summary><code>${esc(c.code)} ・ ${c.op === 'NN' ? 'IS_NOT_NULL' : c.op === 'NOTIN' ? 'NOT_IN' : c.op} ・ ${esc(c.val)}</code></details></div>`).join('');
    const tbody = $('#cmp-test'); const ex = examples(state[last]);
    tbody.innerHTML = `<div class="ex"><div class="y"><b>${icon('check')}會符合</b><ul>${examples(state[last]).ok.map((x) => `<li><q style="quotes:none">${esc(state[last].name)}</q> ${esc(x)}</li>`).join('')}</ul></div><div class="n"><b>${icon('x')}不會符合</b><ul>${ex.no.map((x) => `<li><q style="quotes:none">${esc(state[last].name)}</q> ${esc(x)}</li>`).join('')}</ul></div></div>
      <div><div class="try">${state.map((c, i) => `<label>${esc(c.name)}<input data-s="${i}" value="${esc(c.sample)}" placeholder="（沒填）"></label>`).join('')}</div></div><div id="verdict" role="status" aria-live="polite"></div>
      <p class="fine">只用你在這裡輸入的樣本判斷，不會讀取真實客戶，也不會改動旅程。</p>`;
    verdict();
  };
  const verdict = () => { const rs = state.map((c) => pass(c, c.sample)); const all = rs.every(Boolean);
    $('#verdict').innerHTML = `<div class="verdict ${all ? 'ok' : 'no'}">${icon(all ? 'check' : 'x')}<div>${all ? '這位客戶會符合：可以進入' : '這位客戶不會符合：不會進入'}<ul>${state.map((c, i) => `<li>${rs[i] ? '✓' : '✗'} ${esc(c.name)} ${esc(frag(c))}（你填的：${c.sample === '' ? '沒填' : esc(c.sample)}）</li>`).join('')}</ul></div></div>`; };
  host.addEventListener('change', (e) => { const i = e.target.dataset.i; if (e.target.classList.contains('cmp')) { last = +i; state[i].op = e.target.value; if (['NN', 'NULL'].includes(e.target.value)) state[i].val = ''; render(); } });
  host.addEventListener('input', (e) => { if (e.target.classList.contains('val')) { last = +e.target.dataset.i; state[e.target.dataset.i].val = e.target.value; const p = $('.sentence', host); p.innerHTML = `客戶要<b>全部符合</b>：${state.map((c) => `<q>${esc(c.name)}</q>${esc(frag(c))}`).join('；')}。`; const t = $('#cmp-test'); const keep = document.activeElement; const pos = keep.selectionStart; const idx = keep.dataset.i; renderTest(); const again = host.querySelector(`.val[data-i="${idx}"]`); again && again.focus() && again.setSelectionRange(pos, pos); void t; } });
  function renderTest() { const ex = examples(state[last]); const tb = $('#cmp-test'); tb.querySelector('.ex').innerHTML = `<div class="y"><b>${icon('check')}會符合</b><ul>${ex.ok.map((x) => `<li>${esc(state[last].name)} ${esc(x)}</li>`).join('')}</ul></div><div class="n"><b>${icon('x')}不會符合</b><ul>${ex.no.map((x) => `<li>${esc(state[last].name)} ${esc(x)}</li>`).join('')}</ul></div>`; verdict(); }
  $('#cmp-test').addEventListener('input', (e) => { if (e.target.dataset.s !== undefined) { state[e.target.dataset.s].sample = e.target.value; verdict(); } });
  render();
})();
