package com.qqbridge;

import com.qqbridge.bridge.ImIngress;
import com.qqbridge.bridge.ProximityAlert;
import com.qqbridge.bridge.chat.ChatPushSwitch;
import com.qqbridge.bridge.chat.ChatRelay;
import com.qqbridge.bridge.pearl.PearlTug;
import com.qqbridge.config.QqBridgeConfig;
import com.qqbridge.im.ImClient;
import com.qqbridge.im.qq.QqApi;
import com.qqbridge.im.qq.QqAuth;
import com.qqbridge.im.qq.QqImClient;
import com.qqbridge.shared.MessageFormatter;

/**
 * 组合根（composition root）：集中装配所有服务，明确依赖方向。
 *
 * <p>依赖方向自上而下：入口 → 本类 → bridge（业务）/ im（平台抽象）→ shared。
 * 平台目前只有 QQ 一种实现，换新平台时只需在 {@link #create()} 里替换 {@link ImClient} 的实现。
 */
public final class BridgeContext {
	public final QqBridgeConfig config;
	public final MessageFormatter formatter;
	public final ImClient im;
	public final ChatRelay chatRelay;
	public final PearlTug pearlTug;
	public final ChatPushSwitch chatPushSwitch;
	public final ProximityAlert proximity;
	public final ImIngress ingress;

	private BridgeContext(QqBridgeConfig config, MessageFormatter formatter, ImClient im,
	                      ChatRelay chatRelay, PearlTug pearlTug, ChatPushSwitch chatPushSwitch,
	                      ProximityAlert proximity, ImIngress ingress) {
		this.config = config;
		this.formatter = formatter;
		this.im = im;
		this.chatRelay = chatRelay;
		this.pearlTug = pearlTug;
		this.chatPushSwitch = chatPushSwitch;
		this.proximity = proximity;
		this.ingress = ingress;
	}

	public static BridgeContext create() {
		QqBridgeConfig config = QqBridgeConfig.load();
		MessageFormatter formatter = new MessageFormatter(config.markdownToQq);

		QqAuth auth = new QqAuth(config);
		QqApi api = new QqApi(config, auth);
		ImClient im = new QqImClient(config, auth, api);

		ChatRelay chatRelay = new ChatRelay(config, formatter, im);
		PearlTug pearlTug = new PearlTug(config, chatRelay);
		ChatPushSwitch chatPushSwitch = new ChatPushSwitch(config, chatRelay);
		ProximityAlert proximity = new ProximityAlert(config, chatRelay);
		ImIngress ingress = new ImIngress(config, chatRelay, pearlTug, chatPushSwitch);

		return new BridgeContext(config, formatter, im, chatRelay, pearlTug, chatPushSwitch, proximity, ingress);
	}

	/** 启动平台连接（入站消息经 {@link #ingress} 回流） */
	public void start() {
		im.start(ingress);
	}

	/** 停机清理 */
	public void shutdown() {
		im.shutdown();
	}
}
