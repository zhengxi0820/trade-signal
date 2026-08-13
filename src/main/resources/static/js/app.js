const { createApp } = Vue;

// 图表默认一屏展示的周期数
const WINDOW_SIZE = 60;

// K/D/J 线条颜色（K 黄、D 紫、J 蓝）
const COLOR_K = '#E6A23C';
const COLOR_D = '#8E44AD';
const COLOR_J = '#2E86DE';
// 金叉锚图标颜色（死叉用灰）
const COLOR_ANCHOR_GOLD = '#D4A017';
const COLOR_ANCHOR_GRAY = '#909399';
// 锚形图标（path：圆环 + 竖杆 + 横杆 + 双锚爪）
const ANCHOR_PATH = 'path://M12 3a2 2 0 1 1 -0.01 0Z M12 7v13 M8 10h8 M5 13c0 4.5 3 7 7 7s7-2.5 7-7';

// 显示用日期格式：yyyymmdd → yyyy/mm/dd（前后端交互仍用 yyyymmdd）
function fmtSlash(ymd) {
  const s = String(ymd || '');
  if (s.length === 8) return s.slice(0, 4) + '/' + s.slice(4, 6) + '/' + s.slice(6, 8);
  if (s.length === 6) return s.slice(0, 4) + '/' + s.slice(4, 6);
  return s;
}

// 数字展示：保留两位小数；null / 空串 → 空
function fmt2(v) {
  if (v === null || v === undefined || v === '') return '';
  const n = Number(v);
  return isNaN(n) ? String(v) : n.toFixed(2);
}

// 任一接口返回 401 时回调（由 app 注册）：弹出密钥遮罩
let onUnauthorized = null;

async function getJson(url, onHeaders) {
    const resp = await fetch(url);
    if (resp.status === 401) {
      if (onUnauthorized) onUnauthorized();
      throw new Error('未认证或会话已过期');
    }
    if (!resp.ok) throw new Error(url + ' → HTTP ' + resp.status);
    if (onHeaders) onHeaders(resp.headers);
    return resp.json();
  }

