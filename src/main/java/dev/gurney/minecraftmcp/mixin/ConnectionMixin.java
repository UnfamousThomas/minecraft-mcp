package dev.gurney.minecraftmcp.mixin;

import dev.gurney.minecraftmcp.game.PacketRecorder;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
abstract class ConnectionMixin {
	@Inject(method = "channelRead0", at = @At("HEAD"))
	private void minecraftMcp$recordInbound(ChannelHandlerContext ctx, Packet<?> packet, CallbackInfo ci) {
		PacketRecorder.record("S->C", packet);
	}

	@Inject(method = "sendPacket", at = @At("HEAD"))
	private void minecraftMcp$recordOutbound(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
		PacketRecorder.record("C->S", packet);
	}
}
