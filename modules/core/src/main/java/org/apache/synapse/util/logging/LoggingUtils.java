/*
 * Copyright (c) 2020, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.synapse.util.logging;

import org.apache.synapse.MessageContext;
import org.apache.synapse.SynapseConstants;
import org.apache.synapse.rest.RESTConstants;
/**
 * Util class to get formatted logs for audit purposes.
 */
public class LoggingUtils {

    private static final String OPEN_BRACKETS = "{";
    private static final String CLOSE_BRACKETS = "}";
    public static final String LOG_ENTRY_POINT_INFO = "logEntryPointInfo";

    private LoggingUtils() {
        // do nothing
    }

    public static String getFormattedLog(MessageContext synCtx, Object msg) {

        Object artifactNameObject = synCtx.getProperty(SynapseConstants.ARTIFACT_NAME);
        if (artifactNameObject != null) {
            String artifactName = artifactNameObject.toString();
            if (artifactName.startsWith(SynapseConstants.PROXY_SERVICE_TYPE)) {
                return getFormattedLog(SynapseConstants.PROXY_SERVICE_TYPE,
                                       artifactName.substring(SynapseConstants.PROXY_SERVICE_TYPE.length()), msg);
            } else if (artifactName.startsWith(SynapseConstants.FAIL_SAFE_MODE_API)) {
                return getFormattedLogForAPI(synCtx, SynapseConstants.FAIL_SAFE_MODE_API,
                                       artifactName.substring(SynapseConstants.FAIL_SAFE_MODE_API.length()), msg);
            } else if (artifactName.startsWith(SynapseConstants.FAIL_SAFE_MODE_INBOUND_ENDPOINT)) {
                return getFormattedLog(SynapseConstants.FAIL_SAFE_MODE_INBOUND_ENDPOINT, artifactName
                        .substring(SynapseConstants.FAIL_SAFE_MODE_INBOUND_ENDPOINT.length()), msg);
            }
            return getFormattedString(artifactName, msg);
        }
        return msg.toString();
    }

    private static String getFormattedLogForAPI(MessageContext synCtx, String artifactType, Object artifactName,
                                                Object msg) {
        boolean logEntryPointInfo = Boolean.parseBoolean((String) synCtx.getProperty(LoggingUtils.LOG_ENTRY_POINT_INFO));
        if (!logEntryPointInfo) {
            return getFormattedLog(artifactType, artifactName, msg);
        }
        String name = artifactName != null ? artifactName.toString() : "";
        String artifactInfo = artifactType.concat(":").concat(name);
        String method = (String) synCtx.getProperty(RESTConstants.REST_METHOD);
        String restFullRequestPath = (String) synCtx.getProperty(RESTConstants.REST_FULL_REQUEST_PATH);

        String apiInfo = String.join(" ", artifactInfo, method != null ? method : "",
                restFullRequestPath != null ? restFullRequestPath : "").trim();

        return getFormattedString(apiInfo, msg);
    }

    public static String getFormattedLog(String artifactType, Object artifactName, Object msg) {

        String name = artifactName != null ? artifactName.toString() : "";
        return getFormattedString(artifactType.concat(":").concat(name), msg);
    }

    private static String getFormattedString(String name, Object msg) {

        String message = msg != null ? msg.toString() : "";
        return OPEN_BRACKETS.concat(name).concat(CLOSE_BRACKETS).concat(" ").concat(message);
    }
}
