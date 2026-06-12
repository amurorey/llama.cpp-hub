package org.mark.llamacpp.server.io;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import org.slf4j.Logger;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.ReferenceCountUtil;

/**
 * Helper methods for streaming writes from worker threads.
 */
public final class NettyWriteHelper {

	private static final long WRITABLE_POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(10);

	private NettyWriteHelper() {
	}

	public static boolean waitUntilWritable(ChannelHandlerContext ctx, Logger logger, String logPrefix) {
		if (ctx == null || ctx.channel() == null) {
			return false;
		}
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (ctx.channel().isActive()) {
			if (ctx.channel().isWritable()) {
				return true;
			}
			if (Thread.currentThread().isInterrupted()) {
				Thread.currentThread().interrupt();
				if (logger != null) {
					logger.info("{} 写出线程被中断，停止响应发送", logPrefix);
				}
				return false;
			}
			long remaining = deadline - System.nanoTime();
			if (remaining <= 0) {
				if (logger != null) {
					logger.info("{} 客户端 10 秒未消费数据，判定为异常，停止响应发送", logPrefix);
				}
				return false;
			}
			LockSupport.parkNanos(Math.min(WRITABLE_POLL_NANOS, remaining));
		}
		if (logger != null) {
			logger.info("{} 客户端连接已断开，停止响应发送", logPrefix);
		}
		return false;
	}

	public static boolean writeAndFlushBlocking(ChannelHandlerContext ctx, Object msg, Logger logger, String logPrefix) {
		if (!waitUntilWritable(ctx, logger, logPrefix)) {
			ReferenceCountUtil.release(msg);
			return false;
		}
		ChannelFuture future = ctx.writeAndFlush(msg);
		future.awaitUninterruptibly();
		if (future.isSuccess()) {
			return true;
		}
		if (logger != null) {
			Throwable cause = future.cause();
			logger.info("{} 写出失败: {}", logPrefix, cause == null ? "unknown" : cause.getMessage());
		}
		return false;
	}
}
