package com.qqbridge.bridge;

import com.qqbridge.config.QqBridgeConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 玩家进入视野提醒。
 *
 * 客户端世界里的玩家集合 ≈ 渲染距离内被追踪到的玩家（即「视野距离内」的工程近似）。
 * 每 tick diff 一次：新面孔 → 推 QQ；消失 → 从已见集合移除（再次进入会重新提醒）。
 * 同一玩家带 {@code playerEnterCooldownSeconds} 冷却，防边界来回横跳刷屏。
 */
public final class ProximityAlert {
	private final QqBridgeConfig config;
	private final ChatRelay relay;
	private final Map<UUID, String> seen = new HashMap<>();
	private final Map<UUID, Long> lastAlertAt = new HashMap<>();

	public ProximityAlert(QqBridgeConfig config, ChatRelay relay) {
		this.config = config;
		this.relay = relay;
	}

	public void tick(Minecraft client) {
		if (client.level == null || client.player == null) {
			seen.clear();
			return;
		}
		if (!config.notifyPlayerEnter) {
			seen.clear();
			return;
		}

		Map<UUID, String> now = new HashMap<>();
		for (Player p : client.level.players()) {
			if (p == client.player) {
				continue; // 排除自己
			}
			UUID id = p.getUUID();
			if (id.equals(client.player.getUUID())) {
				continue;
			}
			now.put(id, p.getName().getString());
		}

		long cooldownMs = Math.max(0, config.playerEnterCooldownSeconds) * 1000L;
		long nowMs = System.currentTimeMillis();
		for (Map.Entry<UUID, String> e : now.entrySet()) {
			if (seen.containsKey(e.getKey())) {
				continue;
			}
			long last = lastAlertAt.getOrDefault(e.getKey(), 0L);
			if (nowMs - last < cooldownMs) {
				continue;
			}
			lastAlertAt.put(e.getKey(), nowMs);
			relay.pushSystem(e.getValue(), "");
		}

		seen.clear();
		seen.putAll(now);
	}
}
