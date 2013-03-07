/*
 * Copyright (C) 2012-2013 BonitaSoft S.A.
 * BonitaSoft, 32 rue Gustave Eiffel - 38000 Grenoble
 */
package com.bonitasoft.engine.api.impl;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;

import org.apache.commons.io.FileUtils;
import org.bonitasoft.engine.api.impl.NodeConfiguration;
import org.bonitasoft.engine.api.impl.PageIndexCheckingUtil;
import org.bonitasoft.engine.api.impl.PlatformAPIImpl;
import org.bonitasoft.engine.api.impl.transaction.ActivateTenant;
import org.bonitasoft.engine.api.impl.transaction.CreateDefaultPrivileges;
import org.bonitasoft.engine.api.impl.transaction.CreateTenant;
import org.bonitasoft.engine.api.impl.transaction.DeactivateTenant;
import org.bonitasoft.engine.api.impl.transaction.DeleteTenant;
import org.bonitasoft.engine.api.impl.transaction.DeleteTenantObjects;
import org.bonitasoft.engine.api.impl.transaction.GetDefaultTenantInstance;
import org.bonitasoft.engine.api.impl.transaction.GetTenantInstance;
import org.bonitasoft.engine.api.impl.transaction.RemovePrivilege;
import org.bonitasoft.engine.bpm.model.privilege.Privilege;
import org.bonitasoft.engine.command.CommandService;
import org.bonitasoft.engine.commons.IOUtil;
import org.bonitasoft.engine.commons.exceptions.SBonitaException;
import org.bonitasoft.engine.commons.transaction.TransactionContent;
import org.bonitasoft.engine.commons.transaction.TransactionContentWithResult;
import org.bonitasoft.engine.commons.transaction.TransactionExecutor;
import org.bonitasoft.engine.data.DataService;
import org.bonitasoft.engine.data.model.builder.SDataSourceModelBuilder;
import org.bonitasoft.engine.exception.BonitaException;
import org.bonitasoft.engine.exception.BonitaHomeConfigurationException;
import org.bonitasoft.engine.exception.BonitaHomeNotSetException;
import org.bonitasoft.engine.exception.BonitaRuntimeException;
import org.bonitasoft.engine.exception.InvalidSessionException;
import org.bonitasoft.engine.exception.PageOutOfRangeException;
import org.bonitasoft.engine.exception.PlatformNotStartedException;
import org.bonitasoft.engine.exception.SearchException;
import org.bonitasoft.engine.home.BonitaHomeServer;
import org.bonitasoft.engine.io.PropertiesManager;
import org.bonitasoft.engine.log.technical.TechnicalLogSeverity;
import org.bonitasoft.engine.persistence.OrderByType;
import org.bonitasoft.engine.platform.PlatformService;
import org.bonitasoft.engine.platform.SDeletingActivatedTenantException;
import org.bonitasoft.engine.platform.STenantCreationException;
import org.bonitasoft.engine.platform.STenantNotFoundException;
import org.bonitasoft.engine.platform.model.STenant;
import org.bonitasoft.engine.platform.model.builder.STenantBuilder;
import org.bonitasoft.engine.privilege.api.PrivilegeService;
import org.bonitasoft.engine.privilege.model.buidler.PrivilegeBuilders;
import org.bonitasoft.engine.recorder.model.EntityUpdateDescriptor;
import org.bonitasoft.engine.scheduler.SSchedulerException;
import org.bonitasoft.engine.scheduler.SchedulerService;
import org.bonitasoft.engine.search.SearchOptions;
import org.bonitasoft.engine.search.SearchPrivilegeDescriptor;
import org.bonitasoft.engine.search.SearchPrivileges;
import org.bonitasoft.engine.search.SearchResult;
import org.bonitasoft.engine.search.impl.SearchOptionsImpl;
import org.bonitasoft.engine.session.SessionService;
import org.bonitasoft.engine.session.model.SSession;
import org.bonitasoft.engine.work.WorkService;

