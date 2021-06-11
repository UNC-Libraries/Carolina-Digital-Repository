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

import static edu.unc.lib.dl.util.RDFModelUtil.TURTLE_MIMETYPE;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.apache.http.HttpStatus;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.riot.Lang;
import org.apache.jena.vocabulary.RDF;
import org.fcrepo.client.FcrepoClient;
import org.fcrepo.client.FcrepoOperationFailedException;
import org.fcrepo.client.FcrepoResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.unc.lib.dl.exceptions.OrphanedObjectException;
import edu.unc.lib.dl.fedora.FedoraException;
import edu.unc.lib.dl.fedora.ObjectTypeMismatchException;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.persist.api.event.PremisLogger;
import edu.unc.lib.dl.persist.api.event.PremisLoggerFactory;
import edu.unc.lib.dl.rdf.PcdmModels;
import edu.unc.lib.dl.sparql.SparqlQueryService;

/**
 * Service that provides data and clients to interact with an object's data
 * model.
 *
 * @author bbpennel
 * @author harring
 *
 */
public class RepositoryObjectDriver {
    private static final Logger log = LoggerFactory.getLogger(RepositoryObjectDriver.class);

    private RepositoryObjectLoader repositoryObjectLoader;

    @Deprecated
    private RepositoryObjectFactory repositoryObjectFactory;

    private FcrepoClient client;

    private SparqlQueryService sparqlQueryService;

    private PremisLoggerFactory premisLoggerFactory;

    protected RepositoryPIDMinter pidMinter;

    /**
     * Loads and assigns the RDF types for the given object
     *
     * @param obj
     * @return
     * @throws FedoraException
     */
    public RepositoryObjectDriver loadTypes(RepositoryObject obj) throws FedoraException {
        List<String> types = new ArrayList<>();
        // Iterate through all type properties and add to list
        Resource resc = obj.getModel().getResource(obj.getPid().getRepositoryUri().toString());
        StmtIterator it = resc.listProperties(RDF.type);
        while (it.hasNext()) {
            types.add(it.nextStatement().getResource().getURI());
        }

        obj.setTypes(types);

        return this;
    }

    /**
     * Loads and assigns the model for direct relationships of the given
     * repository object
     *
     * @param obj
     * @param checkForUpdates if true, will reload the model if the object has changed
     * @return
     * @throws FedoraException
     */
    public RepositoryObjectDriver loadModel(RepositoryObject obj, boolean checkForUpdates) throws FedoraException {
        long start = System.nanoTime();
        URI metadataUri = obj.getMetadataUri();
        // Model needs to be loaded if not present, or if checkForUpdates is true and either in a tx or obj has changed
        if (obj.hasModel() && !(checkForUpdates && (FedoraTransaction.isStillAlive() || !obj.isUnmodified()))) {
            log.debug("Object unchanged, reusing existing model for {}", obj.getPid());
            return this;
        }

        // Need to load the model from fedora
        try (FcrepoResponse response = getClient().get(metadataUri)
                .accept(TURTLE_MIMETYPE)
                .perform()) {

            Model model = ModelFactory.createDefaultModel();
            model.read(response.getBody(), null, Lang.TURTLE.getName());

            // Store the fresh model
            obj.storeModel(model);

            // Store updated modification info to track if the object changes
            obj.setEtag(parseEtag(response));
            log.debug("Retrieved new model for {} in {}s", obj.getPid(), (System.nanoTime() - start) / 1e9);

            return this;
        } catch (IOException e) {
            throw new FedoraException("Failed to read model for " + metadataUri, e);
        } catch (FcrepoOperationFailedException e) {
            throw ClientFaultResolver.resolve(e);
        }
    }

    /**
     * Retrieves a RepositoryObject identified by pid
     *
     * @param pid
     * @return
     */
    public RepositoryObject getRepositoryObject(PID pid) {
        return repositoryObjectLoader.getRepositoryObject(pid);
    }

    /**
     * Retrieves a RepositoryObject of the type provided
     *
     * @param pid
     * @param type class of the type of object to retrieve
     * @return
     * @throws ObjectTypeMismatchException thrown if the retrieved object does
     *             not match the requested type
     */
    public <T extends RepositoryObject> T getRepositoryObject(PID pid, Class<T> type)
            throws ObjectTypeMismatchException {
        RepositoryObject repoObj = repositoryObjectLoader.getRepositoryObject(pid);
        if (!type.isInstance(repoObj)) {
            throw new ObjectTypeMismatchException("Requested object " + pid + " is not a " + type.getName());
        }

        return type.cast(repoObj);
    }

    /**
     * Retrieve the binary content for the given BinaryObject as an inputstream
     *
     * @param obj
     * @return
     * @throws FedoraException
     */
    public InputStream getBinaryStream(BinaryObject obj) throws FedoraException {
        PID pid = obj.getPid();

        try {
            FcrepoResponse response = getClient().get(pid.getRepositoryUri()).perform();
            return response.getBody();
        } catch (FcrepoOperationFailedException e) {
            throw ClientFaultResolver.resolve(e);
        }
    }

