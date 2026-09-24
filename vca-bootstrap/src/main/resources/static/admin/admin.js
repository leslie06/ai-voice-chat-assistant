/* 运营后台: 添加商家、开通网关、看全局来电。接口见 AdminRoutes(/api/admin/**)与 MerchantRoutes(/api/merchants/**)。 */
(function () {
  'use strict';
  const C = window.Console;
  const el = C.el;
  const BRAND = '飞鱼电话客服';
  C.setTokenKey('vca_admin_token');

  let ui = null;
  let me = null;
  let industries = [];

  /** HT813 上除账号密码之外要改的项(与 docs/12-freeswitch.md §7.4 一致) */
  const HT813_TIPS = [
    ['FXO PORT · Number of Rings', '2（响两声自动接）'],
    ['FXO PORT · PSTN Ring Thru FXS', 'No（否则座机先响, 人一接就绕过了 AI）'],
    ['FXO PORT · Enable Current Disconnect', 'Yes'],
    ['FXO PORT · PSTN Disconnect Tone Detection', 'Yes, Tone 填 f1=450@-32,f2=450@-32,c=350/350;'],
    ['FXO PORT · Disable Network Echo Suppressor', 'Yes（否则客户插话打不断 AI）'],
    ['两个口 · 语音编码', '只留 PCMA'],
    ['两个口 · NAT Traversal', 'Keep-Alive'],
    ['两个口 · Caller ID Scheme', '按线路选, 大陆多为 FSK Bellcore']
  ];

  // ---------- 启动与登录 ----------
  async function boot() {
    C.onUnauthorized = () => { C.setToken(''); showLogin(); };
    if (C.token()) {
      const r = await C.api('GET', '/api/me');
      if (r.ok && r.data && r.data.admin) { start(r.data); return; }
      C.setToken('');
    }
    showLogin();
  }

  function showLogin() {
    C.loginPage({
      brand: BRAND, title: '运营后台', lead: '添加商家、开通网关、查看全部门店的来电',
      check: m => m && m.admin ? '' : '这个账号不是运营管理员',
      onLogin: (m, phone) => { m.phone = phone; start(m); },
      foot: '商家请登录商家后台 /merchant'
    });
  }

  async function start(m) {
    me = m;
    industries = await C.industries();
    ui = C.shell({
      brand: BRAND, sub: '运营后台', user: m.phone || m.username,
      nav: [
        { id: 'overview', label: '总览', icon: 'home', href: '#/overview' },
        { id: 'merchants', label: '商家', icon: 'store', href: '#/merchants' },
        { id: 'gateways', label: '网关', icon: 'router', href: '#/gateways' },
        { id: 'calls', label: '通话记录', icon: 'phone', href: '#/calls' },
        { id: 'leads', label: '线索', icon: 'users', href: '#/leads' },
        '-',
        { id: 'admins', label: '管理员', icon: 'shield', href: '#/admins' }
      ],
      onLogout: () => { C.setToken(''); location.hash = ''; showLogin(); }
    });
    C.router([
      [/^\/overview$/, overview],
      [/^\/merchants$/, merchants],
      [/^\/merchants\/(\d+)(?:\/(\w+))?$/, merchantDetail],
      [/^\/gateways$/, gateways],
      [/^\/calls$/, calls],
      [/^\/leads$/, leads],
      [/^\/admins$/, admins]
    ], '/overview');
  }

  const industryLabel = code => (industries.find(i => i.code === code) || {}).label || '其他商家';
  const recordingUrl = c => c.merchantId ? '/api/merchants/' + c.merchantId + '/calls/' + encodeURIComponent(c.callId) + '/recording' : null;

  function gatewayPill(g) {
    if (!g) return C.pill('未开通', 'plain');
    return g.lineOnline ? C.pill('在线', 'ok') : C.pill('离线', 'danger');
  }

  // ---------- 总览 ----------
  async function overview() {
    ui.setNav('overview');
    const box = ui.page('总览', '全部门店的来电与系统状态', [
      el('button', { class: 'btn primary', on: { click: addMerchant } }, C.icon('plus'), '添加商家')]);
    box.appendChild(C.loading());
    const r = await C.api('GET', '/api/admin/overview');
    box.textContent = '';
    if (!r.ok) { box.appendChild(C.card(null, C.empty('加载失败', r.error, 'alert'))); return; }
    const d = r.data;
    const sys = d.system;
    const weekCalls = d.week.calls;
    box.appendChild(el('div', { class: 'stats' },
      C.stat('门店', d.stores.enabled, '共 ' + d.stores.total + ' 家, 启用 ' + d.stores.enabled + ' 家', 'store'),
      C.stat('网关在线', d.gateways.online, d.gateways.total ? '共 ' + d.gateways.total + ' 台' : '还没有开通网关', 'router',
        d.gateways.total ? '/ ' + d.gateways.total : ''),
      C.stat('今日来电', d.today.calls, '留资 ' + d.today.leads + ' 条', 'phone', '通'),
      C.stat('近 7 天来电', weekCalls, '意向 A ' + (d.week.intents.A || 0) + ' · 留资 ' + d.week.leads + ' 条', 'chart', '通')));

    const fsOk = sys.freeswitchReachable && sys.freeswitchRunning;
    const statusRows = el('div', { class: 'status-list' },
      statusRow('AI 服务', '对话、识别、合成', C.pill('运行中', 'ok')),
      statusRow('电话交换', fsOk ? 'FreeSWITCH SIP 通道运行中' : (sys.freeswitchMessage || '连不上'),
        C.pill(fsOk ? '正常' : '异常', fsOk ? 'ok' : 'danger')),
      statusRow('网页开通网关', sys.gatewayProvisioning ? 'SIP 地址 ' + (sys.sipServer || '(未配置 VCA_FS_SIP_SERVER)') : '未配置网关分机目录',
        C.pill(sys.gatewayProvisioning ? '可用' : '不可用', sys.gatewayProvisioning ? 'ok' : 'warn')),
      sys.diskTotalGb ? statusRow('录音存储', '剩余 ' + sys.diskFreeGb + ' GB / 共 ' + sys.diskTotalGb + ' GB',
        C.pill(sys.diskFreeGb / sys.diskTotalGb < 0.15 ? '空间紧张' : '充足', sys.diskFreeGb / sys.diskTotalGb < 0.15 ? 'warn' : 'ok')) : null);

    box.appendChild(el('div', { class: 'grid side-wide' },
      C.card('近 7 天来电', el('div', null, C.bars(d.week.daily), el('div', { style: { height: '14px' } }), C.intentBar(d.week.intents)),
        { hint: '10 秒以上、有小结的通话' }),
      C.card('系统状态', statusRows)));
    box.appendChild(C.card('最近通话', C.callFeed(d.recent, { showStore: true, recordingUrl }),
      { flush: true, actions: [el('a', { class: 'btn sm ghost', href: '#/calls', text: '全部通话' })] }));
  }

  function statusRow(title, desc, right) {
    return el('div', { class: 'row' }, el('div', null, el('div', { class: 't1', text: title }), el('div', { class: 't2', text: desc })), right);
  }

  // ---------- 商家列表 ----------
  async function merchants() {
    ui.setNav('merchants');
    const box = ui.page('商家', '每家门店一个接入号、一台网关; 点进去改资料、开通网关、看来电',
      [el('button', { class: 'btn primary', on: { click: addMerchant } }, C.icon('plus'), '添加商家')]);
    box.appendChild(C.loading());
    const r = await C.api('GET', '/api/admin/merchants');
    box.textContent = '';
    if (!r.ok) { box.appendChild(C.card(null, C.empty('加载失败', r.error, 'alert'))); return; }
    const list = r.data.merchants;
    if (!list.length) {
      box.appendChild(C.card(null, el('div', null, C.empty('还没有商家', '点右上角"添加商家", 一步开好账号、门店和网关', 'store'))));
    } else {
      const tb = el('tbody');
      for (const m of list) {
        const tr = el('tr', { class: 'click', on: { click: () => { location.hash = '/merchants/' + m.id; } } },
          el('td', null, el('div', { class: 'strong', text: m.name || '未命名' }), el('div', { class: 'sub', text: industryLabel(m.industry) })),
          el('td', { class: 'mono', text: m.number }),
          el('td', { class: 'num', text: m.ownerAccount || '' }),
          el('td', null, gatewayPill(m.gateway)),
          el('td', { class: 'num', text: m.calls7d }),
          el('td', null, m.enabled ? C.pill('接听中', 'ok') : C.pill('已停用', 'plain')),
          el('td', { class: 'act' }, el('span', { class: 'btn sm ghost', text: '管理 →' })));
        tb.appendChild(tr);
      }
      box.appendChild(C.card(null, el('div', { class: 'table-wrap' }, el('table', { class: 't' },
        el('thead', null, el('tr', null, ['门店', '接入号', '归属账号', '网关', '近 7 天来电', '状态', ''].map(h => el('th', { text: h })))),
        tb)), { flush: true }));
    }
    if (r.data.configMerchants.length) {
      const tb = el('tbody');
      for (const m of r.data.configMerchants) {
        tb.appendChild(el('tr', null, el('td', { class: 'strong', text: m.name || '(未命名)' }), el('td', { class: 'mono', text: m.number }),
          el('td', { class: 'num', text: m.calls7d })));
      }
      box.appendChild(C.card('配置文件里的门店', el('div', null,
        el('div', { class: 'notice', style: { margin: '14px 20px' } }, C.icon('info'),
          el('div', { text: '这些门店写在服务器配置文件里, 页面上改不了。用"添加商家"新建一家同样接入号的门店, 就会接管它的来电, 之后在这里维护。' })),
        el('div', { class: 'table-wrap' }, el('table', { class: 't' },
          el('thead', null, el('tr', null, ['门店', '接入号', '近 7 天来电'].map(h => el('th', { text: h })))), tb))),
        { flush: true }));
    }
  }

  // ---------- 添加商家 ----------
  async function addMerchant() {
    const [next, gw] = await Promise.all([C.api('GET', '/api/admin/next-number'), C.api('GET', '/api/admin/gateways')]);
    const canGateway = gw.ok && gw.data.available;
    const f = {
      phone: el('input', { class: 'input', type: 'tel', placeholder: '商家老板或店长的手机号' }),
      name: el('input', { class: 'input', placeholder: '如 美好口腔东城店' }),
      industry: el('select', { class: 'select' }),
      number: el('input', { class: 'input', value: next.ok ? next.data.number : '' }),
      password: el('input', { class: 'input', placeholder: '不填自动生成' }),
      email: el('input', { class: 'input', type: 'email', placeholder: '可不填' }),
      gateway: el('input', { type: 'checkbox', checked: canGateway, disabled: !canGateway })
    };
    for (const i of industries) f.industry.appendChild(el('option', { value: i.code, text: i.label }));
    const err = el('div', { class: 'notice danger', style: { display: 'none', marginTop: '14px' } });
    const field = (label, control, hint, full) => el('div', { class: 'field' + (full ? ' full' : '') },
      el('label', { text: label }), control, hint ? el('div', { class: 'hint', text: hint }) : null);
    const body = el('div', null,
      el('div', { class: 'form' },
        field('商家手机号', f.phone, '登录商家后台用; 已注册过的手机号会直接把门店挂到这个账号下'),
        field('门店名称', f.name),
        field('行业', f.industry),
        field('接入号', f.number, '已按顺序建议一个空闲号'),
        field('初始密码', f.password, '至少 8 位; 仅新账号需要'),
        field('邮箱', f.email, '用于找回密码; 不填时由运营重置'),
        el('div', { class: 'field full' }, el('label', { class: 'switch' }, f.gateway, el('span', { class: 'track' }),
          el('span', { text: canGateway ? '同时开通语音网关（HT813 的两个分机）' : '网关开通不可用: ' + (gw.ok ? gw.data.message || '电话交换未接入' : gw.error) })))),
      err);
    C.modal({
      title: '添加商家', body, wide: true,
      actions: [{ label: '取消' }, {
        label: '添加', kind: 'primary', onClick: async () => {
          err.style.display = 'none';
          const r = await C.api('POST', '/api/admin/merchants', {
            phone: f.phone.value.trim(), name: f.name.value.trim(), industry: f.industry.value, number: f.number.value.trim(),
            password: f.password.value, email: f.email.value.trim(), gateway: f.gateway.checked
          });
          if (!r.ok) { err.textContent = r.error; err.style.display = 'flex'; return false; }
          showOnboarded(r.data);
          if (location.hash === '#/merchants') merchants(); else location.hash = '/merchants';
        }
      }]
    });
  }

  /** 添加完成: 给运营一份能直接转给商家/装机的清单 */
  function showOnboarded(d) {
    const m = d.merchant, a = d.account, g = d.gateway;
    const login = location.origin + '/merchant/';
    const lines = ['【' + (m.name || '') + '】AI 电话客服已开通', '商家后台: ' + login, '登录账号: ' + a.phone];
    if (a.password) lines.push('初始密码: ' + a.password + '(登录后请修改)');
    const rows = [['商家后台', login], ['登录账号', a.phone]];
    if (a.password) rows.push(['初始密码', a.password]);
    const body = el('div', null,
      el('div', { class: 'notice' }, C.icon('check'), el('div', null,
        el('div', { text: a.created ? '已新建商家账号和门店, 接入号 ' + m.number + '。' : '这个手机号已有账号, 门店已挂到它名下, 接入号 ' + m.number + '。' }),
        el('div', { class: 'muted', text: a.password ? '初始密码只显示这一次, 请现在复制发给商家。' : '' }))),
      el('h4', { text: '给商家', style: { margin: '18px 0 6px' } }), kv(rows),
      el('div', { class: 'toolbar', style: { marginTop: '10px' } },
        el('button', { class: 'btn sm', on: { click: () => C.copy(lines.join('\n')) } }, C.icon('copy'), '复制整段发给商家')));
    if (g) {
      body.appendChild(el('h4', { text: '装网关（HT813）', style: { margin: '20px 0 6px' } }));
      body.appendChild(gatewayKv(g));
    } else if (d.gatewayError) {
      body.appendChild(el('div', { class: 'notice warn', style: { marginTop: '16px' } }, C.icon('alert'),
        el('div', { text: '网关没开通: ' + d.gatewayError + '。可以稍后在门店的"网关"页里再开通。' })));
    }
    C.modal({ title: '商家已添加', body, wide: true, actions: [{ label: '完成', kind: 'primary' }] });
  }

  function kv(rows) {
    const box = el('div', { class: 'kv' });
    for (const [k, v] of rows) {
      box.appendChild(el('div', { class: 'k', text: k }));
      box.appendChild(el('div', { class: 'v', text: v }));
      box.appendChild(el('div', { class: 'c' }, v ? C.copyBtn(v) : null));
    }
    return box;
  }

  function gatewayKv(g) {
    const server = g.sipServer ? g.sipServer + ':5060' : '(未配置 VCA_FS_SIP_SERVER, 填服务器公网 IP:5060)';
    return el('div', null,
      kv([
        ['SIP Server', server],
        ['LINE 口 账号', g.lineUser], ['LINE 口 密码', g.linePassword],
        ['PHONE 口 账号', g.phoneUser], ['PHONE 口 密码', g.phonePassword],
        ['转 VoIP 号码', g.accessNumber]
      ]),
      el('details', { style: { marginTop: '12px' } }, el('summary', { text: 'HT813 其余要改的项', style: { cursor: 'pointer', color: 'var(--sub)' } }),
        el('div', { class: 'kv', style: { marginTop: '8px', gridTemplateColumns: '260px minmax(0,1fr) 0' } },
          HT813_TIPS.flatMap(([k, v]) => [el('div', { class: 'k', text: k }), el('div', { text: v }), el('div')]))));
  }

  // ---------- 单个门店 ----------
  async function merchantDetail(id, tab) {
    ui.setNav('merchants');
    tab = tab || 'profile';
    const r = await C.api('GET', '/api/admin/merchants');
    const m = r.ok ? r.data.merchants.find(x => String(x.id) === id) : null;
    if (!m) {
      const box = ui.page('门店不存在', null, null, { href: '#/merchants', label: '商家' });
      box.appendChild(C.card(null, C.empty('找不到这家门店', r.error || '可能已被删除', 'store')));
      return;
    }
    const box = ui.page(m.name || '未命名门店',
      '接入号 ' + m.number + ' · ' + industryLabel(m.industry) + ' · 归属账号 ' + (m.ownerAccount || '—'),
      [m.enabled ? C.pill('接听中', 'ok') : C.pill('已停用', 'plain'), gatewayPill(m.gateway)],
      { href: '#/merchants', label: '商家' });
    const tabs = [['profile', '资料'], ['gateway', '网关'], ['calls', '通话'], ['leads', '线索'], ['account', '账号']];
    box.appendChild(el('div', { class: 'tabs' }, tabs.map(([k, label]) =>
      el('a', { href: '#/merchants/' + id + '/' + k, class: k === tab ? 'on' : '', text: label }))));
    const pane = el('div');
    box.appendChild(pane);
    ({ profile: profileTab, gateway: gatewayTab, calls: callsTab, leads: leadsTab, account: accountTab }[tab] || profileTab)(m, pane);
  }

  function profileTab(m, pane) {
    const form = C.profileForm(m, industries, { operator: true });
    const save = el('button', { class: 'btn primary' }, '保存');
    save.addEventListener('click', async () => {
      save.disabled = true;
      const r = await C.api('PUT', '/api/merchants/' + m.id, form.value());
      save.disabled = false;
      if (r.ok) { C.toast('已保存, 下一通电话即生效'); Object.assign(m, r.data); } else C.toast(r.error, 'err');
    });
    pane.appendChild(el('section', { class: 'card' },
      el('div', { class: 'card-b' }, form.el),
      el('div', { class: 'form-foot' },
        el('button', { class: 'btn', on: { click: () => previewProfile(m.id) } }, C.icon('file'), '预览 AI 拿到的资料'), save)));
  }

  async function previewProfile(id) {
    const r = await C.api('GET', '/api/merchants/' + id + '/preview');
    if (!r.ok) { C.toast(r.error, 'err'); return; }
    C.modal({
      title: 'AI 拿到的资料', wide: true, body: el('div', null,
        el('div', { class: 'field' }, el('label', { text: '开场白（实际播放）' }), el('div', { class: 'preview', text: r.data.greeting })),
        el('div', { class: 'field', style: { marginTop: '14px' } }, el('label', { text: '门店资料（接在电话人设后面）' }),
          el('div', { class: 'preview', text: r.data.prompt || '（资料为空, AI 只会用通用话术）' })))
    });
  }

  async function gatewayTab(m, pane) {
    pane.appendChild(C.loading());
    const [st, gw] = await Promise.all([
      C.api('GET', '/api/admin/gateways'),
      m.gateway ? C.api('GET', '/api/admin/gateways/' + m.gateway.id) : Promise.resolve(null)]);
    pane.textContent = '';
    if (!m.gateway) {
      const can = st.ok && st.data.available;
      const btn = el('button', { class: 'btn primary', disabled: !can }, C.icon('plus'), '开通网关');
      btn.addEventListener('click', async () => {
        btn.disabled = true;
        const r = await C.api('POST', '/api/admin/merchants/' + m.id + '/gateway');
        if (!r.ok) { btn.disabled = false; C.toast(r.error, 'err'); return; }
        C.toast('网关已开通');
        location.hash = '/merchants/' + m.id + '/gateway';
        merchantDetail(String(m.id), 'gateway');
      });
      pane.appendChild(C.card('语音网关', el('div', null,
        C.empty('还没有开通网关', '开通后给这家店生成两个分机: LINE 口接电话线(来电进 AI), PHONE 口接话机(转人工、AI 故障时来电转到这里)', 'router'),
        el('div', { style: { textAlign: 'center', paddingBottom: '20px' } }, btn,
          can ? null : el('div', { class: 'muted', style: { marginTop: '10px' }, text: st.ok ? (st.data.message || '电话交换未接入') : st.error })))));
      return;
    }
    const g = gw && gw.ok ? gw.data : null;
    const revoke = el('button', { class: 'btn danger' }, C.icon('trash'), '撤销网关');
    revoke.addEventListener('click', async () => {
      if (!await C.confirm('撤销后这台网关的两个分机立即失效, 打到这家店的电话将进不来。确定撤销?', { danger: true, okLabel: '撤销' })) return;
      const r = await C.api('DELETE', '/api/admin/gateways/' + m.gateway.id);
      if (r.ok) { C.toast('已撤销'); merchantDetail(String(m.id), 'gateway'); } else C.toast(r.error, 'err');
    });
    pane.appendChild(el('div', { class: 'grid side-wide' },
      C.card('HT813 配置', g ? gatewayKv(g) : C.empty('读取失败', gw && gw.error, 'alert'), { actions: [revoke] }),
      C.card('在线状态', el('div', { class: 'status-list' },
        statusRow('LINE 口 ' + m.gateway.lineUser, '接电话线, 来电从这里进 AI', m.gateway.lineOnline ? C.pill('在线', 'ok') : C.pill('离线', 'danger')),
        statusRow('PHONE 口 ' + m.gateway.phoneUser, '接前台话机, 转人工时响', m.gateway.phoneOnline ? C.pill('在线', 'ok') : C.pill('离线', 'danger')),
        el('div', { class: 'muted', style: { paddingTop: '12px', fontSize: '12.5px' },
          text: '离线多半是诊所断网、网关断电, 或 HT813 上账号密码没填对。' })))));
  }

  async function callsTab(m, pane) {
    pane.appendChild(C.loading());
    const r = await C.api('GET', '/api/merchants/' + m.id + '/calls?days=30');
    pane.textContent = '';
    pane.appendChild(C.card('最近 30 天通话', r.ok ? C.callFeed(r.data, {
      recordingUrl: c => '/api/merchants/' + m.id + '/calls/' + encodeURIComponent(c.callId) + '/recording'
    }) : C.empty('加载失败', r.error, 'alert'), { flush: true }));
  }

  async function leadsTab(m, pane) {
    pane.appendChild(C.loading());
    const r = await C.api('GET', '/api/merchants/' + m.id + '/leads?days=90');
    pane.textContent = '';
    const rows = r.ok ? r.data : [];
    pane.appendChild(C.card('最近 90 天线索', r.ok ? C.leadTable(rows) : C.empty('加载失败', r.error, 'alert'), {
      flush: true, actions: rows.length ? [el('button', { class: 'btn sm', on: { click: () => C.leadsCsv(rows, (m.name || m.number) + '-线索.csv') } },
        C.icon('download'), '导出')] : null
    }));
  }

  function accountTab(m, pane) {
    const login = location.origin + '/merchant/';
    const reset = el('button', { class: 'btn' }, C.icon('key'), '重置密码');
    reset.addEventListener('click', async () => {
      if (!await C.confirm('重置后商家的旧密码立即失效。确定重置 ' + m.ownerAccount + ' 的密码?', { okLabel: '重置' })) return;
      const r = await C.api('POST', '/api/admin/accounts/' + m.ownerId + '/reset-password');
      if (!r.ok) { C.toast(r.error, 'err'); return; }
      C.modal({
        title: '新密码', body: el('div', null, el('p', { class: 'muted', text: '只显示这一次, 请复制发给商家, 并提醒登录后修改。' }),
          kv([['登录账号', r.data.phone], ['新密码', r.data.password], ['商家后台', login]])),
        actions: [{ label: '完成', kind: 'primary' }]
      });
    });
    const del = el('button', { class: 'btn danger' }, C.icon('trash'), '删除门店');
    del.addEventListener('click', async () => {
      if (!await C.confirm('删除「' + (m.name || m.number) + '」? 它的网关会一并撤销, 打到接入号 ' + m.number + ' 的电话将按系统默认方式处理。通话记录与线索保留。', { danger: true, okLabel: '删除' })) return;
      const r = await C.api('DELETE', '/api/merchants/' + m.id);
      if (r.ok) { C.toast('已删除'); location.hash = '/merchants'; } else C.toast(r.error, 'err');
    });
    pane.appendChild(el('div', { class: 'grid' },
      C.card('商家账号', el('div', null, kv([['登录账号', m.ownerAccount || ''], ['商家后台', login]]),
        el('div', { class: 'toolbar', style: { marginTop: '14px' } }, reset))),
      C.card('危险操作', el('div', null, el('p', { class: 'muted', style: { marginTop: 0 }, text: '只想暂停 AI 接听的话, 在"资料"里关掉"启用 AI 接听"即可。' }), del))));
  }

  // ---------- 网关 ----------
  async function gateways() {
    ui.setNav('gateways');
    const box = ui.page('网关', '每家门店一台 HT813: LINE 口接电话线, PHONE 口接前台话机');
    box.appendChild(C.loading());
    const r = await C.api('GET', '/api/admin/gateways');
    box.textContent = '';
    if (!r.ok) { box.appendChild(C.card(null, C.empty('加载失败', r.error, 'alert'))); return; }
    const d = r.data;
    if (!d.reachable || !d.running) {
      box.appendChild(el('div', { class: 'notice danger' }, C.icon('alert'), el('div', { text: '电话交换状态异常: ' + (d.message || '连不上 FreeSWITCH') })));
    } else if (!d.available) {
      box.appendChild(el('div', { class: 'notice warn' }, C.icon('alert'), el('div', { text: '没配网关分机目录(VCA_FS_GATEWAYS_DIR), 页面上不能开通网关' })));
    }
    if (!d.gateways.length) {
      box.appendChild(C.card(null, C.empty('还没有开通网关', '在"商家"里点进一家门店, 到"网关"页开通; 或添加商家时勾选"同时开通"', 'router')));
    } else {
      const tb = el('tbody');
      for (const g of d.gateways) {
        tb.appendChild(el('tr', { class: 'click', on: { click: () => { location.hash = '/merchants/' + g.merchantId + '/gateway'; } } },
          el('td', { class: 'strong', text: g.store }),
          el('td', { class: 'mono', text: g.accessNumber }),
          el('td', null, el('span', { class: 'mono', text: g.lineUser + '  ' }), g.lineOnline ? C.pill('在线', 'ok') : C.pill('离线', 'danger')),
          el('td', null, el('span', { class: 'mono', text: g.phoneUser + '  ' }), g.phoneOnline ? C.pill('在线', 'ok') : C.pill('离线', 'danger')),
          el('td', { class: 'sub', text: C.time(g.createdAt) }),
          el('td', { class: 'act' }, el('span', { class: 'btn sm ghost', text: '配置 →' }))));
      }
      box.appendChild(C.card(null, el('div', { class: 'table-wrap' }, el('table', { class: 't' },
        el('thead', null, el('tr', null, ['门店', '接入号', 'LINE 口(电话线)', 'PHONE 口(话机)', '开通时间', ''].map(h => el('th', { text: h })))), tb)),
        { flush: true }));
    }
    if (d.otherRegistered.length) {
      box.appendChild(C.card('其他在线分机', el('div', null,
        el('p', { class: 'muted', style: { marginTop: 0 }, text: '不是在这里开通的: 服务器配置文件里的老网关、脚本开的网关或软电话。' }),
        el('div', { class: 'toolbar' }, d.otherRegistered.map(u => C.pill(u, 'info'))))));
    }
  }

  // ---------- 通话与线索 ----------
  async function storeFilter(onChange) {
    const r = await C.api('GET', '/api/admin/merchants');
    const sel = el('select', { class: 'select', style: { width: 'auto', minWidth: '180px' } }, el('option', { value: '', text: '全部门店' }));
    if (r.ok) {
      for (const m of r.data.merchants) sel.appendChild(el('option', { value: m.number, text: (m.name || '未命名') + ' · ' + m.number }));
      for (const m of r.data.configMerchants) sel.appendChild(el('option', { value: m.number, text: (m.name || '') + ' · ' + m.number + '(配置文件)' }));
    }
    sel.addEventListener('change', onChange);
    return sel;
  }
  function daysSelect(def, onChange) {
    const sel = el('select', { class: 'select', style: { width: 'auto' } },
      [[1, '今天'], [7, '近 7 天'], [30, '近 30 天'], [90, '近 90 天']].map(([v, t]) => el('option', { value: v, text: t })));
    sel.value = def;
    sel.addEventListener('change', onChange);
    return sel;
  }

  async function calls() {
    ui.setNav('calls');
    const box = ui.page('通话记录', '全部门店 10 秒以上的来电与 AI 小结');
    const list = el('div');
    const load = async () => {
      list.textContent = '';
      list.appendChild(C.loading());
      const r = await C.api('GET', '/api/admin/calls?days=' + days.value + '&number=' + encodeURIComponent(store.value));
      list.textContent = '';
      list.appendChild(r.ok ? C.callFeed(r.data, { showStore: true, recordingUrl }) : C.empty('加载失败', r.error, 'alert'));
    };
    const store = await storeFilter(load);
    const days = daysSelect(7, load);
    box.appendChild(C.card(null, list, { flush: true }));
    box.firstChild.before(el('div', { class: 'toolbar', style: { marginBottom: '14px' } }, store, days));
    load();
  }

  async function leads() {
    ui.setNav('leads');
    let rows = [];
    const exportBtn = el('button', { class: 'btn', on: { click: () => C.leadsCsv(rows, '线索.csv') } }, C.icon('download'), '导出');
    const box = ui.page('线索', 'AI 在电话里记下的称呼、电话与需求', [exportBtn]);
    const list = el('div');
    const load = async () => {
      list.textContent = '';
      list.appendChild(C.loading());
      const r = await C.api('GET', '/api/admin/leads?days=' + days.value + '&number=' + encodeURIComponent(store.value));
      rows = r.ok ? r.data : [];
      list.textContent = '';
      list.appendChild(r.ok ? C.leadTable(rows, { showStore: true }) : C.empty('加载失败', r.error, 'alert'));
    };
    const store = await storeFilter(load);
    const days = daysSelect(30, load);
    box.appendChild(C.card(null, list, { flush: true }));
    box.firstChild.before(el('div', { class: 'toolbar', style: { marginBottom: '14px' } }, store, days));
    load();
  }

  // ---------- 管理员 ----------
  async function admins() {
    ui.setNav('admins');
    const box = ui.page('管理员', '能登录运营后台的账号', [el('button', { class: 'btn primary', on: { click: addAdmin } }, C.icon('plus'), '添加管理员')]);
    box.appendChild(C.loading());
    const r = await C.api('GET', '/api/admin/admins');
    box.textContent = '';
    if (!r.ok) { box.appendChild(C.card(null, C.empty('加载失败', r.error, 'alert'))); return; }
    const tb = el('tbody');
    for (const a of r.data) {
      const act = el('td', { class: 'act' });
      if (!a.super && !a.self) {
        act.appendChild(el('button', {
          class: 'btn sm danger', text: '撤销', on: {
            click: async () => {
              if (!await C.confirm('撤销 ' + a.phone + ' 的管理员权限?', { danger: true, okLabel: '撤销' })) return;
              const x = await C.api('DELETE', '/api/admin/admins/' + a.userId);
              if (x.ok) { C.toast('已撤销'); admins(); } else C.toast(x.error, 'err');
            }
          }
        }));
      }
      tb.appendChild(el('tr', null,
        el('td', { class: 'strong num', text: a.phone + (a.self ? '（我）' : '') }),
        el('td', null, a.super ? C.pill('超级管理员', 'info') : C.pill('管理员', 'plain')),
        el('td', { class: 'sub', text: a.lastLoginAt ? C.time(a.lastLoginAt) : '从未登录' }), act));
    }
    box.appendChild(C.card(null, el('div', { class: 'table-wrap' }, el('table', { class: 't' },
      el('thead', null, el('tr', null, ['账号', '类型', '最近登录', ''].map(h => el('th', { text: h })))), tb)), { flush: true }));
    box.appendChild(el('div', { class: 'notice', style: { marginTop: '16px' } }, C.icon('info'),
      el('div', { text: '超级管理员写在服务器配置文件里(VCA_ADMIN_USER_IDS), 页面上撤不掉, 保证总有人能进后台。' })));
  }

  function addAdmin() {
    const phone = el('input', { class: 'input', type: 'tel', placeholder: '对方登录用的手机号' });
    const err = el('div', { class: 'notice danger', style: { display: 'none', marginTop: '12px' } });
    C.modal({
      title: '添加管理员', body: el('div', null, el('div', { class: 'field' }, el('label', { text: '手机号' }), phone,
        el('div', { class: 'hint', text: '对方需要先有账号(注册过, 或被添加为商家)。管理员能看到所有门店的资料、来电和网关密码。' })), err),
      actions: [{ label: '取消' }, {
        label: '添加', kind: 'primary', onClick: async () => {
          const r = await C.api('POST', '/api/admin/admins', { phone: phone.value.trim() });
          if (!r.ok) { err.textContent = r.error; err.style.display = 'flex'; return false; }
          C.toast('已添加');
          admins();
        }
      }]
    });
  }

  boot();
})();
