# River Room 德州扑克 · AI Agent 开发手册

> 本文档面向接手此项目的 AI Agent / 工程师，给出后续开发所需的全部工程上下文。
> 阅读完本文件应能直接产出符合既有风格的改动。原有的 build/test/commit 规范保留在最末。

## 1. 项目一句话

实时 2–6 人无限注德州扑克（NLH）Web 应用。Spring Boot 3 后端 + Vue 3 前端，STOMP WebSocket 推送状态版本，Docker Compose 一键部署到 `http://<host>:8088`。状态全部在 JVM 内存，重启即清空；账号与金额配置落盘为文件。

## 2. 代码地图

```
backend/                                 Spring Boot 3.5.3，JDK 17，Maven
  src/main/java/com/example/poker/
    PokerApplication.java                启动入口（@SpringBootApplication）
    config/
      WebSocketConfig.java               STOMP 端点 /ws，broker /topic，app prefix /app
      WebConfig.java                     /api/** CORS（poker.allowed-origins 注入）
    controller/
      TableController.java               /api/tables/** 公开牌桌接口
      AccountController.java             /api/accounts/** 账号与跨设备登录
      AdminController.java               /api/admin/** 管理员后台（X-Admin-Token）
      SettingsController.java            /api/settings 公开金额规则（GET）
      ApiExceptionHandler.java           业务/校验异常 → 400 + {message, timestamp}
    domain/                              纯领域，无 Spring 依赖
      Card.java                          record(Rank, Suit)，toString → "Ts"
      Rank.java / Suit.java              枚举，含 symbol/value
      Deck.java                          单局一副牌，洗牌后 cursor 单调发牌
      HandValue.java + HandCategory.java 牌型等级 + 踢脚牌比较
      HandEvaluator.java                 C(7,5)=21 路枚举 5 张最优组合
      GamePhase.java                     WAITING/PRE_FLOP/FLOP/TURN/RIVER/SHOWDOWN
      ActionType.java                    FOLD/CHECK/CALL/RAISE/ALL_IN
      PlayerStatus.java                  SITTING/ACTIVE/ALL_IN/FOLDED/OUT
      PlayerState.java                   玩家身份 + 可变筹码/状态；pay() 自动转 ALL_IN、requestLeave()
      PokerTable.java                    589 行状态机（★ 核心）；access() 是唯一事务入口
      TableAccessException.java          带 code 的牌桌访问错误（TABLE_CLOSED/SEAT_LEFT/INVALID_SESSION）
    service/
      PokerSettings.java                 金额规则全局配置 + 文件持久化（Properties）
      TableService.java                  编排层（★ 核心）：withTable() 事务、finishUpdate() 收尾、超时调度
      PokerAiStrategy.java               AI 出牌策略（蒙特卡洛 + 加注/弃牌阈值）
      PokerAdvisor.java                  给真人玩家的策略建议（蒙特卡洛 + 底池赔率）
      AccountService.java                无密码账号 + 跨设备登录码（SHA-256 存盘）
    dto/
      Requests.java                      入参 record + Bean Validation 约束
      TableViews.java                    出参 record（TableSummary/TableView/PlayerView/LeaveView/...）
      AccountViews.java                  账号出参
  src/main/resources/application.yml     Spring 配置（端口、CORS 列表、文件路径）
  src/test/java/...                      13 个测试类（领域 + 服务）
  Dockerfile                             多阶段构建，最终 alpine JRE + non-root

frontend/                                Vue 3.5 + Vite 7
  src/
    main.js                              createApp + 挂载 #app
    App.vue                              639 行：大厅 + 账号 + 管理后台三合一
    components/
      PokerRoom.vue                      515 行：牌桌、行动条、计时圆环、筹码飞行、AI/语音
      PlayingCard.vue                    扑克牌单元（隐藏态/红黑/动作/高亮）
    services/                            纯逻辑，无 Vue 依赖（★ 必须保证可单测）
      api.js                             fetch 封装：15s 超时、错误带 status + code
      socket.js                          @stomp/stompjs 封装 watchTable(id, onChange, onStatus, onEvent, onClosed)
      tableSync.js                       shouldApplyTable 版本守卫 / tableExitMessage / createTableRefresh 单飞刷新
      rules.js                           callAmount/canStart/minimumRaiseTo/canAllIn/canAutoStartNextHand
      chips.js                           topUpBounds/suggestedTopUp
      tableView.js                       seatsFromViewer（以自己座位为 12 点钟方向）
      roomLayout.js                      牌桌自适应尺寸计算
      tableEffects.js                    boardMotion/turnClock/collectBetFlights/winningCardState
      cards.js                           displayRank（T → 10）
      audio.js                           VOICE_EMOTES + WebAudio 背景音乐
      account.js / session.js            localStorage 读写
    *.test.js                            Vitest 单测（12 个文件），与 services 同名
  vite.config.js                         代理 /api /actuator /ws 到后端（★ 端口必须与 SERVER_PORT 一致）
  nginx.conf                             容器内静态服务 + 反代

compose.yml                              后端 healthcheck 通过后启动前端，端口 8088
Jenkinsfile                              jdk17 / maven3 / node22，mvn verify → npm ci → vitest → vite build → 归档产物
```

## 3. 关键设计原则（修改前必读）

### 3.1 WebSocket 只推版本号，不推状态
`TableService.publish()` 广播 `TableEvent{tableId, version}`。客户端 `socket.js` 收到非 EMOTE 事件后调用 `onChange()`，由组件自己拉一次 REST 全量。
**理由**：避免把底牌推送到公共频道泄露给其他玩家。
**约束**：新增任何状态变更，必须在 `TableService` 业务方法末尾（**在 publish 之前**）调用 `synchronizeAccounts`/`runAiTurns` 等收尾动作。**禁止**把底牌字段加入广播事件。

### 3.2 牌桌状态机的边界
- 牌桌修改使用 `PokerTable` 的 `synchronized` 方法。`PokerTable.access()` 将一次指令、AI 推进、账号结算、离桌清理和快照合并在同一个牌桌锁内；定时超时检查使用同一入口，**不允许**在 Controller / Service 重新加锁。
- 阶段变更顺序：`WAITING → PRE_FLOP → FLOP → TURN → RIVER → SHOWDOWN → WAITING/SHOWDOWN`。
- `SHOWDOWN` 期间仍允许补码/回收/重连/下一局；不允许 `join`/`act`。
- 动作合法性入口：`PokerTable.act()` 一处统一校验，包括 `acted`/`streetBet`/`raiseAllowed`。
- 新增动作类型（ActionType 枚举）必须同步更新：前端 `rules.js` 中 `validRaise/canAllIn/quickRaiseTo`、UI `PokerRoom.vue` 行动条、`PokerAiStrategy.decide`、`PokerAdvisor.advise`。

### 3.3 金额模型
- `PlayerState` 维护三组数：`chips`（桌上）/ `reserveChips`（账号备用）/ `totalChips`（两者之和）。
- `pay()` 扣桌上、累 streetBet 与 handBet，扣到 0 自动转 ALL_IN。
- `win()` 直接加桌上（不算账号；`synchronizeAccounts` 在每手结束后把 `totalChips` 写回账号）。
- `topUp/cashOut` 只在两局之间（`ensureBetweenHands`）可调，且桌上的筹码不能跌破 `minBuyIn`。
- 边池：按 `handBet` 分层取 unique level，逐层 `pot = (level - previous) × contributors`，每个 level 取贡献者中最大牌型者均分（余数按 `seat` 偏移分配）。

