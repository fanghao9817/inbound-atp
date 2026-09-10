# 交接摘要（2026-09-10 夜间自动推进）

## 现在线上有什么

- **https://demo.haoyufang.dev/** — Vue 前端（Availability / Inbound / Exceptions / Lanes 四页），HTTP 自动跳 HTTPS，Let's Encrypt 证书（到期 2026-12-09，certbot 定时续期已配）。
- **https://demo.haoyufang.dev/api/...** — Spring Boot 4.1 API（容器内运行，nginx 反代），`/health` 为健康检查。
- 服务器上 docker compose 跑着 `inbound-postgres`、`inbound-kafka`（KRaft 单节点）、`inbound-api`；总内存占用约 1.2 GB / 7.6 GB。
- dbt 已在服务器上跑过一次（`~/dbt-venv`），`analytics.lane_lead_time_stats` 有 48 条航线统计，每条 31–51 个样本。

## 代码在哪

本地 WSL：`/home/fangh/workspace/inbound-atp`（git 仓库，main 分支，尚未推到 GitHub）。服务器副本：`/home/ubuntu/inbound-atp`（用 `scripts/sync.sh` 同步）。

## 关键决策（面试时可以直接讲）

1. **数据层 SQL 优先、无 ORM**；四月那道题（inventory + purchase_orders 按 SKU 分配）原样做成 `fulfillment` 模块，`FulfillmentJdbcDao` 用纯 `java.sql`，两次查询共享一个 repeatable-read 快照；`AllocationPlanner` 是纯函数，有单元测试。
2. **ATP 取前瞻最小值**，避免把后面已承诺的库存提前许诺出去（`AtpCalculator`，6 个单元测试）。
3. **预测用 P80 而不是均值**，置信度随样本量和阶段提升；无历史时回退承运商计划并在 `prediction_basis` 里说明。
4. **里程碑 → Kafka → 重算** 全链路幂等：消费者从数据库状态重算而不是信事件内容；`event_id` 去重；阶段只进不退；事件在事务提交后发布。
5. **dbt 拥有 analytics schema，API 只读**；Flyway 建契约表的形状，dbt 填内容。

测试：`mvn verify` 19 个测试全绿（含 Testcontainers 真 Postgres + 真 Kafka 的端到端）；dbt 12 个 build 步骤全过。

## 你醒来后要做的

1. ~~域名~~ 已完成：https://demo.haoyufang.dev/（Cloudflare DNS only → Let's Encrypt，自动续期）。
2. **AWS Free Plan 风险（重要）**：账户是新版 Free account plan，2026-09-10 余额 $100。Lightsail 8 GB 每月 $44 → **约 11 月初用完**，届时 AWS 会关闭账户（保留 90 天）。**10 月中之前**升级到 Paid plan（不收费，Always Free 与余额保留）。DynamoDB/Lambda/CloudFormation 这部分在 Always Free 内，不消耗 credits；已设 $5 月度预算告警。
3. ~~AWS 托管服务~~ 已完成（2026-09-10 下午）：
   - 两个 CloudFormation 栈（us-west-2）：`inbound-atp-bootstrap`（制品桶、GitHub OIDC provider、受限部署角色 `inbound-atp-github-deploy`）和 `inbound-atp`（SAM：DynamoDB `inbound-atp-availability` provisioned 5/5、Lambda `inbound-atp-projector` 与 `inbound-atp-availability-api` + Function URL、7 天日志、只能调用投影 Lambda 的 IAM 用户 `inbound-atp-box`）。
   - 服务器 `infra/.env` 里有 box 用户的密钥与 Function URL；`~/.aws/credentials` 里 `deployer`（你的管理员密钥）和 `box` 两个 profile。
   - GitHub Actions 通过 OIDC 部署已在 `deploy #3` 验证成功（GitHub 的 subject 格式是 `repo:owner@id/repo@id:...`，信任策略已按此钉死 ID）。**`inbound-atp-deployer` 的访问密钥已无用途，建议删除**；将来只有改 bootstrap 栈时才临时再建一把。
   - Databricks：`~/.config/inbound-atp/databricks.env`（token 90 天，2026-12-09 前后过期，到时在 Databricks 重新生成并更新该文件）。同一套 dbt 模型在 Databricks 上跑通，结果表 `workspace.analytics.lane_lead_time_stats`。
4. ~~GitHub~~ 已完成：https://github.com/fanghao9817/inbound-atp ，`ci #1` 三个 job 全绿，`deploy #1` 自动发布成功；部署专用密钥在本地 `~/.ssh/inbound-atp-deploy`（公钥已在服务器）。
5. **浏览器里点一遍**四个页面；我只从命令行验证了路由和资源，没有跑真实浏览器。Inbound 页选一个集装箱、Post 一个里程碑，能看到预测在半秒内通过 Kafka 更新。

## 还没做 / 有意留白

- 前端没有单元测试（Vitest 已配好但没写用例）；README 的 JD 覆盖矩阵等 AWS 部分接上后再补完整。
- 没有 Kafka UI / Prometheus / Grafana（8 GB 装得下，看你要不要）。
- 事务性 outbox 只在 README 里写为下一步。
