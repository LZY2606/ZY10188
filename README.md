# 缸压相位镜（Pressure Phase Mirror）

面向发动机台架的缸压相位重建与燃烧相位审阅工具。从缸压高速采样、曲轴齿脉冲、
缺齿锦标和工况通道重建每个四冲程循环（720°）的曲轴角，审阅上止点偏置与燃烧相位。

技术栈：Kotlin 2.1 + Ktor 2.3（Netty）+ SQLite（xerial JDBC）+ 原生 Web UI（Canvas）。
无前端构建工具，JSON 编解码为仓库内置的轻量实现（`src/main/kotlin/app/Json.kt`），
不依赖反射与第三方序列化框架。

## 安装

```bash
mvn -q -DskipTests package
```

## 演示

```bash
mvn -q test && mvn -q exec:java -Dexec.mainClass=app.MainKt -Dexec.args='--port 5528'
```

浏览器访问 <http://127.0.0.1:5528>，页面标题为“缸压相位镜”。

首次启动会自动导入内置确定性 fixture（也可点“重导入 fixture”）。
可选参数：`--port <端口>`、`--db <sqlite 路径>`、`--no-seed`。

## 页面布局

- **原始时域信号**：抽稀缸压（红色阴影为饱和段）、参考缺齿标记（紫）、
  发火 TDC 锦标（绿）、不可唯一识别跨距（黄虚线）、转速（右轴）。
- **齿间隔**：每个齿间隔的瞬时转速（rpm），缺齿跨距用彩色圆点标出并标注跨距数 `S`。
- **角域压力**：每个循环独立重采样到 0–720° 的 1° 栅格；蓝色半透明区是固定归零窗
  `[390°,450°)`；红色阴影为饱和段。点击表格中循环编号可聚焦单条曲线。
- **多循环包络**：所有未排除循环的 min / mean / max 包络与任一循环饱和的角段。

## 可执行的人工操作

- **确认缺齿模式**：当存在多组循环边界候选时，选择其中一个（操作写入审计表）。
- **置信一个上止点锦标**：把候选发火 TDC 吸附到被信的凸轮轴 TDC 标记，
  消除 720°/360° 与缺齿分解歧义，完成相位解析（pattern + TDC 均确认后才输出 CA50）。
- **排除传感器饱和段所在循环**：该循环不进入多循环包络。
- **调整某个循环的相位偏移**：以度为单位平移该循环的栅格角。

所有操作调用 POST 接口并记录到 SQLite `actions` 表，页面底部“操作审计”可见。

## 每循环指标（含算法/标定版本）

| 指标 | 键 | 说明 |
| --- | --- | --- |
| 峰压 | `pmax` | 1° 栅格最大缸压；含饱和时仅给出下界 |
| 峰压角 | `pmaxAngle` | 最大缸压对应的 °ATDC；饱和时不输出单一值 |
| 最大压升率 | `maxPressureRise` | 1° 一阶差分，bar/°；饱和时不输出 |
| 放热中心 | `ca50` | 净累积放热 50% 位置，°ATDC；相位未确认或饱和时不输出 |

每个指标都带 `algorithmVersion` 与 `calibrationVersion`（页面指标行 hover 可见）。
版本登记在 `src/main/kotlin/app/Versions.kt`：

- `tooth-gap-v2.1`：齿间隔滚动中位解码、整数跨距判定
- `angle-map-v1.2`：齿沿实际时间分段线性插值
- `pegging-v1.0`：固定角域归零窗
- `pmax-angle-v1.0` / `pressure-rise-v1.1` / `heat-release-ca50-v1.1`
- 标定：`wheel-60-2-cal-v1`、`geometry-2.0L-I4-v1`

## 数据口径与关键规则

- **齿盘（60-2）**：60 槽位中槽 59、0 缺失，形成 3 个齿距（18°）的参考缺齿；
  参考锚点 = 缺齿跨距中点 = TDC 前 3°；锚点后沿齿沿 = TDC 后 9°。
- **齿间时间换算**：只在相邻齿沿的**实际时间**之间分段线性插值；
  不假定每齿内转速恒定。长跨距（无齿沿）整段按两端实际时间线性拉伸，
  覆盖该跨距的循环标记 `longSpanInterpolation=true`。