### 3.4 认证三件套
- **牌桌内**：`playerId + reconnectToken`（REST + STOMP 都用，挂在 URL 参数或 body）。
- **账号**：`accountId + accountToken`（请求头 `X-Account-Token`），用于跨牌桌的现金/战绩；落盘时用 `token()` 校验，`loginCode` 用 SHA-256 散列存盘，明文只在生成/重置时返回一次。
- **管理员**：`X-Admin-Token` 请求头；后端用 `MessageDigest.isEqual` 防时序攻击；口令来自 `poker.admin-token`（默认 `88914752`，可用 `POKER_ADMIN_TOKEN` 覆盖）。

### 3.5 实时推送协议
- 客户端订阅 `/topic/tables/{tableId}`。
- 服务端只会发两类消息：
  1. `TableEvent{tableId, version, type="TABLE_UPDATED"}` → 客户端拉全量
  2. `TableEvent{..., type="EMOTE", playerId, nickname, emoteId, text}` → 客户端弹表情气泡 + 朗读
- 表情冷却 1.2s/玩家（`EMOTE_COOLDOWN_NANOS`）。
- 牌桌被管理员删除时广播 `version = -1`，前端应停止轮询并退出房间。

### 3.6 一回合内的状态机时序

下面是一手牌的完整生命周期，含真人 + AI 混合场景。

```
   [客户端]                   [TableService]                 [PokerTable]                  [STOMP /topic]
       │                            │                              │                              │
       │ POST /tables/{id}/start    │                              │                              │
       ├───────────────────────────▶│ table.start(playerId)        │                              │
       │                            ├─────────────────────────────▶│ handNumber++                 │
       │                            │                              │ phase = PRE_FLOP             │
       │                            │                              │ 发底牌 → 收盲注             │
       │                            │                              │ setCurrentTurnSeat(...)      │
       │                            │ runAiTurns()                 │                              │
       │                            │   (轮询 currentPlayer, AI?  │                              │
       │                            │    → aiStrategy.decide(...)  │                              │
       │                            │    → table.act(...))         │                              │
       │                            │ synchronizeAccounts()        │                              │
       │                            │ publish(tableId)             │                              │
       │                            ├──────────────────────────────┴─────────────────────────────▶│ TABLE_UPDATED v=1
       │ ◀───────── TableView ─────────┤                                                       │
       │                            │                              │                              │
       │ POST /tables/{id}/actions  │                              │                              │
       │ {type:"RAISE", raiseTo:60}  │                              │                              │
       ├───────────────────────────▶│ table.act(playerId, ...)     │                              │
       │                            ├─────────────────────────────▶│ 校验合法性                   │
       │                            │                              │ 若全部跟注 → advanceStreet() │
       │                            │                              │ 若只剩 1 人 → awardUncontested│
       │                            │ runAiTurns()                 │                              │
       │                            │ publish(tableId)             │                              │
       │ ◀──── TableView ──────────┤ ├──────────────────────────────┴─────────────────────────────▶│ TABLE_UPDATED v=2
       │                            │                              │                              │
       │     ... (多轮 ...          │                              │                              │
       │  advanceStreet 推进到 RIVER)                              │                              │
       │                            │ showdown() / awardPot()      │                              │
       │                            │ phase = SHOWDOWN             │                              │
       │                            │ finishHand()                 │                              │
       │                            │ synchronizeAccounts()        │                              │
       │                            │ publish(tableId)             │                              │
       │ ◀──── TableView ──────────┤ ├──────────────────────────────┴─────────────────────────────▶│ TABLE_UPDATED v=N
       │                            │                              │                              │
       │ POST /tables/{id}/start    │  (下一局开始，handNumber++)   │                              │
```

关键点：

- **AI 回合循环**（`runAiTurns`）上限 500 次；正常一手牌 AI 行动 < 20 次。触发 500 上限说明状态机卡死，需要排查。
- **每个服务指令完成后广播**：`runAiTurns()` 内的多次 AI 动作完成后统一发布版本号；不会逐个广播 AI 动作。
- **`synchronizeAccounts` 在指令收尾时执行**：余额变化时更新账号；只有 SHOWDOWN 才为参加了本手的玩家记录战绩。预约离桌的玩家先结算，之后释放座位，最后广播；战绩按牌桌和手数去重。

### 3.7 错误响应统一格式

`ApiExceptionHandler` 把三类异常都转成 HTTP 400：

| 异常 | 来源 | 响应 |
|---|---|---|
| `TableAccessException` | `authenticate()` / `requirePlayer()` / `withTable()` | `{ message, timestamp, code }` |
| `IllegalArgumentException` / `IllegalStateException` | 领域校验（下注、筹码、阶段） | `{ message, timestamp, code: null }` |
| `MethodArgumentNotValidException` | Bean Validation | `{ message: "字段: 原因", timestamp, code: null }` |

```json
// 普通校验错误
{ "message": "还没轮到你行动", "timestamp": "2026-09-12T09:53:21.123Z", "code": null }

// 牌桌身份错误（前端据此退出房间）
{ "message": "牌桌已关闭，请选择其他牌桌", "timestamp": "2026-09-13T02:41:03.221Z", "code": "TABLE_CLOSED" }
```

`code` 只有三个取值，**只在这些情况下前端才主动退出牌桌**：

| code | 触发点 | 前端行为 |
|---|---|---|
| `TABLE_CLOSED` | 牌桌已被删除，或操作时发现 `tables.get(id) != table` | 回大厅，提示"牌桌已关闭" |
| `SEAT_LEFT` | `requirePlayer()` 找不到该座位（已结算离桌） | 回大厅，提示"筹码与战绩已保存" |
| `INVALID_SESSION` | `authenticate()` 的 `reconnectToken` 不匹配 | 回大厅，引导从账号恢复座位 |

`AdminController.authorize()` 单独抛 `ResponseStatusException(401)`，**不带 body**。

## 4. 完整 REST 接口清单

> 路径前缀 `/api`。错误统一格式：`{ message, timestamp, code? }`，HTTP 400。
> 所有"需要 playerId"的接口在 URL 或 body 中带 `playerId + reconnectToken`。
> 任何带 `accountId` 的接口通过请求头 `X-Account-Token` 校验。
> 示例统一用 `B=http://localhost:8080`（`application.yml` 的默认端口）。
> **本机若用 `SERVER_PORT` 覆盖端口，每个示例的 host 都要相应替换**（见 §8.2 / §13.1）。

```bash
B=http://localhost:8080          # 以下所有示例的基点
```

### 4.1 接口索引

