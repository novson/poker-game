# River Room — 实时多人德州扑克 MVP

一个可直接运行的 2–6 人实时德州扑克项目。前端使用 Vue 3，后端使用 Spring Boot 3 + STOMP WebSocket，默认以 Docker Compose 的 bridge 网络部署。

## 已实现

- 创建牌桌、浏览牌桌、匿名昵称加入
- 2–6 人座位、庄家位、大小盲注和行动顺序
- 翻牌前、翻牌、转牌、河牌四轮下注
- 过牌、跟注、加注、弃牌、**全押与边池**
- 5–7 张牌最佳牌型计算、摊牌比较、平局分池
- WebSocket 实时刷新；公共消息只推送状态版本号，**底牌永不外泄**
- **无密码账号**：本地存储跨设备登录码，长期筹码与战绩落盘
- **断线身份恢复**：`reconnectToken` 在刷新、关闭浏览器后能回到原座位
- **离桌换桌**：两局之间直接结算离桌；局中预约本手结束后离桌，结算完成后释放座位，最后一名真人离桌时清理牌桌
- **超时推进**：服务端每 500ms 检查 25 秒行动时限，到期自动过牌（无需跟注时）或弃牌；断线和暂离不暂停计时，AI 桌超时后暂停自动下一局
- **移动端操作**：牌桌按可用高度布局，操作栏常驻显示自己的手牌、筹码和倒计时；断线时提示恢复同步，极短屏可滚动查看牌桌
- **私人 AI 牌桌**：可加 1–5 个 AI 对手，并附带策略建议面板
- **管理员后台**：调整盲注与买入范围，强制删除牌桌（需要 `POKER_ADMIN_TOKEN`）
- 后端单元测试、前端 Vitest 与生产构建、Docker Compose 和 Jenkins Pipeline

当前为 MVP：状态保存在单个后端进程内存，**牌桌**重启会清空；**账号与金额配置**已落盘为 JSON / properties 文件。多实例水平扩容、观察者、聊天等功能不在范围内。详细接口、领域规则与修改指南见 [`AGENTS.md`](./AGENTS.md)。

`POST /api/tables/{id}/leave` 使用 `playerId + reconnectToken`，返回 `{pending, table}`：`pending=true` 表示已预约；`pending=false` 表示离桌完成。客户端仅接受当前牌桌、不低于当前 `version` 的状态；收到关闭事件或失效座位错误后停止订阅和重试，并返回大厅。上述功能需要前后端一同更新，后端重启会清空正在进行的牌桌。

## 一键启动

要求 Docker 24+ 和 Docker Compose v2：

```bash
docker compose up -d --build
docker compose ps
```

浏览器访问 `http://服务器IP:8088`。停止服务：

```bash
docker compose down
```

如需从其他域名访问，修改 `compose.yml` 中的 `POKER_ALLOWED_ORIGINS`，多个来源使用英文逗号分隔。

## 本地开发

要求 JDK 17、Maven 3.9+、Node.js 22+：

```bash
cd backend
mvn spring-boot:run
```

另开终端：

```bash
cd frontend
npm install
npm run dev
```

前端开发地址为 `http://localhost:5173`，Vite 会代理 `/api` 与 `/ws` 到后端 8080 端口。

## 测试与构建

```bash
cd backend && mvn clean verify
cd frontend && npm ci && npm test -- --passWithNoTests && npm run build
```

## Jenkins 自动 CI

流水线顺序为：`git push → GitHub webhook → Jenkins → 后端测试 → 前端测试/构建 → 归档产物`。

### 1. Jenkins 安装插件

- Pipeline
- Git
- GitHub Integration
- Maven Integration
- NodeJS
- JUnit

### 2. 配置全局工具

进入 **Manage Jenkins → Tools**，工具名称必须与 `Jenkinsfile` 一致：

| 工具 | Jenkins 名称 | 建议版本 |
|---|---|---|
| JDK | `jdk17` | Temurin 17 |
| Maven | `maven3` | 3.9.x |
| NodeJS | `node22` | 22.x |

### 3. 创建 Pipeline Job

1. New Item → Pipeline，名称填写 `poker-game`。
2. Definition 选择 **Pipeline script from SCM**。
3. SCM 选择 Git，填写 GitHub 仓库地址和凭据。
4. Branch Specifier 填 `*/main`，Script Path 填 `Jenkinsfile`。
5. 在 Build Triggers 勾选 **GitHub hook trigger for GITScm polling**。

### 4. GitHub Webhook

仓库进入 **Settings → Webhooks → Add webhook**：

- Payload URL：`https://你的Jenkins域名/github-webhook/`
- Content type：`application/json`
- Events：`Just the push event`
- Active：启用

Jenkins 地址必须能被 GitHub 公网访问并具有有效 HTTPS 证书。若 Jenkins 只在内网，可使用 GitHub Actions、自建反向代理/隧道，或让 Jenkins 定时 Poll SCM。

推送后可在 GitHub Webhook 的 **Recent Deliveries** 查看 HTTP 状态，在 Jenkins 的 Console Output 查看每个阶段。只有所有测试和构建命令成功，流水线才会显示绿色。

## 主要接口

> 完整接口、字段约束与认证方式见 [`AGENTS.md` §4](./AGENTS.md#4-完整-rest-接口清单)。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/settings` | 获取全局金额规则 |
| GET | `/api/tables` | 公开牌桌列表 |
| POST | `/api/tables` | 创建牌桌（可附账号入座） |
| POST | `/api/tables/{id}/join` | 加入公开牌桌 |
| GET | `/api/tables/{id}?playerId=...&reconnectToken=...` | 获取本玩家可见状态 |
| GET | `/api/tables/{id}/advice?...` | 策略建议（仅私人 AI 桌） |
| POST | `/api/tables/{id}/reconnect` | 断线重连，刷新 token |
| POST | `/api/tables/{id}/start` | 开始下一局 |
| POST | `/api/tables/{id}/actions` | 执行下注动作 |
| POST | `/api/tables/{id}/chips/top-up` | 桌上补码 |
| POST | `/api/tables/{id}/chips/cash-out` | 回收筹码 |
| POST | `/api/tables/{id}/emotes` | 发送语音表情 |
| POST | `/api/accounts` | 创建长期账号 |
| POST | `/api/accounts/login` | 用昵称 + 跨设备登录码登录 |
| GET | `/api/accounts/{id}` | 个人主页（筹码 + 战绩） |
| GET | `/api/accounts/{id}/active-seat` | 查询账号是否有保留座位 |
| GET / PUT | `/api/admin/settings` | 管理员读写金额规则 |
| GET / DELETE | `/api/admin/tables` | 管理员列出 / 删除牌桌 |
| WS | `/ws` | STOMP 连接，订阅 `/topic/tables/{id}` |

## 项目结构

```text
backend/    Spring Boot API、牌局状态、牌型算法、测试
frontend/   Vue 3 大厅与实时牌桌
compose.yml bridge 网络的一键部署
Jenkinsfile GitHub push 自动触发的 CI 流水线
```

更详细的模块说明、领域规则、修改工作流见 [`AGENTS.md`](./AGENTS.md)。

本项目使用虚拟筹码，仅用于技术演示，不包含充值、提现或真钱赌博功能。
