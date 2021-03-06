/*
 * JBoss, Home of Professional Open Source
 * Copyright 2010, Red Hat Inc., and individual contributors as indicated
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
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
package org.jboss.as.weld.deployment.processors;

import java.util.HashSet;
import java.util.Set;

import javax.enterprise.inject.spi.InterceptionType;

import org.jboss.as.ee.component.ComponentConfiguration;
import org.jboss.as.ee.component.ComponentConfigurator;
import org.jboss.as.ee.component.ComponentDescription;
import org.jboss.as.ee.component.ComponentStartService;
import org.jboss.as.ee.component.DependencyConfigurator;
import org.jboss.as.ee.component.EEModuleDescription;
import org.jboss.as.ee.component.InterceptorDescription;
import org.jboss.as.ee.component.interceptors.InterceptorOrder;
import org.jboss.as.ee.component.interceptors.UserInterceptorFactory;
import org.jboss.as.ejb3.component.EJBComponentDescription;
import org.jboss.as.ejb3.component.stateful.SerializedCdiInterceptorsKey;
import org.jboss.as.ejb3.component.stateful.StatefulComponentDescription;
import org.jboss.as.server.deployment.Attachments;
import org.jboss.as.server.deployment.DeploymentPhaseContext;
import org.jboss.as.server.deployment.DeploymentUnit;
import org.jboss.as.server.deployment.DeploymentUnitProcessingException;
import org.jboss.as.server.deployment.DeploymentUnitProcessor;
import org.jboss.as.server.deployment.reflect.ClassIndex;
import org.jboss.as.server.deployment.reflect.DeploymentClassIndex;
import org.jboss.as.weld.WeldBootstrapService;
import org.jboss.as.ee.weld.WeldDeploymentMarker;
import org.jboss.as.weld.WeldMessages;
import org.jboss.as.weld.WeldStartService;
import org.jboss.as.weld.ejb.EjbRequestScopeActivationInterceptor;
import org.jboss.as.weld.ejb.Jsr299BindingsInterceptor;
import org.jboss.as.weld.injection.WeldComponentService;
import org.jboss.as.weld.injection.WeldInjectionContextInterceptor;
import org.jboss.as.weld.injection.WeldInjectionInterceptor;
import org.jboss.as.weld.injection.WeldInterceptorInjectionInterceptor;
import org.jboss.as.weld.injection.WeldManagedReferenceFactory;
import org.jboss.invocation.ImmediateInterceptorFactory;
import org.jboss.modules.ModuleClassLoader;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceTarget;

/**
 * Deployment unit processor that add the {@link org.jboss.as.weld.injection.WeldManagedReferenceFactory} instantiator
 * to components that are part of a bean archive.
 *
 * @author Stuart Douglas
 */
public class WeldComponentIntegrationProcessor implements DeploymentUnitProcessor {

