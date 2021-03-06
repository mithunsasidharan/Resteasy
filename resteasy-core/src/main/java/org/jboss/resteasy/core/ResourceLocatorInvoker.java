package org.jboss.resteasy.core;

import org.jboss.resteasy.resteasy_jaxrs.i18n.Messages;
import org.jboss.resteasy.specimpl.BuiltResponse;
import org.jboss.resteasy.specimpl.ResteasyUriInfo;
import org.jboss.resteasy.spi.ApplicationException;
import org.jboss.resteasy.spi.HttpRequest;
import org.jboss.resteasy.spi.HttpResponse;
import org.jboss.resteasy.spi.InjectorFactory;
import org.jboss.resteasy.spi.InternalServerErrorException;
import org.jboss.resteasy.spi.MethodInjector;
import org.jboss.resteasy.spi.ResourceFactory;
import org.jboss.resteasy.spi.ResourceInvoker;
import org.jboss.resteasy.spi.ResteasyProviderFactory;
import org.jboss.resteasy.spi.metadata.ResourceLocator;
import org.jboss.resteasy.spi.statistics.MethodStatisticsLogger;
import org.jboss.resteasy.statistics.StatisticsControllerImpl;
import org.jboss.resteasy.util.GetRestful;

import javax.ws.rs.NotFoundException;
import javax.ws.rs.Produces;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @version $Revision: 1 $
 */
public class ResourceLocatorInvoker implements ResourceInvoker
{
   protected InjectorFactory injector;
   protected MethodInjector methodInjector;
   protected ResourceFactory resource;
   protected ResteasyProviderFactory providerFactory;
   protected ResourceLocator method;
   protected ConcurrentHashMap<Class<?>, LocatorRegistry> cachedSubresources = new ConcurrentHashMap<Class<?>, LocatorRegistry>();
   protected final boolean hasProduces;
   protected MethodStatisticsLogger methodStatisticsLogger;

   public ResourceLocatorInvoker(final ResourceFactory resource, final InjectorFactory injector, final ResteasyProviderFactory providerFactory, final ResourceLocator locator)
   {
      this.resource = resource;
      this.injector = injector;
      this.providerFactory = providerFactory;
      this.method = locator;
      this.methodInjector = injector.createMethodInjector(locator, providerFactory);
      hasProduces = method.getMethod().isAnnotationPresent(Produces.class) || method.getMethod().getClass().isAnnotationPresent(Produces.class);
      methodStatisticsLogger = ((StatisticsControllerImpl)providerFactory.getStatisticsController()).EMPTY;
   }

   @Override
   public boolean hasProduces() {
      return hasProduces;
   }


   protected CompletionStage<Object> createResource(HttpRequest request, HttpResponse response)
   {
      return this.resource.createResource(request, response, providerFactory)
            .thenCompose(resource -> createResource(request, response, resource));

   }

   protected CompletionStage<Object> createResource(HttpRequest request, HttpResponse response, Object locator)
   {
      ResteasyUriInfo uriInfo = (ResteasyUriInfo)request.getUri();
      RuntimeException lastException = (RuntimeException)request.getAttribute(ResourceMethodRegistry.REGISTRY_MATCHING_EXCEPTION);
      return methodInjector.injectArguments(request, response)
         .exceptionally(t -> {
            if(t.getCause() instanceof NotFoundException && lastException != null)
               throw lastException;
            SynchronousDispatcher.rethrow(t);
            // never reached
            return null;
         }).thenApply(args -> {
            try
            {
               uriInfo.pushCurrentResource(locator);
               Object subResource = method.getMethod().invoke(locator, args);
               if (subResource instanceof Class)
               {
                  subResource = this.providerFactory.injectedInstance((Class<?>)subResource);
               }
               return subResource;

            }
            catch (IllegalAccessException e)
            {
               throw new InternalServerErrorException(e);
            }
            catch (InvocationTargetException e)
            {
               throw new ApplicationException(e.getCause());
            }
            catch (SecurityException e)
            {
               throw new ApplicationException(e.getCause());
            }
         });
   }

   public Method getMethod()
   {
      return method.getMethod();
   }

   public CompletionStage<BuiltResponse> invoke(HttpRequest request, HttpResponse response)
   {
      return createResource(request, response)
            .thenCompose(target -> invokeOnTargetObject(request, response, target));
   }

   public CompletionStage<BuiltResponse> invoke(HttpRequest request, HttpResponse response, Object locator)
   {
      return createResource(request, response, locator)
            .thenCompose(target -> invokeOnTargetObject(request, response, target));
   }

   protected CompletionStage<BuiltResponse> invokeOnTargetObject(HttpRequest request, HttpResponse response, Object target)
   {
      if (target == null)
      {
         NotFoundException notFound = new NotFoundException(Messages.MESSAGES.nullSubresource(request.getUri().getAbsolutePath()));
         throw notFound;
      }
      Class<? extends Object> clazz = target.getClass();
      LocatorRegistry registry = cachedSubresources.get(clazz);
      if (registry == null)
      {
         if (!GetRestful.isSubResourceClass(clazz))
         {
            String msg = Messages.MESSAGES.subresourceHasNoJaxRsAnnotations(clazz.getName());
            throw new InternalServerErrorException(msg);
         }
         registry = new LocatorRegistry(clazz, providerFactory);
         cachedSubresources.putIfAbsent(clazz, registry);
      }
      ResourceInvoker invoker = registry.getResourceInvoker(request);
      if (invoker instanceof ResourceLocatorInvoker)
      {
         ResourceLocatorInvoker locator = (ResourceLocatorInvoker) invoker;
         final long timeStamp = methodStatisticsLogger.timestamp();

         try
         {
            return locator.invoke(request, response, target);
         } finally
         {
            methodStatisticsLogger.duration(timeStamp);
         }
      }
      else
      {
         ResourceMethodInvoker method = (ResourceMethodInvoker) invoker;
         return method.invoke(request, response, target);
      }
   }

   public void setMethodStatisticsLogger(MethodStatisticsLogger msLogger) {
      methodStatisticsLogger = msLogger;
   }

   public MethodStatisticsLogger getMethodStatisticsLogger() {
      return methodStatisticsLogger;
   }
}
