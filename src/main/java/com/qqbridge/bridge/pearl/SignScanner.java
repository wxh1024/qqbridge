package com.qqbridge.bridge.pearl;

import net.minecraft.block.BlockState;
import net.minecraft.block.ButtonBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

/**
 * 告示牌扫描：在给定范围内找「文字完全匹配 + 下方有活板门/按钮」的目标。
 *
 * <p>纯世界读取，不碰网络、不碰状态机；扫描结果打包成 {@link ScanReport} 交给 {@link PearlTug}。
 */
public final class SignScanner {
	/** 触发块下探格数的上限，防止配置写太大把整根柱子爬完 */
	private static final int MAX_DROP_DEPTH = 8;

	/** 命中目标：告示牌坐标 + 其下方的触发方块 */
	public record Target(BlockPos sign, BlockPos trigger, String triggerName, double distance) {
	}

	/** 扫描统计，用于失败提示 */
	public record ScanReport(int signs, int matched, Target best) {
	}

	private SignScanner() {
	}

	/**
	 * 扫描以 origin 为中心、水平 ±radiusH、垂直 ±radiusV 的立方体。
	 * 只遍历已加载区块里的方块实体（客户端看不到未加载区块）。
	 */
	public static ScanReport scan(MinecraftClient client, BlockPos origin, String label,
	                              int radiusH, int radiusV, int dropDepth) {
		int rh = Math.max(1, radiusH);
		int rv = Math.max(0, radiusV);
		int depth = Math.min(MAX_DROP_DEPTH, Math.max(1, dropDepth));
		BlockPos min = origin.add(-rh, -rv, -rh);
		BlockPos max = origin.add(rh, rv, rh);

		int signs = 0;
		int matched = 0;
		Target best = null;

		for (BlockPos pos : BlockPos.iterate(min, max)) {
			BlockEntity be = client.world.getBlockEntity(pos);
			if (!(be instanceof SignBlockEntity sign)) {
				continue;
			}
			signs++;
			if (!signMatches(sign, label)) {
				continue;
			}
			matched++;

			BlockPos trigger = findTrigger(client, pos, depth);
			if (trigger == null) {
				continue;
			}
			BlockState triggerState = client.world.getBlockState(trigger);
			String name = triggerState.getBlock().getName().getString();
			Target candidate = new Target(pos, trigger, name, distance(origin, pos));
			if (best == null || nearer(candidate, best)) {
				best = candidate;
			}
		}
		return new ScanReport(signs, matched, best);
	}

	/** 告示牌任意一行文字与目标完全相等（正反面都看） */
	private static boolean signMatches(SignBlockEntity sign, String wanted) {
		return textMatches(sign.getFrontText(), wanted) || textMatches(sign.getBackText(), wanted);
	}

	private static boolean textMatches(SignText signText, String wanted) {
		if (signText == null) {
			return false;
		}
		for (Text line : signText.getMessages(false)) {
			if (line != null && plain(line.getString()).equals(wanted)) {
				return true;
			}
		}
		return false;
	}

	/** 去掉 § 颜色/格式码（部分服务端会把它们写进告示牌），再 trim */
	private static String plain(String raw) {
		if (raw == null || raw.isEmpty()) {
			return "";
		}
		StringBuilder sb = new StringBuilder(raw.length());
		for (int i = 0; i < raw.length(); i++) {
			char c = raw.charAt(i);
			if (c == '\u00a7') {
				i++; // 跳过 § 及其后的一个字符
				continue;
			}
			sb.append(c);
		}
		return sb.toString().trim();
	}

	/** 告示牌正下方 1..depth 格内的第一个活板门/按钮 */
	private static BlockPos findTrigger(MinecraftClient client, BlockPos signPos, int depth) {
		for (int dy = 1; dy <= depth; dy++) {
			BlockPos p = signPos.down(dy);
			BlockState state = client.world.getBlockState(p);
			if (state.getBlock() instanceof TrapdoorBlock || state.getBlock() instanceof ButtonBlock) {
				return p;
			}
		}
		return null;
	}

	/** 距离更近优先；距离相同则 y 小、x 小、z 小优先（保证结果确定） */
	private static boolean nearer(Target a, Target b) {
		if (a.distance() < b.distance() - 1.0e-6) {
			return true;
		}
		if (a.distance() > b.distance() + 1.0e-6) {
			return false;
		}
		BlockPos pa = a.sign();
		BlockPos pb = b.sign();
		if (pa.getY() != pb.getY()) {
			return pa.getY() < pb.getY();
		}
		if (pa.getX() != pb.getX()) {
			return pa.getX() < pb.getX();
		}
		return pa.getZ() < pb.getZ();
	}

	private static double distance(BlockPos a, BlockPos b) {
		double dx = a.getX() - b.getX();
		double dy = a.getY() - b.getY();
		double dz = a.getZ() - b.getZ();
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}
}
