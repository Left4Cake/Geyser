/*
 * Copyright (c) 2019-2020 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Geyser
 */

package org.geysermc.connector.network.translators.java.world;

import com.github.steveice10.mc.protocol.packet.ingame.server.world.ServerChunkDataPacket;
import com.nukkitx.math.vector.Vector2i;
import com.nukkitx.math.vector.Vector3f;
import com.nukkitx.network.VarInts;
import com.nukkitx.protocol.bedrock.packet.LevelChunkPacket;
import com.nukkitx.protocol.bedrock.packet.NetworkChunkPublisherUpdatePacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.geysermc.api.Geyser;
import org.geysermc.connector.network.session.GeyserSession;
import org.geysermc.connector.network.translators.PacketTranslator;
import org.geysermc.connector.utils.ChunkUtils;
import org.geysermc.connector.world.chunk.ChunkSection;

public class JavaChunkDataTranslator extends PacketTranslator<ServerChunkDataPacket> {

    @Override
    public void translate(ServerChunkDataPacket packet, GeyserSession session) {
        // Not sure if this is safe or not, however without this the client usually times out
        Geyser.getConnector().getGeneralThreadPool().execute(() -> {
            Vector2i chunkPos = session.getLastChunkPosition();
            Vector3f position = session.getPlayerEntity().getPosition();
            Vector2i newChunkPos = Vector2i.from(position.getFloorX() >> 4, position.getFloorZ() >> 4);

            if (chunkPos == null || !chunkPos.equals(newChunkPos)) {
                NetworkChunkPublisherUpdatePacket chunkPublisherUpdatePacket = new NetworkChunkPublisherUpdatePacket();
                chunkPublisherUpdatePacket.setPosition(position.toInt());
                chunkPublisherUpdatePacket.setRadius(session.getRenderDistance() << 4);
                session.getUpstream().sendPacket(chunkPublisherUpdatePacket);

                session.setLastChunkPosition(newChunkPos);
            }

            try {
                ChunkUtils.ChunkData chunkData = ChunkUtils.translateToBedrock(packet.getColumn());
                ByteBuf byteBuf = Unpooled.buffer(32);
                ChunkSection[] sections = chunkData.sections;

                int sectionCount = sections.length - 1;
                while (sectionCount >= 0 && sections[sectionCount].isEmpty()) {
                    sectionCount--;
                }
                sectionCount++;

                for (int i = 0; i < sectionCount; i++) {
                    ChunkSection section = chunkData.sections[i];
                    section.writeToNetwork(byteBuf);
                }

                byteBuf.writeBytes(chunkData.biomes); // Biomes - 256 bytes
                byteBuf.writeByte(0); // Border blocks - Edu edition only
                VarInts.writeUnsignedInt(byteBuf, 0); // extra data length, 0 for now

                byte[] payload = new byte[byteBuf.writerIndex()];
                byteBuf.readBytes(payload);

                LevelChunkPacket levelChunkPacket = new LevelChunkPacket();
                levelChunkPacket.setSubChunksLength(sectionCount);
                levelChunkPacket.setCachingEnabled(false);
                levelChunkPacket.setChunkX(packet.getColumn().getX());
                levelChunkPacket.setChunkZ(packet.getColumn().getZ());
                levelChunkPacket.setData(payload);
                session.getUpstream().sendPacket(levelChunkPacket);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        });
    }
}
