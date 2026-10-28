package com.dsh.leatool;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 向导进度持久化（进程被杀后能恢复）。
 *
 * <p>只存「步骤状态 + 一句话结论」，不存原始 dump：恢复后徽章和结论还在，
 * 点「重新检测」再采集一次即可。
 */
final class WizardStore {

    private static final String PREF = "lea_wizard";

    private final SharedPreferences sp;

    WizardStore(Context c) {
        sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    int step() {
        return clamp(sp.getInt("step", 0));
    }

    void step(int v) {
        sp.edit().putInt("step", clamp(v)).apply();
    }

    int maxStep() {
        return clamp(sp.getInt("max_step", 0));
    }

    void maxStep(int v) {
        sp.edit().putInt("max_step", clamp(v)).apply();
    }

    String status(int i) {
        return sp.getString("status_" + i, WizardLogic.Status.NONE.name());
    }

    void status(int i, String v) {
        sp.edit().putString("status_" + i, v).apply();
    }

    String headline(int i) {
        return sp.getString("headline_" + i, "");
    }

    void headline(int i, String v) {
        sp.edit().putString("headline_" + i, v == null ? "" : v).apply();
    }

    String keyword() {
        String v = sp.getString("keyword", WizardLogic.DEFAULT_KEYWORD);
        return v == null || v.trim().isEmpty() ? WizardLogic.DEFAULT_KEYWORD : v.trim();
    }

    void keyword(String v) {
        sp.edit().putString("keyword", v == null ? "" : v.trim()).apply();
    }

    String oui() {
        String v = sp.getString("oui", WizardLogic.DEFAULT_OUI);
        return v == null || v.trim().isEmpty() ? WizardLogic.DEFAULT_OUI : v.trim();
    }

    void oui(String v) {
        sp.edit().putString("oui", v == null ? "" : v.trim()).apply();
    }

    boolean finished() {
        return sp.getBoolean("finished", false);
    }

    void finished(boolean v) {
        sp.edit().putBoolean("finished", v).apply();
    }

    /** 第 3 步的人工确认复选框：「我已在 Roselink 切到 LC3」。 */
    boolean lc3Confirmed() {
        return sp.getBoolean("lc3_confirmed", false);
    }

    void lc3Confirmed(boolean v) {
        sp.edit().putBoolean("lc3_confirmed", v).apply();
    }

    void reset() {
        sp.edit().clear().apply();
    }

    static WizardLogic.Status parseStatus(String name) {
        return WizardLogic.parseStatus(name);
    }

    private static int clamp(int v) {
        if (v < 0) {
            return 0;
        }
        return v >= WizardLogic.STEP_COUNT ? WizardLogic.STEP_COUNT - 1 : v;
    }
}
