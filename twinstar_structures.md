# 雙星合成引擎：資料結構骨架

來源：設計頁面 https://claude.ai/artifact/Ruu3Cie5sWLb96uwiLgcz5 （討論階段），以及 10-17～10-19 的決定。
初稿由 Haiku 整理；本版依審查結果修訂，並依 10-19「可以依賴 AE2」的決定調整。

## 這份文件遵守的決定

| 決定 | 影響 |
|---|---|
| **規劃器可以依賴 AE2**（10-19；twinstar 本來就只服務 AE2），放在 `com.lumina.twinstar.algorithm` | 可以直接用 `AEKey`、`IPatternDetails`、`ICraftingProvider`，不需要獨立的轉換層或機器介面 |
| **內部仍用整數 ID 加扁平陣列**（10-19 選項 A，效能考量） | `AEKey ↔ int` 的對照表放在快照裡（第 1 節）；演算法內部只用整數，每個物品（或樣板）都有一個值的資料一律用 `long[]`／`int[]`／`double[]` 直接索引；**只有稀疏而且很小的資料**（單張訂單的臨時價格、缺料清單）才用 Map |
| **測試** | 演算法內部只用整數，所以樣板圖可以直接用整數 ID 建立，一般 JUnit 就能測；只有對照表和轉換器那一層需要 ModDevGradle 的遊戲環境測試 |
| **支援天文數字**（樣板與規劃狀態都可能到 10¹⁰⁰⁰） | 數量一律用 `Amount`（內部是 long 或 BigInteger，見第 0 節）；溢位在 `Amount` 內自動升級 |
| **成本與 BOM 用對數儲存** | `double` 表示不了 10⁻¹⁰⁰⁰ 或 10¹⁰⁰⁰；存 log₁₀，相乘變相加，相加用 log-sum-exp。原料價格本來就是 `10^(−級數)`，log 後就是 `−級數` |
| **第一版用 `Pattern` 物件**，CSR 是之後的最佳化 | `Pattern` 是建立快照時的輸入（不可變的值物件）；快照內部要不要轉成 CSR 是快照自己的事，對外方法不變 |
| **規劃在背景執行緒** | 規劃器不能直接讀 AE2 的存儲；庫存由主執行緒先拍成快照（`StockSnapshot`）再交給規劃器 |

標記：`← 補` = 設計頁面有用到、但原本沒寫進結構；`【待定】` = 還沒決定，彙整在第 9 節。

---

## 0. 共用（`algorithm.model`）

```java
enum InputKind { CONSUME, CATALYST, TOOL, CONTAINER }
// CONSUME   每次執行都用掉
// CATALYST  要有但不消耗（持有需求，取最大值）
// TOOL      耐久工具，每次執行扣一點耐久
// CONTAINER 用完歸還（空桶等「配方殘留物」用這個表達，不另設殘留物清單）

enum PatternLocation { HUB_INTERNAL, EXTERNAL_CRAFT, EXTERNAL_PROCESS }   // ordinal 即路線排序鍵的 rank：0 / 1 / 2
enum SccKind { GROWTH, ENTANGLED }                                          // 增殖 / 糾纏
enum OrderMode { EXACT, BEST_EFFORT }                                       // BEST_EFFORT = 能做多少就做多少
```

### 數量：`Amount`（long 與 BigInteger 合一）

```java
/**
 * 所有數量都用這個型別。值在 long 範圍內就只存 long（快速路徑）；超過才存 BigInteger。不可變。
 * 規範化：值能放進 long 時 big 一定是 null，同一個值只有一種表示，equals／compareTo 不會失準。
 * 溢位不丟例外、也不回傳截斷值，而是自動升級成 BigInteger。
 */
final class Amount implements Comparable<Amount> {
    static final Amount ZERO = of(0L);
    static final Amount ONE = of(1L);

    private final long small;       // big == null 時有效
    private final BigInteger big;   // 只有超過 long 範圍時才非 null

    static Amount of(long v);
    static Amount of(BigInteger v); // 能放進 long 就規範化成 small
    boolean isBig();
    long longValue();               // isBig() 時丟 ArithmeticException，避免拿到截斷的值
    BigInteger bigValue();          // 任何時候都可用
    Amount add(Amount o);           // 溢位 → 升級
    Amount sub(Amount o);
    Amount mul(Amount o);
    Amount ceilDiv(Amount o);       // ceil(runs ÷ 耐久度)
    int compareTo(Amount o);
}

/** 每個物品一格（B 的 need／done／avail／ledger，CycleState 的庫存等）。外部只看到 Amount；內部用 long[] 快速路徑，溢位的格子另存（實作細節）。 */
final class AmountArray {
    Amount get(int i);
    void add(int i, Amount v);
    boolean isBig(int i);
}

/** 稀疏的物品 → Amount（缺料清單、工作庫存等，項目少）。 */
final class AmountMap {
    Amount get(int item);
    void add(int item, Amount v);
}
```

### 樣板

```java
/** 不可變。陣列在建構時複製，對外只回傳單一值，不交出陣列本身。 */
final class Pattern {
    final int id;
    // 輸入：三條等長陣列，第 i 個輸入就是第 i 格
    private final int[] inputItems;
    private final Amount[] inputAmounts;
    private final InputKind[] inputKinds;
    private final int[] inputDurability;             // 只有 TOOL 有意義；耐久度永遠在 int 範圍內 ← 補（設計頁面沒寫來源，由轉換器從 AE2 樣板讀出）
    // 產出：所有產出一視同仁，沒有主產物／副產物之分
    private final int[] outputItems;
    private final Amount[] outputAmounts;
    final PatternLocation where;
    final int priority;                              // 供應器優先權（沿用 AE2）
    final boolean allowSubst;                        // 允許替換
    final double energyPerRun;                       // ← 補：每份能源 = Σ 輸入數量 ÷ 每次操作單位量；由轉換器算好（單位量是 AE2 的 key type 屬性）
    final @Nullable IPatternDetails source;          // 原始 AE2 樣板，派發時要用；單元測試用整數建樣板時為 null

    int inputCount();  int inputItem(int i);  InputKind inputKind(int i);  int inputDurability(int i);
    Amount inputAmount(int i);
    int outputCount(); int outputItem(int i);
    Amount outputAmount(int i);
}
```

### 其他共用型別

```java
/** 原料價格的級數（10^(−級數)）。和中樞階級是兩回事，所以不叫 tier。 */
// 用 int[] priceTier 存在 TheoryLayer，見第 2 節

/** 路線排序鍵：優先權 → 有沒有用到保留材料 → 材料成本 → 樣板所在 → id */
record RouteKey(int negPriority, int usesReserved, double logUnitCost, int rank, int p) {
    static final Comparator<RouteKey> ORDER = Comparator
            .comparingInt(RouteKey::negPriority)
            .thenComparingInt(RouteKey::usesReserved)
            .thenComparingDouble(RouteKey::logUnitCost)   // log 單調，比較結果與原成本相同
            .thenComparingInt(RouteKey::rank)
            .thenComparingInt(RouteKey::p);
}

/** 單張訂單專用的覆寫，不影響理論層。項目少，用 Map 是合理的。 */
final class Overlay {
    final Map<Integer, Double> logPrice = new HashMap<>();   // 沒有的物品用理論層價格；−∞ = 隨便用（價格 0），+∞ = 用完
    final BitSet reserved = new BitSet();                    // 確認畫面的「保留」標記
}

/** 主執行緒拍下的庫存快照，規劃器只讀它。 */
final class StockSnapshot {
    final AmountArray stock;                         // 依物品 ID
    final BitSet infinite;                           // 無限來源
}

record Deadline(long startNanos, long softNanos, long hardNanos) {}   // 軟 0.5 秒、硬 1 秒
```

---

## 1. AE2 銜接（`algorithm.model` / `algorithm.cache`）← 補

10-19 決定可以依賴 AE2，所以不再獨立成一層；這些類別直接放在演算法套件裡，只是演算法核心仍只用整數。

```java
/** AEKey ↔ 物品 ID，放在快照裡。同一個網路內 ID 穩定；樣板增減時只新增，不重排。 */
final class ItemIndex {
    int idOf(AEKey key);                             // 沒有就分配一個新 ID
    AEKey keyOf(int id);
}

/** 把網路上的樣板翻譯成內部 Pattern。 */
interface PatternAdapter {                           // 轉換器（我們自己的 ＋ 之後的公開 API）
    boolean handles(IPatternDetails details);
    List<Pattern> translate(IPatternDetails details, ItemIndex index);   // 一個 AE2 樣板可能翻成多個內部樣板
    // 派發與完成判斷：執行端直接用 Pattern.source 與 ICraftingProvider；特殊樣板的派發方式【待定】
}

final class PatternCompiler {                        // 依序查轉換器；都不認得就照清單翻譯
    final List<PatternAdapter> adapters;
}

final class StockSnapshotter { StockSnapshot take(MEStorage storage, ItemIndex index); }   // 只在主執行緒呼叫（規劃在背景執行緒）

final class PlanWrapper implements ICraftingPlan { /* 把我們的 Plan 包成 AE2 確認畫面看得懂的形式 */ }
```

---

## 2. 合成圖快照（`algorithm.cache`）

快照不可變；更新時建新版本（之後再改成以 64 個為一塊的寫時複製）。

```java
final class Scc {
    final int[] members;                             // 物品 ID
    final int[] policy;                              // 與 members 平行：成員選定的樣板 ID
    final SccKind kind;
    final int[] seedItems;  final AmountArray seedAmounts;   // 跑一圈所需的環內物品
    final Rational[][] internal;                     // 內部消耗矩陣（精確分數，見第 5 節）
    final int[] macroItems; final Rational[] macroPerUnit;   // 每單位產出所需的外部輸入（精確）
}

final class Snapshot {
    final long version;

    // ── 物品（索引 = 物品 ID）──
    final int[] level;                               // 原料 = 0
    final int[] scc;                                 // −1 = 不屬於任何 SCC
    final double[] logCost;                          // log₁₀(理論單位成本)；+∞ = 做不出來
    final int[] best;                                // 最佳樣板；−1 = 無
    final int[][] bomItems;                          // 稀疏 BOM：做 1 個 i 需要的原料
    final double[][] bomLogQty;                      // 與 bomItems 平行：log₁₀(用量)。只用於估算，真正數量由 B 精確計算
    final int[][] producers;                         // 能產出 i 的樣板，依 RouteKey 排好（不可變，所以用排序陣列而不是 heap）
    final int[][] consumers;                         // 輸入含 i 的樣板（策略邊 ＋ 監看邊）
    final int[] byLevel;                             // 依層級排序的物品 ID，B 與 A 的遍歷順序

    // ── 樣板（索引 = 樣板 ID）──
    final Pattern[] patterns;                        // 第一版；之後可改 CSR，對外方法不變
    final int[][] patBomItems;  final double[][] patBomLogQty;   // ← 補：bom_p
    // bom_p = Σ_消耗輸入 (數量 × bom[輸入]) ÷ 產出數量 ＋ Σ_TOOL (bom[工具] ÷ 耐久度) ÷ 產出數量；觸媒、容器不計

    // ── 策略 SCC ──
    final Scc[] sccs;

    // ── 反向邊的安全樣板：(x, y) → y 的樣板中不會碰到 x 的那些 ──
    final Map<Long, int[]> safe;                     // 鍵 = ((long) x << 32) | y；反向邊少，用 Map 合理
}
```

---

## 3. 理論層（A，`algorithm.cache`）

