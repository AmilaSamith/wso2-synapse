/*
*  Licensed to the Apache Software Foundation (ASF) under one
*  or more contributor license agreements.  See the NOTICE file
*  distributed with this work for additional information
*  regarding copyright ownership.  The ASF licenses this file
*  to you under the Apache License, Version 2.0 (the
*  "License"); you may not use this file except in compliance
*  with the License.  You may obtain a copy of the License at
*
*   http://www.apache.org/licenses/LICENSE-2.0
*
*  Unless required by applicable law or agreed to in writing,
*  software distributed under the License is distributed on an
*   * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
*  KIND, either express or implied.  See the License for the
*  specific language governing permissions and limitations
*  under the License.
*/
package org.apache.synapse.commons.vfs;

import com.hierynomus.msdtyp.AccessMask;
import org.apache.axis2.AxisFault;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.description.Parameter;
import org.apache.axis2.description.ParameterInclude;
import org.apache.axis2.transport.base.ParamUtils;
import org.apache.commons.lang.WordUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.vfs2.FileContent;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemManager;
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.commons.vfs2.impl.DefaultFileSystemManager;
import org.apache.commons.vfs2.provider.UriParser;
import org.apache.commons.vfs2.provider.ftps.FtpsDataChannelProtectionLevel;
import org.apache.commons.vfs2.provider.ftps.FtpsFileSystemConfigBuilder;
import org.apache.commons.vfs2.provider.ftps.FtpsMode;
import org.apache.commons.vfs2.provider.smb2.Smb2FileSystemConfigBuilder;
import org.apache.commons.vfs2.util.DelegatingFileSystemOptionsBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class VFSUtils {

    private static final Log log = LogFactory.getLog(VFSUtils.class);

    private static final String STR_SPLITER = ":";

    private static final String LOCK_FILE_SUFFIX = ".lock";

    private static final String FAIL_FILE_SUFFIX = ".fail";

    /**
     * URL pattern
     */
    private static final Pattern URL_PATTERN = Pattern.compile("[a-zA-Z0-9]+://.*");

    /**
     * Password pattern
     */
    private static final Pattern PASSWORD_PATTERN = Pattern.compile(":(?:[^/]+)@");

    private static final Random randomNumberGenerator = new Random();

    /**
     * SSL Keystore.
     */
    private static final String KEY_STORE = "vfs.ssl.keystore";

    /**
     * SSL Truststore.
     */
    private static final String TRUST_STORE = "vfs.ssl.truststore";

    /**
     * SSL Keystore password.
     */
    private static final String KS_PASSWD = "vfs.ssl.kspassword";

    /**
     * SSL Truststore password.
     */
    private static final String TS_PASSWD = "vfs.ssl.tspassword";

    /**
     * SSL Key password.
     */
    private static final String KEY_PASSWD = "vfs.ssl.keypassword";

    /**
     * Passive mode
     */
    public static final String PASSIVE_MODE = "vfs.passive";

    /**
     * FTPS implicit mode
     */
    public static final String IMPLICIT_MODE = "vfs.implicit";

    public static final String PROTECTION_MODE = "vfs.protection";

    private static final String ENCRYPTION_ENABLED = "vfs.EncryptionEnabled";

    public static final String DISK_SHARE_ACCESS_MASK = "vfs.diskShareAccessMask";

    public static final String DISK_SHARE_ACCESS_MASK_MAX_ALLOWED = "MAXIMUM_ALLOWED";

    private VFSUtils() {
    }

    /**
     * Get a String property from FileContent message
     *
     * @param message the File message
     * @param property property name
     * @return property value
     */
    public static String getProperty(FileContent message, String property) {
        try {
            Object o = message.getAttributes().get(property);
            if (o instanceof String) {
                return (String) o;
            }
        } catch (FileSystemException ignored) {}
        return null;
    }

    public static String getFileName(MessageContext msgCtx, VFSOutTransportInfo vfsOutInfo) {
        String fileName = null;

        // first preference to a custom filename set on the current message context
        Map transportHeaders = (Map) msgCtx.getProperty(MessageContext.TRANSPORT_HEADERS);
        if (transportHeaders != null) {
            fileName = (String) transportHeaders.get(VFSConstants.REPLY_FILE_NAME);
        }

        // if not, does the service (in its service.xml) specify one?
        if (fileName == null) {
            Parameter param = msgCtx.getAxisService().getParameter(VFSConstants.REPLY_FILE_NAME);
            if (param != null) {
                fileName = (String) param.getValue();
            }
        }

        // next check if the OutTransportInfo specifies one
        if (fileName == null) {
            fileName = vfsOutInfo.getOutFileName();
        }

        // if none works.. use default
        if (fileName == null) {
            fileName = VFSConstants.DEFAULT_RESPONSE_FILE;
        }
        return fileName;
    }

    /**
     * Acquires a file item lock before processing the item, guaranteing that the file is not
     * processed while it is being uploaded and/or the item is not processed by two listeners
     *
     * @param fsManager used to resolve the processing file
     * @param fo representing the processing file item
     * @param fso represents file system options used when resolving file from file system manager.
     * @return boolean true if the lock has been acquired or false if not
     */
    public static synchronized boolean acquireLock(FileSystemManager fsManager, FileObject fo,
                                                   FileSystemOptions fso, boolean isListener) {
        return acquireLock(fsManager, fo, null, fso, isListener);
    }

    /**
     * Acquires a file item lock before processing the item, guaranteing that
     * the file is not processed while it is being uploaded and/or the item is
     * not processed by two listeners
     * 
     * @param fsManager
     *            used to resolve the processing file
     * @param fo
     *            representing the processing file item
     * @param fso
     *            represents file system options used when resolving file from file system manager.
     * @return boolean true if the lock has been acquired or false if not
     */
    public static synchronized boolean acquireLock(FileSystemManager fsManager, FileObject fo, VFSParamDTO paramDTO,
                                                   FileSystemOptions fso, boolean isListener) {
        String strLockValue = getLockValue();
        byte[] lockValue = strLockValue.getBytes();
        FileObject lockObject = null;
        String fullPath = getFullPath(fo);

        try {
            // check whether there is an existing lock for this item, if so it is assumed
            // to be processed by an another listener (downloading) or a sender (uploading)
            // lock file is derived by attaching the ".lock" second extension to the file name
            lockObject = fsManager.resolveFile(fullPath + LOCK_FILE_SUFFIX, fso);
            if (lockObject.exists()) {
                log.debug("There seems to be an external lock, aborting the processing of the file "
                        + maskURLPassword(fo.getName().getURI())
                        + ". This could possibly be due to some other party already "
                        + "processing this file or the file is still being uploaded");
                if(paramDTO != null && paramDTO.isAutoLockRelease()){
                    releaseLock(lockValue, strLockValue, lockObject, paramDTO.isAutoLockReleaseSameNode(),
                            paramDTO.getAutoLockReleaseInterval());
                }
            } else {
                if (isListener) {
                    //Check the original file existence before the lock file to handle concurrent access scenario
                    FileObject originalFileObject = fsManager.resolveFile(fullPath, fso);
                    if (!originalFileObject.exists()) {
                        return false;
                    }
                }
                if (!createLockFile(lockValue, lockObject, fullPath)) {
                    return false;
                }

                // check whether the lock is in place and is it me who holds the lock. This is
                // required because it is possible to write the lock file simultaneously by
                // two processing parties. It checks whether the lock file content is the same
                // as the written random lock value.
                // NOTE: this may not be optimal but is sub optimal
                FileObject verifyingLockObject = fsManager.resolveFile(
                        fullPath + LOCK_FILE_SUFFIX, fso);
                if (verifyingLockObject.exists() && verifyLock(lockValue, verifyingLockObject)) {
                    return true;
                }
            }
        } catch (FileSystemException fse) {
            log.error("Cannot get the lock for the file : " + maskURLPassword(fo.getName().getURI()) + " before processing", fse);
            if (lockObject != null) {
                try {
                    fsManager.closeFileSystem(lockObject.getParent().getFileSystem());
                } catch (FileSystemException e) {
                    log.warn("Unable to close the lockObject parent file system");
                }
            } else {
                try {
                    ((DefaultFileSystemManager) fsManager).closeCachedFileSystem(fullPath + LOCK_FILE_SUFFIX, fso);
                } catch (Exception e1) {
                    log.warn("Unable to clear file system", e1);
                }
            }
        }
        return false;
    }

    private static String getFullPath(FileObject fo) {
        String fullPath = fo.getName().getURI();
        int pos = fullPath.indexOf('?');
        if (pos != -1) {
            fullPath = fullPath.substring(0, pos);
        }
        return fullPath;
    }

    private static boolean createLockFile(byte[] lockValue, FileObject lockObject, String fullPath)
            throws FileSystemException {
        // write a lock file before starting of the processing, to ensure that the
        // item is not processed by any other parties
        lockObject.createFile();
        OutputStream stream = lockObject.getContent().getOutputStream();
        try {
            stream.write(lockValue);
            stream.flush();
        } catch (IOException e) {
            lockObject.delete();
            log.error("Couldn't create the lock file before processing the file "
                    + maskURLPassword(fullPath), e);
            return false;
        } finally {
            try {
                stream.close();
            } catch (IOException e) {
                log.debug("Error closing stream", e);
            }
            lockObject.close();
        }
        return true;
    }

    /**
     * Generate a random lock value to ensure that there are no two parties processing the same file
     * Lock format random:hostname:hostip:time
     * @return lock value as a string
     */
    private static String getLockValue() {

        StringBuilder lockValueBuilder = new StringBuilder();
        lockValueBuilder.append(randomNumberGenerator.nextLong());
        try {
            lockValueBuilder.append(STR_SPLITER)
                            .append(InetAddress.getLocalHost().getHostName())
                            .append(STR_SPLITER)
                            .append(InetAddress.getLocalHost().getHostAddress());
        } catch (UnknownHostException ue) {
            if (log.isDebugEnabled()) {
                log.debug("Unable to get the Hostname or IP.", ue);
            }
        }
        lockValueBuilder.append(STR_SPLITER).append((new Date()).getTime());
        return lockValueBuilder.toString();
    }

    /**
     * Release a file item lock acquired either by the VFS listener or a sender
     *
     * @param fsManager which is used to resolve the processed file
     * @param fo representing the processed file
     * @param fso represents file system options used when resolving file from file system manager.
     */
    public static void releaseLock(FileSystemManager fsManager, FileObject fo, FileSystemOptions fso) {
        String fullPath = fo.getName().getURI();
        FileObject lockObject;

        try {
            int pos = fullPath.indexOf('?');
            if (pos > -1) {
                fullPath = fullPath.substring(0, pos);
            }
            lockObject = fsManager.resolveFile(fullPath + LOCK_FILE_SUFFIX, fso);
            if (lockObject.exists()) {
                lockObject.delete();
            }
        } catch (FileSystemException e) {
            log.error("Couldn't release the lock for the file : "
                    + maskURLPassword(fo.getName().getURI()) + " after processing");
            try {
                ((DefaultFileSystemManager) fsManager).closeCachedFileSystem(fullPath + LOCK_FILE_SUFFIX, fso);
            } catch (Exception e1){
                log.warn("Unable to clear file system", e1);
            }
        }
    }

    /**
     * Mask the password of the connection url with ***
     * @param url the actual url
     * @return the masked url
     */
    public static String maskURLPassword(String url) {
        final Matcher urlMatcher = URL_PATTERN.matcher(url);
        String maskUrl;
        if (urlMatcher.find()) {
            final Matcher pwdMatcher = PASSWORD_PATTERN.matcher(url);
            maskUrl = pwdMatcher.replaceFirst(":***@");
            return maskUrl;
        }
        return url;
    }

    public static String getSystemTime(String dateFormat) {
        return new SimpleDateFormat(dateFormat).format(new Date());
    }


    private static boolean verifyLock(byte[] lockValue, FileObject lockObject) {
        try {
            InputStream is = lockObject.getContent().getInputStream();
            byte[] val = new byte[lockValue.length];
            // noinspection ResultOfMethodCallIgnored
            is.read(val);
            if (Arrays.equals(lockValue, val) && is.read() == -1) {
                return true;
            } else {
                log.debug("The lock has been acquired by an another party");
            }
        } catch (IOException e) {
            log.error("Couldn't verify the lock", e);
            return false;
        }
        return false;
    }

    /**
     * Helper method to get last modified date from msgCtx
     *
     * @param msgCtx
     * @return lastModifiedDate
     */
    public static Long getLastModified(MessageContext msgCtx) {
        Object lastModified;
        Map transportHeaders = (Map) msgCtx.getProperty(MessageContext.TRANSPORT_HEADERS);
        if (transportHeaders != null) {
            lastModified = transportHeaders.get(VFSConstants.LAST_MODIFIED);
            if (lastModified != null) {
                if (lastModified instanceof Long) {
                    return (Long)lastModified;
                } else if (lastModified instanceof String) {
                    try {
                        return Long.parseLong((String) lastModified);
                    } catch (Exception e) {
                        log.warn("Cannot create last modified.", e);
                        return null;
                    }
                }
            }
        }
        return null;
    }

    public static synchronized void markFailRecord(FileSystemManager fsManager, FileObject fo) {
        markFailRecord(fsManager, fo, null);
    }

    public static synchronized void markFailRecord(FileSystemManager fsManager, FileObject fo, FileSystemOptions fso) {

        // generate a random fail value to ensure that there are no two parties
        // processing the same file
        byte[] failValue = (Long.toString((new Date()).getTime())).getBytes();

        try {
            String fullPath = getFullPath(fo);
            FileObject failObject = fsManager.resolveFile(fullPath + FAIL_FILE_SUFFIX, fso);
            if (!failObject.exists()) {
                failObject.createFile();
            }

            // write a lock file before starting of the processing, to ensure that the
            // item is not processed by any other parties

            OutputStream stream = failObject.getContent().getOutputStream();
            try {
                stream.write(failValue);
                stream.flush();
            } catch (IOException e) {
                failObject.delete();
                log.error("Couldn't create the fail file before processing the file " + maskURLPassword(fullPath), e);
            } finally {
                try {
                    stream.close();
                } catch (IOException e) {
                    log.debug("Error closing stream", e);
                }
                failObject.close();
            }
        } catch (FileSystemException fse) {
            log.error("Cannot get the lock for the file : " + maskURLPassword(fo.getName().getURI()) + " before processing");
        }
    }

    public static boolean isFailRecord(FileSystemManager fsManager, FileObject fo) {
        return isFailRecord(fsManager, fo, null);
    }

    public static boolean isFailRecord(FileSystemManager fsManager, FileObject fo, FileSystemOptions fso) {
        try {
	        String fullPath = fo.getName().getURI();
	        String queryParams = "";
            int pos = fullPath.indexOf('?');
            if (pos > -1) {
                queryParams = fullPath.substring(pos);
                fullPath = fullPath.substring(0, pos);
            }
            FileObject failObject = fsManager.resolveFile(fullPath + FAIL_FILE_SUFFIX + queryParams, fso);
            if (failObject.exists()) {
                return true;
            }
        } catch (FileSystemException e) {
            log.error("Couldn't release the fail for the file : " + maskURLPassword(fo.getName().getURI()));
        }
        return false;
    }

    /**
     *
     * @param fo representing the processed file
     * @param waitTimeBeforeRead representing the time period in milliseconds to wait before reading the file
     * @return boolean true if the can be processed or false if not
     */
    public static boolean isReadyToRead(FileObject fo, Long waitTimeBeforeRead) {
        if(waitTimeBeforeRead != null && waitTimeBeforeRead > 0) {
            try {
                return fo.getContent().getLastModifiedTime() < (System.currentTimeMillis() - waitTimeBeforeRead);
            } catch (FileSystemException e) {
                log.warn("Unable to determine whether the file can be read or not", e);
            }
        }
        return true;
    }

    public static void releaseFail(FileSystemManager fsManager, FileObject fo) {
        releaseFail(fsManager, fo, null);
    }

    public static void releaseFail(FileSystemManager fsManager, FileObject fo, FileSystemOptions fso) {
        try {
	        String fullPath = fo.getName().getURI();
            int pos = fullPath.indexOf('?');
            if (pos > -1) {
                fullPath = fullPath.substring(0, pos);
            }
            FileObject failObject = fsManager.resolveFile(fullPath + FAIL_FILE_SUFFIX, fso);
            if (failObject.exists()) {
                failObject.delete();
            }
        } catch (FileSystemException e) {
            log.error("Couldn't release the fail for the file : " + maskURLPassword(fo.getName().getURI()));
        }
    }
    
    private static void releaseLock(byte[] bLockValue, String sLockValue, FileObject lockObject,
                                       Boolean autoLockReleaseSameNode, Long autoLockReleaseInterval) {
        try {
            InputStream is = lockObject.getContent().getInputStream();
            byte[] val = new byte[bLockValue.length];
            // noinspection ResultOfMethodCallIgnored
            is.read(val);
            is.close();
            String strVal = new String(val);
            // Lock format random:hostname:hostip:time
            String[] arrVal = strVal.split(":");
            String[] arrValNew = sLockValue.split(STR_SPLITER);
            if (arrVal.length == 4 && arrValNew.length == 4
                && (!autoLockReleaseSameNode || (arrVal[1].equals(arrValNew[1]) && arrVal[2].equals(arrValNew[2])))) {
                long lInterval = 0;
                try {
                    lInterval = Long.parseLong(arrValNew[3]) - Long.parseLong(arrVal[3]);
                } catch (NumberFormatException nfe) {
                    log.debug("Error calculating lock file age", nfe);
                }
                deleteLockFile(lockObject, autoLockReleaseInterval, lInterval);
            } else {
                lockObject.close();
            }
        } catch (IOException e) {
            log.error("Couldn't verify the lock", e);
        }
    }

    private static void deleteLockFile(FileObject lockObject, Long autoLockReleaseInterval, long lInterval)
            throws FileSystemException {
        if (autoLockReleaseInterval == null || autoLockReleaseInterval <= lInterval) {
            try {
                lockObject.delete();
            } catch (Exception e) {
                log.warn("Unable to delete the lock file during auto release cycle.", e);
            } finally {
                lockObject.close();
            }
        }
    }

    public static Map<String, String> parseSchemeFileOptions(String fileURI, ParameterInclude params) {
        String scheme = UriParser.extractScheme(fileURI);
        if (scheme == null) {
            return null;
        }
        Map<String, String> schemeFileOptions = parseSchemeFileOptions(scheme, fileURI);
        addOptions(schemeFileOptions, params);
        return schemeFileOptions;
    }

    public static Map<String, String> parseSchemeFileOptions(String fileURI, Properties vfsProperties) {
        String scheme = UriParser.extractScheme(fileURI);
        if (scheme == null) {
            return null;
        }
        Map<String, String> schemeFileOptions = parseSchemeFileOptions(scheme, fileURI);
        addOptions(schemeFileOptions, vfsProperties);
        return schemeFileOptions;
    }

    private static Map<String, String> parseSchemeFileOptions(String scheme, String fileURI) {
        HashMap<String, String> schemeFileOptions = new HashMap<>();
        schemeFileOptions.put(VFSConstants.SCHEME, scheme);
        try {
            Map<String, String> queryParams = UriParser.extractQueryParams(fileURI);
            schemeFileOptions.putAll(queryParams);
        } catch (FileSystemException e) {
            log.error("Error while loading scheme query params", e);
        }
        return schemeFileOptions;
    }

    private static void addOptions(Map<String, String> schemeFileOptions, Properties vfsProperties) {
        for (VFSConstants.SFTP_FILE_OPTION option : VFSConstants.SFTP_FILE_OPTION.values()) {
            String paramValue = vfsProperties.getProperty(
                    VFSConstants.SFTP_PREFIX + WordUtils.capitalize(option.toString()));
            if (paramValue != null && !paramValue.isEmpty()) {
                schemeFileOptions.put(option.toString(), paramValue);
            }
        }
    }

    private static void addOptions(Map<String, String> schemeFileOptions, ParameterInclude params) {
        for (VFSConstants.SFTP_FILE_OPTION option : VFSConstants.SFTP_FILE_OPTION.values()) {
            String paramValue = null;
            try {
                paramValue = ParamUtils.getOptionalParam(
                        params, VFSConstants.SFTP_PREFIX + WordUtils.capitalize(option.toString()));
            } catch (AxisFault axisFault) {
                log.error("Error while loading VFS parameter. " + axisFault.getMessage());
            }
            if (paramValue != null && !paramValue.isEmpty()) {
                schemeFileOptions.put(option.toString(), paramValue);
            }
        }
    }

    public static FileSystemOptions attachFileSystemOptions(Map<String, String> options, FileSystemManager fsManager)
            throws FileSystemException {
        if (options == null) {
            return null;
        }

        FileSystemOptions opts = new FileSystemOptions();
        DelegatingFileSystemOptionsBuilder delegate = new DelegatingFileSystemOptionsBuilder(fsManager);

        // setting all available configs regardless of the options.get(VFSConstants.SCHEME)
        // because schemes of FileURI and MoveAfterProcess can be different

        //sftp configs
        for (Map.Entry<String, String> entry : options.entrySet()) {
            for (VFSConstants.SFTP_FILE_OPTION option : VFSConstants.SFTP_FILE_OPTION.values()) {
                if (entry.getKey().equals(option.toString()) && entry.getValue() != null) {
                    delegate.setConfigString(opts, VFSConstants.SCHEME_SFTP, entry.getKey().toLowerCase(),
                                             entry.getValue());
                }
            }
        }

        FtpsFileSystemConfigBuilder configBuilder = FtpsFileSystemConfigBuilder.getInstance();

        // ftp and ftps configs
        String passiveMode = options.get(PASSIVE_MODE);
        if (passiveMode != null) {
            configBuilder.setPassiveMode(opts, Boolean.parseBoolean(passiveMode));
        }

        // ftps configs
        String implicitMode = options.get(IMPLICIT_MODE);
        if (implicitMode != null) {
            if (Boolean.parseBoolean(implicitMode)) {
                configBuilder.setFtpsMode(opts, FtpsMode.IMPLICIT);
            } else {
                configBuilder.setFtpsMode(opts, FtpsMode.EXPLICIT);
            }
        }
        String protectionMode = options.get(PROTECTION_MODE);
        if ("P".equalsIgnoreCase(protectionMode)) {
            configBuilder.setDataChannelProtectionLevel(opts, FtpsDataChannelProtectionLevel.P);
        } else if ("C".equalsIgnoreCase(protectionMode)) {
            configBuilder.setDataChannelProtectionLevel(opts, FtpsDataChannelProtectionLevel.C);
        } else if ("S".equalsIgnoreCase(protectionMode)) {
            configBuilder.setDataChannelProtectionLevel(opts, FtpsDataChannelProtectionLevel.S);
        } else if ("E".equalsIgnoreCase(protectionMode)) {
            configBuilder.setDataChannelProtectionLevel(opts, FtpsDataChannelProtectionLevel.E);
        }
        String keyStore = options.get(KEY_STORE);
        if (keyStore != null) {
            configBuilder.setKeyStore(opts, keyStore);
        }
        String trustStore = options.get(TRUST_STORE);
        if (trustStore != null) {
            configBuilder.setTrustStore(opts, trustStore);
        }
        String keyStorePassword = options.get(KS_PASSWD);
        if (keyStorePassword != null) {
            configBuilder.setKeyStorePW(opts, keyStorePassword);
        }
        String trustStorePassword = options.get(TS_PASSWD);
        if (trustStorePassword != null) {
            configBuilder.setTrustStorePW(opts, trustStorePassword);
        }
        String keyPassword = options.get(KEY_PASSWD);
        if (keyPassword != null) {
            configBuilder.setKeyPW(opts, keyPassword);
        }

        if (options.get(VFSConstants.FILE_TYPE) != null) {
            delegate.setConfigString(opts, options.get(VFSConstants.SCHEME), VFSConstants.FILE_TYPE,
                    options.get(VFSConstants.FILE_TYPE));
        }

        Smb2FileSystemConfigBuilder smb2FileSystemConfigBuilder = Smb2FileSystemConfigBuilder.getInstance();

        boolean encryptionEnabled = Boolean.parseBoolean(options.get(ENCRYPTION_ENABLED));
        if (options.get(ENCRYPTION_ENABLED) != null) {
            smb2FileSystemConfigBuilder.setEncryptionEnabled(opts, encryptionEnabled);
        }

        String diskfileshare = options.get(DISK_SHARE_ACCESS_MASK);
        if (diskfileshare != null) {
            smb2FileSystemConfigBuilder.setDiskShareAccessMask(opts, validateAndGetDiskShareAccessMask(diskfileshare));
        }

        return opts;
    }

    public static ArrayList<String> validateAndGetDiskShareAccessMask(String diskShareAccessMask) {
        // Prepare the set of allowed access mask values
        Set<String> allowedValues = new HashSet<String>();
        for (AccessMask mask : AccessMask.values()) {
            allowedValues.add(mask.name());
        }

        // Assign the validated value
        ArrayList<String> outDiskShareAccessMasks = new ArrayList<String>();

        // Validate and collect allowed values
        if (diskShareAccessMask != null) {
            String[] masks = diskShareAccessMask.split(",");
            for (String mask : masks) {
                String accessMask = mask.trim();
                if (allowedValues.contains(accessMask)) {
                    outDiskShareAccessMasks.add(accessMask);
                } else {
                    log.warn("Access mask is not valid and was ignored: " + mask);
                }
            }
        }

        // Fallback to default if nothing is valid or input was null
        if (outDiskShareAccessMasks.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("Set the access mask to default MAXIMUM_ALLOWED since the access mask is not defined or the defined values are not valid.");
            }
            outDiskShareAccessMasks.add(DISK_SHARE_ACCESS_MASK_MAX_ALLOWED);
        }

        return outDiskShareAccessMasks;
    }

    /**
     * Function to resolve hostname of the vfs uri
     * @param uri URI need to resolve
     * @return hostname resolved uri
     * @throws FileSystemException Unable to decode due to malformed URI
     * @throws UnknownHostException Error occurred while resolving hostname of URI
     */
    public static String resolveUriHost (String uri) throws FileSystemException, UnknownHostException {
        return resolveUriHost(uri, new StringBuilder());
    }

    /**
     * Function to resolve the hostname of uri to ip for following vfs protocols. if not the protocol listed, return
     * same uri provided for {uri}
     * Protocols resolved : SMB
     * @param uri URI need to resolve
     * @param strBuilder string builder to use to build the resulting uri
     * @return hostname resolved uri
     * @throws FileSystemException Unable to decode due to malformed URI
     * @throws UnknownHostException Error occurred while resolving hostname of URI
     */
    public static String resolveUriHost (String uri, StringBuilder strBuilder)
            throws FileSystemException, UnknownHostException {

        if (uri != null && strBuilder != null) {
            // Extract the scheme
            String scheme = UriParser.extractScheme(uri, strBuilder);

            //need to resolve hosts of smb URIs due to limitation in jcifs library
            if (scheme != null && (scheme.equals("smb"))) {
                // Expecting "//"
                if (strBuilder.length() < 2 || strBuilder.charAt(0) != '/' || strBuilder.charAt(1) != '/') {
                    throw new FileSystemException("vfs.provider/missing-double-slashes.error", uri);
                }
                strBuilder.delete(0, 2);

                // Extract userinfo
                String userInfo = extractUserInfo(strBuilder);

                // Extract hostname
                String hostName = extractHostName(strBuilder);

                //resolve host name
                InetAddress hostAddress = InetAddress.getByName(hostName);
                String resolvedHostAddress = hostAddress.getHostAddress();

                //build resolved uri
                StringBuilder uriStrBuilder = new StringBuilder();
                uriStrBuilder.append(scheme).append("://");

                if (userInfo != null) {
                    //user information can be null since it's optional
                    uriStrBuilder.append(userInfo).append("@");
                }

                uriStrBuilder.append(resolvedHostAddress).append(strBuilder);

                return uriStrBuilder.toString();
            }
        }

        return uri;
    }


    /**
     * Extracts the hostname from a URI.  The scheme://userinfo@ part has
     * been removed.
     * extracted hostname will be reoved from the StringBuilder
     */
    private static String extractHostName(StringBuilder name) {
        final int maxlen = name.length();
        int pos = 0;
        for (; pos < maxlen; pos++) {
            char ch = name.charAt(pos);
            //if /;?:@&=+$, characters found means, we have passed the hostname, hence break
            if (ch == '/' || ch == ';' || ch == '?' || ch == ':'
                    || ch == '@' || ch == '&' || ch == '=' || ch == '+'
                    || ch == '$' || ch == ',') {
                break;
            }
        }
        if (pos == 0) {
            //haven't found the hostname
            return null;
        }

        String hostname = name.substring(0, pos);
        name.delete(0, pos);
        return hostname;
    }

    /**
     * Extracts the user info from a URI.  The scheme:// part has been removed
     * already.
     * extracted user info will be removed from the StringBuilder
     */
    private static String extractUserInfo(StringBuilder name) {
        int maxlen = name.length();
        for (int pos = 0; pos < maxlen; pos++) {
            char ch = name.charAt(pos);
            if (ch == '@') {
                // Found the end of the user info
                String userInfo = name.substring(0, pos);
                name.delete(0, pos + 1);
                return userInfo;
            }
            if (ch == '/' || ch == '?') {
                // Not allowed in user info
                break;
            }
        }

        // Not found
        return null;
    }

    private static Integer getFileType(String fileType) {

        fileType = fileType.toUpperCase();

        if (VFSConstants.ASCII_TYPE.equals(fileType)) {
            return FTP.ASCII_FILE_TYPE;
        } else if (VFSConstants.BINARY_TYPE.equals(fileType)) {
            return FTP.BINARY_FILE_TYPE;
        } else if (VFSConstants.EBCDIC_TYPE.equals(fileType)) {
            return FTP.EBCDIC_FILE_TYPE;
        } else if (VFSConstants.LOCAL_TYPE.equals(fileType)) {
            return FTP.LOCAL_FILE_TYPE;
        } else {
            return FTP.BINARY_FILE_TYPE;
        }
    }
}
