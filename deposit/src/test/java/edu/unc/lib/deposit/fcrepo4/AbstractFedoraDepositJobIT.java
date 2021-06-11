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
package edu.unc.lib.deposit.fcrepo4;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.apache.http.HttpStatus;
import org.fcrepo.client.FcrepoClient;
import org.fcrepo.client.FcrepoOperationFailedException;
import org.fcrepo.client.FcrepoResponse;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.ContextHierarchy;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import edu.unc.lib.dl.fcrepo4.ContentObject;
import edu.unc.lib.dl.fcrepo4.ContentRootObject;
import edu.unc.lib.dl.fcrepo4.RepositoryInitializer;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectLoader;
import edu.unc.lib.dl.fcrepo4.RepositoryPIDMinter;
import edu.unc.lib.dl.fcrepo4.RepositoryPaths;
import edu.unc.lib.dl.fedora.FedoraException;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.persist.api.event.PremisLoggerFactory;
import edu.unc.lib.dl.persist.api.storage.StorageLocationManager;
import edu.unc.lib.dl.persist.api.transfer.BinaryTransferService;
import edu.unc.lib.dl.persist.services.deposit.DepositModelManager;
import edu.unc.lib.dl.reporting.ActivityMetricsClient;
import edu.unc.lib.dl.test.TestHelper;
import edu.unc.lib.dl.util.DepositStatusFactory;
import edu.unc.lib.dl.util.JobStatusFactory;
import edu.unc.lib.dl.util.RedisWorkerConstants.DepositState;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.embedded.RedisServer;

/**
 *
 * @author bbpennel
 *
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextHierarchy({
    @ContextConfiguration("/spring-test/test-fedora-container.xml"),
    @ContextConfiguration("/spring-test/cdr-client-container.xml")
})
public abstract class AbstractFedoraDepositJobIT {

    @Autowired
    protected String serverAddress;
    @Autowired
    protected String baseAddress;
    @Autowired
    protected RepositoryPIDMinter pidMinter;
    @Autowired
    protected DepositModelManager depositModelManager;
    @Autowired
    protected JobStatusFactory jobStatusFactory;
    @Autowired
    protected DepositStatusFactory depositStatusFactory;
    @Autowired
    protected PremisLoggerFactory premisLoggerFactory;
    @Autowired
    protected FcrepoClient client;
    @Autowired
    protected ActivityMetricsClient metricsClient;
    @Autowired
    protected BinaryTransferService binaryTransferService;
    @Autowired
    protected StorageLocationManager storageLocationManager;
    @Rule
    public final TemporaryFolder tmpFolder = new TemporaryFolder();
    @Autowired
    protected RepositoryInitializer repositoryInitializer;
    @Autowired
    protected RepositoryObjectLoader repoObjLoader;
    @Autowired
    private JedisPool jedisPool;

    protected File depositsDirectory;
    protected File depositDir;
    protected String jobUUID;
    protected String depositUUID;
    protected PID depositPid;

    protected ContentRootObject rootObj;

    private static final RedisServer redisServer;

    static {
        try {
            redisServer = new RedisServer(46380);
            redisServer.start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

    @AfterClass
    public static void afterClass() throws Exception {
        redisServer.stop();
    }

    @Before
    public void initBase() throws Exception {
        TestHelper.setContentBase(baseAddress);

        depositsDirectory = tmpFolder.newFolder("deposits");

        jobUUID = UUID.randomUUID().toString();

        depositPid = pidMinter.mintDepositRecordPid();
        depositUUID = depositPid.getId();
        depositDir = new File(depositsDirectory, depositUUID);
        depositDir.mkdir();

        depositStatusFactory.setState(depositUUID, DepositState.running);

        repositoryInitializer.initializeRepository();
        rootObj = repoObjLoader.getContentRootObject(RepositoryPaths.getContentRootPid());
    }

    @After
    public void cleanupRedis() throws Exception {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.flushDB();
        }
    }

    protected URI createBaseContainer(String name) throws IOException, FcrepoOperationFailedException {
        URI baseUri = URI.create(serverAddress + name);
        // Create a parent object to put the binary into
        try (FcrepoResponse response = client.put(baseUri).perform()) {
            return response.getLocation();
        } catch (FcrepoOperationFailedException e) {
            // Eat conflict exceptions since this will run multiple times
            if (e.getStatusCode() != HttpStatus.SC_CONFLICT) {
                throw e;
            }
            return baseUri;
        }
    }

    protected ContentObject findContentObjectByPid(List<ContentObject> objs, final PID pid) {
        return objs.stream()
                .filter(p -> p.getPid().equals(pid)).findAny().get();
    }

    protected boolean objectExists(PID pid) throws Exception {
        try (FcrepoResponse response = client.head(pid.getRepositoryUri())
                .perform()) {
            return true;
        } catch (FcrepoOperationFailedException e) {
            if (e.getStatusCode() == HttpStatus.SC_NOT_FOUND) {
                return false;
            }
            throw new FedoraException("Failed to check on object " + pid
                    + " during initialization", e);
        }
    }
}