```java
final class DirtySet {                               // 鍵 = level[x]，由低到高；同一物品只放一次
    private final int[] heap;  private int size;
    private final BitSet queued;
    void push(int item);
    int popMin();
}
// 修正設計頁面的不一致：processScc 原本把「SCC 編號」放進這個以物品為鍵的 heap。
// 改成放入 SCC 的一個成員（例如 members[0]）；flush 取出後用 scc[x] ≠ −1 判斷，就會正確進入 processScc。

final class TheoryLayer {
    static final double LOG_HIGH = 1.0;              // 無庫存原料的價格 10 → log = 1
    static final double HYSTERESIS = 0.8;            // 級數回落門檻
    static final double EPS = /*【待定】*/ 0;

    volatile Snapshot snap;                          // 寫入時整個換成新版本；背景規劃讀自己開始時的那一版
    final int[] priceTier;                           // 原料價格級數（不叫 tier，避免和中樞階級混淆）
    final DirtySet dirty;

    double logRawPrice(int k, StockSnapshot s);      // 無限來源 → −∞（價格 0）；庫存 0 → LOG_HIGH；否則 −priceTier[k]
    void onStockChanged(int k, StockSnapshot s);
    void build();                                    // tarjan → 由原料往上 → level / SCC / bom / safe
    void markDirty(int x);
    void flush();                                    // 背景執行緒，合併同一 tick 的變動
    void processScc(Scc s);
    void raiseLevel(int x, int L, int trigger);      // trigger：觸發這次提升的輸入 y，用來判斷繞回
    Scc mergeIntoScc(int[] path);
    void onPatternRemoved(int p);
}
```

---

## 4. 需求傳遞（B，`algorithm.planning`）

```java
final class Plan {
    final long snapshotVersion;
    final int target;
    final Amount amount;                             // 下單數量
    final AmountMap patternTimes;                    // 樣板 → 次數
    final AmountMap pulled;                          // 從網路抽進 CPU 的物品
    final AmountMap missing;                         // 缺料（原料層）
    final AmountMap leftover;                        // 帳本剩下的零頭與多做的產物（結束時歸還）
    Amount usage;                                    // 容量用量：拉進來的材料 ＋ 中間 ＋ 最終
}

final class PlanState {                              // 單張訂單計算時的工作區，用完即丟
    final AmountArray avail;                         // 從 StockSnapshot 複製
    final AmountArray ledger;
    final AmountArray need;
    final AmountArray done;
    final int[] ctxId;                               // 物品 → 禁止清單 ID；清單本身共用、不可變，取聯集時產生新的
    final List<BitSet> ctxSets;
    final int[] queue;  int queueSize;               // 以 level 為鍵的最大 heap，允許再次進入
    final Overlay overlay;
}

record Split(int p, Amount runs, Amount used) {}

sealed interface OrderResult permits Accepted, Rejected, TimedOut {}
record Accepted(Plan plan) implements OrderResult {}
record Rejected(Amount suggested) implements OrderResult {}   // 超過黑洞剩餘容量；佔用中的工作清單由 MC 那側補上
record TimedOut() implements OrderResult {}                         // 硬上限 1 秒

final class Planner {
    Plan plan(Snapshot snap, StockSnapshot stock, int target, Amount amount, Overlay overlay, Deadline dl);
    OrderResult order(..., OrderMode mode, Amount freeCapacity, Deadline dl);
    // fire / take / greedySplit 等為內部方法
}
// submit（抽料、失敗自動重算一次）要碰網路、在主執行緒執行，放在 TwinStarService，不在 Planner 裡（Planner 可能在背景執行緒）。
```

---

## 5. 多樣板（C，`algorithm.allocation`）【研究中】

設計頁面的 C1／C2（臨時價格、延遲重算的 heap）在模擬中有 11～15% 假缺料，目前研究方向改為：

```java
final class ContestedCore {                          // 只取有衝突的核心：從目標走得到、有多條路線、碰到吃緊原料
    final int[] items;  final int[] patterns;
}

final class LpModel {                                // 兩階段 LP：先求做最多，再固定數量求成本最低
    // 係數用 double；天文數字時要先依物品選單位縮放【待定：縮放方式】
}

// 依層級由上往下固定成整數，每層重解 LP → 整數解 L
// L == 上界 U → 證明最佳；否則分支定界（LP 上界剪枝），受 0.5 秒軟上限限制
```

單純形法與 D 共用（D 用精確分數，C 用 `double`）。

---

## 6. 循環（D，`algorithm.planning`）

```java
final class Rational {                               // 精確分數（狀態方程、巨集樣板）
    final BigInteger num;  final BigInteger den;
}

final class CycleState {                             // A* 節點；狀態只含環內物品（通常幾十個，直接複製即可）
    final AmountArray stock;
    final Amount[] runsDone;                         // SCC 內每條樣板已跑次數
    final double g;
    final CycleState parent;
    final int via;
}

record CycleContract(int[] sequence) {}              // 寫進執行合約的樣板順序

// runMacro / verifyCycle / stateEquation（第一階段單純形法，精確分數）/ aStar（h = 線性方程下界）
```

---

## 7. 執行端（F，`algorithm.execution`）

10-19 決定可以依賴 AE2，所以執行端**直接用 `ICraftingProvider`** 派發（`Pattern.source` 是原始 AE2 樣板），不另設機器介面。內部的進度、在途期望、回流池仍用整數 ID。
代價：執行端的單元測試要在 ModDevGradle 的遊戲環境裡跑，或者另外寫假的 `ICraftingProvider`。

```java
enum PushOutcome { ALL, PARTIAL, REJECTED }
record PushResult(PushOutcome outcome, Amount accepted) {}

final class Task {                                   // 進度 DAG 的節點
    final int item;  final int pattern;  final int level;
    Amount remaining;                                // 剩餘次數
    Amount inFlight;                                 // 在途次數
    final int[] dependents;
}

final class Job {
    final Plan plan;
    final AmountMap inventory;                       // 隔離的 CPU 庫存（物品 ID）
    final PriorityQueue<Task> ready;                 // 依 level 由低到高
    final Map<Integer, List<Task>> waitingOn;        // 物品 → 等它的任務（稀疏）
    final AmountMap outstanding;                     // 在途期望
    final Map<Integer, Long> outstandingSince;       // 最後一次減少的時間（卡住判斷）
    Amount occupancy;                                // 目前佔用的黑洞容量（邊做邊釋放）
    boolean suspended;                               // 暫停（建議）
    boolean unloaded;                                // 區塊曾被卸載（F9）
}

final class Window {                                 // 每台機器 × 樣板一個
    Amount size = Amount.ONE;                        // 批次窗口；無上限時就是能做的都推，所以用 Amount
    Amount lastOk = Amount.ZERO;                     // 上次成功量
    final long[] rttSamples = new long[16];  int rttCount;   // 門檻 = max(中位數 × 4, 10 秒)（建議）
}

/** 每個網路一份的執行狀態。由 TwinStarService 持有；不要另外再有一個 Hub 重複管理工作清單。 */
final class HubState {
    final List<Job> jobs;
    final AmountMap pool;                            // 回流池
    Amount used;                                     // 所有工作合計佔用
    final Map<Long, Window> windows;                 // 鍵 = 機器 × 樣板
    int rotate;
}
// 網路拆開或合併時，Service 會被丟掉重建：進行中的工作要怎麼保存【待定】
```

---

## 8. lib 結構（服務、CPU、mixin，`com.lumina.twinstar`）

依 repo 現況：

```java
public interface ITwinStarService extends IGridService {   // 只放穩定的唯讀查詢
    boolean hasHub();
    int getParallelism();
    long getCapacity();
}

public final class TwinStarService implements ITwinStarService, IGridServiceProvider {
    TwinStarService(IGrid grid, IEnergyService energy);     // AE2 依建構子參數型別注入
    private final Map<IGridNode, ITwinStarHost> hosts;      // 第一個能運作的宿主生效（多中樞規則【待定】）
    private final TwinStarCpu idleCpu;                      // 固定物件
    private final List<TwinStarCpu> workingCpus;            // 每張工作一格
    // HubState（第 7 節）之後也由這裡持有
    void addNode(IGridNode node, @Nullable CompoundTag savedData);
    void removeNode(IGridNode node);
    void onServerEndTick();
}

public final class TwinStarCpu implements ICraftingCPU {
    private final TwinStarService service;
    private Job job;                                        // null = 空閒格
    // 不另存 stored / waitingFor 等副本：畫面要資料時，再從 Job 組出 CraftingStatus
}
```

`ITwinStarHost`（`getHostParallelism`、`getHostCapacity`、`isHostActive`、`getHostWorkerMode`，繼承 `IActionHost`）與設計一致。

**中樞階級的數值**（並行數、容量）屬於引力科技，不放在 lib；lib 只透過宿主介面詢問。

---

## 9. 仍需定案的問題

1. **ε 的數值**：理論層（成本沒變就停止往上傳）與 C2 都用到。
2. **中樞數量**：一個網路多個中樞時的規則；單元能不能疊加。
3. **天文數字的樣板是否限定由中樞內部執行**（建議限定：外部機器一次只收得下 `long` 範圍）。
4. **LP 在天文數字時的縮放方式**（依物品選單位，或改用精確分數）。
5. **特殊樣板的派發方式**：轉換器翻譯過的樣板（放大樣板、封閉循環等）要怎麼派發與判斷完成。
6. **網路拆開或合併時的工作保存**（`saveNodeData` / `addNode`，或存在宿主身上）。
7. **耐久度的來源**：由轉換器從 AE2 樣板讀出，需要確認 AE2 樣板能提供哪些資訊。
8. 已修正、需同步回設計頁面：`processScc` 入隊改為放成員；價格級數改名 `priceTier`；殘留物用 `CONTAINER` 表達；`bom_p` 的推導公式（第 2 節）。

---

## 10. 實作（2026-10-22）

**10-22 使用者決定：整個 `algorithm` 套件由 Claude 寫完**（取代 10-17 的「使用者自己寫、Claude 教」）。服務、CPU、mixin（第 8 節）不在這次範圍內，仍是原本的暫時版。

### 套件

| 套件 | 內容 | 依賴 |
|---|---|---|
| `algorithm.model` | `Amount`、`AmountArray`、`AmountMap`、`Pattern`（含 Builder）、`Rational`、`LogMath`、`Overlay`、`StockSnapshot`、`Deadline`、`RouteKey`、各列舉 | 純 Java |
| `algorithm.cache`（A） | `TheoryLayer`、`Snapshot`、`Scc`、`DirtySet`、`Tarjan`、`DenseLu` | 純 Java |
| `algorithm.planning`（B、D） | `Planner`、`DemandPass`、`Plan`、`Split`、`OrderResult`、`CycleSolver`、`CycleContract`、`ExactSimplex` | 純 Java |
| `algorithm.allocation`（C） | `RouteCeiling`（C1）、`Repair`（C2） | 純 Java |
| `algorithm.ae2` | `ItemIndex`、`PatternAdapter`、`PatternCompiler`、`StockSnapshotter`、`PlanWrapper` | AE2 |
| `algorithm.execution`（F） | `HubState`、`Job`、`Task`、`Window`、`StuckMark`、`ExecutionEnv` | AE2 |

舊的 `algorithm/Snapshot.java` 已刪除（改在 `cache`）；`Pattern.java`、`InputKind.java` 改寫成完整版本。

### 和前幾節不同的地方

