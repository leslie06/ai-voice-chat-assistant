/* 商家后台: 门店资料、知识库、通话与线索、账号。接口见 MerchantRoutes(/api/merchants/**)、KnowledgeRoutes、AccountRoutes。 */
(function () {
  'use strict';
  const C = window.Console;
  const el = C.el;
  const BRAND = '飞鱼电话客服';
  C.setTokenKey('vca_merchant_token');

  let ui = null;
  let me = null;
  let industries = [];
  let stores = [];
  let shop = null;

  // ---------- 启动与登录 ----------
  async function boot() {
    C.onUnauthorized = () => { C.setToken(''); showLogin(); };
    if (C.token()) {
      const r = await C.api('GET', '/api/me');
      if (r.ok && r.data) { start(r.data); return; }
      C.setToken('');
    }
    showLogin();
  }

  function showLogin() {
    C.loginPage({
      brand: BRAND, title: '商家后台', lead: '维护门店资料, 查看 AI 接的每一通电话',
      check: () => '',
      onLogin: (m, phone) => { m.phone = phone; start(m); },
      foot: '账号由运营开通; 忘记密码请联系运营重置'
    });
  }

  async function start(m) {
    me = m;
    const [ind, list] = await Promise.all([C.industries(), C.api('GET', '/api/merchants')]);
    industries = ind;
    stores = list.ok ? list.data : [];
    let saved = null;
    try { saved = localStorage.getItem('vca_merchant_store'); } catch (e) { /* 隐私模式 */ }
    shop = stores.find(s => String(s.id) === saved) || stores[0] || null;

    let switcher = null;
    if (stores.length > 1) {
      switcher = el('div', { class: 'store-switch' }, el('select', {
        class: 'select', on: {
          change: e => {
            shop = stores.find(s => String(s.id) === e.target.value);
            try { localStorage.setItem('vca_merchant_store', e.target.value); } catch (x) { /* 隐私模式 */ }
            window.dispatchEvent(new HashChangeEvent('hashchange'));
          }
        }
      }, stores.map(s => el('option', { value: s.id, text: (s.name || '未命名') + ' · ' + s.number }))));
      switcher.firstChild.value = shop.id;
    }
    ui = C.shell({
      brand: BRAND, sub: shop ? (shop.name || '商家后台') : '商家后台', user: m.phone || m.username, sideExtra: switcher,
      nav: [
        { id: 'overview', label: '概览', icon: 'home', href: '#/overview' },
        { id: 'profile', label: '门店资料', icon: 'store', href: '#/profile' },
        { id: 'knowledge', label: '知识库', icon: 'book', href: '#/knowledge' },
        { id: 'calls', label: '通话记录', icon: 'phone', href: '#/calls' },
        { id: 'leads', label: '线索', icon: 'users', href: '#/leads' },
        '-',
        { id: 'account', label: '账号', icon: 'key', href: '#/account' }
      ],
      onLogout: () => { C.setToken(''); location.hash = ''; showLogin(); }
    });
    const needShop = fn => (...a) => shop ? fn(...a) : noShop();
    C.router([
      [/^\/overview$/, needShop(overview)],
      [/^\/profile$/, needShop(profile)],
      [/^\/knowledge$/, knowledge],
      [/^\/calls$/, needShop(calls)],
      [/^\/leads$/, needShop(leads)],
      [/^\/account$/, account]
    ], '/overview');
  }

  function noShop() {
    ui.setNav('overview');
    const box = ui.page('欢迎使用');
    box.appendChild(C.card(null, C.empty('这个账号下还没有门店',
      'AI 电话客服由运营开通: 装好语音网关、分配接入号后, 门店会出现在这里。请联系运营。', 'store')));
  }

  const recordingUrl = c => '/api/merchants/' + shop.id + '/calls/' + encodeURIComponent(c.callId) + '/recording';

  // ---------- 概览 ----------
  async function overview() {
    ui.setNav('overview');
    const box = ui.page(shop.name || '我的门店', '接入号 ' + shop.number);
    box.appendChild(C.loading());
    const [st, gw, ld] = await Promise.all([
      C.api('GET', '/api/merchants/' + shop.id + '/stats?days=30'),
      C.api('GET', '/api/merchants/' + shop.id + '/gateway'),
      C.api('GET', '/api/merchants/' + shop.id + '/leads?days=30')]);
    box.textContent = '';
    if (!st.ok) { box.appendChild(C.card(null, C.empty('加载失败', st.error, 'alert'))); return; }
    const s = st.data;
    const week = s.daily.slice(-7);
    const weekCalls = week.reduce((a, d) => a + d.calls, 0);
    const today = s.daily[s.daily.length - 1] || { calls: 0, leads: 0 };

    box.appendChild(lineNotice(gw.ok ? gw.data : null));
    box.appendChild(el('div', { class: 'stats' },
      C.stat('今日来电', today.calls, '留资 ' + today.leads + ' 条', 'phone', '通'),
      C.stat('近 7 天来电', weekCalls, '近 30 天共 ' + s.calls + ' 通', 'chart', '通'),
      C.stat('高意向客户', s.intents.A || 0, '近 30 天已约或要回电', 'users', '位'),
      C.stat('留资', s.leads, '近 30 天 AI 记下的线索', 'list', '条')));
    box.appendChild(el('div', { class: 'grid side-wide' },
      C.card('近 30 天来电', C.bars(s.daily), { hint: '10 秒以上、有小结的通话' }),
      C.card('来电意向', C.intentBar(s.intents))));
    const recent = ld.ok ? ld.data.slice(0, 5) : [];
    box.appendChild(C.card('最新线索', C.leadTable(recent), {
      flush: true, actions: [el('a', { class: 'btn sm ghost', href: '#/leads', text: '全部线索' })]
    }));
  }

  /** 线路状态: 客户能不能打进来, 是商家最关心的一件事 */
  function lineNotice(g) {
    if (!shop.enabled) {
      return el('div', { class: 'notice warn' }, C.icon('alert'),
        el('div', null, el('b', { text: 'AI 接听已关闭。' }), ' 到"门店资料"里打开"启用 AI 接听"即可恢复。'));
    }
    if (!g || !g.opened) {
      return el('div', { class: 'notice' }, C.icon('info'), el('div', { text: '语音网关还没开通, 客户暂时打不进来。请联系运营安装。' }));
    }
    if (!g.lineOnline) {
      return el('div', { class: 'notice danger' }, C.icon('alert'), el('div', null, el('b', { text: '语音网关不在线, 客户打进来 AI 接不到。' }),
        ' 请检查网关的电源和网线, 重新插上后一两分钟内会自动恢复; 仍不行请联系运营。'));
    }
    return el('div', { class: 'notice' }, C.icon('check'), el('div', null, el('b', { text: 'AI 正在接听。' }),
      ' 电话线已接入' + (g.phoneOnline ? ', 转人工时前台话机 ' + g.phoneExtension + ' 会响。' : '; 前台话机不在线, 转人工暂时转不过去。')));
  }

  // ---------- 门店资料 ----------
  function profile() {
    ui.setNav('profile');
    const box = ui.page('门店资料', 'AI 接电话时就照这里说。改完保存, 下一通电话就生效');
    const form = C.profileForm(shop, industries, { operator: false });
    const save = el('button', { class: 'btn primary' }, '保存');
    save.addEventListener('click', async () => {
      const v = form.value();
      if (!v.name) { C.toast('请填写门店名称', 'err'); return; }
      save.disabled = true;
      const r = await C.api('PUT', '/api/merchants/' + shop.id, v);
      save.disabled = false;
      if (!r.ok) { C.toast(r.error, 'err'); return; }
      Object.assign(shop, r.data);
      C.toast('已保存, 下一通电话即生效');
    });
    box.appendChild(el('section', { class: 'card' },
      el('div', { class: 'card-b' }, form.el),
      el('div', { class: 'form-foot' },
        el('button', { class: 'btn', on: { click: preview } }, C.icon('file'), '预览 AI 拿到的资料'), save)));
  }

  async function preview() {
    const r = await C.api('GET', '/api/merchants/' + shop.id + '/preview');
    if (!r.ok) { C.toast(r.error, 'err'); return; }
    C.modal({
      title: 'AI 拿到的资料', wide: true, body: el('div', null,
        el('p', { class: 'muted', style: { marginTop: 0 }, text: '这是按已保存的资料生成的。改了还没保存的内容, 保存后再预览。' }),
        el('div', { class: 'field' }, el('label', { text: '开场白（客户接通后听到的第一句）' }), el('div', { class: 'preview', text: r.data.greeting })),
        el('div', { class: 'field', style: { marginTop: '14px' } }, el('label', { text: '门店资料' }),
          el('div', { class: 'preview', text: r.data.prompt || '（资料为空, AI 只会用通用话术）' })))
    });
  }

  // ---------- 知识库 ----------
  async function knowledge() {
    ui.setNav('knowledge');
    const file = el('input', { type: 'file', accept: '.txt,.md,.pdf', style: { display: 'none' } });
    const upload = el('button', { class: 'btn primary', on: { click: () => file.click() } }, C.icon('upload'), '上传文档');
    const box = ui.page('知识库', '门店资料放不下的长篇内容: 价目表、常见问题、项目介绍。AI 回答时会检索', [upload]);
    box.appendChild(file);
    const list = el('div');
    box.appendChild(el('div', { class: 'notice' }, C.icon('info'),
      el('div', { text: '支持 txt、md、pdf, 单个 5MB 以内。写成"一问一答"或分条列清楚效果最好。营业时间、地址、价格这类常问的, 优先填在"门店资料"里, 更快更准。' })));
    box.appendChild(C.card('已上传', list, { flush: true }));
    const load = async () => {
      list.textContent = '';
      list.appendChild(C.loading());
      const r = await C.api('GET', '/api/knowledge');
      list.textContent = '';
      if (!r.ok) { list.appendChild(C.empty('加载失败', r.error === '请求失败(404)' ? '知识库功能没有启用' : r.error, 'alert')); return; }
      if (!r.data.length) { list.appendChild(C.empty('还没有文档', '点右上角"上传文档"', 'book')); return; }
      const tb = el('tbody');
      for (const d of r.data) {
        tb.appendChild(el('tr', null,
          el('td', { class: 'strong', text: d.title || d.name || '文档' }),
          el('td', { class: 'sub', text: C.time(d.createdAt) }),
          el('td', { class: 'act' }, el('button', {
            class: 'btn sm danger', text: '删除', on: {
              click: async () => {
                if (!await C.confirm('删除「' + (d.title || '文档') + '」? AI 将不再参考它。', { danger: true, okLabel: '删除' })) return;
                const x = await C.api('DELETE', '/api/knowledge/' + d.id);
                if (x.ok) { C.toast('已删除'); load(); } else C.toast(x.error, 'err');
              }
            }
          }))));
      }
      list.appendChild(el('div', { class: 'table-wrap' }, el('table', { class: 't' },
        el('thead', null, el('tr', null, el('th', { text: '文档' }), el('th', { text: '上传时间' }), el('th'))), tb)));
    };
    file.addEventListener('change', async () => {
      const f = file.files[0];
      file.value = '';
      if (!f) return;
      if (f.size > 5 * 1024 * 1024) { C.toast('文件超过 5MB', 'err'); return; }
      upload.disabled = true; upload.lastChild.textContent = '上传中…';
      const fd = new FormData();
      fd.append('file', f);
      const r = await C.api('POST', '/api/knowledge', fd);
      upload.disabled = false; upload.lastChild.textContent = '上传文档';
      if (r.ok) { C.toast('已上传, 切成 ' + (r.data.chunks || 0) + ' 段'); load(); } else C.toast(r.error, 'err');
    });
    load();
  }

  // ---------- 通话与线索 ----------
  function daysSelect(def, onChange) {
    const sel = el('select', { class: 'select', style: { width: 'auto' } },
      [[1, '今天'], [7, '近 7 天'], [30, '近 30 天'], [90, '近 90 天']].map(([v, t]) => el('option', { value: v, text: t })));
    sel.value = def;
    sel.addEventListener('change', onChange);
    return sel;
  }

  function calls() {
    ui.setNav('calls');
    const list = el('div');
    const days = daysSelect(30, () => load());
    const box = ui.page('通话记录', '10 秒以上的来电, 附 AI 写的小结; 录音保留一段时间后自动删除', [days]);
    box.appendChild(C.card(null, list, { flush: true }));
    const load = async () => {
      list.textContent = '';
      list.appendChild(C.loading());
      const r = await C.api('GET', '/api/merchants/' + shop.id + '/calls?days=' + days.value);
      list.textContent = '';
      list.appendChild(r.ok ? C.callFeed(r.data, { recordingUrl }) : C.empty('加载失败', r.error, 'alert'));
    };
    load();
  }

  function leads() {
    ui.setNav('leads');
    let rows = [];
    const list = el('div');
    const days = daysSelect(30, () => load());
    const exportBtn = el('button', { class: 'btn', on: { click: () => C.leadsCsv(rows, (shop.name || shop.number) + '-线索.csv') } },
      C.icon('download'), '导出');
    const box = ui.page('线索', 'AI 在电话里记下的称呼、电话和需求, 请及时回访', [days, exportBtn]);
    box.appendChild(C.card(null, list, { flush: true }));
    const load = async () => {
      list.textContent = '';
      list.appendChild(C.loading());
      const r = await C.api('GET', '/api/merchants/' + shop.id + '/leads?days=' + days.value);
      rows = r.ok ? r.data : [];
      list.textContent = '';
      list.appendChild(r.ok ? C.leadTable(rows) : C.empty('加载失败', r.error, 'alert'));
    };
    load();
  }

  // ---------- 账号 ----------
  function account() {
    ui.setNav('account');
    const box = ui.page('账号', '登录与安全');
    const f = {
      old: el('input', { class: 'input', type: 'password', autocomplete: 'current-password' }),
      pw: el('input', { class: 'input', type: 'password', autocomplete: 'new-password' }),
      pw2: el('input', { class: 'input', type: 'password', autocomplete: 'new-password' })
    };
    const save = el('button', { class: 'btn primary' }, '修改密码');
    save.addEventListener('click', async () => {
      if (f.pw.value.length < 8) { C.toast('新密码至少 8 位', 'err'); return; }
      if (f.pw.value !== f.pw2.value) { C.toast('两次输入的新密码不一样', 'err'); return; }
      save.disabled = true;
      const r = await C.api('POST', '/api/password/change', { oldPassword: f.old.value, newPassword: f.pw.value });
      save.disabled = false;
      if (!r.ok) { C.toast(r.error, 'err'); return; }
      f.old.value = f.pw.value = f.pw2.value = '';
      C.toast('密码已修改');
    });
    const field = (label, input) => el('div', { class: 'field' }, el('label', { text: label }), input);
    box.appendChild(el('div', { class: 'grid' },
      C.card('修改密码', el('div', null,
        el('div', { class: 'form', style: { gridTemplateColumns: '1fr' } },
          field('原密码', f.old), field('新密码（至少 8 位）', f.pw), field('再输一次新密码', f.pw2)),
        el('div', { style: { marginTop: '18px' } }, save))),
      C.card('当前账号', el('div', { class: 'status-list' },
        el('div', { class: 'row' }, el('div', null, el('div', { class: 't1', text: me.phone || me.username }),
          el('div', { class: 't2', text: '名下 ' + stores.length + ' 家门店' }))),
        el('div', { class: 'row' }, el('div', { class: 't2', text: '忘记密码时请联系运营重置。' }),
          el('button', { class: 'btn', on: { click: () => { C.setToken(''); location.hash = ''; showLogin(); } } }, C.icon('logout'), '退出登录'))))));
  }

  boot();
})();
