package cc.deckamaster.server;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.util.AttributeKey;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Static files for the web client plus one WebSocket endpoint (/ws) per game session. */
final class WebServer {
    private static final Gson GSON = new Gson();
    private static final AttributeKey<GameSession> SESSION = AttributeKey.valueOf("session");
    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"), Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"), Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"), Map.entry("json", "application/json"),
            Map.entry("ico", "image/x-icon"), Map.entry("woff2", "font/woff2"),
            Map.entry("woff", "font/woff"), Map.entry("ttf", "font/ttf"), Map.entry("txt", "text/plain; charset=utf-8"));

    private final int port;
    private final Path webDir;
    private final Decks decks;

    WebServer(int port, Path webDir, Decks decks) {
        this.port = port;
        this.webDir = webDir;
        this.decks = decks;
    }

    void run() throws InterruptedException {
        EventLoopGroup boss = new NioEventLoopGroup(1);
        EventLoopGroup workers = new NioEventLoopGroup();
        try {
            ServerBootstrap b = new ServerBootstrap()
                    .group(boss, workers)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline()
                                    .addLast(new HttpServerCodec())
                                    .addLast(new HttpObjectAggregator(1 << 20))
                                    .addLast(new WebSocketServerProtocolHandler("/ws", null, true, 1 << 20))
                                    .addLast(new StaticFiles())
                                    .addLast(new GameSocket());
                        }
                    });
            Channel ch = b.bind(port).sync().channel();
            System.out.println("Deckamaster listening on http://localhost:" + port);
            ch.closeFuture().sync();
        } finally {
            boss.shutdownGracefully();
            workers.shutdownGracefully();
        }
    }

    private final class StaticFiles extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) throws Exception {
            String path = req.uri().split("\\?")[0];
            if (path.equals("/")) path = "/index.html";
            Path file = webDir.resolve(path.substring(1)).normalize();
            FullHttpResponse res;
            if (!file.startsWith(webDir) || !Files.isRegularFile(file)) {
                res = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND,
                        Unpooled.copiedBuffer("Not found".getBytes()));
                res.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
            } else {
                res = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                        Unpooled.wrappedBuffer(Files.readAllBytes(file)));
                String name = file.getFileName().toString();
                String ext = name.substring(name.lastIndexOf('.') + 1);
                res.headers().set(HttpHeaderNames.CONTENT_TYPE, TYPES.getOrDefault(ext, "application/octet-stream"));
                res.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
            }
            res.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, res.content().readableBytes());
            ctx.writeAndFlush(res).addListener(ChannelFutureListener.CLOSE);
        }
    }

    private final class GameSocket extends SimpleChannelInboundHandler<TextWebSocketFrame> {
        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
                Channel ch = ctx.channel();
                ch.attr(SESSION).set(new GameSession(decks, msg -> {
                    if (ch.isActive()) ch.writeAndFlush(new TextWebSocketFrame(GSON.toJson(msg)));
                }));
            }
            super.userEventTriggered(ctx, evt);
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) {
            GameSession s = ctx.channel().attr(SESSION).get();
            if (s == null) return;
            try {
                s.onMessage(GSON.fromJson(frame.text(), JsonObject.class));
            } catch (RuntimeException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            GameSession s = ctx.channel().attr(SESSION).get();
            if (s != null) s.close();
            super.channelInactive(ctx);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            cause.printStackTrace();
            ctx.close();
        }
    }
}