1. **`Pattern.source` 的型別是 `Object`**（內容是 `IPatternDetails`）：讓 model／cache／planning／allocation 不碰到 AE2 型別，一般 JUnit 就能測。AE2 相關的程式集中在 `ae2` 與 `execution`。
2. **快照沒有 `safe` 表**：B 碰到反向邊時才檢查「這條路線的策略子樹會不會碰到禁止清單」（每次規劃有快取）；都會碰到時退回只檢查直接輸入。
3. **`Scc` 沒有 `macroItems／macroPerUnit`**：存的是精確的 `net` 矩陣（每次執行對每個成員的淨產出），`runMacro` 直接用精確單純形法求次數。
4. **最佳樣板的順序**：做得出來的優先 → 供應器優先權 → 成本 → 所在 → id，和 B 的路線順序一致。
5. **理論層的策略迭代**加了兩件事：
   - 從 ∞ 出發的 Bellman–Ford 進不了「要先有種子才能跑」的增殖環，所以對仍是 ∞ 的成員試三種起點（成員當免費的最便宜路線／每個產出用最少成員的路線／從下方的值迭代），各自精確求解後取做得出來最多的。
   - 同時切換造成「原本做得出來的變成做不出來」時，改成逐一切換，造成問題的那一個暫時禁止（其他切換成功後就解除）。
   - **完全沒有外部輸入的環一律是 ∞**（設計頁：「沒有任何外部來源的環 → ∞」）。
6. **增量更新改走重建**的條件：碰到策略 SCC、新路線會繞回自己、或物品仍做不出來（∞，可能要靠新的環）。
7. **C1**：先用 BOM 估計原料最多能做多少，用一次 B 確認，不夠就最多往下調 8 次（每次 ×0.9），夠就最多往上加倍 4 次；都不行才二分搜尋（受 0.5 秒軟上限）。原本「每條路線都二分搜尋」在大圖上會慢到不能用。
8. **C2**：照設計頁的版本實作（研究中的 LP 版本沒有做）。ε = 10：設計頁的 ε 未定，ε = 1 時 P1/P2 範例仍然缺料（原料價格級數差 10 倍，用完整批庫存只漲 e^ε 倍，ε 要明顯大於 ln 10）。起始價格下限 0.01、K = 100。
9. **D 的種子**：這張訂單本來要從庫存拿的環內物品，可以改當種子（環會補回），避免「庫存裡有 1 顆種子卻報缺料」。
10. **F 的回流**：每個工作在輪詢到時取回自己在途期望內的量（仍受期望限制、工作之間輪流），不是任務執行當下才拿；送到外部機器的容器與觸媒也列為在途期望。
11. **AE2 樣板轉換**：剩餘物等於輸入本身 → `CONTAINER`；不同（水桶 → 空桶）→ `CONSUME` 並把剩餘物加成產出。AE2 樣板沒有耐久度，`TOOL` 只會來自轉換器。庫存回報 `Long.MAX_VALUE` 視為無限來源。
12. 理論層的 ε（成本沒變就停止往上傳、策略切換門檻）先用 1e-9。

### 測試（`core/src/test`，`gradlew :core:test`，29 個全部通過）

- `AmountTest`、`ExactSimplexTest`。
- `TheoryLayerTest`：成本／層級／BOM、優先權、增殖環、互轉配方、級數緩衝，以及**增量 == 重建**：預設 200 輪（每輪 15 次隨機增刪樣板或改庫存），已用 30,000 輪（45 萬次變動）跑過全部通過。`-Drounds=N` 可加大。
- `PlannerTest`（14 個）：共用中間產物只算一次、先用中間產物庫存、ceil 進帳本、缺料在原料層、觸媒取最大值、耐久工具、**P1/P2 做出 7 個**、增殖環用庫存當種子、反向邊、替換、能做多少就做多少、超過容量回報最大可做量、10^1000 的數量、保留標記。
- `PlannerScaleTest`：8,660 個樣板，建立 124～178 ms，60 種材料的大訂單規劃 32 ms（用到 380 個樣板），增量更新 4～14 ms，原料吃緊時 255 ms。

**沒有測到的**：`execution`（F）與 `ae2` 只確認能編譯，單元測試需要 AE2 與 MC 的遊戲環境（或假的 `ICraftingProvider`）。設計頁要求的「做得出來就不能報缺料（和暴力搜尋對照）」也還沒做；C2 本來就已知有 11～15% 假缺料。

### 新的待決問題

1. **優先權造成的多解**：兩條高優先權路線合起來會形成跑不動的環時，留哪一條沒有唯一答案，增量更新和重建可能停在不同結果。要定規則（例如固定保留 id 小的那一條），或接受。
2. **沒有外部輸入的增殖環**（例如 1 個 X → 2 個 X）：照設計頁是 ∞。但這讓最佳策略變成組合問題，增量和重建也可能不同。要不要改成「成本 0、只需要種子」？
3. C2 改成 LP 版本（研究中）。
4. ~~F 的整合~~：已接上，見下方「接線」。

### 接線（10-22）

| 位置 | 做什麼 |
|---|---|
| `TwinStarService` | 持有 `ItemIndex`、`PatternCompiler`、`TheoryLayer`、`HubState`。理論層與規劃只在一條背景執行緒「TwinStar Engine」上跑（所有網路共用、依序）；`HubState`、AE2 存儲與樣板登錄只在伺服器執行緒碰。 |
| 樣板同步 | 每 tick 檢查 AE2 樣板登錄的修改 tick（`CraftingServiceAccessor` 取 `craftingProviders`），有變才比對：新樣板在伺服器執行緒翻譯，交給背景執行緒加進理論層；移除或優先權改變的先移除。樣板優先權 = 提供它的供應器中最高的；供應器本身是中樞（`ITwinStarHost`）就算中樞內部樣板。 |
| 庫存同步 | 每 20 tick 拍一次庫存快照，背景執行緒更新原料價格級數。 |
| `beginCraftingCalculation` | 有中樞就改走引擎：伺服器執行緒拍庫存，背景執行緒 flush 理論層後呼叫 `Planner.order`。`CRAFT_LESS` = 能做多少就做多少。超過容量時仍回傳計畫，AE2 會顯示「CPU 太小」；逾時讓 Future 失敗。 |
| `submitJob` | 我們的計畫（`PlanWrapper`）跑在中樞上（不論有沒有選 CPU）；選了中樞但計畫是 AE2 算的 → 拒絕（`NO_SUITABLE_CPU_FOUND`），選了別的 CPU 但計畫是我們的 → 也拒絕。抽料失敗會重新規劃一次（最多等 2 秒），仍失敗就回報第一個缺料。最終產物先交給請求者（AE2 的合成連結），其餘進存儲。 |
| `insertIntoCpus` | AE2 自己的 CPU 先拿，剩下的由中樞依在途期望收下。 |
| `isRequesting`／`getRequestedAmount`／`isRequestingAny` | 加上中樞工作的在途期望。 |
| `onServerEndTick` | 服務自己的 tick 執行 `HubState.hubTick`（每 tick 計算上限 5 ms），完成的工作 `link.markDone()` 並移除 CPU 格子。 |
| `TwinStarCpu` | 閒置格一個 ＋ 每張工作一格（物件固定）。閒置格的容量 = 剩餘黑洞容量，工作格 = 該工作仍佔用的量。CPU 畫面：stored = 工作庫存、active = 在途、pending = 剩餘次數 × 產出；`getJobStatus` 回報交出量。 |
| `CraftingCPUMenu` | 取消、暫停（`toggleScheduling` → 工作暫停）、狀態畫面都改讀新的 `Job`。 |

舊的 `job/Job.java` 已刪除（改用 `algorithm.execution.Job`）。

**mixin 已在伺服器驗證（10-22）**：把 `lumina-multiblock` 的 `mod_version` 改成 `1.0.0` 後，`runServer` 可以載入模組；log 顯示 `CraftingServiceMixin`、`CraftingServiceAccessor`、`CraftingCPUMenuMixin` 的每一個注入都成功套用，沒有 mixin 錯誤。實際下單、執行、CPU 畫面還沒在遊戲裡測過。

**修正（10-23，遊戲內回報）**：

- **下單的物品一律全部合成**：原本 B 對目標物品也先拿庫存，網路裡已有 2 個時，下單 100 只合成 98，還把那 2 個抽進 CPU 再交回來。現在照 AE2，目標的下單數量一律合成；只有其他物品才先用庫存（新增測試 `orderedItemInStorageIsNotCountedAsCrafted`）。
- **只合成第一份就停住**：外部機器（分子組裝器）收下第一份後變忙，同一 tick 再派發失敗時，任務被設成「等材料」；但材料本來就在，不會再有通知，所以永遠不再派發。現在材料在、只是機器忙或沒電時，任務改成下一個 tick 重試。

**下單數量（10-23）**：AE2 的下單數量畫面讀成 int、封包也是 int，超過 int 就無法下單。

- 輸入框（所有 AE2 數字欄位）多支援單位 K／M／G／T／P／E（10³～10¹⁸，大小寫皆可，和 AE2 顯示的單位相同，例如 `3E`）與科學記號 `2e18`、`1.2e3K`；`e` 後面接數字是指數，單獨結尾的 `E` 是單位；開頭的 `=`（只做缺少的）照樣有效。純數字和算式仍交給 AE2 原本的解析器（`util/AmountParser`，有測試）。
- **下單數量不設上限（BigInteger）**：超過 int 的數量改走新封包 `ConfirmBigCraftPacket`（數量以十進位字串傳送，上限 4096 位數）。伺服器照 AE2 原本的步驟開確認畫面：不超過 long 時走 AE2 的 API（有中樞時仍由引擎規劃）；超過 long 時直接呼叫引擎，網路上必須有運作中的中樞，否則提示玩家。「重新計算」沿用完整數量。數字欄位超過 long 時不再溢位成負數，改成在 long 上限飽和，畫面上的檢查照樣運作。
- **確認畫面（10-23 修正）**：AE2 的摘要把「使用 ＋ 缺少」用 long 相加，超大訂單會溢位成負數，格子就什麼都不顯示。現在引擎算的計畫改由 `PlanSummaries` 依計畫本身建立摘要：AE2 的 long 欄位放飽和值（排序、紅色標示照常），精確數值與「無限來源」標記另外附在封包後面傳到客戶端。格子顯示精確數量（超過 long 時用單位短格式（見下方「數字顯示改用單位」），提示框顯示每一位數），無限來源的物品顯示「可用數量：∞」，提示框補一行「無限來源：執行時才取出（本次使用 N）」；標題的使用量也顯示精確值。
- **宿主的並行數與容量改成任意大小（10-23）**：`ITwinStarHost.getHostParallelism()`／`getHostCapacity()` 改回傳 `BigInteger`，null 代表不限（原本是 `int`／`long`，容量最多 9.2e18，1e40 的訂單一定「CPU 存儲容量不足」）。`ITwinStarService.getParallelism()`／`getCapacity()` 同樣改成 `BigInteger`；給 AE2 的 CPU 清單時才飽和成 int／long。虛擬 CPU（ae2_gvt 的 `DummyTwinStarCPUBlockEntity`）的容量與並行數改用 `BigInteger` 存檔（十進位字串，舊存檔的 long 照樣讀得到），畫面同步改用字串，輸入框可輸入任意位數，也接受 `3E`、`2e18`、`5K` 這類寫法，空白或 0 = 不限。
- **數字顯示改用單位（10-23）**：超過 long 的數量改用 K M G T P E Z Y R Q（10³～10³⁰）顯示，最多三位有效數字（例如 `45.6Z`）；超過 1000Q 才用科學記號（`1.00e40`）。輸入（下單、虛擬 CPU）也接受 Z／Y／R／Q。確認畫面標題的使用量超過 long 時也用單位顯示。所選 CPU 的「可存儲／並行處理單元」：不限顯示 ∞；其他用單位；大到 AE2 欄位（long／int）裝不下的有限值顯示成單位加「+」（中樞把「不限」送成欄位上限、「太大」送成上限減一來區分）。
- **CPU 狀態畫面（10-23 修正）**：同樣的做法套用到 AE2 的狀態條目（`CraftingStatusEntry`）：已存（CPU 內）、正在合成（在途）、計劃合成（剩餘次數 × 產出）都附上精確值；還有無限來源要陸續取出的物品顯示「可用數量：∞」，提示框補一行 CPU 內現有量。標題的預估時間沿用 AE2 的算法（經過時間 ÷ 已完成 × 剩餘），天文數字訂單算出的時間超過 long 上限時，標題改顯示「∞」（原本是 2562047:47:16）。
- **無限來源的判斷（10-23 修正）**：AE2 的創造型儲存元件對每種物品回報 `Integer.MAX_VALUE`，不是 `Long.MAX_VALUE`，原本會被當成 21 億的有限庫存。現在對回報量 ≥ `Integer.MAX_VALUE` 的物品模擬取出 `Long.MAX_VALUE`，網路肯給這麼多就算無限來源（實際的存儲不可能有這麼多）。
- 限制：從確認畫面按「返回」時，數量欄會顯示成 int 上限（AE2 開啟數量畫面時只接受 int）。

