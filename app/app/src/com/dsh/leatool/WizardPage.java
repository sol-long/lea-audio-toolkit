package com.dsh.leatool;

import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 功能 5：向导 —— 傻瓜式一键配置 LC3。
 *
 * <p>7 个步骤（0 前置检查 → 1 AAC → 2 经典配对 → 3 切 LC3 → 4 LE 配对 → 5 激活验证 → 6 收尾），
 * 每步都有状态徽章、人话指引、自动检测、下一步 / 跳过 / 重新检测。
 * 判据全部在 {@link WizardLogic}（纯逻辑，可单测），本类只负责采集与绘制。
 *
 * <p>写操作边界：setprop / lspd CLI / svc bluetooth / 模块广播（forget），
 * 不向耳机发送任何厂商命令。
 */
final class WizardPage {

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final long PAIR_WATCH_MS = 30000L;

    private final MainActivity a;
    private final WizardStore store;
    private final ScrollView root;
    private final LinearLayout content;

    private final StepCard[] cards = new StepCard[WizardLogic.STEP_COUNT];
    private final TextView[] progress = new TextView[WizardLogic.STEP_COUNT];
    private final WizardLogic.Status[] status = new WizardLogic.Status[WizardLogic.STEP_COUNT];
    private final String[] headlines = new String[WizardLogic.STEP_COUNT];
    private final WizardLogic.Result[] results = new WizardLogic.Result[WizardLogic.STEP_COUNT];
    /** 最近一次采集的原始输入（30 秒配对超时后直接用它重算僵尸分支，不再等一轮采集）。 */
    private final WizardLogic.Inputs[] lastInputs =
            new WizardLogic.Inputs[WizardLogic.STEP_COUNT];
    private final Map<String, Boolean> zombieChecked = new HashMap<String, Boolean>();

    private TextView tvOverall;
    private TextView tvOverallNote;
    private TextView tvBridge;
    private EditText inKeyword;
    private EditText inOui;

    private int current;
    private int maxReached;
    private boolean started;
    private boolean visible;
    private boolean busy;
    private boolean dead;
    private final boolean[] zombieOpen = new boolean[WizardLogic.STEP_COUNT];
    private boolean pairTimedOut;
    private boolean watchOn;
    private int watchStep = -1;
    private long watchDeadline;
    private String lastWriteKey = "";
    private List<Parsers.Bonded> lastBonded = new ArrayList<Parsers.Bonded>();

    WizardPage(MainActivity a) {
        this.a = a;
        this.store = new WizardStore(a);
        for (int i = 0; i < status.length; i++) {
            status[i] = WizardLogic.Status.NONE;
            headlines[i] = "";
        }
        root = new ScrollView(a);
        root.setFillViewport(true);
        content = Ui.column(a);
        content.setPadding(0, a.dp(4), 0, a.dp(28));
        root.addView(content, new ViewGroup.LayoutParams(MATCH, WRAP));
        build();
    }

    View view() {
        return root;
    }

    // ---------------------------------------------------------------- 构建

    private void build() {
        buildOverview();
        buildSettings();
        for (int i = 0; i < WizardLogic.STEP_COUNT; i++) {
            cards[i] = new StepCard(i);
        }
        TextView foot = Ui.caption(a, "本页只做 setprop / LSPosed CLI / 蓝牙开关 / 模块广播删除配对，"
                + "不向耳机发送任何厂商命令，也不写固件。");
        foot.setTextSize(10.5f);
        content.addView(foot, Ui.lp(MATCH, WRAP, a.dp(18), a.dp(10), a.dp(18), a.dp(6)));
    }