| 方法 | 路径 | 鉴权 | 用途 |
|---|---|---|---|
| GET | `/api/settings` | 公开 | 全局金额规则 |
| GET | `/api/tables` | 公开 | 公开牌桌列表（不含私人桌） |
| POST | `/api/tables` | 可选账号 | 创建牌桌（创建者自动入座） |
| POST | `/api/tables/{id}/join` | 可选账号 | 加入公开牌桌 |
| GET | `/api/tables/{id}` | `playerId+token` | 本玩家可见全量状态 |
| GET | `/api/tables/{id}/advice` | `playerId+token` | 策略建议（仅私人 AI 桌） |
| POST | `/api/tables/{id}/reconnect` | `playerId+token` | 重连，刷新 `reconnectToken` |
| POST | `/api/tables/{id}/start` | `playerId+token` | 开始下一局（≥2 人有筹码） |
| POST | `/api/tables/{id}/actions` | `playerId+token` | 提交下注动作 |
| POST | `/api/tables/{id}/leave` | `playerId+token` | 结算离桌；局中预约本手结束后释放座位 |
| POST | `/api/tables/{id}/chips/top-up` | `playerId+token` | 桌上补码（备用 → 桌上） |
| POST | `/api/tables/{id}/chips/cash-out` | `playerId+token` | 回收（桌上 → 备用） |
| POST | `/api/tables/{id}/emotes` | `playerId+token` | 发送语音表情 |
| POST | `/api/accounts` | 公开 | 创建账号（首登拿到明文登录码） |
| POST | `/api/accounts/login` | 公开 | 用昵称 + 跨设备登录码换 session |
| GET | `/api/accounts/{id}` | `X-Account-Token` | 个人主页（筹码 + 三段战绩） |
| POST | `/api/accounts/{id}/login-code` | `X-Account-Token` | 重置/指定登录码（旧码失效） |
| GET | `/api/accounts/{id}/active-seat` | `X-Account-Token` | 查账号是否有保留座位（无则 204） |
| GET | `/api/admin/settings` | `X-Admin-Token` | 管理员读金额规则 |
| PUT | `/api/admin/settings` | `X-Admin-Token` | 管理员写金额规则 |
| GET | `/api/admin/tables` | `X-Admin-Token` | 管理员列出全部牌桌（含私人） |
| DELETE | `/api/admin/tables/{id}` | `X-Admin-Token` | 管理员强制删除牌桌 |
| GET | `/api/admin/accounts` | `X-Admin-Token` | 管理员列出账号（不含凭证） |
| PATCH | `/api/admin/accounts/{id}` | `X-Admin-Token` | 改昵称/筹码/登录码（均可省略） |
| DELETE | `/api/admin/accounts/{id}` | `X-Admin-Token` | 删除账号（在座时拒绝） |
| GET | `/actuator/health` | 公开 | Docker 健康检查 |

### 4.2 创建账号 & 登录

```bash
# 创建账号（首次用；明文 loginCode 只在这里返回一次）
curl -X POST $B/api/accounts \
  -H 'Content-Type: application/json' \
  -d '{"nickname":"RiverKing"}'
```

```json
{
  "accountId": "aa787c68-3bfc-4ea5-a3e7-b7c8b41837d2",
  "accountToken": "0138e731-d5a7-4647-919e-9a170885a835",
  "loginCode": "JZXK-DEJX-PRWG",
  "profile": {
    "id": "aa787c68-3bfc-4ea5-a3e7-b7c8b41837d2",
    "nickname": "RiverKing",
    "chips": 10000,
    "createdAt": "2026-09-12T10:12:51.731854600Z",
    "overall": { "hands": 0, "wins": 0, "ties": 0, "losses": 0, "netChips": 0, "winRate": 0.0 },
    "ai":      { "hands": 0, "wins": 0, "ties": 0, "losses": 0, "netChips": 0, "winRate": 0.0 },
    "human":   { "hands": 0, "wins": 0, "ties": 0, "losses": 0, "netChips": 0, "winRate": 0.0 },
    "recentHands": []
  }
}
```

```bash
# 跨设备登录（昵称 + 登录码，横杠/大小写都容忍）
curl -X POST $B/api/accounts/login \
  -H 'Content-Type: application/json' \
  -d '{"nickname":"RiverKing","loginCode":"abcdefghijkl"}'
# → AccountSession（结构同创建账号的响应）
# 失败 → 400 {"message":"账号或跨设备登录码不正确","timestamp":"...","code":null}

# 个人主页（含最近 100 手）
curl $B/api/accounts/$ID -H "X-Account-Token: $TOKEN"
```

```json
{
  "id": "aa787c68-3bfc-4ea5-a3e7-b7c8b41837d2",
  "nickname": "RiverKing",
  "chips": 10480,
  "createdAt": "2026-09-12T10:12:51.731854600Z",
  "overall": { "hands": 3, "wins": 2, "ties": 0, "losses": 1, "netChips": 480, "winRate": 0.6666666666666666 },
  "ai":      { "hands": 3, "wins": 2, "ties": 0, "losses": 1, "netChips": 480, "winRate": 0.6666666666666666 },
  "human":   { "hands": 0, "wins": 0, "ties": 0, "losses": 0, "netChips": 0,   "winRate": 0.0 },
  "recentHands": [
    {
      "id": "5f0c1e2a-...",
      "playedAt": "2026-09-12T10:40:02.114Z",
      "tableId": "f9966666-c9c8-4fef-a349-9bc6283aface",
      "tableName": "周末牌局",
      "handNumber": 3,
      "mode": "AI",
      "result": "WIN",
      "netChips": 320,
      "endingChips": 10480
    }
  ]
}
```

> `mode` ∈ `AI`（私人桌）/ `HUMAN`（公开桌）；`result` ∈ `WIN` / `TIE` / `LOSS`。平局不计入胜场，`winRate = wins / hands`。

```bash
# 重置登录码（旧码立即失效）—— 不带 body 或 loginCode 为空 → 随机生成
curl -X POST $B/api/accounts/$ID/login-code -H "X-Account-Token: $TOKEN"
# → {"loginCode":"NEWC-ODE1-2345"}

# 指定自定义登录码：4 位纯数字，或 12 位字母数字组合；大小写与横杠都容忍，被他人占用则拒绝
curl -X POST $B/api/accounts/$ID/login-code \
  -H 'Content-Type: application/json' -H "X-Account-Token: $TOKEN" \
  -d '{"loginCode":"MYCODE123456"}'
# → {"loginCode":"MYCO-DE12-3456"}   12 位展示形态与随机码一致，登录时横杠可有可无

curl -X POST $B/api/accounts/$ID/login-code \
  -H 'Content-Type: application/json' -H "X-Account-Token: $TOKEN" \
  -d '{"loginCode":"1234"}'
# → {"loginCode":"1234"}   4 位数字码原样展示

# 非法 → 400 {"message":"跨设备登录码需要 4 位数字或 12 位字母数字组合",...}
# 12 位含符号 → 400 {"message":"跨设备登录码只能包含字母和数字",...}
# 冲突 → 400 {"message":"该跨设备登录码已被其他账号使用",...}

# 查账号是否还有保留座位 —— 有则 200，无则 204（无 body）
curl -i $B/api/accounts/$ID/active-seat -H "X-Account-Token: $TOKEN"
# 200 → SessionView（同 §4.3 建桌响应）；204 → 空 body
```

### 4.3 牌桌生命周期

```bash
# 1. 创建牌桌（可选附 accountId/Token 自动入座；私人桌可带 aiPlayers）
curl -X POST $B/api/tables \
  -H 'Content-Type: application/json' \
  -d '{
    "tableName":"周末牌局",
    "nickname":"Alice",
    "accountId":"aa787c68-...","accountToken":"0138e731-...",
    "maxPlayers":2,"privateTable":false,"aiPlayers":0,
    "buyIn":2000
  }'
```

```json
{
  "playerId": "a60cf871-1eca-46bc-b193-5c19c883880c",
  "reconnectToken": "575afc13-15ff-4895-a622-e9e26ddefd9f",
  "table": {
    "id": "f9966666-c9c8-4fef-a349-9bc6283aface",
    "name": "周末牌局",
    "maxPlayers": 2,
    "privateTable": false,
    "totalChips": 10000,
    "minBuyIn": 1000, "defaultBuyIn": 2000, "maxBuyIn": 4000,
    "smallBlind": 10, "bigBlind": 20,
    "phase": "WAITING", "phaseLabel": "等待开局",
    "handNumber": 0, "pot": 0, "currentBet": 0, "minRaise": 0,
    "actionDeadline": 0, "actionTimeSeconds": 25,
    "pots": [],
    "message": "Alice 加入了牌桌",
    "communityCards": [],
    "players": [
      {
        "id": "a60cf871-1eca-46bc-b193-5c19c883880c",
        "nickname": "Alice",
        "seat": 0,
        "chips": 2000, "reserveChips": 8000, "totalChips": 10000,
        "streetBet": 0, "handBet": 0,
        "status": "SITTING",
        "ai": false, "dealer": false, "currentTurn": false, "canRaise": false,
        "winner": false, "bestCards": [], "cards": [], "leaving": false
      }
    ],
    "version": 1
  }
}
```

