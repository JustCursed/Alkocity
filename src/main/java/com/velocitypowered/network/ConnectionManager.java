package com.velocitypowered.network;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.client.HandshakeSessionHandler;
import com.velocitypowered.proxy.protocol.ProtocolConstants;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.netty.LegacyPingDecoder;
import com.velocitypowered.proxy.protocol.netty.LegacyPingEncoder;
import com.velocitypowered.proxy.protocol.netty.MinecraftDecoder;
import com.velocitypowered.proxy.protocol.netty.MinecraftEncoder;
import com.velocitypowered.proxy.protocol.netty.MinecraftVarintFrameDecoder;
import com.velocitypowered.proxy.protocol.netty.MinecraftVarintLengthEncoder;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.ReadTimeoutHandler;

import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import static com.velocitypowered.network.Connections.CLIENT_READ_TIMEOUT_SECONDS;
import static com.velocitypowered.network.Connections.FRAME_DECODER;
import static com.velocitypowered.network.Connections.FRAME_ENCODER;
import static com.velocitypowered.network.Connections.LEGACY_PING_DECODER;
import static com.velocitypowered.network.Connections.LEGACY_PING_ENCODER;
import static com.velocitypowered.network.Connections.MINECRAFT_DECODER;
import static com.velocitypowered.network.Connections.MINECRAFT_ENCODER;
import static com.velocitypowered.network.Connections.READ_TIMEOUT;

public final class ConnectionManager {
    private static final String DISABLE_EPOLL_PROPERTY = "velocity.connection.disable-epoll";
    private static final boolean DISABLE_EPOLL = Boolean.getBoolean(DISABLE_EPOLL_PROPERTY);
    private final Set<Channel> endpoints = new HashSet<>();
    private final Class<? extends ServerSocketChannel> serverSocketChannelClass;
    private final Class<? extends SocketChannel> socketChannelClass;
    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;

    public ConnectionManager() {
        final boolean epoll = canUseEpoll();
        if (epoll) {
            this.serverSocketChannelClass = EpollServerSocketChannel.class;
            this.socketChannelClass = EpollSocketChannel.class;
            this.bossGroup = new EpollEventLoopGroup(0, createThreadFactory("Netty Epoll Boss #%d"));
            this.workerGroup = new EpollEventLoopGroup(0, createThreadFactory("Netty Epoll Worker #%d"));
        } else {
            this.serverSocketChannelClass = NioServerSocketChannel.class;
            this.socketChannelClass = NioSocketChannel.class;
            this.bossGroup = new NioEventLoopGroup(0, createThreadFactory("Netty Nio Boss #%d"));
            this.workerGroup = new NioEventLoopGroup(0, createThreadFactory("Netty Nio Worker #%d"));
        }
        this.logChannelInformation(epoll);
    }

    private void logChannelInformation(final boolean epoll) {
        final StringBuilder sb = new StringBuilder();
        sb.append("Channel type: ");
        sb.append(epoll ? "epoll": "nio");
        if(DISABLE_EPOLL) {
            sb.append(String.format(" - epoll explicitly disabled using -D%s=true", DISABLE_EPOLL_PROPERTY));
        }
        System.out.println(sb.toString()); // TODO: move to logger
    }

    public void bind(final InetSocketAddress address) {
        final ServerBootstrap bootstrap = new ServerBootstrap()
                .channel(this.serverSocketChannelClass)
                .group(this.bossGroup, this.workerGroup)
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(final Channel ch) {
                        ch.pipeline()
                                .addLast(READ_TIMEOUT, new ReadTimeoutHandler(CLIENT_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                                .addLast(LEGACY_PING_DECODER, new LegacyPingDecoder())
                                .addLast(FRAME_DECODER, new MinecraftVarintFrameDecoder())
                                .addLast(LEGACY_PING_ENCODER, LegacyPingEncoder.INSTANCE)
                                .addLast(FRAME_ENCODER, MinecraftVarintLengthEncoder.INSTANCE)
                                .addLast(MINECRAFT_DECODER, new MinecraftDecoder(ProtocolConstants.Direction.TO_SERVER))
                                .addLast(MINECRAFT_ENCODER, new MinecraftEncoder(ProtocolConstants.Direction.TO_CLIENT));

                        final MinecraftConnection connection = new MinecraftConnection(ch);
                        connection.setState(StateRegistry.HANDSHAKE);
                        connection.setSessionHandler(new HandshakeSessionHandler(connection));
                        ch.pipeline().addLast(Connections.HANDLER, connection);
                    }
                })
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.IP_TOS, 0x18)
                .localAddress(address);
        bootstrap.bind()
                .addListener((ChannelFutureListener) future -> {
                    final Channel channel = future.channel();
                    if (future.isSuccess()) {
                        this.endpoints.add(channel);
                        System.out.println("Listening on " + channel.localAddress()); // TODO: move to logger
                    } else {
                        System.out.println("Can't bind to " + channel.localAddress()); // TODO: move to logger
                        future.cause().printStackTrace();
                    }
                });
    }

    public Bootstrap createWorker() {
        return new Bootstrap()
                .channel(this.socketChannelClass)
                .group(this.workerGroup);
    }

    public void shutdown() {
        for (final Channel endpoint : this.endpoints) {
            try {
                System.out.println(String.format("Closing endpoint %s", endpoint.localAddress())); // TODO: move to logger
                endpoint.close().sync();
            } catch (final InterruptedException e) {
                System.err.println("Interrupted whilst closing endpoint"); // TODO: move to logger
                e.printStackTrace();
            }
        }
    }

    private static boolean canUseEpoll() {
        return Epoll.isAvailable() && !DISABLE_EPOLL;
    }

    private static ThreadFactory createThreadFactory(final String nameFormat) {
        return new ThreadFactoryBuilder()
                .setNameFormat(nameFormat)
                .setDaemon(true)
                .build();
    }
}