import com.bonitasoft.engine.api.PlatformAPI;
import com.bonitasoft.engine.api.impl.transaction.GetNumberOfTenants;
import com.bonitasoft.engine.api.impl.transaction.GetTenants;
import com.bonitasoft.engine.api.impl.transaction.GetTenantsWithOrder;
import com.bonitasoft.engine.api.impl.transaction.UpdateTenant;
import com.bonitasoft.engine.exception.TenantActivationException;
import com.bonitasoft.engine.exception.TenantAlreadyExistException;
import com.bonitasoft.engine.exception.TenantCreationException;
import com.bonitasoft.engine.exception.TenantDeactivationException;
import com.bonitasoft.engine.exception.TenantDeletionException;
import com.bonitasoft.engine.exception.TenantNotFoundException;
import com.bonitasoft.engine.exception.TenantUpdateException;
import com.bonitasoft.engine.platform.Tenant;
import com.bonitasoft.engine.platform.TenantCriterion;
import com.bonitasoft.engine.platform.TenantUpdateDescriptor;
import com.bonitasoft.engine.platform.TenantUpdateDescriptor.TenantField;
import com.bonitasoft.engine.search.SearchPlatformEntitiesDescriptor;
import com.bonitasoft.engine.search.SearchTenants;
import com.bonitasoft.engine.service.PlatformServiceAccessor;
import com.bonitasoft.engine.service.SPModelConvertor;
import com.bonitasoft.engine.service.TenantServiceAccessor;
import com.bonitasoft.engine.service.impl.ServiceAccessorFactory;
import com.bonitasoft.manager.Features;
import com.bonitasoft.manager.Manager;

/**
 * @author Matthieu Chaffotte
 * @author Celine Souchet
 */
public class PlatformAPIExt extends PlatformAPIImpl implements PlatformAPI {

    private final static String STATUS_DEACTIVATED = "DEACTIVATED";

    @Override
    protected PlatformServiceAccessor getPlatformAccessor() throws BonitaHomeNotSetException, InstantiationException, IllegalAccessException,
            ClassNotFoundException, IOException, BonitaHomeConfigurationException {
        return ServiceAccessorFactory.getInstance().createPlatformServiceAccessor();
    }

    @Override
    protected TenantServiceAccessor getTenantServiceAccessor(final long tenantId) throws SBonitaException, BonitaHomeNotSetException, IOException,
            BonitaHomeConfigurationException, NoSuchMethodException, InstantiationException, IllegalAccessException, InvocationTargetException {
        return ServiceAccessorFactory.getInstance().createTenantServiceAccessor(tenantId);
    }

    private boolean isPlatformStarted(final PlatformServiceAccessor platformAccessor) {
        final SchedulerService schedulerService = platformAccessor.getSchedulerService();
        try {
            return schedulerService.isStarted();
        } catch (final SSchedulerException e) {
            log(platformAccessor, e, TechnicalLogSeverity.ERROR);
        }
        return false;
    }

    @Override
    public final long createTenant(final String tenantName, final String description, final String iconName, final String iconPath, final String username,
            final String password) throws InvalidSessionException, TenantCreationException, PlatformNotStartedException, TenantAlreadyExistException {
        if (!Manager.isFeatureActive(Features.CREATE_TENANT)) {
            throw new IllegalStateException("The tenant creation is not an active feature");
        }
        PlatformServiceAccessor platformAccessor = null;
        TransactionExecutor transactionExecutor = null;
        PlatformService platformService = null;
        try {
            platformAccessor = ServiceAccessorFactory.getInstance().createPlatformServiceAccessor();
            platformService = platformAccessor.getPlatformService();
            transactionExecutor = platformAccessor.getTransactionExecutor();
            final GetTenantInstance transactionContent = new GetTenantInstance(tenantName, platformService);
            transactionExecutor.execute(transactionContent);
            final String message = "Tenant named \"" + tenantName + "\" already exists!";
            throw new TenantAlreadyExistException(message);
        } catch (final STenantNotFoundException e) {
            // ok
        } catch (final TenantAlreadyExistException e) {
            throw e;
        } catch (final Exception e) {
            log(platformAccessor, e, TechnicalLogSeverity.ERROR);
            throw new TenantCreationException(e);
        }
        return create(tenantName, description, iconName, iconPath, username, password, false);
    }

