package ac.boar.protocol;

import ac.boar.anticheat.player.BoarPlayer;
import ac.boar.anticheat.util.BoarLog;
import ac.boar.protocol.api.CloudburstPacketEvent;
import ac.boar.protocol.api.PacketListener;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageCodec;
import lombok.RequiredArgsConstructor;
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper;
import org.cloudburstmc.protocol.bedrock.netty.codec.packet.BedrockPacketCodec;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;

import java.util.List;

@RequiredArgsConstructor
public class BoarHandlerAdaptor extends MessageToMessageCodec<BedrockPacketWrapper, BedrockPacketWrapper> {
    private final BoarPlayer player;
    private final BedrockPacketCodec codec;

    public static final String NAME = "boar-packet-handler";

    @Override
    protected void encode(ChannelHandlerContext ctx, BedrockPacketWrapper msg, List<Object> out) {
        if (player.isClosed()) {
            return;
        }

        final CloudburstPacketEvent event = new CloudburstPacketEvent(this.player, msg.getPacket());
        try {
            for (final PacketListener listener : PacketEvents.getApi().getListeners()) {
                listener.onPacketSend(event);
            }
        } catch (Exception exception) {
            BoarLog.severe("packet-send", "Listener failed while sending " + packetName(msg.getPacket()), exception);
        }

        if (event.isCancelled()) {
            return;
        }

        msg.setPacketBuffer(null);

        ByteBuf buf = ctx.alloc().buffer(128);
        try {
            BedrockPacket packet = event.getPacket();
            msg.setPacketId(this.codec.getPacketId(packet));
            this.codec.encodeHeader(buf, msg);
            this.codec.getCodec().tryEncode(this.codec.getHelper(), buf, packet);

            msg.setPacketBuffer(buf.retain());
            out.add(msg.retain());
        } catch (Exception exception) {
            BoarLog.severe("packet-send", "Failed to encode " + packetName(event.getPacket()), exception);
            ctx.fireExceptionCaught(exception);
        } finally {
            buf.release();
        }

        event.getPostTasks().forEach(Runnable::run);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, BedrockPacketWrapper msg, List<Object> out) {
        if (player.isClosed()) {
            return;
        }

        final CloudburstPacketEvent event = new CloudburstPacketEvent(this.player, msg.getPacket());
        try {
            for (final PacketListener listener : PacketEvents.getApi().getListeners()) {
                listener.onPacketReceived(event);
            }
        } catch (Exception exception) {
            BoarLog.severe("packet-receive", "Listener failed while receiving " + packetName(msg.getPacket()), exception);
        }

        if (event.isCancelled()) {
            return;
        }

        msg.setPacket(event.getPacket());
        out.add(msg.retain());

        event.getPostTasks().forEach(Runnable::run);
    }

    private static String packetName(BedrockPacket packet) {
        return packet == null ? "null" : packet.getClass().getSimpleName();
    }
}
