/* ==========================================================================
   QUANT ENGINE v10.0 — front-end behaviour
   Chart rendering, live terminal, signal simulation, performance report.
   No external dependencies.
   ========================================================================== */
(function () {
  'use strict';

  var $ = function (id) { return document.getElementById(id); };

  /* --------------------------------------------------------------- utils */
  function mulberry32(a) {
    return function () {
      a |= 0; a = (a + 0x6D2B79F5) | 0;
      var t = Math.imul(a ^ (a >>> 15), 1 | a);
      t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
  }
  function pad(n) { return n < 10 ? '0' + n : '' + n; }
  function fmt(n, d) { return Number(n).toFixed(d === undefined ? 2 : d); }
  function group(n) { return String(Math.round(n)).replace(/\B(?=(\d{3})+(?!\d))/g, ','); }
  function compact(n) {
    if (n >= 1e6) return (n / 1e6).toFixed(2) + 'M';
    if (n >= 1e3) return (n / 1e3).toFixed(1) + 'K';
    return String(Math.round(n));
  }

  /* ---------------------------------------------------------------- clock */
  var clockEl = $('clock');
  function tickClock() {
    var d = new Date();
    clockEl.textContent = pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds());
  }
  tickClock();
  setInterval(tickClock, 1000);

  /* ------------------------------------------------------------ market data */
  var TOTAL = 980;
  var candles = (function () {
    var rnd = mulberry32(20260812);
    var out = [];
    var price = 96;
    var start = new Date(2026, 8, 27);
    start.setDate(start.getDate() - TOTAL);
    for (var i = 0; i < TOTAL; i++) {
      var drift = 0.0016 + Math.sin(i / 46) * 0.0022;
      var shock = (rnd() - 0.5) * 0.036;
      var open = price;
      var close = Math.max(14, open * (1 + drift + shock));
      var hi = Math.max(open, close) * (1 + rnd() * 0.016);
      var lo = Math.min(open, close) * (1 - rnd() * 0.016);
      var vol = Math.round(420000 + rnd() * 2600000 + Math.abs(close - open) * 260000);
      var day = new Date(start.getTime());
      day.setDate(day.getDate() + i + 1);
      out.push({
        i: i, date: day,
        o: open, c: close, h: hi, l: lo, v: vol,
        ma5: 0, ma10: 0, ma20: 0
      });
      price = close;
    }
    function ma(k) {
      for (var n = 0; n < TOTAL; n++) {
        if (n < k - 1) continue;
        var s = 0;
        for (var m = n - k + 1; m <= n; m++) s += out[m].c;
        out[n]['ma' + k] = s / k;
      }
    }
    ma(5); ma(10); ma(20);
    return out;
  })();

  /* MACD 三项恒等关系：MACD = 2 × (DIFF − DEA) */
  function setMacd(diffVal, macdVal) {
    var deaVal = diffVal - macdVal / 2;
    $('vDiff').textContent = fmt(diffVal, 4);
    $('vDea').textContent = fmt(deaVal, 4);
    $('vMacd').textContent = fmt(macdVal, 4);
  }
  setMacd(0.0182, 0.0874);

  function rollMacd() {
    setMacd(
      (Math.random() - 0.5) * 0.09,
      (Math.random() - 0.5) * 0.16
    );
  }

  /* ----------------------------------------------------------------- chart */
  var canvas = $('chart');
  var ctx = canvas.getContext('2d');
  var tip = $('chartTip');
  var tipDate = $('tipDate');
  var tipGrid = $('tipGrid');
  var slider = $('range');
  var rangeLabel = $('rangeLabel');

  var state = { visible: 150, viewEnd: TOTAL, hover: -1 };
  var layout = { w: 0, h: 0 };

  function visibleCount() {
    return window.innerWidth < 720 ? 84 : (window.innerWidth < 1180 ? 118 : 150);
  }
  function maxOffset() { return Math.max(0, TOTAL - state.visible); }

  function resize() {
    var dpr = window.devicePixelRatio || 1;
    var rect = canvas.getBoundingClientRect();
    layout.w = rect.width;
    layout.h = rect.height;
    canvas.width = Math.round(rect.width * dpr);
    canvas.height = Math.round(rect.height * dpr);
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);

    var atEnd = state.viewEnd >= TOTAL;
    state.visible = visibleCount();
    slider.max = String(maxOffset());
    if (atEnd || Number(slider.value) > maxOffset()) slider.value = String(maxOffset());
    state.viewEnd = TOTAL - (maxOffset() - Number(slider.value));
    draw();
  }

  function windowRange() {
    var end = state.viewEnd;
    var start = Math.max(0, end - state.visible);
    return { start: start, end: end };
  }

  function draw() {
    var W = layout.w, H = layout.h;
    if (!W || !H) return;
    ctx.clearRect(0, 0, W, H);

    var padR = 62, padL = 6, padT = 14, padB = 24;
    var plotW = W - padL - padR;
    var volH = Math.round((H - padT - padB) * 0.2);
    var gap = 16;
    var priceH = H - padT - padB - volH - gap;

    var r = windowRange();
    var slice = candles.slice(r.start, r.end);
    if (!slice.length) return;

    var hi = -Infinity, lo = Infinity, vmax = 0;
    slice.forEach(function (d) {
      if (d.h > hi) hi = d.h;
      if (d.l < lo) lo = d.l;
      if (d.v > vmax) vmax = d.v;
    });
    var padRatio = (hi - lo) * 0.08;
    hi += padRatio;
    lo -= padRatio;

    function yOf(p) { return padT + (hi - p) / (hi - lo) * priceH; }
    function xOf(k) { return padL + (k + 0.5) * (plotW / slice.length); }
    var step = plotW / slice.length;
    var bodyW = Math.max(1.4, Math.min(9, step * 0.62));

    /* grid + price axis */
    ctx.save();
    ctx.strokeStyle = 'rgba(60,60,67,0.07)';
    ctx.fillStyle = '#aeaeb2';
    ctx.font = '500 10px ui-monospace, SFMono-Regular, Menlo, monospace';
    ctx.lineWidth = 1;
    ctx.textAlign = 'left';
    ctx.textBaseline = 'middle';
    var rows = 5;
    for (var g = 0; g <= rows; g++) {
      var yy = Math.round(padT + (priceH / rows) * g) + 0.5;
      ctx.beginPath();
      ctx.moveTo(padL, yy);
      ctx.lineTo(padL + plotW, yy);
      ctx.stroke();
      var val = hi - ((hi - lo) / rows) * g;
      ctx.fillText(fmt(val, 1), padL + plotW + 9, yy);
    }
    /* volume axis rule */
    var volTop = padT + priceH + gap;
    ctx.beginPath();
    ctx.moveTo(padL, Math.round(volTop) + 0.5);
    ctx.lineTo(padL + plotW, Math.round(volTop) + 0.5);
    ctx.stroke();
    ctx.fillText(compact(vmax), padL + plotW + 9, volTop + 8);
    ctx.restore();

    /* candles */
    slice.forEach(function (d, k) {
      var up = d.c >= d.o;
      var x = xOf(k);
      var yO = yOf(d.o), yC = yOf(d.c);
      var top = Math.min(yO, yC), bot = Math.max(yO, yC);
      var hh = Math.max(1, bot - top);

      ctx.strokeStyle = up ? 'rgba(28,28,30,0.72)' : 'rgba(28,28,30,0.9)';
      ctx.lineWidth = 1;
      ctx.beginPath();
      ctx.moveTo(Math.round(x) + 0.5, yOf(d.h));
      ctx.lineTo(Math.round(x) + 0.5, yOf(d.l));
      ctx.stroke();

      if (up) {
        ctx.fillStyle = 'rgba(255,255,255,0.95)';
        ctx.fillRect(x - bodyW / 2, top, bodyW, hh);
        ctx.strokeRect(Math.round(x - bodyW / 2) + 0.5, Math.round(top) + 0.5, Math.round(bodyW) - 1, Math.max(1, Math.round(hh) - 1));
      } else {
        ctx.fillStyle = 'rgba(28,28,30,0.88)';
        ctx.fillRect(x - bodyW / 2, top, bodyW, hh);
      }
    });

    /* moving averages */
    function line(key, color, dash, width) {
      ctx.save();
      ctx.strokeStyle = color;
      ctx.lineWidth = width;
      if (dash) ctx.setLineDash(dash);
      ctx.beginPath();
      var started = false;
      slice.forEach(function (d, k) {
        var v = d[key];
        if (!v) return;
        var x = xOf(k), y = yOf(v);
        if (!started) { ctx.moveTo(x, y); started = true; } else { ctx.lineTo(x, y); }
      });
      ctx.stroke();
      ctx.restore();
    }
    line('ma5', 'rgba(28,28,30,0.85)', null, 1.4);
    line('ma10', 'rgba(28,28,30,0.45)', [4, 3], 1.2);
    line('ma20', 'rgba(28,28,30,0.28)', [2, 4], 1.2);

    /* volume bars */
    slice.forEach(function (d, k) {
      var up = d.c >= d.o;
      var hgt = Math.max(1, (d.v / vmax) * (volH - 12));
      var x = xOf(k);
      ctx.fillStyle = up ? 'rgba(28,28,30,0.22)' : 'rgba(28,28,30,0.42)';
      ctx.fillRect(x - bodyW / 2, volTop + (volH - 12) - hgt + 12, bodyW, hgt);
    });

    /* time axis */
    ctx.save();
    ctx.fillStyle = '#aeaeb2';
    ctx.font = '500 10px ui-monospace, SFMono-Regular, Menlo, monospace';
    ctx.textBaseline = 'top';
    var ticks = 6;
    for (var t = 0; t <= ticks; t++) {
      var idx = Math.min(slice.length - 1, Math.round((slice.length - 1) * (t / ticks)));
      var dd = slice[idx].date;
      var label = pad(dd.getMonth() + 1) + '-' + pad(dd.getDate());
      var lx = xOf(idx);
      ctx.textAlign = t === 0 ? 'left' : (t === ticks ? 'right' : 'center');
      ctx.fillText(label, lx, H - padB + 7);
    }
    ctx.restore();

    /* crosshair */
    if (state.hover >= 0 && state.hover < slice.length) {
      var hx = Math.round(xOf(state.hover)) + 0.5;
      ctx.save();
      ctx.strokeStyle = 'rgba(28,28,30,0.28)';
      ctx.lineWidth = 1;
      ctx.setLineDash([3, 3]);
      ctx.beginPath();
      ctx.moveTo(hx, padT);
      ctx.lineTo(hx, volTop + volH);
      ctx.stroke();
      ctx.restore();
    }
    updateRangeLabel();
  }

  function updateRangeLabel() {
    rangeLabel.textContent = (state.viewEnd) + ' / ' + TOTAL;
    $('rangeTag').textContent = '980 根 K线 · 4Y';
  }

  function setHover(clientX) {
    var rect = canvas.getBoundingClientRect();
    var padR = 62, padL = 6;
    var plotW = rect.width - padL - padR;
    var r = windowRange();
    var sliceLen = r.end - r.start;
    var rel = clientX - rect.left - padL;
    var k = Math.floor(rel / (plotW / sliceLen));
    if (k < 0 || k >= sliceLen) { clearHover(); return; }
    state.hover = k;
    var d = candles[r.start + k];

    tipDate.textContent = d.date.getFullYear() + '-' +
      pad(d.date.getMonth() + 1) + '-' + pad(d.date.getDate());
    var rows = [
      ['开', fmt(d.o, 2)], ['收', fmt(d.c, 2)],
      ['低', fmt(d.l, 2)], ['高', fmt(d.h, 2)],
      ['MA5', d.ma5 ? fmt(d.ma5, 2) : '—'], ['MA10', d.ma10 ? fmt(d.ma10, 2) : '—'],
      ['MA20', d.ma20 ? fmt(d.ma20, 2) : '—'], ['量', group(d.v)]
    ];
    tipGrid.innerHTML = rows.map(function (row) {
      return '<dt>' + row[0] + '</dt><dd class="mono">' + row[1] + '</dd>';
    }).join('');

    /* keep tooltip inside panel */
    var wrap = canvas.parentElement.getBoundingClientRect();
    var left = Math.min(clientX - wrap.left + 18, wrap.width - tip.offsetWidth - 10);
    tip.style.left = Math.max(10, left) + 'px';
    tip.classList.add('on');
    draw();
  }
  function clearHover() {
    state.hover = -1;
    tip.classList.remove('on');
    draw();
  }

  canvas.addEventListener('pointermove', function (e) { setHover(e.clientX); });
  canvas.addEventListener('pointerleave', clearHover);

  slider.addEventListener('input', function () {
    state.viewEnd = TOTAL - (maxOffset() - Number(slider.value));
    clearHover();
  });

  window.addEventListener('resize', function () {
    clearTimeout(window.__qt);
    window.__qt = setTimeout(resize, 120);
  });
  if (window.ResizeObserver) {
    new ResizeObserver(function () { resize(); }).observe(canvas.parentElement);
  }

  /* ------------------------------------------------------------- terminal */
  var SYMS = ['BTC', 'ETH', 'SOL', 'BNB', 'XRP', 'DOGE', 'ADA'];
  var logScroll = $('logScroll');
  var logCount = $('logCount');
  var logTotal = 0;
  var rndLog = mulberry32(77123);

  function pick(a) { return a[Math.floor(rndLog() * a.length)]; }
  function ohlc() {
    var o = 40 + rndLog() * 470, c = 40 + rndLog() * 470;
    return ' O:' + fmt(o, 2) + ' H:' + fmt(Math.max(o, c) + rndLog() * 30, 2) +
      ' L:' + fmt(Math.min(o, c) - rndLog() * 20, 2) + ' C:' + fmt(c, 2) +
      ' V:' + group(rndLog() * 99000);
  }
  function makeLine() {
    var sym = pick(SYMS) + '/USDT';
    var roll = rndLog();
    if (roll < 0.22) return { lv: 'OK', msg: sym + ' 回测引擎就绪' };
    if (roll < 0.38) return { lv: 'INFO', msg: sym + ' 行情订阅已建立' };
    if (roll < 0.5) return { lv: 'INFO', msg: sym + ' 订单簿深度扫描' };
    if (roll < 0.6) return { lv: 'INFO', msg: sym + ' 均线交叉检测中' };
    if (roll < 0.68) return { lv: 'INFO', msg: sym + ' 资金费率结算' };
    if (roll < 0.75) return { lv: 'INFO', msg: sym + ' 风控模块巡检' };
    if (roll < 0.82) return { lv: 'OK', msg: sym + ' 策略信号已分发' };
    if (roll < 0.88) return { lv: 'OK', msg: sym + ' MACD 柱状图更新' };
    if (roll < 0.92) return { lv: 'OK', msg: sym + ' 信号计算完成' };
    if (roll < 0.95) return { lv: 'WARN', msg: sym + ' 波动率异常上升' };
    if (roll < 0.975) return { lv: 'WARN', msg: sym + ' 滑点超出阈值' };
    return { lv: 'ERR', msg: sym + ' 连接延迟 >200ms' };
  }
  function nowStamp() {
    var d = new Date();
    return pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds());
  }
  function pushLog(lv, msg, time, ohlcText) {
    var el = document.createElement('div');
    el.className = 'log-line';
    el.innerHTML = '<span class="t">' + time + '</span>' +
      '<span class="lv ' + lv + '">[' + lv + ']</span>' +
      '<span class="msg">' + msg + '</span>' +
      (ohlcText ? '<span class="ohlc">' + ohlcText + '</span>' : '');
    logScroll.appendChild(el);
    logTotal++;
    logCount.textContent = logTotal + ' lines';
    while (logScroll.children.length > 420) logScroll.removeChild(logScroll.firstChild);
    logScroll.scrollTop = logScroll.scrollHeight;
  }
  function autoLog() {
    var l = makeLine();
    pushLog(l.lv, l.msg, nowStamp(), rndLog() < 0.35 ? ohlc() : '');
  }

  /* boot sequence — mirrors the engine startup banner */
  pushLog('INFO', '正在订阅行情数据源…', '13:17:17', ohlc());
  pushLog('OK', 'QUANT ENGINE v10.0 启动成功', '13:17:17');
  pushLog('OK', '数据加载完成: 980 根 K线', '13:17:17', ohlc());
  for (var b = 0; b < 24; b++) autoLog();
  setInterval(autoLog, 2100);

  /* ------------------------------------------------- strategy interactions */
  var strategyCount = 0;
  var strategyEl = $('strategyCount');

  function flash(btn) {
    btn.animate(
      [{ transform: 'scale(1)' }, { transform: 'scale(0.95)' }, { transform: 'scale(1)' }],
      { duration: 260, easing: 'cubic-bezier(0.32,0.72,0,1)' }
    );
  }
  function addStrategies(n, label) {
    strategyCount += n;
    strategyEl.textContent = strategyCount + ' 策略';
    pushLog('OK', label + ' 完成，新增 ' + n + ' 条策略', nowStamp());
    for (var i = 0; i < n; i++) {
      var names = ['趋势跟随', '放量突破', '金叉追涨', '止损反转', '均值回归', '波动率过滤'];
      pushLog('WARN', '反向做空策略 [' + pick(names) + '] 已生成', nowStamp());
    }
    jitterSignal();
  }
  function jitterSignal() {
    var score = 44 + Math.round(Math.random() * 34);
    $('mScore').textContent = score;
    $('mWin').innerHTML = fmt(52 + Math.random() * 14, 1) + '<small>%</small>';
    $('mRr').textContent = fmt(1.7 + Math.random() * 1.1, 2);
    $('mRisk').textContent = Math.round(12 + Math.random() * 32);
    $('mPos').innerHTML = Math.round(30 + Math.random() * 45) + '<small>%</small>';
    $('mEv').innerHTML = fmt(48 + Math.random() * 42, 2) + '<small>%</small>';
    $('mVol').innerHTML = fmt(0.6 + Math.random() * 1.6, 2) + '<small>%</small>';
    var long = score >= 50;
    $('sigVerdict').textContent = long ? '做多' : '做空';
    $('sigVerdictEn').textContent = long ? 'LONG' : 'SHORT';
  }

  $('btnGen').addEventListener('click', function () {
    flash(this);
    pushLog('INFO', 'AI 正在分析 ' + $('fSymbol').value + ' 历史特征…', nowStamp());
    setTimeout(function () { addStrategies(3, 'AI 生成策略'); }, 420);
  });
  $('btnGen10').addEventListener('click', function () {
    flash(this);
    addStrategies(10, '批量生成');
  });
  $('btnShort').addEventListener('click', function () {
    flash(this);
    addStrategies(1, '反向做空');
  });
  function fetchData(btn) {
    flash(btn);
    pushLog('INFO', '正在拉取 ' + $('fSymbol').value + ' 行情数据…', nowStamp());
    setTimeout(function () {
      pushLog('OK', '数据加载完成: 980 根 K线', nowStamp(), ohlc());
      jitterSignal();
      rollMacd();
    }, 520);
  }
  $('btnFetch').addEventListener('click', function () { fetchData(this); });
  $('btnFetch2').addEventListener('click', function () { fetchData(this); });
  $('btnSync').addEventListener('click', function () {
    this.classList.remove('spin');
    void this.offsetWidth;
    this.classList.add('spin');
    fetchData($('btnFetch'));
  });
  $('fSymbol').addEventListener('change', function () {
    pushLog('INFO', '切换标的至 ' + this.value, nowStamp());
    jitterSignal();
  });

  /* -------------------------------------------------- performance report */
  var trades = [
    {
      at: 24, label: '2025-08-12', dir: '做空',
      macd: '0.398', diff: '16.4952', dea: '16.2962', volume: '2,984,063',
      o: '273.74', c: '272.82', l: '270.22', h: '274.32',
      ma5: '271.8', ma10: '267.52', ma20: '262.73'
    },
    {
      at: 56, label: '2025-11-04', dir: '做空',
      macd: '-0.126', diff: '8.3317', dea: '8.3947', volume: '1,742,880',
      o: '266.11', c: '274.55', l: '264.02', h: '275.90',
      ma5: '269.4', ma10: '265.18', ma20: '261.44'
    },
    {
      at: 84, label: '2026-03-18', dir: '做多',
      macd: '0.245', diff: '12.8804', dea: '12.6356', volume: '3,406,120',
      o: '281.30', c: '277.64', l: '276.05', h: '283.77',
      ma5: '279.2', ma10: '274.86', ma20: '270.05'
    }
  ];

  var timeline = $('timeline');
  var trackFill = $('trackFill');
  var reportDetail = $('reportDetail');
  var activeIdx = 0;

  function renderDetail(t) {
    reportDetail.innerHTML =
      '<div class="detail-block">' +
        '<div class="bk">MACD 指标</div>' +
        '<div class="brow"><span>MACD</span><span class="mono">' + t.macd + '</span></div>' +
        '<div class="brow"><span>DIFF</span><span class="mono">' + t.diff + '</span></div>' +
        '<div class="brow"><span>DEA</span><span class="mono">' + t.dea + '</span></div>' +
      '</div>' +
      '<div class="detail-block">' +
        '<div class="bk">成交量</div>' +
        '<div class="brow"><span>' + t.label + '</span><span class="mono">' + t.volume + '</span></div>' +
      '</div>' +
      '<div class="detail-block">' +
        '<div class="bk">K线</div>' +
        '<div class="brow"><span>open</span><span class="mono">' + t.o + '</span></div>' +
        '<div class="brow"><span>close</span><span class="mono">' + t.c + '</span></div>' +
        '<div class="brow"><span>lowest</span><span class="mono">' + t.l + '</span></div>' +
        '<div class="brow"><span>highest</span><span class="mono">' + t.h + '</span></div>' +
      '</div>' +
      '<div class="detail-block">' +
        '<div class="bk">均线 / 方向</div>' +
        '<div class="brow"><span>MA5</span><span class="mono">' + t.ma5 + '</span></div>' +
        '<div class="brow"><span>MA10</span><span class="mono">' + t.ma10 + '</span></div>' +
        '<div class="brow"><span>MA20</span><span class="mono">' + t.ma20 + '</span></div>' +
        '<div class="brow"><span>方向</span><span>' + t.dir + '</span></div>' +
      '</div>';
  }

  function buildTimeline() {
    Array.prototype.slice.call(timeline.querySelectorAll('.marker')).forEach(function (m) { m.remove(); });
    trades.forEach(function (t, idx) {
      var m = document.createElement('div');
      m.className = 'marker' + (idx === activeIdx ? ' active' : '');
      m.style.left = t.at + '%';
      m.innerHTML = '<span class="m-dir">' + t.dir + '</span>' +
        '<button type="button" aria-label="' + t.label + '"><i></i></button>' +
        '<span class="m-label mono">' + t.label + '</span>';
      m.querySelector('button').addEventListener('click', function () {
        activeIdx = idx;
        buildTimeline();
        renderDetail(t);
        pushLog('INFO', '查看回测交易点 ' + t.label, nowStamp());
      });
      timeline.appendChild(m);
    });
    trackFill.style.width = trades[activeIdx].at + '%';
  }

  buildTimeline();
  renderDetail(trades[activeIdx]);

  /* reduced motion: stop the log stream animation pressure */
  if (window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
    document.querySelectorAll('.backdrop i').forEach(function (el) { el.style.animation = 'none'; });
  }

  resize();
})();