    private long create(final String tenantName, final String description, final String iconName, final String iconPath, final String userName,
            final String password, final boolean isDefault) throws TenantCreationException, PlatformNotStartedException {
        PlatformServiceAccessor platformAccessor = null;
        try {
            platformAccessor = ServiceAccessorFactory.getInstance().createPlatformServiceAccessor();
            final PlatformService platformService = platformAccessor.getPlatformService();
            final TransactionExecutor transactionExecutor = platformAccessor.getTransactionExecutor();

            // add tenant to database
            final String createdBy = "defaultUser";
            final STenantBuilder sTenantBuilder = platformAccessor.getSTenantBuilder();
            final STenant tenant = sTenantBuilder.createNewInstance(tenantName, createdBy, System.currentTimeMillis(), STATUS_DEACTIVATED, isDefault)
                    .setDescription(description).setIconName(iconName).setIconPath(iconPath).done();
            final CreateTenant transactionContent = new CreateTenant(tenant, platformService);
            transactionExecutor.execute(transactionContent);

            // add tenant folder
            String targetDir = null;
            String sourceDir = null;
            try {
                final BonitaHomeServer home = BonitaHomeServer.getInstance();
                targetDir = home.getTenantsFolder() + File.separator + tenant.getId();
                sourceDir = home.getTenantTemplateFolder();
            } catch (final Exception e) {
                deleteTenant(tenant.getId());
                throw new STenantCreationException("Bonita home not set!");
            }
            // copy configuration file
            try {
                FileUtils.copyDirectory(new File(sourceDir), new File(targetDir));
            } catch (final IOException e) {
                IOUtil.deleteDir(new File(targetDir));
                deleteTenant(tenant.getId());
                throw new STenantCreationException("Copy File Exception!");
            }
            // modify user name and password
            try {
                modifyTechnicalUser(tenant.getId(), userName, password);
            } catch (final Exception e) {
                IOUtil.deleteDir(new File(targetDir));
                deleteTenant(tenant.getId());
                throw new STenantCreationException("Modify File Exception!");
            }
            final Long tenantId = transactionContent.getResult();

            final SDataSourceModelBuilder sDataSourceModelBuilder = platformAccessor.getTenantServiceAccessor(tenantId).getSDataSourceModelBuilder();
            final DataService dataService = platformAccessor.getTenantServiceAccessor(tenantId).getDataService();
            final SessionService sessionService = platformAccessor.getSessionService();
            final CommandService commandService = platformAccessor.getTenantServiceAccessor(tenantId).getCommandService();
            final PrivilegeService privilegeService = platformAccessor.getTenantServiceAccessor(tenantId).getPrivilegeService();
            final PrivilegeBuilders privilegeBuilders = platformAccessor.getTenantServiceAccessor(tenantId).getPrivilegeBuilders();
            boolean txOpened = transactionExecutor.openTransaction();
            try {

                final SSession session = sessionService.createSession(tenantId, userName);

                createDefaultDataSource(sDataSourceModelBuilder, dataService);

                commandService.createDefaultCommands();

                final CreateDefaultPrivileges createDefaultPrivileges = new CreateDefaultPrivileges(privilegeService, privilegeBuilders);
                createDefaultPrivileges.execute();

                sessionService.deleteSession(session.getId());

                return tenantId;
            } finally {
                transactionExecutor.completeTransaction(txOpened);
            }
        } catch (final Exception e) {
            throw new TenantCreationException("Unable to create tenant " + tenantName, e);
        }
    }

    // modify user name and password
    private void modifyTechnicalUser(final long tenantId, final String userName, final String password) throws IOException, BonitaHomeNotSetException {
        final String tenantPath = BonitaHomeServer.getInstance().getTenantConfFolder(tenantId) + File.separator + "bonita-server.xml";
        final File file = new File(tenantPath);
        if (!file.exists()) {
            file.createNewFile();
        }
        final Properties properties = PropertiesManager.getPropertiesFromXML(file);
        if (userName != null) {
            properties.setProperty("userName", userName);
        }
        if (password != null) {
            properties.setProperty("userPassword", password);
        }
        PropertiesManager.savePropertiesToXML(properties, file);
    }