**天文數字訂單的執行（10-23 決定）**：

- **並行數語意不變**：一個 tick 內送出的所有樣板次數加總 ≤ P。宿主回報 `Integer.MAX_VALUE` 代表不限（例如普朗克階），這時中樞內部樣板一個 tick 可以跑完整批。
- **無限來源改成執行時再抽**：規劃時來自無限來源的部分記在 `Plan.drawLater`；提交時不抽，執行時要用前再取。只要網路模擬取出 `Long.MAX_VALUE` 仍然全給（來源還在），就直接記入所需的全部數量，不經過 AE2 每次最多一個 long 的取出（10-23 修正：原本每 tick 只能拿一個 long 的材料，合成速度因此卡在約 9.2E 次）；來源不見了才改成實際取出。其他物品照舊在提交時全部抽進 CPU。
- **能源改成精確分數記帳**：`Pattern.energyPerRun` 是 `Rational`。執行時先向中樞自己的能源取電（新介面 `ITwinStarEnergySource`，由 `ITwinStarHost.getHostEnergySource()` 提供，預設沒有），不夠的部分再向 AE2 網路取（`double`）。能源元件還在規劃中，之後實作這個介面就能接上。
- **交付到存儲**：AE2 的存儲只吃 long，天文數字的成品每次最多寫入 16 個 long 份，其餘留在工作裡分好幾個 tick 交付，避免伺服器卡住。

**修正（10-23）**：選了原版 CPU 卻送出引擎的計畫（或反過來）時會當機。AE2 的「沒有合適的 CPU」錯誤必須附上 `UnsuitableCpus` 細節，同步到客戶端時才不會出錯；原本傳的是 null。現在改由 `TwinStarService.wrongEngine` 回傳附細節的錯誤，並在聊天欄說明原因（「此計畫只能由雙星調度中樞執行」或「中樞只能執行由它計算的計畫」）。

**測試中樞識別樣板（10-23）**：虛擬 CPU（`DummyTwinStarCPUBlockEntity`）同時是樣板供應器（`ICraftingProvider`）：格子裡的編碼樣板會提供給網路。因為供應器本身就是中樞，這些樣板算中樞內部樣板，由引擎在黑洞內直接完成，每 tick 最多 P 次，可以不接外部機器就測試並行數。它對 AE2 自己的 CPU 一律回報忙碌，原版 CPU 不會嘗試推送給它；格子變動時會通知網路重新讀取樣板。

**引擎紀錄（10-23）**：`service/EngineLog` 把引擎的運作印到主控台（開發環境預設開啟，其他環境用 `-Dtwinstar.debug=true` 開啟、`=false` 關閉）：
- 樣板同步：新增／移除了哪些樣板（輸入、產出、種類、所在、優先權）。
- 理論層每次更新：全部重建或增量更新幾個物品、用時、物品／樣板／策略環數；全部重建時依層級列出每個可合成物品的最佳樣板、理論成本、是否在策略環、還有幾條路線。
- 每張訂單的合成樹：從目標往下，每個物品用了哪張樣板、跑幾次、從庫存或無限來源拿多少、缺多少（共用的中間產物只列一次），以及缺料與結束後歸還的清單。
- 工作開始、完成或取消。

**已知限制**：網路拆開或合併時服務會重建，進行中的工作會遺失（待討論第 6 項）；確認畫面的保留標記、建議數量按鈕還沒做。

### 10-23：大數存儲、能源上限、循環種子

- **雙星測試存儲**（`twinstar:big_storage`，`storage/` 套件，方塊 + 方塊實體，無上限，測試用）：推翻「存儲沿用 AE2」。
  - 內容是 `AEKey → BigInteger`。AE2 把它當一般網路存儲掛載（`IStorageProvider`，long 視圖，超過 long 的部分顯示為 long 上限）。
  - 引擎透過 `api.IBigStorage` 直接精確存取，繞過 AE2 寫入後會呼叫 `invalidateCache()`。
  - 拆下時內容存在掉落物（`exportSettings` / `importSettings`）。
- **`algorithm.ae2.NetworkStore`** = AE2 存儲 + 網格上已通電的大數存儲（`TwinStarService.addNode` 追蹤）。
  - 放入：數量放得進 long 時先走 AE2（優先級照舊），剩下的，或天文數字的全部，精確放進大數存儲。
  - 取出：先從大數存儲精確取，再找 AE2。
  - 無限來源探測：只有大數存儲的精確數量 < Long.MAX 時才成立。
  - `StockSnapshotter`：把 AE2 總數中大數存儲那一份換成精確值。
  - `HubState.submit` 改為真的取出、不足就放回（大數存儲沒有 long 大小的模擬可問）。
- **約 1e308 的合成上限**：AE2 能源是 double，每種樣板每 tick 的能源只能要到 Double.MAX（約 1.8e308）。
  - 修正：需求超出 double 範圍時，如果網路仍然給出全部 Double.MAX（創造能源元件），就當成無限能源、不設上限。
- **循環種子**：
  - 理論層把循環每個起點（rotation）需要的種子都存成 `Scc.seedOption`。
  - B 的 `runMacro` 挑庫存最能滿足的那組，同一循環重開時沿用。
  - 庫存沒有的種子，用循環外的路線（輸入不含循環成員的樣板）先做出來，並持有為種子（`craftSeed`）。
  - 計畫帶 `loopSeeds`，執行端保護的是計畫選中的種子。
  - 先前兩個 bug：兩步循環（種子 + 沙 → 水晶，水晶 → 2 種子）有種子庫存仍報缺水晶 1；有一般配方可做種子時仍報缺種子 1。
- 理論層日誌多列「樣板圖中的循環」：所有循環，標示哪些是策略環、哪些沒被採用（另有更便宜的路線，或缺外部輸入）。
- 新測試 `LoopScenarioTest`（4 個），全部 43 個通過。
- **後來才加入的自我循環會讓理論層卡死**（遊戲：鍛造模板複製，7 鑽石 + 1 模板 + 1 地獄石 → 2 模板，鑽石和地獄石來自創造元件）。
  - 增量更新沒把「輸入含自己的路線」當成新循環：`raiseLevel` 會略過 x 本身。
  - 輸入免費時，模板的成本每輪都再減半。這在 log 尺度上是每輪 −0.301，會無止盡地遞減，於是背景執行緒一直在 `computePatBom` 裡打轉，所有規劃都在後面排隊。
  - 修正一：最佳路線的輸入含 x 時，直接改為全部重建（重建時會把它辨識成成長循環）。
  - 修正二：增量更新的處理次數上限設為 64 ×（物品 + 樣板 + 16），超過就改為重建，不會再無限打轉。
  - 測試：`SelfLoopVariantsTest`（遊戲裡的網路 + 18 種庫存組合）；`LoopScenarioTest.templateDuplicationWithFreeInputs`（1e40 張模板）。全部 46 個通過。
- **訂單上限統一為 1e10000（含）**（`AmountParser.MAX_EXPONENT` / `MAX`；使用者決定，更大的數字規劃太慢）：
  - 解析器的指數原本最多 4 位（只到 e9999），改為 6 位，再檢查不超過 MAX；超過就回傳 null，欄位顯示為無效。
  - 下單封包改為二進位（`util.BigIntCodec.STREAM_CODEC`，上限是 `MAX_EXTERNAL` = MAX × 1000，因為流體的顯示單位是桶）。伺服器也會拒絕超過上限的數量。
  - 確認畫面與 CPU 狀態的數字也改為二進位，上限 16 KiB：材料可能比訂單大，而規劃最大只到無限容量的代表值。
  - 無限容量的代表值原本是 10^4096（比 1e10000 的訂單還小），改為 2^65536 ≈ 10^19728。
  - 測試用虛擬 CPU 的容量和並行數同步時是字串（AE2 上限 32767 字元），所以那裡另外限制在 30000 位以內。
  - 提示框的完整數字超過 64 位時，改為「30 位有效數字×10^指數」。
  - 測試：1e10000 的五層鏈和成長循環規劃共 0.15 秒；全部 51 個通過。

### 10-23：用整合包合成表測試（`recipeBench`）

- **匯出**（遊戲內，需裝 twinstar）：`/twinstar dump_recipes [檔案]`（OP 2 級），預設寫到 `<伺服器目錄>/twinstar/recipes.json`。
  - 自動化：伺服器啟動參數 `-Dtwinstar.dumpRecipesOnStart=<檔案>`，加上 `-Dtwinstar.stopAfterDump=true` 會在匯出後自動關閉。
  - 只看得到原版配方介面提供的資訊：物品材料與主要產物、容器殘留（水桶 → 桶）。看不到流體、機率產物、機器的額外產物；資料元件（藥水種類等）會被略過。
  - tag 的替代品最多保留 32 種。
- **生成樣板**（遊戲內）：`/twinstar gen_patterns <座標> [篩選]`（OP 2 級）。把伺服器載入的每條配方編成 AE2 樣板，放進該座標的方塊（任何能放物品的方塊，例如測試 CPU 的 5 萬格、箱子）。篩選字串只保留 id 含有它的配方（`minecraft:`、`ae2:`）。
  - 工作台配方 → 合成樣板：依配方形狀排進 3×3，允許替換。
  - 切石機 → 切石機樣板。
  - 其他（熔爐、鍛造、各 mod 機器）→ 處理樣板：產物加上容器殘留（桶）作為輸出。
  - 處理樣板的去向：
    - `/twinstar gen_patterns <座標> no_processing [篩選]`：不生成處理樣板，只回報張數。
    - `/twinstar gen_patterns <座標> processing_to <座標2> [篩選]`：處理樣板放到另一個容器（例如接著機器的樣板供應器），其餘放到第一個。
  - 不會卡住伺服器：工作在主執行緒上分批執行，所有工作合計每 tick 最多 20 毫秒（一個 tick 是 50 毫秒），每 10 秒回報一次進度。
    - 放入方塊只能在主執行緒做，所以沒有另開執行緒。
    - `/twinstar gen_patterns cancel` 取消。
    - 每個 tick 都會重新找目標方塊：方塊被拆掉或所在區塊卸載時，會中止並回報已完成的部分。
    - 開發環境 1924 條配方：每 tick 2 毫秒時約 4 秒；使用者改成 20 毫秒。
  - 開發用自動化：
    - `-Dtwinstar.startupCommands="指令1;指令2"`：啟動時以主控台身分執行。
    - `-Dtwinstar.stopAfterDump=true`：等生成工作結束後才關閉伺服器。
  - 每張樣板都會解碼確認；AE2 不接受的合成或切石機樣板，改做成處理樣板。
  - 純粹 A 變 A 的配方不生成（使用者要求）：每個材料都只能是產物本身（tag 材料的選項也必須全部是它），通常是清除物品資料的配方。鍛造模板複製（還需要鑽石等）、終端升級（還需要另一個終端）需要其他材料，保留。
  - 自動化檢查：`-Dtwinstar.genPatternsCheck=true`。啟動時全部編碼、解碼一次並記錄數量，不放入任何方塊；可搭配 `-Dtwinstar.stopAfterDump=true`。
  - 開發環境結果：合成 1297、切石機 289、處理 119、略過 219 條特殊配方，不到 1 秒。
