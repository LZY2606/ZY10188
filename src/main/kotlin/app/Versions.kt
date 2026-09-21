package app

/**
 * 算法与标定版本的唯一登记处。
 * 每个落库指标都携带生成它的算法版本与标定版本，便于重放复核。
 */
object Versions {
    const val FIXTURE_ID = "synthetic-crank-v1"

    /** 齿间隔解码与缺齿模式识别：滚动中位间隔自适应、整数跨距判定 */
    const val ALG_TOOTH = "tooth-gap-v2.1"

    /** 角度重建：相邻齿沿实际时间分段线性插值（不假定每齿内转速恒定） */
    const val ALG_ANGLE_MAP = "angle-map-v1.2"

    /** 角域归零（pegging）：固定角域窗 [PEG_START_DEG, PEG_END_DEG) 半开 */
    const val ALG_PEGGING = "pegging-v1.0"

    /** 峰压角 */
    const val ALG_PMAX = "pmax-angle-v1.0"

    /** 最大压升率 */
    const val ALG_RPR = "pressure-rise-v1.1"

    /** 放热中心（CA50），含 5 点滑动平滑 */
    const val ALG_HRR = "heat-release-ca50-v1.1"

    /** 60-2 齿盘标定：58 齿、参考缺齿跨 3 个齿距、锚点为跨距中点、TDC 在锚点后 3° */
    const val CAL_WHEEL = "wheel-60-2-cal-v1"

    /** 气缸几何与传感器标定 */
    const val CAL_ENGINE = "geometry-2.0L-I4-v1"

    const val WHEEL_TEETH_PRESENT = 58
    const val WHEEL_SLOTS_PER_REV = 60
    const val REFERENCE_GAP_INTERVALS = 3
    const val TOOTH_SPACING_DEG = 6.0
    const val TDC_OFFSET_FROM_ANCHOR_DEG = 3.0
    const val CYCLE_DEG = 720.0

    /** 归零窗（相对发火上止点后的角域坐标），半开 [390, 450)，位于进气冲程 */
    const val PEG_START_DEG = 390.0
    const val PEG_END_DEG = 450.0

    /** 压力传感器饱和门槛（bar），达到即视为削顶，不做插值伪造 */
    const val SATURATION_LIMIT_BAR = 100.0
    const val SATURATION_EPSILON = 0.05

    /** 角域重采样栅格（度/点） */
    const val GRID_STEP_DEG = 1.0

    // 几何标定（单缸，2.0L 直列四缸）
    const val BORE_M = 0.086
    const val STROKE_M = 0.086
    const val ROD_M = 0.148
    const val COMPRESSION_RATIO = 10.0
    const val GAMMA_COMPRESSION = 1.32
    const val GAMMA_HEAT_RELEASE = 1.32
}
