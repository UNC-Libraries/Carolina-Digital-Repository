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
package edu.unc.lib.dl.services.camel.destroy;

import static edu.unc.lib.dl.persist.services.destroy.DestroyObjectsHelper.serializeDestroyRequest;
import static org.apache.jena.rdf.model.ModelFactory.createDefaultModel;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import java.util.UUID;

import org.apache.camel.BeanInject;
import org.apache.camel.Produce;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.AdviceWithRouteBuilder;
import org.apache.camel.test.spring.CamelSpringTestSupport;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import edu.unc.lib.dl.acl.fcrepo4.InheritedAclFactory;
import edu.unc.lib.dl.acl.util.AccessGroupSet;
import edu.unc.lib.dl.acl.util.AgentPrincipals;
import edu.unc.lib.dl.fcrepo4.FedoraTransaction;
import edu.unc.lib.dl.fcrepo4.PIDs;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectFactory;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectLoader;
import edu.unc.lib.dl.fcrepo4.TransactionManager;
import edu.unc.lib.dl.fcrepo4.WorkObject;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.persist.services.destroy.DestroyObjectsRequest;
import edu.unc.lib.dl.rdf.Cdr;
import edu.unc.lib.dl.search.solr.model.ObjectPath;
import edu.unc.lib.dl.search.solr.service.ObjectPathFactory;

/**
 * @author bbpennel
 *
 */
public class DestroyObjectsRouterTest extends CamelSpringTestSupport {
    private static final String DESTROY_ROUTE = "CdrDestroyObjects";

    private static final String USER_NAME = "user";
    private static final String USER_GROUPS = "edu:lib:staff_grp";

    @Produce(uri = "direct:start")
    protected ProducerTemplate template;

    private AgentPrincipals agent;

    @BeanInject(value = "repositoryObjectLoader")
    private RepositoryObjectLoader repoObjLoader;
    @BeanInject(value = "repositoryObjectFactory")
    private RepositoryObjectFactory repoObjFactory;
    @BeanInject(value = "inheritedAclFactory")
    private InheritedAclFactory inheritedAclFactory;
    @BeanInject(value = "objectPathFactory")
    private ObjectPathFactory objectPathFactory;

    @BeanInject(value = "transactionManager")
    private TransactionManager txManager;

    @Mock
    private WorkObject workObj;
    @Mock
    private FedoraTransaction tx;
    @Mock
    private ObjectPath objPath;

    @Before
    public void setup() {
        initMocks(this);
        AccessGroupSet testPrincipals = new AccessGroupSet(USER_GROUPS);
        agent = new AgentPrincipals(USER_NAME, testPrincipals);

        when(txManager.startTransaction()).thenReturn(tx);
    }

    @Override
    protected AbstractApplicationContext createApplicationContext() {
        return new ClassPathXmlApplicationContext("/service-context.xml", "/destroy-objects-context.xml");
    }

    @Test
    public void destroyObject() throws Exception {
        createContext(DESTROY_ROUTE);

        String id = UUID.randomUUID().toString();
        PID pid = PIDs.get(id);
        when(repoObjLoader.getRepositoryObject(pid)).thenReturn(workObj);
        Model model = createDefaultModel();
        Resource resc = model.getResource(pid.getRepositoryPath());
        resc.addProperty(RDF.type, Cdr.Work);
        when(workObj.getResource()).thenReturn(resc);
        when(workObj.getPid()).thenReturn(pid);
        when(workObj.getUri()).thenReturn(pid.getRepositoryUri());

        when(objectPathFactory.getPath(pid)).thenReturn(objPath);
        when(objPath.toNamePath()).thenReturn("/path/to/stuff");
        when(objPath.toIdPath()).thenReturn(id);

        when(inheritedAclFactory.isMarkedForDeletion(pid)).thenReturn(true);

        String jobId = UUID.randomUUID().toString();
        DestroyObjectsRequest request = new DestroyObjectsRequest(jobId, agent, id);
        template.sendBodyAndHeaders(serializeDestroyRequest(request), null);

        verify(repoObjFactory).createOrTransformObject(eq(pid.getRepositoryUri()), any(Model.class));
    }

    private void createContext(String routeName) throws Exception {
        context.getRouteDefinition(routeName).adviceWith(context, new AdviceWithRouteBuilder() {
            @Override
            public void configure() throws Exception {
                replaceFromWith("direct:start");
                mockEndpointsAndSkip("*");
            }
        });

        context.start();
    }
}