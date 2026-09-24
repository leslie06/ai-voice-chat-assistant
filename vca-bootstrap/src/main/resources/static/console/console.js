/*
 * 运营后台与商家后台共用的小框架: 取数、渲染、对话框、路由、公共组件。
 * 页面上显示的内容(门店资料、通话摘要、客户原话)一律 textContent 渲染, 不拼 innerHTML。
 */
(function () {
  'use strict';

  const C = {};
  let tokenKey = 'vca_console_token';

  // ---------- DOM ----------
  C.el = function (tag, attrs, ...children) {
    const e = document.createElement(tag);
    if (attrs) {
      for (const [k, v] of Object.entries(attrs)) {
        if (v == null || v === false) continue;
        if (k === 'class') e.className = v;
        else if (k === 'text') e.textContent = v;
        else if (k === 'on') for (const [ev, fn] of Object.entries(v)) e.addEventListener(ev, fn);
        else if (k === 'style' && typeof v === 'object') Object.assign(e.style, v);
        else if (k === 'value') e.value = v;
        else if (k === 'checked') e.checked = !!v;
        else e.setAttribute(k, v === true ? '' : v);
      }
    }
    for (const c of children.flat()) {
      if (c == null || c === false) continue;
      e.appendChild(typeof c === 'string' || typeof c === 'number' ? document.createTextNode(String(c)) : c);
    }
    return e;
  };
  const el = C.el;

  const ICONS = {
    home: 'M3 10.5 12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z',
    store: 'M3 9l1.5-5h15L21 9M3 9h18M3 9v11h18V9M9 20v-6h6v6',
    router: 'M4 14h16v6H4zM8 17h.01M12 17h.01M7 10a7 7 0 0 1 10 0M9.5 12a3.5 3.5 0 0 1 5 0',
    phone: 'M22 16.9v3a2 2 0 0 1-2.2 2 19.8 19.8 0 0 1-8.6-3.1 19.5 19.5 0 0 1-6-6A19.8 19.8 0 0 1 2.1 4.2 2 2 0 0 1 4.1 2h3a2 2 0 0 1 2 1.7c.1 1 .4 1.9.7 2.8a2 2 0 0 1-.5 2.1L8 9.9a16 16 0 0 0 6 6l1.3-1.3a2 2 0 0 1 2.1-.4c.9.3 1.8.6 2.8.7a2 2 0 0 1 1.7 2z',
    users: 'M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM22 21v-2a4 4 0 0 0-3-3.9M16 3.1a4 4 0 0 1 0 7.8',
    shield: 'M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z',
    file: 'M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8zM14 2v6h6M8 13h8M8 17h5',
    list: 'M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01',
    user: 'M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2M12 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z',
    logout: 'M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9',
    copy: 'M9 9h11v11H9zM5 15H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v1',
    plus: 'M12 5v14M5 12h14',
    refresh: 'M21 12a9 9 0 1 1-2.6-6.4M21 3v6h-6',
    check: 'M20 6 9 17l-5-5',
    x: 'M18 6 6 18M6 6l12 12',
    play: 'M6 4l14 8-14 8z',
    download: 'M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M7 10l5 5 5-5M12 15V3',
    info: 'M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20zM12 16v-4M12 8h.01',
    alert: 'M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0zM12 9v4M12 17h.01',
    chart: 'M3 3v18h18M7 16v-4M12 16V8M17 16v-7',
    book: 'M4 19.5A2.5 2.5 0 0 1 6.5 17H20V3H6.5A2.5 2.5 0 0 0 4 5.5zM4 19.5A2.5 2.5 0 0 0 6.5 22H20v-5',
    key: 'M21 2l-2 2m-7.6 7.6a5.5 5.5 0 1 1-7.8 7.8 5.5 5.5 0 0 1 7.8-7.8zm0 0L15.5 7.5m0 0 3 3L22 7l-3-3m-3.5 3.5L19 4',
    trash: 'M3 6h18M8 6V4a1 1 0 0 1 1-1h6a1 1 0 0 1 1 1v2M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6',
    edit: 'M12 20h9M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z',
    arrowLeft: 'M19 12H5M12 19l-7-7 7-7',
    upload: 'M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M17 8l-5-5-5 5M12 3v12',
    power: 'M18.4 6.6a9 9 0 1 1-12.8 0M12 2v10'
  };
  C.icon = function (name) {
    const ns = 'http://www.w3.org/2000/svg';
    const svg = document.createElementNS(ns, 'svg');
    svg.setAttribute('viewBox', '0 0 24 24');
    svg.setAttribute('class', 'i');
    const p = document.createElementNS(ns, 'path');
    p.setAttribute('d', ICONS[name] || ICONS.info);
    svg.appendChild(p);
    return svg;
  };

  // ---------- 取数 ----------
  C.setTokenKey = function (k) { tokenKey = k; };
  C.token = function () { try { return localStorage.getItem(tokenKey) || ''; } catch (e) { return ''; } };
  C.setToken = function (t) { try { t ? localStorage.setItem(tokenKey, t) : localStorage.removeItem(tokenKey); } catch (e) { /* 隐私模式 */ } };
  C.authHeaders = function (extra) {
    const h = Object.assign({}, extra || {});
    const t = C.token();
    if (t) h.Authorization = 'Bearer ' + t;
    return h;
  };
  C.onUnauthorized = function () { };
  C.api = async function (method, path, body) {
    try {
      const opt = { method, headers: C.authHeaders(body && !(body instanceof FormData) ? { 'Content-Type': 'application/json' } : {}) };
      if (body) opt.body = body instanceof FormData ? body : JSON.stringify(body);
      const r = await fetch(path, opt);
      let data = null;
      try { data = await r.json(); } catch (e) { /* 无正文 */ }
      if (r.status === 401) C.onUnauthorized();
      return { ok: r.ok, status: r.status, data, error: (data && data.error) || (r.ok ? '' : '请求失败(' + r.status + ')') };
    } catch (e) {
      return { ok: false, status: 0, data: null, error: '网络不通, 请稍后再试' };
    }
  };

  // ---------- 提示与对话框 ----------
  let toastBox;
  C.toast = function (msg, type) {
    if (!toastBox) { toastBox = el('div', { class: 'toasts' }); document.body.appendChild(toastBox); }
    const t = el('div', { class: 'toast' + (type === 'err' ? ' err' : ''), text: msg });
    toastBox.appendChild(t);
    setTimeout(() => t.remove(), type === 'err' ? 4200 : 2400);
  };

  /** 对话框。actions: [{label, kind:'primary'|'danger', onClick: async () => 返回 false 则不关}] */
  C.modal = function ({ title, body, actions, wide }) {
    const back = el('div', { class: 'modal-back' });
    const close = () => { back.remove(); document.removeEventListener('keydown', onKey); };
    const onKey = e => { if (e.key === 'Escape') close(); };
    const foot = el('div', { class: 'modal-f' });
    for (const a of (actions || [{ label: '关闭' }])) {
      const b = el('button', { class: 'btn' + (a.kind ? ' ' + a.kind : ''), text: a.label });
      b.addEventListener('click', async () => {
        if (!a.onClick) { close(); return; }
        b.disabled = true;
        try { if ((await a.onClick(b)) !== false) close(); } finally { b.disabled = false; }
      });
      foot.appendChild(b);
    }
    const x = el('button', { class: 'btn ghost icon', title: '关闭', on: { click: close } }, C.icon('x'));
    back.appendChild(el('div', { class: 'modal' + (wide ? ' wide' : ''), role: 'dialog' },
      el('div', { class: 'modal-h' }, el('h3', { text: title }), x),
      el('div', { class: 'modal-b' }, body),
      foot));
    back.addEventListener('mousedown', e => { if (e.target === back) close(); });
    document.addEventListener('keydown', onKey);
    document.body.appendChild(back);
    const first = back.querySelector('input,select,textarea');
    if (first) setTimeout(() => first.focus(), 30);
    return close;
  };

  C.confirm = function (text, { title = '确认', okLabel = '确定', danger = false } = {}) {
    return new Promise(resolve => {
      let done = false;
      C.modal({
        title, body: el('p', { text, style: { margin: '4px 0 0', color: 'var(--sub)' } }),
        actions: [
          { label: '取消', onClick: () => { done = true; resolve(false); } },
          { label: okLabel, kind: danger ? 'danger' : 'primary', onClick: () => { done = true; resolve(true); } }
        ]
      });
      // 按 Esc 或点遮罩关掉的也算取消
      const obs = new MutationObserver(() => {
        if (!document.querySelector('.modal-back')) { obs.disconnect(); if (!done) resolve(false); }
      });
      obs.observe(document.body, { childList: true });
    });
  };

  C.copy = async function (text) {
    try { await navigator.clipboard.writeText(text); C.toast('已复制'); }
    catch (e) {
      const ta = el('textarea', { value: text, style: { position: 'fixed', opacity: '0' } });
      document.body.appendChild(ta); ta.select();
      try { document.execCommand('copy'); C.toast('已复制'); } catch (e2) { C.toast('复制失败, 请手动选中', 'err'); }
      ta.remove();
    }
  };
  C.copyBtn = function (text, label) {
    return el('button', { class: 'btn sm ghost', title: '复制', on: { click: () => C.copy(text) } }, C.icon('copy'), label || null);
  };

  // ---------- 格式 ----------
  C.time = function (iso) {
    if (!iso) return '';
    const d = new Date(iso);
    if (isNaN(d)) return iso.replace('T', ' ').slice(0, 16);
    const now = new Date();
    const hm = d.toTimeString().slice(0, 5);
    const sameDay = d.toDateString() === now.toDateString();
    const y = new Date(now); y.setDate(now.getDate() - 1);
    if (sameDay) return '今天 ' + hm;
    if (d.toDateString() === y.toDateString()) return '昨天 ' + hm;
    return (d.getMonth() + 1) + '月' + d.getDate() + '日 ' + hm;
  };
  C.duration = function (sec) {
    sec = sec || 0;
    return sec < 60 ? sec + ' 秒' : Math.floor(sec / 60) + ' 分 ' + (sec % 60) + ' 秒';
  };
  C.INTENTS = { A: '已约 / 要回电', B: '有兴趣', C: '只是问问', D: '无效来电' };
  C.INTENT_COLORS = { A: '#e5484d', B: '#f5a524', C: '#9aa4af', D: '#d6dbe0' };

  C.downloadCsv = function (filename, header, rows) {
    const esc = v => '"' + String(v == null ? '' : v).replace(/"/g, '""') + '"';
    const csv = '﻿' + [header, ...rows].map(r => r.map(esc).join(',')).join('\n');
    const a = el('a', { href: URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' })), download: filename });
    document.body.appendChild(a); a.click(); a.remove();
  };

  // ---------- 路由 ----------
  /** routes: [[正则, 渲染函数(匹配组)]]; 渲染函数自己往 C.view 里填内容 */
  C.router = function (routes, fallback) {
    const go = () => {
      const h = location.hash.replace(/^#/, '') || fallback;
      for (const [re, fn] of routes) {
        const m = h.match(re);
        if (m) { fn(...m.slice(1)); window.scrollTo(0, 0); return; }
      }
      location.hash = fallback;
    };
    window.addEventListener('hashchange', go);
    go();
  };

  // ---------- 骨架 ----------
  /**
   * 左侧导航 + 主区。nav: [{id, label, icon, href}]; 返回 { page(title, subtitle, actions) → 内容容器, setNav(id), foot }
   */
  C.shell = function ({ brand, sub, nav, user, onLogout, sideExtra }) {
    document.body.textContent = '';
    const navBox = el('nav', { class: 'nav' });
    const links = {};
    for (const n of nav) {
      if (n === '-') { navBox.appendChild(el('div', { class: 'nav-sep' })); continue; }
      links[n.id] = el('a', { href: n.href }, C.icon(n.icon), n.label);
      navBox.appendChild(links[n.id]);
    }
    const initials = (user || '?').replace(/\D/g, '').slice(-2) || '?';
    const side = el('aside', { class: 'side' },
      el('div', { class: 'brand' }, el('div', { class: 'brand-mark', text: '鱼' }),
        el('div', null, el('div', { class: 'brand-name', text: brand }), el('div', { class: 'brand-sub', text: sub }))),
      sideExtra || null,
      navBox,
      el('div', { class: 'side-foot' },
        el('div', { class: 'avatar', text: initials }),
        el('div', { class: 'who' }, user || '', el('small', { text: '已登录' })),
        el('button', { class: 'btn ghost icon', title: '退出登录', on: { click: onLogout } }, C.icon('logout'))));
    const main = el('main', { class: 'main' });
    document.body.appendChild(el('div', { class: 'shell' }, side, main));
    return {
      setNav(id) { for (const [k, a] of Object.entries(links)) a.classList.toggle('on', k === id); },
      page(title, subtitle, actions, back) {
        main.textContent = '';
        const head = el('div', null,
          back ? el('a', { class: 'back', href: back.href }, C.icon('arrowLeft'), back.label) : null,
          el('h1', { text: title }), subtitle ? el('p', { text: subtitle }) : null);
        main.appendChild(el('header', { class: 'top' }, head, actions ? el('div', { class: 'actions' }, actions) : null));
        const content = el('div', { class: 'content' });
        main.appendChild(content);
        document.title = title + ' · ' + brand;
        return content;
      }
    };
  };

  /** 登录页。onLogin(me) 在登录成功且通过 check(me) 后调用; check 返回错误文字表示不允许进 */
  C.loginPage = function ({ brand, title, lead, check, onLogin, foot }) {
    document.body.textContent = '';
    document.title = title + ' · ' + brand;
    const phone = el('input', { class: 'input', type: 'tel', autocomplete: 'username', placeholder: '手机号' });
    const pw = el('input', { class: 'input', type: 'password', autocomplete: 'current-password', placeholder: '密码' });
    const err = el('div', { class: 'err' });
    const btn = el('button', { class: 'btn primary', type: 'submit', text: '登录' });
    const form = el('form', {
      on: {
        submit: async e => {
          e.preventDefault();
          err.textContent = '';
          if (!phone.value.trim() || !pw.value) { err.textContent = '请输入手机号和密码'; return; }
          btn.disabled = true; btn.textContent = '登录中…';
          const r = await C.api('POST', '/api/login', { username: phone.value.trim(), password: pw.value });
          if (!r.ok || !r.data || !r.data.token) {
            btn.disabled = false; btn.textContent = '登录';
            err.textContent = r.error || '登录失败'; return;
          }
          C.setToken(r.data.token);
          const me = await C.api('GET', '/api/me');
          const msg = me.ok ? check(me.data) : (me.error || '登录失败');
          if (msg) { C.setToken(''); btn.disabled = false; btn.textContent = '登录'; err.textContent = msg; return; }
          onLogin(me.data, phone.value.trim());
        }
      }
    },
      el('div', { class: 'field' }, el('label', { text: '手机号' }), phone),
      el('div', { class: 'field' }, el('label', { text: '密码' }), pw),
      btn, err);
    document.body.appendChild(el('div', { class: 'login-page' },
      el('div', { class: 'login-card' },
        el('div', { class: 'brand' }, el('div', { class: 'brand-mark', text: '鱼' }),
          el('div', null, el('div', { class: 'brand-name', text: brand }), el('div', { class: 'brand-sub', text: 'AI 电话客服' }))),
        el('h1', { text: title }), el('p', { class: 'lead', text: lead }), form,
        foot ? el('div', { class: 'foot', text: foot }) : null)));
    setTimeout(() => phone.focus(), 30);
  };

  // ---------- 公共组件 ----------
  C.stat = function (label, value, foot, icon, unit) {
    return el('div', { class: 'stat' },
      el('div', { class: 'label' }, icon ? C.icon(icon) : null, label),
      el('div', { class: 'value' }, String(value), unit ? el('small', { text: unit }) : null),
      foot ? el('div', { class: 'foot', text: foot }) : null);
  };
  C.card = function (title, body, { hint, actions, flush } = {}) {
    return el('section', { class: 'card' },
      title ? el('div', { class: 'card-h' }, el('h2', null, title, hint ? el('span', { class: 'hint', text: hint }) : null),
        actions ? el('div', { class: 'toolbar' }, actions) : null) : null,
      el('div', { class: 'card-b' + (flush ? ' flush' : '') }, body));
  };
  C.empty = function (title, text, icon) {
    return el('div', { class: 'empty' }, C.icon(icon || 'info'), el('b', { text: title }), text ? el('div', { text }) : null);
  };
  C.pill = function (text, kind) { return el('span', { class: 'pill ' + (kind || ''), text }); };
  C.loading = function () { return el('div', { class: 'skeleton', text: '加载中…' }); };

  /** 近 N 天每天的来电数 */
  C.bars = function (daily) {
    const max = Math.max(1, ...daily.map(d => d.calls));
    const box = el('div', { class: 'bars' });
    const step = daily.length > 14 ? Math.ceil(daily.length / 10) : 1;
    daily.forEach((d, i) => {
      const h = Math.round(d.calls / max * 96);
      const label = d.date.slice(5).replace('-', '/');
      box.appendChild(el('div', { class: 'bar', title: label + ': ' + d.calls + ' 通, 留资 ' + d.leads + ' 条' },
        el('div', { class: 'col' + (d.calls ? '' : ' zero'), style: { height: Math.max(2, h) + 'px' } }),
        el('div', { class: 'd', text: (i % step === 0 || i === daily.length - 1) ? label : '' })));
    });
    return box;
  };

  C.intentBar = function (intents) {
    const total = Object.values(intents).reduce((a, b) => a + b, 0);
    const bar = el('div', { class: 'intent-bar' });
    const legend = el('div', { class: 'legend' });
    for (const k of ['A', 'B', 'C', 'D']) {
      const n = intents[k] || 0;
      if (total) bar.appendChild(el('span', { style: { width: (n / total * 100) + '%', background: C.INTENT_COLORS[k] } }));
      legend.appendChild(el('span', null, el('i', { style: { background: C.INTENT_COLORS[k] } }), k + ' ' + C.INTENTS[k] + ' · ' + n));
    }
    return el('div', null, bar, legend);
  };

  /** 录音: audio 标签带不了令牌, 用 fetch 带令牌取回再播; 同一时间只留一段 */
  let currentAudio = null;
  C.playRecording = async function (url, container, btn) {
    btn.disabled = true;
    const old = btn.textContent;
    btn.textContent = '加载中…';
    try {
      const r = await fetch(url, { headers: C.authHeaders() });
      if (!r.ok) { btn.textContent = '录音已过保留期'; return; }
      if (currentAudio) { URL.revokeObjectURL(currentAudio.src); currentAudio.remove(); }
      const audio = el('audio', { controls: true });
      audio.src = URL.createObjectURL(await r.blob());
      currentAudio = audio;
      container.appendChild(audio);
      btn.remove();
      audio.play().catch(() => { });
    } catch (e) { btn.textContent = old; btn.disabled = false; C.toast('加载录音失败', 'err'); }
  };

  /** 通话流。recordingUrl(row) 返回回放地址, 返回空则不给听 */
  C.callFeed = function (rows, { recordingUrl, showStore } = {}) {
    if (!rows.length) return C.empty('还没有通话', '10 秒以上的来电会在这里出现, 附带 AI 写的小结', 'phone');
    const box = el('div', { class: 'feed' });
    for (const c of rows) {
      const body = el('div', { class: 'body' },
        el('div', { class: 'meta' },
          el('span', { text: C.time(c.at) }),
          showStore && c.store ? el('span', { class: 'pill plain', text: c.store }) : null,
          el('span', { text: c.peerNumber || '未知号码' }),
          el('span', { text: C.duration(c.durationSec) }),
          el('span', { text: C.INTENTS[c.intent] || '' })),
        c.summary ? el('div', { class: 'text', text: c.summary }) : null,
        c.followUp ? el('div', { class: 'follow', text: '待跟进: ' + c.followUp }) : null);
      const url = c.hasRecording && recordingUrl ? recordingUrl(c) : null;
      if (url) {
        const b = el('button', { class: 'btn sm', style: { marginTop: '8px' } }, C.icon('play'), '听录音');
        b.addEventListener('click', () => C.playRecording(url, body, b));
        body.appendChild(b);
      }
      box.appendChild(el('div', { class: 'item' }, el('span', { class: 'intent ' + (c.intent || ''), text: c.intent || '·' }), body));
    }
    return box;
  };

  C.leadTable = function (rows, { showStore } = {}) {
    if (!rows.length) return C.empty('还没有留资', '客户在电话里留下称呼、电话和想办的事, AI 会记在这里', 'users');
    const tb = el('tbody');
    for (const l of rows) {
      tb.appendChild(el('tr', null,
        el('td', { class: 'sub', text: C.time(l.at) }),
        showStore ? el('td', { text: l.store || '' }) : null,
        el('td', { class: 'strong', text: l.name || '未留称呼' }),
        el('td', { class: 'num', text: l.phone || l.peerNumber || '' }),
        el('td', { text: l.intent || '' }),
        el('td', { text: l.preferredTime || '' }),
        el('td', { class: 'sub', text: l.note || '' })));
    }
    return el('div', { class: 'table-wrap' }, el('table', { class: 't' },
      el('thead', null, el('tr', null, el('th', { text: '时间' }), showStore ? el('th', { text: '门店' }) : null,
        el('th', { text: '称呼' }), el('th', { text: '电话' }), el('th', { text: '想办的事' }),
        el('th', { text: '期望时间' }), el('th', { text: '备注' }))), tb));
  };
  C.leadsCsv = function (rows, name) {
    C.downloadCsv(name, ['时间', '门店', '称呼', '电话', '来电号码', '想办的事', '期望时间', '备注'],
      rows.map(l => [l.at ? l.at.replace('T', ' ').slice(0, 16) : '', l.store || '', l.name, l.phone, l.peerNumber,
        l.intent, l.preferredTime, l.note]));
  };

  // ---------- 门店资料表单(运营与商家共用) ----------
  const PLACEHOLDERS = {
    dental: {
      name: '美好口腔东城店', greeting: '您好，这里是美好口腔，请问有什么可以帮您？',
      services: '洗牙 200-400 元\n种植牙 6800 元起，含种植体、基台和牙冠\n正畸 12000 元起',
      staff: '张伟，种植科主任，从业 15 年', booking: '留下称呼、手机号和希望的时间段，前台 1 小时内回电确认；改期请提前 4 小时',
      notes: '停车怎么免费、医保哪些能报、常见顾虑怎么答……', prompt: '称呼客户为您；不承诺任何治疗效果；被问到别家诊所一律不评价'
    },
    education: {
      name: '启明少儿英语（望京校区）', greeting: '您好，这里是启明少儿英语，请问有什么可以帮您？',
      services: '少儿英语启蒙班（3-6 岁）每课时 150 元\n小学同步班 每课时 180 元\n雅思冲刺班 12000 元/期',
      staff: '李老师，剑桥少儿英语考官，8 年教龄', booking: '免费试听需提前一天预约，留下家长称呼、孩子年龄和手机号；开课后 3 次课内可全额退费',
      notes: '寒暑假班几月开、校区有没有停车位、教材是否另收费……', prompt: '称呼家长为您；不承诺提分或通过率；不评价其他机构'
    },
    generic: {
      name: '门店名称', greeting: '您好，这里是××，请问有什么可以帮您？',
      services: '产品或服务名 价格\n一行一项', staff: '姓名，职务，一句话介绍', booking: '预约或办理要留什么信息、提前多久、能不能改期',
      notes: '常见问题怎么答、哪些事要转给同事……', prompt: '称呼客户为您；不确定的事不要承诺'
    }
  };

  let industriesCache = null;
  C.industries = async function () {
    if (industriesCache) return industriesCache;
    const r = await C.api('GET', '/api/merchants/industries');
    industriesCache = r.ok && Array.isArray(r.data) && r.data.length ? r.data
      : [{ code: 'generic', label: '其他商家', servicesLabel: '产品/服务与价格', staffLabel: '团队成员', bookingLabel: '预约与办理' }];
    return industriesCache;
  };

  /**
   * 门店资料表单。operator=true 时接入号、转人工分机、音色、热词表可改(运营), 否则只读显示。
   * 返回 { el, value() }。
   */
  C.profileForm = function (p, industries, { operator = false } = {}) {
    // 转人工在库里是拨号串 user/8012@vca.local, 给人看只显示分机号(保存时服务端会补回拨号串)
    const ext = /^user\/(\d+)@/.exec(p.transferDialString || '');
    if (ext) p = Object.assign({}, p, { transferDialString: ext[1] });
    const f = {};
    const input = (key, attrs) => (f[key] = el('input', Object.assign({ class: 'input', value: p[key] || '' }, attrs || {})));
    const area = (key, rows) => (f[key] = el('textarea', { class: 'textarea', rows: rows || 4, value: p[key] || '' }));
    const field = (label, control, { hint, full, opt } = {}) => el('div', { class: 'field' + (full ? ' full' : '') },
      el('label', null, label, opt ? el('span', { class: 'opt', text: opt }) : null), control,
      hint ? el('div', { class: 'hint', text: hint }) : null);

    f.industry = el('select', { class: 'select' });
    for (const i of industries) f.industry.appendChild(el('option', { value: i.code, text: i.label }));
    f.industry.value = p.industry || industries[0].code;
    f.enabled = el('input', { type: 'checkbox', checked: p.enabled !== false });

    const labels = {};
    const lab = (key, text) => (labels[key] = el('span', { text }));
    const applyIndustry = () => {
      const ind = industries.find(i => i.code === f.industry.value) || industries[0];
      const ph = PLACEHOLDERS[f.industry.value] || PLACEHOLDERS.generic;
      labels.services.textContent = ind.servicesLabel + '（一行一项）';
      labels.staff.textContent = ind.staffLabel + '（一行一人）';
      labels.booking.textContent = ind.bookingLabel;
      f.name.placeholder = ph.name; f.greeting.placeholder = ph.greeting + '（留空按店名生成）';
      f.services.placeholder = ph.services; f.staff.placeholder = ph.staff; f.bookingRules.placeholder = ph.booking;
      f.notes.placeholder = ph.notes; f.systemPrompt.placeholder = ph.prompt;
    };

    const opField = (key, label, hint, placeholder) => field(label,
      input(key, operator ? { placeholder } : { readonly: true, title: '由运营配置, 需要修改请联系运营' }),
      { hint: operator ? hint : (key === 'number' ? '由运营配置。系统内部给门店的编号，客户不会拨它，也不是门店的对外电话' : '由运营配置') });

    const form = el('div', { class: 'form' },
      el('div', { class: 'section-title', text: '基本信息' }),
      field('门店名称', input('name')),
      field('行业', f.industry, { hint: '决定 AI 的接待路数: 诊所约面诊、培训机构约试听' }),
      field('开场白', input('greeting'), { full: true, opt: '可不填',
        hint: '接通后 AI 说的第一句话。系统会自动补上"智能助理接听、会录音"的告知（法规要求），不用自己写' }),
      field('启用 AI 接听', el('label', { class: 'switch' }, f.enabled, el('span', { class: 'track' }),
        el('span', { class: 'muted', text: '关掉后来电按系统默认方式处理' }))),
      el('div', { class: 'section-title', text: '营业信息' }),
      field('营业时间', input('businessHours', { placeholder: '每天 9:00-20:00，除夕初一休息' })),
      field('对外电话', input('phone', { placeholder: '010-8888-6666' }), { opt: 'AI 报给客户的号码' }),
      field('地址', input('address', { placeholder: '东城区东直门南大街 12 号美好大厦 2 层' }), { full: true }),
      field('交通与停车', input('transport', { placeholder: '地铁 2 号线东直门 C 口步行 5 分钟；大厦地下车库免 2 小时' }), { full: true }),
      el('div', { class: 'section-title', text: '服务与预约' }),
      field(lab('services', ''), area('services', 5), { full: true, hint: '价格以这里为准, AI 不会编造这里没有的信息' }),
      field(lab('staff', ''), area('staff', 3), { full: true }),
      field(lab('booking', ''), area('bookingRules', 3), { full: true }),
      field('其他要让 AI 知道的', area('notes', 3), { full: true }),
      field('语气与禁忌', area('systemPrompt', 3), { full: true, opt: '可不填' }),
      el('div', { class: 'section-title', text: '通知' }),
      field('通话小结推送', input('summaryWebhook', { placeholder: 'https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=…' }),
        { full: true, hint: '每通电话结束后, AI 写的小结会推到这个企业微信/钉钉群。在群设置里添加"群机器人", 复制它的 Webhook 地址粘贴到这里' }),
      el('div', { class: 'section-title', text: operator ? '线路（运营）' : '线路' }),
      opField('number', '接入号', '系统内部给门店的编号，客户不会拨它，也不是门店的对外电话。用系统建议的即可；装网关时 HT813 的"转 VoIP 号码"填它', '5000'),
      opField('transferDialString', '转人工分机', '网关话机口的分机号; 开通网关时会自动填上', '8012'),
      operator ? opField('ttsVoice', '音色', '留空用系统默认', '留空用系统默认') : null,
      operator ? opField('asrVocabularyId', '识别热词表 ID', '一般留空, 用本行业自动维护的热词表', '留空自动') : null);

    f.industry.addEventListener('change', applyIndustry);
    applyIndustry();
    return {
      el: form,
      value() {
        const v = {};
        for (const [k, e] of Object.entries(f)) v[k] = e.type === 'checkbox' ? e.checked : e.value.trim();
        return Object.assign({}, p, v);
      }
    };
  };

  window.Console = C;
})();
