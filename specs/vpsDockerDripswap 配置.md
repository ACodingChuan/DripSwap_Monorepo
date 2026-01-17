下面是你现在 **VPS（KopC）最新落地配置的“运维备忘录”汇总**（按当前实际状态写，含端口、目录、compose、fail2ban、nftables、排错点）。

------

# ✅ 1. 服务与端口总览（当前生产态）

## PostgreSQL（docker）

- 容器：`postgres`
- 容器端口：`5432`
- 宿主机对外端口：**`0.0.0.0:27419 -> 5432`**
- 用途：Goldsky sink + 本地/工具连接
- 认证失败防护：Fail2ban（nftables）✅

验证：

```
docker ps
ss -lntp | grep 27419
```

## Redis（docker）

- 容器：`redis`
- 容器端口：`6379`
- 宿主机对外端口：**`0.0.0.0:24918 -> 6379`**
- Redis 已启用密码（requirepass），默认用户 `default`
- 认证失败防护：Fail2ban（nftables）🚧（当前已能识别失败次数，等待触发 ban 后 nft 规则/集合会生成）

验证：

```
docker ps
ss -lntp | grep 24918
```

------

# ✅ 2. 目录与关键文件路径（你最容易忘的）

## Compose 项目目录

- **`/opt/stacks/datastores/`**
  - `.env`：**`/opt/stacks/datastores/.env`**
  - compose：`/opt/stacks/datastores/compose.yml`（或 docker-compose.yml，以你实际文件名为准）
  - Redis 配置目录：**`/opt/stacks/datastores/redis/`**
    - `redis.conf`：`/opt/stacks/datastores/redis/redis.conf`
    - ACL 导出脚本：`/opt/stacks/datastores/redis/dump-acl-log.sh`

## Docker volumes（数据与日志落盘）

### Postgres 数据/日志

- Volume：`datastores_pgdata`
- 日志目录（Fail2ban 监控）：
   **`/var/lib/docker/volumes/datastores_pgdata/_data/pglog/`**
  - 文件名：`postgresql-YYYY-MM-DD.log`

### Redis 数据/日志

- Volume：`datastores_redisdata`
- Redis logfile：
   **`/var/lib/docker/volumes/datastores_redisdata/_data/redis.log`**
- Redis ACL 落盘文件（Fail2ban 监控用）：
   **`/var/lib/docker/volumes/datastores_redisdata/_data/redis-acl.log`**

------

# ✅ 3. Docker 现状（你贴过的最新结果）

你现在容器端口映射是：

- Postgres：`0.0.0.0:27419->5432/tcp`
- Redis：`0.0.0.0:24918->6379/tcp`

查看命令：

```
cd /opt/stacks/datastores
docker compose up -d
docker compose ps
docker ps
```

------

# ✅ 4. Fail2ban 配置总览（目前 VPS 上的安全层）

## Postgres（已完全闭环）

- jail：`postgres-auth`
- 配置文件：`/etc/fail2ban/jail.d/postgres.local`
- filter：`/etc/fail2ban/filter.d/postgres-auth.conf`
- 监控日志：
  - `.../pglog/postgresql-*.log`（通配符跨天稳定）
- 规则：
  - `findtime=10m`
  - `maxretry=3`
  - `bantime=24h`
  - `banaction=nftables-multiport`
- nft 生效位置：
  - `table inet f2b-table`
  - `set addr-set-postgres-auth`

常用命令：

```
sudo fail2ban-client status postgres-auth
sudo nft list set inet f2b-table addr-set-postgres-auth
sudo fail2ban-client set postgres-auth unbanip <IP>
sudo fail2ban-client set postgres-auth unbanip --all
```

------

## Redis（当前方案：通过 ACL LOG 落盘→Fail2ban）

你现在 Redis 的认证失败 **不会写入 redis.log**，但会进入 Redis 的 `ACL LOG`。
 所以你采用的机制是：

