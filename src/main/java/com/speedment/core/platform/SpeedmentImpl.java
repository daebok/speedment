/**
 *
 * Copyright (c) 2006-2015, Speedment, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); You may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.speedment.core.platform;

import com.speedment.api.Speedment;
import com.speedment.core.platform.component.Component;
import com.speedment.api.annotation.Api;
import com.speedment.api.exception.SpeedmentException;
import com.speedment.api.Manager;
import com.speedment.core.platform.component.ManagerComponent;
import com.speedment.core.platform.component.impl.ConnectionPoolComponentImpl;
import com.speedment.core.platform.component.impl.DbmsHandlerComponentImpl;
import com.speedment.core.platform.component.impl.DefaultClassMapper;
import com.speedment.core.platform.component.impl.EntityManagerImpl;
import com.speedment.core.platform.component.impl.JavaTypeMapperComponentImpl;
import com.speedment.core.platform.component.impl.LoggerFactoryComponentImpl;
import com.speedment.core.platform.component.impl.ManagerComponentImpl;
import com.speedment.core.platform.component.impl.PrimaryKeyFactoryComponentImpl;
import com.speedment.core.platform.component.impl.ProjectComponentImpl;
import com.speedment.core.platform.component.impl.SqlTypeMapperComponentImpl;

/**
 * <img src="{@docRoot}/doc-files/hare.png" alt="Hare">
 *
 * The {@code Platform} class acts as a generic holder of different system
 * {@link Component Components}. Using its pluggable architecture, one can
 * replace existing default implementations of existing Components or plug in
 * custom made implementation of any Interface.
 * <p>
 * Pluggable instances must implement the {@link Component} interface.
 *
 * @author pemi
 */
public final class SpeedmentImpl extends DefaultClassMapper<Component> implements Speedment {

    public SpeedmentImpl() {
        add(new ManagerComponentImpl());
        add(new ProjectComponentImpl());
        add(new PrimaryKeyFactoryComponentImpl());
        add(new DbmsHandlerComponentImpl(this));
        add(new SqlTypeMapperComponentImpl());
        add(new JavaTypeMapperComponentImpl());
        add(new EntityManagerImpl(this));
        add(new LoggerFactoryComponentImpl());
        add(new ConnectionPoolComponentImpl());
    }

    /**
     * Gets a {@link SpeedmentImpl} {@link Component} based on its interface
     * class.
     * <p>
     * The supported standard interfaces are:
     * <ul>
     * <li>{@link com.speedment.core.platform.component.EntityManager}</li>
     * <li>{@link com.speedment.core.platform.component.DbmsHandlerComponent}</li>
     * <li>{@link com.speedment.core.platform.component.ManagerComponent}</li>
     * <li>{@link com.speedment.core.platform.component.PrimaryKeyFactoryComponent}</li>
     * <li>{@link com.speedment.core.platform.component.ProjectComponent}</li>
     * <li>{@link com.speedment.core.platform.component.SqlTypeMapperComponent}</li>
     * <li>{@link com.speedment.core.platform.component.LoggerFactoryComponent}</li>
     * <li>{@link com.speedment.core.platform.component.JavaTypeMapperComponent}</li>
     * </ul>
     *
     * @param <R> The intended return type
     * @param iface The interface class of the intended return type
     * @return The currently mapped instance
     */
    @Api(version = "2.0")
    @Override
    public <R extends Component> R get(Class<R> iface) {
        return super.get(iface);
    }

    @Api(version = "2.0")
    @Override
    public Component add(Component item) {
        return add(item, Component::onAdd, Component::onRemove, Component::getComponentClass);
    }

    /**
     * Obtains and returns the currently associated {@link Manager}
     * implementation for the given Entity interface Class. If no Manager exists
     * for the given entityClass, a SpeedmentException will be thrown.
     * <p>
     * N.B.This conveniency method is a pure delegator to the ManagerComponent
     * and is exactly equivalent to the code:
     * <p>
     * {@code get(ManagerComponent.class).managerOf(entityClass) }
     *
     * @param <ENTITY> the Entity interface type
     * @param entityClass the Entity interface {@code Class}
     * @return the currently associated {@link Manager} implementation for the
     * given Entity interface Class
     * @throws SpeedmentException if no Manager exists for the given entityClass
     */
    public <ENTITY> Manager<ENTITY> managerOf(Class<ENTITY> entityClass) throws SpeedmentException {
        return get(ManagerComponent.class).managerOf(entityClass);
    }

}