    private void buildOverview() {
        LinearLayout card = Ui.cardShell(a, content);

        LinearLayout head = Ui.row(a);
        card.addView(head, Ui.lp(MATCH, WRAP, 0, 0, 0, 0));
        TextView t = new TextView(a);
        t.setText("一键配置 LC3");
        t.setTextSize(17f);
        t.setTextColor(Ui.C_TXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(t, new LinearLayout.LayoutParams(0, WRAP, 1f));
        tvOverall = new TextView(a);
        tvOverall.setTextSize(10f);
        tvOverall.setTypeface(Typeface.DEFAULT_BOLD);
        tvOverall.setPadding(a.dp(9), a.dp(4), a.dp(9), a.dp(4));
        head.addView(tvOverall);

        tvOverallNote = Ui.note(a, "—");
        tvOverallNote.setTextSize(12f);
        card.addView(tvOverallNote, Ui.lp(MATCH, WRAP, 0, a.dp(6), 0, 0));

        LinearLayout bar = Ui.row(a);
        card.addView(bar, Ui.lp(MATCH, WRAP, 0, a.dp(10), 0, 0));
        for (int i = 0; i < WizardLogic.STEP_COUNT; i++) {
            final int idx = i;
            TextView p = new TextView(a);
            p.setTextSize(10.5f);
            p.setGravity(android.view.Gravity.CENTER);
            p.setPadding(0, a.dp(6), 0, a.dp(6));
            p.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onProgressClick(idx);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, WRAP, 1f);
            if (i > 0) {
                lp.setMarginStart(a.dp(3));
            }
            bar.addView(p, lp);
            progress[i] = p;
        }
        TextView cap = Ui.caption(a, "点上面的数字可以跳回已走过的步骤；当前步骤蓝色描边");
        cap.setTextSize(10.5f);
        card.addView(cap, Ui.lp(MATCH, WRAP, 0, a.dp(6), 0, 0));

        Button detectNow = Ui.small(a, "重新检测当前步", false);
        Button restart = Ui.small(a, "重新开始", false);
        detectNow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                detect(current, false);
            }
        });
        restart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                restartWizard();
            }
        });
        Ui.buttonRow(a, card, detectNow, restart);

        tvBridge = Ui.caption(a, "广播通道：空闲（模块不回时自动降级为属性）");
        tvBridge.setTextSize(10.5f);
        card.addView(tvBridge, Ui.lp(MATCH, WRAP, 0, a.dp(6), 0, 0));
    }

    private void buildSettings() {
        LinearLayout card = Ui.card(a, content, "识别设置（可选）");
        card.addView(Ui.caption(a, "耳机名字关键字 · 默认 OpenFeel"));
        inKeyword = Ui.input(a, WizardLogic.DEFAULT_KEYWORD);
        inKeyword.setText(store.keyword());
        card.addView(inKeyword, Ui.lp(MATCH, WRAP, 0, a.dp(4), 0, 0));

        card.addView(Ui.caption(a, "MAC 前缀 · 日志地址完整时用作第 3 步兜底（打码时按尾两段匹配）"));
        inOui = Ui.input(a, WizardLogic.DEFAULT_OUI);
        inOui.setText(store.oui());
        card.addView(inOui, Ui.lp(MATCH, WRAP, 0, a.dp(4), 0, 0));

        Button save = Ui.small(a, "保存并重新检测", false);
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                store.keyword(keywordValue());
                store.oui(ouiValue());
                a.toast("已保存关键字：" + keywordValue() + " · 前缀 " + ouiValue());
                detect(current, false);
            }
        });
        Ui.buttonRow(a, card, save);
    }

    // ---------------------------------------------------------------- 生命周期

    void onShown() {
        visible = true;
        if (!started) {
            started = true;
            current = store.step();
            maxReached = Math.max(store.maxStep(), current);
            for (int i = 0; i < status.length; i++) {
                status[i] = WizardStore.parseStatus(store.status(i));
                headlines[i] = store.headline(i);
                cards[i].setExpanded(i == current);
            }
            renderAll();
        }
        if (status[current] == WizardLogic.Status.NONE
                || (current == 6 && status[6] == WizardLogic.Status.READY)) {
            detect(current, false);
        }
    }

    void onHidden() {
        visible = false;
        stopPairWatch();
    }

    void onDestroy() {
        dead = true;
        stopPairWatch();
    }

    // ---------------------------------------------------------------- 检测

    private void detect(final int step, final boolean silent) {
        if (dead) {
            return;
        }
        if (busy) {
            if (!silent) {
                a.toast("正在检测，请稍候…");
            }
            return;
        }
        if (step < 0 || step >= WizardLogic.STEP_COUNT) {
            return;
        }
        busy = true;
        if (!silent) {
            pairTimedOut = false;
            status[step] = WizardLogic.Status.RUNNING;
            headlines[step] = "正在检测…（读取 dumpsys / 蓝牙日志 / 属性）";
            StepCard sc = cards[step];
            sc.setBadge(WizardLogic.Status.RUNNING);
            sc.headline.setText(headlines[step]);
            sc.headline.setVisibility(View.VISIBLE);
            sc.watchInfo.setVisibility(View.GONE);
            sc.advice.setVisibility(View.GONE);
            sc.checksBox.removeAllViews();
            renderProgress();
            if (step == 2 || step == 4) {
                sc.zombieBox.setVisibility(View.GONE);
            }
        }
        final String kw = keywordValue();
        final String oui = ouiValue();
        final boolean timedOut = pairTimedOut;
        a.postIo(new Runnable() {
            @Override
            public void run() {
                WizardLogic.Result res;
                WizardLogic.Inputs in = null;
                try {
                    in = WizardProbe.collect(step, kw, oui, timedOut);
                    in.lc3Confirmed = store.lc3Confirmed();
                    res = WizardLogic.eval(step, in);
                } catch (Throwable t) {
                    res = WizardLogic.Result.error(step,
                            "检测异常：" + t.getClass().getSimpleName());
                }
                final WizardLogic.Result fr = res;
                final WizardLogic.Inputs fi = in;
                a.onUi(new Runnable() {
                    @Override
                    public void run() {
                        busy = false;
                        if (dead) {
                            return;
                        }
                        if (fi != null) {
                            lastInputs[step] = fi;
                        }
                        applyResult(fr);
                    }
                });
            }
        });
    }

    private void applyResult(WizardLogic.Result r) {
        if (r == null || dead) {
            return;
        }
        results[r.step] = r;
        status[r.step] = r.status;
        headlines[r.step] = r.headline;
        store.status(r.step, r.status.name());
        store.headline(r.step, r.headline);
        maxReached = Math.max(maxReached, r.step);
        store.maxStep(maxReached);

        renderResult(r);
        renderStep(r.step);
        renderProgress();
        renderOverall();

        if (r.status == WizardLogic.Status.PASS || r.status == WizardLogic.Status.SKIP) {
            stopPairWatch();
        }
        if ((r.step == 2 || r.step == 4) && r.status == WizardLogic.Status.FAIL
                && !r.zombie && !watchOn && !pairTimedOut) {
            startPairWatch(r.step);
        }
        if (r.step == 6 && r.status == WizardLogic.Status.READY) {
            maybeAutoWrite(r);
        }
    }

    private void renderResult(WizardLogic.Result r) {
        StepCard sc = cards[r.step];
        lastBonded = r.bonded;

        sc.checksBox.removeAllViews();
        for (WizardLogic.Check c : r.checks) {
            TextView t = new TextView(a);
            String mark = c.pass ? "✓ " : (c.skipped ? "• " : "✗ ");
            String detail = c.detail.isEmpty() ? "" : "\n    " + c.detail;
            t.setText(mark + c.label + detail
                    + (c.skipped ? "\n    （未命中 · 不计入失败）" : ""));
            t.setTextSize(12.5f);
            t.setTextColor(c.pass ? Ui.C_OK : (c.skipped ? Ui.C_WARN : Ui.C_ERR));
            t.setLineSpacing(a.dp(1.5f), 1f);
            sc.checksBox.addView(t, Ui.lp(MATCH, WRAP, 0, a.dp(3), 0, 0));
        }
        if (r.advice != null && !r.advice.trim().isEmpty()) {
            sc.advice.setTextColor(Ui.C_ERR);
            sc.advice.setText(r.advice);
            sc.advice.setVisibility(View.VISIBLE);
        } else {
            sc.advice.setVisibility(View.GONE);
        }
        if (r.footnote != null && !r.footnote.isEmpty()) {
            sc.footnote.setText(r.footnote);
            sc.footnote.setVisibility(View.VISIBLE);
        } else {
            sc.footnote.setVisibility(View.GONE);
        }

        if (r.step == 2 || r.step == 4) {
            boolean show = r.zombie || zombieOpen[r.step];
            if (show) {
                sc.zombieBox.setVisibility(View.VISIBLE);
                sc.zombieTitle.setText(r.zombie
                        ? "僵尸配对清理 · " + (r.zombieReason.isEmpty() ? "" : r.zombieReason)
                        : "配对列表（手动打开）");
                rebuildZombie(sc);
            } else {
                sc.zombieBox.setVisibility(View.GONE);
            }
        }
    }

    private void renderAll() {
        for (int i = 0; i < WizardLogic.STEP_COUNT; i++) {
            renderStep(i);
        }
        renderProgress();
        renderOverall();
    }

    private void renderStep(int idx) {
        StepCard sc = cards[idx];
        sc.setBadge(status[idx]);
        String h = headlines[idx];
        if (h != null && !h.isEmpty()) {
            sc.headline.setText(h);
            sc.headline.setVisibility(View.VISIBLE);
        } else {
            sc.headline.setVisibility(View.GONE);
        }
        sc.btnDetect.setText(status[idx] == WizardLogic.Status.NONE ? "自动检测" : "重新检测");
        Ui.styleCard(a, sc.card, idx == current);
        sc.setExpanded(sc.expanded);
        if (idx == 6 && sc.writeLine.getText() != null
                && "尚未写入".contentEquals(sc.writeLine.getText())) {
            sc.writeLine.setText(status[6] == WizardLogic.Status.PASS
                    ? "当前值与期望一致，无需重复写入（点「重新检测」可核对）" : "尚未写入");
        }
    }

    private void renderProgress() {
        for (int i = 0; i < WizardLogic.STEP_COUNT; i++) {
            TextView p = progress[i];
            p.setText(String.valueOf(i) + " " + WizardLogic.glyph(status[i]));
            if (i == current) {
                p.setTextColor(0xFFFFFFFF);
                p.setBackground(pillBg(Ui.C_ACCENT));
                p.setTypeface(Typeface.DEFAULT_BOLD);
            } else {
                p.setTextColor(statusColor(status[i]));
                p.setBackground(pillBg(statusBg(status[i])));
                p.setTypeface(Typeface.DEFAULT);
            }
        }
    }

    private void renderOverall() {
        int pass = 0;
        int fail = 0;
        boolean any = false;
        for (WizardLogic.Status s : status) {
            if (s == WizardLogic.Status.PASS) {
                pass++;
            } else if (s == WizardLogic.Status.FAIL) {
                fail++;
            }
            if (s != WizardLogic.Status.NONE) {
                any = true;
            }
        }
        WizardLogic.Status overall;
        if (pass >= WizardLogic.STEP_COUNT) {
            overall = WizardLogic.Status.PASS;
        } else if (fail > 0) {
            overall = WizardLogic.Status.FAIL;
        } else if (any) {
            overall = WizardLogic.Status.RUNNING;
        } else {
            overall = WizardLogic.Status.NONE;
        }
        tvOverall.setText(WizardLogic.badge(overall));
        tvOverall.setTextColor(statusColor(overall));
        tvOverall.setBackground(pillBg(statusBg(overall)));

        if (pass >= WizardLogic.STEP_COUNT) {
            tvOverallNote.setText("全部 7 步通过 🎉 以后开蓝牙会自动连 LC3。");
        } else {
            tvOverallNote.setText("已完成 " + pass + "/7 步"
                    + (fail > 0 ? " · " + fail + " 项未通过" : "")
                    + " · 当前停在第 " + current + " 步");
        }
    }

    // ---------------------------------------------------------------- 导航

    private void onProgressClick(int idx) {
        if (idx > maxReached && idx != current) {
            a.toast("还没走到第 " + idx + " 步");
            return;
        }
        goTo(idx, false);
        if (status[idx] == WizardLogic.Status.NONE) {
            detect(idx, false);
        }
    }

    private void goTo(int i, boolean detectIt) {
        if (i < 0 || i >= WizardLogic.STEP_COUNT) {
            return;
        }
        current = i;
        store.step(i);
        if (watchOn && watchStep != i) {
            stopPairWatch();
        }
        if (i > maxReached) {
            maxReached = i;
            store.maxStep(i);
        }
        for (int k = 0; k < WizardLogic.STEP_COUNT; k++) {
            cards[k].setExpanded(k == i);
        }
        renderAll();
        scrollToCard(i);
        if (detectIt
                && (status[i] == WizardLogic.Status.NONE || i == 6)) {
            detect(i, false);
        }
    }

    private void next() {
        if (current >= WizardLogic.STEP_COUNT - 1) {
            finish();
            return;
        }
        if (current == 0 && status[0] != WizardLogic.Status.PASS
                && status[0] != WizardLogic.Status.SKIP) {
            int n = results[0] == null ? 0 : results[0].failCount();
            a.toastLong(n > 0
                    ? "前置检查还有 " + n + " 项没通过：先点「一键修复」，或明确点「跳过」再继续。"
                    : "先点「重新检测」完成前置检查，或明确点「跳过」再继续。");
            return;
        }
        if (current == 3 && !store.lc3Confirmed()
                && status[3] != WizardLogic.Status.SKIP) {
            a.toastLong("先在卡片里勾选「我已在 Roselink 切到 LC3」；确实还没切就点「跳过」。");
            return;
        }
        if (status[current] != WizardLogic.Status.PASS
                && status[current] != WizardLogic.Status.SKIP) {
            final int target = current + 1;
            a.confirm("这一步还没通过",
                    "第 " + current + " 步「" + WizardLogic.TITLES[current] + "」现在的状态是"
                            + WizardLogic.badge(status[current]) + "。\n仍要进入下一步吗？",
                    new Runnable() {
                        @Override
                        public void run() {
                            goTo(target, true);
                        }
                    });
            return;
        }
        goTo(current + 1, true);
    }

    private void skip() {
        if (current >= WizardLogic.STEP_COUNT - 1) {
            a.toast("最后一步不能跳过");
            return;
        }
        status[current] = WizardLogic.Status.SKIP;
        store.status(current, WizardLogic.Status.SKIP.name());
        headlines[current] = "已跳过，可随时回来补做";
        store.headline(current, headlines[current]);
        stopPairWatch();
        renderStep(current);
        renderProgress();
        renderOverall();
        goTo(current + 1, true);
    }

    private void finish() {
        if (status[6] == WizardLogic.Status.PASS) {
            store.finished(true);
            a.toastLong("🎉 配置完成：以后开蓝牙会自动连 LC3");
            scrollToCard(0);
        } else {
            a.confirm("还没完成收尾", "第 6 步还没通过。要现在重新检测并写入吗？",
                    new Runnable() {
                        @Override
                        public void run() {
                            detect(6, false);
                        }
                    });
        }
    }

    private void restartWizard() {
        a.confirm("重新开始向导",
                "会清空本页的进度记录（不影响已经写进 persist.sys.lea.* 的属性）。",
                new Runnable() {
                    @Override
                    public void run() {
                        store.reset();
                        stopPairWatch();
                        pairTimedOut = false;
                        lastWriteKey = "";
                        zombieChecked.clear();
                        java.util.Arrays.fill(zombieOpen, false);
                        lastBonded = new ArrayList<Parsers.Bonded>();
                        for (int i = 0; i < WizardLogic.STEP_COUNT; i++) {
                            status[i] = WizardLogic.Status.NONE;
                            headlines[i] = "";
                            results[i] = null;
                            lastInputs[i] = null;
                            StepCard sc = cards[i];
                            sc.headline.setVisibility(View.GONE);
                            sc.advice.setVisibility(View.GONE);
                            sc.checksBox.removeAllViews();
                            sc.setBadge(WizardLogic.Status.NONE);
                            sc.setExpanded(i == 0);
                            if (sc.zombieBox != null) {
                                sc.zombieBox.setVisibility(View.GONE);
                                sc.zombieList.removeAllViews();
                            }
                            if (sc.writeLine != null) {
                                sc.writeLine.setText("尚未写入");
                            }
                            if (sc.footnote != null) {
                                sc.footnote.setVisibility(View.GONE);
                            }
                            sc.setConfirmSilently(false);
                        }
                        current = 0;
                        maxReached = 0;
                        store.step(0);
                        store.maxStep(0);
                        inKeyword.setText(store.keyword());
                        inOui.setText(store.oui());
                        renderAll();
                        detect(0, false);
                    }
                });
    }

    private void scrollToCard(final int idx) {
        a.handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                if (dead) {
                    return;
                }
                View v = cards[idx].card;
                root.smoothScrollTo(0, Math.max(0, v.getTop() - a.dp(6)));
            }
        }, 80L);
    }

    // ------------------------------------------------------- 第 2/4 步配对守卫

    private final Runnable watchTick = new Runnable() {
        @Override
        public void run() {
            if (!watchOn || watchStep < 0 || dead) {
                return;
            }
            final int step = watchStep;
            long left = watchDeadline - System.currentTimeMillis();
            if (left <= 0) {
                stopPairWatch();
                pairTimedOut = true;
                a.toast("等了 30 秒还没配上，已展开「僵尸配对清理」");
                // 用上次采到的原始数据重算一次（带上超时标志），不用再等一轮 shell
                WizardLogic.Inputs cached = lastInputs[step];
                if (cached != null) {
                    cached.pairTimedOut = true;
                    applyResult(WizardLogic.eval(step, cached));
                } else {
                    detect(step, true);
                }
                return;
            }
            StepCard sc = cards[step];
            sc.watchInfo.setVisibility(View.VISIBLE);
            sc.watchInfo.setText("还在等配对…（剩 " + Math.max(1, left / 1000) + " 秒）"
                    + "配对成功会自动变绿；超时会展开「僵尸配对清理」。");
            detect(step, true);
            a.handler().postDelayed(this, 5000L);
        }
    };

    private void startPairWatch(int step) {
        if (!visible || dead) {
            return;
        }
        stopPairWatch();
        watchOn = true;
        watchStep = step;
        watchDeadline = System.currentTimeMillis() + PAIR_WATCH_MS;
        a.handler().postDelayed(watchTick, 5000L);
    }

    private void stopPairWatch() {
        watchOn = false;
        watchStep = -1;
        a.handler().removeCallbacks(watchTick);
        for (int i = 0; i < cards.length; i++) {
            if (cards[i] != null) {
                cards[i].watchInfo.setVisibility(View.GONE);
            }
        }
    }

    // -------------------------------------------------------- 僵尸配对清理

    private void rebuildZombie(StepCard sc) {
        sc.zombieList.removeAllViews();
        if (lastBonded.isEmpty()) {
            TextView t = Ui.caption(a, "没读到配对列表（需要 root：dumpsys bluetooth_manager）");
            t.setTextColor(Ui.C_WARN);
            sc.zombieList.addView(t);
            return;
        }
        TextView tip = Ui.caption(a, "共 " + lastBonded.size()
                + " 条 · 只勾要删的（默认不勾，避免误删）");
        sc.zombieList.addView(tip, Ui.lp(MATCH, WRAP, 0, a.dp(2), 0, a.dp(4)));

        for (final Parsers.Bonded b : lastBonded) {
            LinearLayout row = Ui.row(a);
            CheckBox cb = new CheckBox(a);
            Boolean prev = zombieChecked.get(b.mac);
            cb.setChecked(prev != null && prev);
            cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    zombieChecked.put(b.mac, isChecked);
                }
            });
            row.addView(cb, new LinearLayout.LayoutParams(a.dp(38), WRAP));

            TextView tv = new TextView(a);
            tv.setText("[" + b.transport + "] " + b.mac + (b.connected ? " · 当前连接" : "")
                    + "\n" + (b.name.isEmpty() ? "（无名字）" : b.name));
            tv.setTextSize(11.5f);
            tv.setTextColor(b.isLe() ? Ui.C_ACCENT : Ui.C_TXT);
            tv.setLineSpacing(a.dp(1), 1f);
            row.addView(tv, new LinearLayout.LayoutParams(0, WRAP, 1f));
            sc.zombieList.addView(row, Ui.lp(MATCH, WRAP, 0, a.dp(3), 0, 0));
        }

        Button del = Ui.small(a, "删除选中的配对", false);
        del.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                deleteSelected();
            }
        });
        sc.zombieList.addView(del, Ui.lp(WRAP, WRAP, 0, a.dp(8), 0, 0));
    }

    private void deleteSelected() {
        final List<Parsers.Bonded> sel = new ArrayList<Parsers.Bonded>();
        for (Parsers.Bonded b : lastBonded) {
            if (Boolean.TRUE.equals(zombieChecked.get(b.mac))) {
                sel.add(b);
            }
        }
        if (sel.isEmpty()) {
            a.toast("先勾选要删除的配对（默认不勾，防误删）");
            return;
        }
        StringBuilder body = new StringBuilder("将删除下面 " + sel.size() + " 条配对：\n\n");
        for (Parsers.Bonded b : sel) {
            body.append("· [").append(b.transport).append("] ").append(b.mac)
                    .append("  ").append(b.name).append('\n');
        }
        body.append("\n删除后需要重新配对。确认删除？");
        a.confirm("删除选中的 " + sel.size() + " 条配对", body.toString(), new Runnable() {
            @Override
            public void run() {
                doForget(sel);
            }
        });
    }

    private void doForget(List<Parsers.Bonded> sel) {
        final String macs = WizardLogic.joinMacs(macsOf(sel));
        setBridge("广播通道：等待模块响应…（删除配对，2 秒超时）");
        ModuleBridge.send("forget", null, macs, ModuleBridge.DEFAULT_TIMEOUT_MS,
                new ModuleBridge.Callback() {
                    @Override
                    public void onResult(boolean ok, String msg, boolean timeout) {
                        if (dead) {
                            return;
                        }
                        setBridge(null);
                        if (timeout) {
                            fallbackForget(macs);
                            return;
                        }
                        if (!ok) {
                            a.showReport("✗ 删除失败", "模块返回：" + Ui.nz(msg));
                            return;
                        }
                        zombieChecked.clear();
                        a.toast("已删除 " + macs.split(",").length + " 条，正在重新检测…");
                        detect(current, true);
                    }
                });
    }

    private void fallbackForget(final String macs) {
        a.toastLong("模块没响应，改用属性通道（需要重启蓝牙才生效）");
        a.withRoot("写入 forget_mac", new Runnable() {
            @Override
            public void run() {
                final String cmd = "setprop " + WizardLogic.P_FORGET_MAC + " "
                        + Shell.quote(macs);
                a.postIo(new Runnable() {
                    @Override
                    public void run() {
                        final Shell.Result r = Shell.run(cmd, 15000);
                        a.onUi(new Runnable() {
                            @Override
                            public void run() {
                                if (dead) {
                                    return;
                                }
                                if (r == null || !r.ok()) {
                                    a.showReport("✗ 降级写入失败",
                                            describe(r) + "\n\n$ " + cmd);
                                    return;
                                }
                                a.confirm("需要重启蓝牙生效",
                                        "已写入 " + WizardLogic.P_FORGET_MAC + " = " + macs
                                                + "\n重启蓝牙后模块会删除这些配对。\n现在重启蓝牙？",
                                        new Runnable() {
                                            @Override
                                            public void run() {
                                                restartBluetooth();
                                            }
                                        });
                            }
                        });
                    }
                });
            }
        });
    }

    private void restartBluetooth() {
        a.postIo(new Runnable() {
            @Override
            public void run() {
                final Shell.Result r = Shell.run(
                        "svc bluetooth disable; sleep 2; svc bluetooth enable", 30000);
                a.onUi(new Runnable() {
                    @Override
                    public void run() {
                        if (dead) {
                            return;
                        }
                        a.toast(r != null && r.ok()
                                ? "蓝牙已重启，等 8 秒再点「重新检测」"
                                : "重启蓝牙失败：" + describe(r));
                    }
                });
            }
        });
    }

    // -------------------------------------------------------------- 第 0 步修复

    private void fixAll() {
        WizardLogic.Result r0 = results[0];
        if (r0 == null) {
            a.toast("先点「重新检测」拿到检查结果");
            detect(0, false);
            return;
        }
        final List<String[]> cmds = new ArrayList<String[]>();
        StringBuilder log = new StringBuilder();
        // 顺序很重要：先启用模块 / 写属性，最后统一重启蓝牙
        int[] order = {WizardLogic.C_MODULE, WizardLogic.C_DUAL, WizardLogic.C_WHITE_LIST,
                WizardLogic.C_BT_ON, WizardLogic.C_INJECT};
        for (int idx : order) {
            if (idx >= r0.checks.size()) {
                continue;
            }
            WizardLogic.Check c = r0.checks.get(idx);
            if (c.pass) {
                continue;
            }
            String cmd = fixCommand(idx);
            if (cmd == null) {
                continue;
            }
            cmds.add(new String[]{c.label, cmd});
            log.append("· ").append(cmd).append('\n');
        }
        if (r0.checks.size() > WizardLogic.C_ROOT && !r0.checks.get(WizardLogic.C_ROOT).pass) {
            log.append("· （root 不能自动修：请去 ReSukiSU 里授权）\n");
        }
        if (r0.checks.size() > WizardLogic.C_ROSELINK && !r0.checks.get(WizardLogic.C_ROSELINK).pass) {
            log.append("· （Roselink 不能自动修：请先安装 APK）\n");
        }
        if (cmds.isEmpty()) {
            a.showReport("没有可自动修复的项", log.length() == 0
                    ? "第 0 步已经全部通过。" : log.toString());
            return;
        }
        final String detail = log.toString();
        a.withRoot("一键修复", new Runnable() {
            @Override
            public void run() {
                a.confirm("一键修复 " + cmds.size() + " 项",
                        "将依次执行：\n\n" + detail + "\n修复后会自动重新检测。",
                        new Runnable() {
                            @Override
                            public void run() {
                                runFix(cmds);
                            }
                        });
            }
        });
    }

    private static String fixCommand(int idx) {
        switch (idx) {
            case WizardLogic.C_MODULE:
                return "/data/adb/lspd/cli modules enable " + WizardLogic.MODULE_ID;
            case WizardLogic.C_INJECT:
                return "svc bluetooth disable; sleep 2; svc bluetooth enable";
            case WizardLogic.C_DUAL:
                return "setprop " + WizardLogic.P_DUAL + " true";
            case WizardLogic.C_WHITE_LIST:
                return "setprop " + WizardLogic.P_WHITE_LIST + " false";
            case WizardLogic.C_BT_ON:
                return "svc bluetooth enable";
            default:
                return null;
        }
    }

    private void runFix(final List<String[]> cmds) {
        a.postIo(new Runnable() {
            @Override
            public void run() {
                StringBuilder log = new StringBuilder();
                boolean all = true;
                for (String[] c : cmds) {
                    Shell.Result r = Shell.run(c[1], 30000);
                    boolean ok = r != null && r.ok();
                    all &= ok;
                    log.append(ok ? "✓ " : "✗ ").append(c[0]).append('\n');
                    if (!ok) {
                        log.append("    ").append(describe(r)).append('\n');
                    }
                }
                final boolean fok = all;
                final String text = log.toString();
                a.onUi(new Runnable() {
                    @Override
                    public void run() {
                        if (dead) {
                            return;
                        }
                        a.showReport(fok ? "修复完成" : "部分修复失败",
                                text + (fok ? "\n正在重新检测…" : "\n修不掉的项请按提示手动处理。"));
                        detect(0, false);
                    }
                });
            }
        });
    }

    // -------------------------------------------------------- 第 3 步人工确认

    /**
     * 勾选/取消「我已在 Roselink 切到 LC3」。
     *
     * <p>这一步以人工确认为准（配对前自动检测不可靠），所以勾选后直接用上次采集的
     * 原始输入重算一遍，立刻变绿；取消则回到「等待人工确认」。
     */
    private void onLc3ConfirmChanged(boolean checked) {
        store.lc3Confirmed(checked);
        WizardLogic.Inputs cached = lastInputs[3];
        if (cached != null) {
            cached.lc3Confirmed = checked;
            applyResult(WizardLogic.eval(3, cached));
        } else {
            detect(3, true);
        }
    }

    // ------------------------------------------------------------ 第 6 步写入
    private void maybeAutoWrite(WizardLogic.Result r) {
        if (r.desiredAutoMac == null || r.desiredTargetMacs.isEmpty()) {
            return;
        }
        String key = r.desiredAutoMac + "|" + WizardLogic.joinMacs(r.desiredTargetMacs);
        if (key.equals(lastWriteKey)) {
            return;
        }
        lastWriteKey = key;
        writeTargetProps(r);
    }

    private void writeTargetProps(WizardLogic.Result r) {
        final String auto = r.desiredAutoMac;
        final String targets = WizardLogic.joinMacs(r.desiredTargetMacs);
        if (auto == null || targets.isEmpty()) {
            return;
        }
        final StepCard sc = cards[6];
        sc.writeLine.setText("正在写入…\nauto_mac = " + auto + "\ntarget_macs = " + targets);
        final String cmd = "setprop " + WizardLogic.P_AUTO_MAC + " " + Shell.quote(auto)
                + "; setprop " + WizardLogic.P_TARGET_MACS + " " + Shell.quote(targets);
        a.postIo(new Runnable() {
            @Override
            public void run() {
                final Shell.Result res = Shell.run(cmd, 15000);
                a.onUi(new Runnable() {
                    @Override
                    public void run() {
                        if (dead) {
                            return;
                        }
                        if (res != null && res.ok()) {
                            sc.writeLine.setText("✓ 已写入\n" + "auto_mac = " + auto
                                    + "\ntarget_macs = " + targets);
                        } else {
                            sc.writeLine.setText("✗ 写入失败（" + describe(res)
                                    + "）\n需要 root；也可以在「操作」页手动写入。");
                        }
                        detect(6, true);
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------ 工具

    private String keywordValue() {
        String s = inKeyword == null || inKeyword.getText() == null
                ? "" : inKeyword.getText().toString().trim();
        return s.isEmpty() ? WizardLogic.DEFAULT_KEYWORD : s;
    }

    private String ouiValue() {
        String s = inOui == null || inOui.getText() == null
                ? "" : inOui.getText().toString().trim();
        return s.isEmpty() ? WizardLogic.DEFAULT_OUI : s.toUpperCase(Locale.US);
    }

    private void setBridge(String text) {
        if (tvBridge == null || dead) {
            return;
        }
        if (text == null) {
            tvBridge.setText("广播通道：空闲（模块不回时自动降级为属性）");
            tvBridge.setTextColor(Ui.C_SUB);
        } else {
            tvBridge.setText(text);
            tvBridge.setTextColor(Ui.C_WARN);
        }
    }

    private GradientDrawable pillBg(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(a.dp(20));
        return g;
    }

    private static int statusColor(WizardLogic.Status s) {
        switch (s) {
            case PASS:
                return Ui.C_OK;
            case FAIL:
                return Ui.C_ERR;
            case RUNNING:
            case READY:
                return Ui.C_WARN;
            default:
                return Ui.C_SUB;
        }
    }

    private static int statusBg(WizardLogic.Status s) {
        switch (s) {
            case PASS:
                return 0xFFE7F5EC;
            case FAIL:
                return 0xFFFBE7E7;
            case RUNNING:
            case READY:
                return 0xFFFFF3E0;
            default:
                return 0xFFEDEFF3;
        }
    }

    private static List<String> macsOf(List<Parsers.Bonded> list) {
        List<String> out = new ArrayList<String>();
        for (Parsers.Bonded b : list) {
            out.add(b.mac);
        }
        return out;
    }

    private String describe(Shell.Result r) {
        if (r == null) {
            return "无结果";
        }
        if (r.timedOut) {
            return "超时";
        }
        if (r.error != null) {
            return r.error;
        }
        String t = r.out == null ? "" : r.out.trim();
        if (!t.isEmpty()) {
            return t.length() > 300 ? t.substring(0, 300) + "…" : t;
        }
        return "exit " + r.code;
    }

    // ------------------------------------------------------------------ 卡片

    private final class StepCard {
        final int idx;
        final LinearLayout card;
        final LinearLayout body;
        final LinearLayout checksBox;
        final LinearLayout zombieBox;
        final LinearLayout zombieList;
        final TextView badge;
        final TextView title;
        final TextView chevron;
        final TextView guide;
        final TextView headline;
        final TextView watchInfo;
        final TextView advice;
        final TextView footnote;
        final TextView note;
        final TextView zombieTitle;
        final TextView writeLine;
        CheckBox cbConfirm;
        CompoundButton.OnCheckedChangeListener confirmListener;
        boolean suppressConfirm;
        final Button btnDetect;
        final Button btnNext;
        final Button btnSkip;
        Button btnFix;
        Button btnZombie;
        Button btnWrite;
        boolean expanded;

        StepCard(int index) {
            idx = index;
            card = Ui.cardShell(a, content);

            LinearLayout head = Ui.row(a);
            card.addView(head, Ui.lp(MATCH, WRAP, 0, a.dp(2), 0, a.dp(2)));
            badge = new TextView(a);
            badge.setTextSize(10f);
            badge.setTypeface(Typeface.DEFAULT_BOLD);
            badge.setPadding(a.dp(8), a.dp(3), a.dp(8), a.dp(3));
            head.addView(badge);
            title = new TextView(a);
            title.setText(WizardLogic.TITLES[idx]);
            title.setTextSize(14f);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setTextColor(Ui.C_TXT);
            title.setPadding(a.dp(8), 0, 0, 0);
            head.addView(title, new LinearLayout.LayoutParams(0, WRAP, 1f));
            chevron = new TextView(a);
            chevron.setText("▾");
            chevron.setTextSize(14f);
            chevron.setTextColor(Ui.C_SUB);
            chevron.setPadding(a.dp(6), 0, 0, 0);
            head.addView(chevron);
            head.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    setExpanded(!expanded);
                }
            });

            body = Ui.column(a);
            card.addView(body, Ui.lp(MATCH, WRAP, 0, a.dp(8), 0, 0));

            guide = Ui.note(a, WizardLogic.GUIDES[idx]);
            guide.setTextSize(12.5f);
            body.addView(guide, Ui.lp(MATCH, WRAP, 0, 0, 0, a.dp(6)));

            if (idx == 3) {
                // 第 3 步以人工确认为准：配对前「切 LC3」没有可靠的自动判据
                cbConfirm = new CheckBox(a);
                cbConfirm.setText("我已在 Roselink 切到 LC3，耳机已重启（必填）");
                cbConfirm.setTextSize(13f);
                cbConfirm.setTextColor(Ui.C_TXT);
                cbConfirm.setChecked(store.lc3Confirmed());
                confirmListener = new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        if (!suppressConfirm) {
                            onLc3ConfirmChanged(isChecked);
                        }
                    }
                };
                cbConfirm.setOnCheckedChangeListener(confirmListener);
                body.addView(cbConfirm, Ui.lp(MATCH, WRAP, 0, a.dp(4), 0, a.dp(2)));
            }

            headline = new TextView(a);
            headline.setTextSize(13f);
            headline.setTypeface(Typeface.DEFAULT_BOLD);
            headline.setTextColor(Ui.C_TXT);
            headline.setVisibility(View.GONE);
            body.addView(headline, Ui.lp(MATCH, WRAP, 0, a.dp(4), 0, 0));

            watchInfo = new TextView(a);
            watchInfo.setTextSize(12f);
            watchInfo.setTextColor(Ui.C_WARN);
            watchInfo.setVisibility(View.GONE);
            body.addView(watchInfo, Ui.lp(MATCH, WRAP, 0, a.dp(4), 0, 0));

            advice = new TextView(a);
            advice.setTextSize(12.5f);
            advice.setTextColor(Ui.C_ERR);
            advice.setLineSpacing(a.dp(2), 1f);
            advice.setVisibility(View.GONE);
            body.addView(advice, Ui.lp(MATCH, WRAP, 0, a.dp(4), 0, 0));

            checksBox = Ui.column(a);
            body.addView(checksBox, Ui.lp(MATCH, WRAP, 0, a.dp(4), 0, 0));

            footnote = new TextView(a);
            footnote.setTextSize(11.5f);
            footnote.setTextColor(Ui.C_SUB);
            footnote.setLineSpacing(a.dp(1), 1f);
            footnote.setVisibility(View.GONE);
            body.addView(footnote, Ui.lp(MATCH, WRAP, 0, a.dp(4), 0, 0));

            writeLine = new TextView(a);
            writeLine.setTextSize(11.5f);
            writeLine.setTypeface(Typeface.MONOSPACE);
            writeLine.setTextColor(Ui.C_TXT);
            writeLine.setTextIsSelectable(true);
            if (idx == 6) {
                writeLine.setText("尚未写入");
                body.addView(writeLine, Ui.lp(MATCH, WRAP, 0, a.dp(6), 0, 0));
            } else {
                writeLine.setVisibility(View.GONE);
            }

            zombieBox = Ui.column(a);
            zombieList = Ui.column(a);
            zombieTitle = Ui.caption(a, "僵尸配对清理");
            zombieTitle.setTypeface(Typeface.DEFAULT_BOLD);
            zombieBox.setVisibility(View.GONE);
            zombieBox.addView(zombieTitle, Ui.lp(MATCH, WRAP, 0, a.dp(6), 0, 0));
            zombieBox.addView(zombieList, Ui.lp(MATCH, WRAP, 0, a.dp(2), 0, 0));
            if (idx == 2 || idx == 4) {
                body.addView(zombieBox, Ui.lp(MATCH, WRAP, 0, a.dp(4), 0, 0));
            }

            note = Ui.caption(a, WizardLogic.NOTES[idx]);
            note.setTextSize(11f);
            note.setLineSpacing(a.dp(1), 1f);
            body.addView(note, Ui.lp(MATCH, WRAP, 0, a.dp(6), 0, 0));

            LinearLayout actionCol = Ui.column(a);
            body.addView(actionCol, Ui.lp(MATCH, WRAP, 0, a.dp(2), 0, 0));

            btnDetect = Ui.small(a, "自动检测", false);
            btnNext = Ui.small(a, idx == 6 ? "完成" : "下一步", true);
            btnSkip = Ui.small(a, "跳过", false);
            btnDetect.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    detect(idx, false);
                }
            });
            btnNext.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (idx == WizardLogic.STEP_COUNT - 1) {
                        finish();
                    } else {
                        next();
                    }
                }
            });
            btnSkip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    skip();
                }
            });

            if (idx == 0) {
                btnFix = Ui.small(a, "一键修复", true);
                btnFix.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        fixAll();
                    }
                });
                Ui.buttonRow(a, actionCol, btnDetect, btnFix);
                Button btSettings = Ui.small(a, "打开蓝牙设置", false);
                btSettings.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openBluetoothSettings();
                    }
                });
                Ui.buttonRow(a, actionCol, btnSkip, btSettings);
                Ui.buttonRow(a, actionCol, btnNext);
            } else if (idx == 2 || idx == 4) {
                btnZombie = Ui.small(a, "僵尸配对清理", false);
                btnZombie.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        zombieOpen[idx] = !zombieOpen[idx];
                        StepCard sc = cards[idx];
                        sc.zombieBox.setVisibility(zombieOpen[idx] ? View.VISIBLE : View.GONE);
                        if (zombieOpen[idx]) {
                            sc.zombieTitle.setText("配对列表（手动打开）");
                            rebuildZombie(sc);
                            if (lastBonded.isEmpty()) {
                                detect(idx, true);
                            }
                        }
                    }
                });
                Ui.buttonRow(a, actionCol, btnDetect, btnNext);
                Ui.buttonRow(a, actionCol, btnSkip, btnZombie);
            } else if (idx == 6) {
                btnWrite = Ui.small(a, "重新写入", false);
                btnWrite.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        lastWriteKey = "";
                        detect(6, false);
                    }
                });
                Ui.buttonRow(a, actionCol, btnDetect, btnWrite);
                Ui.buttonRow(a, actionCol, btnNext);
            } else {
                Ui.buttonRow(a, actionCol, btnDetect, btnNext);
                Ui.buttonRow(a, actionCol, btnSkip);
            }

            setExpanded(idx == 0);
        }

        void setBadge(WizardLogic.Status s) {
            badge.setText(WizardLogic.badge(s));
            badge.setTextColor(statusColor(s));
            badge.setBackground(pillBg(statusBg(s)));
        }

        void setExpanded(boolean e) {
            expanded = e;
            if (body == null) {
                return;
            }
            body.setVisibility(e ? View.VISIBLE : View.GONE);
            if (chevron != null) {
                chevron.setText(e ? "▾" : "▸");
            }
        }

        /** 复位第 3 步复选框，但不触发「重新判定」（重新开始时用）。 */
        void setConfirmSilently(boolean checked) {
            if (cbConfirm == null) {
                return;
            }
            suppressConfirm = true;
            cbConfirm.setChecked(checked);
            suppressConfirm = false;
        }
    }

    /** 供「操作」页跳转过来时定位（暂未使用，保留入口）。 */
    void showStep(int step) {
        if (step >= 0 && step < WizardLogic.STEP_COUNT) {
            goTo(step, true);
        }
    }

    /** 打开系统蓝牙设置（第 0 步「去开蓝牙」用）。 */
    void openBluetoothSettings() {
        try {
            a.startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
        } catch (Throwable t) {
            a.toast("打不开蓝牙设置，请手动下拉通知栏打开蓝牙");
        }
    }
}
