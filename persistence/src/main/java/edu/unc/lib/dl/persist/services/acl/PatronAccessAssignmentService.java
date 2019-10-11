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
package edu.unc.lib.dl.persist.services.acl;

import static edu.unc.lib.dl.acl.util.EmbargoUtil.isEmbargoActive;
import static edu.unc.lib.dl.acl.util.Permission.changePatronAccess;
import static edu.unc.lib.dl.rdf.CdrAcl.embargoUntil;
import static edu.unc.lib.dl.util.DateTimeUtil.formatDateToUTC;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.springframework.util.Assert.notNull;

import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.datatypes.xsd.XSDDateTime;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.unc.lib.dl.acl.exception.InvalidAssignmentException;
import edu.unc.lib.dl.acl.fcrepo4.ContentObjectAccessRestrictionValidator;
import edu.unc.lib.dl.acl.service.AccessControlService;
import edu.unc.lib.dl.acl.util.AgentPrincipals;
import edu.unc.lib.dl.acl.util.RoleAssignment;
import edu.unc.lib.dl.acl.util.UserRole;
import edu.unc.lib.dl.event.PremisLogger;
import edu.unc.lib.dl.fcrepo4.AdminUnit;
import edu.unc.lib.dl.fcrepo4.ContentObject;
import edu.unc.lib.dl.fcrepo4.FedoraTransaction;
import edu.unc.lib.dl.fcrepo4.RepositoryObject;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectFactory;
import edu.unc.lib.dl.fcrepo4.RepositoryObjectLoader;
import edu.unc.lib.dl.fcrepo4.TransactionManager;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.fedora.ServiceException;
import edu.unc.lib.dl.metrics.TimerFactory;
import edu.unc.lib.dl.rdf.Premis;
import edu.unc.lib.dl.services.OperationsMessageSender;
import edu.unc.lib.dl.util.JMSMessageUtil.CDRActions;
import io.dropwizard.metrics5.Timer;

/**
 * Service which replaces patron access assignments for content objects.
 *
 * @author bbpennel
 *
 */
public class PatronAccessAssignmentService {

    private static final Logger log = LoggerFactory.getLogger(PatronAccessAssignmentService.class);
    private static final String NEWLINE = System.getProperty("line.separator");

    private AccessControlService aclService;
    private RepositoryObjectLoader repositoryObjectLoader;
    private RepositoryObjectFactory repositoryObjectFactory;
    private OperationsMessageSender operationsMessageSender;
    private TransactionManager txManager;

    private ContentObjectAccessRestrictionValidator accessValidator;

    private static final Timer timer = TimerFactory.createTimerForClass(PatronAccessAssignmentService.class);

    public PatronAccessAssignmentService() {
        accessValidator = new ContentObjectAccessRestrictionValidator();
    }

    public String updatePatronAccess(AgentPrincipals agent, PID target, PatronAccessDetails accessDetails) {
        notNull(agent, "Must provide an agent for this operation");
        notNull(target, "Must provide the PID of the object to update");
        notNull(accessDetails, "Must provide patron access details");

        FedoraTransaction tx = txManager.startTransaction();
        log.info("Starting update of patron access on {}", target.getId());
        try (Timer.Context context = timer.time()) {
            aclService.assertHasAccess("Insufficient privileges to assign patron roles for object " + target.getId(),
                    target, agent.getPrincipals(), changePatronAccess);

            assertAssignmentsComplete(accessDetails);

            assertOnlyPatronRoles(accessDetails);

            RepositoryObject repoObj = repositoryObjectLoader.getRepositoryObject(target);

            if (repoObj instanceof AdminUnit || !(repoObj instanceof ContentObject)) {
                throw new InvalidAssignmentException("Cannot assign patron access to object " + target.getId()
                    + ", objects of type " + repoObj.getClass().getName() + " are not eligible.");
            }

            Model updated = ModelFactory.createDefaultModel().add(repoObj.getModel());
            Resource rolesEventResc = replacePatronRoles(repoObj, agent, updated, accessDetails.getRoles());
            Resource embargoEventResc = updateEmbargo(repoObj, agent, updated, accessDetails.getEmbargo());

            // No changes occurred, perform no updates to the resource
            if (rolesEventResc == null && embargoEventResc == null) {
                return null;
            }

            // Validate that the access control assignments are allowed
            accessValidator.validate(updated.getResource(repoObj.getPid().getRepositoryPath()));

            repositoryObjectFactory.createOrTransformObject(repoObj.getUri(), updated);

            writePremisEvents(repoObj, rolesEventResc, embargoEventResc);

            return operationsMessageSender.sendOperationMessage(agent.getUsername(),
                    CDRActions.EDIT_ACCESS_CONTROL,
                    Collections.singletonList(target));
        } catch (RuntimeException e) {
            tx.cancelAndIgnore();
            throw e;
        } finally {
            tx.close();
        }
    }

