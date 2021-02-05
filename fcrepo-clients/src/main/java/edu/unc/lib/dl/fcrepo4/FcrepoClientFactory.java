/**
 * Copyright 2008 The University of North Carolina at Chapel Hill
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.unc.lib.dl.fcrepo4;

import org.fcrepo.client.FcrepoClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author bbpennel
 *
 */
public class FcrepoClientFactory {
    private static final Logger log = LoggerFactory.getLogger(FcrepoClientFactory.class);

    private String baseUri;
    private String authHost;
    private String authUser;
    private String authPassword;

    private static FcrepoClientFactory instance;

    private FcrepoClientFactory(String base, String host, String user, String password) {
        baseUri = base;
        authHost = host;
        authUser = user;
        authPassword = password;
    }

    /**
     * Returns a FcrepoClientFactory with the given baseUri if the factory has
     * not been previously constructed, otherwise the existing factory will be
     * returned.
     *
     * @param baseUri base uri for the repository. Required.
     * @return a new FcrepoClientFactory with the given baseUri or the
     *         previously constructed factory.
     */
    public static FcrepoClientFactory factory(String baseUri) {
        return factory(baseUri, null, null, null);
    }

    /**
     * Return a new FcrepoClientFactory with the given baseUri and authentication
     * enabled by default. Otherwise the existing factory is returned.
     *
     * @param baseUri base uri for the repository
     * @param host host name to provide as the authentication scope
     * @param user user name for authentication
     * @param password password for authentication
     * @return a new FcrepoClientFactory with the given baseUri or the
     *         previously constructed factory.
     */
    public static FcrepoClientFactory factory(String baseUri, String host, String user, String password) {
        if (baseUri == null) {
            throw new IllegalArgumentException("A base URI is required to construct a factory");
        }

        if (instance == null) {
            instance = new FcrepoClientFactory(baseUri, host, user, password);
        } else {
            log.warn("Requested to construct a factory when a previous instance has "
                    + "already been initialized (current base uri: {0}, requested {1}), ignoring.",
                    instance.baseUri, baseUri);
        }
        return instance;
    }

    /**
     * Returns the default factory. The factory must have been previously
     * constructed via the factory(baseUri) method.
     *
     * @return the existing default FcrepoClientFactory
     */
    public static FcrepoClientFactory defaultFactory() {
        return instance;
    }

    /**
     * Construct a TransactionalFcrepoClient object with the supplied authentication information
     *
     * @param host
     * @param user
     * @param password
     * @return
     */
    public FcrepoClient makeAuthenticatedClient(String host, String user, String password) {
        return TransactionalFcrepoClient.client(baseUri)
                .credentials(user, password)
                .authScope(host)
                .throwExceptionOnFailure()
                .build();
    }

    /**
     * Construct a FcrepoClient object with the supplied authentication information
     *
     * @param host
     * @param user
     * @param password
     * @return
     */
    public FcrepoClient makeAuthenticatedNonTransactionalClient(String host, String user, String password) {
        return FcrepoClient.client()
                .credentials(user, password)
                .authScope(host)
                .throwExceptionOnFailure()
                .build();
    }

    /**
     * Construct a TransactionalFcrepoClient with exceptions thrown on failure and no authentication.
     *
     * @return
     */
    public FcrepoClient makeClient() {
        if (authHost == null) {
            return TransactionalFcrepoClient.client(baseUri)
                    .throwExceptionOnFailure()
                    .build();
        } else {
            return makeAuthenticatedClient(authHost, authUser, authPassword);
        }

    }
}