    @Override
    public void deleteTenant(final long tenantId) throws InvalidSessionException, TenantNotFoundException, TenantDeletionException, PlatformNotStartedException {
        PlatformServiceAccessor platformAccessor = null;
        try {
            platformAccessor = ServiceAccessorFactory.getInstance().createPlatformServiceAccessor();
            if (!isPlatformStarted(platformAccessor)) {
                throw new PlatformNotStartedException();
            }
            final PlatformService platformService = platformAccessor.getPlatformService();
            final TransactionExecutor transactionExecutor = platformAccessor.getTransactionExecutor();

            // delete tenant objects in database
            final TransactionContent transactionContentForTenantObjects = new DeleteTenantObjects(tenantId, platformService);
            transactionExecutor.execute(transactionContentForTenantObjects);

            // delete tenant in database
            final TransactionContent transactionContent = new DeleteTenant(tenantId, platformService);
            transactionExecutor.execute(transactionContent);

            // delete default privileges
            final TenantServiceAccessor tenantAccessor = platformAccessor.getTenantServiceAccessor(tenantId);
            final PrivilegeService privilegeService = tenantAccessor.getPrivilegeService();
            final SearchPrivilegeDescriptor privilegeSearcher = tenantAccessor.getSearchEntitiesDescriptor().getPrivilegeDescriptor();
            final SearchOptions searchOptions = new SearchOptionsImpl(0, 10);
            final SearchPrivileges searchPrivileges = new SearchPrivileges(privilegeService, privilegeSearcher, searchOptions);
            transactionExecutor.execute(searchPrivileges);
            final SearchResult<Privilege> privilegesRes = searchPrivileges.getResult();
            if (privilegesRes.getCount() > 0) {
                for (final Privilege pri : privilegesRes.getResult()) {
                    final RemovePrivilege removePrivilege = new RemovePrivilege(pri.getId(), privilegeService);
                    transactionExecutor.execute(removePrivilege);
                }
            }

            // delete tenant folder
            final String targetDir = BonitaHomeServer.getInstance().getTenantsFolder() + File.separator + tenantId;
            IOUtil.deleteDir(new File(targetDir));
        } catch (final PlatformNotStartedException e) {
            throw e;
        } catch (final STenantNotFoundException e) {
            log(platformAccessor, e, TechnicalLogSeverity.ERROR);
            throw new TenantNotFoundException(tenantId);
        } catch (final SDeletingActivatedTenantException e) {
            log(platformAccessor, e, TechnicalLogSeverity.ERROR);
            throw new TenantDeletionException("Unable to delete an activated tenant " + tenantId);
        } catch (final Exception e) {
            log(platformAccessor, e, TechnicalLogSeverity.ERROR);
            throw new TenantDeletionException(tenantId);
        }
    }

    @Override
    public void activateTenant(final long tenantId) throws InvalidSessionException, TenantNotFoundException, TenantActivationException,
            PlatformNotStartedException {
        PlatformServiceAccessor platformAccessor = null;
        try {
            platformAccessor = ServiceAccessorFactory.getInstance().createPlatformServiceAccessor();
            if (!isPlatformStarted(platformAccessor)) {
                throw new PlatformNotStartedException();
            }
            final Tenant alreadyActivateTenant = getTenantById(tenantId);
            if ("ACTIVATED".equals(alreadyActivateTenant.getState())) {
                throw new TenantActivationException("Tenant already activated.");
            }
            final PlatformService platformService = platformAccessor.getPlatformService();
            final TransactionExecutor transactionExecutor = platformAccessor.getTransactionExecutor();
            final SchedulerService schedulerService = platformAccessor.getSchedulerService();
            final SessionService sessionService = platformAccessor.getSessionService();
            final WorkService workService = platformAccessor.getWorkService();
            final NodeConfiguration plaformConfiguration = platformAccessor.getPlaformConfiguration();
            final long sessionId = createSession(tenantId, sessionService, transactionExecutor);
            final TransactionContent transactionContent = new ActivateTenant(tenantId, platformService, schedulerService, plaformConfiguration,
                    platformAccessor.getTechnicalLoggerService(), workService);
            transactionExecutor.execute(transactionContent);
            sessionService.deleteSession(sessionId);
        } catch (final PlatformNotStartedException e) {
            throw e;
        } catch (final TenantNotFoundException e) {
            log(platformAccessor, e, TechnicalLogSeverity.ERROR);
            throw new TenantNotFoundException(tenantId);
        } catch (final Exception e) {
            log(platformAccessor, e, TechnicalLogSeverity.ERROR);
            throw new TenantActivationException("Tenant activation: failed.", e);
        }
    }