- **測試**：`gradlew :core:recipeBench -Drecipes=<檔案> [-Dorders=300] [-Dseed=1] [-Dtrace=物品id,…]`。報告會印出來，也寫在 `<檔案>.report.txt`。只有引擎拋出例外時才算失敗。
  - 兩種庫存情境：
    - 創造：沒有配方的物品，以及只能從「無外部輸入的循環」得到的物品，都當成無限來源。
    - 生存：隨機有限庫存。
  - 測量項目：
    - 理論層：全部建置；增量更新（每次 50 個庫存變動，並和重建結果比對）；移除 20 張樣板再加回。
    - 隨機下單：1 / 64 / 1e6 / 1e18 / 1e100 / 1e10000，使用實際的 0.5 秒 / 1 秒時限。
  - `-Dtrace`：列出單一物品的理論資料、所有路線，以及 ×1 的計畫。
- **開發環境的結果**：1705 條配方、1385 種物品。
  - 建置 60–110 ms，增量更新中位數約 30 ms。
  - 下單中位數 < 2 ms，最慢約 85 ms，沒有逾時。
- **找到並已修好**：增量更新的 `raiseLevel` 在最佳路線已經成環（不經過 x）時，會把層級一直往上加，最後記憶體不足（花了 20 秒）。修正：層級超過物品數就改為重建，因為沒有環時不可能超過。
- **路線同成本時挑到繞回去的路線（已修，補回原設計的前提）**：
  - 原設計：B 和 C1 的第一條路線就是理論層的策略。
  - 原因：互轉配方（鐵方塊 ⇄ 9 鐵錠 ⇄ 鐵粒）讓這些物品在完整樣板圖裡成環，於是由策略迭代決定最佳（同分不換）。但路線清單另外按成本排序、同分看編號，「拆鐵方塊」排到第一。結果所有材料免費時，鐵方塊 ×1 報缺鐵方塊；有庫存時則拿 1 個鐵方塊拆了再壓回。
  - 修正：
    - 建置完把最佳路線移到清單第一。
    - `compareRoutes` 同成本時目前的最佳優先（增量更新不會因同分而換路線）。
    - 訂單臨時價格的排序用同樣規則。
  - 增量 == 重建 10,000 輪通過。
- **連帶修正 C1**：路線上限原本只用原料估算，估出 0 就直接跳過那條路線，不試算。但庫存中的中間產物可能夠用（`backEdgeUsesTheOtherDirection` 原本只是靠編號順序剛好通過）。
  - 現在估出 0 時，從 1 往上試：1 不行就是 0；全部可以就全部；否則倍增再二分。不從訂單量往下找，因為 1e10000 會試到逾時。
- 新測試 `ReversibleRecipeTest`（4 個）。recipeBench 的創造情境缺料從 55 降到 11（剩下的是需要種子的循環，例如鍛造模板，和 AE2 一樣需要先有一個）。全部 56 個測試通過。
- **挖礦取得的物品（改設計，使用者同意 10-23）**：
  - 原設計頁規定「沒有任何外部來源的環 → ∞」，而且只用庫存幫沒有配方的物品定價。結果粗鐵 ⇄ 粗鐵方塊這類物品成本 ∞：有粗鐵方塊時下單粗鐵會報缺，庫存的粗鐵也不會被拿去熔煉。
  - **新規則**：重建時先依配方結構（與價格無關）決定哪些物品是「挖礦取得」（`TheoryLayer.computeMined`）：
    1. 從原料開始，能由「已可取得的物品」做出來的都可取得（Horn-SAT 工作佇列，線性時間）。
    2. 剩下的只能靠循環取得。取「沒有其他剩餘物品餵它」的源頭循環：會增長的（1 X → 2 X、種子 + 泥土 → 2 種子）照舊當成長循環，需要種子；不會增長的（粗鐵 ⇄ 粗鐵方塊、小麥 ⇄ 乾草塊）成員就是**挖礦取得**。
    3. 兩者都算可取得，重複直到全部決定。
    - 是否增長用精確判定：在循環的所有配方（輸入都是成員或可取得）中，是否存在一種組合，讓每個成員都不減少、總量增加。這是用分數計算的小型線性可行性問題（`ExactSimplex.feasible`）。
    - 第一版用「每個成員取一條配方、模擬一輪」來判斷，結果把 AE2 的賽特斯石英水晶 ⇄ 石英方塊 ⇄ 切製石英方塊 ⇄ 平滑石英方塊誤判成會增長。連帶使儲存元件、能源元件、成型核心、終端等 111 種物品被判定為做不出來。
  - 挖礦取得的物品像原料一樣，依自己的庫存定價（`best = -1`、`Snapshot.isMined`）；它的配方只當作轉換使用。
  - B：先拿庫存，再用配方轉換（拆粗鐵方塊）。轉換也不夠時，**缺的是物品本身**（缺粗鐵，而不是缺粗鐵方塊）。
  - 增量更新：
    - 庫存變動只更新價格。
    - 樣板增刪後，flush 會重算一次結構（成本很低），挖礦取得的集合有變才全部重建。前兩版用「依成本判斷」或「只在已有挖礦物品時重建」都不對：價格、或兩個循環合併，都會讓結果和重建不同（增量 == 重建測試抓到）。
  - C1 順帶修正：路線上限的估算把無限來源當成「Long.MAX × Long.MAX」，1e100 的訂單會被算成做不到。現在無限來源不設上限。
  - 測試：
    - `MinedItemTest`（7 個）。
    - 增量 == 重建 30,000 輪（45 萬次隨機變動）通過。
    - recipeBench：
      - 開發環境「做不出來」從 327 降到 **0**。43 種是挖礦取得，其餘都可以合成。
      - 創造情境 300 單中，缺料 7 單全部是鍛造模板，需要先有一個，和 AE2 相同。
      - `-DlistImpossible=true` 會列出做不出來的物品及原因；創造情境會列出每一筆缺料。

### 10-23：大型整合包下單逾時

- **現象**：使用者的整合包有約 2 萬張樣板，生成樣板期間連簡單的配方也會「計算逾時」。
- **原因**：
  - 1 秒時限從「下單工作開始」算起，包含下單前的理論層更新。樣板剛變動過時，這次更新是全部重建：2 萬張樣板約 1.05 秒（用開發合成表複製 12 份量測：14,519 物品、20,460 樣板），單是重建就超時。規劃本身每單都在 54 ms 以內。
  - 生成期間樣板每個 tick 都在變，每個 tick 都排一次「套用＋更新」到引擎執行緒，工作會一直堆積。
- **修正**：
  - 時限改成在理論層更新之後才開始計算。
  - 樣板變動改成先放進佇列，同一時間最多只排一次更新，一次套用累積的全部變動；下單前也會先套用佇列中的變動。
- 大型合成表的增量更新與重建比對：成本全部相同。有 33 個物品成本相同、但選了不同路線，這是同分時增量更新不換路線（設計：同分不換）造成的，不是錯誤。recipeBench 現在會把「成本不同」和「同分不同路線」分開列。
- 待改進：2 萬張樣板全部重建約 1 秒（在背景執行緒，不卡伺服器，但下單會等它）。

### 10-23：在正式環境查看引擎狀態

- `/twinstar status`（OP 2 級）：每個有中樞的網路列出：
  - 中樞位置；理論層版本與規模（物品、樣板、策略環數量）。
  - 上次更新是全部重建還是增量更新、花多久、原因、幾秒前。
  - 引擎正在處理或閒置、排隊幾項、還有幾批樣板變動沒套用，最後標示「已更新完畢」或「還沒更新完」。
  - 上次規劃的物品、結果與耗時；執行中的工作數。
  - 狀態從引擎每次更新、規劃後記下的欄位讀取，不直接讀理論層（重建時理論層會被鎖住），所以查詢不會卡住伺服器。
- `/twinstar debug true|false`：在遊戲中開關詳細引擎日誌，寫在 `logs/latest.log`（原本只有開發環境預設開啟）。
- 引擎執行緒改成 `ThreadPoolExecutor`，才能查詢排隊數量。
- 開發用自動化：`-Dtwinstar.startupCommands` 支援 `@n 指令`（n 個 tick 後執行）；`stopAfterDump` 會等排程的指令與樣板生成都結束才關閉伺服器。
- **測試 CPU 修正**：補位時每移動一張樣板就存檔一次，拿掉前面的樣板或拆掉方塊會是 O(n²) 次存檔（1586 張樣板時拆方塊卡住伺服器 24 秒）。現在補位期間不存檔、結束後存一次；清空方塊時不逐格補位。

### 10-23：實際整合包的規劃超時（New Age Science and Technology hard）

- 合成表：10,241 物品、13,632 樣板，最深 30 層，651 種原料、788 種挖礦取得。
- recipeBench 重現：創造情境不會超時；生存情境（隨機有限庫存）300 單有 48 單超過 1 秒，連 `ae2:orange_covered_dense_cable` 也會。遊戲內 `/twinstar status` 顯示引擎閒置、已更新完畢，所以超時是規劃本身造成的。
- 用 JFR 量測（recipeBench 加上 `-Djfr=<檔案>`；用 `jfr print --stack-depth 64` 才看得到完整呼叫堆疊）：下單時間有 92% 花在 C1 的試算（`DemandPass.trial`），其中 56% 來自 10-23 加的「原料估出 0 時從 1 往上試」。
- 修正：
  1. 原料估出 0 的路線，只有在它的直接材料中有中間產物還有庫存時才試算；更深層的庫存看不到，那部分需求改走第一條路線。
  2. C1 遵守軟時限：超過 0.5 秒後不再開始新的試算，正在進行的試算也會中止（`TrialStopped`），並視為這條路線做不到。原設計就規定「軟時限後停止改進」。
- 結果：生存情境 0 單超時，最慢 0.67 秒；約一成大量訂單（×1e6 以上、庫存不足）會用到 0.5 秒才停止改進，它們回報的缺料可能比最佳解多。
- 待改進：
  - 試算裡的 `routes()`／`touches()`（檢查往回走的路線）佔試算時間約一半，可以快取。
  - 理論層全部重建要 2～3 秒、改樣板最慢 10 秒，80% 花在 `seedLoops`：每換一條路線就重新評估整個元件。
