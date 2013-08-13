/**
 * Copyright (C) 2013 BonitaSoft S.A.
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
 **/
package org.bonitasoft.engine.test.synchro;

import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.Map;

import org.bonitasoft.engine.events.model.SEvent;
import org.bonitasoft.engine.events.model.SHandler;
import org.bonitasoft.engine.events.model.SHandlerExecutionException;
import org.bonitasoft.engine.exception.BonitaHomeConfigurationException;
import org.bonitasoft.engine.exception.BonitaHomeNotSetException;
import org.bonitasoft.engine.service.impl.ServiceAccessorFactory;
import org.bonitasoft.engine.transaction.BonitaTransactionSynchronization;
import org.bonitasoft.engine.transaction.STransactionNotFoundException;
import org.bonitasoft.engine.transaction.TransactionService;

/**
 * @author Baptiste Mesta
 */
public abstract class AbstractUpdateHandler implements SHandler<SEvent> {

    private static final long serialVersionUID = 1L;
    private final long tenantId;

    public AbstractUpdateHandler(final long tenantId) {
        super();
        this.tenantId = tenantId;
    }

    protected abstract Map<String, Serializable> getEvent(final SEvent sEvent);

    @Override
    public void execute(final SEvent sEvent) throws SHandlerExecutionException {
        try {
            final Map<String, Serializable> event = getEvent(sEvent);
            Long id = getObjectId(sEvent);

            final BonitaTransactionSynchronization synchronization = new WaitForEventSynchronization(event, id);

            TransactionService transactionService = getTransactionService();
            transactionService.registerBonitaSynchronization(synchronization);
        } catch (final STransactionNotFoundException e) {
            e.printStackTrace();
            throw new SHandlerExecutionException(e);
        }
    }

    /**
     * @param sEvent
     * @return
     */
    private Long getObjectId(final SEvent sEvent) {
        Long id = null;
        Object object = null;
        try {
            object = sEvent.getObject();
            final Method method = object.getClass().getMethod("getId");
            final Object invoke = method.invoke(object);
            id = (Long) invoke;
        } catch (final Throwable e) {
            System.err.println("AbstractUpdateHandler: No id on object " + object);
        }
        return id;
    }

    /**
     * @return
     * @throws BonitaHomeNotSetException
     * @throws InstantiationException
     * @throws IllegalAccessException
     * @throws ClassNotFoundException
     * @throws IOException
     * @throws BonitaHomeConfigurationException
     */
    private TransactionService getTransactionService() throws SHandlerExecutionException {
        TransactionService transactionService;
        try {
            transactionService = ServiceAccessorFactory.getInstance().createPlatformServiceAccessor().getTenantServiceAccessor(tenantId).getTransactionService();
        } catch (Exception e) {
            throw new SHandlerExecutionException(e.getMessage(), null);
        }
        return transactionService;
    }

}
