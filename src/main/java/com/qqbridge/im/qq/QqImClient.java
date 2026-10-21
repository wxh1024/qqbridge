package com.qqbridge.im.qq;

import com.qqbridge.config.QqBridgeConfig;
import com.qqbridge.im.ImClient;
import com.qqbridge.im.ImListener;
import com.qqbridge.im.MessageKind;
import com.qqbridge.im.SendResult;

/**
 * QQ 官方机器人的 {@link ImClient} 实现——把网关（收）与 REST（发）拼成统一的平台客户端。
 *
 * <p>未配置凭证时 {@link #start} 直接跳过，避免网关空转重连（原实现里未配置也会不停
 * 拉 token 失败、指数退避死循环）。
 */
public final class QqImClient implements ImClient {
	private final QqBridgeConfig config;
	private final QqAuth auth;
	private final QqApi api;
	private volatile QqGateway gateway;

	public QqImClient(QqBridgeConfig config, QqAuth auth, QqApi api) {
		this.config = config;
		this.auth = auth;
		this.api = api;
	}

	@Override
	public boolean isConfigured() {
		return config.isConfigured();
	}

	@Override
	public void start(ImListener listener) {
		if (!config.isConfigured()) {
			// 未配置就不空转重连；用户提示由入口（QqBridgeClient）统一打日志
			return;
		}
		gateway = new QqGateway(config, auth, api, listener);
		gateway.start();
	}

	@Override
	public void shutdown() {
		QqGateway g = gateway;
		if (g != null) {
			g.shutdown();
		}
	}

	@Override
	public SendResult send(String channelId, MessageKind kind, String content, String replyToMsgId) {
		if (kind == MessageKind.MARKDOWN) {
			return api.sendGroupMarkdown(channelId, content, replyToMsgId);
		}
		return api.sendGroupText(channelId, content, replyToMsgId);
	}
}
