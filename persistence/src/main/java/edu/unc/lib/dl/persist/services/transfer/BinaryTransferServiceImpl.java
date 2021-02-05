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
package edu.unc.lib.dl.persist.services.transfer;

import static org.slf4j.LoggerFactory.getLogger;

import java.io.IOException;
import java.net.URI;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.fcrepo.client.FcrepoClient;
import org.fcrepo.client.FcrepoOperationFailedException;
import org.fcrepo.client.FcrepoResponse;
import org.slf4j.Logger;
import org.springframework.http.HttpHeaders;

import edu.unc.lib.dl.fcrepo4.RepositoryObject;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.persist.api.ingest.IngestSourceManager;
import edu.unc.lib.dl.persist.api.storage.StorageLocation;
import edu.unc.lib.dl.persist.api.storage.StorageLocationManager;
import edu.unc.lib.dl.persist.api.transfer.BinaryTransferOutcome;
import edu.unc.lib.dl.persist.api.transfer.BinaryTransferService;
import edu.unc.lib.dl.persist.api.transfer.BinaryTransferSession;
import edu.unc.lib.dl.persist.api.transfer.MultiDestinationTransferSession;

/**
 * Default implementation of a binary transfer service
 *
 * @author bbpennel
 *
 */
public class BinaryTransferServiceImpl implements BinaryTransferService {
    private static final Logger log = getLogger(BinaryTransferServiceImpl.class);

    private IngestSourceManager sourceManager;

    private StorageLocationManager storageLocationManager;

    private Map<String, Collection<TransferCacheEntry>> txTransferCache;
    private FcrepoClient nonTransactionalClient;

    public BinaryTransferServiceImpl() {
        txTransferCache = new ConcurrentHashMap<>();
    }

    @Override
    public MultiDestinationTransferSession getSession() {
        return new MultiDestinationTransferSessionImpl(sourceManager, storageLocationManager);
    }

    @Override
    public BinaryTransferSession getSession(StorageLocation destination) {
        return new BinaryTransferSessionImpl(sourceManager, destination);
    }

    @Override
    public BinaryTransferSession getSession(RepositoryObject repoObj) {
        StorageLocation loc = storageLocationManager.getStorageLocation(repoObj);
        return getSession(loc);
    }

    @Override
    public void prepareCommitTransaction(URI txUri) {
        String txId = txUri.toString();
        Collection<TransferCacheEntry> cache = txTransferCache.get(txId);
        if (cache == null) {
            return;
        }
        // Record existing content URIs for binaries updated in this tx for cleanup after committing
        for (TransferCacheEntry entry : cache) {
            try (FcrepoResponse resp = nonTransactionalClient.head(entry.binPid.getRepositoryUri()).perform()) {
                String contentLoc = resp.getHeaderValue(HttpHeaders.CONTENT_LOCATION);
                if (contentLoc != null) {
                    entry.headContentUri = URI.create(contentLoc);
                } else {
                    log.warn("No content URI for binary {}", entry.binPid);
                }
            } catch (IOException | FcrepoOperationFailedException e) {
                log.error("Failed to read response for {}", entry.binPid, e);
            }
        }
    }

    @Override
    public void postCommitTransaction(URI txUri) {
        String txId = txUri.toString();
        Collection<TransferCacheEntry> cache = txTransferCache.get(txId);
        if (cache == null) {
            return;
        }
        // Delete old versions of binaries updated in this tx in the background
        new Thread(() -> {
            try (MultiDestinationTransferSession mSession = getSession()) {
                for (TransferCacheEntry entry : cache) {
                    URI headUri = entry.headContentUri;
                    StorageLocation loc = storageLocationManager.getStorageLocationForUri(headUri);
                    try (BinaryTransferSession session = mSession.forDestination(loc)) {
                        session.delete(entry.headContentUri);
                    } catch (Exception e) {
                        log.error("Failed to cleanup old binary {}: {}", entry.headContentUri, e.getMessage());
                    }
                }
            } finally {
                txTransferCache.remove(txId);
            }
        }).start();
    }

    @Override
    public void rollbackTransaction(URI txUri) {
        String txId = txUri.toString();
        Collection<TransferCacheEntry> cache = txTransferCache.get(txId);
        if (cache == null) {
            return;
        }
        new Thread(() -> {
            try (MultiDestinationTransferSession mSession = getSession()) {
                for (TransferCacheEntry entry : cache) {
                    StorageLocation loc = storageLocationManager.getStorageLocationById(entry.newContentStorageId);
                    try (BinaryTransferSession session = mSession.forDestination(loc)) {
                        session.delete(entry.newContentUri);
                    } catch (Exception e) {
                        log.error("Rollback of transaction failed to cleanup new binary {}", entry.newContentUri, e);
                    }
                }
            } finally {
                txTransferCache.remove(txId);
            }
        }).start();
    }

    @Override
    public void registerOutcome(URI txUri, BinaryTransferOutcome outcome) {
        if (txUri == null) {
            return;
        }
        String txId = txUri.toString();
        txTransferCache.computeIfAbsent(txId, k -> new ConcurrentLinkedQueue<TransferCacheEntry>())
                .add(new TransferCacheEntry(outcome));
    }

    /**
     * @param sourceManager the sourceManager to set
     */
    public void setIngestSourceManager(IngestSourceManager sourceManager) {
        this.sourceManager = sourceManager;
    }

    public void setStorageLocationManager(StorageLocationManager storageLocationManager) {
        this.storageLocationManager = storageLocationManager;
    }

    public void setNonTransactionalClient(FcrepoClient nonTransactionalClient) {
        this.nonTransactionalClient = nonTransactionalClient;
    }

    private static class TransferCacheEntry {
        private PID binPid;
        private URI newContentUri;
        private URI headContentUri;
        private String newContentStorageId;

        private TransferCacheEntry(BinaryTransferOutcome outcome) {
            this.binPid = outcome.getBinaryPid();
            this.newContentUri = outcome.getDestinationUri();
            this.newContentStorageId = outcome.getDestinationId();
        }
    }
}
