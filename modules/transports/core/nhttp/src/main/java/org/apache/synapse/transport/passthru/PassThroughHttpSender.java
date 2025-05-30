/*
 *  Copyright (c) 2009, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.synapse.transport.passthru;

import org.apache.axiom.om.OMOutputFormat;
import org.apache.axiom.util.blob.OverflowBlob;
import org.apache.axis2.AxisFault;
import org.apache.axis2.Constants;
import org.apache.axis2.addressing.AddressingConstants;
import org.apache.axis2.addressing.AddressingHelper;
import org.apache.axis2.addressing.EndpointReference;
import org.apache.axis2.context.ConfigurationContext;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.description.TransportOutDescription;
import org.apache.axis2.handlers.AbstractHandler;
import org.apache.axis2.transport.MessageFormatter;
import org.apache.axis2.transport.OutTransportInfo;
import org.apache.axis2.transport.TransportSender;
import org.apache.axis2.transport.base.BaseConstants;
import org.apache.axis2.transport.base.threads.NativeThreadFactory;
import org.apache.axis2.transport.base.threads.WorkerPool;
import org.apache.axis2.transport.http.HTTPConstants;
import org.apache.commons.io.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpException;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpStatus;
import org.apache.http.impl.nio.reactor.DefaultConnectingIOReactor;
import org.apache.http.nio.NHttpClientConnection;
import org.apache.http.nio.NHttpServerConnection;
import org.apache.http.nio.reactor.IOReactorException;
import org.apache.http.nio.reactor.IOReactorExceptionHandler;
import org.apache.http.protocol.HTTP;
import org.apache.synapse.commons.CorrelationConstants;
import org.apache.synapse.transport.exceptions.InvalidConfigurationException;
import org.apache.synapse.transport.http.conn.ClientConnFactory;
import org.apache.synapse.transport.http.conn.ProxyConfig;
import org.apache.synapse.transport.http.conn.Scheme;
import org.apache.synapse.transport.nhttp.NhttpConstants;
import org.apache.synapse.transport.nhttp.config.ClientConnFactoryBuilder;
import org.apache.synapse.transport.nhttp.config.ProxyConfigBuilder;
import org.apache.synapse.transport.nhttp.util.MessageFormatterDecoratorFactory;
import org.apache.synapse.transport.nhttp.util.NhttpUtil;
import org.apache.synapse.transport.passthru.config.SourceConfiguration;
import org.apache.synapse.transport.passthru.config.TargetConfiguration;
import org.apache.synapse.transport.passthru.connections.TargetConnections;
import org.apache.synapse.transport.passthru.core.PassThroughSenderManager;
import org.apache.synapse.transport.passthru.jmx.MBeanRegistrar;
import org.apache.synapse.transport.passthru.jmx.PassThroughTransportMetricsCollector;
import org.apache.synapse.transport.passthru.jmx.TransportView;
import org.apache.synapse.transport.passthru.util.PassThroughTransportUtils;
import org.apache.synapse.transport.passthru.util.RelayUtils;
import org.apache.synapse.transport.passthru.util.SourceResponseFactory;
import org.apache.synapse.transport.passthru.util.StreamInterceptorsLoader;
import org.wso2.caching.CachingConstants;
import org.wso2.caching.digest.DigestGenerator;

import java.io.IOException;
import java.io.OutputStream;
import java.rmi.RemoteException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import javax.xml.stream.XMLStreamException;

/**
 * PassThroughHttpSender for Synapse based on HttpCore and NIO extensions
 */
public class PassThroughHttpSender extends AbstractHandler implements TransportSender {

    protected Log log;

    /** IOReactor used to create connections and manage them */
    private DefaultConnectingIOReactor ioReactor;
    /** Protocol handler */
    private TargetHandler handler;
    /** I/O dispatcher */
    private ClientIODispatch ioEventDispatch;
    /** The connection factory */
    private ClientConnFactory connFactory;
    
    /** Delivery agent used for delivering the messages to the servers */
    private DeliveryAgent deliveryAgent;

    /** The protocol scheme of the sender */
    private Scheme scheme;
    /** The configuration of the sender */
    private TargetConfiguration targetConfiguration;

    /** Proxy config */
    private ProxyConfig proxyConfig;

    // manage target connections
    private TargetConnections targetConnections;
    
    /** state of the sender */
    private volatile int state = BaseConstants.STOPPED;

    private String namePrefix;

    private DigestGenerator digestGenerator  = CachingConstants.DEFAULT_XML_IDENTIFIER;

    private ConfigurationContext configurationContext;

    private List<StreamInterceptor> interceptors;

    public PassThroughHttpSender() {
        log = LogFactory.getLog(this.getClass().getName());
    }

    protected Scheme getScheme() {
        return new Scheme("http", 80, false);
    }
    
    protected ClientConnFactoryBuilder initConnFactoryBuilder(
            final TransportOutDescription transportOut, ConfigurationContext configurationContext) throws AxisFault {
        return new ClientConnFactoryBuilder(transportOut, configurationContext);
    }
    
    public void init(ConfigurationContext configurationContext,
                     TransportOutDescription transportOutDescription) throws AxisFault {
        log.info("Initializing Pass-through HTTP/S Sender...");

        this.configurationContext = configurationContext;
        namePrefix = transportOutDescription.getName().toUpperCase(Locale.US);
        scheme = getScheme();
        
        WorkerPool workerPool = null;
        Object obj = configurationContext.getProperty(
                PassThroughConstants.PASS_THROUGH_TRANSPORT_WORKER_POOL);
        if (obj != null) {
            workerPool = (WorkerPool) obj;                                   
        }

        PassThroughTransportMetricsCollector metrics = new PassThroughTransportMetricsCollector(
            false, scheme.getName());
        
        proxyConfig = new ProxyConfigBuilder().build(transportOutDescription);
        if (log.isDebugEnabled()) {
            log.debug(proxyConfig.logProxyConfig());
        }

        targetConfiguration = new TargetConfiguration(configurationContext,
                transportOutDescription, workerPool, metrics,
                proxyConfig.createProxyAuthenticator());
        targetConfiguration.build();

        PassThroughSenderManager.registerPassThroughHttpSender(this);


        configurationContext.setProperty(PassThroughConstants.PASS_THROUGH_TRANSPORT_WORKER_POOL,
                targetConfiguration.getWorkerPool());
        
        ClientConnFactoryBuilder connFactoryBuilder = initConnFactoryBuilder(transportOutDescription, configurationContext);
        connFactory = connFactoryBuilder.createConnFactory(targetConfiguration.getHttpParams());
        
        try {
            String prefix = namePrefix + "-Sender I/O dispatcher";

            ioReactor = new DefaultConnectingIOReactor(
                            targetConfiguration.getIOReactorConfig(),
                            new NativeThreadFactory(new ThreadGroup(prefix + " Thread Group"), prefix));

            ioReactor.setExceptionHandler(new IOReactorExceptionHandler() {

                public boolean handle(IOException ioException) {
                    log.warn("System may be unstable: " + namePrefix +
                            " ConnectingIOReactor encountered a checked exception : " +
                            ioException.getMessage(), ioException);
                    return true;
                }

                public boolean handle(RuntimeException runtimeException) {
                    log.warn("System may be unstable: " + namePrefix +
                            " ConnectingIOReactor encountered a runtime exception : "
                            + runtimeException.getMessage(), runtimeException);
                    return true;
                }
            });
        } catch (IOReactorException e) {
            handleException("Error starting " + namePrefix + " ConnectingIOReactor", e);
        }

        ConnectCallback connectCallback = new ConnectCallback();

        targetConnections = new TargetConnections(ioReactor, targetConfiguration, connectCallback);
        targetConfiguration.setConnections(targetConnections);

        TransportView view = new TransportView(null, this, metrics, targetConfiguration.getWorkerPool());
        MBeanRegistrar.getInstance().registerMBean(view, "Transport",
                "passthru-" + namePrefix.toLowerCase() + "-sender");

        // create the delivery agent to hand over messages
        deliveryAgent = new DeliveryAgent(targetConfiguration, targetConnections, proxyConfig);
        // we need to set the delivery agent
        connectCallback.setDeliveryAgent(deliveryAgent);

        interceptors = StreamInterceptorsLoader.getInterceptors();
        handler = new TargetHandler(deliveryAgent, connFactory, targetConfiguration , interceptors);
        ioEventDispatch = new ClientIODispatch(handler, connFactory);
        
        // start the sender in a separate thread
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    ioReactor.execute(ioEventDispatch);
                } catch (Exception ex) {
                   log.fatal("Exception encountered in the " + namePrefix + " Sender. " +
                            "No more connections will be initiated by this transport", ex);
                }
                log.info(namePrefix + " Sender shutdown");
            }
        }, "PassThrough" + namePrefix + "Sender");
        t.start();

        state = BaseConstants.STARTED;

        log.info("Pass-through " + namePrefix + " Sender started...");
    }

    public void cleanup(org.apache.axis2.context.MessageContext messageContext) throws AxisFault {

    }

    public void stop() {
        try {
            ioReactor.shutdown();
        } catch (IOException e) {
            log.error("Error shutting down the PassThroughHttpSender", e);
        }
    }


    public InvocationResponse invoke(org.apache.axis2.context.MessageContext msgContext) throws AxisFault {
        // remove unwanted HTTP headers (if any from the current message)

        PassThroughTransportUtils.removeUnwantedHeaders(msgContext, targetConfiguration);

        if (AddressingHelper.isReplyRedirected(msgContext)
                && !msgContext.getReplyTo().hasNoneAddress()) {

            msgContext.setProperty(PassThroughConstants.IGNORE_SC_ACCEPTED, Constants.VALUE_TRUE);
        }

        EndpointReference epr = PassThroughTransportUtils.getDestinationEPR(msgContext);
        if (epr != null) {
            if (!epr.hasNoneAddress()) {
                if (msgContext.getProperty(PassThroughConstants.PASS_THROUGH_PIPE) == null) {
                    Pipe pipe = new Pipe(targetConfiguration.getBufferFactory().getBuffer(),
                            "Test", targetConfiguration);
                    msgContext.setProperty(PassThroughConstants.PASS_THROUGH_PIPE, pipe);
                    msgContext.setProperty(PassThroughConstants.MESSAGE_BUILDER_INVOKED, Boolean.TRUE);
                }

                // NOTE:this a special case where, when the backend service expects content-length but,there is no
                // desire that the message should be build, if FORCE_HTTP_CONTENT_LENGTH and
                // COPY_CONTENT_LENGTH_FROM_INCOMING, we assume that the content coming from the client side has not
                // been changed
                boolean forceContentLength = msgContext.isPropertyTrue(NhttpConstants.FORCE_HTTP_CONTENT_LENGTH);
                boolean forceContentLengthCopy = msgContext
                        .isPropertyTrue(PassThroughConstants.COPY_CONTENT_LENGTH_FROM_INCOMING);

                if (forceContentLength && forceContentLengthCopy
                        && msgContext.getProperty(PassThroughConstants.ORGINAL_CONTEN_LENGTH) != null) {
                    msgContext.setProperty(PassThroughConstants.PASSTROUGH_MESSAGE_LENGTH, Long.parseLong(
                            (String) msgContext.getProperty(PassThroughConstants.ORGINAL_CONTEN_LENGTH)));
                }

                sendRequestContent(msgContext, epr);
            } else {
                handleException("Cannot send message to " + AddressingConstants.Final.WSA_NONE_URI);
            }
        } else {
            if (msgContext.getProperty(Constants.OUT_TRANSPORT_INFO) != null) {
                if (msgContext.getProperty(Constants.OUT_TRANSPORT_INFO) instanceof ServerWorker) {
                    try {
                        submitResponse(msgContext);
                    } catch (Exception e) {
                        handleException("Failed to submit the response", e);
                    }
                }else {
                    //handleException("No valid destination EPR to send message");
                	//should be able to handle sendUsingOutputStream  Ref NHTTP_NIO
                	sendUsingOutputStream(msgContext);
                }
            } else {
                handleException("No valid destination EPR to send message");
            }
        }

        if (msgContext.getOperationContext() != null) {
            msgContext.getOperationContext().setProperty(
                Constants.RESPONSE_WRITTEN, Constants.VALUE_TRUE);
        }

        return InvocationResponse.CONTINUE;
    }
    
    
    private void sendUsingOutputStream(MessageContext msgContext) throws AxisFault {

        OMOutputFormat format = NhttpUtil.getOMOutputFormat(msgContext);
        MessageFormatter messageFormatter =
                MessageFormatterDecoratorFactory.createMessageFormatterDecorator(msgContext);
        OutputStream out = (OutputStream) msgContext.getProperty(MessageContext.TRANSPORT_OUT);

        if (msgContext.isServerSide()) {
            OutTransportInfo transportInfo =
                (OutTransportInfo) msgContext.getProperty(Constants.OUT_TRANSPORT_INFO);

            if (transportInfo != null) {
                transportInfo.setContentType(
                messageFormatter.getContentType(msgContext, format, msgContext.getSoapAction()));
            } else {
                throw new AxisFault(Constants.OUT_TRANSPORT_INFO + " has not been set");
            }
        }

        try {
            messageFormatter.writeTo(msgContext, format, out, false);
            out.close();
        } catch (IOException e) {
            handleException("IO Error sending response message", e);
        }
    }

    public void addPreserveHttpHeader(String headerName) {
        targetConfiguration.getPreserveHttpHeaders().add(headerName.toUpperCase());
    }

    public void removePreserveHttpHeader(String headerName) {
        targetConfiguration.getPreserveHttpHeaders().remove(headerName.toUpperCase());
    }

    private boolean waitForReady(final MessageContext msgContext) {
        synchronized (msgContext) {
            while (!Boolean.TRUE.equals(msgContext.getProperty(PassThroughConstants.WAIT_BUILDER_IN_STREAM_COMPLETE)) &&
                   !Boolean.TRUE.equals(msgContext.getProperty("PASSTHRU_CONNECT_ERROR"))) {
                try {
                    msgContext.wait();
                } catch (InterruptedException e) {
                    log.error("Interrupted while waiting for passthru connection..", e);
                }
            }
        }

        if (Boolean.TRUE.equals(msgContext.getProperty("PASSTHRU_CONNECT_ERROR"))) {
            return false;
        }

        return true;
    }

    private void sendRequestContent(final MessageContext msgContext, final EndpointReference epr) throws AxisFault {

        boolean hasNoMessageBody = HTTPConstants.HTTP_METHOD_GET.equals(msgContext.getProperty(Constants.Configuration.
                HTTP_METHOD)) || RelayUtils.isDeleteRequestWithoutPayload(msgContext);
        // consume the buffer completely before sending a GET request or DELETE request without a payload
        if (hasNoMessageBody) {
            RelayUtils.discardMessage(msgContext);
        }

        if (Boolean.TRUE.equals(msgContext.getProperty(PassThroughConstants.MESSAGE_BUILDER_INVOKED))) {
            String disableChunking = (String) msgContext.getProperty(PassThroughConstants.DISABLE_CHUNKING);
            String forceHttp10 = (String) msgContext.getProperty(PassThroughConstants.FORCE_HTTP_1_0);
            Pipe pipe = (Pipe) msgContext.getProperty(PassThroughConstants.PASS_THROUGH_PIPE);
            OutputStream out = null;
            MessageFormatter formatter = MessageFormatterDecoratorFactory.createMessageFormatterDecorator(msgContext);
            OMOutputFormat format = PassThroughTransportUtils.getOMOutputFormat(msgContext);

            if ("true".equals(disableChunking) || "true".equals(forceHttp10)) {

                try {
                    OverflowBlob overflowBlob = setStreamAsTempData(formatter, msgContext, format, hasNoMessageBody);
                    long messageSize = overflowBlob.getLength();
                    msgContext.setProperty(PassThroughConstants.PASSTROUGH_MESSAGE_LENGTH, messageSize);
                    if (!deliveryAgent.submit(msgContext, epr)) {
                        return;
                    }
                    if (!waitForReady(msgContext)) {
                        return;
                    }
                    out = (OutputStream) msgContext.getProperty(PassThroughConstants.BUILDER_OUTPUT_STREAM);
                    if (out != null) {
                        // Check HTTP method is GET or DELETE with no body
                        if (ignoreMessageBody(pipe, hasNoMessageBody)) {
                            return;
                        }
                        overflowBlob.writeTo(out);
                        if (pipe.isStale) {
                            throw new IOException("Target Connection is stale..");
                        }
                        pipe.setSerializationComplete(true);
                    }
                } catch (IOException e) {
                    handleException("IO while building message", e);
                }
            } else {
                if (!deliveryAgent.submit(msgContext, epr)) {
                    return;
                }
                if (!waitForReady(msgContext)) {
                    return;
                }
                out = (OutputStream) msgContext.getProperty(PassThroughConstants.BUILDER_OUTPUT_STREAM);
                if (out != null) {
                    // Check HTTP method is GET or DELETE with no body
                    if (ignoreMessageBody(pipe, hasNoMessageBody)) {
                        return;
                    }
                    formatter.writeTo(msgContext, format, out, false);
                    if (pipe.isStale) {
                        handleException("IO while building message", new IOException("Target Connection is stale.."));
                    }
                    pipe.setSerializationComplete(true);
                }
            }
        } else {
            if (!deliveryAgent.submit(msgContext, epr)) {
                return;
            }
        }
    }

    // If the HTTP method is GET or DELETE with no body, we need to write down the HEADER information to the wire
    // and need to ignore any entity enclosed methods available.
    private boolean ignoreMessageBody(Pipe pipe, boolean hasNoMessageBody) {
        if (hasNoMessageBody) {
            pipe.setSerializationComplete(true);
            return true;
        }
        return false;
    }


    /**
     * Write the stream to a temporary storage and calculate the content length
     *
     * @throws IOException if an exception occurred while writing data
     */
    private OverflowBlob setStreamAsTempData(MessageFormatter messageFormatter, MessageContext msgContext,
                                             OMOutputFormat format, boolean hasNoMessageBody) throws IOException {
        OverflowBlob serialized = new OverflowBlob(256, 4096, "http-nio_", ".dat");
        if (hasNoMessageBody) {
            return serialized;
        }
        OutputStream out = serialized.getOutputStream();
        try {
            messageFormatter.writeTo(msgContext, format, out, false);
        } finally {
            out.close();
        }
       return serialized;
    }

    public void submitResponse(MessageContext msgContext)
            throws IOException, HttpException {
        SourceConfiguration sourceConfiguration = (SourceConfiguration) msgContext.getProperty(
                        PassThroughConstants.PASS_THROUGH_SOURCE_CONFIGURATION);

        NHttpServerConnection conn = (NHttpServerConnection) msgContext.getProperty(
                PassThroughConstants.PASS_THROUGH_SOURCE_CONNECTION);
        if (conn == null) {
            ServerWorker serverWorker = (ServerWorker) msgContext.getProperty(Constants.OUT_TRANSPORT_INFO);
            if (serverWorker != null) {
                MessageContext requestContext = serverWorker.getRequestContext();
                conn = (NHttpServerConnection) requestContext.getProperty(
                        PassThroughConstants.PASS_THROUGH_SOURCE_CONNECTION);
                sourceConfiguration = (SourceConfiguration) requestContext.getProperty(
                        PassThroughConstants.PASS_THROUGH_SOURCE_CONFIGURATION);
            } else {
                throw new IllegalStateException("Unable to correlate the response to a request");
            }
        }

        // Handle ETag caching
        if (msgContext.getProperty(PassThroughConstants.HTTP_ETAG_ENABLED) != null
            && (Boolean) msgContext.getProperty(PassThroughConstants.HTTP_ETAG_ENABLED)) {

            try {
                RelayUtils.buildMessage(msgContext);
            } catch (IOException e) {
                handleException("IO Error occurred while building the message", e);
            } catch (XMLStreamException e) {
                handleException("XML Error occurred while building the message", e);
            }

            String hash = digestGenerator.getDigest(msgContext);
            Map headers = (Map) msgContext.getProperty(MessageContext.TRANSPORT_HEADERS);
            headers.put(HttpHeaders.ETAG,"\""+hash+"\"");
        }

        if (msgContext.getProperty(Constants.Configuration.ENABLE_MTOM) != null
                && !Boolean.TRUE.equals(msgContext.getProperty(PassThroughConstants.MESSAGE_BUILDER_INVOKED))) {
            try {
                RelayUtils.buildMessage(msgContext);
            } catch (IOException e) {
                handleException("IO Error occurred while building the message", e);
            } catch (XMLStreamException e) {
                handleException("XML Error occurred while building the message", e);
            }
        }

        SourceRequest sourceRequest = SourceContext.getRequest(conn);

        if (sourceRequest == null) { // We'll get here if the connection is already closed
            //this is a special case we dropped source connection where message size exceeds the user defined threshold
            if (conn.getContext().getAttribute(PassThroughConstants.SOURCE_CONNECTION_DROPPED) != null &&
                    (Boolean) conn.getContext().getAttribute(PassThroughConstants.SOURCE_CONNECTION_DROPPED)) {
                //already submitted response for this case, hence return
                return;
            }
            log.warn("Trying to submit a response to an already closed connection : " + conn);
            return;
        }

        SourceResponse sourceResponse = SourceResponseFactory.create(msgContext,
                sourceRequest, sourceConfiguration);
        sourceResponse.checkResponseChunkDisable(msgContext);

        conn.getContext().setAttribute(PassThroughConstants.RESPONSE_MESSAGE_CONTEXT, msgContext);

        SourceContext.setResponse(conn, sourceResponse);
        Boolean noEntityBody = (Boolean) msgContext.getProperty(PassThroughConstants.NO_ENTITY_BODY);
        Pipe pipe = (Pipe) msgContext.getProperty(PassThroughConstants.PASS_THROUGH_PIPE);
        if ((noEntityBody == null || !noEntityBody) || pipe != null) {
            if (pipe == null) {
                pipe = new Pipe(sourceConfiguration.getBufferFactory().getBuffer(),
                        "Test", sourceConfiguration);
                msgContext.setProperty(PassThroughConstants.PASS_THROUGH_PIPE, pipe);
                msgContext.setProperty(PassThroughConstants.MESSAGE_BUILDER_INVOKED, Boolean.TRUE);
            }

            pipe.attachConsumer(conn);
            sourceResponse.connect(pipe);
        }

        Integer errorCode = (Integer) msgContext.getProperty(PassThroughConstants.ERROR_CODE);
        if (errorCode != null) {
            sourceResponse.setStatus(HttpStatus.SC_BAD_GATEWAY);
            SourceContext.get(conn).setShutDown(true);
        }

        ProtocolState state = SourceContext.getState(conn);
        if (state != null && state.compareTo(ProtocolState.REQUEST_DONE) <= 0) {
            // start sending the response if we
        	
			boolean noEntityBodyResponse = false;
			if (noEntityBody != null && Boolean.TRUE == noEntityBody
					&& pipe != null) {
				OutputStream out = pipe.getOutputStream();
				out.write(new byte[0]);
				pipe.setRawSerializationComplete(true);
				out.close();
				noEntityBodyResponse = true;
			}
			
            if (!noEntityBodyResponse && msgContext.isPropertyTrue(PassThroughConstants.MESSAGE_BUILDER_INVOKED) && pipe != null) {
                OutputStream out = pipe.getOutputStream();
                /*if (msgContext.isPropertyTrue(NhttpConstants.SC_ACCEPTED)) {
                    out.write(new byte[0]);
                }else {*/

                //This is to support MTOM in response path for requests sent without a SOAPAction. The reason is
                //axis2 selects application/xml formatter as the formatter for formatting the ESB to client response
                //when there is no SOAPAction.
                if (Constants.VALUE_TRUE.equals(msgContext.getProperty(Constants.Configuration.ENABLE_MTOM)) ||
                        Constants.VALUE_TRUE.equals(msgContext.getProperty(Constants.Configuration.ENABLE_SWA))) {
                    Object contentType = msgContext.getProperty(Constants.Configuration.CONTENT_TYPE);
                    if (Objects.isNull(contentType) ||
                            !((String) contentType).trim()
                                                   .startsWith(PassThroughConstants.CONTENT_TYPE_MULTIPART_RELATED)) {
                        msgContext.setProperty(Constants.Configuration.CONTENT_TYPE,
                                               PassThroughConstants.CONTENT_TYPE_MULTIPART_RELATED);
                    }
                    msgContext.setProperty(Constants.Configuration.MESSAGE_TYPE,
                                           PassThroughConstants.CONTENT_TYPE_MULTIPART_RELATED);
                }

                MessageFormatter formatter = MessageFormatterDecoratorFactory.createMessageFormatterDecorator(msgContext);
                OMOutputFormat format = PassThroughTransportUtils.getOMOutputFormat(msgContext);
                setContentType(msgContext, sourceResponse, formatter, format, sourceConfiguration);
                try {
                    formatter.writeTo(msgContext, format, out, false);
                } catch (RemoteException fault) {
                    IOUtils.closeQuietly(out);
                    throw fault;
                } finally {
                    //Serialization should be set as complete so that the state of the socket can be
                    // reset to readable
                    pipe.setSerializationComplete(true);
                }
                out.close();
            }
            
            conn.requestOutput();
        } else {
            // nothing much to do as we have started the response already
            if (errorCode != null) {
                if (log.isDebugEnabled()) {
                    log.warn("A Source connection is closed because of an " +
                            "error in target: " + conn);
                }
            } else {
                log.debug("A Source Connection is closed, because source handler " +
                          "is already in the process of writing a response while " +
                          "another response is submitted: " + conn);
            }

            pipe.consumerError();
            SourceContext.updateState(conn, ProtocolState.CLOSED);
            sourceConfiguration.getSourceConnections().shutDownConnection(conn, true);
        }
    }

    /**
     * This method will close the target connection upon endpoint timeout.
     *
     * @param msgContext The outgoing message context sent via the sender
     */
    @Override
    public void onAppError(MessageContext msgContext) {

        NHttpClientConnection targetConn = (NHttpClientConnection) msgContext.getProperty(
                PassThroughConstants.PASS_THROUGH_TARGET_CONNECTION);

        TargetConfiguration targetConfiguration = (TargetConfiguration) msgContext.getProperty(
                PassThroughConstants.PASS_THROUGH_TARGET_CONFIGURATION);

        if (targetConn == null || targetConfiguration == null) {
            log.warn("Unable to update target connection state to CLOSED upon endpoint timeout.");
            return;
        }
        TargetContext.updateState(targetConn, ProtocolState.CLOSED);
        targetConfiguration.getConnections().closeConnection(targetConn, true);
        if (log.isDebugEnabled()) {
            log.debug("STATE_DESCRIPTION = Disconnected the target connection upon endpoint timeout"
                    + ", TARGET_HOST = " + msgContext.getProperty(NhttpConstants.REMOTE_HOST)
                    + ", TARGET_CONTEXT = " + msgContext.getProperty(NhttpConstants.ENDPOINT_PREFIX)
                    + ", HTTP_METHOD = " + msgContext.getProperty(HTTPConstants.HTTP_METHOD)
                    + ", TRIGGER_TYPE = " + msgContext.getProperty(PassThroughConstants.INTERNAL_TRIGGER_TYPE)
                    + ", TRIGGER_NAME = " + msgContext.getProperty(PassThroughConstants.INTERNAL_TRIGGER_NAME)
                    + ", SEND_TIMEOUT = " + msgContext.getProperty(NhttpConstants.SEND_TIMEOUT)
                    + ", CORRELATION_ID = " + msgContext.getProperty(CorrelationConstants.CORRELATION_ID));
        }
    }

    public void pause() throws AxisFault {
        if (state != BaseConstants.STARTED) {
            return;
        }
        state = BaseConstants.PAUSED;
        log.info(namePrefix + " Sender Paused");
    }

    public void resume() throws AxisFault {
        if (state != BaseConstants.PAUSED) {
            return;
        }
        state = BaseConstants.STARTED;
        log.info(namePrefix + " Sender Resumed");
    }

    public void maintenanceShutdown(long millis) throws AxisFault {
        if (state != BaseConstants.STARTED) return;
        try {
            long start = System.currentTimeMillis();
            ioReactor.shutdown(millis);
            state = BaseConstants.STOPPED;
            log.info("Sender shutdown in : " + (System.currentTimeMillis() - start) / 1000 + "s");
        } catch (IOException e) {
            handleException("Error shutting down the IOReactor for maintenence", e);
        }
    }

    private void handleException(String s, Exception e) throws AxisFault {
        log.error(s, e);
        throw new AxisFault(s, e);
    }

    private void handleException(String msg) throws AxisFault {
        log.error(msg);
        throw new AxisFault(msg);
    }

    /**
     * Reload SSL configurations from configurations, reset all connections and restart the thread
     *
     * @param transport TransportOutDescription of the configuration
     * @throws AxisFault
     */
    public void reloadDynamicSSLConfig(TransportOutDescription transport) throws AxisFault {
        log.info("PassThroughHttpSender reloading SSL Config..");
        try {
            ClientConnFactoryBuilder connFactoryBuilder = initConnFactoryBuilder(transport, this.configurationContext);
            connFactory = connFactoryBuilder.createConnFactory(targetConfiguration.getHttpParams());

            //Set new configurations
            handler.setConnFactory(connFactory);
            ioEventDispatch.setConnFactory(connFactory);

            //close existing connections to apply new settings
            targetConnections.resetConnectionPool(connFactory.getHostList());

            log.info("Pass-through " + namePrefix + " Sender updated with Dynamic Configuration Updates ...");

        } catch (InvalidConfigurationException configFault) {
            log.error("Ignoring reload SSL config since there is an invalid configuration.", configFault);
        }
    }

    public void reloadSSL(TransportOutDescription transport) throws AxisFault {
        log.info("PassThroughHttpSender SSL Config..");
        ClientConnFactoryBuilder connFactoryBuilder =
                initConnFactoryBuilder(transport, this.configurationContext).parseSSL();
        connFactory = connFactoryBuilder.createConnFactory(targetConfiguration.getHttpParams());

        handler.setConnFactory(connFactory);
        ioEventDispatch.setConnFactory(connFactory);

        log.info("Pass-through " + namePrefix + " Sender updated with SSL Configuration Updates ...");
    }

    /**
     * Set content type headers along with the charactor encoding if content type header is not preserved
     * @param msgContext    message context
     * @param sourceResponse    source response
     * @param formatter response formatter
     * @param format    response format
     */
    public void setContentType(MessageContext msgContext, SourceResponse sourceResponse, MessageFormatter formatter,
                               OMOutputFormat format, SourceConfiguration sourceConfiguration) {
        if (sourceConfiguration.isPreserveHttpHeader(HTTP.CONTENT_TYPE)) {
            return;
        }
        Object contentTypeInMsgCtx =
                msgContext.getProperty(org.apache.axis2.Constants.Configuration.CONTENT_TYPE);
        boolean isContentTypeSetFromMsgCtx = false;

        // If ContentType header is set in the axis2 message context, use it.
        if (contentTypeInMsgCtx != null) {
            String contentTypeValueInMsgCtx = contentTypeInMsgCtx.toString();
            // Skip multipart/related as it should be taken from formatter.
            if (!(contentTypeValueInMsgCtx.contains(
                    PassThroughConstants.CONTENT_TYPE_MULTIPART_RELATED) ||
                  contentTypeValueInMsgCtx.contains(PassThroughConstants.CONTENT_TYPE_MULTIPART_FORM_DATA))) {

                // adding charset only if charset is not available,
                if (format != null && contentTypeValueInMsgCtx.indexOf(HTTPConstants.CHAR_SET_ENCODING) == -1 &&
                    !"false".equals(msgContext.getProperty(PassThroughConstants.SET_CHARACTER_ENCODING))) {
                    String encoding = format.getCharSetEncoding();
                    if (encoding != null) {
                        contentTypeValueInMsgCtx += "; charset=" + encoding;
                    }
                }
                sourceResponse.removeHeader(HTTP.CONTENT_TYPE);
                sourceResponse.addHeader(HTTP.CONTENT_TYPE, contentTypeValueInMsgCtx);
                isContentTypeSetFromMsgCtx = true;
            }
        }

        // If ContentType is not set from msg context, get the formatter ContentType
        if (!isContentTypeSetFromMsgCtx) {
            sourceResponse.removeHeader(HTTP.CONTENT_TYPE);
            sourceResponse.addHeader(HTTP.CONTENT_TYPE,
                                     formatter.getContentType(
                                             msgContext, format, msgContext.getSoapAction()));
        }
    }
}
