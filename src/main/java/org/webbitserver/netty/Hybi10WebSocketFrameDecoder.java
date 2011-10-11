package org.webbitserver.netty;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.handler.codec.frame.CorruptedFrameException;
import org.jboss.netty.handler.codec.frame.TooLongFrameException;
import org.jboss.netty.handler.codec.replay.ReplayingDecoder;

import java.util.ArrayList;
import java.util.List;

public class Hybi10WebSocketFrameDecoder extends ReplayingDecoder<Hybi10WebSocketFrameDecoder.State> {
    private long framePayloadLen;
    private ChannelBuffer maskingKey;
    private List<ChannelBuffer> frames = new ArrayList<ChannelBuffer>();

    private boolean isServer = true;
    private boolean requireMaskedClientFrames = true;
    private boolean insideMessage;
    private byte frameOpcode;
    private Byte fragmentOpcode;
    private boolean frameFin;
    private int frameRsv;

    public static enum State {
        FRAME_START,
        MASKING_KEY,
        PAYLOAD
    }

    public Hybi10WebSocketFrameDecoder() {
        super(State.FRAME_START);
    }

    @Override
    protected Object decode(ChannelHandlerContext ctx, Channel channel, ChannelBuffer buffer, State state) throws Exception {
        switch (state) {
            case FRAME_START:
                // FIN, RSV, OPCODE
                byte b = buffer.readByte();
                frameFin = (b & 0x80) != 0;
                frameRsv = (b & 0x70) >> 4;
                frameOpcode = (byte) (b & 0x0F);

                // MASK, PAYLOAD LEN 1
                b = buffer.readByte();
                boolean frameMasked = (b & 0x80) != 0;
                int framePayloadLen1 = (b & 0x7F);

                if (frameRsv != 0) {
                    protocolViolation(channel, "RSV != 0 and no extension negotiated");
                    return null;
                }

                if (isServer && requireMaskedClientFrames && !frameMasked) {
                    protocolViolation(channel, "unmasked client to server frame");
                    return null;
                }

                if (frameOpcode > 7) { // control frame (have MSB in opcode set)

                    // control frames MUST NOT be fragmented
                    if (!frameFin) {
                        protocolViolation(channel, "fragmented control frame");
                        return null;
                    }

                    // control frames MUST have payload 125 octets or less
                    if (framePayloadLen1 > 125) {
                        protocolViolation(channel, "control frame with payload length > 125 octets");
                        return null;

                    }

                    // check for reserved control frame opcodes
                    if (!(frameOpcode == Opcodes.OPCODE_CLOSE || frameOpcode == Opcodes.OPCODE_PING || frameOpcode == Opcodes.OPCODE_PONG)) {
                        protocolViolation(channel, "control frame using reserved opcode " + frameOpcode);
                        return null;
                    }

                    // close frame : if there is a body, the first two bytes of the body MUST be a 2-byte
                    // unsigned integer (in network byte order) representing a status code
                    if (frameOpcode == 8 && framePayloadLen1 == 1) {
                        protocolViolation(channel, "received close control frame with payload len 1");
                        return null;
                    }
                } else { // data frame
                    // check for reserved data frame opcodes
                    if (!(frameOpcode == Opcodes.OPCODE_CONT || frameOpcode == Opcodes.OPCODE_TEXT || frameOpcode == Opcodes.OPCODE_BINARY)) {
                        protocolViolation(channel, "data frame using reserved opcode " + frameOpcode);
                        return null;
                    }

//                    // check opcode vs message fragmentation state 1/2
//                    if (!insideMessage && frameOpcode == OPCODE_CONT) {
//                        return protocolViolation("received continuation data frame outside fragmented message");
//                    }
//
//                    // check opcode vs message fragmentation state 2/2
//                    if (insideMessage && frameOpcode != OPCODE_CONT) {
//                        return protocolViolation("received non-continuation data frame while inside fragmented message");
//                    }
                }

                int maskLen = frameMasked ? 4 : 0;

                if (framePayloadLen1 == 126) {
                    framePayloadLen = buffer.readUnsignedShort();
                    if (framePayloadLen < 126) {
                        protocolViolation(channel, "invalid data frame length (not using minimal length encoding)");
                        return null;
                    }
                } else if (framePayloadLen1 == 127) {
                    framePayloadLen = buffer.readLong();
                    // TODO: check if it's bigger than 0x7FFFFFFFFFFFFFFF, Maybe just check if it's negative?

                    if (framePayloadLen < 65536) {
                        protocolViolation(channel, "invalid data frame length (not using minimal length encoding)");
                        return null;
                    }
                } else {
                    framePayloadLen = framePayloadLen1;
                }
                checkpoint(State.MASKING_KEY);
                return null;
            case MASKING_KEY:
                maskingKey = buffer.readBytes(4);
                checkpoint(State.PAYLOAD);
            case PAYLOAD:
                ChannelBuffer frame = buffer.readBytes(toFrameLength(framePayloadLen));
                unmask(frame);

                if (frameOpcode == Opcodes.OPCODE_CONT) {
                    frameOpcode = fragmentOpcode;
                    frames.add(frame);

                    frame = channel.getConfig().getBufferFactory().getBuffer(0);
                    for (ChannelBuffer channelBuffer : frames) {
                        frame.ensureWritableBytes(channelBuffer.readableBytes());
                        frame.writeBytes(channelBuffer);
                    }

                    this.fragmentOpcode = null;
                    frames.clear();
                } else {
                    checkpoint(State.FRAME_START);
                    if (frameOpcode == Opcodes.OPCODE_TEXT || frameOpcode == Opcodes.OPCODE_BINARY || frameOpcode == Opcodes.OPCODE_PONG) {
                        return new HybiFrame(frameOpcode, frameFin, frameRsv, frame);
                    } else if (frameOpcode == Opcodes.OPCODE_PING) {
                        channel.write(new HybiFrame(Opcodes.OPCODE_PONG, true, 0, frame));
                        return null;
                    } else if (frameOpcode == Opcodes.OPCODE_CLOSE) {
                        channel.write(new HybiFrame(Opcodes.OPCODE_CLOSE, true, 0, ChannelBuffers.buffer(0)));
                        channel.close();
                        return null;
                    }
                }
            default:
                throw new Error("Shouldn't reach here.");
        }
    }

    private void protocolViolation(Channel channel, String reason) throws CorruptedFrameException {
        channel.close();
        throw new CorruptedFrameException(reason);
    }

    private int toFrameLength(long l) throws TooLongFrameException {
        if (l > Integer.MAX_VALUE) {
            throw new TooLongFrameException("Length:" + l);
        } else {
            return (int) l;
        }
    }

    private void unmask(ChannelBuffer frame) {
        byte[] bytes = frame.array();
        for (int i = 0; i < bytes.length; i++) {
            frame.setByte(i, frame.getByte(i) ^ maskingKey.getByte(i % 4));
        }
    }
}