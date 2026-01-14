# ✅ 最终架构概览

## VPS（KopC）

- **PostgreSQL（docker）**
  - 容器端口：`5432`
  - 宿主机对外：`0.0.0.0:27419 -> 5432`
  - 日志落盘：docker volume 里 `pglog`（Fail2ban 监控这个）
- **Redis（docker）**
  - 容器端口：`6379`
  - 宿主机仅本机：`127.0.0.1:6379 -> 6379`（不对外暴露）

## 本地 Mac

- 通过 **autossh 常驻隧道**
  - `localhost:5432 -> VPS 127.0.0.1:27419`（转发 Postgres）
  - `localhost:6379 -> VPS 127.0.0.1:6379`（转发 Redis）
- 你的本地后端配置可以长期写：
  - Postgres：`localhost:5432`
  - Redis：`localhost:6379`

------

# 1️⃣ 目录与关键文件位置（非常重要）

## VPS 目录

- compose 项目目录（你一直在用的）：
  - **`/opt/stacks/datastores/`**
- 环境变量：
  - **`/opt/stacks/datastores/.env`**
- docker compose 文件：
  - **`/opt/stacks/datastores/compose.yml`**（或 `docker-compose.yml`，以你实际为准）

## Postgres 日志路径（Fail2ban 依赖它）

- **`/var/lib/docker/volumes/datastores_pgdata/_data/pglog/`**
  - 每日文件：`postgresql-YYYY-MM-DD.log`

## Fail2ban 配置路径

- jail 配置（你最终稳定的）：
  - **`/etc/fail2ban/jail.d/postgres.local`**
- filter 配置（你最终稳定的）：
  - **`/etc/fail2ban/filter.d/postgres-auth.conf`**
- Fail2ban 服务日志（排错常用）：
  - **`/var/log/fail2ban.log`**
- nft 封禁集合（实际生效位置）：
  - `inet f2b-table addr-set-postgres-auth`

------

# 2️⃣ docker 相关常用命令（VPS）

进入项目目录：

```
cd /opt/stacks/datastores
```

启动/更新：

```
docker compose up -d
```

查看状态：

```
docker compose ps
docker ps
```

看端口监听是否正确：

```
ss -lntp | egrep '(:27419|:6379)'
```

进入 Postgres：

```
docker exec -it postgres psql -U dbadmin -d dripswap
```

退出 psql：

- 如果在 `dripswap-#`（多行模式）：输入 `;` 回车 或 `Ctrl+C`
- 退出：`\q` 回车（或 `Ctrl+D`）

------

# 3️⃣ Fail2ban：自动封禁 Postgres 暴力破解

## 你的最终 jail 配置（已确认跨天稳定）

文件：`/etc/fail2ban/jail.d/postgres.local`

```
[postgres-auth]
enabled  = true
filter   = postgres-auth
port     = 27419
logpath  = /var/lib/docker/volumes/datastores_pgdata/_data/pglog/postgresql-*.log
maxretry = 3
findtime = 10m
bantime  = 24h
banaction = nftables-multiport
```

- 含义：
  - **10 分钟内失败 3 次 → 封 24 小时**
  - 跨天靠 `postgresql-*.log` 通配符保证不断档

## 最终 filter（匹配你实际日志格式，稳定）

文件：`/etc/fail2ban/filter.d/postgres-auth.conf`

```
[Definition]
failregex = ^.*\]\s+<HOST>\s+\S+\s+\S+\s+FATAL:\s+password authentication failed for user ".*".*$
ignoreregex =
```

## 常用查看/调试

看 jail 状态与封禁 IP：

```
sudo fail2ban-client status postgres-auth
```

看 nft 封禁集合：

```
sudo nft list set inet f2b-table addr-set-postgres-auth
```

看 Fail2ban 服务是否正常：

```
sudo systemctl status fail2ban --no-pager -l
```

### 解封（单个 IP）

```
sudo fail2ban-client set postgres-auth unbanip <IP>
```

### 解封全部

```
sudo fail2ban-client set postgres-auth unbanip --all
```

> 注意：你之前没生效的原因就是 `--all` 写成了 `-all`，以及 IP 写错（280 vs 208）。

### 验证 regex 是否命中（离线验收）

抽一条 FATAL 行：

```
sudo grep "FATAL:  password authentication failed" \
  /var/lib/docker/volumes/datastores_pgdata/_data/pglog/postgresql-$(date +%Y-%m-%d).log | tail -n 1 \
  | sudo tee /tmp/pg-fatal-one.log > /dev/null
```

验证：

```
sudo fail2ban-regex /tmp/pg-fatal-one.log /etc/fail2ban/filter.d/postgres-auth.conf
```

------

# 4️⃣ 本地 Mac：autossh 常驻隧道（保持 localhost 不变）

## 启动（前台，终端会一直挂着是正常的）

```
autossh -M 0 -N \
  -o "ServerAliveInterval=30" \
  -o "ServerAliveCountMax=3" \
  -L 5432:127.0.0.1:27419 \
  -L 6379:127.0.0.1:6379 \
  admin@vps
```

### 为什么“卡住”是正常的？

因为 `-N` 不执行命令，只维护隧道；它必须一直运行，转发才存在。

## 验证隧道是否生效（一定要在 Mac 上跑）

```
nc -vz 127.0.0.1 5432
nc -vz 127.0.0.1 6379
```

如果本地 `5432` 被占用（比如你本机装过 Postgres），就换本地端口：

- 本地 `15432 -> VPS 27419`：

```
autossh -M 0 -N \
  -L 15432:127.0.0.1:27419 \
  -L 6379:127.0.0.1:6379 \
  admin@vps
```

本地就改连 `localhost:15432`。

## 关闭 autossh（两种方式）