> **`chips` 是桌上筹码，`reserveChips` 是账号备用，`totalChips` 是两者之和。** 入座 2000 后账号还剩 8000 备用。

```bash
# 2. 公开桌：其他人加入
curl -X POST $B/api/tables/$TABLE_ID/join \
  -H 'Content-Type: application/json' \
  -d '{"nickname":"Bob","buyIn":2000,"accountId":"...","accountToken":"..."}'
# → SessionView（结构同上，playerId/reconnectToken 换成 Bob 的）

# 3. 拉全量（每次 STOMP 推送后必调）——下面是开局打到翻牌后的完整结构
curl "$B/api/tables/$TABLE_ID?playerId=$PID&reconnectToken=$RT"
```

```json
{
  "id": "f9966666-c9c8-4fef-a349-9bc6283aface",
  "name": "周末牌局",
  "maxPlayers": 2, "privateTable": false,
  "totalChips": 10000, "minBuyIn": 1000, "defaultBuyIn": 2000, "maxBuyIn": 4000,
  "smallBlind": 10, "bigBlind": 20,
  "phase": "FLOP", "phaseLabel": "翻牌",
  "handNumber": 1,
  "pot": 60, "currentBet": 20, "minRaise": 20,
  "actionDeadline": 1789251913114, "actionTimeSeconds": 25,
  "pots": [60],
  "message": "翻牌",
  "communityCards": ["♠A", "♥9", "♦4"],
  "players": [
    { "id": "a60cf871-...", "nickname": "Alice", "seat": 0,
      "chips": 1980, "reserveChips": 8000, "totalChips": 9980,
      "streetBet": 0, "handBet": 20,
      "status": "ACTIVE",
      "ai": false, "dealer": true, "currentTurn": true, "canRaise": true,
      "winner": false, "bestCards": [], "cards": ["♠K", "♠Q"], "leaving": false },
    { "id": "b71d982f-...", "nickname": "Bob", "seat": 1,
      "chips": 1980, "reserveChips": 8000, "totalChips": 9980,
      "streetBet": 0, "handBet": 20,
      "status": "ACTIVE",
      "ai": false, "dealer": false, "currentTurn": false, "canRaise": false,
      "winner": false, "bestCards": [], "cards": ["??", "??"], "leaving": false }
  ],
  "version": 7
}
```

> - **`cards` 对他人永远是 `"??"`**（除非 SHOWDOWN 且未弃牌）。这是防作弊的核心，前端必须按 `'??'` 渲染背面。
> - **`pots[0]` 是主池**；长度 > 1 才存在边池，UI 才显示"边池 1…"。
> - **`version` 单调递增**，客户端用它丢弃过期响应（见 §5）。

```bash
# 4. 断线后重连 —— 返回**新** token，旧的立即失效
curl -X POST $B/api/tables/$TABLE_ID/reconnect \
  -H 'Content-Type: application/json' \
  -d "{\"playerId\":\"$PID\",\"reconnectToken\":\"$RT\"}"
# → SessionView（reconnectToken 已轮换，必须覆盖本地存储）

# 5. 开始下一局（仅当 phase∈{WAITING,SHOWDOWN} 且 ≥2 人有筹码）
curl -X POST $B/api/tables/$TABLE_ID/start \
  -H 'Content-Type: application/json' \
  -d "{\"playerId\":\"$PID\",\"reconnectToken\":\"$RT\"}"
# → TableView（同上）

# 6. 提交动作
curl -X POST $B/api/tables/$TABLE_ID/actions \
  -H 'Content-Type: application/json' \
  -d "{\"playerId\":\"$PID\",\"reconnectToken\":\"$RT\",\"type\":\"RAISE\",\"raiseTo\":60}"
# type ∈ {FOLD, CHECK, CALL, RAISE, ALL_IN}
#   CHECK/CALL 不带 raiseTo；RAISE 必须带 raiseTo；ALL_IN 忽略 raiseTo
# → TableView；非法动作 → 400 {"message":"最小加注至 40",...}

# 7. 预约离桌 —— 局中申请时 pending=true，本手结算后自动释放座位
curl -X POST $B/api/tables/$TABLE_ID/leave \
  -H 'Content-Type: application/json' \
  -d "{\"playerId\":\"$PID\",\"reconnectToken\":\"$RT\"}"
```

```json
// 局中申请 → 座位保留到本手结束；players[] 里该玩家 leaving=true
{ "pending": true, "table": { "...": "当前 TableView，leaving 标记为 true" } }

// 等待开局 / 摊牌后申请 → 立即结算，座位已释放
{ "pending": false, "table": null }
```

```bash
# 8. 补码 / 回收（仅 SHOWDOWN 或 WAITING，且桌上筹码须落在 [minBuyIn, maxBuyIn]）
curl -X POST $B/api/tables/$TABLE_ID/chips/top-up \
  -H 'Content-Type: application/json' \
  -d "{\"playerId\":\"$PID\",\"reconnectToken\":\"$RT\",\"amount\":500}"
# → TableView；chips 500↑ / reserveChips 500↓，totalChips 不变

curl -X POST $B/api/tables/$TABLE_ID/chips/cash-out \
  -H 'Content-Type: application/json' \
  -d "{\"playerId\":\"$PID\",\"reconnectToken\":\"$RT\",\"amount\":1500}"
# → TableView；反向搬运。只能全部回收或留下 ≥ minBuyIn

# 9. 发语音表情 —— 只广播事件，不改变牌桌状态
curl -X POST $B/api/tables/$TABLE_ID/emotes \
  -H 'Content-Type: application/json' \
  -d "{\"playerId\":\"$PID\",\"reconnectToken\":\"$RT\",\"emoteId\":\"nice-hand\"}"
```

```json
{
  "tableId": "f9966666-c9c8-4fef-a349-9bc6283aface",
  "version": 7,
  "type": "EMOTE",
  "playerId": "a60cf871-...",
  "nickname": "Alice",
  "emoteId": "nice-hand",
  "text": "打得不错"
}
```

> `emoteId` ∈ `{nice-hand, good-luck, thinking, call-you, wow, cheers}`；同一玩家 1.2s 内重复发送 → 400 `语音表情发送太快`。

### 4.4 管理员接口

> 管理员登录密钥默认为 `88914752`（`application.yml` 里 `poker.admin-token` 的默认值），可用环境变量 `POKER_ADMIN_TOKEN` 覆盖；口令不对或缺失时所有管理端点返回 401（**不是** 403）。

```bash
# 读取金额规则
curl $B/api/admin/settings -H "X-Admin-Token: $ADMIN"
# → {"totalChips":10000,"minBuyIn":1000,"defaultBuyIn":2000,"maxBuyIn":4000,"smallBlind":10,"bigBlind":20}

# 更新（只影响之后新建的牌桌，现有牌桌保持原规则）
curl -X PUT $B/api/admin/settings \
  -H 'Content-Type: application/json' -H "X-Admin-Token: $ADMIN" \
  -d '{"totalChips":10000,"minBuyIn":1000,"defaultBuyIn":2000,"maxBuyIn":4000,"smallBlind":10,"bigBlind":20}'
# → 同结构；非法组合 → 400 {"message":"最低带入不能少于 20 个大盲",...}
# 落盘到 POKER_SETTINGS_FILE（properties，原子写）

# 列出全部牌桌（含私人桌）
curl $B/api/admin/tables -H "X-Admin-Token: $ADMIN"
# → [ {"id":"...","name":"周末牌局","playerCount":2,"maxPlayers":2,"aiCount":0,
#       "privateTable":false,"totalChips":10000,"smallBlind":10,"bigBlind":20,
#       "minBuyIn":1000,"defaultBuyIn":2000,"maxBuyIn":4000,
#       "phase":"FLOP","phaseLabel":"翻牌","createdAt":"2026-09-13T02:41:03.221Z"} ]

# 删除牌桌（在线玩家收到 version=-1 事件后自动退出房间）
curl -X DELETE $B/api/admin/tables/$TABLE_ID -H "X-Admin-Token: $ADMIN"
# → 204 No Content

# 列出账号（不含任何凭证：无 token、无登录码散列）
curl $B/api/admin/accounts -H "X-Admin-Token: $ADMIN"
# → [ {"id":"...","nickname":"RiverKing","chips":9500,
#       "createdAt":"2026-09-13T02:41:03.221Z","lastSeenAt":"...","hands":42} ]

# 改账号：nickname / chips / loginCode 均可省略，省略即保持不变
curl -X PATCH $B/api/admin/accounts/$ID \
  -H 'Content-Type: application/json' -H "X-Admin-Token: $ADMIN" \
  -d '{"nickname":"NewName","chips":8000,"loginCode":"ADMINSET1234"}'
# → {"account":{...同上...},"loginCode":"ADMI-NSET-1234"}
#   仅当本次设置了 loginCode 才回文明文（服务端只存散列），否则 loginCode 为 null
# 昵称重复 → 400；chips 范围 0..10_000_000

# 删除账号：账号仍在牌局中会被拒绝，需先让其离桌或删除牌桌
curl -X DELETE $B/api/admin/accounts/$ID -H "X-Admin-Token: $ADMIN"
# → 204 No Content
# 在座 → 400 {"message":"该账号仍在牌桌 <tableId> 的牌局中，请先离桌再删除",...}
```

### 4.5 TableView 关键字段含义

`GET /api/tables/{id}` 返回 `TableViews.TableView`，组件大部分计算依赖它：

| 字段 | 含义 |
|---|---|
| `phase` / `phaseLabel` | 当前阶段（WAITING/PRE_FLOP/FLOP/TURN/RIVER/SHOWDOWN）+ 中文标签 |
| `handNumber` | 本牌桌第几局；变化时前端重置筹码飞行基线 |
| `pot` | 当前桌上累计（不区分主/边池，详见 `pots`） |
| `pots` | 边池分层金额数组；`pots[0]` 始终是主池，长度 > 1 才存在边池 |
| `currentBet` | 本轮当前最高注码（决定跟注额） |
| `minRaise` | 最小加注增量（基于上一个加注尺寸） |
| `actionDeadline` | 当前行动截止毫秒时间戳（前端轮询计算倒计时） |
| `actionTimeSeconds` | 单回合总时长（固定 25） |
| `communityCards` | 公共牌；不足 5 张时按出现顺序铺 |
| `players[].cards` | 底牌明文或 `"??"`（规则见 §5） |
| `players[].streetBet` / `handBet` | 本轮已下注 / 本局已下注 |
| `players[].status` | SITTING/ACTIVE/ALL_IN/FOLDED/OUT |
| `players[].winner` / `bestCards` | SHOWDOWN 时赢家标记 + 5 张最优组合 |
| `players[].leaving` | 已预约本手结束后离桌；不能自动开下一局 |
| `players[].timedOut` | 本手曾超时，AI 桌暂停自动下一局，手动开局后重置 |
| `version` | 全量状态版本，与 STOMP 版本一致；前端忽略旧版本和已离开牌桌的迟到响应 |

`POST /api/tables/{id}/leave` 复用 `PlayerCommand`（`playerId + reconnectToken`），返回 `{pending, table}`。等待开局或摊牌后立即结算离桌，`pending=false`、`table=null`；局中返回 `pending=true` 及当前状态，直到本手结算后释放座位。离桌不会中途删除玩家的下注或全押资格。最后一名真人离开时清理牌桌并发 `version=-1`。

牌桌访问错误的 `code` 语义见 §3.7。前端只对牌桌身份错误退出房间，普通下注校验错误仅提示；断线期间每 4 秒尝试拉取状态，恢复订阅后立即同步。

### 4.6 关键校验

`Requests.CreateTable`：`tableName 1–30`、`nickname 1–16`、`maxPlayers 2–6`、`aiPlayers 0–5` 且 < `maxPlayers`、`buyIn 1..10_000_000`，**公开桌不允许 AI**。
`Requests.UpdateSettings`：`totalChips 100..10M`、`smallBlind 1..100k` 且 `bigBlind > smallBlind`、`minBuyIn ≥ 20*bigBlind`、`min ≤ default ≤ max ≤ totalChips`（`PokerSettings.validate()`）。
`AccountService.normalizeNickname`：1–16 字符；`normalizeLoginCode`：12 位去横杠/空格大写后等价。
自定义登录码额外要求 `requireCustomLoginCode`：去横杠大写后匹配 `\d{4}`（4 位纯数字）或 `[A-Z0-9]{12}`，且不能与其他账号的散列冲突；12 位存储/返回形态统一为 `XXXX-XXXX-XXXX`，4 位原样返回。随机生成仍是 12 位。服务端口径：明文只在「创建 / 重置 / 管理员改码」的响应里出现一次，`accounts.json` 只存 SHA-256（无盐）。

## 5. 前端模块分层

| 层 | 位置 | 约束 |
|---|---|---|
| API 客户端 | `services/api.js` | 所有请求走这里；15s `AbortSignal.timeout`；错误对象带 `status` + `code` |
| 纯逻辑 | `services/{rules,chips,tableView,tableEffects,cards,roomLayout}.js` | **零 Vue 依赖**、纯函数，便于 Vitest 单测 |
| 同步策略 | `services/tableSync.js` | `shouldApplyTable`（版本守卫）、`tableExitMessage`（code→文案）、`createTableRefresh`（单飞刷新） |
| 实时 | `services/socket.js` | `watchTable` 返回退订函数，组件 `onBeforeUnmount` 必须调 |
| 持久化 | `services/{session,account}.js` | localStorage；JSON 解析失败返回 null，**不要抛** |
| 视图 | `App.vue` + `components/*.vue` | `defineProps/defineEmits`，不直接 fetch、不直接读 storage |
| 状态机入口 | `App.vue` 的 `initialize` / `connectTable` / `applyTable` / `settleLeave` | 加载账户 → 设置 → 牌桌 → 自动重连 → 订阅 STOMP → 单飞刷新 |

### 前端重要约定

- **响应式策略面板**只在 `table.privateTable && !betweenHands` 显示；非私人桌调用 `/advice` 会 400。
- **`currentTurn` 与服务端 `currentTurnSeat` 同步**：组件 watch `myTurn` 触发震动/声音；`handNumber` 变化时清空上局 `previousBets` 重新计筹码飞行。
- **底牌可见性**：`TableViews.TableView.from()` 仅在 `viewerId == player.id` 或 `phase == SHOWDOWN && status != FOLDED` 时返回明牌，否则返回 `"??"`。前端 UI 必须按 `seat.player.cards[i] === '??'` 渲染背面。
- **`actionClock`** 服务端 25s 倒计时；前端 200ms 轮询 `actionNow`；≤5s 进入 `urgent` 触发滴答音与震动。超时后服务端自动 CHECK/FOLD，客户端会收到新版本。
- **跨设备登录**：登录码可为 4 位数字或 12 位字母数字，12 位展示为 `XXXX-XXXX-XXXX`（仅展示用），存储时去横杠大写。复制按钮使用 `navigator.clipboard.writeText`，失败需 toast 提示。
- **开始下一局的补码确认**：`PokerRoom.startHand()` 先算 `topUpWaitPrompt(table)`（内部用 `playersNeedingTopUp`），有人桌上筹码为 0 且备用筹码够 `minBuyIn` 就弹 `window.confirm`：确定 → 立即开始，取消 → 本次不开局，留时间等 TA 补码。AI 座位（自己会补码）、已预约离桌、备用筹码不够最低买入的人都不算。判定必须留在 `services/rules.js` 里以便单测，不要写进组件。
- **STOMP 重连**：`socket.js` 内部 `reconnectDelay=2000`，`onStompError`/`onWebSocketClose` 会把 `connected` 置 false，UI 显示"正在重连"。