    @Override
    public void deploy(DeploymentPhaseContext phaseContext) throws DeploymentUnitProcessingException {
        final DeploymentUnit deploymentUnit = phaseContext.getDeploymentUnit();
        final DeploymentClassIndex classIndex = deploymentUnit.getAttachment(Attachments.CLASS_INDEX);

        if (!WeldDeploymentMarker.isWeldDeployment(deploymentUnit)) {
            return;
        }


        final DeploymentUnit topLevelDeployment = deploymentUnit.getParent() == null ? deploymentUnit : deploymentUnit.getParent();
        final EEModuleDescription eeModuleDescription = deploymentUnit.getAttachment(org.jboss.as.ee.component.Attachments.EE_MODULE_DESCRIPTION);
        final ServiceName weldBootstrapService = topLevelDeployment.getServiceName().append(WeldBootstrapService.SERVICE_NAME);
        final ServiceName weldStartService = topLevelDeployment.getServiceName().append(WeldStartService.SERVICE_NAME);

        for (ComponentDescription component : eeModuleDescription.getComponentDescriptions()) {
            final String beanName;
            if (component instanceof EJBComponentDescription) {
                beanName = component.getComponentName();

            } else {
                beanName = null;
            }
            component.getConfigurators().addFirst(new ComponentConfigurator() {
                @Override
                public void configure(final DeploymentPhaseContext context, final ComponentDescription description, final ComponentConfiguration configuration) throws DeploymentUnitProcessingException {
                    final Class<?> componentClass = configuration.getComponentClass();
                    final DeploymentUnit deploymentUnit = context.getDeploymentUnit();
                    final ModuleClassLoader classLoader = deploymentUnit.getAttachment(Attachments.MODULE).getClassLoader();

                    //get the interceptors so they can be injected as well
                    final Set<Class<?>> interceptorClasses = new HashSet<Class<?>>();
                    for (InterceptorDescription interceptorDescription : description.getAllInterceptors()) {
                        try {
                            final ClassIndex index = classIndex.classIndex(interceptorDescription.getInterceptorClassName());
                            interceptorClasses.add(index.getModuleClass());
                        } catch (ClassNotFoundException e) {
                            throw WeldMessages.MESSAGES.couldNotLoadInterceptorClass(interceptorDescription.getInterceptorClassName(), e);
                        }
                    }

                    addWeldIntegration(context.getServiceTarget(), configuration, description, componentClass, beanName, weldBootstrapService, weldStartService, interceptorClasses, classLoader, description.getBeanDeploymentArchiveId());

                    //add a context key for weld interceptor replication
                    if (description instanceof StatefulComponentDescription) {
                        configuration.getInterceptorContextKeys().add(SerializedCdiInterceptorsKey.class);
                    }
                }
            });
        }

    }

