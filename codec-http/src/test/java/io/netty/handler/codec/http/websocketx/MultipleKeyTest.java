package io.netty.handler.codec.http.websocketx;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.URI;
import java.util.LinkedList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory.*;
import static io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler.ClientHandshakeStateEvent.*;
import static org.junit.Assert.*;

/**
 * Reproducer for a client sending mutltiple {@code Sec-Websocket-Key} headers on a request, and the server picking one
 * such that it responds with an unexpected {@code Sec-Websocket-Accept} header value in the response.
 */
public class MultipleKeyTest {

  private static final String HOST = "localhost";
  private static final int PORT = 1337;
  private static final String PATH = "/_ws";

  private Channel serverChannel;
  private Channel clientChannel;
  private CountDownLatch pingLatch;
  private CountDownLatch pongLatch;
  private EventLoopGroup clientGroup;
  private EventLoopGroup serverGroup;

  /**
   * Start up both a server and a client.
   *
   * The server opens a latch on receipt of a PING frame and the client opens a latch on receipt
   * of the corresponding PONG frame.
   */
  @Before public void setUp() throws InterruptedException {
    // start a server
    pingLatch = new CountDownLatch(1);
    serverGroup = new NioEventLoopGroup();
    ServerBootstrap serverBootstrap = new ServerBootstrap();
    serverBootstrap.group(serverGroup)
        .channel(NioServerSocketChannel.class)
        .handler(new LoggingHandler(LogLevel.INFO))
        .childHandler(
            new ChannelInitializer<SocketChannel>() {
              @Override protected void initChannel(SocketChannel ch) throws Exception {
                ChannelPipeline pipeline = ch.pipeline();
                pipeline.addLast(new HttpServerCodec());
                pipeline.addLast(new HttpObjectAggregator(65536));
                pipeline.addLast(new ChannelInboundHandlerAdapter() {

                  @Override public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                    if (msg instanceof HttpRequest) {
                      // Rotate the X-Websocket-Key headers to simulate a server negotiating the handshake with the
                      // late header it sees, rather than the first
                      HttpRequest httpRequest = (HttpRequest) msg;
                      HttpHeaders originalHeaders = httpRequest.headers();

                      LinkedList<String> keys =
                              new LinkedList<String>(originalHeaders.getAll(HttpHeaderNames.SEC_WEBSOCKET_KEY));
                      keys.addLast(keys.pop());

                      HttpHeaders rotatedHeaders = new DefaultHttpHeaders();
                      rotatedHeaders.add(HttpHeaderNames.SEC_WEBSOCKET_KEY, keys);

                      originalHeaders.remove(HttpHeaderNames.SEC_WEBSOCKET_KEY);
                      originalHeaders.add(rotatedHeaders);
                    }
                    if (msg instanceof PingWebSocketFrame) {
                      pingLatch.countDown();
                    }
                    super.channelRead(ctx, msg);
                  }
                });
                pipeline.addLast(new WebSocketServerProtocolHandler(PATH, null, true));
              }
            });
    serverChannel = serverBootstrap.bind(PORT).sync().channel();

    // start a client
    pongLatch = new CountDownLatch(1);
    clientGroup = new NioEventLoopGroup();
    Bootstrap clientBootstrap = new Bootstrap();
    clientBootstrap.group(clientGroup)
        .channel(NioSocketChannel.class)
        .handler(new ChannelInitializer<SocketChannel>() {

          @Override protected void initChannel(SocketChannel ch) throws Exception {
            ChannelPipeline pipeline = ch.pipeline();
            pipeline.addLast(new HttpClientCodec());
            pipeline.addLast(new HttpObjectAggregator(8192));

            HttpHeaders headers = new DefaultHttpHeaders();
            headers.add(HttpHeaderNames.SEC_WEBSOCKET_KEY, "garbage");

            WebSocketClientHandshaker handshaker =
                newHandshaker(URI.create("ws://" + HOST + ":" + PORT + PATH), WebSocketVersion.V13,
                    null, true, headers);
            pipeline.addLast(new WebSocketClientProtocolHandler(handshaker, true, false));
            pipeline.addLast(new ChannelDuplexHandler() {

              @Override public void channelRead(ChannelHandlerContext ctx, Object msg)
                  throws Exception {
                if (msg instanceof PongWebSocketFrame) {
                  pongLatch.countDown();
                }
                super.channelRead(ctx, msg);
              }

              @Override
              public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
                  throws Exception {
                super.write(ctx, msg, promise);
              }

              @Override public void userEventTriggered(ChannelHandlerContext ctx, Object evt)
                  throws Exception {
                if (evt == HANDSHAKE_COMPLETE) {
                  ctx.pipeline().writeAndFlush(new PingWebSocketFrame());
                }
                super.userEventTriggered(ctx, evt);
              }
            });
          }
        });

    clientChannel = clientBootstrap.connect(HOST, PORT).sync().channel();
  }

  @After public void tearDown() throws InterruptedException {
    clientGroup.shutdownGracefully();
    clientChannel.closeFuture().sync();

    serverGroup.shutdownGracefully();
    serverChannel.closeFuture().sync();
  }

  @Test public void test() throws InterruptedException {
    boolean receivedPingFrame = pingLatch.await(5, TimeUnit.SECONDS);
    assertTrue(receivedPingFrame);

    boolean receivedPongFrame = pongLatch.await(5, TimeUnit.SECONDS);
    assertTrue(receivedPongFrame);
  }
}