    /**
     * Replace all the patron roles for the provided object
     *
     * @param repoObj
     * @param agent
     * @param model
     * @param assignments
     * @return Returns the premis event resource created to capture this event, or null if no patron roles changed.
     */
    private Resource replacePatronRoles(RepositoryObject repoObj, AgentPrincipals agent, Model model,
            Collection<RoleAssignment> assignments) {
        // Update a copy of the model for this object
        Resource resc = model.getResource(repoObj.getPid().getRepositoryPath());

        // So nor proceed if there are no changes to role assignments
        if (!hasRoleChanges(resc, assignments)) {
            return null;
        }

        // Clear out all the existing staff roles
        for (UserRole role: UserRole.getPatronRoles()) {
            resc.removeAll(role.getProperty());
        }

        // Add the new role assignments
        if (assignments != null) {
            for (RoleAssignment assignment: assignments) {
                resc.addProperty(assignment.getRole().getProperty(), assignment.getPrincipal());
            }
        }

        // Add PREMIS event indicating the role changes
        return repoObj.getPremisLog().buildEvent(Premis.PolicyAssignment)
                .addImplementorAgent(agent.getUsernameUri())
                .addEventDetail(createRoleEventDetails(assignments))
                .create();
    }

    /**
     * Returns true if there are any differences between the patron role
     * assignments on the existing resource versus the incoming role asisgnments
     *
     * @param resc
     * @param assignments
     * @return
     */
    private boolean hasRoleChanges(Resource resc, Collection<RoleAssignment> assignments) {
        Set<String> existingPatronRoles = new HashSet<>();
        StmtIterator it = resc.listProperties();
        while (it.hasNext()) {
            Statement stmt = it.next();
            UserRole role = UserRole.getRoleByProperty(stmt.getPredicate().toString());
            if (role != null && role.isPatronRole()) {
                existingPatronRoles.add(stmt.getString() + "|" + role.name());
            }
        }

        Set<String> incomingRoles;
        if (assignments == null) {
            incomingRoles = Collections.emptySet();
        } else {
            incomingRoles = assignments.stream()
                    .map(a -> a.getPrincipal() + "|" + a.getRole().name())
                    .collect(Collectors.toSet());
        }

        if (incomingRoles.size() != existingPatronRoles.size()) {
            return true;
        }

        return !incomingRoles.containsAll(existingPatronRoles);
    }

