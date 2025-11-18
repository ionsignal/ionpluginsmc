package com.ionsignal.minecraft.ionnerrus.persona.network;

import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.DefaultChannelConfig;
import io.netty.channel.EventLoop;

import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.net.SocketAddress;

public class EmptyConnection extends Connection {
    private static Field packetListenerField;
    private static Field disconnectListenerField;
    static {
        try {
            // CORRECTED: Use deobfuscated field names
            packetListenerField = Connection.class.getDeclaredField("packetListener");
            packetListenerField.setAccessible(true);
            disconnectListenerField = Connection.class.getDeclaredField("disconnectListener");
            disconnectListenerField.setAccessible(true);
        } catch (NoSuchFieldException e) {
            e.printStackTrace();
        }
    }

    public EmptyConnection(PacketFlow packetFlow) {
        super(packetFlow);
        this.channel = new EmptyChannel(null);
        this.address = new SocketAddress() {
            private static final long serialVersionUID = 8207338859896320185L;
        };
    }

    @Override
    public void disconnect(@SuppressWarnings("null") Component component) {
    }

    @Override
    public boolean isConnected() {
        return true;
    }

    @Override
    public void send(@SuppressWarnings("null") Packet<?> packet) {
    }

    @Override
    public void send(@SuppressWarnings("null") Packet<?> packet, @Nullable ChannelFutureListener channelFutureListener) {
    }

    public void send(@SuppressWarnings("null") Packet<?> packet, @Nullable ChannelFutureListener channelFutureListener, boolean flush) {
    }

    public void setListenerForServerboundHandshake(@SuppressWarnings("null") PacketListener packetListener) {
        try {
            if (packetListenerField != null) {
                packetListenerField.set(this, packetListener);
            }
            if (disconnectListenerField != null) {
                disconnectListenerField.set(this, null);
            }
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void flushChannel() {
    }

    private static class EmptyChannel extends io.netty.channel.AbstractChannel {
        private final ChannelConfig config = new DefaultChannelConfig(this);

        protected EmptyChannel(Channel parent) {
            super(parent);
        }

        @Override
        public ChannelConfig config() {
            return this.config;
        }

        @Override
        protected void doBeginRead() {
        }

        @Override
        protected void doBind(SocketAddress socketAddress) {
        }

        @Override
        protected void doClose() {
        }

        @Override
        protected void doDisconnect() {
        }

        @Override
        protected void doWrite(ChannelOutboundBuffer channelOutboundBuffer) {
        }

        @Override
        public boolean isActive() {
            return false;
        }

        @Override
        public boolean isOpen() {
            return false;
        }

        @Override
        protected boolean isCompatible(EventLoop eventLoop) {
            return true;
        }

        @Override
        public SocketAddress localAddress() {
            return null;
        }

        @Override
        protected SocketAddress localAddress0() {
            return null;
        }

        @Override
        public ChannelMetadata metadata() {
            return new ChannelMetadata(false);
        }

        @Override
        protected AbstractUnsafe newUnsafe() {
            return new AbstractUnsafe() {
                @Override
                public void connect(SocketAddress remoteAddress, SocketAddress localAddress, io.netty.channel.ChannelPromise promise) {
                }
            };
        }

        @Override
        public SocketAddress remoteAddress() {
            return null;
        }

        @Override
        protected SocketAddress remoteAddress0() {
            return null;
        }
    }
}