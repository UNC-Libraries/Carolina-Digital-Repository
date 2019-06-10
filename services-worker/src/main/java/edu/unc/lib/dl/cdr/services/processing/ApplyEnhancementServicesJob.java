package edu.unc.lib.dl.cdr.services.processing;

import static edu.unc.lib.dl.util.JMSMessageUtil.servicesMessageNamespace;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ws.client.WebServiceIOException;

import edu.unc.lib.dl.cdr.services.ObjectEnhancementService;
import edu.unc.lib.dl.cdr.services.exception.EnhancementException;
import edu.unc.lib.dl.cdr.services.exception.EnhancementException.Severity;
import edu.unc.lib.dl.cdr.services.model.EnhancementMessage;
import edu.unc.lib.dl.fedora.ManagementClient;
import edu.unc.lib.dl.fedora.ServiceException;
import edu.unc.lib.dl.reporting.ActivityMetricsClient;
import edu.unc.lib.dl.util.JMSMessageUtil.ServicesActions;

public class ApplyEnhancementServicesJob implements Runnable {

	private static final Logger LOG = LoggerFactory.getLogger(ApplyEnhancementServicesJob.class);
	private static long BACKOFF_DELAY = 10000;
	private static int MAX_RETRIES = 5;

	private List<ObjectEnhancementService> services;
	private long recoverableDelay = 0;
	private final EnhancementMessage message;
	private ActivityMetricsClient metricsClient;
	private ManagementClient fedoraManagementClient;

	public ApplyEnhancementServicesJob(String pidString, boolean force) {
		this.message = new EnhancementMessage(pidString, servicesMessageNamespace,
				ServicesActions.APPLY_SERVICE_STACK.getName());
		this.message.setCompletedServices(new ArrayList<String>());
		this.message.setForce(force);
	}

	public ApplyEnhancementServicesJob(String messagePid, String messageNamespace, String messageAction, String messageServiceName, List<String> filteredServices) {
		this.message = new EnhancementMessage(messagePid, messageNamespace, messageAction, messageServiceName);
		this.message.setFilteredServices(filteredServices);
		this.message.setCompletedServices(new ArrayList<String>());
	}

	public void setServices(List<ObjectEnhancementService> services) {
		this.services = services;
	}

	public void setRecoverableDelay(long recoverableDelay) {
		this.recoverableDelay = recoverableDelay;
	}

	public void setMetricsClient(ActivityMetricsClient operationMetricsClient) {
		this.metricsClient = operationMetricsClient;
	}

	public void setFedoraManagementClient(ManagementClient fedoraManagementClient) {
		this.fedoraManagementClient = fedoraManagementClient;
	}

	@Override
	public void run() {
		for (ObjectEnhancementService service : services) {
			if (message.getFilteredServices() != null
					&& !message.getFilteredServices().contains(service.getClass().getName())) {
				continue;
			}

			try {
				if (!service.isApplicable(message)) {
					continue;
				}
			} catch (EnhancementException e) {
				LOG.error("Error determining applicability for service " + service.getClass().getName() + " and object " + message.getTargetID(), e);
			}

			try {
				applyService(service);
				metricsClient.incrFinishedEnhancement(service.getClass().getName());
			} catch (EnhancementException e) {
				LOG.error("Error applying service " + service.getClass().getName() + " to object " + message.getTargetID(), e);
				metricsClient.incrFailedEnhancement(service.getClass().getName());
			} catch (Throwable t) {
				metricsClient.incrFailedEnhancement(service.getClass().getName());
				throw t;
			}
		}
	}

	private void applyService(ObjectEnhancementService service) throws EnhancementException {
		int backoffAttempts = 1;
		
		while (true) {
			LOG.info("Applying service {} to object {}", service.getClass().getName(), message.getTargetID());

			long retryDelay;
			try {
				// If the repository is not available, wait until it recovers
				fedoraManagementClient.waitForRepositoryAvailable();
				
				service.getEnhancement(message).call();
				message.getCompletedServices().add(service.getClass().getName());
				break;
			} catch (EnhancementException e) {
				if (backoffAttempts < MAX_RETRIES && e.getSeverity() == Severity.RECOVERABLE) {
					LOG.warn("Retrying service for recoverable exception {}, attempt {}.",
							new Object[] { service.getClass().getName(), backoffAttempts }, e);
					retryDelay = recoverableDelay;
				} else {
					throw e;
				}
			} catch (ServiceException e) {
				if (backoffAttempts < MAX_RETRIES && e.getCause() instanceof WebServiceIOException) {
					LOG.warn("Failed to persist changes for service {}, attempt {}",
							new Object[] { service.getClass().getName(), backoffAttempts }, e);
					retryDelay = BACKOFF_DELAY * backoffAttempts;
				} else {
					throw e;
				}
			}

			backoffAttempts++;
			// Clear cached foxml prior to attempting to recover
			message.setFoxml(null);
			
			try {
				Thread.sleep(retryDelay);
			} catch (InterruptedException e1) {
				LOG.warn("Thread interrupted while waiting to reattempt job {}", service.getClass().getName());
				Thread.currentThread().interrupt();
				break;
			}
			
		}
	}

}