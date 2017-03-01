/**********************************************************************
Copyright (c) 2014 Andy Jefferson and others. All rights reserved.
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

Contributors:
    ...
**********************************************************************/
package org.datanucleus;

import java.lang.reflect.InvocationTargetException;
import java.util.Collection;
import java.util.Map;
import java.util.Random;

import org.datanucleus.exceptions.NucleusException;
import org.datanucleus.exceptions.NucleusUserException;
import org.datanucleus.exceptions.TransactionIsolationNotSupportedException;
import org.datanucleus.plugin.ConfigurationElement;
import org.datanucleus.plugin.Extension;
import org.datanucleus.store.StoreManager;
import org.datanucleus.util.Localiser;

/**
 * Helper methods for NucleusContext operations.
 */
public class NucleusContextHelper
{
    /** Random number generator, for use when needing unique names. */
    public static final Random random = new Random();

    /**
     * Method to return the transaction isolation level that will be used for the provided StoreManager
     * bearing in mind the specified level the user requested.
     * @param storeMgr The Store Manager
     * @param transactionIsolation Requested isolation level
     * @return Isolation level to use
     * @throws TransactionIsolationNotSupportedException When no suitable level available given the requested level
     */
    public static String getTransactionIsolationForStoreManager(StoreManager storeMgr, String transactionIsolation)
    {
        if (transactionIsolation != null)
        {
            // Transaction isolation has been specified and we need to provide at least this level
            // Order of priority is :-
            // read-uncommitted (lowest), read-committed, repeatable-read, serializable (highest)
            Collection<String> supportedOptions = storeMgr.getSupportedOptions();
            if (!supportedOptions.contains("TransactionIsolationLevel." + transactionIsolation))
            {
                // Requested transaction isolation isn't supported by datastore so check for higher
                if (transactionIsolation.equals("read-uncommitted"))
                {
                    if (supportedOptions.contains(StoreManager.OPTION_TXN_ISOLATION_READ_COMMITTED))
                    {
                        return "read-committed";
                    }
                    else if (supportedOptions.contains(StoreManager.OPTION_TXN_ISOLATION_REPEATABLE_READ))
                    {
                        return "repeatable-read";
                    }
                    else if (supportedOptions.contains(StoreManager.OPTION_TXN_ISOLATION_SERIALIZABLE))
                    {
                        return "serializable";
                    }
                }
                else if (transactionIsolation.equals("read-committed"))
                {
                    if (supportedOptions.contains(StoreManager.OPTION_TXN_ISOLATION_REPEATABLE_READ))
                    {
                        return "repeatable-read";
                    }
                    else if (supportedOptions.contains(StoreManager.OPTION_TXN_ISOLATION_SERIALIZABLE))
                    {
                        return "serializable";
                    }
                }
                else if (transactionIsolation.equals("repeatable-read"))
                {
                    if (supportedOptions.contains(StoreManager.OPTION_TXN_ISOLATION_SERIALIZABLE))
                    {
                        return "serializable";
                    }
                }
                else
                {
                    throw new TransactionIsolationNotSupportedException(transactionIsolation);
                }
            }
        }
        return transactionIsolation;
    }

    /**
     * Method to create a StoreManager based on the specified properties passed in.
     * @param props The overall persistence properties
     * @param datastoreProps Persistence properties to apply to the datastore
     * @param clr ClassLoader resolver
     * @param nucCtx NucleusContext
     * @return The StoreManager
     * @throws NucleusUserException if impossible to create the StoreManager (not in CLASSPATH?, invalid definition?)
     */
    public static StoreManager createStoreManagerForProperties(Map<String, Object> props, Map<String, Object> datastoreProps, ClassLoaderResolver clr, NucleusContext nucCtx)
    {
        Extension[] exts = nucCtx.getPluginManager().getExtensionPoint("org.datanucleus.store_manager").getExtensions();
        Class[] ctrArgTypes = new Class[] {ClassConstants.CLASS_LOADER_RESOLVER, ClassConstants.PERSISTENCE_NUCLEUS_CONTEXT, Map.class};
        Object[] ctrArgs = new Object[] {clr, nucCtx, datastoreProps};

        StoreManager storeMgr = null;

        // Try using the URL of the data source
        String url = (String) props.get(PropertyNames.PROPERTY_CONNECTION_URL.toLowerCase());
        if (url != null)
        {
            int idx = url.indexOf(':');
            if (idx > -1)
            {
                url = url.substring(0, idx);
            }

            for (int e=0; storeMgr == null && e<exts.length; e++)
            {
                ConfigurationElement[] confElm = exts[e].getConfigurationElements();
                for (int c=0; storeMgr == null && c<confElm.length; c++)
                {
                    String urlKey = confElm[c].getAttribute("url-key");
                    if (url == null || urlKey.equalsIgnoreCase(url))
                    {
                        // Either no URL, or url defined so take this StoreManager
                        try
                        {
                            storeMgr = (StoreManager)nucCtx.getPluginManager().createExecutableExtension("org.datanucleus.store_manager", "url-key", 
                                url == null ? urlKey : url, "class-name", ctrArgTypes, ctrArgs);
                        }
                        catch (InvocationTargetException ex)
                        {
                            Throwable t = ex.getTargetException();
                            if (t instanceof RuntimeException)
                            {
                                throw (RuntimeException) t;
                            }
                            else if (t instanceof Error)
                            {
                                throw (Error) t;
                            }
                            else
                            {
                                throw new NucleusException(t.getMessage(), t).setFatal();
                            }
                        }
                        catch (Exception ex)
                        {
                            throw new NucleusException(ex.getMessage(), ex).setFatal();
                        }
                    }
                }
            }
        }
        else
        {
            // Assumed to be using RDBMS since only that allows ConnectionFactory/ConnectionFactoryName TODO If any other stores start supporting ConnectionFactory then update this
            try
            {
                storeMgr = (StoreManager)nucCtx.getPluginManager().createExecutableExtension("org.datanucleus.store_manager", "key", "rdbms", "class-name", ctrArgTypes, ctrArgs);
            }
            catch (InvocationTargetException ex)
            {
                Throwable t = ex.getTargetException();
                if (t instanceof RuntimeException)
                {
                    throw (RuntimeException) t;
                }
                else if (t instanceof Error)
                {
                    throw (Error) t;
                }
                else
                {
                    throw new NucleusException(t.getMessage(), t).setFatal();
                }
            }
            catch (Exception ex)
            {
                throw new NucleusException(ex.getMessage(), ex).setFatal();
            }
        }

        if (storeMgr == null)
        {
            throw new NucleusUserException(Localiser.msg("008004", url)).setFatal();
        }

        return storeMgr;
    }
}