    /**
     * Retrieves the parent container of the provided object following a parent to child relationship
     *
     * @param child repository object to retrieve the parent of.
     * @param membershipRelation parent to child membership relation to use to
     *            find the parent container.
     * @return PID for the parent container
     * @throws OrphanedObjectException thrown if the object does not have a
     *             parent container.
     */
    public PID fetchContainer(RepositoryObject child, Property membershipRelation) {
        String queryString = String.format("select ?pid where { ?pid <%1$s> <%2$s> }",
                membershipRelation, child.getPid().getURI());

        try (QueryExecution qexec = sparqlQueryService.executeQuery(queryString)) {
            ResultSet results = qexec.execSelect();

            for (; results.hasNext();) {
                QuerySolution soln = results.nextSolution();
                Resource res = soln.getResource("pid");

                if (res != null) {
                    return PIDs.get(res.getURI());
                }
            }
        }

        return null;
    }

    /**
     * Produces a list of PIDs for objects which are members of the provided object.
     *
     * @param obj the object
     * @return a List of PIDs for member objects of the provided object.
     */
    public List<PID> listMembers(RepositoryObject obj) {
        return listRelated(obj, PcdmModels.memberOf);
    }

    /**
     * Produces a list of PIDs for objects which are related to the current object via
     * the provided relationship property.
     *
     * @param obj the object
     * @param relation relation predicate
     * @return a List of PIDs for objects related by the given predicate
     */
    public List<PID> listRelated(RepositoryObject obj, Property relation) {
        PID pid = obj.getPid();
        String queryString = String.format("select ?pid where { ?pid <%1$s> <%2$s> }",
                relation, pid.getURI());
        List<PID> related = new ArrayList<>();

        try (QueryExecution qexec = sparqlQueryService.executeQuery(queryString)) {
            ResultSet results = qexec.execSelect();

            while (results.hasNext()) {
                QuerySolution soln = results.nextSolution();
                Resource res = soln.getResource("pid");

                if (res != null) {
                    related.add(PIDs.get(res.getURI()));
                }
            }
        }

        return related;
    }

    /**
     * Retrieves parent object of the provided object
     * @param obj object to get the parent of.
     * @return RepositoryObject for the parent object of the provided object.
     * @throws OrphanedObjectException thrown if no parent object found for the object.
     * @throws ObjectTypeMismatchException thrown if object is not of a type eligible to have a parent.
     */
    public RepositoryObject getParentObject(RepositoryObject obj) {
        PID parentPid = null;

        if (obj instanceof BinaryObject) {
            parentPid = fetchContainer(obj, PcdmModels.hasFile);
        } else if (obj instanceof ContentObject) {
            // For resources in the membership hierarchy, use reverse membership
            Statement memberOf = obj.getResource().getProperty(PcdmModels.memberOf);
            if (memberOf != null) {
                parentPid = PIDs.get(memberOf.getObject().toString());
            }
        } else {
            throw new ObjectTypeMismatchException("Unable to get parent object for " + obj.getPid()
                    + ", resources of type " + obj.getClass().getName() + " are not eligible.");
        }

        if (parentPid == null) {
            throw new OrphanedObjectException("Cannot find a parent container for object " + obj.getPid());
        }

        return repositoryObjectLoader.getRepositoryObject(parentPid);
    }

    /**
     * Retrieves the etag for the provided object
     *
     * @param obj
     * @return
     */
    public String getEtag(RepositoryObject obj) {
        try (FcrepoResponse response = getClient().head(obj.getMetadataUri()).perform()) {
            if (response.getStatusCode() != HttpStatus.SC_OK) {
                throw new FedoraException("Received " + response.getStatusCode()
                        + " response while retrieving headers for " + obj.getPid().getRepositoryUri());
            }

            return parseEtag(response);
        } catch (IOException e) {
            throw new FedoraException("Unable to create deposit record at "
                    + obj.getPid().getRepositoryUri(), e);
        } catch (FcrepoOperationFailedException e) {
            throw ClientFaultResolver.resolve(e);
        }
    }

    /**
     * Retrieve the ETag of the response, with surrounding quotes stripped.
     *
     * @param response
     * @return
     */
    private static String parseEtag(FcrepoResponse response) {
        String etag = response.getHeaderValue("ETag");
        if (etag != null) {
            return etag.substring(1, etag.length() - 1);
        }
        return null;
    }

    public PremisLogger getPremisLog(RepositoryObject repoObj) {
        return premisLoggerFactory.createPremisLogger(repoObj);
    }

    public void setClient(FcrepoClient client) {
        this.client = client;
    }

    public FcrepoClient getClient() {
        return client;
    }

    public void setRepositoryObjectLoader(RepositoryObjectLoader repoObjLoader) {
        this.repositoryObjectLoader = repoObjLoader;
    }

    public SparqlQueryService getSparqlQueryService() {
        return sparqlQueryService;
    }

    public void setSparqlQueryService(SparqlQueryService SparqlQueryService) {
        this.sparqlQueryService = SparqlQueryService;
    }

    /**
     * @param repositoryObjectFactory the repositoryObjectFactory to set
     */
    public void setRepositoryObjectFactory(RepositoryObjectFactory repositoryObjectFactory) {
        this.repositoryObjectFactory = repositoryObjectFactory;
    }

    /**
     * @param pidMinter the pidMinter to set
     */
    public void setPidMinter(RepositoryPIDMinter pidMinter) {
        this.pidMinter = pidMinter;
    }

    /**
     * @param premisLoggerFactory the premisLoggerFactory to set
     */
    public void setPremisLoggerFactory(PremisLoggerFactory premisLoggerFactory) {
        this.premisLoggerFactory = premisLoggerFactory;
    }
}
