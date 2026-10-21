package com.qqbridge.im;

/**
 * IM 平台客户端抽象——Minecraft 侧与具体平台的唯一接口。
 *
 * <p>桥接业务层只依赖本接口与 {@link InboundMessage}/{@link SendResult}/{@link MessageKind}，
 * 不 import 任何 QQ 实现类。新增一种平台（如 Telegram / Discord）只需再写一个
 * {@code ImClient} 实现并在组合根里选择它，业务层零改动。
 */
public interface ImClient {
	/** 凭证是否已配置好（未配置时 start 应直接跳过、不空转） */
	boolean isConfigured();

	/** 启动连接，收到入站消息时回调 listener */
	void start(ImListener listener);

	/** 停机：关闭连接、释放线程 */
	void shutdown();

	/**
	 * 发一条消息到指定群/频道。
	 *
	 * @param channelId   目标群/频道 id
	 * @param kind        落地格式（纯文本 / Markdown）
	 * @param content     已渲染好的正文
	 * @param replyToMsgId 非空 = 作为对该消息的回复（被动回复）；空 = 主动推送
	 * @return 发送结果，供出站队列按类别决定 重试/换通道/丢弃
	 */
	SendResult send(String channelId, MessageKind kind, String content, String replyToMsgId);
}
