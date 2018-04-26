/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2010, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.msc.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jboss.msc.inject.Injector;
import org.jboss.msc.inject.Injectors;
import org.jboss.msc.value.ImmediateValue;
import org.jboss.msc.value.Value;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Obsolete {@link ServiceBuilder} implementation.
 *
 * @param <T> the type of service being built
 *
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
final class ObsoleteServiceBuilderImpl<T> extends AbstractServiceBuilder<T> {

    private final org.jboss.msc.Service service;
    private final Map<ServiceName, WritableValueImpl> provides = new LinkedHashMap<>();
    private final Set<ServiceName> aliases = new HashSet<>();
    private final Set<StabilityMonitor> monitors = new IdentityHashSet<>();
    private final Map<ServiceName, Dependency> dependencies = new HashMap<>(0);
    private final Set<ServiceListener<? super T>> listeners = new IdentityHashSet<>(0);
    private final Set<LifecycleListener> lifecycleListeners = new IdentityHashSet<>(0);
    private final List<ValueInjection<?>> valueInjections = new ArrayList<>(0);
    private final List<Injector<? super T>> outInjections = new ArrayList<>(0);
    private ServiceController.Mode initialMode = ServiceController.Mode.ACTIVE;
    private boolean installed = false;

    ObsoleteServiceBuilderImpl(ServiceName serviceId, ServiceTargetImpl serviceTarget, final Service<T> service, final ServiceControllerImpl<?> parent) {
        super(serviceId, serviceTarget, parent);
        if (service == null) throw new IllegalArgumentException("Service can not be null");
        this.service = service;
        this.provides.put(serviceId, null);
    }

    @Override
    public ServiceBuilder<T> addAliases(ServiceName... aliases) {
        if (aliases != null) for (ServiceName alias : aliases) {
            if (alias != null && !provides.keySet().contains(alias)) {
                this.provides.put(alias, null);
                this.aliases.add(alias);
            }
        }
        return this;
    }

    @Override
    public ServiceBuilder<T> setInitialMode(final ServiceController.Mode mode) {
        checkAlreadyInstalled();
        if (mode == null) {
            throw new IllegalArgumentException("Initial mode is null");
        }
        if (mode == ServiceController.Mode.REMOVE) {
            throw new IllegalArgumentException("Initial mode cannot be set to REMOVE");
        }
        initialMode = mode;
        return this;
    }

    @Override
    public ServiceBuilder<T> addDependencies(final ServiceName... newDependencies) {
        return addDependencies(DependencyType.REQUIRED, newDependencies);
    }

    @Override
    public ServiceBuilder<T> addDependencies(final DependencyType dependencyType, final ServiceName... newDependencies) {
        checkAlreadyInstalled();
        if (newDependencies != null) for (ServiceName dependency : newDependencies) {
            if (dependency == null) {
                throw new IllegalArgumentException("dependency is null");
            }
            if(!provides.keySet().contains(dependency)) {
                doAddDependency(dependency, dependencyType);
            }
        }
        return this;
    }

    @Override
    public ServiceBuilder<T> addDependencies(final Iterable<ServiceName> newDependencies) {
        return addDependencies(DependencyType.REQUIRED, newDependencies);
    }

    void addDependenciesNoCheck(final Iterable<ServiceName> newDependencies) {
        addDependenciesNoCheck(newDependencies, DependencyType.REQUIRED);
    }

    @Override
    public ServiceBuilder<T> addDependencies(final DependencyType dependencyType, final Iterable<ServiceName> newDependencies) {
        checkAlreadyInstalled();
        addDependenciesNoCheck(newDependencies, dependencyType);
        return this;
    }

    void addDependenciesNoCheck(final Iterable<ServiceName> newDependencies, final DependencyType dependencyType) {
        if (newDependencies != null) for (ServiceName dependency : newDependencies) {
            if (dependency != null && !provides.keySet().contains(dependency)) {
                doAddDependency(dependency, dependencyType);
            }
        }
    }

    @Override
    public ServiceBuilder<T> addDependency(final ServiceName dependency) {
        return addDependency(DependencyType.REQUIRED, dependency);
    }

    @Override
    public ServiceBuilder<T> addDependency(final DependencyType dependencyType, final ServiceName dependency) {
        checkAlreadyInstalled();
        if(!provides.keySet().contains(dependency)) {
            doAddDependency(dependency, dependencyType);
        }
        return this;
    }

    @Override
    public ServiceBuilder<T> addDependency(final ServiceName dependency, final Injector<Object> target) {
        return addDependency(DependencyType.REQUIRED, dependency, target);
    }

    @Override
    public ServiceBuilder<T> addDependency(DependencyType dependencyType, final ServiceName dependency, final Injector<Object> target) {
        checkAlreadyInstalled();
        doAddDependency(dependency, dependencyType).getInjectorList().add(target);
        return this;
    }

    @Override
    public <I> ServiceBuilder<T> addDependency(final ServiceName dependency, final Class<I> type, final Injector<I> target) {
        return addDependency(DependencyType.REQUIRED, dependency, type, target);
    }