- 創造情境的缺料幾乎都是 Mystical Agriculture 的種子／精華循環，需要先有種子，和 AE2 相同。
- **試算失控的根本原因（已修，補回原設計）**：
  - 臨時統計：生存情境 1e18 的三張單，計畫只用到 40～56 張樣板、主流程 100～160 步，但試算跑了 2750 萬～2905 萬步（不設時限要 56～63 秒）。
  - 抓到的失控試算：強制白色羊毛走 `minecraft:dye_white_wool`（白色染料 + 任意羊毛 → 白色羊毛）。「任意羊毛」的第一個選項就是白色羊毛本身，所以每跑一次就多要一個白色羊毛。需求繞回來時又被強制走同一條路線，繞過了原設計的禁止走回頭路檢查，直到撞上 64 ×（物品 + 樣板 + 16）≈ 153 萬步的安全網。整張圖的大小就是從這個上限影響到規劃時間。
  - 修正（`DemandPass.forcedUsed`）：試算的指定路線只用在第一次需求；繞回來的需求照一般規則處理（循環整組求解、禁止清單）。新測試 `TrialTest`。
  - 結果（整合包 bench，生存情境）：大量訂單的 p90 從 500 ms（撞軟時限）降到 30～330 ms，0 單超時。
- **剩下較慢的單**（約 0.85～1 秒，不設時限時；有時限時在 0.5 秒停止改進）：
  - 時間 92% 在 D 的 A* 搜尋（`CycleSolver.aStar`），其中 75% 是 `Amount.toString`。A* 用「把所有數量轉成字串」當作已走過狀態的鍵，數量是 1e100 時轉換非常慢。
  - 已修正（使用者同意）：鍵改為直接比對兩個數量陣列（`CycleSolver.Seen`，用 `Amount` 的 equals／hashCode），搜尋結果不變。
  - 同一批 300 單：`extendedae_plus:c-h716 ×1e100` 從 500 ms 降到 281 ms，×1e100 最慢 501 → 281 ms，×1e10000 的 p90 從 331 降到 228 ms。仍有 2 單 ×1e10000 約 0.5 秒：一萬位數的大數運算本身就很花時間。

### 10-24：和 Data Energistics（數據能源）共存的下單數量

- **現象**：裝了 Data Energistics 3.3.5 後，超大數量的下單會被吞掉。
- **原因**：它也修改了 AE2 的下單數量畫面（反編譯 3.3.5 確認），而且全部只支援 long：
  - `CraftAmountScreen.confirm` 開頭：用自己的 `LongAmountExpressionParser` 讀數量、送自己的封包，**一律中斷這個方法**（讀不出來也一樣）。我們在同一處插入的大數處理就沒有機會執行。
  - `updateBeforeRender` 結尾：「下一步」按鈕依它的 long 解析結果決定是否可按。
  - 建好畫面後：輸入欄限 19 個字元，並啟用它的驗證（long 讀不出來就變紅色，顯示「invalid expression」）。
  - 不超過 long 的數量，它的流程最後會呼叫 AE2 的合成計算，有中樞的網路照樣由我們的引擎規劃，可以正常共存。
- **做法**：它處理不了的（超過 long）才由我們接手，其餘照它的流程（包括它的數量模式按鈕）。用 `@Inject(order = …)` 控制執行先後（Mixin 0.8.7：order 小的先套用，同一插入點先套用的先執行）：
  - `confirm` 開頭 `order = 900`（比它早）：超過 long 才送我們的大數封包並中斷。
  - `updateBeforeRender` 結尾 `order = 1100`（比它晚）：超過 long 才把「下一步」設為可按，其餘不動。
  - `<init>` 結尾 `order = 1100`：放寬輸入欄長度。
  - `NumberEntryWidget.validate` 結尾 `order = 1100`：超過 long 的值恢復正常顏色、清掉它的錯誤提示。
  - 沒裝它時照舊：超過 int 就由我們接手。
- 驗證狀況：開發環境沒有 Data Energistics，只確認了我們的注入可以套用、注入目標存在（`validate()`、`normalTextColor`），還沒在裝有它的整合包裡實測。
- **後續：規劃本身也被數據能源接走**（使用者回報修改後仍然只能用它的邏輯）。
  - 它用 `@WrapMethod` 包住整個 `CraftingService.beginCraftingCalculation`。網路上有它的 Trinity 數據核心時，就用自己的引擎規劃，只在部分情況呼叫原方法；沒有數據核心才交給原方法。
  - 我們原本的掛點在原方法開頭，所以有數據核心時根本收不到訂單。上一節「不超過 long 的單最後也會交給我們」的前提是錯的。
- **決定：中樞優先**（使用者 10-24）：網路上有運作中的雙星中樞時，所有訂單都由雙星引擎規劃；沒有中樞的網路完全不動。
  - 合成服務：改成 `@WrapMethod(order = 1500)`。後套用的包裝在最外層、最先執行，有中樞就直接交給引擎、不往內呼叫。這樣其他來源（介面、發信器）的合成請求也由我們接手。
  - 確認畫面 `planJob`：`order = 1100`，排在其他 mod 的掛點之後，讓數據能源記錄要求數量、計畫版本的流程照常進行。有中樞就直接呼叫引擎，不經過合成服務；沒有中樞且超過 long 就回傳失敗。
  - 超過 long 的訂單也走 `planJob`，以 Integer.MAX 當記號，`twinstar$amount`／`twinstar$item` 記住完整數量。數據能源的「重新規劃」會用 int 數量呼叫 planJob，記號會被換回完整數量，不會被截斷。
  - 驗證狀況：伺服器啟動時兩個注入都有套用；還沒在裝有數據能源的整合包實測。

### 10-24：雙星下單選單（取代 AE2 的數量畫面）

- 使用者決定：網路上有運作中的中樞時，用我們的下單選單**取代** AE2 的數量畫面。玩家選擇不用雙星時，**跳回** AE2 原本的數量畫面（裝了數據能源就是它改過的版本），由原本的流程規劃。介面、發信器等自動請求維持中樞優先。原因：AE2 的數量輸入也被其他 mod 修改、各有自己的上限。
- 入口：`CraftAmountMenuMixin` 在 AE2 的 `CraftAmountMenu.open` 開頭攔截（終端、無線終端、JEI 都經過這裡）。`OrderFlow.replaces` 判斷網路上是否有運作中的中樞，有的話改開 `OrderMenu`。
- `order.OrderMenu`（`twinstar:order`，`MenuTypeBuilder` + `ISubMenuHost`）：
  - 和 AE2 的數量選單相同：一個顯示物品的唯讀格，以及返回主畫面。
  - client action `confirm`（`Request`：數量的十進位文字、只做缺少的量、直接開始）：交給 `OrderFlow.start`，與 AE2 數量選單共用的流程（扣掉現有量，開確認畫面，再呼叫 `planJob`）。
  - client action `original`：記錄這個玩家「下一次不攔截」（`OrderFlow.skipOnce`），再用同一個物品、輸入的數量（int 以內）呼叫 AE2 的 `CraftAmountMenu.open`。
- `client.OrderScreen`：
  - 照抄 AE2 `CraftAmountScreen` 的版面與材質（`assets/ae2/screens/twinstar_order.json`，背景 `guis/craft_amt.png`），標題改成「選擇數量（雙星）」。
  - 數量欄放寬到大數（上限 1e10000）。Shift 按「下一步」直接開始，與 AE2 相同。
  - 左側工具列多一個 `CRAFT_HAMMER` 圖示按鈕「改用原本的下單畫面」。
- `CraftAmountMenuMixin.twinstar$confirmBig` 改用 `OrderFlow.start`，邏輯只有一份。
- 驗證狀況：伺服器啟動時選單有註冊、`open` 的攔截有套用；畫面與實際下單還沒在遊戲中測試。

### 10-24：自己的下單畫面與規劃結果畫面（取代 AE2 確認畫面）

- 使用者回饋上一版「跟想像中不一樣，資訊可以多一點，跟原版規劃分出來」。決定：下單和規劃結果**都自己做**，雙星下單不再經過 AE2 的確認畫面。先做 mockup（`tools/twinstar_order_mock.png`、`tools/twinstar_plan_mock.png`），使用者選了下單 **A 直式**、規劃 **B 樹狀**。
- 下單畫面（`OrderMenu` + `client.OrderScreen`，`screens/twinstar_order.json`，背景 `twinstar:textures/gui/order.png` 178×226）：
  - 物品卡：2 倍大的物品圖示、名稱、網路庫存（精確值，滑鼠移上去看完整數字；有無限來源就顯示「無限來源」）、可合成路線幾條、引擎是否已更新完畢。每 10 tick 由 `TwinStarService.orderInfo` 更新（GuiSync 字串）。
  - 數量：AE2 的數量欄（±按鈕），上限 1e10000；從規劃畫面返回時保留原數量（`initialAmount`，太大的數字用 `1e10000` 這種寫法放回欄位）。
  - 三個選項（AE2 勾選框，記住本次遊戲的選擇）：只做缺少的量（扣掉精確庫存）、能做多少做多少（`CRAFT_LESS`）、規劃完直接開始（沒有缺料才會自動開始）。
  - 按鈕：原版畫面（跳回 AE2 數量畫面一次）、規劃 →（`OrderFlow.startTwinStar` → `PlanMenu.open`）。Shift 不再有特殊作用。
- 規劃畫面（`order.PlanMenu` + `client.PlanScreen`，`twinstar:plan`，`screens/twinstar_plan.json`，背景 `plan.png` 262×200）：
  - 伺服器直接呼叫 `service.beginCalculation`，在 `broadcastChanges` 檢查是否完成；完成後用 `PlanTree.build` 做成 `PlanView`，以 `PlanViewPacket`（`twinstar:plan_view`，伺服器→客戶端）送一次。數量以文字傳送（long 以內是數字，超過就是單位寫法），最多 400 列。
  - 左邊合成樹：每列是物品 ×使用量，下一行是來源（合成 N 次／庫存／無限來源／缺），缺料列整列淡紅；已列過的物品標「共用」、不再展開。可捲動，滑鼠移上去顯示完整數字。
  - 右上摘要：目標 ×數量、樣板幾種、規劃耗時、黑洞容量（用／剩，∞ = 無上限）、並行。右下缺料清單（最多 3–4 項，其餘「還有 N 種」）；沒有缺料時顯示「材料齊全」。
  - 按鈕：返回修改（回下單畫面，數量不變）、重新規劃、開始（中樞）（只有計畫完整時能按；送出失敗把錯誤碼顯示在摘要下方）。右上角返回鍵回終端。
- 材質由 `tools/twinstar_order_gui_texture.py` 產生，只有面板、卡片和捲動軌道；文字、圖示、按鈕由畫面畫。
- 驗證狀況：編譯通過、64 個測試通過、伺服器啟動時選單與封包都有註冊；兩個畫面還沒在客戶端看過（使用者自己測）。

### 10-24：拿掉原版下單路線上的 mixin

- 使用者決定：原版畫面（「原版畫面」按鈕之後的 AE2 數量畫面、確認畫面）完全交還給 AE2 或其他 mod，不再修改。
- 刪除：
  - `CraftAmountScreenMixin`（客戶端，大數輸入、和數據能源排順序）、`CraftAmountMenuMixin.twinstar$confirmBig`、`ConfirmBigCraftPacket`、`BigCraftAmountMenu`。
  - `CraftConfirmMenuMixin`（BigInteger 的 `planJob`、有中樞時直接用雙星引擎）、`BigCraftConfirmMenu`、`OrderFlow.start`。
  - 確認畫面顯示我們計畫的部分：`CraftingPlanSummaryMixin`、`CraftingPlanSummaryEntryMixin`、`CraftConfirmScreenMixin`、`CraftConfirmTableRendererMixin`、`PlanSummaries`、`BigSummary`、`BigSummaryEntry`、`BigEntryAmounts`，以及 `gui.twinstar.infinite_source`。
  - `NumberEntryWidgetMixin` 中為了數據能源把超過 long 的值改回正常顏色的 `validate` 注入。
- 保留：`CraftAmountMenuMixin.open` 的攔截（我們的下單畫面入口，「原版畫面」跳過一次）。`NumberEntryWidgetMixin` 的單位／科學記號解析和飽和改成**只對我們的數量欄**（`allowBigInput` 設的旗標），AE2 其他數字欄不受影響。執行端（CPU 清單、`submitJob`、CPU 狀態畫面）不變：原版規劃的計畫選到中樞仍會被拒絕並提示。
- 中樞優先（`CraftingServiceMixin.twinstar$hubFirst`）改成**只管不是玩家發出的請求**（介面、發信器等，`ICraftingSimulationRequester.getActionSource().player()` 為空）。玩家的訂單由雙星下單畫面直接交給引擎；選了原版畫面的玩家，由 AE2 或數據能源規劃。
- 驗證：編譯、64 個測試、伺服器啟動時 mixin 套用都沒問題；遊戲內還沒測。

### 10-24：雙星畫面改成黑底、左上角旋轉雙星

- 使用者不要電路框／標題裝飾／軌道角飾三個方案（`tools/twinstar_deco_{A,B,C}.png`），要**整個背景黑色**，左上角有黑白雙星旋轉，並帶一點用像素模擬的扭曲（不是真的扭曲）。mockup `tools/twinstar_dark_mock.gif` 確認後做進遊戲。
- 雙星：`tools/twinstar_gui_star.py` 產生 `textures/gui/twin_star.png`（32×32，48 格，直條）。黑洞（黑色核心＋迎面較亮的光子環）和白洞（白色核心＋光暈）在同一條傾斜軌道上，靠近觀看者的那顆比較大、畫在前面。背景是淡淡的點陣和星星：每個像素取「被黑洞拉近、被白洞推開」之後位置上的點，所以點陣和星星會在雙星旁邊彎曲、滑動。邊緣淡出，沒有方框。軌道是很淡的虛線。
- `client.TwinStarBadge` 每 80 ms 換一格，兩個畫面都在 `drawBG` 之後畫在 (3, 1)。
- 黑底材質（`tools/twinstar_order_gui_texture.py`）：面板 #08080C、外框 #3A3454；凹槽 #12121B；缺料清單 #241016 暗紅。標題列加高 14 px（下單 178×240、規劃 262×214），所有元件往下移 14 px（畫面程式裡的 `HEADER`）。
- 文字：版面 JSON 的 `palette` 把 AE2 預設文字改成淺色（#DEDEEA，暗色 #8C8CA2），勾選框的字也跟著變；標題用淡紫色粗體（文字元件自己的 color）。畫面自己畫的字改成淺色系：合成藍 #96ACFF、綠 #78E296、紅 #FF7474、更新中橘 #F0B060。AE2 的按鈕、捲動條、輸入框維持原樣。
- 驗證：編譯通過；還沒在客戶端看過。
- 10-24 追加：使用者在遊戲裡看到 AE2 的淺灰按鈕／勾選框／輸入框和黑底「風格不太匹配」。改成我們自己的暗色元件（`client/widget`）：
  - `DarkButton`（暗底紫框，滑鼠移上去框變亮紫）、`DarkBackButton`（畫出來的 ↩，回終端，取代 AE2 的分頁形返回鍵，放在面板內右上角 16×16）、`DarkToggle`（22×12 開關，開啟時紫色、圓點在右）、`DarkAmountEntry`（取代 AE2 的 `NumberEntryWidget`：同樣的 ±1/10/100/1000 排列，Shift／Ctrl 變 1/16/32/64，輸入框暗底紫框，Enter 確認，聚焦時吃掉所有按鍵，避免按 E 關掉畫面）。
  - 捲動條用自己的暗色把手 sprite（`textures/gui/sprites/scroller*.png`，`DarkStyle.SCROLLBAR`）。
  - AE2 左側工具列的「?」說明鍵在兩個畫面都隱藏（`DarkStyle.hideGuideButton`）。
  - 因為數量欄不再是 AE2 的元件，`NumberEntryWidgetMixin` 和 `BigNumberEntry` 刪除：雙星 lib 已經沒有任何改 AE2 數字欄的 mixin。
- 10-24 追加：花紋與外框。選項 mockup：`tools/twinstar_dark_deco_{A,B,C,AC,CA}.png`。最後定案（使用者先說反了又更正）：**下單畫面用引力格線**（每 8 px 一條的暗格線，越靠近左上角雙星越被拉彎），**規劃畫面用點狀軌道環紋**（以雙星為中心的橢圓虛點環，越外越淡）。兩個畫面都用 A 的外框：離邊 2 px 的細線，四角有雙層紫色括弧；大於 20 px 的凹槽四角加小括弧。全部畫在材質裡（`tools/twinstar_order_gui_texture.py`），程式沒有改。
- 10-24 追加：數量按鈕照 AE2 的規則：值剛好是 1 時按大於 1 的步進，直接變成步進值（1 按 +1000 得 1000，不是 1001）；最少 1、最多 1e10000（到上限時欄位顯示 `1e10000`）。

### 10-28：規劃結果畫面改用 LDLib2（試用）

- 使用者想把 GUI 改成 LDLib2，先拿規劃結果畫面試。依賴：Modrinth 專案 `ldlib`（LDLib2 2.2.41，版本 id `RIgL4A7W`，`gradle.properties` 的 `ldlib_version`）。核心 lib 用 `compileOnly`，開發客戶端用 `localRuntime`；`neoforge.mods.toml` 宣告 `ldlib2` 為**只在客戶端**需要（伺服器不用裝）。LDLib2 的 jar 把排版引擎 taffy、yoga 包在裡面（jar-in-jar，不在公開 maven），`core/build.gradle` 的 `extractLdlibNested` 從 jar 取出來只給編譯用。
- 接法：LDLib2 用 mixin 讓所有 container menu 都能放一個 ModularUI（`IModularUIHolderMenu`），畫面初始化時自動接上。所以 `PlanMenu` 完全沒改；`client.PlanScreen` 變成普通的 `AbstractContainerScreen<PlanMenu>`，建構時把 `client.ui.PlanUI` 放到 menu 上，每 tick 呼叫 `refresh()`。用一般的 `RegisterMenuScreensEvent` 註冊，`screens/twinstar_plan.json` 刪除。
- `PlanUI`：版面用 flex（標題列：旋轉雙星＋標題＋返回；中間：左邊合成樹、右邊摘要和缺料；下面：返回修改／重新規劃／開始）。合成樹和缺料清單都是 LDLib2 的 `ScrollerView`（滾輪捲動，缺料清單不再限 3–4 項）；過長的名稱滑鼠移上去會橫向捲動；每列、摘要、缺料項目都有提示框。自訂元件：`KeyIcon`（AE2 物品／流體圖示）、`TwinStarElement`、`TreeRow`（縮排、連接線、缺料列暗紅）。
- 外觀在 `assets/twinstar/lss/plan.lss`（LDLib2 的樣式表）：背景 `plan_ui.png`（面板＋軌道環紋＋外框，不含格子），格子用九宮格 sprite `inset.png`／`inset_missing.png`（角落括弧在任何大小都在角上），按鈕暗底紫框，字用 Minecraft 字型。
- 驗證：編譯通過；伺服器啟動時 LDLib2 2.2.41 正常載入；客戶端還沒看過。

### 10-28：下單畫面也改用 LDLib2；規劃畫面的保留標記

- 使用者：下單畫面也改用 LDLib2；容量不夠的處理在下單畫面已有選項（能做多少做多少），所以不做「改成 M 個」按鈕；做規劃畫面的物品標記。
- 標記（`order.ItemMark`：自動／保留／隨便用）：引擎早就支援（`Overlay.markReserved / markFree`，`DemandPass` 依標記排路線），這次接上畫面。合成樹每列（目標以外）右邊有一個小標籤，點一下換下一個並重新規劃。標籤是像素圖示（使用者：不要用中文字當代號）：自動 = 灰色空心圓、保留 = 橘色鎖頭、隨便用 = 綠色勾，意思寫在提示框；只對這次下單有效（離開規劃畫面就沒了）。
  - 傳送：客戶端送「第幾列、哪個標記」（`PlanMenu.MarkRequest`），伺服器用它剛送出的那份 `PlanView` 找出物品，記在這張單上，再呼叫 `TwinStarService.beginCalculation(…, marks)`。標記疊在「缺機器的樣板不能用」之上；缺機器時的備用計畫也帶著標記。
- 下單畫面（`client/ui/OrderUI` + 普通的 `OrderScreen`）：內容和之前一樣（物品卡、數量 ± 按鈕與 Shift／Ctrl 的 1/16/32/64、三個選項、原版畫面／規劃 →），改成 flex 版面和樣式表；數量欄是 LDLib2 的文字欄（像素字型版 `PixelTextField`），開關是自己畫的 `PixelToggle`（LDLib2 的開關字是細字型）。物品格那個同步用的 slot 在客戶端設為不顯示（`OrderMenu.hideItemSlot`）。
- 共用樣式表改名 `lss/twinstar.lss`。背景只剩 `order_ui.png`（面板＋引力格線＋外框）和 `plan_ui.png`。刪除：`screens/twinstar_order.json`、舊的 `order.png`／`plan.png`／捲動條 sprite、`client/widget`（給 AE2 畫面用的暗色元件）。
- 驗證：編譯、98 個測試、伺服器啟動都沒問題；兩個畫面還沒在客戶端看過。

### 10-28：規劃畫面的合成圖（可拖曳、縮放，循環預設收起）

- 使用者：加一個按鈕顯示合成樹，含循環；不要縮排清單（第一版做成「網路上所有配方往下列」被否決），要可以拖動、縮放的圖，循環預設不展開、點了才看裡面；畫這次規劃實際用的路線。
- 規劃畫面標題列（返回鍵左邊）的樹形圖示 → 全螢幕的 `PlanGraphScreen`（普通 `Screen`，選單在伺服器上照樣開著）。返回或 Esc 會為同一個選單**重新建一個** `PlanScreen`：LDLib2 在畫面離開時會把那個畫面的 UI 和樣式丟掉（`ModularUI.onRemoved` → `StyleEngine.dispose`），舊的不能再顯示。
- 資料（伺服器 `order.PlanGraphBuilder` → `PlanGraph`，跟著 `PlanView` 一起送）：每個物品一個（第 0 個是目標）、每個規劃用到的樣板一個（做幾次、在哪台中樞機器、機器缺不缺）、每個配方的材料／產出總量（催化劑、容器算一份；工具按耐久算幾把），以及循環：在「產物 → 材料」的物品圖上找強連通元件（Tarjan，`PlanGraphBuilder.loops`），兩個以上物品、或自己做自己（水桶）的才算；種子取自 `Plan.loopSeeds` 和 `Plan.recycles`。物品、配方各最多 400 個。
- 版面（客戶端 `client/ui/PlanGraphLayout`）：收起的循環是一個方塊；目標在最上層，材料在用它的東西下面一層（最長路徑分層；只是多出來的副產物掛在做它的配方上面一層），每層用重心法排序減少交叉，再把方塊拉到相連方塊的下方。展開的循環是一個紫框，裡面的物品和配方照循環的順序排成一圈，框在分層裡占一格。
- 畫面（`client/ui/PlanGraphView`）：
  - 例：A 用 B 做、B 用 A 做、B 還要煤炭 → 目標 A 下面是紫色「⟳ A B」方塊，煤炭在它下面；點一下變成紫框，A → 配方 → B → 配方 → A 繞一圈，箭頭指向產物，煤炭的線連到框裡用它的配方；點框的標題收回去。
  - 物品方塊：圖示＋用量，框色：缺 = 紅、要做 = 藍紫、庫存／無限 = 綠；目標多一圈亮框。配方方塊：機器小圖示（有的話）＋「×次數」，缺機器紅框。線是材料到配方、配方到產物的 S 形曲線，滑鼠移到方塊上它的線會變亮；放大到 0.75 倍以上時線上標材料數量。
  - 操作：左鍵拖曳移動、滾輪以滑鼠為中心縮放（0.2–3 倍）、上方「置中」顯示整張圖；提示框和規劃樹相同（用量、做出、庫存、缺、多出；配方列出材料和產出；循環列出物品和一開始需要的種子）。
  - 重新規劃或改標記後新的結果到了，圖會重新排、回到全部收起。
- 驗證：編譯、102 個測試（新增 `PlanGraphLoopsTest`）通過；使用者在遊戲裡看過，覺得不錯。

### 10-28：合成圖改成色盲也看得出來

- 使用者有點紅綠色盲：不放大分不出「無限」（綠）和「缺料」（紅）。
- 狀態不再只靠紅綠：缺料 = 粗的橘色雙框＋框角「!」小標籤；無限來源 = 「∞」小標籤；顏色改成橘（有問題）／藍（從網路拿）／紫（要合成）。缺機器的配方也是橘色雙框＋「!」。
- 只改合成圖；規劃畫面和下單畫面字很大、有文字說明，使用者說不用改。

### 10-28：理論層檢視介面

- 使用者：開一個介面讓玩家實時看到引擎的樣板管理（理論層），方便除錯，也讓玩家預知會怎麼規劃；從雙星中樞控制器畫面和下單畫面打開。
- 打開：下單畫面標題列的放大鏡（直接看那個物品）；測試中樞（`DummyCpuScreen`，目前真正跑引擎的中樞）左側工具列的放大鏡（自己搜尋）。六芒星中樞的控制器還沒有畫面、也還不是引擎的主機，之後有了再加。返回：下單畫面重新建一個（LDLib2 的畫面離開就丟 UI），中樞畫面回到原本那個。
- 傳輸（`theory/TheoryPackets`）：檢視器是客戶端畫面，底下的 AE2 選單照樣開著；伺服器用那個選單主機的網路回答（`AEBaseMenu.getTarget()` → 網格 → `TwinStarService`）。三種請求：狀態（每秒問一次）、可做的物品清單（理論層版本變了才重拿，最多 20000 個，搜尋在客戶端做）、某個物品（`TheoryQueries.item`）。版本一變，畫面上的物品全部重新要一次 → 實時跟著引擎。全部讀引擎最後發佈的 `Snapshot`，不會等引擎。
- 物品的資料（`TheoryItem`）：層級、成本（log10，原料單位）、原料／礦物類（只靠庫存或互相轉換）／無限來源／不能做、能做它的配方依引擎順序（最多 24 個；每個帶「做一個的成本」、機器、每次的材料和產出）、做 1 個要的原料（估計，最多 16 種）、引擎的策略循環（成員、種子）。
- 畫面（`client/TheoryScreen` + `client/ui/TheoryGraphView`，和合成圖共用 `GraphView`／`GraphLayout`：拖曳、縮放、循環收合、畫法）：
  - 例：搜尋「處理器」→ 它在最上面，下面最多 6 個配方：引擎首選是亮色雙框、寫「×1」，其他寫「×1.8」（成本是首選的幾倍），做不出來的寫「—」；每個配方下面是材料。材料方塊右上角有「+」小標籤，點一下展開它的配方（「−」收起）；點到哪展開到哪，被點的方塊留在畫面上原來的位置。
  - 物品框：要合成的紫、原料／礦物類藍；不能做的有橘色「!」，無限來源有「∞」，還在讀取的有「…」。
  - 右邊資訊欄：點過的物品（預設是目標）的層級、成本、種類、全部配方的排序（首選有星星圖示）、做 1 個要的原料、循環成員和種子；滾輪捲動。
  - 上方狀態列：「已同步・物品 N・樣板 M・循環 L」（藍）或「更新中：還有 N 批樣板變動、排隊 M 項」（橘）；左下角一直寫著「這是預測：實際下單還會看庫存、標記、缺不缺機器」。
  - 左上角搜尋框：打字就會搜尋（名稱或 ID），下拉清單點一下換成那個物品。
- 驗證：兩個模組都能編譯、102 個測試通過；客戶端還沒看過（特別是從中樞畫面返回）。

### 10-28：理論層改成「一打開就全部畫出來」

- 使用者：原本的想法是直接顯示全部（像星圖那樣，沒關聯的也放在旁邊，不是真的照星圖畫）；搜尋時只放那一份；全部配方都畫，推薦的線畫粗一點。先前「搜尋一個物品再一層層點開」被取代。
- 傳輸：理論層版本一變，伺服器把整個理論層送過來（`theory/TheoryMap`：每個物品的層級、成本、原料／礦物類／無限／不能做、引擎首選的樣板、策略循環；每個樣板的機器、每次的材料、產出和經由它做一個的成本），切成多個封包（每包最多 1500 個物品、800 個樣板），客戶端 `TheoryCache` 收齊才換上；收的時候顯示「載入理論層……（3/12）」。單一物品的詳細資料（配方排序、原料清單、循環種子）還是另外要，給右邊資訊欄用。
- 版面（`GraphLayout`）：沒有單一目標時，每個沒有產物的方塊都是頂端；彼此沒有線相連的部分分開排好，再由大到小一排排並排（每排大約和整張圖一樣寬高比）。鄰接表預先算好，6000 個物品、40 塊的測試圖 0.5 秒排完（`GraphLayoutTest`）。
- 畫法（`GraphView`）：引擎推薦的路線（某個物品首選的樣板：它的材料線和做出那個物品的線）畫粗、畫亮，配方方塊亮色雙框、寫「×1」；其他配方寫成本是首選的幾倍（「×1.8」），做不出來寫「—」。只畫畫面上看得到的東西；拉遠（< 0.45 倍）時方塊變成色塊、線變直線；非常遠（< 0.12 倍）只畫推薦的粗線和滑鼠所在方塊的線（大型整合包有十萬條線）。
- 例：打開 → 整個網路的配方都在畫面上，鐵相關的一大塊、魔法模組另一塊並排；搜尋「處理器」→ 只剩處理器和做它要的一切（所有配方、一路到原料），左上角出現「全部」可以回去；在全圖點一個物品 → 右邊顯示詳細，按「只看它的那一份」也一樣只剩它那份。
- 驗證：編譯、106 個測試通過；客戶端還沒看過。

### 10-28：理論層：小標籤蓋到標題列、拉遠看不出是什麼

- 使用者回報兩件事：框角的小標籤畫到上方標題列上；縮小後完全看不出是什麼。
- 圖只畫在標題列下方、右邊資訊欄左方（剪裁），小標籤和物品圖示不會再蓋到標題列和資訊欄。
- 拉遠（< 0.7 倍）時像地圖的標籤：最有代表性的物品保留原尺寸的圖示（各塊最上面的產物優先，再來是連線最多的），互不重疊、最多 500 個；各塊最上面的產物旁邊還寫名稱。其餘方塊是色塊。滑鼠移到圖示上看提示框，點圖示等於點那個方塊（選物品／展開循環）。
- 驗證：編譯、106 個測試通過；客戶端還沒看過。
- 之後（使用者回報）：圖上出現沒有任何線的孤立方塊，例如閃電模擬室（機器）。原因是引擎的物品索引也收了只在庫存裡的物品和樣板要用的機器。理論層地圖現在只送「有樣板拿它當材料或產物」的物品；機器只以小圖示出現在配方方塊上。
- 提示框：配方算不出成本時，材料清單裡標出「← 算不出成本」的那個材料；用語改成「理論層算不出成本（實際下單仍可能直接用庫存）」——理論層只看配方不看庫存，所以材料在庫存裡時，實際下單仍做得出來。

### 10-28：規劃標記多一個「保留」（可填數量）

- 使用者：控制物品數量的標記再加一個——完整保留對應物品，保留後做不完再說缺料，而且這個模式可以調數量。
- 標記變成四個，點標籤依序換：自動 → 盡量不用（原本叫「保留」的那個，圖示改成向下箭頭）→ 隨便用 → 保留（鎖頭，藍色）。
- 保留（`ItemMark.KEEP`）：規劃前把網路庫存扣掉（`StockSnapshot.withKept`），只有這張單看得到扣過的庫存；理論層的價格照舊用真正的庫存。預設「全部」= 這個物品當作庫存 0（無限來源也不用）；填了數量就只扣那麼多（無限來源扣了還是無限）。剩下還要的就合成，做不出來就算缺料（能做多少做多少的單就少做）。
  - 例：做 10 個鐵板，保留全部鐵錠 → 改用礦石煉 10 個鐵錠；鐵錠保留全部、礦石保留 95 個（共 100 個）→ 只有 5 個礦石能用，缺 5 個礦石（`KeepStockTest`）。
- 畫面：保留的那一列，第二行換成「保留 [全部]」小輸入框：打數量（流體以桶計，可用 K／M 等單位）按 Enter 就重新規劃；打「全部」或清空 = 全部保留。
- 傳送：`PlanMenu.MarkRequest` 多帶保留數量（基本單位的十進位文字，空的 = 全部）；`TwinStarService.beginCalculation(…, marks, keep)`。
- 驗證：編譯、109 個測試通過；客戶端還沒看過。

### 10-28：賽特斯石英粉：網路上沒有粉時規劃成缺料

- 使用者回報（New Age Science and Technology hard，`/twinstar dump_network` 匯出的網路）：粉 0 個、高純水晶 1000 個、種子 100 個時，下單 1 個粉也變成「缺粉 ×7」。循環是：粉 8 + 沙子 32 → 種子 32；種子 1 → 高純水晶 1；高純水晶 1 → 粉 1（晶體粉碎機）。
- 原因一：三個物品是同一個策略環，規劃器一律「整圈跑」，從不直接拿庫存的上游成員走半條路。修法（`DemandPass.fromUpstream`）：環裡某個成員的需求，先試著沿著環往回走（粉 ← 高純水晶 ← 種子），用庫存裡的上游成員補上，全部補得到才這樣做；補不到再整圈跑。例：要 1 個粉 → 拿 1 個高純水晶粉碎一次。
- 原因二：循環的啟動量（種子）照「每個樣板跑一次」算，所以從高純水晶開始只算要 1 個，可是種子樣板一次要 8 個粉 → 實際轉不起來，缺 7。修法（`PolicyCycles.roundRuns`）：從這一圈的最後一個樣板往回推，每個樣板跑到夠下一個用為止（粉碎 8 次才夠做一次種子）→ 從高純水晶開始要 8 個。
- 驗證：`CertusDustLoopTest`（猜的配方 72 種組合 + 實際網路的兩個情境）；離線用實際網路隨機下 30 張單全部完成（之前 5 張全缺料）；112 個測試通過。
- 之後：規劃畫面的合成圖按了沒反應（例外被 LDLib2 的點擊處理吞掉）。原因：抽出共用的 `GraphView` 後，`GraphLayout` 建構時就向 view 問方塊的文字，`PlanGraphView` 卻從還沒指定的 `layout` 讀圖 → NullPointerException；每張計畫都會發生。修法：`PlanGraphView` 自己存一份圖。另外，合成圖打不開時改成在聊天欄說明原因並寫進紀錄檔；規劃還沒好時按下也會提示。