    private Long createSession(final long tenantId, final SessionService sessionService, final TransactionExecutor transactionExecutor) throws SBonitaException {
        final TransactionContentWithResult<Long> transaction = new TransactionContentWithResult<Long>() {

            private long sessionId;

            @Override
            public void execute() throws SBonitaException {
                final SSession session = sessionService.createSession(tenantId, "system"); // FIXME use technical user
                sessionId = session.getId();
            }

            @Override
            public Long getResult() {
                return sessionId;
            }
        };

        transactionExecutor.execute(transaction);
        return transaction.getResult();
    }

    private void log(final PlatformServiceAccessor platformAccessor, final Exception e, final TechnicalLogSeverity logSeverity) {
        if (platformAccessor != null) {
            platformAccessor.getTechnicalLoggerService().log(this.getClass(), logSeverity, e);
        } else {
            e.printStackTrace();
        }
    }

    @Override
    public void deactiveTenant(final long tenantId) throws InvalidSessionException, TenantNotFoundException, TenantDeactivationException,
            PlatformNotStartedException {
        PlatformServiceAccessor platformAccessor = null;
        try {
            platformAccessor = ServiceAccessorFactory.getInstance().createPlatformServiceAccessor();
            if (!isPlatformStarted(platformAccessor)) {
                throw new PlatformNotStartedException();
            }
            final Tenant alreadyDeactivateTenant = getTenantById(tenantId);
            if (STATUS_DEACTIVATED.equals(alreadyDeactivateTenant.getState())) {
                throw new TenantDeactivationException("Tenant already deactivated.");
            }
            final PlatformService platformService = platformAccessor.getPlatformService();
            final SchedulerService schedulerService = platformAccessor.getSchedulerService();
            final SessionService sessionService = platformAccessor.getSessionService();
            final WorkService workService = platformAccessor.getWorkService();
            final TransactionExecutor transactionExecutor = platformAccessor.getTransactionExecutor();
            final long sessionId = createSession(tenantId, sessionService, transactionExecutor);
            final TransactionContent transactionContent = new DeactivateTenant(tenantId, platformService, schedulerService, workService);
            transactionExecutor.execute(transactionContent);
            sessionService.deleteSession(sessionId);
        } catch (final PlatformNotStartedException e) {
            throw e;
        } catch (final TenantNotFoundException e) {
            log(platformAccessor, e, TechnicalLogSeverity.ERROR);
            throw new TenantNotFoundException(tenantId);
        } catch (final Exception e) {
            log(platformAccessor, e, TechnicalLogSeverity.ERROR);
            throw new TenantDeactivationException("Tenant deactivation failed.", e);
        }
    }

    @Override
    public List<Tenant> getTenants(final int pageIndex, final int numberPerPage) throws InvalidSessionException, PlatformNotStartedException {
        PlatformServiceAccessor platformAccessor;
        try {
            platformAccessor = ServiceAccessorFactory.getInstance().createPlatformServiceAccessor();
        } catch (final Exception e) {
            throw new BonitaRuntimeException(e);
        }
        if (!isPlatformStarted(platformAccessor)) {
            throw new PlatformNotStartedException();
        }
        final PlatformService platformService = platformAccessor.getPlatformService();
        final TransactionExecutor transactionExecutor = platformAccessor.getTransactionExecutor();
        final TransactionContentWithResult<List<STenant>> transactionContent = new GetTenants(platformService);
        try {
            transactionExecutor.execute(transactionContent);
            final List<STenant> sTenants = transactionContent.getResult();
            return SPModelConvertor.toTenants(sTenants);
        } catch (final Exception e) {
            return Collections.emptyList();
        }
    }

    @Override
    public List<Tenant> getTenants(final int pageIndex, final int numberPerPage, final TenantCriterion pagingCriterion) throws InvalidSessionException,
            PageOutOfRangeException, BonitaException {
        final int totalNumber = getNumberOfTenants();
        PageIndexCheckingUtil.checkIfPageIsOutOfRange(totalNumber, pageIndex, numberPerPage);
        PlatformServiceAccessor platformAccessor;
        try {
            platformAccessor = ServiceAccessorFactory.getInstance().createPlatformServiceAccessor();
        } catch (final Exception e) {
            throw new BonitaException(e);
        }
        try {
            final PlatformService platformService = platformAccessor.getPlatformService();
            if (!isPlatformStarted(platformAccessor)) {
                throw new PlatformNotStartedException();
            }
            final TransactionExecutor transactionExecutor = platformAccessor.getTransactionExecutor();
            final STenantBuilder tenantBuilder = platformAccessor.getSTenantBuilder();
            String field = null;
            OrderByType order = null;
            switch (pagingCriterion) {
                case NAME_ASC:
                    field = tenantBuilder.getNameKey();
                    order = OrderByType.ASC;
                    break;
                case DESC_ASC:
                    field = tenantBuilder.getDescriptionKey();
                    order = OrderByType.ASC;
                    break;
                case CREATION_ASC:
                    field = tenantBuilder.getCreatedKey();
                    order = OrderByType.ASC;
                    break;
                case STATE_ASC:
                    field = tenantBuilder.getStatusKey();
                    order = OrderByType.ASC;
                    break;
                case NAME_DESC:
                    field = tenantBuilder.getNameKey();
                    order = OrderByType.DESC;
                    break;
                case DESC_DESC:
                    field = tenantBuilder.getDescriptionKey();
                    order = OrderByType.DESC;
                    break;
                case CREATION_DESC:
                    field = tenantBuilder.getCreatedKey();
                    order = OrderByType.DESC;
                    break;
                case STATE_DESC:
                    field = tenantBuilder.getStatusKey();
                    order = OrderByType.DESC;
                    break;
                case DEFAULT:
                    field = tenantBuilder.getCreatedKey();
                    order = OrderByType.DESC;
                    break;
            }
            final String fieldContent = field;
            final OrderByType orderContent = order;
            final TransactionContentWithResult<List<STenant>> transactionContent = new GetTenantsWithOrder(platformService, pageIndex, orderContent,
                    fieldContent, numberPerPage);
            transactionExecutor.execute(transactionContent);
            final List<STenant> tenants = transactionContent.getResult();
            return SPModelConvertor.toTenants(tenants);
        } catch (final PlatformNotStartedException e) {
            throw e;
        } catch (final Exception e) {
            log(platformAccessor, e, TechnicalLogSeverity.ERROR);
            throw new BonitaException(e.getMessage());
        }
    }

    @Override
    public Tenant getTenantByName(final String tenantName) throws InvalidSessionException, TenantNotFoundException, PlatformNotStartedException {
        PlatformServiceAccessor platformAccessor = null;
        try {
            platformAccessor = ServiceAccessorFactory.getInstance().createPlatformServiceAccessor();
            final PlatformService platformService = platformAccessor.getPlatformService();
            if (!isPlatformStarted(platformAccessor)) {
                throw new PlatformNotStartedException();
            }
            final TransactionExecutor transactionExecutor = platformAccessor.getTransactionExecutor();
            final GetTenantInstance transactionContent = new GetTenantInstance(tenantName, platformService);
            transactionExecutor.execute(transactionContent);
            return SPModelConvertor.toTenant(transactionContent.getResult());
        } catch (final PlatformNotStartedException e) {
            throw e;
        } catch (final STenantNotFoundException e) {
            log(platformAccessor, e, TechnicalLogSeverity.DEBUG);
            throw new TenantNotFoundException("No tenant exists with name: " + tenantName);
        } catch (final SBonitaException e) {
            log(platformAccessor, e, TechnicalLogSeverity.DEBUG);
            throw new TenantNotFoundException("Unable to retrieve the tenant with name " + tenantName);
        } catch (final Exception e) {
            log(platformAccessor, e, TechnicalLogSeverity.DEBUG);
            throw new TenantNotFoundException("Unable to retrieve the tenant with name " + tenantName);
        }
    }

    @Override
    public Tenant getDefaultTenant() throws InvalidSessionException, PlatformNotStartedException, TenantNotFoundException {
        PlatformServiceAccessor platformAccessor = null;
        try {
            platformAccessor = ServiceAccessorFactory.getInstance().createPlatformServiceAccessor();
            final PlatformService platformService = platformAccessor.getPlatformService();
            if (!isPlatformStarted(platformAccessor)) {
                throw new PlatformNotStartedException();
            }
            final TransactionExecutor transactionExecutor = platformAccessor.getTransactionExecutor();
            final GetDefaultTenantInstance transactionContent = new GetDefaultTenantInstance(platformService);
            transactionExecutor.execute(transactionContent);
            return SPModelConvertor.toTenant(transactionContent.getResult());
        } catch (final PlatformNotStartedException e) {
            throw e;
        } catch (final SBonitaException e) {
            log(platformAccessor, e, TechnicalLogSeverity.ERROR);
            throw new TenantNotFoundException("Unable to retrieve the defaultTenant");
        } catch (final Exception e) {
            log(platformAccessor, e, TechnicalLogSeverity.ERROR);
            throw new TenantNotFoundException("Unable to retrieve the defaultTenant");
        }
    }

    @Override
    public Tenant getTenantById(final long tenantId) throws InvalidSessionException, PlatformNotStartedException, TenantNotFoundException {
        PlatformServiceAccessor platformAccessor = null;
        try {
            platformAccessor = ServiceAccessorFactory.getInstance().createPlatformServiceAccessor();
            final PlatformService platformService = platformAccessor.getPlatformService();
            if (!isPlatformStarted(platformAccessor)) {
                throw new PlatformNotStartedException();
            }
            final TransactionExecutor transactionExecutor = platformAccessor.getTransactionExecutor();
            final GetTenantInstance transactionContent = new GetTenantInstance(tenantId, platformService);
            transactionExecutor.execute(transactionContent);
            return SPModelConvertor.toTenant(transactionContent.getResult());
        } catch (final PlatformNotStartedException e) {
            throw e;
        } catch (final STenantNotFoundException e) {
            log(platformAccessor, e, TechnicalLogSeverity.DEBUG);
            throw new TenantNotFoundException("No tenant exists with id: " + tenantId);
        } catch (final SBonitaException e) {
            log(platformAccessor, e, TechnicalLogSeverity.DEBUG);
            throw new TenantNotFoundException("Unable to retrieve the tenant with id " + tenantId);
        } catch (final Exception e) {
            log(platformAccessor, e, TechnicalLogSeverity.DEBUG);
            throw new TenantNotFoundException("Unable to retrieve the tenant with id " + tenantId);
        }
    }

    @Override
    public int getNumberOfTenants() throws InvalidSessionException, PlatformNotStartedException {
        PlatformServiceAccessor platformAccessor;
        try {
            platformAccessor = ServiceAccessorFactory.getInstance().createPlatformServiceAccessor();
            if (!isPlatformStarted(platformAccessor)) {
                throw new PlatformNotStartedException();
            }
        } catch (final PlatformNotStartedException e) {
            throw e;
        } catch (final Exception e) {
            throw new BonitaRuntimeException(e);
        }
        try {
            final PlatformService platformService = platformAccessor.getPlatformService();
            final TransactionExecutor transactionExecutor = platformAccessor.getTransactionExecutor();
            final TransactionContentWithResult<Integer> transactionContent = new GetNumberOfTenants(platformService);
            transactionExecutor.execute(transactionContent);
            return transactionContent.getResult();
        } catch (final Exception e) {
            return 0;
        }
    }

    @Override
    public Tenant updateTenant(final long tenantId, final TenantUpdateDescriptor udpateDescriptor) throws InvalidSessionException, PlatformNotStartedException,
            TenantUpdateException {
        if (udpateDescriptor == null || udpateDescriptor.getFields().isEmpty()) {
            throw new TenantUpdateException("The update descriptor does not contain field updates");
        }
        PlatformServiceAccessor platformAccessor;
        try {
            platformAccessor = ServiceAccessorFactory.getInstance().createPlatformServiceAccessor();
        } catch (final Exception e) {
            throw new BonitaRuntimeException(e);
        }
        if (!isPlatformStarted(platformAccessor)) {
            throw new PlatformNotStartedException();
        }
        final PlatformService platformService = platformAccessor.getPlatformService();
        final TransactionExecutor transactionExecutor = platformAccessor.getTransactionExecutor();
        // check existence for tenant
        Tenant tenant;
        try {
            tenant = getTenantById(tenantId);
            // update user name and password in file system
            final Map<TenantField, Serializable> updatedFields = udpateDescriptor.getFields();
            final String username = (String) updatedFields.get(TenantField.USERNAME);
            final String password = (String) updatedFields.get(TenantField.PASSWOWRD);
            if (username != null || password != null) {
                modifyTechnicalUser(tenantId, username, password);
            }
            // update tenant in database
            final EntityUpdateDescriptor changeDescriptor = getTenantUpdateDescriptor(udpateDescriptor);
            final UpdateTenant updateTenant = new UpdateTenant(tenant.getId(), changeDescriptor, platformService);
            transactionExecutor.execute(updateTenant);
            return getTenantById(tenantId);
        } catch (final TenantNotFoundException e) {
            throw new TenantUpdateException(e);
        } catch (final BonitaHomeNotSetException e) {
            throw new TenantUpdateException(e);
        } catch (final BonitaHomeConfigurationException e) {
            throw new TenantUpdateException(e);
        } catch (final InstantiationException e) {
            throw new TenantUpdateException(e);
        } catch (final IllegalAccessException e) {
            throw new TenantUpdateException(e);
        } catch (final ClassNotFoundException e) {
            throw new TenantUpdateException(e);
        } catch (final IOException e) {
            throw new TenantUpdateException(e);
        } catch (final SBonitaException e) {
            throw new TenantUpdateException(e);
        } catch (final Exception e) {
            throw new TenantUpdateException(e);
        }
    }

    private EntityUpdateDescriptor getTenantUpdateDescriptor(final TenantUpdateDescriptor udpateDescriptor) throws BonitaHomeNotSetException,
            BonitaHomeConfigurationException, InstantiationException, IllegalAccessException, ClassNotFoundException, IOException {
        final PlatformServiceAccessor platformAccessor = ServiceAccessorFactory.getInstance().createPlatformServiceAccessor();
        final STenantBuilder tenantBuilder = platformAccessor.getSTenantBuilder();

        final EntityUpdateDescriptor descriptor = new EntityUpdateDescriptor();
        final Map<TenantField, Serializable> fields = udpateDescriptor.getFields();
        for (final Entry<TenantField, Serializable> field : fields.entrySet()) {
            switch (field.getKey()) {
                case NAME:
                    descriptor.addField(tenantBuilder.getNameKey(), field.getValue());
                    break;
                case DESCRIPTION:
                    descriptor.addField(tenantBuilder.getDescriptionKey(), field.getValue());
                    break;
                case ICON_NAME:
                    descriptor.addField(tenantBuilder.getIconNameKey(), field.getValue());
                    break;
                case ICON_PATH:
                    descriptor.addField(tenantBuilder.getIconPathKey(), field.getValue());
                    break;
                case STATUS:
                    descriptor.addField(tenantBuilder.getStatusKey(), field.getValue());
                    break;
                default:
                    break;
            }
        }
        return descriptor;
    }

    @Override
    public SearchResult<Tenant> searchTenants(final SearchOptions searchOptions) throws InvalidSessionException, SearchException {
        PlatformServiceAccessor platformAccessor;
        try {
            platformAccessor = ServiceAccessorFactory.getInstance().createPlatformServiceAccessor();
        } catch (final Exception e) {
            throw new BonitaRuntimeException(e);
        }
        final TransactionExecutor transactionExecutor = platformAccessor.getTransactionExecutor();
        final PlatformService platformService = platformAccessor.getPlatformService();
        final SearchPlatformEntitiesDescriptor searchPlatformEntitiesDescriptor = platformAccessor.getSearchPlatformEntitiesDescriptor();
        final SearchTenants searchTenants = new SearchTenants(platformService, searchPlatformEntitiesDescriptor.getSearchTenantDescriptor(), searchOptions);
        try {
            transactionExecutor.execute(searchTenants);
            return searchTenants.getResult();
        } catch (final SBonitaException sbe) {
            throw new BonitaRuntimeException(sbe);
        }
    }

}