### 方式 A：前台运行时直接

- 在 autossh 那个终端 `Ctrl+C`

### 方式 B：后台运行时杀进程

找进程：

```
ps aux | grep autossh | grep -v grep
```

结束：

```
kill <PID>
```

（必要时 `kill -9 <PID>`）

## SSH key 每次问 passphrase 的处理

推荐用 ssh-agent / Keychain 缓存：

```
ssh-add --apple-use-keychain ~/.ssh/id_ed25519
```

并确保 `~/.ssh/config` 有：

```
Host *
  AddKeysToAgent yes
  UseKeychain yes
  IdentityFile ~/.ssh/id_ed25519
```

------

# 5️⃣ 数据库权限策略（Goldsky + App 都需要）

你已执行过：

- 创建两个用户：`${APP_DB_USER}`、`${GOLDSKY_DB_USER}`
- 两个 schema：`app`、`goldsky`
- 设置 search_path

如果 App 需要读取 Goldsky 生成的表：**必须补默认权限**（否则未来新建表 app 读不到）
 你最终建议保持的思想是：

- goldsky 只在 `goldsky` schema 建表
- app 需要 `USAGE` schema + 读写/只读按你需求授予
- 用 `ALTER DEFAULT PRIVILEGES` 让未来表自动授权

------

# 6️⃣ 常见问题与一键排错清单

## A) 外网能 nc，但 psql 报 “server closed unexpectedly”

优先看：

1. VPS 是否收到连接日志（pglog 里 host=你的公网IP）
2. fail2ban 是否误封
3. docker 端口映射是否正确 `ss -lntp | grep 27419`

## B) Fail2ban 突然“又不封了”

99% 是：

- `logpath` 没用通配符（跨天后监控旧文件）
- filter 正则太死，日志格式稍变就不匹配

你现在用的 `logpath=postgresql-*.log` + 宽松 filter 已经避免了这类问题。

## C) autossh 开着但本地 5432 refused

- 你可能在 VPS 上测试了（应该在 Mac 上测试）
- 或本地 5432 被占用（用 `lsof -iTCP:5432 -sTCP:LISTEN` 查）
- 或 ssh 没真正监听到本地端口（用 `ssh -vvv -N ...` 看 “Local forwarding listening”）

------

# ✅ 你当前最终状态（你可以记一句话）

- VPS：postgres 对外 `27419`，redis 仅本机 `6379`
- Fail2ban：10 分钟 3 次失败 → 封 24 小时（nft 生效），跨天稳定
- 本地：autossh 常驻后，后端永远连 `localhost:5432/6379`

------

如果你愿意，我还可以帮你做一个**“一键启动/停止隧道”脚本**（start/stop/status 三个命令），放到 `~/.zshrc` 里作为 alias，这样以后你不会再忘记怎么关 autossh。你想用 alias 还是独立脚本（比如 `~/bin/db-tunnel`）？

autossh 给出nohup的命令

下面是你这套转发（Postgres 5432→VPS 27419，Redis 6379→VPS 6379）的 **nohup 后台常驻 autossh 命令**，以及停止/查看方法。

## 1) 后台启动（nohup）

在 **Mac** 运行：

```
nohup autossh -M 0 -N \
  -o "ServerAliveInterval=30" \
  -o "ServerAliveCountMax=3" \
  -o "ExitOnForwardFailure=yes" \
  -L 5432:127.0.0.1:27419 \
  -L 6379:127.0.0.1:6379 \
  admin@vps \
  >/tmp/autossh-db.log 2>&1 &
```

- 日志会写到：`/tmp/autossh-db.log`
- `ExitOnForwardFailure=yes` 很关键：如果端口转发没真正建立，它会直接退出（避免“假连接”）

> 如果你本地 5432 被占用，就把本地端口换成 15432：
>  `-L 15432:127.0.0.1:27419`（然后你的本地后端连 15432）

------

## 2) 查看是否在跑

```
ps aux | grep autossh | grep -v grep
```

或看本地端口是否已监听（更直观）：

```
lsof -nP -iTCP:5432 -sTCP:LISTEN
lsof -nP -iTCP:6379 -sTCP:LISTEN
```

------

## 3) 停止（关闭后台 autossh）

最简单一条（会杀掉包含这组转发的 autossh）：

```
pkill -f 'autossh.*-L 5432:127\.0\.0\.1:27419.*-L 6379:127\.0\.0\.1:6379'
```

如果你想手动 kill：

```
ps aux | grep autossh | grep -v grep
kill <PID>
```

------

如果你愿意，我也可以把这三条（start/status/stop）封装成 `alias` 或一个 `db-tunnel` 小脚本，之后一行命令就能开关。**admin@KopC**:**/opt/stacks/datastores**$ vim .env



\# Postgres admin（仅用于初始化建库建用户）

DB_ADMIN_USER=dbadmin

DB_ADMIN_PASSWORD=G8ZXMvoMtRXhF2xXmYaTur8334hqvqSjIaCo31X/4S8=



\# 业务库 + 业务用户（给你本地后端用）

APP_DB=appdb

APP_DB_USER=app

APP_DB_PASSWORD=NcAyrFgznXjmYiSDd9PPQqxV9sQGzRQgJ0EFIujjZec=



\# Goldsky 专用用户（用于 sink 自动建表）

GOLDSKY_DB_USER=goldsky

GOLDSKY_DB_PASSWORD=vrL9fyfguq0gIvffpTyD72IgwricEQkufsgPcOBuc5Y=



\# Redis

REDIS_PASSWORD=itvoIpIdzJQfV1blVeurueSEu2CGBaNm6e9bxp7n+R4=



TZ=Asia/Shanghai



datasource-graph:  graphql:  subgraph:   dripswap:chains: