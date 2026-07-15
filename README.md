# River Room — 实时多人德州扑克 MVP

一个可直接运行的 2–6 人实时德州扑克项目。前端使用 Vue 3，后端使用 Spring Boot 3 + STOMP WebSocket，默认以 Docker Compose 的 bridge 网络部署。

## 已实现

- 创建牌桌、浏览牌桌、匿名昵称加入
- 2–6 人座位、庄家位、大小盲注和行动顺序
- 翻牌前、翻牌、转牌、河牌四轮下注
- 过牌、跟注、加注、弃牌
- 5–7 张牌最佳牌型计算、摊牌比较、平局分池
- WebSocket 实时刷新；公共消息不携带玩家底牌
- 后端单元测试、前端生产构建、Docker Compose 和 Jenkins Pipeline

当前为 MVP：状态保存在单个后端进程内存，重启会清空；暂不支持全押/边池、账号系统、断线身份恢复和多实例部署。

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

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/tables` | 牌桌列表 |
| POST | `/api/tables` | 创建牌桌并入座 |
| POST | `/api/tables/{id}/join` | 加入牌桌 |
| GET | `/api/tables/{id}?playerId=...` | 获取该玩家可见状态 |
| POST | `/api/tables/{id}/start` | 开始下一局 |
| POST | `/api/tables/{id}/actions` | 执行下注动作 |
| WS | `/ws` | STOMP 连接端点 |

## 项目结构

```text
backend/    Spring Boot API、牌局状态、牌型算法、测试
frontend/   Vue 3 大厅与实时牌桌
compose.yml bridge 网络的一键部署
Jenkinsfile GitHub push 自动触发的 CI 流水线
```

本项目使用虚拟筹码，仅用于技术演示，不包含充值、提现或真钱赌博功能。