    @Override
    public <I> ServiceBuilder<T> addDependency(final DependencyType dependencyType, final ServiceName dependency, final Class<I> type, final Injector<I> target) {
        checkAlreadyInstalled();
        doAddDependency(dependency, dependencyType).getInjectorList().add(Injectors.cast(target, type));
        return this;
    }

    private Dependency doAddDependency(final ServiceName name, final DependencyType type) {
        if (name == null) {
            throw new IllegalArgumentException("name is null");
        }
        if (type == null) {
            throw new IllegalArgumentException("type is null");
        }
        final Dependency existing = dependencies.get(name);
        if (existing != null) {
            if (type == DependencyType.REQUIRED) existing.setDependencyType(DependencyType.REQUIRED);
            return existing;
        }
        final Dependency newDep = new Dependency(name, type);
        if (dependencies.size() == ServiceControllerImpl.MAX_DEPENDENCIES) {
            throw new IllegalArgumentException("Too many dependencies specified (max is " + ServiceControllerImpl.MAX_DEPENDENCIES + ")");
        }
        dependencies.put(name, newDep);
        return newDep;
    }

    @Override
    public <I> ServiceBuilder<T> addInjection(final Injector<? super I> target, final I value) {
        return addInjectionValue(target, new ImmediateValue<>(value));
    }

    @Override
    public <I> ServiceBuilder<T> addInjectionValue(final Injector<? super I> target, final Value<I> value) {
        checkAlreadyInstalled();
        valueInjections.add(new ValueInjection<>(value, target));
        return this;
    }

    @Override
    public ServiceBuilder<T> addInjection(final Injector<? super T> target) {
        checkAlreadyInstalled();
        outInjections.add(target);
        return this;
    }

    @Override
    public ServiceBuilder<T> addMonitor(final StabilityMonitor monitor) {
        checkAlreadyInstalled();
        if (monitor != null) {
            monitors.add(monitor);
        }
        return this;
    }

    @Override
    public ServiceBuilder<T> addMonitors(final StabilityMonitor... monitors) {
        checkAlreadyInstalled();
        if (monitors != null) {
            for (final StabilityMonitor monitor : monitors) {
                if (monitor != null) {
                    this.monitors.add(monitor);
                }
            }
        }
        return this;
    }

    @Override
    public ServiceBuilder<T> addListener(final LifecycleListener listener) {
        checkAlreadyInstalled();
        lifecycleListeners.add(listener);
        return this;
    }

    @Override
    public ServiceBuilder<T> addListener(final ServiceListener<? super T> listener) {
        checkAlreadyInstalled();
        listeners.add(listener);
        return this;
    }

    @Override
    public ServiceBuilder<T> addListener(final ServiceListener<? super T>... serviceListeners) {
        checkAlreadyInstalled();
        for (ServiceListener<? super T> listener : serviceListeners) {
            listeners.add(listener);
        }
        return this;
    }

    @Override
    public ServiceBuilder<T> addListener(final Collection<? extends ServiceListener<? super T>> serviceListeners) {
        checkAlreadyInstalled();
        listeners.addAll(serviceListeners);
        return this;
    }

    void addServiceListenersNoCheck(final Set<? extends ServiceListener<? super T>> listeners) {
        this.listeners.addAll(listeners);
    }

    void addLifecycleListenersNoCheck(final Set<LifecycleListener> listeners) {
        this.lifecycleListeners.addAll(listeners);
    }

    void addMonitorsNoCheck(final Collection<? extends StabilityMonitor> monitors) {
        this.monitors.addAll(monitors);
    }

    private void checkAlreadyInstalled() {
        if (installed) {
            throw new IllegalStateException("ServiceBuilder already installed");
        }
    }

    @Override
    public ServiceController<T> install() throws ServiceRegistryException {
        if (installed) {
            throw new IllegalStateException("ServiceBuilder is already installed");
        }
        // mark it before perform the installation,
        // so we avoid ServiceRegistryException being thrown multiple times
        installed = true;
        return getServiceTarget().install(this);
    }

    org.jboss.msc.Service getService() {
        return service;
    }

    Collection<ServiceName> getServiceAliases() {
        return aliases;
    }

    Map<ServiceName, WritableValueImpl> getProvides() {
        return provides;
    }

    Map<ServiceName, Dependency> getDependencies() {
        return dependencies;
    }

    Set<StabilityMonitor> getMonitors() {
        ServiceControllerImpl parent = getParent();
        while (parent != null) {
            synchronized (parent) {
                addMonitorsNoCheck(parent.getMonitors());
                parent = parent.getParent();
            }
        }
        return monitors;
    }

    Set<ServiceListener<? super T>> getServiceListeners() {
        return listeners;
    }

    Set<LifecycleListener> getLifecycleListeners() {
        return lifecycleListeners;
    }

    List<ValueInjection<?>> getValueInjections() {
        return valueInjections;
    }

    ServiceController.Mode getInitialMode() {
        return initialMode;
    }

    List<Injector<? super T>> getOutInjections() {
        return outInjections;
    }

    @Override
    public <V> Supplier<V> requires(final ServiceName name) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <V> Consumer<V> provides(final ServiceName... names) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ServiceBuilder<T> setInstance(final org.jboss.msc.Service service) {
        throw new UnsupportedOperationException();
    }

}
