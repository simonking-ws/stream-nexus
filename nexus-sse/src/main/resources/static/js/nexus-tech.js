/**
 * Stream Nexus · 科技感动效（admin / console 共用）
 *
 * <p>做三件事：
 * <ol>
 *   <li>注入背景层：网格、光晕、扫描线，以及一个粒子连线网络 Canvas；</li>
 *   <li>{@code NexusFX.countTo(el, n)}：数字滚动，指标刷新时不再硬跳；</li>
 *   <li>{@code NexusFX.flash(el)}：数值变化时闪一下，提示「这里刚变了」。</li>
 *   <li>主题切换：在顶栏挂一个深 / 亮切换按钮，选择记在 {@code localStorage}，没选过则跟随系统。</li>
 * </ol>
 *
 * <p>主题只切 {@code <html data-theme>} 这一个属性，配色全在 CSS 变量里（见 nexus-tech.css），
 * 这里只额外管粒子网络的描线颜色（Canvas 画不出来，吃不到 CSS 变量）。
 *
 * <p>尊重 {@code prefers-reduced-motion}：用户要求减弱动效时跳过粒子网络与数字滚动。
 *
 * @author simonking
 */
(function () {
    'use strict';

    var REDUCE = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

    /* ==================== 背景层 ==================== */

    /** 造出背景 DOM：canvas + 网格 + 三个光晕 + 一条扫描线 */
    function buildBackdrop() {
        if (document.getElementById('fxCanvas')) return;

        var frag = document.createDocumentFragment();

        var canvas = document.createElement('canvas');
        canvas.id = 'fxCanvas';
        canvas.className = 'fx-canvas';
        frag.appendChild(canvas);

        var grid = document.createElement('div');
        grid.className = 'fx-grid';
        frag.appendChild(grid);

        ['o1', 'o2', 'o3'].forEach(function (key) {
            var orb = document.createElement('div');
            orb.className = 'fx-orb ' + key;
            frag.appendChild(orb);
        });

        var scan = document.createElement('div');
        scan.className = 'fx-scan';
        frag.appendChild(scan);

        document.body.insertBefore(frag, document.body.firstChild);

        if (!REDUCE) startParticles(canvas);
    }

    /**
     * 粒子连线网络：节点缓慢漂移，距离近的连成线；鼠标附近的节点额外连一条紫色高亮线。
     * 纯装饰，不接收事件（canvas 是 pointer-events:none）。
     */
    function startParticles(canvas) {
        var ctx = canvas.getContext('2d');
        if (!ctx) return;

        var dpr = Math.min(window.devicePixelRatio || 1, 2);
        var w = 0, h = 0;
        var nodes = [];
        var mouse = {x: -9999, y: -9999};
        var LINK = 135;          // 连线距离阈值（px）
        var MOUSE_LINK = 165;    // 鼠标牵引距离阈值（px）

        function resize() {
            w = canvas.clientWidth;
            h = canvas.clientHeight;
            canvas.width = Math.round(w * dpr);
            canvas.height = Math.round(h * dpr);
            ctx.setTransform(dpr, 0, 0, dpr, 0, 0);

            // 密度按面积算，小屏少放几个，避免手机上算不动
            var count = Math.max(24, Math.min(78, Math.round(w * h / 22000)));
            nodes = [];
            for (var i = 0; i < count; i++) {
                nodes.push({
                    x: Math.random() * w,
                    y: Math.random() * h,
                    vx: (Math.random() - .5) * .3,
                    vy: (Math.random() - .5) * .3,
                    r: Math.random() * 1.5 + .8
                });
            }
        }

        function frame() {
            ctx.clearRect(0, 0, w, h);

            // 粒子配色跟着主题走：亮色下浅蓝连线在白底上等于没有，换成深色描线
            var light = document.documentElement.getAttribute('data-theme') === 'light';
            var linkRGB = light ? '15, 23, 42' : '94, 210, 255';
            var mouseRGB = light ? '124, 58, 237' : '168, 85, 247';
            var nodeFill = light ? 'rgba(30, 64, 175, .5)' : 'rgba(125, 211, 252, .7)';

            var i, j, dx, dy, d2, d, a;

            for (i = 0; i < nodes.length; i++) {
                var n = nodes[i];
                n.x += n.vx;
                n.y += n.vy;
                if (n.x < 0 || n.x > w) n.vx *= -1;
                if (n.y < 0 || n.y > h) n.vy *= -1;
            }

            ctx.lineWidth = 1;
            for (i = 0; i < nodes.length; i++) {
                for (j = i + 1; j < nodes.length; j++) {
                    dx = nodes[i].x - nodes[j].x;
                    dy = nodes[i].y - nodes[j].y;
                    d2 = dx * dx + dy * dy;
                    if (d2 > LINK * LINK) continue;
                    d = Math.sqrt(d2);
                    a = (1 - d / LINK) * .3;
                    ctx.strokeStyle = 'rgba(' + linkRGB + ', ' + a.toFixed(3) + ')';
                    ctx.beginPath();
                    ctx.moveTo(nodes[i].x, nodes[i].y);
                    ctx.lineTo(nodes[j].x, nodes[j].y);
                    ctx.stroke();
                }
            }

            // 鼠标牵引：让画面「跟着人动」，不至于像张静态图
            for (i = 0; i < nodes.length; i++) {
                dx = nodes[i].x - mouse.x;
                dy = nodes[i].y - mouse.y;
                d2 = dx * dx + dy * dy;
                if (d2 > MOUSE_LINK * MOUSE_LINK) continue;
                d = Math.sqrt(d2);
                a = (1 - d / MOUSE_LINK) * .45;
                ctx.strokeStyle = 'rgba(' + mouseRGB + ', ' + a.toFixed(3) + ')';
                ctx.beginPath();
                ctx.moveTo(nodes[i].x, nodes[i].y);
                ctx.lineTo(mouse.x, mouse.y);
                ctx.stroke();
            }

            ctx.fillStyle = nodeFill;
            for (i = 0; i < nodes.length; i++) {
                ctx.beginPath();
                ctx.arc(nodes[i].x, nodes[i].y, nodes[i].r, 0, Math.PI * 2);
                ctx.fill();
            }

            requestAnimationFrame(frame);
        }

        var timer = null;
        window.addEventListener('resize', function () {
            clearTimeout(timer);
            timer = setTimeout(resize, 150);
        });
        window.addEventListener('mousemove', function (e) {
            mouse.x = e.clientX;
            mouse.y = e.clientY;
        });
        window.addEventListener('mouseleave', function () {
            mouse.x = mouse.y = -9999;
        });

        resize();
        requestAnimationFrame(frame);
    }

    /* ==================== 运维接口 401 兜底 ==================== */

    /**
     * 运维接口（/sse/admin/**）返回 401，说明登录态没了（session 过期 / 服务重启），
     * 直接跳登录页——否则页面只会一直显示「拉取失败：HTTP 401」，看不出要重新登录。
     *
     * <p>只认这一个前缀：/sse/push 的 401 是 appId / apiKey 不对，属于推送侧自己的错误，
     * 跳登录页会把真正的原因（凭证填错）掩盖掉。
     */
    var rawFetch = window.fetch;
    window.fetch = function (input, init) {
        var url = typeof input === 'string' ? input : ((input && input.url) || '');
        return rawFetch.apply(window, arguments).then(function (resp) {
            if (resp.status === 401 && url.indexOf('/sse/admin/') === 0 && location.pathname !== '/login') {
                location.href = '/login';
            }
            return resp;
        });
    };

    /* ==================== 数字滚动 / 闪动 ==================== */

    /**
     * 数字滚动到目标值（从元素当前数字起算，看不出中间过程就别滚了）
     *
     * @param el 目标元素
     * @param to 目标值
     * @param duration 时长 ms，默认 450
     */
    function countTo(el, to, duration) {
        if (!el) return;
        var from = parseFloat(String(el.textContent).replace(/[^\d.-]/g, ''));
        if (isNaN(from)) from = 0;
        if (el.__fxRaf) cancelAnimationFrame(el.__fxRaf);
        if (REDUCE || from === to) {
            el.textContent = to;
            return;
        }
        duration = duration || 450;
        var start = performance.now();
        (function step(now) {
            var p = Math.min((now - start) / duration, 1);
            var eased = 1 - Math.pow(1 - p, 3);
            el.textContent = Math.round(from + (to - from) * eased);
            if (p < 1) el.__fxRaf = requestAnimationFrame(step);
        })(start);
    }

    /** 闪一下，用于「这个数刚变过」的提示 */
    function flash(el) {
        if (!el) return;
        el.classList.remove('fx-flash');
        void el.offsetWidth;   // 强制回流，保证同一个元素能连续触发动画
        el.classList.add('fx-flash');
        setTimeout(function () {
            el.classList.remove('fx-flash');
        }, 700);
    }

    /* ==================== 主题切换（深 / 亮） ==================== */

    var THEME_KEY = 'nexus-theme';

    /* 图标用 SVG 而不是字符：☀ / ☾ 在不同系统上会被替换成彩色 emoji，深浅色下都糊成一团 */
    var SUN_ICON = '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="4.2"/>'
        + '<path d="M12 2.6v2.2M12 19.2v2.2M2.6 12h2.2M19.2 12h2.2'
        + 'M5.3 5.3l1.6 1.6M17.1 17.1l1.6 1.6M18.7 5.3l-1.6 1.6M6.9 17.1l-1.6 1.6"/></svg>';
    var MOON_ICON = '<svg viewBox="0 0 24 24" aria-hidden="true">'
        + '<path d="M20.5 14.8A8.6 8.6 0 0 1 9.2 3.5a8.6 8.6 0 1 0 11.3 11.3Z"/></svg>';

    /** 当前主题：以 <html data-theme> 为准，它才是 CSS 真正读的那个值 */
    function currentTheme() {
        return document.documentElement.getAttribute('data-theme') === 'light' ? 'light' : 'dark';
    }

    /** 落地主题：<html data-theme> + color-scheme（表单控件 / 滚动条跟着变） + 按钮文案 */
    function paintTheme(theme) {
        document.documentElement.setAttribute('data-theme', theme);
        var meta = document.querySelector('meta[name="color-scheme"]');
        if (meta) meta.setAttribute('content', theme === 'light' ? 'light' : 'dark');
        var btn = document.getElementById('themeToggle');
        if (btn) {
            // 图标 + 文案都写「切过去的那一边」：当前深色 → 显示太阳和「亮色」
            var toLight = theme !== 'light';
            btn.innerHTML = (toLight ? SUN_ICON : MOON_ICON) + '<span>' + (toLight ? '亮色' : '深色') + '</span>';
            btn.title = toLight ? '切换到亮色主题' : '切换到深色主题';
            btn.setAttribute('aria-label', btn.title);
        }
    }

    function toggleTheme() {
        var next = currentTheme() === 'light' ? 'dark' : 'light';
        try {
            localStorage.setItem(THEME_KEY, next);
        } catch (e) {
            // 隐私模式 / 禁用存储会抛：不记也能用，只是刷新后回到跟随系统
        }
        paintTheme(next);
    }

    /** 把切换按钮挂到顶栏：三个页面（admin / console / login）都有 .topbar .meta */
    function mountThemeToggle() {
        if (document.getElementById('themeToggle')) return;
        var host = document.querySelector('.topbar .meta');
        if (!host) return;
        var btn = document.createElement('button');
        btn.type = 'button';
        btn.id = 'themeToggle';
        btn.className = 'theme-toggle';
        btn.onclick = toggleTheme;
        var logout = host.querySelector('a.logout');
        if (logout) host.insertBefore(btn, logout);
        else host.appendChild(btn);
    }

    function initTheme() {
        var saved = null;
        try {
            saved = localStorage.getItem(THEME_KEY);
        } catch (e) {
            saved = null;
        }
        // 默认深色：本项目的视觉基线就是深色科技感，亮色是「想换才换」，不跟随系统
        if (saved !== 'light' && saved !== 'dark') saved = 'dark';
        // 顺序不能反：paintTheme 要往按钮里写图标和文案，按钮得先挂上，否则首次渲染是个空按钮
        mountThemeToggle();
        paintTheme(saved);
    }

    window.NexusFX = {
        countTo: countTo,
        flash: flash
    };

    window.NexusTheme = {
        toggle: toggleTheme,
        current: currentTheme
    };

    // 先落主题再画背景：否则粒子网络会用上一帧的配色起手
    initTheme();

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', buildBackdrop);
    } else {
        buildBackdrop();
    }
})();
