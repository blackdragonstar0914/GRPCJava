package com.google.net.stubby.newtransport.netty;

import static com.google.net.stubby.newtransport.netty.Utils.CONTENT_TYPE_GRPC;
import static com.google.net.stubby.newtransport.netty.Utils.CONTENT_TYPE_HEADER;
import static com.google.net.stubby.newtransport.netty.Utils.HTTP_METHOD;
import static io.netty.buffer.Unpooled.EMPTY_BUFFER;
import static io.netty.handler.codec.http2.Http2CodecUtil.toByteBuf;
import static io.netty.handler.codec.http2.Http2Error.NO_ERROR;

import com.google.common.base.Preconditions;
import com.google.net.stubby.Status;
import com.google.net.stubby.newtransport.ServerStreamListener;
import com.google.net.stubby.newtransport.ServerTransportListener;
import com.google.net.stubby.newtransport.TransportFrameUtil;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http2.DefaultHttp2InboundFlowController;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2FrameAdapter;
import io.netty.handler.codec.http2.Http2FrameReader;
import io.netty.handler.codec.http2.Http2FrameWriter;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2OutboundFlowController;
import io.netty.handler.codec.http2.Http2Stream;
import io.netty.handler.codec.http2.Http2StreamException;
import io.netty.util.ReferenceCountUtil;

import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Nullable;

/**
 * Server-side Netty handler for GRPC processing. All event handlers are executed entirely within
 * the context of the Netty Channel thread.
 */
class NettyServerHandler extends Http2ConnectionHandler {

  private static Logger logger = Logger.getLogger(NettyServerHandler.class.getName());

  private static final Status GOAWAY_STATUS = Status.UNAVAILABLE;

  private final ServerTransportListener transportListener;
  private final DefaultHttp2InboundFlowController inboundFlow;
  private Throwable connectionError;

  NettyServerHandler(ServerTransportListener transportListener, Http2Connection connection,
      Http2FrameReader frameReader,
      Http2FrameWriter frameWriter,
      DefaultHttp2InboundFlowController inboundFlow,
      Http2OutboundFlowController outboundFlow) {
    super(connection, frameReader, frameWriter, inboundFlow, outboundFlow, new LazyFrameListener());
    this.transportListener = Preconditions.checkNotNull(transportListener, "transportListener");
    this.inboundFlow = Preconditions.checkNotNull(inboundFlow, "inboundFlow");
    initListener();
    connection.local().allowPushTo(false);
  }

  @Nullable
  Throwable connectionError() {
    return connectionError;
  }

  private void initListener() {
    ((LazyFrameListener) decoder().listener()).setHandler(this);
  }

  @Override
  public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
    // Avoid NotYetConnectedException
    if (!ctx.channel().isActive()) {
        ctx.close(promise);
        return;
    }

    // Write the GO_AWAY frame to the remote endpoint and then shutdown the channel.
    goAwayAndClose(ctx, NO_ERROR.code(), EMPTY_BUFFER, promise);
  }

  private void onHeadersRead(ChannelHandlerContext ctx, int streamId, Http2Headers headers)
      throws Http2Exception {
    try {
      NettyServerStream stream = new NettyServerStream(ctx.channel(), streamId, inboundFlow);
      // The Http2Stream object was put by AbstractHttp2ConnectionHandler before calling this
      // method.
      Http2Stream http2Stream = connection().requireStream(streamId);
      http2Stream.data(stream);
      String method = determineMethod(streamId, headers);
      ServerStreamListener listener =
          transportListener.streamCreated(stream, method, Utils.convertHeaders(headers));
      stream.setListener(listener);
    } catch (Http2Exception e) {
      throw e;
    } catch (Throwable e) {
      logger.log(Level.WARNING, "Exception in onHeadersRead()", e);
      throw newStreamException(streamId, e);
    }
  }

  private void onDataRead(int streamId, ByteBuf data, boolean endOfStream) throws Http2Exception {
    try {
      NettyServerStream stream = serverStream(connection().requireStream(streamId));
      stream.inboundDataReceived(data, endOfStream);
    } catch (Http2Exception e) {
      throw e;
    } catch (Throwable e) {
      logger.log(Level.WARNING, "Exception in onDataRead()", e);
      throw newStreamException(streamId, e);
    }
  }

  private void onRstStreamRead(int streamId) throws Http2Exception {
    try {
      NettyServerStream stream = serverStream(connection().requireStream(streamId));
      stream.abortStream(Status.CANCELLED, false);
    } catch (Http2Exception e) {
      throw e;
    } catch (Throwable e) {
      logger.log(Level.WARNING, "Exception in onRstStreamRead()", e);
      throw newStreamException(streamId, e);
    }
  }

  @Override
  protected void onConnectionError(ChannelHandlerContext ctx, Throwable cause,
      Http2Exception http2Ex) {
    connectionError = cause;
    Http2Error error = http2Ex != null ? http2Ex.error() : Http2Error.INTERNAL_ERROR;

    // Write the GO_AWAY frame to the remote endpoint and then shutdown the channel.
    goAwayAndClose(ctx, error.code(), toByteBuf(ctx, cause), ctx.newPromise());
  }

  @Override
  protected void onStreamError(ChannelHandlerContext ctx, Throwable cause,
      Http2StreamException http2Ex) {
    Http2Stream stream = connection().stream(http2Ex.streamId());
    if (stream != null) {
      // Abort the stream with a status to help the client with debugging.
      // Don't need to send a RST_STREAM since the end-of-stream flag will
      // be sent.
      serverStream(stream).abortStream(Status.fromThrowable(cause), true);
    } else {
      // Delegate to the base class to send a RST_STREAM.
      super.onStreamError(ctx, cause, http2Ex);
    }
  }

  /**
   * Handler for the Channel shutting down
   */
  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    super.channelInactive(ctx);
    // Any streams that are still active must be closed
    for (Http2Stream stream : connection().activeStreams()) {
      serverStream(stream).abortStream(GOAWAY_STATUS, false);
    }
  }

  /**
   * Handler for commands sent from the stream.
   */
  @Override
  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
      throws Http2Exception {
    if (msg instanceof SendGrpcFrameCommand) {
      sendGrpcFrame(ctx, (SendGrpcFrameCommand) msg, promise);
    } else if (msg instanceof SendResponseHeadersCommand) {
      sendResponseHeaders(ctx, (SendResponseHeadersCommand) msg, promise);
    } else {
      AssertionError e =
          new AssertionError("Write called for unexpected type: " + msg.getClass().getName());
      ReferenceCountUtil.release(msg);
      promise.setFailure(e);
      throw e;
    }
  }

  private void closeStreamWhenDone(ChannelPromise promise, int streamId) throws Http2Exception {
    final NettyServerStream stream = serverStream(connection().requireStream(streamId));
    promise.addListener(new ChannelFutureListener() {
      @Override
      public void operationComplete(ChannelFuture future) {
        stream.complete();
      }
    });
  }

  /**
   * Sends the given gRPC frame to the client.
   */
  private void sendGrpcFrame(ChannelHandlerContext ctx, SendGrpcFrameCommand cmd,
      ChannelPromise promise) throws Http2Exception {
    if (cmd.endStream()) {
      closeStreamWhenDone(promise, cmd.streamId());
    }
    // Call the base class to write the HTTP/2 DATA frame.
    encoder().writeData(ctx, cmd.streamId(), cmd.content(), 0, cmd.endStream(), promise);
    ctx.flush();
  }

  /**
   * Sends the response headers to the client.
   */
  private void sendResponseHeaders(ChannelHandlerContext ctx, SendResponseHeadersCommand cmd,
      ChannelPromise promise) throws Http2Exception {
    if (cmd.endOfStream()) {
      closeStreamWhenDone(promise, cmd.streamId());
    }
    encoder().writeHeaders(ctx, cmd.streamId(), cmd.headers(), 0, cmd.endOfStream(), promise);
    ctx.flush();
  }

  /**
   * Writes a {@code GO_AWAY} frame to the remote endpoint. When it completes, shuts down
   * the channel.
   */
  private void goAwayAndClose(final ChannelHandlerContext ctx, int errorCode, ByteBuf data,
      ChannelPromise promise) {
    if (connection().goAwaySent()) {
      // Already sent the GO_AWAY. Do nothing.
      return;
    }

    // Write the GO_AWAY frame to the remote endpoint.
    int lastKnownStream = connection().remote().lastStreamCreated();
    ChannelFuture future = writeGoAway(ctx, lastKnownStream, errorCode, data, promise);

    // When the write completes, close this channel.
    future.addListener(new ChannelFutureListener() {
      @Override
      public void operationComplete(ChannelFuture future) throws Exception {
        ctx.close();
      }
    });
  }

  private String determineMethod(int streamId, Http2Headers headers)
      throws Http2StreamException {
    if (!HTTP_METHOD.equals(headers.method())) {
      throw new Http2StreamException(streamId, Http2Error.REFUSED_STREAM,
          String.format("Method '%s' is not supported", headers.method()));
    }
    if (!CONTENT_TYPE_GRPC.equals(headers.get(CONTENT_TYPE_HEADER))) {
      throw new Http2StreamException(streamId, Http2Error.REFUSED_STREAM,
          String.format("Header '%s'='%s', while '%s' is expected", CONTENT_TYPE_HEADER,
          headers.get(CONTENT_TYPE_HEADER), CONTENT_TYPE_GRPC));
    }
    String methodName = TransportFrameUtil.getFullMethodNameFromPath(headers.path().toString());
    if (methodName == null) {
      throw new Http2StreamException(streamId, Http2Error.REFUSED_STREAM,
          String.format("Malformatted path: %s", headers.path()));
    }
    return methodName;
  }

  /**
   * Returns the server stream associated to the given HTTP/2 stream object
   */
  private NettyServerStream serverStream(Http2Stream stream) {
    return stream.<NettyServerStream>data();
  }

  private Http2StreamException newStreamException(int streamId, Throwable cause) {
    return new Http2StreamException(streamId, Http2Error.INTERNAL_ERROR, cause.getMessage(), cause);
  }

  private static class LazyFrameListener extends Http2FrameAdapter {
    private NettyServerHandler handler;

    void setHandler(NettyServerHandler handler) {
      this.handler = handler;
    }

    @Override
    public void onDataRead(ChannelHandlerContext ctx, int streamId, ByteBuf data, int padding,
        boolean endOfStream) throws Http2Exception {
      handler.onDataRead(streamId, data, endOfStream);
    }

    @Override
    public void onHeadersRead(ChannelHandlerContext ctx,
        int streamId,
        Http2Headers headers,
        int streamDependency,
        short weight,
        boolean exclusive,
        int padding,
        boolean endStream) throws Http2Exception {
      handler.onHeadersRead(ctx, streamId, headers);
    }

    @Override
    public void onRstStreamRead(ChannelHandlerContext ctx, int streamId, long errorCode)
        throws Http2Exception {
      handler.onRstStreamRead(streamId);
    }
  }
}
