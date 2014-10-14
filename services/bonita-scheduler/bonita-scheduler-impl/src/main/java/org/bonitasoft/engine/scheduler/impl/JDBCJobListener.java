/**
 * Copyright (C) 2013-2014 BonitaSoft S.A.
 * BonitaSoft, 32 rue Gustave Eiffel - 38000 Grenoble
 * This library is free software; you can redistribute it and/or modify it under the terms
 * of the GNU Lesser General Public License as published by the Free Software Foundation
 * version 2.1 of the License.
 * This library is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 * You should have received a copy of the GNU Lesser General Public License along with this
 * program; if not, write to the Free Software Foundation, Inc., 51 Franklin Street, Fifth
 * Floor, Boston, MA 02110-1301, USA.
 *
 * @since 6.1
 */
package org.bonitasoft.engine.scheduler.impl;

import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.bonitasoft.engine.commons.exceptions.SBonitaException;
import org.bonitasoft.engine.incident.Incident;
import org.bonitasoft.engine.incident.IncidentService;
import org.bonitasoft.engine.log.technical.TechnicalLogSeverity;
import org.bonitasoft.engine.log.technical.TechnicalLoggerService;
import org.bonitasoft.engine.persistence.FilterOption;
import org.bonitasoft.engine.persistence.OrderByOption;
import org.bonitasoft.engine.persistence.OrderByType;
import org.bonitasoft.engine.persistence.QueryOptions;
import org.bonitasoft.engine.persistence.SBonitaSearchException;
import org.bonitasoft.engine.recorder.model.EntityUpdateDescriptor;
import org.bonitasoft.engine.scheduler.AbstractBonitaPlatormJobListener;
import org.bonitasoft.engine.scheduler.JobService;
import org.bonitasoft.engine.scheduler.SchedulerService;
import org.bonitasoft.engine.scheduler.StatelessJob;
import org.bonitasoft.engine.scheduler.exception.SSchedulerException;
import org.bonitasoft.engine.scheduler.exception.jobDescriptor.SJobDescriptorNotFoundException;
import org.bonitasoft.engine.scheduler.exception.jobDescriptor.SJobDescriptorReadException;
import org.bonitasoft.engine.scheduler.exception.jobLog.SJobLogCreationException;
import org.bonitasoft.engine.scheduler.exception.jobLog.SJobLogDeletionException;
import org.bonitasoft.engine.scheduler.exception.jobLog.SJobLogUpdatingException;
import org.bonitasoft.engine.scheduler.model.SJobDescriptor;
import org.bonitasoft.engine.scheduler.model.SJobLog;
import org.bonitasoft.engine.scheduler.model.impl.SJobLogImpl;

/**
 * @author Celine Souchet
 * @author Matthieu Chaffotte
 * @author Elias Ricken de Medeiros
 */
public class JDBCJobListener extends AbstractBonitaPlatormJobListener {

    private static final long serialVersionUID = -5060516371371295271L;

    private final JobService jobService;

    private final IncidentService incidentService;

    private final TechnicalLoggerService logger;

    private final SchedulerService schedulerService;

    public JDBCJobListener(final SchedulerService schedulerService, final JobService jobService, final IncidentService incidentService,
            final TechnicalLoggerService logger) {
        super();
        this.schedulerService = schedulerService;
        this.jobService = jobService;
        this.incidentService = incidentService;
        this.logger = logger;
    }

    @Override
    public String getName() {
        return "JDBCJobListener";
    }

    @Override
    public void jobToBeExecuted(final Map<String, Serializable> context) {
        // nothing to do
    }

    @Override
    public void jobExecutionVetoed(final Map<String, Serializable> context) {
        // nothing to do
    }

