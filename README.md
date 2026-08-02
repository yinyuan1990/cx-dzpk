# cx-dzpk — 德州扑克子游戏服务(独立微服务)

扯旋平台的德州扑克子游戏,**独立进程部署**,不依赖扯旋主服(chexuan-springboot)运行;
玩法核心(比牌规则)移植自老德州(hsdz room BiPai),结算重写为**循环玩法**(对齐扯旋周期结算模型)。

## 玩法模型(循环结算)

- 创建房间选 **结算时间**(30/45/60 分钟可配)与盲注/抽水比例,**到点不散桌**
- 牌局一手接一手循环;玩家从参与发牌起累计游戏时间(墙钟,含局间)
- 每手结束检查:累计时间 ≥ 结算时间 → 触发该玩家**周期结算**:
  盈利抽水 → 剩余筹码退回钱包 → 桌面清零 → 限时补带入(重新带入开新周期,超时自动站起)
- 战绩按周期分段(settlePeriodSeq)

## 技术栈

- Spring Boot 3.2 / Java 17 / WebSocket(JSON)
- 与主服共享 JWT 密钥本地验签(不回主服校验);开发模式支持游客登录
- 房间任务串行化:StripedExecutor(同房串行、异房并行),移植自扯旋

## 快速开始

```bash
mvn spring-boot:run        # 端口 9100
# 浏览器打开 http://127.0.0.1:9100/  内置联调页(建房/坐下/带入/打牌)
mvn test                   # 牌型21 + 边池 + 牌局流程集成测试
python _ws_smoke.py        # WS 全链路冒烟(两游客自动打完一手)
```

## 协议

WS 端点 `ws://host:9100/ws/dzpk`,信封 `{type, roomId, sequence, data, timestamp}`(与扯旋 GameMessage 同构),
命令号独立 **4xx 段**:C→S 401登录/403建房/404进房/406坐下/407带入/408站起/409行动/410快照;
S→C 451~469(458开局/459手牌私发/460轮到行动/462发公共牌/463摊牌/464一手结算/468周期结算),错误 499。
详见 `src/main/java/com/chexuan/dzpk/ws/MsgType.java`。

## 目录

```
game/card      Card/Deck/BiPai(老德州比牌逐行移植)/HandResult
game/engine    Pot/PotManager(标准边池)
game/model     DzRoom/DzPlayer/GameStage/ActionType
game/service   DzGameService(牌局引擎)/DzRoomManager/RoomWorkerService/WalletService(内存桩)
ws             DzWebSocketHandler/WsSessionRegistry/GameMessage/MsgType
auth           JwtVerifier
```

## 机器人(陪打测试)

- 真人坐下带入后自动进场补位(`dzpk.robot.fill-count`,默认 2 个),真人全部离开自动撤场
- 按牌力启发式行动(起手牌强度 + 翻牌后成牌程度,带 0.8~2.5s 拟真思考延迟)
- 周期结算/打光后自动补带入;与真人走完全相同的引擎 API 和消息协议,不开后门
- 配置见 `application.yml` 的 `dzpk.robot.*`,联调页坐下带入即可看到机器人进场

## 架构决策:俱乐部与货币

- **俱乐部按子游戏独立**:德州有自己的俱乐部体系(建部/成员/积分),不复用扯旋的俱乐部表;
  每个后续子游戏同理,各管各的俱乐部
- **钻石平台公用**:开房扣钻等走主服共享钻石接口,是唯一跨游戏的货币

## 待接入(桩)

- WalletService 为内存钱包,后续替换为德州自有俱乐部积分(独立表)
- 钻石扣费对接主服共享钻石 internal 接口
- 德州俱乐部体系(建部/加入/成员管理)、战绩落库

前端(Pixi)仓库:cx-dzpk-pixi