### 5.1 一次状态变更的完整链路

新增任何"会改变牌桌状态"的前端操作，必须走完这条链，否则会出现**旧状态覆盖新状态**的竞态：

```
用户点击 → App.vue 调 api.xxx() → 拿到最新 TableView
        → applyTable(latest)
             ├─ shouldApplyTable(table.value, latest)   // 版本守卫：latest.version >= current.version 且 id 相同
             ├─ 检查 players 里还有没有自己              // 没有 → finishLeaving(SEAT_LEFT 文案)
             └─ table.value = latest; tableSynced = true
        → loadAdvice() / phase===SHOWDOWN 时 loadAccountProfile()

同时 STOMP 推来 TABLE_UPDATED → refresh()
        → tableRefresh.request()   // 单飞：正在进行时只置 again=true，结束后补跑一次
        → 同样经 applyTable() 的版本守卫
```

三条铁律：

1. **任何来源的 TableView 都要过 `applyTable`**，不要直接 `table.value = x`。
2. **`connected` 要同时满足 WebSocket 已连 且 至少同步过一次**（模板里绑的是 `connected && tableSynced`），否则 UI 会显示"实时在线"但数据是旧的。
3. **收到 `version == -1` 事件**：`socket.js` 会自行停订阅并回调 `onClosed`，`App.vue` 据此提示"牌桌已关闭"或"已结算离桌"（区分依据是 `leavingTableId` / `player.leaving`）。

## 6. 数据持久化与配置

| 文件 | 内容 | 写入位置 | 备注 |
|---|---|---|---|
| `accounts.json` | 全部账号 + 历史 100 手战绩 | `STATE_DIRECTORY/accounts.json` 或 `data/accounts.json` | JSON 原子写（先 .tmp 再 ATOMIC_MOVE） |
| `settings.properties` | totalChips/min/default/max/smallBlind/bigBlind | `POKER_SETTINGS_FILE` 路径 | Properties 格式，原子写 |
| 牌桌状态 | 无 | 进程内 `ConcurrentHashMap<UUID,PokerTable>` | 重启清空 |
| 玩家聊天/表情 | 无 | 无 | 仅实时广播 |

环境变量（`compose.yml` 已默认）：`SERVER_PORT=8080`、`POKER_ALLOWED_ORIGINS=http://localhost:8088`、`POKER_SETTINGS_FILE=/var/lib/poker/settings.properties`、`POKER_ACCOUNTS_FILE=/var/lib/poker/accounts.json`、可选 `POKER_ADMIN_TOKEN=<口令>`。Docker 镜像以 `poker` 非 root 用户运行，宿主挂载 `poker-data` 卷即可持久化。

## 7. 测试策略与命名

- 后端 JUnit 5 + AssertJ，文件以 `Test.java` 结尾，镜像放置在同包测试目录（`backend/src/test/java/com/example/poker/...`）。
- 领域测试用 `PokerTable` 包内构造函数（`Supplier<Deck>` 注入）做确定性测试。`service` 测试用 `TableService` 的测试构造器，绕开 Spring。
- 前端 Vitest + jsdom，测试文件 `*.test.js` 与被测代码同目录。`npm test -- --passWithNoTests`、`npm run build` 必须通过才能合入。
- 提交前需 `cd backend && mvn clean verify` 与 `cd frontend && npm ci && npm test -- --passWithNoTests && npm run build` 全绿。

## 8. 本地开发与排错

```bash
# 后端（需要 JDK 17 + Maven 3.9+）
cd backend && mvn spring-boot:run    # 默认 8080
# 前端（另开终端，需要 Node.js 22+）
cd frontend && npm install && npm run dev   # 5173，代理 /api /ws
# 一键（需要 Docker 24+ 与 Compose v2）
docker compose up -d --build          # 8088
```

**CORS**：本地 `vite.config.js` 代理无需配 CORS；自托管需把访问域名加入 `POKER_ALLOWED_ORIGINS`。
**健康检查**：`/actuator/health`（仅暴露 health、info）。
**管理员口令**：默认空 → 管理端点全部 401。要启用就设 `POKER_ADMIN_TOKEN` 并在 UI 顶栏"管理"中输入。

### 8.1 本机已验证的工具链（参考）

本项目可在以下组合下冷启动通过（冒烟测试通过：`/actuator/health` UP + `POST /api/accounts` + `POST /api/tables` 全成功）：

- Windows 11（NT 10.0.26200），非管理员
- JDK: Adoptium Temurin 17.0.20.1 → `C:\Users\User\.tools\jdk\jdk-17.0.20.1+1`
- Maven: Apache Maven 3.9.9 → `C:\Users\User\.tools\maven\apache-maven-3.9.9`
- Node: 22.22.2（managed runtime）→ `C:\Users\User\.workbuddy\binaries\node\versions\22.22.2-3\`
- winget 可用但 JDK/Maven 用 winget 装失败（权限），改手动下载 zip 解压
- PowerShell `Out-File` 默认 GBK，中文响应会显示成 `?`；读取用 `Read` 工具拿到 UTF-8 原文

### 8.2 端口冲突的 workaround

在某些机器上即使 `8080` 没人监听，`mvn spring-boot:run` 启动也会随机选端口并踩到其他进程。已知占用：

- `23561`：WorkBuddy.exe（IDE 用，无法释放）
- 任何 IDE / Chromium 调试端口

绕过办法：**显式指定** `SERVER_PORT`（避开 8080 备用逻辑），并避免与本机其他进程冲突：

```bash
# PowerShell
$env:SERVER_PORT = "8090"
$env:POKER_ALLOWED_ORIGINS = "http://localhost:5173,http://127.0.0.1:5173,http://localhost:8088,http://127.0.0.1:8088"
cd backend
mvn -B spring-boot:run
```

前端 vite 默认端口 `5173` 通常可用；如被占用改 `vite.config.js` 的 `server.port` 后重启。

## 9. 已知待办与边界

- 牌桌状态在 JVM 内存，**不支持水平扩容**。
- 无盲注结构变化 / 无多桌大厅聚合 / 无观察者 / 无聊天。
- AI 决策使用蒙特卡洛（260 次）+ 启发式阈值；策略建议同理（900 次）但仅参考用。
- 行动倒计时到 0 由服务端每 500ms 检查：无跟注金额则自动 CHECK，否则 FOLD；暂离与断线仍会计时。
- README 中关于"不支持全押/边池/账号/重连"的描述已过时，**真实能力以本文件为准**。

## 10. 修改工作流建议

1. 明确改动落在哪一层（领域 / 服务 / Controller / 前端 service / 视图）。
2. 若改领域规则，先写领域单测（`PokerTableTest` 系列）再改实现。
3. 若改前端交互，先在 `services/*.test.js` 加纯逻辑用例，再改组件。
4. 若改 API 形状，同步更新 `Requests/TableViews/AccountViews` 三个 record + 前端 `api.js`。
5. 若引入新环境变量，更新 `compose.yml` 的 `backend.environment` 与 `application.yml` 默认值。
6. CI 跑通 `mvn clean verify` + `npm test -- --passWithNoTests` + `npm run build`。

> 具体某类改动要碰哪些文件、有哪些坑，见 **§12 改动 cookbook**；踩过的坑集中在 **§13 已知陷阱与排查**。

## 11. 调试与诊断

### 11.1 常见报错

| 状态码 | 触发条件 | 处理建议 |
|---|---|---|
| 400 `还没轮到你行动` | 当前 playerId 与 `currentTurnSeat` 不匹配 | 等 STOMP 推送新版本后用最新 `currentTurnSeat` |
| 400 `牌局进行中，暂不能加入` | `phase` 不是 `WAITING/SHOWDOWN` | 在 SHOWDOWN 后再 join，或等下一局 |
| 400 `牌桌已满` | `players.size() >= maxPlayers` | 客户端把"入座"按钮 disable |
| 400 `带入筹码必须在 X 到 Y 之间` | 违反 `PokerSettings.validate()` | UI 提示并 clamp 到范围 |
| 400 `账号已有保留座位` | 同一账号在别桌 | 先 `restoreAccountSeat()` 回到原桌 |
| 400 `私人牌桌不能从大厅加入` | 大厅 join 私人桌 | 应通过 `POST /tables` 创建 + join，或分享邀请码（未实现） |
| 401 `管理员口令错误` | `AdminController.authorize` 失败 | 设置 `POKER_ADMIN_TOKEN` 后重启 |
| 404 `牌桌不存在` | TableService 已删除该 table | 重新进入大厅 |
| 400 `重连凭证无效` | reconnectToken 不匹配 | 清 localStorage，让用户重新入座 |

### 11.2 日志与监控

- **健康检查**：`GET /actuator/health` → `{ "status":"UP" }`。Docker Compose 据此决定前端何时启动。
- **后端日志**：默认 `INFO`，Spring Web 与 STOMP 框架日志可在 `application.yml` 加 `logging.level.org.springframework.web.socket=DEBUG` 调试连接。
- **看牌桌内存状态**：临时方案：在 `PokerTable` 加 `toString()` 调试，或在 `TableService` 加 `/api/debug/tables/{id}`（**生产慎用，会泄露底牌**）。
- **前端调试**：`localStorage` 里 `poker.session` / `poker.account` / `poker.nickname` 是状态根；浏览器 console 的 `connect → onChange → fetch` 链路可直接断点。

### 11.3 典型排查流程

1. 客户端没刷新 → 看 STOMP `connected` 是否 true；用 `wscat -c ws://host/ws` 测裸连接。
2. 客户端显示 `version` 在涨但 UI 没变 → 检查 `App.vue:refresh()` 是否有未捕获异常。
3. AI 不动 → 检查 `runAiTurns` 是否触发 500 上限；临时把 `simulations` 调到 50 加速排查。
4. 边池对不上 → 加日志到 `PokerTable.showdown()`，打印 `levels / contributors / eligible / best / winners`。
5. 重连后状态错位 → 注意 `reconnect()` 会返回**新**的 `reconnectToken`，前端必须用返回值的 token 覆盖 localStorage。

## 12. 改动 cookbook（按场景查表）

每个场景列出「触碰文件 → 关键注意点 → 必改测试 → 风险」。
风险等级：🟢 局部纯新增，合入即用 ｜ 🟡 跨层，需回归相关测试 ｜ 🔴 触及领域规则或实时协议，必须全量 `mvn clean verify` + `npm test` + `npm run build`。

### 12.1 新增一种下注动作（如 `BET` / `POST`）— 🔴

| 层 | 文件 |
|---|---|
| 领域 | `domain/ActionType.java`（加枚举）、`domain/PokerTable.java` → `act()`（**唯一校验入口**，改状态转移） |
| 编排 | `service/TableService.java`（通常无需改，`act` 转发） |
| AI/建议 | `service/PokerAiStrategy.java`、`service/PokerAdvisor.java`（决策分支） |
| 入参 | `dto/Requests.java`（若需新字段，如 `betTo`） |
| 前端逻辑 | `services/rules.js` → `callAmount / canAllIn / quickRaiseTo / validRaise` |
| 前端视图 | `components/PokerRoom.vue` 行动条按钮；可选 `services/audio.js` 音效 |

注意点：`act()` 里的 `acted` / `streetBet` / `raiseAllowed` 三个状态位必须同步更新，否则会卡住回合；**不要**在 `publish()` 里塞动作细节（§3.1）；校验失败直接抛 `IllegalArgumentException` 即可自动转 400。
必改测试：`PokerTableTest`（新动作合法性 + 非法场景）、`rules.test.js`。
风险点：动作枚举被 JSON 反序列化，枚举名改动即破坏协议，禁止重命名既有值。

### 12.2 给 `TableView` 加一个字段 — 🟡

`dto/TableViews.java` 是 Java `record`：加字段会让**所有** `new TableView(...)` / `new PlayerView(...)` 调用点在编译期报错——这是刻意保留的防漏改护栏，逐一补参数即可，别用 `@JsonIgnore` 绕过。若字段需要派生，优先放 `services/tableView.js`（纯函数）而不是后端。
注意点：**底牌类字段绝不能进公共 view**（§3.1）；随版本变化的字段无需改 STOMP，前端拉全量即可拿到。
必改测试：`tableView.test.js`；若字段影响布局，附桌面/移动截图。

### 12.3 新增一条 REST 端点 — 🟡

| 层 | 文件 |
|---|---|
| Controller | `controller/TableController.java` / `AccountController.java` / `AdminController.java` |
| 服务 | `service/TableService.java`：**必须**用 `withTable(...)` / `table.access()` 包住，切勿在 Controller 加锁（§3.2） |
| 入参 | `dto/Requests.java`：record + `@Valid` 约束（缺注解则 400 文案为空） |
| 前端 | `services/api.js` 封装；若改状态，调用方走 `App.vue` 的 `applyTable()`（§5.1） |

注意点：状态变更后**没有 `publish()` 就不会广播**，前端不会自动刷新——明确是否需要广播；管理端点须走 `authorize()` + `X-Admin-Token`。
必改测试：`TableService` 测试构造器（绕开 Spring）加用例；必要时补 `@WebMvcTest` 校验鉴权/校验注解。

### 12.4 改边池 / 摊牌结算 — 🔴

`domain/PokerTable.java` 的 `showdown()` / `awardPot()` / 边池分层逻辑。这是全项目最易出错处，**先写确定性测试再改实现**：用包内构造器注入 `Supplier<Deck>` 固定发牌。必测边界：多档 all-in 分层、有人弃牌后仍进池、只剩一人（`awardUncontested`）、余数分配（按 `seat` 偏移）。调试时在 `showdown()` 打印 `levels / contributors / eligible / best / winners`（§11.3）。

### 12.5 新增一个前端纯逻辑 helper — 🟢

放 `services/*.js`（**零 Vue 依赖**，§5），同目录加 `*.test.js`，再由 `App.vue` / `PokerRoom.vue` 消费。禁止在 `.vue` 里写可单测的算法——组件只做绑定与响应式。

### 12.6 新增配置 / 环境变量 — 🟢

三处同步：`resources/application.yml` 默认值 → `PokerSettings`（`@Value` 注入）或对应 service → `compose.yml` 的 `backend.environment`。本地运行时用 `$env:XXX`（见 §8.2）。漏改 `application.yml` 默认值会导致裸跑启动失败。

### 12.7 改行动倒计时 / 超时行为 — 🟡

- 服务端：`service/TableService.java`（`@Scheduled` 每 500ms 扫描）、`domain/PokerTable.java`（超时后无跟注额 → CHECK，否则 FOLD）。
- 前端：`services/tableEffects.js`（`turnClock`）、`PokerRoom.vue`（200ms 轮询、≤5s `urgent`）。
- 协议：`dto/TableViews.java` 的 `actionDeadline` / `actionTimeSeconds`。
注意点：超时**照样计时**，暂离/断线也计入；测试里若要“静止观察”，记得关掉或缩短调度周期。

### 12.8 新增账号统计字段 — 🟡

`service/AccountService.java`（统计写入）+ `dto/AccountViews.java`（出参）+ `frontend/src/services/account.js` / `App.vue`。**必须兼容旧 `accounts.json`**：反序列化时为缺失字段给默认值（`0` / 空数组），否则老存档会读出 null。战绩按「牌桌 + 手数」去重（§3.6）。

## 13. 已知陷阱与排查

### 13.1 `SERVER_PORT` 被环境变量污染 → 后端随机端口
现象：`mvn spring-boot:run` 起来后随机选端口（甚至踩到 WorkBuddy 的 23561），前端 `/api` 全 404。
根因：机器上存在 `SERVER_PORT` 变量，Spring 优先读它。
对策：启动前**显式**设 `$env:SERVER_PORT="8090"`，并把 `vite.config.js` 的 `server.proxy` target 改成同一端口（§8.2）。

### 13.2 vite 代理端口写死
`vite.config.js` 把 `/api` `/actuator` `/ws` 代理到固定端口。改了 `SERVER_PORT` 却忘改这里 —— 症状是前端 500/CORS 报错但后端日志无请求。**两处必须一起改**。

### 13.3 残留 JVM 锁住 jar / 端口假死
现象 A：`mvn clean verify` 报 `target/*.jar` 被占用（测试其实全过，只是 `repackage` 失败）。
现象 B：端口 `LISTEN` 但 `Invoke-WebRequest` 超时——进程卡死的残留 JVM。
对策：`Get-Process java` / `Get-NetTCPConnection -State Listen` 找 PID → `Stop-Process -Id <pid> -Force`；构建时用 `mvn -B verify`（去掉 `clean`）可临时绕过锁。

### 13.4 PowerShell 输出与编码
本机 PowerShell **stdout 不回显**、`Out-File` 默认 GBK 会把中文变 `?`。
对策：结果 `Out-File -Encoding utf8` 落盘，再用 Read 工具读原文；抓 HTTP 响应统一用 `curl.exe -s -o file.json`（比 `Invoke-RestMethod` 稳）。

### 13.5 误以为 STOMP 推送里有状态
推送只有 `{tableId, version, type}`（EMOTE 例外）。看到 UI 不更新先怀疑：是不是没在收到事件后调 REST 拉全量（§3.1 / §5.1）。

### 13.6 锁边界：不要在 Controller/Service 另加 `synchronized`
`PokerTable.access()` 是唯一事务入口，已把指令、AI 推进、账号结算、离桌清理合并进同一把锁。外部再包一层锁会死锁或导致 AI 轮询期间读到半状态。

### 13.7 `reconnectToken` 每次重连都会轮换
`reconnect()` 返回**新** token；前端若继续用旧 token，下一次请求直接 `INVALID_SESSION` 被踢回大厅。**必须**用返回值覆盖 localStorage。

### 13.8 版本守卫不可绕过
任何来源的 `TableView`（REST 返回、STOMP 触发的刷新、乐观更新）都要经 `applyTable()` → `shouldApplyTable()` 版本比对，否则迟到的旧响应会**覆盖**新状态。

### 13.9 远程重启别用 `pkill -f <jar 名>`
`pkill -f poker-backend-1.0.0.jar` 会连**正在执行这条命令的 ssh/bash 自身**一起杀掉（它的命令行里就含这个字符串），表现为 ssh 直接断连、输出丢失、退出码 255。
正确做法：先单独一次 `ps -eo pid,args | grep '[p]oker-backend'` 拿到 PID，再 `kill <pid>`；或分两条 ssh 命令执行。

### 13.10 金额输入框不要绑 `step=bigBlind`
HTML `<input type=number>` 的 `step` 校验基准是 `min`，不是 0。曾经给「最低/默认/最高带入」和「调整金额」写了
`min="1" :step="table.bigBlind"`，大盲 50 时合法值变成 `1,51,101…`，想填 5000 会被浏览器拦下并提示“最接近的有效值为 4951 和 5001”
—— 连内置预设 5000/10000/20000 都存不进去。金额类输入统一 `step="1"`（任意整数），规则校验交给服务端 `PokerSettings.validate()`。

### 13.11 推送到 GitHub 走不通 `git push` 时的退路
本机到 `github.com:443` 常被代理拦掉（`CONNECT tunnel failed, response 502`），但 `api.github.com` 可用：用 `gh auth token` + Git Data API（blob → tree → commit → 更新 ref）提交。
**注意**：必须从 git 对象库取内容（`git cat-file blob $(git rev-parse HEAD:<path>)`），不要直接读工作区文件——Windows 工作区是 CRLF，直接上传会把仓库里的 LF 全改成 CRLF。

---

## 原 Repository Guidelines（保留）

### Project Structure & Module Organization

- `backend/`: Spring Boot 3 API, STOMP WebSocket endpoints, poker domain logic, AI/advisor services, and configuration.
- `backend/src/main/java/com/example/poker/`: production Java code organized into `config`, `controller`, `domain`, `dto`, and `service`.
- `backend/src/test/java/com/example/poker/`: JUnit tests mirroring production packages.
- `frontend/`: Vue 3 and Vite client.
- `frontend/src/components/`: Vue table and card components; `frontend/src/services/` contains API, session, rules, and presentation helpers plus colocated Vitest tests.
- `compose.yml`, `Jenkinsfile`, and module `Dockerfile`s define deployment and CI.

Generated directories (`backend/target`, `frontend/dist`, and `frontend/node_modules`) must not be committed.

### Build, Test, and Development Commands

- `cd backend && mvn spring-boot:run`: run the API on port 8080.
- `cd backend && mvn clean verify`: compile and run all backend tests.
- `cd frontend && npm ci`: install locked dependencies.
- `cd frontend && npm run dev`: start Vite on port 5173 with API/WebSocket proxies.
- `cd frontend && npm test`: run Vitest once.
- `cd frontend && npm run build`: create the production bundle in `frontend/dist`.
- `docker compose up -d --build`: build and run both modules at port 8088.

### Coding Style & Naming Conventions

Use four-space indentation for Java and two spaces for JavaScript, Vue, YAML, and CSS. Follow Java conventions: `PascalCase` types, `camelCase` methods/fields, and packages under `com.example.poker`. Use `camelCase` for JavaScript exports and descriptive Vue component names such as `PlayingCard.vue`. The frontend uses ES modules, single quotes, and generally omits semicolons. No formatter or linter is enforced, so preserve nearby style.

### Testing Guidelines

Backend tests use JUnit 5 and end in `Test.java`; frontend tests use Vitest and end in `.test.js`. Add regression tests for betting, side pots, reconnects, chip transfers, AI decisions, and responsive view helpers when changing those areas. Run both `mvn clean verify` and `npm test` before submitting; also run `npm run build` for UI changes.

### Commit & Pull Request Guidelines

History follows Conventional Commit prefixes such as `feat:`, `fix:`, `test:`, and `chore:`. Write imperative, narrowly scoped subjects. Pull requests should explain behavior changes, list verification commands, link related issues, and include desktop/mobile screenshots for visual changes. Call out configuration or deployment impacts explicitly.

### Security & Configuration

Do not commit `.env` files, credentials, admin tokens, or SSH keys. Configure allowed origins and runtime settings through environment variables or deployment configuration. Poker state is process-local unless explicitly persisted; consider restart and compatibility effects when changing state models.
