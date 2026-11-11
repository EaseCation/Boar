package ac.boar.anticheat.util.geyser;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtUtils;
import org.cloudburstmc.protocol.bedrock.packet.LevelChunkPacket;
import org.cloudburstmc.protocol.common.util.VarInts;
import org.geysermc.geyser.level.chunk.bitarray.BitArray;
import org.geysermc.geyser.level.chunk.bitarray.BitArrayVersion;
import org.geysermc.geyser.level.chunk.bitarray.SingletonBitArray;
import org.geysermc.geyser.registry.type.BlockMappings;
import org.geysermc.geyser.session.GeyserSession;

import java.io.IOException;

/** Reads both ordinary Geyser chunks and the persistent subchunks referenced by client blob caching. */
public final class GeyserChunkReader {
    private GeyserChunkReader() {
    }

    public static BoarChunkSection[] read(LevelChunkPacket packet, GeyserSession session,
                                           int sectionCapacity) throws IOException {
        int count = packet.getSubChunksLength();
        if (count < 0 || count > sectionCapacity) {
            throw new IOException("Invalid subchunk count: " + count);
        }
        BoarChunkSection[] sections = new BoarChunkSection[sectionCapacity];
        if (packet.isCachingEnabled()) {
            // Geyser emits one blob per section, followed by one biome blob. data is only the tail.
            for (int i = 0; i < count; i++) {
                ByteBuf blob = session.getClientBlobCache().getChunkSectionBlob(packet, i);
                if (blob == null) {
                    throw new IOException("Missing outbound chunk blob for section: " + i);
                }
                try {
                    sections[i] = readSection(blob, session.getBlockMappings());
                    if (blob.isReadable()) {
                        throw new IOException("Unread subchunk blob bytes: " + blob.readableBytes());
                    }
                } finally {
                    blob.release();
                }
            }
        } else {
            ByteBuf data = packet.getData().duplicate();
            for (int i = 0; i < count; i++) {
                sections[i] = readSection(data, session.getBlockMappings());
            }
        }
        return sections;
    }

    private static BoarChunkSection readSection(ByteBuf data, BlockMappings mappings) throws IOException {
        int version = data.readUnsignedByte();
        if (version != 8 && version != 9) {
            throw new IOException("Unsupported subchunk format: " + version);
        }
        int layerCount = data.readUnsignedByte();
        if (version == 9) {
            data.readByte(); // Y index; Geyser sends sections in dimension order.
        }
        BlockStorage[] layers = new BlockStorage[layerCount];
        for (int i = 0; i < layerCount; i++) {
            layers[i] = readLayer(data, mappings);
        }
        return new BoarChunkSection(layers);
    }

    private static BlockStorage readLayer(ByteBuf data, BlockMappings mappings) throws IOException {
        int header = data.readUnsignedByte();
        int bits = header >> 1;
        if (bits == 127) {
            throw new IOException("Previous-palette encoding is not supported for block storage");
        }
        BitArrayVersion version = BitArrayVersion.get(bits, true);
        BitArray bitArray = bits == 0 ? version.createArray(4096, null) : version.createArray(4096);
        for (int i = 0; i < bitArray.getWords().length; i++) {
            bitArray.getWords()[i] = data.readIntLE();
        }
        int size = bitArray instanceof SingletonBitArray ? 1 : VarInts.readInt(data);
        if (size <= 0 || size > 4096) {
            throw new IOException("Invalid block palette size: " + size);
        }
        IntArrayList palette = new IntArrayList(size);
        if ((header & 1) != 0) {
            for (int i = 0; i < size; i++) {
                palette.add(VarInts.readInt(data));
            }
        } else {
            var nbt = NbtUtils.createNetworkReader(new ByteBufInputStream(data));
            for (int i = 0; i < size; i++) {
                NbtMap state = (NbtMap) nbt.readTag();
                var key = state.toBuilder();
                key.remove("version");
                var definition = mappings.getDefinition(key.build());
                if (definition == null) {
                    throw new IOException("Unknown persistent block state: " + state.getString("name"));
                }
                palette.add(definition.getRuntimeId());
            }
        }
        return new BlockStorage(bitArray, palette);
    }
}