- **连续两齿丢失**：当观测跨距为 S 个齿距时，其内部有 S-1 个缺槽，
  由 2 个参考缺槽 + S-3 个故障缺槽组成。在“连续两个齿丢失”约束下，
  合法分解只有“故障缺槽全在参考左（k1=0）”与“全在参考右（k1=S-3）”两种，
  各自产生一个锚点。两种分解均保留为**循环边界候选**，不强行拼接。
- **孤立单齿丢失（S=2）**：无法放入 3 齿距参考，整段不可同步。
- **归零窗**：固定在角域 `[390°,450°)`（相对发火 TDC，半开区间，进气冲程），
  窗内非饱和点均值作为归零偏移；开闭边界不随转速/循环漂移。
- **饱和处理**：压力达到 100 bar（`SATURATION_LIMIT_BAR`）即判为削顶；
  饱和点不做任何插值，峰压只报告下界，峰压角/压升率/CA50 不输出单一值；
  循环边界处的栅格点严格取本循环时间窗，不跨循环插值。
- **放热**：`dQ = γ/(γ-1)·p·dV + 1/(γ-1)·V·dp`，5 点滑动平滑，
  以 TDC 累积值为基线在 0–100°ATDC 搜索 50% 点；饱和或相位未解析时不给单一 CA50。

## 固定 fixture（验收场景）

`FixtureGenerator.generate()` 是确定性合成信号（PRNG 固定种子），参数：

- 60-2 齿盘，8 转，转速 1200 → 2200 rpm 线性上升；
- 缸压 25 kHz 均匀采样，约 7200 点、约 289 ms，覆盖 4 个完整四冲程循环；
- 发火 TDC 1440° 前的两个连续齿（1422°、1428°）丢失，
  与该转参考缺齿合并为 5 齿距（30°）长跨距，故障转不输出参考标记；
- 下一循环（cycle 1）峰值燃烧超过 100 bar，发生压力饱和削顶。

因此重建结果稳定呈现验收不变量：

1. 保留 **2 个**循环边界候选（`patternUnique=false`），仅故障发火 TDC 边界不同；
2. 缺齿模式未确认时，所有循环的 CA50 均为 `null`（不输出单一放热中心）；
3. 饱和循环 `pmax` 标记 `lowerBoundOnly=true`（≥100 bar），
   峰压角/压升率/CA50 均为 `null`，不跨循环插值。

## 自动化测试

```bash
mvn -q test
```

20 个 JUnit 5 测试覆盖：fixture 确定性、缺齿跨距与双候选、
未确认相位不输出 CA50、饱和只给下界、不跨循环插值、归零窗边界、
SQLite 清空/导出/回放一致、以及端到端 HTTP 工作流。

## 运行记录导出与清空后复核

- 页面“导出运行记录”或 `GET /api/runs/{id}/export`：导出包含
  原始 run（全部压力/齿沿/标记/工况）、当前分析状态、操作审计的 JSON 文件。
- `POST /api/admin/clear` 清空 `runs/state/actions` 全部表。
- `POST /api/runs/replay` 上传导出文件即可重新导入；
  或页面“清空数据库”后自动重新导入 fixture。
  由于 run id 固定（`synthetic-crank-v1`）且信号确定，清空后重新导入、
  重放同样的操作序列会得到完全一致的分析结果。

## REST 接口

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| GET | `/api/health` | 健康检查（含产品名） |
| GET | `/api/runs` | run 列表 |
| POST | `/api/runs/import-fixture` | 导入内置 fixture |
| POST | `/api/runs/import` | 导入 run JSON |
| GET | `/api/runs/{id}` | run 摘要 |
| GET | `/api/runs/{id}/raw` | 时域原始信号（抽稀） |
| GET | `/api/runs/{id}/analysis` | 全部分析结果与指标 |
| POST | `/api/runs/{id}/confirm-pattern` | 确认缺齿模式候选 |
| POST | `/api/runs/{id}/trust-tdc` | 置信 TDC 锦标索引 |
| POST | `/api/runs/{id}/cycle-offset` | 调整单循环相位偏移 |
| POST | `/api/runs/{id}/exclude-saturation` | 排除/恢复饱和循环 |
| GET | `/api/runs/{id}/actions` | 操作审计 |
| GET | `/api/runs/{id}/export` | 导出运行记录 JSON |
| POST | `/api/runs/replay` | 回放导出文件 |
| POST | `/api/admin/clear` | 清空数据库 |

## 数据存储

SQLite 文件默认位于 `data/pressure-phase-mirror.sqlite`
（首次启动自动建库建表：`runs / state / actions`）。