1. 用脚本把 `ACL LOG` 导出到：`redis-acl.log`
2. Fail2ban 监控 `redis-acl.log`，从 `addr=<ip>:port` 抽出 IP
3. 3 次失败后 ban（nftables）

配置文件：

- jail：`/etc/fail2ban/jail.d/redis.local`
- filter：`/etc/fail2ban/filter.d/redis-auth.conf`
- logpath：`/var/lib/docker/volumes/datastores_redisdata/_data/redis-acl.log`

当前状态（你贴的）：

- `Total failed: 1`（说明已经能命中）
- `Currently banned: 0`（还没到 3 次，nft set 也还没创建，所以 `nft list set ...` 会显示不存在，这是正常现象）

查看命令：

```
sudo fail2ban-client status redis-auth
sudo nft list table inet f2b-table
```

------

# ✅ 5. Redis ACL 导出脚本 + 定时任务（你现在的“关键组件”）

脚本（宿主机）：

- **`/opt/stacks/datastores/redis/dump-acl-log.sh`**

手动运行（需要带上 REDIS_PASSWORD）：

```
set -a
source /opt/stacks/datastores/.env
set +a
sudo -E /opt/stacks/datastores/redis/dump-acl-log.sh
```

如果你已经加了 cron（每分钟导出一次），检查：

```
sudo crontab -l
```

------

# ✅ 6. 常用验收/排错清单（以后最快定位）

## 端口是否对外监听

```
ss -lntp | egrep '(:27419|:24918)'
```

## 外网连通性（从客户端）

```
nc -vz <VPS_IP> 27419
nc -vz <VPS_IP> 24918
```

## Postgres 是否收到外部连接

```
sudo grep -n "connection received" /var/lib/docker/volumes/datastores_pgdata/_data/pglog/postgresql-*.log | tail
```

## Redis 是否启用密码

```
set -a; source /opt/stacks/datastores/.env; set +a
docker exec -e REDISCLI_AUTH="$REDIS_PASSWORD" -it redis redis-cli ping
# 期望 PONG
```

## Fail2ban 是否正常运行

```
sudo systemctl status fail2ban --no-pager -l
sudo tail -n 120 /var/log/fail2ban.log
```

------

# ✅ 7. 当前“未完成/待确认”的唯一点（Redis 的 ban 触发）

你现在 Redis jail 已经能计数（failed=1），下一步只要让它在 10 分钟内命中 3 次并导出 ACL log，就会：

- `Currently banned` 变为 1
- `nft table inet f2b-table` 下出现 redis-auth 对应的 set/规则（届时 `nft list ...` 才查得到）

------

如果你希望我把 Redis 这一段也做到“像 Postgres 一样可直接用固定 set 名称查询”，我可以让你跑一次 **3 次错误认证 + 手动导出脚本**，然后我根据你实际生成的 nft 规则把“固定查询命令”也写进备忘录里。



\# Postgres admin（仅用于初始化建库建用户）

DB_ADMIN_USER=dbadmin

DB_ADMIN_PASSWORD=G8ZXMvoMtRXhF2xXmYaTur8334hqvqSjIaCo31X/4S8=



\# 业务库 + 业务用户（给你本地后端用）

APP_DB=dripswap

APP_DB_USER=app

APP_DB_PASSWORD=NcAyrFgznXjmYiSDd9PPQqxV9sQGzRQgJ0EFIujjZec=



\# Goldsky 专用用户（用于 sink 自动建表）

GOLDSKY_DB_USER=goldsky

GOLDSKY_DB_PASSWORD=vrL9fyfguq0gIvffpTyD72IgwricEQkufsgPcOBuc5Y=



\# Redis

REDIS_PASSWORD=itvoIpIdzJQfV1blVeurueSEu2CGBaNm6e9bxp7n+R4=



TZ=Asia/Shanghai



datasource-graph:  graphql:  subgraph:   dripswap:chains:

