/**
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

import org.apache.commons.collections.map.MultiValueMap;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.ConnectionReuseStrategy;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.HttpVersion;
import org.apache.http.ProtocolVersion;
import org.apache.http.entity.BasicHttpEntity;
import org.apache.http.impl.DefaultConnectionReuseStrategy;
import org.apache.http.nio.ContentDecoder;
import org.apache.http.nio.NHttpClientConnection;
import org.apache.http.protocol.HttpContext;
import org.apache.synapse.commons.CorrelationConstants;
import org.apache.synapse.transport.http.conn.LoggingNHttpClientConnection;
import org.apache.synapse.transport.passthru.config.TargetConfiguration;
import org.apache.synapse.transport.passthru.connections.HostConnections;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * This class represents a response coming from the target server.
 */
public class TargetResponse {
     private Log log = LogFactory.getLog(TargetResponse.class);
    /** To pipe the incoming data through */
    private Pipe pipe = null;
    /** Headers of the response */
    private Map<String, String> headers = new HashMap<String, String>();
    /** Excess headers of the response */
    private Map excessHeaders = new MultiValueMap();
    /** The status of the response */
    private int status = HttpStatus.SC_OK;
    /** Http status line */
    private String statusLine = "OK";
    /** The Http response */
    private HttpResponse response = null;
    /** Configuration of the sender */
    private TargetConfiguration targetConfiguration;
    /** Protocol version */
    private ProtocolVersion version = HttpVersion.HTTP_1_1;
    /** This utility class is used for determining weather we need to close the connection
     * after submitting the response */
    private ConnectionReuseStrategy connStrategy = new DefaultConnectionReuseStrategy();
    /** The connection */
    private NHttpClientConnection connection;
    /** Weather this response has a body */
    private boolean expectResponseBody = true;
    /** Whether to shutdown connection after response completion*/
    private boolean forceShutdownConnectionOnComplete = false;
    /** logger for correlation.log */
    private static final Log correlationLog = LogFactory.getLog(PassThroughConstants.CORRELATION_LOGGER);
    private static final Log transportLatencyLog = LogFactory.getLog(PassThroughConstants.TRANSPORT_LATENCY_LOGGER);

    // Constants for honoring keep-alive header coming from backend
    private static final String KEEP_ALIVE_HEADER = "Keep-Alive";
    private static final String KEEP_ALIVE_TIMEOUT = "timeout=";

    public TargetResponse(TargetConfiguration targetConfiguration,
                          HttpResponse response,
                          NHttpClientConnection conn,
                          boolean expectResponseBody,
                          boolean forceShutdownConnectionOnComplete) {
        this.targetConfiguration = targetConfiguration;
        this.response = response;
        this.connection = conn;

        this.version = response.getProtocolVersion();

        this.status = response.getStatusLine().getStatusCode();
        this.statusLine = response.getStatusLine().getReasonPhrase();

        Header[] headers = response.getAllHeaders();
        if (headers != null) {
            for (Header header : headers) {
            	if(this.headers.containsKey(header.getName())) {
            		addExcessHeader(header);
            	} else {
                    this.headers.put(header.getName(), header.getValue());
                }
                // If the keep-alive header is set
                if (header.getName().equalsIgnoreCase(KEEP_ALIVE_HEADER)) {
                    // Retrieve the keep-alive timeout
                    String keepAlive = header.getValue();
                    if (keepAlive != null && keepAlive.contains(KEEP_ALIVE_TIMEOUT)) {
                        conn.getContext().setAttribute(PassThroughConstants.CONNECTION_KEEP_ALIVE_TIME_OUT,
                                getKeepAliveTimeout(keepAlive));
                    }
                }
            }
        }

        this.expectResponseBody = expectResponseBody;
        this.forceShutdownConnectionOnComplete = forceShutdownConnectionOnComplete;
    }

    /**
     * Starts the response
     * @param conn the client connection
     */
    public void start(NHttpClientConnection conn) {
        TargetContext.updateState(conn, ProtocolState.RESPONSE_HEAD);

        if (expectResponseBody) {
            pipe
                = new Pipe(conn, targetConfiguration.getBufferFactory().getBuffer(), "target", targetConfiguration);

            TargetContext.get(conn).setReader(pipe);

            BasicHttpEntity entity = new BasicHttpEntity();
            if (response.getStatusLine().getProtocolVersion().greaterEquals(HttpVersion.HTTP_1_1)) {
                entity.setChunked(true);
            }
            response.setEntity(entity);
        } else {
            if (!connStrategy.keepAlive(response, conn.getContext()) || forceShutdownConnectionOnComplete) {
                try {
                    // this is a connection we should not re-use
                    TargetContext.updateState(conn, ProtocolState.CLOSING);
                    targetConfiguration.getConnections().shutdownConnection(conn, forceShutdownConnectionOnComplete);

                } catch (Exception ignore) {

                }
            } else {
                // Complete the state transition
                TargetContext.updateState(conn, ProtocolState.RESPONSE_DONE);
                targetConfiguration.getConnections().releaseConnection(conn);
            }
        }
    }

    /**
     * Read the data from the wire and read in to the pipe so that other end of
     * the pipe can write.
     *
     * @param conn    the target connection
     * @param decoder content decoder
     * @return number of bites read
     * @throws java.io.IOException if an error occurs
     */
    public int read(NHttpClientConnection conn, ContentDecoder decoder) throws IOException {

        int bytes = 0;
        if (pipe != null) {
            bytes = pipe.produce(decoder);
        }
        // Update connection state
        readPostActions(conn, decoder);
        return bytes;
    }

    /**
     * Same as
     * {@link TargetResponse#read(org.apache.http.nio.NHttpClientConnection, org.apache.http.nio.ContentDecoder)} but
     * gives out the data read from the wire in to the pipe.
     *
     * @param conn    the target connection
     * @param decoder content decoder
     * @return data read
     * @throws java.io.IOException if an error occurs
     */
    public ByteBuffer copyAndRead(NHttpClientConnection conn, ContentDecoder decoder) throws IOException {

        ByteBuffer bufferCopy = null;
        if (pipe != null) {
            bufferCopy = pipe.copyAndProduce(decoder);
        }
        // Update connection state
        readPostActions(conn, decoder);
        return bufferCopy;
    }

    private void readPostActions(NHttpClientConnection conn, ContentDecoder decoder) {

        if (decoder.isCompleted()) {
            HttpContext httpContext = conn.getContext();
            long responseReadEndTime = System.currentTimeMillis();
            if (transportLatencyLog.isDebugEnabled()) {
                HostConnections pool = (HostConnections) httpContext.getAttribute("CONNECTION_POOL");
                String route = pool == null ? "null" : pool.getRouteRequestMapping().toString();
                transportLatencyLog.debug(httpContext.getAttribute(CorrelationConstants.CORRELATION_ID) + "|" +
                        "Completed Reading Response from Backend at time stamp: " + responseReadEndTime +
                        " and route: " + route);
            }
            httpContext.setAttribute(PassThroughConstants.RES_FROM_BACKEND_READ_END_TIME, responseReadEndTime);
            httpContext.setAttribute(PassThroughConstants.RES_ARRIVAL_TIME, responseReadEndTime);
            TargetContext.updateState(conn, ProtocolState.RESPONSE_DONE);
            targetConfiguration.getMetrics().notifyReceivedMessageSize(conn.getMetrics().getReceivedBytesCount());

            if (!this.connStrategy.keepAlive(response, conn.getContext()) || forceShutdownConnectionOnComplete) {
                TargetContext.updateState(conn, ProtocolState.CLOSED);

                targetConfiguration.getConnections().shutdownConnection(conn, forceShutdownConnectionOnComplete);
            } else {
                if (conn instanceof LoggingNHttpClientConnection) {
                    ((LoggingNHttpClientConnection) conn).setReleaseConn(true);
                }
            }
        }
    }

    public String getHeader(String name) {
        return headers.get(name);
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public Map getExcessHeaders() {
    	return this.excessHeaders;
    }

    public void addExcessHeader(Header h) {
    	this.excessHeaders.put(h.getName(), h.getValue());
    }

    public Pipe getPipe() {
        return pipe;
    }

    public int getStatus() {
        return status;
    }

    public String getStatusLine() {
        return statusLine;
    }

    public boolean isExpectResponseBody() {
        return expectResponseBody;
    }

    public NHttpClientConnection getConnection() {
        return connection;
    }

    public ProtocolVersion getVersion() {
        return version;
    }

    /**
     * Retrieve the keep-alive timeout value
     * @param keepAlive header value which has timeout in seconds
     * @return keep-alive timeout in milliseconds
     */
    private int getKeepAliveTimeout(String keepAlive) {
        int keepAliveTimeout = -1;
        String timeout;

        try {
            timeout = keepAlive.split(KEEP_ALIVE_TIMEOUT)[1];
        } catch (ArrayIndexOutOfBoundsException ex) {
            log.error("Value is not specified in KeepAlive Timeout header.");
            return -1;
        }
        // If header has more than one property
        int commaIndex = timeout.indexOf(',');
        if (commaIndex != -1) {
            timeout = timeout.substring(0, commaIndex);
        }

        try {
            keepAliveTimeout = Integer.parseInt(timeout) * 1000;
        } catch (NumberFormatException e) {
            log.error("Error parsing value " + timeout + " as connection keep-alive timeout.", e);
        }
        return keepAliveTimeout;
    }

    public boolean isForceShutdownConnectionOnComplete() {
        return forceShutdownConnectionOnComplete;
    }
}
