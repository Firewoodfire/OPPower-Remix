package com.oppower.shizuku;

interface IPrivilegedCommandService {
    void destroy() = 16777114;
    void exit() = 1;
    String execute(String command) = 2;
    int[] readBatteryFallback() = 3;
    String readPowerSupplyDump() = 4;

    /**
     * 充电限流的受限 sysfs 通道。节点集合固定在服务端，调用方只能传值，
     * 不能传路径，因此不需要把 shell 命令面放宽到任意 `echo > file`。
     */
    int[] readChargeLimitNodes() = 5;
    int[] writeChargeLimitNodes(int currentMa) = 6;

    /**
     * 恢复系统默认限流。原始值由上层保存后透传，特权进程不持有状态。
     * 数组顺序与 readChargeLimitNodes 一致：[mA, raw, mA, raw, mA]。
     */
    int[] restoreChargeLimitNodes(in int[] originalRaw) = 7;

    /**
     * 只读诊断快照：热控策略服务是否存活、厂商热控开关属性的当前值、
     * 以及内核报告的实际充电协议。全部为读取，不含任何写入。
     */
    String readThermalDiagnostics() = 8;

    /**
     * 设备树里写死的电芯数（`oplus,batt_num`）。返回 1 表示单芯、2 表示双芯、
     * -1 表示读不到。节点路径固定在服务端，调用方传不了路径。
     */
    int readDeviceCellCount() = 9;
}