    private Resource updateEmbargo(RepositoryObject repoObj, AgentPrincipals agent, Model model, Date newEmbargo) {
        if (newEmbargo != null && !isEmbargoActive(newEmbargo)) {
            throw new InvalidAssignmentException("Cannot assign expired embargo to object "
                    + repoObj.getPid().getId());
        }

        Resource resc = model.getResource(repoObj.getPid().getRepositoryPath());

        String existingEmbargo = null;
        // Remove any existing embargoes
        if (resc.hasProperty(embargoUntil)) {
            existingEmbargo = formatDateToUTC(((XSDDateTime) resc.getProperty(embargoUntil).getLiteral()
                    .getValue()).asCalendar().getTime());
            resc.removeAll(embargoUntil);
        }

        String newEmbargoString = null;
        // Add the new embargo to the record, if supplied
        if (newEmbargo != null) {
            newEmbargoString = formatDateToUTC(newEmbargo);
            resc.addProperty(embargoUntil, newEmbargoString, XSDDatatype.XSDdateTime);
        }

        String eventText = createEmbargoEventDetails(existingEmbargo, newEmbargoString);
        if (eventText == null) {
            // No embargo change, return no event.
            return null;
        } else {
            // Produce the premis event for this embargo
            return repoObj.getPremisLog().buildEvent(Premis.PolicyAssignment)
                    .addImplementorAgent(agent.getUsernameUri())
                    .addEventDetail(eventText)
                    .create();
        }
    }

    private String createRoleEventDetails(Collection<RoleAssignment> assignments) {
        StringBuilder details = new StringBuilder("Patron roles for item set to:");
        details.append(NEWLINE);
        if (assignments == null || assignments.isEmpty()) {
            details.append("No roles assigned");
        } else {
            String roleDetails = assignments.stream()
                    .map(a -> a.getPrincipal() + ": " + a.getRole().getPropertyString())
                    .collect(Collectors.joining(NEWLINE));
            details.append(roleDetails);
        }

        return details.toString();
    }

    private void assertAssignmentsComplete(PatronAccessDetails details) {
        if (details.getRoles() == null) {
            return;
        }
        for (RoleAssignment assignment: details.getRoles()) {
            if (assignment.getRole() == null) {
                throw new IllegalArgumentException("Assignment must provide a role");
            }
            if (isBlank(assignment.getPrincipal())) {
                throw new IllegalArgumentException("Assignment must provide a principal");
            }
        }
    }

    private void assertOnlyPatronRoles(PatronAccessDetails details) {
        if (details.getRoles() != null && details.getRoles().stream().anyMatch(a -> a.getRole().isStaffRole())) {
            throw new ServiceException("Only patron roles are applicable for this service");
        }
    }

    private String createEmbargoEventDetails(String existingEmbargo, String newEmbargo) {
        if (existingEmbargo == null) {
            if (newEmbargo == null) {
                // No change
                return null;
            } else {
                // Assigned a new embargo
                return "Set an embargo that will expire " + newEmbargo;
            }
        } else {
            if (newEmbargo == null) {
                // Cleared an existing embargo
                return "Embargo expiration date changed from " + existingEmbargo + " to no embargo.";
            } else if (newEmbargo.equals(existingEmbargo)) {
                // No change
                return null;
            } else {
                // Change existing embargo
                return "Embargo expiration date changed from " + existingEmbargo + " to " + newEmbargo;
            }
        }
    }

    private void writePremisEvents(RepositoryObject repoObj, Resource rolesEvent, Resource embargoEvent) {
        PremisLogger logger = repoObj.getPremisLog();

        if (rolesEvent != null) {
            if (embargoEvent != null) {
                logger.writeEvents(rolesEvent, embargoEvent);
            } else {
                logger.writeEvents(rolesEvent);
            }
        } else {
            // This method is only called if either roles or embargo event is non-null
            logger.writeEvents(embargoEvent);
        }
    }

    public void setAclService(AccessControlService aclService) {
        this.aclService = aclService;
    }

    public void setRepositoryObjectLoader(RepositoryObjectLoader repositoryObjectLoader) {
        this.repositoryObjectLoader = repositoryObjectLoader;
    }

    public void setRepositoryObjectFactory(RepositoryObjectFactory repositoryObjectFactory) {
        this.repositoryObjectFactory = repositoryObjectFactory;
    }

    public void setOperationsMessageSender(OperationsMessageSender operationsMessageSender) {
        this.operationsMessageSender = operationsMessageSender;
    }

    public void setTransactionManager(TransactionManager txManager) {
        this.txManager = txManager;
    }

    public void setAccessValidator(ContentObjectAccessRestrictionValidator accessValidator) {
        this.accessValidator = accessValidator;
    }
}