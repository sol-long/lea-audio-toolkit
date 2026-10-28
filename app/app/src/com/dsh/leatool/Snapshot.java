package com.dsh.leatool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 一次状态采集的结果。字段都是 null 表示"取不到"。 */
final class Snapshot {

    long time;
    boolean root;

    // 1. 双模属性
    String dualModeProp;

    // 2. dumpsys bluetooth_manager
    String groupId;
    String activeAudioOut;
    String dualModeEnabled;
    String a2dpActiveDevice;
    Map<String, String> a2dp = new LinkedHashMap<String, String>();

    // 3. dumpsys audio
    String musicDevices;
    String routeKind;

    // 4. 蓝牙日志（dumpConfigName / dumpAseState 是日志不可用时的 dumpsys 回退）
    String btLogFile;
    String btConfigName;
    String btAseState;
    String dumpConfigName;
    String dumpAseState;

    // 5. 通知里的电量
    String batteryText;

    // 6. LSPosed 模块
    String moduleState;
    String moduleRaw;

    // 采集过程中的问题（超时 / 权限 / 命令失败）
    final List<String> issues = Collections.synchronizedList(new ArrayList<String>());

    boolean isEmpty() {
        return dualModeProp == null && groupId == null && activeAudioOut == null
                && dualModeEnabled == null && a2dp.isEmpty() && musicDevices == null
                && btConfigName == null && btAseState == null && batteryText == null
                && moduleState == null;
    }
}