createApp({
  data() {
    return {
      // 认证状态：默认未认证（遮罩盖住整页），/auth/check 通过后才进入
      authed: false,
      authView: 'login',      // login | register（默认登录页，「立即注册」切注册页）
      useKey: false,          // 登录页内切访问密钥入口（服务器脚本同款）
      authKey: '',
      // 登录与注册表单状态完全独立，互不串
      loginForm: { username: '', password: '' },
      registerForm: { username: '', password: '', inviteCode: '' },
      authLoading: false,
      // 提示条：3 秒自动消失
      toast: { text: '', type: 'error' },

      // 页签（全市场/我的自选）+ 自选集合（服务端按用户存储）+ 筛选关键字
      page: 'all',
      favs: [],
      searchKw: '',
      // 板块设置（纯前端过滤，不传后端）：默认全选 = 不过滤
      boardFilter: ['0', '1', '2', '3', '4'],
      boardOptions: [
        { value: '0', label: '上交所主板' },
        { value: '1', label: '科创板' },
        { value: '2', label: '创业板' },
        { value: '3', label: '北交所' },
        { value: '4', label: '深交所主板' },
      ],
      // 周/月/季物化未就绪标记（后端 X-Data-Not-Ready 头）
      dataNotReady: false,
      // 「其他参数」：点击查询后关闭图表 / 查询历史周期时同步展示最新周期（localStorage 持久化，纯 UI 偏好，不进查询）
      closeChartOnQuery: localStorage.getItem('ts_close_chart_on_query') === '1',
      showLatestChart: localStorage.getItem('ts_show_latest_chart') !== '0',
      // 有未应用的查询条件修改（点「查询」才发请求）
      queryDirty: false,

      kdjType: '0',

      // 截止周期选择器（选项来自 /kdj/periods，时间倒序）
      periods: [],
      dailyDate: '',
      weekValue: '',
      monthValue: '',
      quarterYear: null,
      quarter: '1',

      paramOpen: false,
      querying: false,
      params: {
        n: 9, m1: 3, m2: 3,
        lastGoldCrossMax: 20,
        currGoldCrossMax: 50,
        lastDeathCrossMax: 50,
        goldInternalMin: 5,
        goldInternalMax: 15,
        openClosePriceLimit: true,
        goldCrossLimit: true,
        adjust: '1'
      },

      allColumns: [
        { prop: 'code',       label: '代码',   width: 90,  minWidth: 80 },
        { prop: 'name',       label: '名称',   width: 100, minWidth: 100 },
        { prop: 'close',      label: '收盘价', width: 90,  minWidth: 100, fmt: true },
        { prop: 'open',       label: '开盘价', width: 90,  minWidth: 100, fmt: true },
        { prop: 'high',       label: '最高价', width: 90,  minWidth: 100, fmt: true },
        { prop: 'low',        label: '最低价', width: 90,  minWidth: 100, fmt: true },
        { prop: 'k',          label: 'K',      width: 70,  minWidth: 100, fmt: true },
        { prop: 'd',          label: 'D',      width: 70,  minWidth: 100, fmt: true },
        { prop: 'j',          label: 'J',      width: 70,  minWidth: 100, fmt: true },
        { prop: 'crossValue', label: '交汇点', width: 150, minWidth: 150, fmt: true }
      ],
      goldCols: ['code', 'name', 'close', 'open', 'high', 'low', 'k', 'd', 'j', 'crossValue'],
      signalCols: ['code', 'name', 'close', 'open', 'high', 'low', 'k', 'd', 'j', 'crossValue'],

      allStockList: [],
      goldCrossList: [],
      tradeSignalList: [],
      // 所有股票表分页（5534 行全量渲染会卡，el-table 无虚拟滚动，前端分页切片）
      allPage: 1,
      allPageSize: 100,
      loadingAll: false,
      loadingGold: false,
      loadingSignal: false,
      loadingChart: false,

      chartStock: null
      // echarts 实例与图表数据为非响应式（created 中初始化），避免 Vue 代理破坏 ECharts 内部机制
    };
  },
  computed: {
    periodName() {
      return { '0': '日线', '1': '周线', '2': '月线', '3': '季线' }[this.kdjType];
    },
    // 是否处于「最新已完结周期」（此时无需额外展示最新周期图）
    isLatestPeriod() {
      const latest = this.periods[0];
      if (!latest) return true;
      if (this.kdjType === '0') return !!this.dailyDate && this.dailyDate === latest.tradeDate;
      if (this.kdjType === '1') return !!this.weekValue && this.weekValue === (latest.tradeDateMin + '-' + latest.tradeDateMax);
      if (this.kdjType === '2') return !!this.monthValue && this.monthValue === String(latest.tradeDate).slice(0, 6);
      const y = +String(latest.tradeDateMin).slice(0, 4);
      const q = Math.ceil(+String(latest.tradeDateMax).slice(4, 6) / 3);
      return this.quarterYear === y && +this.quarter === q;
    },
    // 最新周期图是否展示：开关开 + 非最新截止
    latestChartVisible() {
      return this.showLatestChart && !this.isLatestPeriod;
    },
    // 截止周期图内联标签（与当前选择一致）
    cutoffPeriodLabel() {
      if (this.kdjType === '0') return fmtSlash(this.dailyDate);
      if (this.kdjType === '1') {
        const [mn, mx] = (this.weekValue || '-').split('-');
        return fmtSlash(mn) + ' ~ ' + fmtSlash(mx);
      }
      if (this.kdjType === '2') return fmtSlash(String(this.monthValue || ''));
      return (this.quarterYear || '') + 'Q' + (this.quarter || '');
    },
    // 最新周期图内联标签（periods[0]）
    latestPeriodLabel() {
      const p = this.periods[0];
      if (!p) return '';
      if (this.kdjType === '0') return fmtSlash(p.tradeDate);
      if (this.kdjType === '1') return fmtSlash(p.tradeDateMin) + ' ~ ' + fmtSlash(p.tradeDateMax);
      if (this.kdjType === '2') return fmtSlash(String(p.tradeDate).slice(0, 6));
      return String(p.tradeDateMin).slice(0, 4) + 'Q' + Math.ceil(+String(p.tradeDateMax).slice(4, 6) / 3);
    },
    visibleGoldCols() {
      return this.allColumns.filter(c => this.goldCols.includes(c.prop));
    },
    // 页签 + 筛选后的三个列表视图
    viewAllStockList() { return this._boardFilter(this._favFilter(this.allStockList)); },
    viewGoldCrossList() { return this._boardFilter(this._favFilter(this.goldCrossList)); },
    viewTradeSignalList() { return this._boardFilter(this._favFilter(this.tradeSignalList)); },
    // 板块设置：全选 = 不过滤（含 boardType 为空的股票）
    boardAllOn() {
      return this.boardFilter.length === 5;
    },
    // 所有股票表当前页切片（基于过滤后视图）
    pagedAllStockList() {
      const start = (this.allPage - 1) * this.allPageSize;
      return this.viewAllStockList.slice(start, start + this.allPageSize);
    },
    visibleSignalCols() {
      return this.allColumns.filter(c => this.signalCols.includes(c.prop));
    },
    // 三张表的「代码」列为固定列（带挂牌徽标），其余列仍走列设置动态渲染
    otherColumns() {
      return this.allColumns.filter(c => c.prop !== 'code');
    },
    visibleGoldColsNoCode() {
      return this.visibleGoldCols.filter(c => c.prop !== 'code');
    },
    visibleSignalColsNoCode() {
      return this.visibleSignalCols.filter(c => c.prop !== 'code');
    },
    // 日线：可交易日集合（periods 已剔除未来日期与未完结周期）
    tradeDates() {
      return new Set(this.periods.map(p => p.tradeDate));
    },
    // 月线：可选月份集合（yyyymm）
    monthSet() {
      return new Set(this.periods.map(p => String(p.tradeDate).slice(0, 6)));
    },
    // 季线：年份 → 可选季度集合（跳过缺 tradeDateMin/Max 的条目，防御脏数据产生 NaN）
    quarterMap() {
      const map = {};
      for (const p of this.periods) {
        if (!p.tradeDateMin || !p.tradeDateMax) continue;
        const y = +String(p.tradeDateMin).slice(0, 4);
        const q = Math.ceil(+String(p.tradeDateMax).slice(4, 6) / 3);
        (map[y] = map[y] || new Set()).add(q);
      }
      return map;
    },
    yearOptions() {
      return Object.keys(this.quarterMap).map(Number).sort((a, b) => b - a);
    }
  },
  watch: {
    kdjType() {
      this.clearSelection();
      // 切换周期类型时先清空旧周期数据：新数据返回前选择器渲染空态，
      // 避免旧结构的 periods（如日线数据缺 tradeDateMin）喂给季线选择器产生 NaN 选项
      this.periods = [];
      // 查询触发制：只刷新选择器，列表等用户点「查询」
      this.queryDirty = true;
      this.initPeriods(true);
    },
    dailyDate() { if (!this._suppressDirty) this.queryDirty = true; },
    weekValue() { if (!this._suppressDirty) this.queryDirty = true; },
    monthValue() { if (!this._suppressDirty) this.queryDirty = true; },
    quarter() { if (!this._suppressDirty) this.queryDirty = true; },
    params: { deep: true, handler() { if (!this._suppressDirty) this.queryDirty = true; } },
    quarterYear() {
      // 切换年份后，当前季度不可选时回退到该年最新可选季度
      const set = this.quarterMap[this.quarterYear];
      if (set && !set.has(+this.quarter)) {
        this.quarter = String(Math.max(...set));
      }
    }
  },
    created() {
      // 图表实例集（非响应式，避免 Vue 代理破坏 ECharts 内部机制）：
      // cut = 截止周期图、latest = 最新周期图，各含 { chart, cd, raf }
      this._charts = {};
      // 任一业务接口 401 → 回到密钥遮罩
      onUnauthorized = () => { this.authed = false; };
    },
  async mounted() {
    // 进门检查：已认证直接初始化，未认证停在遮罩
    try {
      const resp = await fetch('/auth/check');
      this.authed = resp.ok;
    } catch (e) {
      this.authed = false;
    }
    if (this.authed) {
      this.initPeriods();
    }
  },
  methods: {
    fmtSlash,
    // 表格数字列格式化
    fmtNum(row, column, cellValue) {
      return fmt2(cellValue);
    },
    // 挂牌徽标文案（boardType 字典：0=上交所主板 1=科创板 2=创业板 3=北交所 4=深交所主板）
    badgeText(boardType) {
      return ({ '0': '沪', '1': '科', '2': '创', '3': '北', '4': '深' })[boardType] || '';
    },

    // ---- 认证 ----
    showToast(text, type) {
      this.toast = { text, type: type || 'error' };
      clearTimeout(this._toastTimer);
      this._toastTimer = setTimeout(() => { this.toast.text = ''; }, 3000);
    },
    async login() {
      const body = this.useKey
        ? { key: this.authKey }
        : { username: this.loginForm.username, password: this.loginForm.password };
      if (this.useKey && !this.authKey) {
        this.showToast('请输入访问密钥');
        return;
      }
      if (!this.useKey && (!this.loginForm.username || !this.loginForm.password)) {
        this.showToast('请输入用户名和密码');
        return;
      }
      this.authLoading = true;
      try {
        const resp = await fetch('/auth/login', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(body)
        });
        if (resp.ok) {
          this.onAuthed();
        } else if (resp.status === 429) {
          this.showToast('尝试次数过多，已锁定 15 分钟');
        } else {
          // 401 带剩余尝试次数
          this.showToast(await this.readErrorDetail(resp, this.useKey ? '密钥错误' : '用户名或密码错误'));
        }
      } catch (e) {
        this.showToast('网络异常，请重试');
      } finally {
        this.authLoading = false;
      }
    },
    async register() {
      const f = this.registerForm;
      if (!f.username || !f.password || !f.inviteCode) {
        this.showToast('请填写用户名、密码和邀请码');
        return;
      }
      this.authLoading = true;
      try {
        const resp = await fetch('/auth/register', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ username: f.username, password: f.password, inviteCode: f.inviteCode })
        });
        if (resp.ok) {
          // 注册成功：清空注册表单，跳回登录页
          this.registerForm = { username: '', password: '', inviteCode: '' };
          this.authView = 'login';
          this.showToast('注册成功，请登录', 'success');
        } else if (resp.status === 429) {
          this.showToast('尝试次数过多，已锁定 15 分钟');
        } else if (resp.status === 403) {
          this.showToast('注册未开放');
        } else {
          this.showToast(await this.readErrorDetail(resp, '注册失败'));
        }
      } catch (e) {
        this.showToast('网络异常，请重试');
      } finally {
        this.authLoading = false;
      }
    },
    async readErrorDetail(resp, fallback) {
      // 错误体为 {"message":"具体原因"}（如"用户名已存在，还可尝试 N 次"）
      try {
        const data = await resp.json();
        return data.message || data.detail || fallback;
      } catch (e) {
        return fallback;
      }
    },
    onAuthed() {
      this.authed = true;
      this.authKey = '';
      this.loginForm = { username: '', password: '' };
      this.registerForm = { username: '', password: '', inviteCode: '' };
      this.initPeriods();
      this.loadFavs();
    },

    // ---- 自选股（服务端按用户存储，GET/POST /watchlist）----
    _favFilter(list) {
      let r = this.page === 'fav' ? list.filter(s => this.favs.includes(s.code)) : list;
      const kw = this.searchKw.trim();
      if (kw) r = r.filter(s => s.code.includes(kw) || (s.name && s.name.includes(kw)));
      return r;
    },
    // 板块过滤：全选时原样返回；否则只保留勾选板块（boardType 为空只在全选时显示）
    _boardFilter(list) {
      if (this.boardFilter.length === 5) return list;
      return list.filter(s => this.boardFilter.includes(s.boardType));
    },
    toggleBoardAll() {
      this.boardFilter = this.boardAllOn ? [] : ['0', '1', '2', '3', '4'];
    },
    isFav(code) { return this.favs.includes(code); },
    switchPage(p) { this.page = p; this.allPage = 1; },
    async loadFavs() {
      try {
        this.favs = await getJson('/watchlist');
      } catch (e) {
        this.favs = [];
      }
    },
    async toggleFav(code) {
      // 乐观更新，失败回滚
      const i = this.favs.indexOf(code);
      const adding = i < 0;
      if (adding) this.favs.push(code); else this.favs.splice(i, 1);
      try {
        const resp = await fetch(adding ? '/watchlist' : '/watchlist/remove', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ code })
        });
        if (!resp.ok) throw new Error('HTTP ' + resp.status);
      } catch (e) {
        const j = this.favs.indexOf(code);
        if (adding) { if (j >= 0) this.favs.splice(j, 1); } else if (j < 0) { this.favs.push(code); }
        ElementPlus.ElMessage.error('自选操作失败：' + e.message);
      }
    },

    // ---- 周期选择器 ----
    async initPeriods(noQuery) {
      try {
        this.periods = await getJson('/kdj/periods?kdjType=' + this.kdjType);
      } catch (e) {
        this.periods = [];
        ElementPlus.ElMessage.error('可选周期加载失败：' + e.message);
      }
      this._suppressDirty = true;
      this.applyPeriodDefault();
      if (!noQuery) this.loadLists();
      this.$nextTick(() => { this._suppressDirty = false; });
    },
    // periods 为时间倒序，[0] 即最新已完结周期
    applyPeriodDefault() {
      const latest = this.periods[0];
      if (!latest) {
        this.dailyDate = '';
        this.weekValue = '';
        this.monthValue = '';
        this.quarterYear = null;
        return;
      }
      if (this.kdjType === '0') {
        this.dailyDate = latest.tradeDate;
      } else if (this.kdjType === '1') {
        this.weekValue = latest.tradeDateMin + '-' + latest.tradeDateMax;
      } else if (this.kdjType === '2') {
        this.monthValue = String(latest.tradeDate).slice(0, 6);
      } else {
        this.quarterYear = +String(latest.tradeDateMin).slice(0, 4);
        this.quarter = String(Math.ceil(+String(latest.tradeDateMax).slice(4, 6) / 3));
      }
    },
    isNotTradeDate(date) {
      const ymd = date.getFullYear()
        + String(date.getMonth() + 1).padStart(2, '0')
        + String(date.getDate()).padStart(2, '0');
      return !this.tradeDates.has(ymd);
    },
    monthDisabled(date) {
      const ym = date.getFullYear() + String(date.getMonth() + 1).padStart(2, '0');
      return !this.monthSet.has(ym);
    },
    quarterDisabled(q) {
      const set = this.quarterMap[this.quarterYear];
      return !set || !set.has(q);
    },

    // ---- 查询参数组装（与接口文档一致）----
    buildQuery() {
      const p = new URLSearchParams();
      const pms = this.params;
      p.set('kdjType', this.kdjType);
      p.set('adjust', pms.adjust);
      p.set('n', pms.n);
      p.set('m1', pms.m1);
      p.set('m2', pms.m2);
      p.set('lastGoldCrossMax', pms.lastGoldCrossMax);
      p.set('currGoldCrossMax', pms.currGoldCrossMax);
      p.set('lastDeathCrossMax', pms.lastDeathCrossMax);
      p.set('goldInternalMin', pms.goldInternalMin);
      p.set('goldInternalMax', pms.goldInternalMax);
      p.set('openClosePriceLimit', pms.openClosePriceLimit ? '1' : '0');
      p.set('goldCrossLimit', pms.goldCrossLimit ? '1' : '0');
      // 三字段日期规则：日/月 tradeDate；周/季 tradeDateMin + tradeDateMax
      if (this.kdjType === '0') {
        if (this.dailyDate) p.set('tradeDate', this.dailyDate);
      } else if (this.kdjType === '1') {
        const [min, max] = (this.weekValue || '-').split('-');
        if (min && max) { p.set('tradeDateMin', min); p.set('tradeDateMax', max); }
      } else if (this.kdjType === '2') {
        const hit = this.periods.find(x => String(x.tradeDate).slice(0, 6) === this.monthValue);
        if (hit) p.set('tradeDate', hit.tradeDate);
      } else {
        const hit = this.periods.find(x =>
          +String(x.tradeDateMin).slice(0, 4) === this.quarterYear
          && Math.ceil(+String(x.tradeDateMax).slice(4, 6) / 3) === +this.quarter);
        if (hit) { p.set('tradeDateMin', hit.tradeDateMin); p.set('tradeDateMax', hit.tradeDateMax); }
      }
      return p;
    },

    // ---- 三个列表 ----
    async loadLists() {
      const q = this.buildQuery().toString();
      this.querying = true;
      this.queryDirty = false;
      this.allPage = 1;
      this.loadingAll = this.loadingGold = this.loadingSignal = true;
      const fill = (url, key, loadingKey) =>
        getJson(url, h => { this.dataNotReady = h.get('x-data-not-ready') === '1'; })
            .then(list => { this[key] = list; })
          .catch(e => {
            this[key] = [];
            ElementPlus.ElMessage.error(url.split('?')[0] + ' 查询失败：' + e.message);
          })
          .finally(() => { this[loadingKey] = false; });
      await Promise.all([
        fill('/kdj/all-stocks?' + q, 'allStockList', 'loadingAll'),
        fill('/kdj/gold-cross?' + q, 'goldCrossList', 'loadingGold'),
        fill('/kdj/trade-signal?' + q, 'tradeSignalList', 'loadingSignal')
      ]);
      this.querying = false;
      // 「其他参数」：点击查询后关闭图表；否则已选股票的图表跟随新参数刷新
      if (this.closeChartOnQuery) {
        this.closeCharts();
      } else if (this.chartStock) {
        this.loadChart(this.chartStock.code);
      }
    },

    // ---- 选中联动：三表互斥 + 拉取序列画图 ----
    onSelect(row, from) {
      if (!row) return;
      if (from !== 'gold') this.$refs.goldTable.setCurrentRow();
      if (from !== 'signal') this.$refs.signalTable.setCurrentRow();
      if (from !== 'all') this.$refs.allTable.setCurrentRow();
      this.chartStock = row;
      this.$nextTick(() => {
        this.loadChart(row.code);
        const el = document.querySelector('.chart-card');
        el && el.scrollIntoView({ behavior: 'smooth', block: 'start' });
      });
    },
    clearSelection() {
      this.$refs.goldTable && this.$refs.goldTable.setCurrentRow();
      this.$refs.signalTable && this.$refs.signalTable.setCurrentRow();
      this.$refs.allTable && this.$refs.allTable.setCurrentRow();
      this.closeCharts();
    },
    async loadChart(code) {
      this.loadingChart = true;
      try {
        const q = this.buildQuery();
        q.set('code', code);
        const rows = await getJson('/kdj/series?' + q.toString());
        if (!rows.length) {
          ElementPlus.ElMessage.warning(code + ' 无序列数据');
          return;
        }
        this.renderChart(rows);
        if (this.latestChartVisible) {
          this.loadLatestChart(code);
        } else {
          this.disposeLatestChart();
        }
      } catch (e) {
        ElementPlus.ElMessage.error('KDJ 序列加载失败：' + e.message);
      } finally {
        this.loadingChart = false;
      }
    },

    // ---- 图表 ----
    // 周期标签：日 yyyy/mm/dd；周 min~max；月 yyyy/mm；季 2025Q1
    periodLabel(r) {
      if (this.kdjType === '0') return fmtSlash(r.tradeDate);
      if (this.kdjType === '1') return fmtSlash(r.tradeDateMin) + '~' + fmtSlash(r.tradeDateMax);
      if (this.kdjType === '2') return fmtSlash(String(r.tradeDate).slice(0, 6));
      const y = String(r.tradeDateMin).slice(0, 4);
      const q = Math.ceil(+String(r.tradeDateMax).slice(4, 6) / 3);
      return y + 'Q' + q;
    },
    renderChart(rows, T) {
      const key = T ? T.key : 'cut';
      const elId = T ? T.el : 'kdjChart';
      const inst = this._chartInst(key);
      const labels = rows.map(r => this.periodLabel(r));
      const bars = rows.map(r => ({ open: +r.open, close: +r.close, low: +r.low, high: +r.high }));
      const kdj = rows.map((r, i) => ({
        idx: i,
        k: +r.k, d: +r.d, j: +r.j,
        crossType: r.crossType || null,
        crossValue: r.crossValue === null || r.crossValue === undefined ? null : +r.crossValue
      }));
      inst.cd = { labels, bars, kdj };

      const last = kdj[kdj.length - 1];
      // KDJ 标题：N/M1/M2 + 最新 K/D/J，各自着色（K 黄、D 紫、J 蓝）
      const kdjTitle = '{t|KDJ(' + this.params.n + ',' + this.params.m1 + ',' + this.params.m2 + ')：}'
        + ' {k|K:' + fmt2(last.k) + '} {d|D:' + fmt2(last.d) + '} {j|J:' + fmt2(last.j) + '}';

      // 默认视野：截止周期钉在最右端；数据不足一屏则左对齐展示全部
      const len = labels.length;
      const start = len <= WINDOW_SIZE ? 0 : (1 - WINDOW_SIZE / len) * 100;

      // 网格全部用 graphic 画成屏幕固定的「闭合实线方块」：
      // 价格图 3 列 × 4 行、KDJ 图 3 列 × 2 行，四边闭合实线
      // y 轴上下各留 8% 空白，极值不贴边
      const pad = v => (v.max - v.min) * 0.08;
      const plainYAxis = {
        scale: true,
        min: v => v.min - pad(v),
        max: v => v.max + pad(v),
        axisLabel: { show: false },
        axisLine: { show: false },
        axisTick: { show: false },
        splitLine: { show: false }
      };

      if (inst.chart) inst.chart.dispose();
      inst.chart = echarts.init(document.getElementById(elId));
      const cw = document.getElementById(elId).clientWidth;
      const gl = 40, gw = cw - 80;
      // graphic 元素必须全部带唯一 id——无 id 元素按索引合并，
      // 会导致 updateRangeMarks 里按 id 更新的动态文本对位错乱
      const gridRects = [[30, 230, 4], [360, 160, 2]];
      const gridLines = [];
      let glId = 0;
      for (const [gt, gh, rows_] of gridRects) {
        gridLines.push({
          id: 'gl' + glId++,
          type: 'rect', z: 1, silent: true,
          shape: { x: gl, y: gt, width: gw, height: gh },
          style: { fill: 'none', stroke: '#dcdfe6', lineWidth: 1 }
        });
        for (let r = 1; r < rows_; r++) {
          const y = gt + (gh * r) / rows_;
          gridLines.push({
            id: 'gl' + glId++,
            type: 'line', z: 1, silent: true,
            shape: { x1: gl, y1: y, x2: gl + gw, y2: y },
            style: { stroke: '#f0f0f0', lineWidth: 1 }
          });
        }
        for (const x of [gl + gw / 3, gl + (2 * gw) / 3]) {
          gridLines.push({
            id: 'gl' + glId++,
            type: 'line', z: 1, silent: true,
            shape: { x1: x, y1: gt, x2: x, y2: gt + gh },
            style: { stroke: '#f0f0f0', lineWidth: 1 }
          });
        }
      }
      inst.chart.setOption({
        animation: false,
        axisPointer: { link: [{ xAxisIndex: 'all' }] },
        tooltip: {
          trigger: 'axis',
          axisPointer: { type: 'cross' },
          formatter: ps => {
            if (!ps || !ps.length) return '';
            let html = '周期：' + ps[0].axisValue;
            for (const p of ps) {
              if (p.seriesName === 'K线') {
                const v = p.value; // [idx, open, close, low, high]
                html += '<br/>' + p.marker + ' 开：' + fmt2(v[1]) + ' 收：' + fmt2(v[2]) + ' 低：' + fmt2(v[3]) + ' 高：' + fmt2(v[4]);
              } else {
                html += '<br/>' + p.marker + ' ' + p.seriesName + '：' + fmt2(p.value);
              }
            }
            return html;
          }
        },
        // K/D/J 图例：与 KDJ(9,3,3) 标题同一行，靠右
        legend: { data: ['K', 'D', 'J'], top: 328, right: 40 },
        grid: [
          { left: 40, right: 40, top: 30, height: 230 },
          { left: 40, right: 40, top: 360, height: 160 }
        ],
        xAxis: [
          {
            type: 'category', gridIndex: 0, data: labels,
            axisLabel: { show: false },
            axisTick: { show: false },
            axisLine: { show: false }
          },
          { type: 'category', gridIndex: 1, data: labels, axisLabel: { show: false }, axisTick: { show: false }, axisLine: { show: false } }
        ],
        yAxis: [
          Object.assign({ gridIndex: 0 }, plainYAxis),
          Object.assign({ gridIndex: 1 }, plainYAxis)
        ],
        dataZoom: [{
          type: 'inside',
          xAxisIndex: [0, 1],
          moveOnMouseMove: true,
          moveOnMouseWheel: false,
          // 滚轮 = 缩放日期区间（比例尺收放）；拖动 = 平移
          zoomOnMouseWheel: true,
          throttle: 0,
          start, end: 100
        }],
        series: [
          {
            name: 'K线', type: 'candlestick', xAxisIndex: 0, yAxisIndex: 0,
            data: bars.map(b => [b.open, b.close, b.low, b.high]),
            itemStyle: { color: '#f56c6c', color0: '#67c23a', borderColor: '#f56c6c', borderColor0: '#67c23a' }
          },
          { name: 'K', type: 'line', xAxisIndex: 1, yAxisIndex: 1, smooth: true, showSymbol: false, color: COLOR_K, data: kdj.map(p => p.k) },
          { name: 'D', type: 'line', xAxisIndex: 1, yAxisIndex: 1, smooth: true, showSymbol: false, color: COLOR_D, data: kdj.map(p => p.d) },
          { name: 'J', type: 'line', xAxisIndex: 1, yAxisIndex: 1, smooth: true, showSymbol: false, color: COLOR_J, lineStyle: { opacity: 0.6 }, data: kdj.map(p => p.j) }
        ],
        graphic: gridLines.concat([
          {
            id: 'kdjTitle',
            type: 'text', left: 40, top: 332,
            style: {
              text: kdjTitle,
              rich: {
                t: { fill: '#303133', fontWeight: 600, fontSize: 13 },
                k: { fill: COLOR_K, fontWeight: 600, fontSize: 13 },
                d: { fill: COLOR_D, fontWeight: 600, fontSize: 13 },
                j: { fill: COLOR_J, fontWeight: 600, fontSize: 13 }
              }
            }
          },
          // 最高 / 最低交汇点：放在 KDJ 图最左（上 / 下），随视野动态更新
          { id: 'crossHi', type: 'text', left: 0, top: 362, style: { text: '', fill: '#909399', fontSize: 12 } },
          { id: 'crossLo', type: 'text', left: 0, top: 503, style: { text: '', fill: '#909399', fontSize: 12 } },
          // 视野两端日期：放在两图中间的左 / 右两侧，随拖动更新
          { id: 'dateL', type: 'text', left: 40, top: 298, style: { text: '', fill: '#909399', fontSize: 12 } },
          { id: 'dateR', type: 'text', right: 40, top: 298, style: { text: '', fill: '#909399', fontSize: 12, align: 'right' } }
        ])
      }, true);

      // 拖动时每次 datazoom 都做 setOption 会掉帧：用 rAF 合帧，每帧最多重算一次标注
      inst.chart.on('datazoom', () => {
        if (inst.raf) return;
        inst.raf = requestAnimationFrame(() => {
          inst.raf = null;
          this._updateRangeMarks(key);
        });
      });
      this._updateRangeMarks(key);
    },
    // 可见范围动态标注：价格最高/最低（点+短横线样式）、KDJ 最高/最低交汇（左侧文字）
    _updateRangeMarks(key) {
      const inst = this._chartInst(key);
      if (!inst.chart || !inst.cd) return;
      const { labels, bars, kdj } = inst.cd;
      const len = bars.length;
      const dz = inst.chart.getOption().dataZoom[0];
      // 可见下标：startValue 可能是数字下标、字符串类目值或空，统一转成下标
      const toIdx = (v, pct, isEnd) => {
        if (typeof v === 'number' && isFinite(v)) return v;
        if (typeof v === 'string') {
          const i = labels.indexOf(v);
          if (i >= 0) return i;
        }
        return isEnd ? Math.ceil(len * pct / 100) - 1 : Math.floor(len * pct / 100);
      };
      let si = toIdx(dz.startValue, dz.start, false);
      let ei = toIdx(dz.endValue, dz.end, true);
      si = Math.max(0, Math.min(len - 1, Math.round(si)));
      ei = Math.max(0, Math.min(len - 1, Math.round(ei)));
      if (ei < si) ei = si;

      let hi = -Infinity, lo = Infinity, hiIdx = si, loIdx = si;
      for (let i = si; i <= ei; i++) {
        if (bars[i].high > hi) { hi = bars[i].high; hiIdx = i; }
        if (bars[i].low < lo)  { lo = bars[i].low;  loIdx = i; }
      }
      let cHi = null, cLo = null;
      for (let i = si; i <= ei; i++) {
        const p = kdj[i];
        if (!p.crossType) continue;
        if (!cHi || p.crossValue > cHi.crossValue) cHi = p;
        if (!cLo || p.crossValue < cLo.crossValue) cLo = p;
      }

      // 价格极值：极值点小圆点 + 一截短横线 + 线端数值
      // 线段端点夹在可见窗口内，避免极值在视野边缘时整段被裁剪
      const seg = 4;
      const extremeLine = {
        silent: true,
        symbol: ['circle', 'none'],
        symbolSize: 5,
        lineStyle: { color: '#909399', width: 1 },
        emphasis: { disabled: true }
      };
      const priceLines = [
        [
          { coord: [hiIdx, hi] },
          { coord: [Math.min(hiIdx + seg, ei), hi],
            label: { show: true, formatter: fmt2(hi), position: 'end', color: '#303133', fontWeight: 600, distance: 4 } }
        ],
        [
          { coord: [loIdx, lo] },
          { coord: [Math.max(loIdx - seg, si), lo],
            label: { show: true, formatter: fmt2(lo), position: 'start', color: '#303133', fontWeight: 600, distance: 4 } }
        ]
      ];

      // KDJ：金叉 / 死叉锚形小图标（金叉金色、死叉灰色），不带数字，统一放在线条下方
      const kdjMarks = kdj.filter(p => p.crossType).map(p => ({
        coord: [p.idx, p.k],
        symbol: ANCHOR_PATH,
        symbolSize: 14,
        symbolOffset: [0, 12],
        itemStyle: {
          color: 'rgba(0,0,0,0)',
          borderColor: p.crossType === 'gold' ? COLOR_ANCHOR_GOLD : COLOR_ANCHOR_GRAY,
          borderWidth: 1.5
        },
        label: { show: false }
      }));

      inst.chart.setOption({
        series: [
          { name: 'K线', markLine: Object.assign({ data: priceLines }, extremeLine), markPoint: { data: [] } },
          { name: 'K', markPoint: { data: kdjMarks } }
        ],
        graphic: [
          { id: 'crossHi', style: { text: cHi ? fmt2(cHi.crossValue) : '' } },
          { id: 'crossLo', style: { text: cLo ? fmt2(cLo.crossValue) : '' } },
          { id: 'dateL', style: { text: labels[si] } },
          { id: 'dateR', style: { text: labels[ei] } }
        ]
      });
    },
    // 图表实例存取（cut=截止周期图，latest=最新周期图）
    _chartInst(key) {
      if (!this._charts) this._charts = {};
      if (!this._charts[key]) this._charts[key] = { chart: null, cd: null, raf: null };
      return this._charts[key];
    },
    // 最新周期序列：以 periods[0] 为截止（与 /kdj/periods 口径一致），复用同一套渲染
    async loadLatestChart(code) {
      const latest = this.periods[0];
      if (!latest) return;
      const q = this.buildQuery();
      q.set('code', code);
      if (this.kdjType === '0') q.set('tradeDate', latest.tradeDate);
      else if (this.kdjType === '1') { q.set('tradeDateMin', latest.tradeDateMin); q.set('tradeDateMax', latest.tradeDateMax); }
      else if (this.kdjType === '2') q.set('tradeDate', latest.tradeDate);
      else { q.set('tradeDateMin', latest.tradeDateMin); q.set('tradeDateMax', latest.tradeDateMax); }
      try {
        const rows = await getJson('/kdj/series?' + q.toString());
        if (!rows.length) { this.disposeLatestChart(); return; }
        this.renderChart(rows, { el: 'kdjChartLatest', key: 'latest' });
      } catch (e) {
        this.disposeLatestChart();
      }
    },
    disposeLatestChart() {
      const inst = this._charts && this._charts['latest'];
      if (inst && inst.chart) inst.chart.dispose();
      if (inst) inst.chart = null;
    },
    // 关闭全部图表（「点击查询后关闭」与切换周期类型共用）
    closeCharts() {
      if (this._charts) {
        Object.keys(this._charts).forEach(k => {
          const inst = this._charts[k];
          if (inst && inst.chart) inst.chart.dispose();
        });
      }
      this._charts = {};
      this.chartStock = null;
    },
    // 「其他参数」localStorage 持久化
    persistChartPrefs() {
      localStorage.setItem('ts_close_chart_on_query', this.closeChartOnQuery ? '1' : '0');
      localStorage.setItem('ts_show_latest_chart', this.showLatestChart ? '1' : '0');
    },
    onCloseChartPrefChange() {
      this.persistChartPrefs();
    },
    onShowLatestPrefChange() {
      this.persistChartPrefs();
      if (!this.chartStock) return;
      this.$nextTick(() => {
        if (this.showLatestChart && this.latestChartVisible) {
          this.loadLatestChart(this.chartStock.code);
        } else {
          this.disposeLatestChart();
        }
      });
    }
  }
}).use(ElementPlus, { locale: ElementPlusLocaleZhCn }).mount('#app');
