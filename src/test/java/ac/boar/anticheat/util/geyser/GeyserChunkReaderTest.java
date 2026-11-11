package ac.boar.anticheat.util.geyser;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.packet.ClientCacheBlobStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.LevelChunkPacket;
import org.geysermc.geyser.level.chunk.GeyserChunkSection;
import org.geysermc.geyser.level.chunk.bitarray.BitArrayVersion;
import org.geysermc.geyser.registry.type.BlockMappings;
import org.geysermc.geyser.registry.type.GeyserBedrockBlock;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.session.cache.ClientBlobCache;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GeyserChunkReaderTest {
    private final GeyserBedrockBlock air = block(0, "minecraft:air");
    private final GeyserBedrockBlock stone = block(1, "minecraft:stone");
    private final GeyserBedrockBlock water = block(2, "minecraft:water");
    private final BlockMappings mappings = BlockMappings.builder()
            .bedrockAir(air).bedrockRuntimeMap(new GeyserBedrockBlock[] {air, stone, water})
            .stateDefinitionMap(Map.of(air.getState(), air, stone.getState(), stone, water.getState(), water))
            .blockStateVersion(18168865).build();

    @Test
    void cachedPersistentAndOrdinaryChunksProduceTheSameWorld() throws Exception {
        GeyserChunkSection bottom = new GeyserChunkSection(0, -4);
        bottom.setFullBlock(2, 3, 4, 0, 1);
        bottom.setFullBlock(2, 3, 4, 1, 2);
        bottom.setFullBlock(15, 15, 15, 0, 2);
        GeyserChunkSection next = new GeyserChunkSection(0, -3);
        next.setFullBlock(1, 2, 3, 0, 2);
        assertEquivalentWorld(bottom, next);
    }

    @Test
    void singletonPersistentPalettesAndEmptySectionsAreDecoded() throws Exception {
        var storage = new org.geysermc.geyser.level.chunk.BlockStorage(
                BitArrayVersion.V0.createArray(4096, null), new it.unimi.dsi.fastutil.ints.IntArrayList(new int[] {1}));
        var singleton = new GeyserChunkSection(new org.geysermc.geyser.level.chunk.BlockStorage[] {storage}, -4);
        assertEquivalentWorld(singleton, new GeyserChunkSection(0, -3));
    }

    @Test
    void missingBlobFailsInsteadOfInstallingAnEmptyChunk() {
        GeyserSession session = mock(GeyserSession.class, RETURNS_DEEP_STUBS);
        LevelChunkPacket packet = new LevelChunkPacket();
        packet.setCachingEnabled(true);
        packet.setSubChunksLength(1);
        packet.getBlobIds().add(42);
        packet.getBlobIds().add(43);
        when(session.getClientBlobCache().getChunkSectionBlob(packet, 0)).thenReturn(null);
        assertThrows(IOException.class, () -> GeyserChunkReader.read(packet, session, 24));
    }

    @Test
    void malformedChunkCountIsRejectedBeforeReading() {
        LevelChunkPacket packet = new LevelChunkPacket();
        packet.setSubChunksLength(25);
        assertThrows(IOException.class, () -> GeyserChunkReader.read(packet, mock(GeyserSession.class), 24));
        packet.setCachingEnabled(true);
        packet.setSubChunksLength(2);
        var session = mock(GeyserSession.class, RETURNS_DEEP_STUBS);
        when(session.getClientBlobCache().getChunkSectionBlob(packet, 0))
                .thenThrow(new IllegalArgumentException("Invalid cached chunk"));
        assertThrows(IllegalArgumentException.class, () -> GeyserChunkReader.read(packet, session, 24));
    }

    private void assertEquivalentWorld(GeyserChunkSection... source) throws Exception {
        boolean enabled = ClientBlobCache.isGloballyEnabled();
        ClientBlobCache.setGloballyEnabled(true);
        GeyserSession session = mock(GeyserSession.class, RETURNS_DEEP_STUBS);
        ClientBlobCache cache = new ClientBlobCache(session);
        when(session.getClientBlobCache()).thenReturn(cache);
        when(session.getBlockMappings()).thenReturn(mappings);
        cache.setClientSupported(true);
        ByteBuf full = Unpooled.buffer();
        LevelChunkPacket ordinary = new LevelChunkPacket();
        LevelChunkPacket cached = new LevelChunkPacket();
        try {
            // A non-zero reader index must stay intact for later outbound encoding.
            full.writeByte(99);
            full.readerIndex(1);
            byte[][] blobs = new byte[source.length + 1][];
            for (int i = 0; i < source.length; i++) {
                source[i].writeToNetwork(full);
                ByteBuf blob = Unpooled.buffer();
                try {
                    source[i].writeToCache(blob, mappings);
                    blobs[i] = ByteBufUtil.getBytes(blob);
                } finally {
                    blob.release();
                }
            }
            blobs[source.length] = new byte[] {0}; // biome blob, not block storage
            byte[] payload = ByteBufUtil.getBytes(full);
            ordinary.setData(full.retain());
            ordinary.setSubChunksLength(source.length);
            cached.setSubChunksLength(source.length);
            assertTrue(cache.prepareChunkPacket(cached, new byte[] {0}, blobs, 0));
            assertEquals(1, cached.getData().readableBytes()); // production failure shape

            // Earlier chunks may already have acknowledged identical blobs.
            ClientCacheBlobStatusPacket ack = new ClientCacheBlobStatusPacket();
            ack.getAcks().addAll(cached.getBlobIds());
            cache.handleBlobStatus(ack);
            BoarChunkSection[] regular = GeyserChunkReader.read(ordinary, session, 24);
            BoarChunkSection[] restored = GeyserChunkReader.read(cached, session, 24);
            for (int i = 0; i < source.length; i++) {
                for (int layer = 0; layer < source[i].getBlockStorageArray().length; layer++) {
                    for (int x = 0; x < 16; x++) {
                        for (int y = 0; y < 16; y++) {
                            for (int z = 0; z < 16; z++) {
                                int expected = source[i].getFullBlock(x, y, z, layer);
                                assertEquals(expected, regular[i].getFullBlock(x, y, z, layer));
                                assertEquals(expected, restored[i].getFullBlock(x, y, z, layer));
                            }
                        }
                    }
                }
            }
            assertNull(restored[source.length]);
            assertEquals(1, full.readerIndex());
            assertArrayEquals(payload, ByteBufUtil.getBytes(full));
            assertEquals(0, cached.getData().readerIndex());
            ByteBuf view = cache.getChunkSectionBlob(cached, 0);
            assertNotNull(view);
            try {
                assertTrue(view.isReadOnly());
                assertEquals(0, view.readerIndex());
            } finally {
                view.release();
            }
        } finally {
            ordinary.release();
            cached.release();
            full.release();
            ClientBlobCache.setGloballyEnabled(enabled);
        }
    }

    private static GeyserBedrockBlock block(int id, String name) {
        return new GeyserBedrockBlock(id, NbtMap.builder().putString("name", name)
                .putCompound("states", NbtMap.EMPTY).build());
    }
}