    @Override
    public void jobWasExecuted(final Map<String, Serializable> context, final Exception jobException) {
        final StatelessJob bosJob = (StatelessJob) context.get(BOS_JOB);
        if (bosJob == null) {
            return;
        }

        final Long jobDescriptorId = (Long) context.get(JOB_DESCRIPTOR_ID);
        final Long tenantId = (Long) context.get(TENANT_ID);
        try {
            if (jobDescriptorId != null) {
                if (jobException != null) {
                    final List<SJobLog> jobLogs = getJobLogs(jobDescriptorId);
                    if (!jobLogs.isEmpty()) {
                        updateJobLog(jobException, jobLogs);
                    } else {
                        createJobLog(jobException, jobDescriptorId);
                    }
                } else {
                    cleanJobLogIfAny(jobDescriptorId);
                    deleteJobIfNotScheduledAnyMore(jobDescriptorId);
                }
            } else if (logger.isLoggable(getClass(), TechnicalLogSeverity.WARNING)) {
                logger.log(getClass(), TechnicalLogSeverity.WARNING, "An exception occurs during the job execution: " + jobException);
            }
        } catch (final SBonitaException sbe) {
            final Incident incident = new Incident("An exception occurs during the job execution of the job descriptor" + jobDescriptorId, "", jobException,
                    sbe);
            incidentService.report(tenantId, incident);
        }
    }

    private void createJobLog(final Exception jobException, final Long jobDescriptorId) throws SJobLogCreationException {
        final SJobLogImpl jobLog = new SJobLogImpl(jobDescriptorId);
        jobLog.setLastMessage(getStackTrace(jobException));
        jobLog.setRetryNumber(Long.valueOf(0));
        jobLog.setLastUpdateDate(System.currentTimeMillis());
        jobService.createJobLog(jobLog);
    }

    private void updateJobLog(final Exception jobException, final List<SJobLog> jobLogs) throws SJobLogUpdatingException {
        final SJobLog jobLog = jobLogs.get(0);
        final EntityUpdateDescriptor descriptor = new EntityUpdateDescriptor();
        descriptor.addField("lastMessage", getStackTrace(jobException));
        descriptor.addField("lastUpdateDate", System.currentTimeMillis());
        descriptor.addField("retryNumber", jobLog.getRetryNumber() + 1);
        jobService.updateJobLog(jobLog, descriptor);
    }

    private void deleteJobIfNotScheduledAnyMore(final Long jobDescriptorId) throws SJobDescriptorNotFoundException, SJobDescriptorReadException,
            SSchedulerException {
        try {
            final SJobDescriptor jobDescriptor = jobService.getJobDescriptor(jobDescriptorId);
            if (!schedulerService.isStillScheduled(jobDescriptor)) {
                schedulerService.delete(jobDescriptor.getJobName());
            }
        } catch (final SJobDescriptorNotFoundException e) {
            if (logger.isLoggable(this.getClass(), TechnicalLogSeverity.TRACE)) {
                final StringBuilder stringBuilder = new StringBuilder();
                stringBuilder.append("jobDescriptor with id");
                stringBuilder.append(jobDescriptorId);
                stringBuilder.append(" already deleted, ignore it");
                logger.log(this.getClass(), TechnicalLogSeverity.TRACE, stringBuilder.toString());
            }
        }
    }

    private void cleanJobLogIfAny(final Long jobDescriptorId) throws SBonitaSearchException, SJobLogDeletionException {
        final List<SJobLog> jobLogs = getJobLogs(jobDescriptorId);
        if (!jobLogs.isEmpty()) {
            jobService.deleteJobLog(jobLogs.get(0));
        }
    }

    private List<SJobLog> getJobLogs(final Long jobDescriptorId) throws SBonitaSearchException {
        final List<FilterOption> filters = new ArrayList<FilterOption>(2);
        filters.add(new FilterOption(SJobLog.class, "jobDescriptorId", jobDescriptorId));
        final OrderByOption orderByOption = new OrderByOption(SJobLog.class, "jobDescriptorId", OrderByType.ASC);
        final QueryOptions options = new QueryOptions(0, 1, Arrays.asList(orderByOption), filters, null);
        return jobService.searchJobLogs(options);
    }

    private String getStackTrace(final Exception jobException) {
        final StringWriter exceptionWriter = new StringWriter();
        jobException.printStackTrace(new PrintWriter(exceptionWriter));
        return exceptionWriter.toString();
    }

}
