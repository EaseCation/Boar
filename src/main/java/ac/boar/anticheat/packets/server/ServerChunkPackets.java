package ac.boar.anticheat.packets.server;

import ac.boar.anticheat.Boar;
import ac.boar.anticheat.compensated.world.base.CompensatedWorld;
import ac.boar.anticheat.player.BoarPlayer;
import ac.boar.anticheat.util.BoarLog;
import ac.boar.anticheat.util.DimensionUtil;
import ac.boar.anticheat.util.geyser.BoarChunk;
import ac.boar.anticheat.util.geyser.BoarChunkSection;
import ac.boar.anticheat.util.geyser.GeyserChunkReader;
import ac.boar.anticheat.util.math.Vec3;
import ac.boar.protocol.api.CloudburstPacketEvent;
import ac.boar.protocol.api.PacketListener;
import org.cloudburstmc.math.GenericMath;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.data.ServerboundLoadingScreenPacketType;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.geysermc.geyser.level.BedrockDimension;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockEntityInfo;

import java.util.Objects;

public class ServerChunkPackets implements PacketListener {
    @Override
    public void onPacketSend(CloudburstPacketEvent event) {
        final BoarPlayer player = event.getPlayer();
        final CompensatedWorld world = player.compensatedWorld;

        if (event.getPacket() instanceof NetworkChunkPublisherUpdatePacket packet) {
            player.sendLatencyStack(() -> {
                world.setRadiusCenter(packet.getPosition());
                world.setRadius(packet.getRadius());

                world.yeetOutOfRangeChunks();
            });
        }

        if (event.getPacket() instanceof UpdateSubChunkBlocksPacket packet) {
            int blockX = packet.getChunkX() << 4;
            int blockZ = packet.getChunkZ() << 4;
            if (Math.abs(player.position.x - blockX) <= 16 || Math.abs(player.position.z - blockZ) <= 16) {
                player.sendLatencyStack();
            }

            player.getLatencyUtil().queue(() -> {
                if (player.compensatedWorld.isOutOfRadius(blockX, blockZ)) {
                    return;
                }

                world.updateBlockSections(packet);
            });
        }

        // There are a ton of chunk sending and world type for Bedrock (why support that much anyway)
        // But since Geyser only use this, then we only added support for this, no need to torture ourselves.
        if (event.getPacket() instanceof LevelChunkPacket packet) {
            final int subChunksCount = packet.getSubChunksLength();
            if (subChunksCount <= -2 || packet.getDimension() < 0 || packet.getDimension() > 2) {
                // These cases will all be ignored.
                return;
            }

            final int x = packet.getChunkX() << 4, z = packet.getChunkZ() << 4;
            // Avoid spamming latency if possible, unless the player is seriously lagging then this shouldn't false.
            if (Math.abs(player.position.x - x) <= 16 || Math.abs(player.position.z - z) <= 16) {
                player.sendLatencyStack();
            }

            final BedrockDimension dimension = DimensionUtil.dimensionFromId(packet.getDimension());
            final BoarChunkSection[] sections;
            try {
                sections = GeyserChunkReader.read(packet, player.getSession(), dimension.height() >> 4);
            } catch (Exception exception) {
                BoarLog.warning("chunk", "Failed to read LevelChunkPacket for " + player.getSession().bedrockUsername()
                        + ", chunk=" + packet.getChunkX() + "," + packet.getChunkZ()
                        + ", subChunks=" + subChunksCount + ", cached=" + packet.isCachingEnabled(), exception);
                // Never replace a known world view with a partially decoded chunk.
                return;
            }

            player.getLatencyUtil().queue(() -> {
                if (player.compensatedWorld.isOutOfRadius(packet.getChunkX()  << 4, packet.getChunkZ() << 4) || dimension != player.compensatedWorld.getDimension()) {
                    return;
                }

                world.put(packet.getChunkX(), packet.getChunkZ(), sections);
            });
        }

        if (event.getPacket() instanceof UpdateBlockPacket packet) {
            // Ugly hack.
            if (packet.getDataLayer() == 0 && Boar.getConfig().ignoreGhostBlock() && !player.inLoadingScreen && player.sinceLoadingScreen >= 2) {
                boolean newBlockIsAir = player.AIR_IDS.contains(packet.getDefinition().getRuntimeId());
                boolean oldBlockIsAir = player.AIR_IDS.contains(player.compensatedWorld.getRawBlockAt(packet.getBlockPosition().getX(), packet.getBlockPosition().getY(), packet.getBlockPosition().getZ(), 0));

                if (newBlockIsAir && !oldBlockIsAir) {
                    int distance = Math.abs(packet.getBlockPosition().getY() - GenericMath.floor(player.position.y - 1));
                    if (distance <= 1) {
                        player.tickSinceBlockResync = 5;
                        world.updateBlock(packet.getBlockPosition(), packet.getDataLayer(), packet.getDefinition().getRuntimeId());
                    }
                }
            }

            // Avoid spamming latency if possible, unless the player is seriously lagging then this shouldn't false.
            boolean send = player.position.distanceTo(new Vec3(packet.getBlockPosition())) <= 16;
            if (send) {
                player.sendLatencyStack();
            }

            player.getLatencyUtil().queue(() -> world.updateBlock(packet.getBlockPosition(), packet.getDataLayer(), packet.getDefinition().getRuntimeId()));
        }

        if (event.getPacket() instanceof BlockEntityDataPacket packet) {
            player.sendLatencyStack();
            player.getLatencyUtil().queue(() -> {
                final BoarChunk chunk = player.compensatedWorld.getChunk(packet.getBlockPosition().getX() >> 4, packet.getBlockPosition().getZ() >> 4);
                if (chunk == null) {
                    return;
                }

                final Vector3i pos = packet.getBlockPosition();
                chunk.blockEntities().removeIf(block -> block.getX() == pos.getX() && block.getY() == pos.getY() && block.getZ() == pos.getZ());
                chunk.blockEntities().add(new BlockEntityInfo(pos.getX(), pos.getY(), pos.getZ(), null, packet.getData()));
            });
        }
    }

    @Override
    public void onPacketReceived(CloudburstPacketEvent event) {
        final BoarPlayer player = event.getPlayer();

        if (event.getPacket() instanceof ServerboundLoadingScreenPacket packet && packet.getType() == ServerboundLoadingScreenPacketType.END_LOADING_SCREEN) {
            if (Objects.equals(player.currentLoadingScreen, packet.getLoadingScreenId()) && player.inLoadingScreen) {
                player.currentLoadingScreen = null;
                player.inLoadingScreen = false;
                player.sinceLoadingScreen = 0;
            }
        }
    }

}