    /**
     * As the weld based instantiator needs access to the bean manager it is installed as a service.
     */
    private void addWeldIntegration(final ServiceTarget target, final ComponentConfiguration configuration, final ComponentDescription description, final Class<?> componentClass, final String beanName, final ServiceName weldServiceName, final ServiceName weldStartService, final Set<Class<?>> interceptorClasses, final ClassLoader classLoader, final String beanDeploymentArchiveId) {

        final ServiceName serviceName = configuration.getComponentDescription().getServiceName().append("WeldInstantiator");

        final WeldComponentService weldComponentService = new WeldComponentService(componentClass, beanName, interceptorClasses, classLoader, beanDeploymentArchiveId, description.isCDIInterceptorEnabled(), description);
                ServiceBuilder<WeldComponentService> builder = target.addService(serviceName, weldComponentService)
                        .addDependency(weldServiceName, WeldBootstrapService.class, weldComponentService.getWeldContainer())
                         .addDependency(weldStartService);

        configuration.setInstanceFactory(WeldManagedReferenceFactory.INSTANCE);
        configuration.getStartDependencies().add(new DependencyConfigurator<ComponentStartService>() {
            @Override
            public void configureDependency(final ServiceBuilder<?> serviceBuilder, ComponentStartService service) throws DeploymentUnitProcessingException {
                serviceBuilder.addDependency(serviceName);
            }
        });

        //if this is an ejb add the EJB interceptors
        if (description instanceof EJBComponentDescription) {

            //add interceptor to activate the request scope if required
            final EjbRequestScopeActivationInterceptor.Factory requestFactory = new EjbRequestScopeActivationInterceptor.Factory(weldServiceName);
            configuration.addComponentInterceptor(requestFactory, InterceptorOrder.Component.CDI_REQUEST_SCOPE, false);

            final Jsr299BindingsInterceptor.Factory aroundInvokeFactory = new Jsr299BindingsInterceptor.Factory(description.getBeanDeploymentArchiveId(), beanName, InterceptionType.AROUND_INVOKE, classLoader);
            final Jsr299BindingsInterceptor.Factory aroundTimeoutFactory = new Jsr299BindingsInterceptor.Factory(description.getBeanDeploymentArchiveId(), beanName, InterceptionType.AROUND_TIMEOUT, classLoader);

            builder.addDependency(weldServiceName, WeldBootstrapService.class, aroundTimeoutFactory.getWeldContainer());
            builder.addDependency(weldServiceName, WeldBootstrapService.class, aroundInvokeFactory.getWeldContainer());

            configuration.addComponentInterceptor(new UserInterceptorFactory(aroundInvokeFactory, aroundTimeoutFactory), InterceptorOrder.Component.CDI_INTERCEPTORS, false);

            final Jsr299BindingsInterceptor.Factory preDestroyInterceptor = new Jsr299BindingsInterceptor.Factory(description.getBeanDeploymentArchiveId(), beanName, InterceptionType.PRE_DESTROY, classLoader);
            builder.addDependency(weldServiceName, WeldBootstrapService.class, preDestroyInterceptor.getWeldContainer());
            configuration.addPreDestroyInterceptor(preDestroyInterceptor, InterceptorOrder.ComponentPreDestroy.CDI_INTERCEPTORS);

            if (description.isPassivationApplicable()) {
                final Jsr299BindingsInterceptor.Factory prePassivateInterceptor = new Jsr299BindingsInterceptor.Factory(description.getBeanDeploymentArchiveId(), beanName, InterceptionType.PRE_PASSIVATE, classLoader);
                builder.addDependency(weldServiceName, WeldBootstrapService.class, prePassivateInterceptor.getWeldContainer());
                configuration.addPrePassivateInterceptor(prePassivateInterceptor, InterceptorOrder.ComponentPassivation.CDI_INTERCEPTORS);
                final Jsr299BindingsInterceptor.Factory postActivateInterceptor = new Jsr299BindingsInterceptor.Factory(description.getBeanDeploymentArchiveId(), beanName, InterceptionType.POST_ACTIVATE, classLoader);
                builder.addDependency(weldServiceName, WeldBootstrapService.class, postActivateInterceptor.getWeldContainer());
                configuration.addPostActivateInterceptor(postActivateInterceptor, InterceptorOrder.ComponentPassivation.CDI_INTERCEPTORS);
            }

            final Jsr299BindingsInterceptor.Factory postConstruct = new Jsr299BindingsInterceptor.Factory(description.getBeanDeploymentArchiveId(), beanName, InterceptionType.POST_CONSTRUCT, classLoader);
            builder.addDependency(weldServiceName, WeldBootstrapService.class, postConstruct.getWeldContainer());
            configuration.addPostConstructInterceptor(postConstruct, InterceptorOrder.ComponentPostConstruct.CDI_INTERCEPTORS);

            /*
             * Add interceptor to activate the request scope for the @PostConstruct callback.
             * See https://issues.jboss.org/browse/CDI-219 for details
             */
            final EjbRequestScopeActivationInterceptor.Factory postConstructRequestContextActivationFactory = new EjbRequestScopeActivationInterceptor.Factory(weldServiceName);
            configuration.addPostConstructInterceptor(postConstructRequestContextActivationFactory, InterceptorOrder.ComponentPostConstruct.REQUEST_SCOPE_ACTIVATING_INTERCEPTOR);

        }

        builder.install();

        configuration.addPostConstructInterceptor(new ImmediateInterceptorFactory(new WeldInjectionContextInterceptor(weldComponentService)), InterceptorOrder.ComponentPostConstruct.WELD_INJECTION_CONTEXT_INTERCEPTOR);
        configuration.addPostConstructInterceptor(new WeldInterceptorInjectionInterceptor.Factory(configuration, interceptorClasses), InterceptorOrder.ComponentPostConstruct.INTERCEPTOR_WELD_INJECTION);
        configuration.addPostConstructInterceptor(new WeldInjectionInterceptor.Factory(configuration), InterceptorOrder.ComponentPostConstruct.COMPONENT_WELD_INJECTION);

    }

    @Override
    public void undeploy(DeploymentUnit context) {

    }
}
